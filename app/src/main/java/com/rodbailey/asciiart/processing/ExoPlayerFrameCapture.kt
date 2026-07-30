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
     * getBitmap(scaledWidth, scaledHeight) performs the GPU→CPU copy and scales in one step,
     * replacing both getFrameAtTime() and the subsequent createScaledBitmap() call.
     */
    private fun captureFrameToQueue(currentTimeMs: Long) {
        val textureView = textureViewProvider() ?: return
        if (!textureView.isAvailable) return
        val videoFormat = exoPlayer.videoFormat ?: return
        val scaleFactor = scaleFactorProvider()
        val scaledWidth = (videoFormat.width / scaleFactor).coerceAtLeast(1)
        val scaledHeight = (videoFormat.height / scaleFactor).coerceAtLeast(1)
        val bitmap = textureView.getBitmap(scaledWidth, scaledHeight) ?: return
        val sent = frameQueue.trySend(bitmap).isSuccess
        if (sent) {
            frameState.recordQueuedFrame(currentTimeMs)
        } else {
            bitmap.recycle()  // Channel full — drop frame rather than queue memory pressure
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
                bitmap.recycle()
            }
        }
    }
}