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

/**
 * A right-angle rotation expressed as an affine map into the destination grid:
 * `dstIndex = base + (stepX * x) + (stepY * y)`, where x and y index the *source* grid.
 *
 * This lets the downsample loop write each pixel straight to its rotated position, instead
 * of building the grid upright and then rotating it in a second pass.
 */
internal data class RotationMap(
    val base: Int,
    val stepX: Int,
    val stepY: Int,
    val destWidth: Int,
    val destHeight: Int
)

/**
 * Builds the [RotationMap] for a [width] x [height] source grid rotated clockwise by
 * [rotationDegrees].
 *
 * The mapping matches `Matrix.postRotate()`, which is what the camera frame bitmap was
 * previously rotated with, so the grid lands in the same orientation as before.
 *
 * Rotations that are not a multiple of 90 fall back to identity. CameraX only ever reports
 * 0, 90, 180 or 270 via `imageInfo.rotationDegrees`.
 */
internal fun rotationMap(width: Int, height: Int, rotationDegrees: Int): RotationMap =
    when (((rotationDegrees % 360) + 360) % 360) {
        // (x, y) -> (height - 1 - y, x) in a height x width grid
        90 -> RotationMap(
            base = height - 1, stepX = height, stepY = -1,
            destWidth = height, destHeight = width
        )
        // (x, y) -> (width - 1 - x, height - 1 - y)
        180 -> RotationMap(
            base = (width * height) - 1, stepX = -1, stepY = -width,
            destWidth = width, destHeight = height
        )
        // (x, y) -> (y, width - 1 - x) in a height x width grid
        270 -> RotationMap(
            base = (width - 1) * height, stepX = -height, stepY = 1,
            destWidth = height, destHeight = width
        )
        else -> RotationMap(
            base = 0, stepX = 1, stepY = width,
            destWidth = width, destHeight = height
        )
    }

object ImageProcessor {

    // Pre-allocated buffer for processLumaFrame (camera pipeline, single background thread).
    // Consumed by setPixels() within each call, so reuse is safe. There is no equivalent
    // buffer for the colour grid: that array escapes to Compose state, so it is allocated
    // fresh per frame.
    private var lumaOutputPixels = IntArray(0)

    // Pre-allocated buffers for processBitmap (video pipeline, IO coroutine).
    // Both are consumed (read/written) entirely within each call before the next call can start.
    // colorPixels is NOT pre-allocated here because it escapes as asciiColors to Compose state.
    private var bitmapInputPixels = IntArray(0)
    private var bitmapOutputPixels = IntArray(0)

    /**
     * Downsamples a camera frame into a de-res grid, applying [rotationDegrees] as it goes.
     *
     * The rotation is folded into this loop rather than applied afterwards. Camera sensors
     * are mounted at a fixed angle — typically 90 degrees on phones like the Pixel 3 — so
     * even with the app locked to portrait the sensor output has to be turned to match the
     * display, and CameraX reports the required angle via `imageInfo.rotationDegrees`.
     * Without it the output renders sideways.
     *
     * This used to be a second pass: build the grid upright, then rotate the bitmap with
     * `Bitmap.createBitmap(src, matrix, true)` and the colour grid with a copy loop. Since
     * every pixel is already being written individually, [rotationMap] just changes where
     * each one lands, which removes a full-size bitmap allocation, a rotation blit and a
     * copy pass per frame.
     */
    fun processLumaFrame(
        image: ImageProxy,
        scaleFactor: Int,
        contrastFactor: Float,
        colorEnabled: Boolean,
        rotationDegrees: Int
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
        // Sized exactly, not pooled: this escapes to Compose as asciiColors, so a reused
        // buffer would be overwritten underneath the UI while it is still being drawn.
        val colorPixels: IntArray? = if (colorEnabled) IntArray(size) else null

        val rotation = rotationMap(outputWidth, outputHeight, rotationDegrees)
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (y in 0 until outputHeight) {
            val sourceY = min(sourceHeight - 1, y * step)
            val rowOffset = sourceY * rowStride
            val rotatedRowBase = rotation.base + (rotation.stepY * y)
            for (x in 0 until outputWidth) {
                val sourceX = min(sourceWidth - 1, x * step)
                val lumaIndex = rowOffset + (sourceX * pixelStride)
                val gray = lumaBuffer.get(lumaIndex).toInt() and 0xFF
                val contrastedGray = (((gray - 128f) * contrast) + 128f).coerceIn(0f, 255f)
                val adjustedGray = contrastedGray.roundToInt().coerceIn(0, 255)
                val outIndex = rotatedRowBase + (rotation.stepX * x)
                lumaOutputPixels[outIndex] = (0xFF shl 24) or
                    (adjustedGray shl 16) or
                    (adjustedGray shl 8) or
                    adjustedGray

                if (colorEnabled) {
                    val uvX = sourceX / 2
                    val uvY = sourceY / 2
                    val uIndex = (uvY * uPlane.rowStride) + (uvX * uPlane.pixelStride)
                    val vIndex = (uvY * vPlane.rowStride) + (uvX * vPlane.pixelStride)
                    val uValue = uBuffer.get(uIndex).toInt() and 0xFF
                    val vValue = vBuffer.get(vIndex).toInt() and 0xFF
                    colorPixels?.set(outIndex, yuvToArgb(gray, uValue, vValue))
                }
            }
        }

        val grayscaleBitmap = Bitmap
            .createBitmap(rotation.destWidth, rotation.destHeight, Bitmap.Config.ARGB_8888)
            .apply {
                setPixels(
                    lumaOutputPixels, 0, rotation.destWidth,
                    0, 0, rotation.destWidth, rotation.destHeight
                )
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

        // The colour grid is just the source pixels verbatim, so copy it in one go rather
        // than assigning element-by-element inside the loop below. copyOf(size) also trims
        // the reusable input buffer, which may be longer than this frame needs.
        val colorPixels = if (colorEnabled) bitmapInputPixels.copyOf(size) else null

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
