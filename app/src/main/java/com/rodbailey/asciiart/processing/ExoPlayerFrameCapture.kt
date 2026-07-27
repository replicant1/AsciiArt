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

    private val retriever by lazy { MediaMetadataRetriever().apply { setDataSource(videoUri) } }

    fun startListening() {
        if (isProcessing) return
        isProcessing = true
        
        Log.i(TAG, "startListening() called - video URI: $videoUri")
        
        // Use a polling approach to check for frame updates
        mainThreadHandler.post(frameUpdateChecker)
        
        processingThread = Thread { processFrameQueue() }.apply {
            name = "ExoPlayerFrameProcessor"
            start()
        }
        Log.i(TAG, "Frame listener started (skip rate: $frameSkipRate)")
    }

    fun stopListening() {
        isProcessing = false
        mainThreadHandler.removeCallbacks(frameUpdateChecker)
        processingThread?.join(2000)
        Log.d(TAG, "Frame listener stopped (processed ~$renderedFrameCount frames)")
    }

    fun release() {
        stopListening()
        try {
            retriever.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing retriever", e)
        }
    }

    private val frameUpdateChecker: Runnable = Runnable {
        if (isProcessing && exoPlayer.isPlaying) {
            val currentTimeMs = exoPlayer.currentPosition
            
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
                    // Only process if enough time has passed (avoid re-processing same frame)
                    if (frameTimeMs > lastProcessedTimeMs + 50) {
                        lastProcessedTimeMs = frameTimeMs
                        val frameTimeUs = frameTimeMs * 1000
                        val bitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bitmap != null) {
                            processFrame(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame processing queue", e)
            }
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        val colorEnabled = colorEnabledProvider()
        
        // Create a reduced-size bitmap for ASCII processing
        val scaleFactor = scaleFactorProvider()
        val scaledWidth = (bitmap.width / scaleFactor).coerceAtLeast(1)
        val scaledHeight = (bitmap.height / scaleFactor).coerceAtLeast(1)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // Process through ImageProcessor to get grayscale and colors
        val frameResult = ImageProcessor.processBitmap(
            bitmap = scaledBitmap,
            contrastFactor = contrastFactorProvider(),
            colorEnabled = colorEnabled
        )

        // Generate ASCII text
        val asciiText = when (displayModeProvider()) {
            AsciiDisplayMode.IMAGE_ONLY -> ""
            AsciiDisplayMode.ASCII_OVERLAY,
            AsciiDisplayMode.ASCII_ONLY -> AsciiArt.toAsciiText(
                grayscaleBitmap = frameResult.grayscaleBitmap,
                preset = AsciiCharsetPreset.PRINTABLE
            )
        }

        // Create display bitmap
        val displayBitmap = if (colorEnabled && frameResult.asciiColors != null) {
            bitmapFromColorGrid(
                colors = frameResult.asciiColors,
                width = frameResult.grayscaleBitmap.width,
                height = frameResult.grayscaleBitmap.height
            )
        } else {
            frameResult.grayscaleBitmap
        }

        // Post result back to main thread
        mainThreadHandler.post {
            onFrameProcessed(displayBitmap, asciiText, frameResult.asciiColors)
        }
    }

    private fun bitmapFromColorGrid(colors: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(colors, 0, width, 0, 0, width, height)
        return bitmap
    }
}

