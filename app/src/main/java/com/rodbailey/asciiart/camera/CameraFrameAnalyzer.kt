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
    private val colorEnabledProvider: () -> Boolean,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val onFrameProcessed: (
        bitmap: Bitmap,
        asciiText: String,
        asciiColors: IntArray?
    ) -> Unit
) : ImageAnalysis.Analyzer {

    private val mainThreadHandler = Handler(Looper.getMainLooper())

    override fun analyze(image: ImageProxy) {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val colorEnabled = colorEnabledProvider()
        val frameResult = ImageProcessor.processLumaFrame(
            image = image,
            scaleFactor = scaleFactorProvider(),
            contrastFactor = contrastFactorProvider(),
            colorEnabled = colorEnabled
        )
        image.close()
        val bitmap = frameResult.grayscaleBitmap
        val orientedBitmap = rotateBitmapIfNeeded(bitmap, rotationDegrees)
        val orientedAsciiColors = rotateColorGridIfNeeded(
            colors = frameResult.asciiColors,
            width = bitmap.width,
            height = bitmap.height,
            rotationDegrees = rotationDegrees
        )
        val asciiText = when (displayModeProvider()) {
            AsciiDisplayMode.IMAGE_ONLY -> ""
            AsciiDisplayMode.ASCII_OVERLAY,
            AsciiDisplayMode.ASCII_ONLY -> AsciiArt.toAsciiText(
                grayscaleBitmap = orientedBitmap,
                preset = AsciiCharsetPreset.PRINTABLE
            )
        }
        val displayBitmap = if (colorEnabled && orientedAsciiColors != null) {
            bitmapFromColorGrid(
                colors = orientedAsciiColors,
                width = orientedBitmap.width,
                height = orientedBitmap.height
            )
        } else {
            orientedBitmap
        }

        mainThreadHandler.post {
            onFrameProcessed(displayBitmap, asciiText, orientedAsciiColors)
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

    private fun rotateColorGridIfNeeded(
        colors: IntArray?,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): IntArray? {
        if (colors == null) return null
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return colors

        val rotated = IntArray(colors.size)
        when (normalized) {
            90 -> {
                val newWidth = height
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIndex = (y * width) + x
                        val newX = height - 1 - y
                        val newY = x
                        val dstIndex = (newY * newWidth) + newX
                        rotated[dstIndex] = colors[srcIndex]
                    }
                }
            }
            180 -> {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIndex = (y * width) + x
                        val newX = width - 1 - x
                        val newY = height - 1 - y
                        val dstIndex = (newY * width) + newX
                        rotated[dstIndex] = colors[srcIndex]
                    }
                }
            }
            270 -> {
                val newWidth = height
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIndex = (y * width) + x
                        val newX = y
                        val newY = width - 1 - x
                        val dstIndex = (newY * newWidth) + newX
                        rotated[dstIndex] = colors[srcIndex]
                    }
                }
            }
            else -> return colors
        }
        return rotated
    }

    private fun bitmapFromColorGrid(colors: IntArray, width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(colors, 0, width, 0, 0, width, height)
        }
    }
}
