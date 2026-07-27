package com.rodbailey.asciiart.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rodbailey.asciiart.processing.AsciiArt
import com.rodbailey.asciiart.processing.AsciiCharsetPreset
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import com.rodbailey.asciiart.processing.ImageProcessor

class CameraFrameAnalyzer(
    private val scaleFactorProvider: () -> Int,
    private val contrastFactorProvider: () -> Float,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val onFrameProcessed: (
        bitmap: Bitmap,
        asciiText: String,
        processingMs: Double,
        estimatedFps: Double
    ) -> Unit
) : ImageAnalysis.Analyzer {

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private var lastFrameTimestampNs = 0L

    override fun analyze(image: ImageProxy) {
        val frameStartNs = System.nanoTime()
        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = ImageProcessor.grayscaleDownscaleLumaPlane(
            image = image,
            scaleFactor = scaleFactorProvider(),
            contrastFactor = contrastFactorProvider()
        )
        image.close()
        val orientedBitmap = rotateBitmapIfNeeded(bitmap, rotationDegrees)
        val asciiText = when (displayModeProvider()) {
            AsciiDisplayMode.IMAGE_ONLY -> ""
            AsciiDisplayMode.ASCII_OVERLAY,
            AsciiDisplayMode.ASCII_ONLY -> AsciiArt.toAsciiText(
                grayscaleBitmap = orientedBitmap,
                preset = AsciiCharsetPreset.PRINTABLE
            )
        }

        val frameEndNs = System.nanoTime()
        val processingMs = (frameEndNs - frameStartNs) / 1_000_000.0
        val estimatedFps = if (lastFrameTimestampNs == 0L) {
            0.0
        } else {
            1_000_000_000.0 / (frameEndNs - lastFrameTimestampNs).coerceAtLeast(1L)
        }
        lastFrameTimestampNs = frameEndNs

        mainThreadHandler.post {
            onFrameProcessed(orientedBitmap, asciiText, processingMs, estimatedFps)
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
        bitmap.recycle()
        return rotatedBitmap
    }
}
