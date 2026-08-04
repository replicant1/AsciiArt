package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.roundToInt

enum class AsciiDisplayMode {
    IMAGE,
    ASCII
}

object AsciiArt {
    /**
     * Printable ASCII sorted by visual density, built once on first use.
     *
     * `by lazy` rather than a map keyed by charset: there is only one charset, and its
     * default SYNCHRONIZED mode makes initialisation safe from either pipeline — the
     * camera analysis executor and the video IO dispatcher both reach this.
     */
    private val densitySortedChars: List<Char> by lazy { buildSortedCharset() }

    private const val densityGridWidth = 24
    private const val densityGridHeight = 24
    private const val densityTextSizePx = 20f

    /**
     * Converts a grayscale bitmap to ASCII text.
     *
     * For each pixel in the input bitmap:
     * 1. Reads the grayscale intensity (0–255)
     * 2. Maps intensity to a character from a density-sorted character set
     * 3. Builds a string where each character represents the intensity of its corresponding pixel
     *
     * The sorted character set ensures sparse characters (e.g., space) represent dark areas,
     * and dense characters (e.g., #) represent bright areas, creating a visual ASCII representation
     * of the original grayscale image.
     *
     * @param grayscaleBitmap The input grayscale bitmap (expected to be a small de-res grid, e.g., 32×18 pixels)
     * @return A multi-line ASCII string with dimensions matching the input bitmap
     */
    fun toAsciiText(grayscaleBitmap: Bitmap): String {
        val sortedChars = densitySortedChars
        if (sortedChars.isEmpty()) {
            return ""
        }

        val width = grayscaleBitmap.width
        val height = grayscaleBitmap.height
        val pixels = IntArray(width * height)
        grayscaleBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val textBuilder = StringBuilder((width + 1) * height)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val color = pixels[rowOffset + x]
                val gray = color and 0xFF
                // sortedChars ascends in density, so intensity maps straight to the index:
                // bright pixels reach the dense end ('@', '#'), dark pixels stay near ' '.
                // Do not invert to (255 - gray) — on the black background of ASCII mode that
                // blanks out the bright parts of the scene and inks the dark ones.
                val index = (gray * (sortedChars.size - 1) / 255f).roundToInt()
                    .coerceIn(0, sortedChars.lastIndex)
                textBuilder.append(sortedChars[index])
            }
            if (y < height - 1) {
                textBuilder.append('\n')
            }
        }
        return textBuilder.toString()
    }

    /**
     * Sorts printable ASCII by visual density using a single set of scratch objects
     * (Bitmap, Canvas, Paint, Rect, IntArray) reused across every character — replacing
     * the previous approach that allocated all of these fresh for each of the 95
     * characters in the set.
     *
     * Each density is measured exactly once, up front, and the sort then runs over the
     * pre-computed values. Measuring inside sortedBy {} would redo the draw + getPixels
     * work on every comparison: sortedBy delegates to compareBy, which invokes its
     * selector on both operands of each of the ~n log n comparisons, so a 95-character
     * set cost ~1,200 measurements instead of 95.
     */
    private fun buildSortedCharset(): List<Char> {
        val chars = (32..126).map { it.toChar() }
        val scratchBitmap = Bitmap.createBitmap(densityGridWidth, densityGridHeight, Bitmap.Config.ARGB_8888)
        val scratchCanvas = Canvas(scratchBitmap)
        val scratchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = densityTextSizePx
            typeface = Typeface.MONOSPACE
        }
        val scratchPixels = IntArray(densityGridWidth * densityGridHeight)
        val scratchBounds = Rect()
        val scratchChar = CharArray(1)
        val densities = try {
            FloatArray(chars.size) { index ->
                val char = chars[index]
                if (char == ' ') {
                    0f
                } else {
                    scratchCanvas.drawColor(Color.BLACK)
                    scratchChar[0] = char
                    scratchPaint.getTextBounds(scratchChar, 0, 1, scratchBounds)
                    val x = ((densityGridWidth - scratchBounds.width()) / 2f) - scratchBounds.left
                    val y = ((densityGridHeight - scratchBounds.height()) / 2f) - scratchBounds.top
                    scratchCanvas.drawText(scratchChar, 0, 1, x, y, scratchPaint)
                    scratchBitmap.getPixels(scratchPixels, 0, densityGridWidth, 0, 0, densityGridWidth, densityGridHeight)
                    scratchPixels.count { (it and 0xFF) > 0 }.toFloat() / (densityGridWidth * densityGridHeight)
                }
            }
        } finally {
            scratchBitmap.recycle()
        }
        return chars.indices.sortedBy { densities[it] }.map { chars[it] }
    }
}
