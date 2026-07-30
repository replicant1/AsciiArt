package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.util.Log
import android.view.TextureView
import com.google.android.exoplayer2.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "ExoPlayerFrameListener"

/**
 * Captures frames from ExoPlayer for ASCII processing.
 *
 * Uses coroutines with a producer-consumer pattern:
 * - Main thread: Polls ExoPlayer position at ~60Hz. When a new frame is due, calls
 *   [TextureView.getBitmap] to capture the frame that ExoPlayer has already decoded
 *   and rendered to the TextureView. This is a GPU→CPU copy (~5–15ms) and is orders
 *   of magnitude faster than the previous MediaMetadataRetriever.getFrameAtTime()
 *   approach (~50–200ms per seek-and-decode).
 * - IO thread: Receives captured bitmaps from the channel and runs them through
 *   ImageProcessor and AsciiArt, then posts results back to the main thread.
 * - Channel<Bitmap>: Transfers captured bitmaps between threads. Sized at 2; excess
 *   frames are dropped (trySend) to avoid memory pressure from a slow IO thread.
 */
class ExoPlayerFrameListener(
    private val exoPlayer: ExoPlayer,
    private val textureViewProvider: () -> TextureView?,
    private val scaleFactorProvider: () -> Int,
    private val contrastFactorProvider: () -> Float,
    private val colorEnabledProvider: () -> Boolean,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val frameSkipRate: Int = 2,
    private val onFrameProcessed: (
        bitmap: Bitmap,
        asciiText: String,
        asciiColors: IntArray?
    ) -> Unit
) {

    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private val frameQueue = Channel<Bitmap>(2)
    private var lastDisplayedBitmap: Bitmap? = null
    private val frameState = FrameQueueState()

    /**
     * Pool of pre-allocated capture bitmaps. Eliminates the per-frame Bitmap allocation
     * from TextureView.getBitmap(w, h). A bitmap has exactly one owner at any time:
     * the pool, the channel, or the IO processor — so reuse is always safe.
     * Accessed from both the main thread (capture) and IO thread (post-process return),
     * so ConcurrentLinkedQueue is used for thread safety.
     */
    private val captureBitmapPool = ConcurrentLinkedQueue<Bitmap>()

    fun startListening() {
        scope.launch(Dispatchers.Main) { pollForFrames() }
        scope.launch(Dispatchers.IO) { processQueuedFrames() }
    }

    fun stopListening() {
        scope.cancel()
    }

    fun release() {
        stopListening()
        lastDisplayedBitmap?.recycle()
        lastDisplayedBitmap = null
        var pooled = captureBitmapPool.poll()
        while (pooled != null) {
            pooled.recycle()
            pooled = captureBitmapPool.poll()
        }
    }

    /**
     * Polls ExoPlayer's playback position every ~16ms on the main thread.
     * When a frame is due, captures the current TextureView content via getBitmap()
     * and forwards the bitmap to the processing channel.
     */
    private suspend fun pollForFrames() {
        while (true) {
            if (exoPlayer.isPlaying && displayModeProvider() == AsciiDisplayMode.ASCII) {
                val currentTimeMs = exoPlayer.currentPosition

                if (frameState.detectPlaybackRestart(currentTimeMs)) {
                    Log.d(TAG, "Playback restart detected, resetting frame state")
                }

                if (frameState.shouldQueueFrame(currentTimeMs, frameSkipRate)) {
                    captureFrameToQueue(currentTimeMs)
                }
            }
            delay(16)
        }
    }

    /**
     * Captures the current frame from the TextureView and sends it to the processing queue.
     * We use the TextureView's layout dimensions (not videoFormat.width/height) because
     * getBitmap() captures the screen-rendered result after ExoPlayer has applied its
     * rotation/scaling transform. Using raw codec dimensions would produce a squished or
     * mis-oriented bitmap when the video has rotation metadata (e.g. rotationDegrees=90).
     */
    private fun captureFrameToQueue(currentTimeMs: Long) {
        val textureView = textureViewProvider() ?: return
        if (!textureView.isAvailable) return
        val tvW = textureView.width
        val tvH = textureView.height
        if (tvW <= 0 || tvH <= 0) return
        val scaleFactor = scaleFactorProvider()
        val scaledWidth = (tvW / scaleFactor).coerceAtLeast(1)
        val scaledHeight = (tvH / scaleFactor).coerceAtLeast(1)

        // Reuse a pooled bitmap if one with the correct dimensions is available.
        // This avoids a Bitmap allocation (+ GC pressure) on every frame.
        val pooled = captureBitmapPool.poll()
        val bitmap = when {
            pooled != null && pooled.width == scaledWidth && pooled.height == scaledHeight -> {
                // Overwrite the pooled bitmap in-place — no allocation needed.
                textureView.getBitmap(pooled) ?: run { captureBitmapPool.offer(pooled); return }
            }
            else -> {
                // Wrong size (e.g. scale factor changed) or pool empty — allocate fresh.
                pooled?.recycle()
                textureView.getBitmap(scaledWidth, scaledHeight) ?: return
            }
        }

        val sent = frameQueue.trySend(bitmap).isSuccess
        if (sent) {
            frameState.recordQueuedFrame(currentTimeMs)
        } else {
            // Channel full — return to pool for reuse on the next capture rather than recycling.
            captureBitmapPool.offer(bitmap)
        }
    }

    /**
     * Receives captured bitmaps from the queue and runs them through ImageProcessor
     * and AsciiArt on the IO thread. Results are posted back to the main thread.
     */
    private suspend fun processQueuedFrames() {
        for (bitmap in frameQueue) {
            try {
                val frameResult = ImageProcessor.processBitmap(
                    bitmap = bitmap,
                    contrastFactor = contrastFactorProvider(),
                    colorEnabled = colorEnabledProvider()
                )
                val asciiText = AsciiArt.toAsciiText(
                    grayscaleBitmap = frameResult.grayscaleBitmap,
                    preset = AsciiCharsetPreset.PRINTABLE
                )
                withContext(Dispatchers.Main) {
                    lastDisplayedBitmap?.recycle()
                    lastDisplayedBitmap = frameResult.grayscaleBitmap
                    onFrameProcessed(frameResult.grayscaleBitmap, asciiText, frameResult.asciiColors)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                // Return capture bitmap to pool — processBitmap() has finished reading it.
                // It will be overwritten by the next getBitmap() call, never read stale.
                captureBitmapPool.offer(bitmap)
            }
        }
    }
}