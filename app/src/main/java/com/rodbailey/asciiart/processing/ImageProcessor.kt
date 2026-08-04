package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything one processed frame hands to the UI.
 *
 * The processor picks the picture, rather than returning a grayscale bitmap and letting the
 * UI re-derive what to draw from it. That is what lets each of the four Display x Colour
 * combinations build only what it actually shows: previously a full-size grayscale bitmap
 * was allocated and filled on every frame in all four, and read in only one.
 */
data class FrameProcessingResult(
    /**
     * The image to draw in Image mode — the colour grid when Colour is on, the grayscale
     * grid when it is off. Null in ASCII mode, which draws glyphs and never a bitmap.
     */
    val displayBitmap: Bitmap?,
    /** Glyph rows for ASCII mode; empty in Image mode, which does not convert. */
    val asciiText: String,
    /** Per-cell ARGB tints for the glyphs in ASCII + Colour; null in every other case. */
    val asciiColors: IntArray?,
    /** De-res grid size, after rotation. The UI lays out cells from these. */
    val gridWidth: Int,
    val gridHeight: Int
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

    // Pre-allocated buffers for processLumaFrame (camera pipeline, single background thread).
    // Both are consumed within the call that fills them, so reuse is safe.
    //
    // lumaColorScratch covers Colour + Image mode only, where the colour grid is copied into
    // the display bitmap and never seen again. In ASCII + Colour the grid escapes to Compose
    // state as asciiColors, so that case allocates fresh: a reused buffer would be
    // overwritten underneath the UI while it is still being drawn. That is precisely the
    // bug item 4 fixed, so the two cases are kept apart here rather than sharing one buffer.
    private var lumaOutputPixels = IntArray(0)
    private var lumaColorScratch = IntArray(0)

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
     *
     * [displayMode] is here so the frame can stop at what will actually be looked at — see
     * `grayscaleNeeded` below and the ASCII conversion at the end.
     */
    fun processLumaFrame(
        image: ImageProxy,
        scaleFactor: Int,
        contrastFactor: Float,
        colorEnabled: Boolean,
        displayMode: AsciiDisplayMode,
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

        val imageMode = displayMode == AsciiDisplayMode.IMAGE

        // The grayscale grid has exactly two readers: it is the picture in Image mode with
        // Colour off, and the source of the glyphs in ASCII mode. In Colour + Image the
        // colour grid is the picture, so nothing ever looked at the pixels packed here.
        // The contrast arithmetic below stays either way — yuvToArgb needs it.
        val grayscaleNeeded = !(colorEnabled && imageMode)

        val size = outputWidth * outputHeight
        if (grayscaleNeeded && lumaOutputPixels.size < size) lumaOutputPixels = IntArray(size)
        val colorPixels: IntArray? = when {
            !colorEnabled -> null
            // Copied into the display bitmap and dropped, so the buffer can be reused.
            imageMode -> {
                if (lumaColorScratch.size < size) lumaColorScratch = IntArray(size)
                lumaColorScratch
            }
            // Escapes to Compose as asciiColors, so it has to be its own exactly-sized array.
            else -> IntArray(size)
        }

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
                val contrastAdjustedGray = contrastedGray.roundToInt().coerceIn(0, 255)
                val outIndex = rotatedRowBase + (rotation.stepX * x)
                if (grayscaleNeeded) {
                    lumaOutputPixels[outIndex] = (0xFF shl 24) or
                        (contrastAdjustedGray shl 16) or
                        (contrastAdjustedGray shl 8) or
                        contrastAdjustedGray
                }

                if (colorEnabled) {
                    val uvX = sourceX / 2
                    val uvY = sourceY / 2
                    val uIndex = (uvY * uPlane.rowStride) + (uvX * uPlane.pixelStride)
                    val vIndex = (uvY * vPlane.rowStride) + (uvX * vPlane.pixelStride)
                    val uValue = uBuffer.get(uIndex).toInt() and 0xFF
                    val vValue = vBuffer.get(vIndex).toInt() and 0xFF
                    // Contrast-adjusted luma, not the raw sample. Passing `gray` here left
                    // the Contrast slider with no effect at all in Colour + Image mode,
                    // where the displayed pixels come from this array alone. Chroma (U, V)
                    // is deliberately untouched, so contrast changes brightness separation
                    // without shifting hue or saturation.
                    colorPixels?.set(outIndex, yuvToArgb(contrastAdjustedGray, uValue, vValue))
                }
            }
        }

        return FrameProcessingResult(
            displayBitmap = displayBitmapFor(
                imageMode, colorPixels, lumaOutputPixels, rotation.destWidth, rotation.destHeight
            ),
            asciiText = if (imageMode) {
                ""
            } else {
                AsciiArt.toAsciiText(lumaOutputPixels, rotation.destWidth, rotation.destHeight)
            },
            asciiColors = if (imageMode) null else colorPixels,
            gridWidth = rotation.destWidth,
            gridHeight = rotation.destHeight
        )
    }

    /**
     * Downsamples an already-scaled video frame into the same de-res grid the camera
     * pipeline produces. [displayMode] serves the same purpose as it does there: it keeps
     * the frame from building anything the chosen mode will not look at.
     */
    fun processBitmap(
        bitmap: Bitmap,
        contrastFactor: Float,
        colorEnabled: Boolean,
        displayMode: AsciiDisplayMode
    ): FrameProcessingResult {
        val contrast = contrastFactor.coerceIn(0.2f, 2.0f)
        val width = bitmap.width
        val height = bitmap.height
        val imageMode = displayMode == AsciiDisplayMode.IMAGE
        // See processLumaFrame: nothing reads the grayscale grid in Colour + Image mode.
        // Here that skips the whole conversion loop, since on this path the colour output
        // is the source pixels rather than anything the loop computes.
        val grayscaleNeeded = !(colorEnabled && imageMode)

        val size = width * height
        if (bitmapInputPixels.size < size) bitmapInputPixels = IntArray(size)
        if (grayscaleNeeded && bitmapOutputPixels.size < size) bitmapOutputPixels = IntArray(size)
        bitmap.getPixels(bitmapInputPixels, 0, width, 0, 0, width, height)

        // The colour grid is just the source pixels verbatim, so copy it in one go rather
        // than assigning element-by-element inside the loop below. copyOf(size) also trims
        // the reusable input buffer, which may be longer than this frame needs. Image mode
        // skips even that: there the colour grid goes straight into the display bitmap,
        // which copies it, so the input buffer can be handed over as-is.
        val colorPixels = if (colorEnabled && !imageMode) bitmapInputPixels.copyOf(size) else null

        if (grayscaleNeeded) {
            for (i in 0 until size) {
                val argb = bitmapInputPixels[i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                // Convert RGB to grayscale (luminance)
                val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                val contrastedGray = (((gray - 128f) * contrast) + 128f).coerceIn(0f, 255f)
                val contrastAdjustedGray = contrastedGray.roundToInt().coerceIn(0, 255)

                bitmapOutputPixels[i] = (0xFF shl 24) or
                    (contrastAdjustedGray shl 16) or
                    (contrastAdjustedGray shl 8) or
                    contrastAdjustedGray
            }
        }

        return FrameProcessingResult(
            displayBitmap = displayBitmapFor(
                imageMode,
                colorPixels = if (colorEnabled) bitmapInputPixels else null,
                grayscalePixels = bitmapOutputPixels,
                width = width,
                height = height
            ),
            asciiText = if (imageMode) "" else AsciiArt.toAsciiText(bitmapOutputPixels, width, height),
            asciiColors = colorPixels,
            gridWidth = width,
            gridHeight = height
        )
    }

    /**
     * The one bitmap a frame still needs: the picture Image mode draws.
     *
     * Colour on paints [colorPixels], Colour off the grayscale grid. ASCII mode gets null —
     * its glyphs come from the pixel array and its layout from the grid dimensions, so a
     * bitmap there would be built for no reader at all.
     *
     * Either array may be longer than `width * height`, since the callers pass reusable
     * buffers that are only ever grown. `createBitmap` and `setPixels` both need the array
     * to be *at least* that large and ignore the tail.
     */
    private fun displayBitmapFor(
        imageMode: Boolean,
        colorPixels: IntArray?,
        grayscalePixels: IntArray,
        width: Int,
        height: Int
    ): Bitmap? = when {
        !imageMode -> null
        colorPixels != null ->
            Bitmap.createBitmap(colorPixels, width, height, Bitmap.Config.ARGB_8888)
        else -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(grayscalePixels, 0, width, 0, 0, width, height)
        }
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
