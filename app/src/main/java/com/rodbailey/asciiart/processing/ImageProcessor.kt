package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class FrameProcessingResult(
    val grayscaleBitmap: Bitmap,
    val asciiColors: IntArray?
)

object ImageProcessor {

    // Pre-allocated buffers for processLumaFrame (camera pipeline, single background thread).
    // outputPixels is consumed by setPixels() within each call; lumaColorPixels is safe because
    // rotateColorGridIfNeeded() always copies into a fresh IntArray before the buffer is reused.
    private var lumaOutputPixels = IntArray(0)
    private var lumaColorPixels = IntArray(0)

    // Pre-allocated buffers for processBitmap (video pipeline, IO coroutine).
    // Both are consumed (read/written) entirely within each call before the next call can start.
    // colorPixels is NOT pre-allocated here because it escapes as asciiColors to Compose state.
    private var bitmapInputPixels = IntArray(0)
    private var bitmapOutputPixels = IntArray(0)

    fun processLumaFrame(
        image: ImageProxy,
        scaleFactor: Int,
        contrastFactor: Float,
        colorEnabled: Boolean
    ): FrameProcessingResult {
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

        val size = outputWidth * outputHeight
        if (lumaOutputPixels.size < size) lumaOutputPixels = IntArray(size)
        val colorPixels: IntArray? = if (colorEnabled) {
            if (lumaColorPixels.size < size) lumaColorPixels = IntArray(size)
            lumaColorPixels
        } else null
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
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
                lumaOutputPixels[outIndex] = (0xFF shl 24) or
                    (adjustedGray shl 16) or
                    (adjustedGray shl 8) or
                    adjustedGray

                if (colorEnabled) {
                    val yValue = gray
                    val uvX = sourceX / 2
                    val uvY = sourceY / 2
                    val uIndex = (uvY * uPlane.rowStride) + (uvX * uPlane.pixelStride)
                    val vIndex = (uvY * vPlane.rowStride) + (uvX * vPlane.pixelStride)
                    val uValue = uBuffer.get(uIndex).toInt() and 0xFF
                    val vValue = vBuffer.get(vIndex).toInt() and 0xFF
                    val color = yuvToArgb(yValue, uValue, vValue)
                    colorPixels?.set(outIndex, color)
                }
                outIndex++
            }
        }

        val grayscaleBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(lumaOutputPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        }
        return FrameProcessingResult(
            grayscaleBitmap = grayscaleBitmap,
            asciiColors = colorPixels
        )
    }

    fun processBitmap(
        bitmap: Bitmap,
        contrastFactor: Float,
        colorEnabled: Boolean
    ): FrameProcessingResult {
        val contrast = contrastFactor.coerceIn(0.2f, 2.0f)
        val width = bitmap.width
        val height = bitmap.height

        val size = width * height
        if (bitmapInputPixels.size < size) bitmapInputPixels = IntArray(size)
        if (bitmapOutputPixels.size < size) bitmapOutputPixels = IntArray(size)
        bitmap.getPixels(bitmapInputPixels, 0, width, 0, 0, width, height)

        val colorPixels = if (colorEnabled) IntArray(size) else null

        for (i in 0 until size) {
            val argb = bitmapInputPixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            
            // Convert RGB to grayscale (luminance)
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            val contrastedGray = (((gray - 128f) * contrast) + 128f).coerceIn(0f, 255f)
            val adjustedGray = contrastedGray.roundToInt().coerceIn(0, 255)

            bitmapOutputPixels[i] = (0xFF shl 24) or
                (adjustedGray shl 16) or
                (adjustedGray shl 8) or
                adjustedGray

            if (colorEnabled) {
                colorPixels?.set(i, argb)
            }
        }

        val grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(bitmapOutputPixels, 0, width, 0, 0, width, height)
        }
        return FrameProcessingResult(
            grayscaleBitmap = grayscaleBitmap,
            asciiColors = colorPixels
        )
    }

    private fun yuvToArgb(yValue: Int, uValue: Int, vValue: Int): Int {
        val c = (yValue - 16).coerceAtLeast(0)
        val d = uValue - 128
        val e = vValue - 128

        val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)

        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
