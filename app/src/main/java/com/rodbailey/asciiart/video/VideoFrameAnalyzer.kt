package com.rodbailey.asciiart.video

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rodbailey.asciiart.processing.AsciiArt
import com.rodbailey.asciiart.processing.AsciiCharsetPreset
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import com.rodbailey.asciiart.processing.ImageProcessor
import kotlin.math.max
import kotlin.math.min

class VideoFrameAnalyzer(
    private val scaleFactorProvider: () -> Int,
    private val contrastFactorProvider: () -> Float,
    private val colorEnabledProvider: () -> Boolean,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val onFrameProcessed: (
        bitmap: Bitmap,
        asciiText: String,
        asciiColors: IntArray?
    ) -> Unit
) {
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    fun processFrame(bitmap: Bitmap, videoRotation: Int) {
        val rotatedBitmap = rotateBitmapIfNeeded(bitmap, videoRotation)
        
        // Convert bitmap to YUV format for processing
        // For now, we'll process the bitmap directly through our image processor
        val colorEnabled = colorEnabledProvider()
        val scaleFactor = scaleFactorProvider()
        val contrastFactor = contrastFactorProvider()
        
        // Create a downsampled version for processing
        val step = scaleFactor.coerceAtLeast(1)
        val outputWidth = max(1, rotatedBitmap.width / step)
        val outputHeight = max(1, rotatedBitmap.height / step)
        
        val outputPixels = IntArray(outputWidth * outputHeight)
        val colorPixels = if (colorEnabled) IntArray(outputWidth * outputHeight) else null
        
        for (y in 0 until outputHeight) {
            val sourceY = min(rotatedBitmap.height - 1, y * step)
            for (x in 0 until outputWidth) {
                val sourceX = min(rotatedBitmap.width - 1, x * step)
                val srcPixel = rotatedBitmap.getPixel(sourceX, sourceY)
                
                // Extract RGB
                val r = (srcPixel shr 16) and 0xFF
                val g = (srcPixel shr 8) and 0xFF
                val b = srcPixel and 0xFF
                
                // Convert to grayscale
                val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                
                // Apply contrast
                val contrast = contrastFactor.coerceIn(0.2f, 2.0f)
                val contrastedGray = (((gray - 128f) * contrast) + 128f).coerceIn(0f, 255f).toInt()
                
                val outIndex = (y * outputWidth) + x
                outputPixels[outIndex] = (0xFF shl 24) or
                    (contrastedGray shl 16) or
                    (contrastedGray shl 8) or
                    contrastedGray
                
                if (colorEnabled) {
                    colorPixels?.set(outIndex, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
        }
        
        val grayscaleBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(outputPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        }
        
        val asciiText = when (displayModeProvider()) {
            AsciiDisplayMode.IMAGE_ONLY -> ""
            AsciiDisplayMode.ASCII_OVERLAY,
            AsciiDisplayMode.ASCII_ONLY -> AsciiArt.toAsciiText(
                grayscaleBitmap = grayscaleBitmap,
                preset = AsciiCharsetPreset.PRINTABLE
            )
        }
        
        val displayBitmap = if (colorEnabled && colorPixels != null) {
            bitmapFromColorGrid(colorPixels, outputWidth, outputHeight)
        } else {
            grayscaleBitmap
        }
        
        mainThreadHandler.post {
            onFrameProcessed(displayBitmap, asciiText, colorPixels)
        }
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return bitmap
        }
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        return rotatedBitmap
    }

    private fun bitmapFromColorGrid(colors: IntArray, width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(colors, 0, width, 0, 0, width, height)
        }
    }

    companion object {
        private const val TAG = "VideoFrameAnalyzer"
    }
}
