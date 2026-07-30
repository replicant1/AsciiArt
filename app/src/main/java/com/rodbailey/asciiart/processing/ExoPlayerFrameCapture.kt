package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.video.VideoRendererEventListener
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
 * Listens to ExoPlayer's rendering events and extracts frames for ASCII processing.
 * Uses coroutines to bridge main thread (ExoPlayer polling) and background processing thread.
 *
 * The producer-consumer pattern:
 * - Main thread: Polls ExoPlayer position at ~60Hz, detects when new frames are needed
 * - IO thread: Extracts frames from MediaMetadataRetriever, processes to ASCII
 * - Channel: Transfers frame timestamps between threads safely
 */
class ExoPlayerFrameListener(
    private val exoPlayer: ExoPlayer,
    private val videoUri: String,
    private val scaleFactorProvider: () -> Int,
    private val contrastFactorProvider: () -> Float,
    private val colorEnabledProvider: () -> Boolean,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val frameSkipRate: Int = 2,  // Process every Nth frame opportunity
    private val onFrameProcessed: (
        bitmap: Bitmap,
        asciiText: String,
        asciiColors: IntArray?
    ) -> Unit
) {

    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private val frameQueue = Channel<Long>(10)  // Max 10 pending frame times
    private var lastDisplayedBitmap: Bitmap? = null  // Track bitmap for recycling

    private val retriever by lazy { MediaMetadataRetriever().apply { setDataSource(videoUri) } }
    
    /**
     * Tracks frame timing state to manage queueing and processing.
     * Keeps playback position history separate from processing history.
     */
    private val frameState = FrameQueueState()

    fun startListening() {
        scope.launch(Dispatchers.Main) {
            pollForFrames()
        }
        
        scope.launch(Dispatchers.IO) {
            processQueuedFrames()
        }
    }

    fun stopListening() {
        scope.cancel()
    }

    fun release() {
        stopListening()
        try {
            lastDisplayedBitmap?.recycle()
            lastDisplayedBitmap = null
            retriever.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing retriever", e)
        }
    }

    /**
     * Polls ExoPlayer's playback position every ~16ms on the main thread.
     * MUST run on main thread because ExoPlayer's API is designed for main thread access.
     *
     * Responsibilities:
     * - Detect when playback position has advanced enough to warrant frame extraction
     * - Implement frame skipping to control processing rate (e.g., process every 2nd opportunity)
     * - Detect playback restarts (seeking backwards) and reset state
     */
    private suspend fun pollForFrames() {
        while (true) {
            if (exoPlayer.isPlaying) {
                val currentTimeMs = exoPlayer.currentPosition
                
                // Detect seek backward or video loop (position jumped back by >1 second)
                if (frameState.detectPlaybackRestart(currentTimeMs)) {
                    Log.d(TAG, "Playback restart detected, resetting frame state")
                }
                
                // Queue frame if position advanced significantly and frame skip count allows
                if (frameState.shouldQueueFrame(currentTimeMs, frameSkipRate)) {
                    try {
                        frameQueue.send(currentTimeMs)
                        frameState.recordQueuedFrame(currentTimeMs)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error queuing frame", e)
                    }
                }
            }
            delay(16)  // ~60Hz polling
        }
    }

    /**
     * Processes frames from the queue on a background thread (Dispatchers.IO).
     * Decoupled from polling to prevent UI thread blocking during heavy frame extraction.
     *
     * Responsibilities:
     * - Extract frame bitmap from video at the requested timestamp
     * - Scale bitmap down for ASCII processing
     * - Generate ASCII text and color grid
     * - Post results back to main thread for UI update
     */
    private suspend fun processQueuedFrames() {
        for (frameTimeMs in frameQueue) {
            try {
                // Only process if this is a new frame OR a re-process of the current frame
                // Re-processing happens when user changes parameters (scale, contrast, etc)
                if (frameState.shouldProcessFrame(frameTimeMs)) {
                    val frameTimeUs = frameTimeMs * 1000
                    val bitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        processFrame(bitmap, frameTimeMs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            }
        }
    }

    private suspend fun processFrame(bitmap: Bitmap, frameTimeMs: Long) {
        // Create a reduced-size bitmap for ASCII processing
        val scaleFactor = scaleFactorProvider()
        val scaledWidth = (bitmap.width / scaleFactor).coerceAtLeast(1)
        val scaledHeight = (bitmap.height / scaleFactor).coerceAtLeast(1)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        try {
            // Process through ImageProcessor to get grayscale and colors
            val frameResult = ImageProcessor.processBitmap(
                bitmap = scaledBitmap,
                contrastFactor = contrastFactorProvider(),
                colorEnabled = colorEnabledProvider()
            )

            // Generate ASCII text (always, regardless of display mode)
            val asciiText = AsciiArt.toAsciiText(
                grayscaleBitmap = frameResult.grayscaleBitmap,
                preset = AsciiCharsetPreset.PRINTABLE
            )

            // Always use grayscale bitmap as display - AsciiGridPreview will overlay colors if enabled
            val displayBitmap = frameResult.grayscaleBitmap

            // Post result back to main thread for UI update
            withContext(Dispatchers.Main) {
                // Recycle the previously displayed bitmap to prevent memory leak
                lastDisplayedBitmap?.recycle()
                lastDisplayedBitmap = displayBitmap
                onFrameProcessed(displayBitmap, asciiText, frameResult.asciiColors)
            }
        } finally {
            // Clean up the scaled bitmap (it's no longer needed after processing)
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
        }
    }

    private fun bitmapFromColorGrid(colors: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(colors, 0, width, 0, 0, width, height)
        return bitmap
    }
}