package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.video.VideoRendererEventListener
import java.util.concurrent.LinkedBlockingQueue

private const val TAG = "ExoPlayerFrameListener"

/**
 * Listens to ExoPlayer's rendering events and extracts frames for ASCII processing.
 * Uses a queue to bridge main thread (ExoPlayer) and background processing thread.
 */
class ExoPlayerFrameListener(
    private val exoPlayer: ExoPlayer,
    private val videoUri: String,
    private val scaleFactorProvider: () -> Int,
    private val contrastFactorProvider: () -> Float,
    private val colorEnabledProvider: () -> Boolean,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val frameSkipRate: Int = 2,  // Process every Nth rendered frame
    private val onFrameProcessed: (
        bitmap: Bitmap,
        asciiText: String,
        asciiColors: IntArray?
    ) -> Unit
) {

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private var processingThread: Thread? = null
    private var isProcessing = false
    private val frameQueue = LinkedBlockingQueue<Long>(10)  // Max 10 pending frame times
    private var lastProcessedTimeMs = 0L
    private var renderedFrameCount = 0
    private var lastQueuedTimeMs = 0L
    private var lastDisplayedBitmap: Bitmap? = null  // Track bitmap for recycling

    private val retriever by lazy { MediaMetadataRetriever().apply { setDataSource(videoUri) } }

    fun startListening() {
        if (isProcessing) return
        isProcessing = true
        
        // Use a polling approach to check for frame updates
        mainThreadHandler.post(frameUpdateChecker)
        
        processingThread = Thread { processFrameQueue() }.apply {
            name = "ExoPlayerFrameProcessor"
            start()
        }
    }

    fun stopListening() {
        isProcessing = false
        mainThreadHandler.removeCallbacks(frameUpdateChecker)
        processingThread?.join(2000)
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

    private val frameUpdateChecker: Runnable = Runnable {
        if (isProcessing && exoPlayer.isPlaying) {
            val currentTimeMs = exoPlayer.currentPosition
            
            // Detect playback restart (position reset)
            if (currentTimeMs < lastQueuedTimeMs - 1000) {
                lastQueuedTimeMs = 0
                lastProcessedTimeMs = 0
                renderedFrameCount = 0
            }
            
            // Queue frame if time has advanced significantly
            if (currentTimeMs > lastQueuedTimeMs + 30) {  // At least 30ms between frames
                renderedFrameCount++
                if (renderedFrameCount % frameSkipRate == 0) {
                    try {
                        frameQueue.offer(currentTimeMs)  // Non-blocking offer
                        lastQueuedTimeMs = currentTimeMs
                    } catch (e: Exception) {
                        Log.e(TAG, "Error queuing frame", e)
                    }
                }
            }
        }
        
        if (isProcessing) {
            mainThreadHandler.postDelayed(frameUpdateChecker, 16)  // ~60Hz polling
        }
    }

    private fun processFrameQueue() {
        while (isProcessing) {
            try {
                // Wait for next frame time (max 100ms to check isProcessing)
                val frameTimeMs = frameQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frameTimeMs != null) {
                    // Allow re-processing of same frame (for parameter changes) OR new frames with enough time passed
                    val shouldProcess = (frameTimeMs == lastProcessedTimeMs) || (frameTimeMs > lastProcessedTimeMs + 50)
                    if (shouldProcess) {
                        lastProcessedTimeMs = frameTimeMs
                        val frameTimeUs = frameTimeMs * 1000
                        val bitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bitmap != null) {
                            processFrame(bitmap, frameTimeMs)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame processing queue", e)
            }
        }
    }

    private fun processFrame(bitmap: Bitmap, frameTimeMs: Long) {
        val colorEnabled = colorEnabledProvider()
        
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

            // Post result back to main thread
            mainThreadHandler.post {
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

