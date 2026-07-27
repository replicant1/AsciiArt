package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer

private const val TAG = "ExoPlayerFrameCapture"

/**
 * Captures frames from ExoPlayer playback by periodically extracting frames
 * at the current playback position using MediaMetadataRetriever.
 * This runs on a background thread to avoid blocking playback.
 */
class ExoPlayerFrameCapture(
    private val exoPlayer: ExoPlayer,
    private val videoUri: String,
    private val scaleFactorProvider: () -> Int,
    private val contrastFactorProvider: () -> Float,
    private val colorEnabledProvider: () -> Boolean,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val captureInterval: Long = 100,  // Capture every 100ms
    private val onFrameProcessed: (
        bitmap: Bitmap,
        asciiText: String,
        asciiColors: IntArray?
    ) -> Unit
) {

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private var captureThread: Thread? = null
    private var isCapturing = false
    private var lastCapturedTimeUs = 0L
    private val captureIntervalUs = captureInterval * 1000

    private val retriever by lazy { MediaMetadataRetriever().apply { setDataSource(videoUri) } }

    fun startCapture() {
        if (isCapturing) return
        isCapturing = true
        captureThread = Thread { captureFramesLoop() }.apply {
            name = "ExoPlayerFrameCapture"
            start()
        }
        Log.d(TAG, "Frame capture started (interval: ${captureInterval}ms)")
    }

    fun stopCapture() {
        isCapturing = false
        captureThread?.join(2000)
        Log.d(TAG, "Frame capture stopped")
    }

    fun release() {
        stopCapture()
        try {
            retriever.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing retriever", e)
        }
    }

    private fun captureFramesLoop() {
        while (isCapturing) {
            try {
                if (exoPlayer.isPlaying) {
                    val currentTimeUs = exoPlayer.currentPosition * 1000
                    
                    // Only capture if enough time has passed since last capture
                    if (currentTimeUs - lastCapturedTimeUs >= captureIntervalUs) {
                        val bitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bitmap != null) {
                            lastCapturedTimeUs = currentTimeUs
                            processFrame(bitmap)
                        }
                    }
                }
                Thread.sleep(50)  // Small sleep to avoid busy-waiting
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame capture loop", e)
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
