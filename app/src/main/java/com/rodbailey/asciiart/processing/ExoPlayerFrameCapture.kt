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
                colorEnabled = true  // Always generate colors for flexibility
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

    /**
     * Encapsulates the state tracking for frame queueing and processing.
     * Keeps frame timing logic separate and testable.
     */
    private class FrameQueueState {
        /**
         * Last playback position we queued for extraction.
         * Used to ensure minimum 30ms between frame extraction requests.
         */
        private var lastQueuedTimeMs = 0L

        /**
         * Counter for frame skip implementation.
         * Incremented for each frame opportunity; only process when counter % frameSkipRate == 0
         */
        private var frameSkipCounter = 0

        /**
         * Last playback position we actually processed.
         * Used to prevent re-processing the same frame (unless explicitly re-requested due to parameter changes).
         */
        private var lastProcessedTimeMs = 0L

        /**
         * Detects when playback has jumped backward (user seeked or video looped).
         * When detected, resets all tracking state to start fresh.
         *
         * @return true if restart was detected and state was reset
         */
        fun detectPlaybackRestart(currentTimeMs: Long): Boolean {
            // If position jumped back by >1 second, it's a restart
            if (currentTimeMs < lastQueuedTimeMs - 1000) {
                lastQueuedTimeMs = 0
                lastProcessedTimeMs = 0
                frameSkipCounter = 0
                return true
            }
            return false
        }

        /**
         * Determines if the current playback position warrants queuing a frame for extraction.
         *
         * Logic:
         * 1. Position must have advanced by at least 30ms since last queued frame
         * 2. Frame skip counter must be at the right value (e.g., every 2nd opportunity)
         *
         * @param currentTimeMs playback position
         * @param skipRate process every Nth frame opportunity (e.g., 2 = process every 2nd)
         * @return true if frame should be queued
         */
        fun shouldQueueFrame(currentTimeMs: Long, skipRate: Int): Boolean {
            // Check if enough time has passed since last queued frame (30ms minimum)
            if (currentTimeMs <= lastQueuedTimeMs + 30) {
                return false
            }

            // Increment skip counter and check if we should process this one
            frameSkipCounter++
            return frameSkipCounter % skipRate == 0
        }

        /**
         * Records that a frame time has been queued for extraction.
         * Called after successfully sending frame time to the queue.
         */
        fun recordQueuedFrame(frameTimeMs: Long) {
            lastQueuedTimeMs = frameTimeMs
        }

        /**
         * Determines if a frame should be processed.
         *
         * Logic:
         * - Allow re-processing of the same frame (for parameter changes like scale/contrast)
         * - Allow processing of new frames if 50ms+ has passed since last processing
         *   (prevents processing rapid duplicates if extraction is slow)
         *
         * @param frameTimeMs timestamp of frame to potentially process
         * @return true if frame should be processed
         */
        fun shouldProcessFrame(frameTimeMs: Long): Boolean {
            val isSameFrame = frameTimeMs == lastProcessedTimeMs
            val isNewFrameAfterDelay = frameTimeMs > lastProcessedTimeMs + 50
            return isSameFrame || isNewFrameAfterDelay
        }

        /**
         * Records that a frame has been processed.
         * Called after extraction and ASCII generation complete.
         */
        fun recordProcessedFrame(frameTimeMs: Long) {
            lastProcessedTimeMs = frameTimeMs
        }
    }
}