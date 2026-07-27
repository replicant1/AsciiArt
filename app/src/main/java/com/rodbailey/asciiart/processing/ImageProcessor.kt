package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageProcessor {
    fun grayscaleDownscaleLumaPlane(
        image: ImageProxy,
        scaleFactor: Int,
        contrastFactor: Float
    ): Bitmap {
        val step = scaleFactor.coerceAtLeast(1)
        val contrast = contrastFactor.coerceIn(0.2f, 2.0f)
        val sourceWidth = image.width
        val sourceHeight = image.height
        val outputWidth = max(1, sourceWidth / step)
        val outputHeight = max(1, sourceHeight / step)

        val lumaPlane = image.planes[0]
        val lumaBuffer = lumaPlane.buffer
        val rowStride = lumaPlane.rowStride
        val pixelStride = lumaPlane.pixelStride

        val outputPixels = IntArray(outputWidth * outputHeight)
        var outIndex = 0

        for (y in 0 until outputHeight) {
            val sourceY = min(sourceHeight - 1, y * step)
            val rowOffset = sourceY * rowStride
            for (x in 0 until outputWidth) {
                val sourceX = min(sourceWidth - 1, x * step)
                val lumaIndex = rowOffset + (sourceX * pixelStride)
                val gray = lumaBuffer.get(lumaIndex).toInt() and 0xFF
                val contrastedGray = (((gray - 128f) * contrast) + 128f).coerceIn(0f, 255f)
                val adjustedGray = contrastedGray.roundToInt().coerceIn(0, 255)
                outputPixels[outIndex] = (0xFF shl 24) or
                    (adjustedGray shl 16) or
                    (adjustedGray shl 8) or
                    adjustedGray
                outIndex++
            }
        }

        return Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(outputPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        }
    }
}
