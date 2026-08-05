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
    /**
     * Per-cell ARGB tints for the glyphs in ASCII + Colour; null in every other case.
     * A [PixelGrid] rather than a raw array because this is the one thing here that outlives
     * the call and is held in Compose state — see [PixelBuffer.freeze].
     */
    val asciiColors: PixelGrid?,
    /**
     * De-res grid size, after rotation. Carried separately from [asciiColors] because the UI
     * needs it in every mode and the colour grid exists in only one of the four.
     */
    val gridSize: GridSize
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

    // Reusable buffers, one pair per tab — Live Camera and Video File. The camera analysis
    // executor and the video IO dispatcher each run one call at a time, so a buffer is only
    // ever touched by the call that prepared it, and the two pairs never meet.
    // Nothing here escapes; what the UI keeps comes from freeze().
    private val liveCameraGrayscale = PixelBuffer()
    private val liveCameraColor = PixelBuffer()
    private val videoFileInput = PixelBuffer()
    private val videoFileGrayscale = PixelBuffer()

    /**
     * Downsamples a Live Camera frame into a de-res grid, applying [rotationDegrees] as it
     * goes.
     *
     * Brightness comes from the luma (Y) plane alone — `image.planes[0]` — which is why the
     * loop below reads a single byte per cell rather than converting YUV. Chroma is sampled
     * only when Colour is on. This was once called `processLumaFrame` for that reason; the
     * name now says which tab it serves, since that is what a reader needs to place it.
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
    fun processLiveCameraFrame(
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

        val rotation = rotationMap(outputWidth, outputHeight, rotationDegrees)
        val gridSize = GridSize(rotation.destWidth, rotation.destHeight)
        // Both modes write the colour grid into the same buffer. Only ASCII mode hands it on,
        // and it does that by freezing, so the "does this escape?" question is answered once
        // at the return rather than by picking an allocation strategy up here.
        if (grayscaleNeeded) liveCameraGrayscale.prepare(gridSize)
        if (colorEnabled) liveCameraColor.prepare(gridSize)

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
                    liveCameraGrayscale[outIndex] = (0xFF shl 24) or
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
                    liveCameraColor[outIndex] = yuvToArgb(contrastAdjustedGray, uValue, vValue)
                }
            }
        }

        return assembleResult(
            imageMode = imageMode,
            colorEnabled = colorEnabled,
            grayscale = liveCameraGrayscale,
            colour = liveCameraColor,
            gridSize = gridSize
        )
    }

    /**
     * Downsamples an already-scaled Video File frame into the same de-res grid
     * [processLiveCameraFrame] produces. [displayMode] serves the same purpose as it does
     * there: it keeps the frame from building anything the chosen mode will not look at.
     *
     * The [bitmap] arrives from `TextureView.getBitmap`, already scaled down by the Scale
     * factor, so unlike the camera path there is no subsampling step — every source pixel is
     * one cell. Colour output is the source pixels verbatim, which is why contrast reaches
     * only the grayscale grid on this path.
     */
    fun processVideoFileFrame(
        bitmap: Bitmap,
        contrastFactor: Float,
        colorEnabled: Boolean,
        displayMode: AsciiDisplayMode
    ): FrameProcessingResult {
        val contrast = contrastFactor.coerceIn(0.2f, 2.0f)
        val width = bitmap.width
        val height = bitmap.height
        val imageMode = displayMode == AsciiDisplayMode.IMAGE
        // See processLiveCameraFrame: nothing reads the grayscale grid in Colour + Image mode.
        // Here that skips the whole conversion loop, since on this path the colour output
        // is the source pixels rather than anything the loop computes.
        val grayscaleNeeded = !(colorEnabled && imageMode)

        val size = width * height
        val gridSize = GridSize(width, height)
        videoFileInput.prepare(gridSize)
        if (grayscaleNeeded) videoFileGrayscale.prepare(gridSize)
        bitmap.getPixels(videoFileInput.pixelsForPlatformApi, 0, width, 0, 0, width, height)

        if (grayscaleNeeded) {
            for (i in 0 until size) {
                val argb = videoFileInput[i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                // Convert RGB to grayscale (luminance)
                val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                val contrastedGray = (((gray - 128f) * contrast) + 128f).coerceIn(0f, 255f)
                val contrastAdjustedGray = contrastedGray.roundToInt().coerceIn(0, 255)

                videoFileGrayscale[i] = (0xFF shl 24) or
                    (contrastAdjustedGray shl 16) or
                    (contrastAdjustedGray shl 8) or
                    contrastAdjustedGray
            }
        }

        return assembleResult(
            imageMode = imageMode,
            colorEnabled = colorEnabled,
            grayscale = videoFileGrayscale,
            // The colour output on this path is the source pixels verbatim, so the input
            // buffer is also the colour grid.
            colour = videoFileInput,
            gridSize = gridSize
        )
    }

    /**
     * Turns a pipeline's filled buffers into the frame the UI receives. Both pipelines reach
     * the Display x Colour rules with the same answers, so they live here rather than twice.
     *
     * [colour] is read only when Colour is on, so callers may pass a buffer they never
     * prepared.
     *
     * The per-pixel work stays in the callers. Those loops differ on five axes and run
     * ~972,000 times a second; sharing them would need a per-pixel lambda or interface, the
     * shape item 13 measured at 4.4 ms per frame.
     */
    private fun assembleResult(
        imageMode: Boolean,
        colorEnabled: Boolean,
        grayscale: PixelBuffer,
        colour: PixelBuffer,
        gridSize: GridSize
    ): FrameProcessingResult = FrameProcessingResult(
        displayBitmap = displayBitmapFor(
            imageMode,
            colorPixels = if (colorEnabled) colour else null,
            grayscalePixels = grayscale,
            gridSize = gridSize
        ),
        asciiText = if (imageMode) {
            ""
        } else {
            AsciiArt.toAsciiText(grayscale.pixelsForPlatformApi, gridSize)
        },
        asciiColors = if (imageMode || !colorEnabled) null else colour.freeze(),
        gridSize = gridSize
    )

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
        colorPixels: PixelBuffer?,
        grayscalePixels: PixelBuffer,
        gridSize: GridSize
    ): Bitmap? {
        val (width, height) = gridSize
        return when {
            !imageMode -> null
            colorPixels != null ->
                Bitmap.createBitmap(colorPixels.pixelsForPlatformApi, width, height, Bitmap.Config.ARGB_8888)
            else -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(grayscalePixels.pixelsForPlatformApi, 0, width, 0, 0, width, height)
            }
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
