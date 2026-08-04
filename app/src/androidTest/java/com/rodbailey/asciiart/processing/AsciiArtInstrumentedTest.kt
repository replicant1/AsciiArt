package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that [AsciiArt] orders its character set by ascending visual density.
 *
 * These are instrumented tests because the density measurement draws each glyph with a
 * real [Canvas] and [Paint] onto a real [Bitmap] — none of which have usable behaviour
 * under the local JVM's stubbed android.jar.
 *
 * [AsciiArt.buildSortedCharset] is private, but the sorted charset is fully recoverable
 * through the public [AsciiArt.toAsciiText]: that function maps pixel intensity to a
 * charset index monotonically and without skipping indices, so feeding it every possible
 * intensity yields every character in sorted order. See [recoverSortedCharset].
 */
@RunWith(AndroidJUnit4::class)
class AsciiArtInstrumentedTest {

    @Test
    fun sortedCharset_isAPermutationOfPrintableAscii() {
        val sorted = recoverSortedCharset()

        assertEquals(
            "charset should contain every printable ASCII character exactly once",
            (32..126).map { it.toChar() }.toSet(),
            sorted.toSet()
        )
        assertEquals("charset should contain no duplicates", 95, sorted.size)
    }

    @Test
    fun sortedCharset_startsWithSpace() {
        assertEquals(
            "space is the least dense glyph and must sort first",
            ' ',
            recoverSortedCharset().first()
        )
    }

    /**
     * The core invariant: the charset is sorted by ascending ink coverage. Coverage is
     * re-measured here independently of the production sort, so a broken comparator or a
     * mis-indexed sort surfaces as an out-of-order pair.
     */
    @Test
    fun sortedCharset_isOrderedByAscendingInkCoverage() {
        val sorted = recoverSortedCharset()
        val coverage = sorted.map { it to inkCoverage(it) }

        for (i in 1 until coverage.size) {
            val (previousChar, previousCoverage) = coverage[i - 1]
            val (currentChar, currentCoverage) = coverage[i]
            assertTrue(
                "'$previousChar' (coverage $previousCoverage) sorts before '$currentChar' " +
                    "(coverage $currentCoverage) but is denser",
                currentCoverage >= previousCoverage - COVERAGE_TOLERANCE
            )
        }
    }

    /**
     * A human-readable sanity check on the outcome of the sort: heavy glyphs land in the
     * dense half of the ramp and hairline glyphs land in the sparse half.
     */
    @Test
    fun sortedCharset_placesHeavyGlyphsInTheDenseHalf() {
        val sorted = recoverSortedCharset()
        val midpoint = sorted.size / 2

        for (dense in listOf('@', '#', 'M', 'W')) {
            assertTrue(
                "'$dense' should sort into the dense half but sits at index ${sorted.indexOf(dense)}",
                sorted.indexOf(dense) > midpoint
            )
        }
        for (sparse in listOf('.', ',', '\'', '`', ':')) {
            assertTrue(
                "'$sparse' should sort into the sparse half but sits at index ${sorted.indexOf(sparse)}",
                sorted.indexOf(sparse) < midpoint
            )
        }
    }

    /** The charset is cached after first use; the cache must not perturb the ordering. */
    @Test
    fun sortedCharset_isStableAcrossRepeatedCalls() {
        assertEquals(recoverSortedCharset(), recoverSortedCharset())
    }

    /**
     * ASCII mode draws white glyphs on a black background with no display-time inversion,
     * so ink must track scene brightness: bright pixels get dense glyphs, dark pixels get
     * sparse ones. Regression test for commit 268b241, which mapped `255 - gray` and so
     * blanked out the bright parts of the scene.
     */
    @Test
    fun toAsciiText_mapsBrightPixelsToDenseGlyphsAndDarkPixelsToSpace() {
        val brightest = asciiFor(intensity = 255)
        val darkest = asciiFor(intensity = 0)

        assertEquals("a fully dark pixel maps to the sparsest glyph", ' ', darkest)
        assertEquals(
            "a fully bright pixel maps to the densest glyph in the charset",
            recoverSortedCharset().last(),
            brightest
        )
        assertTrue(
            "a bright pixel should ink more than a dark one, got '$brightest' vs '$darkest'",
            inkCoverage(brightest) > inkCoverage(darkest)
        )
    }

    /**
     * AsciiGridPreview derives each row's bounds arithmetically as `y * (width + 1)`
     * instead of scanning for newlines, which is only valid while every row is exactly
     * `width` characters followed by a single '\n'. Pin that layout here — if the
     * separator or row padding ever changes, this fails instead of the UI silently
     * drawing shifted rows.
     */
    @Test
    fun toAsciiText_laysRowsOutAtAFixedStride() {
        val width = 7
        val height = 5
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { i ->
            val gray = (i * 255) / (width * height - 1)
            Color.rgb(gray, gray, gray)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val text = try {
            AsciiArt.toAsciiText(bitmap, AsciiCharsetPreset.PRINTABLE)
        } finally {
            bitmap.recycle()
        }

        assertEquals(
            "text should be width*height glyphs plus height-1 separators",
            width * height + (height - 1),
            text.length
        )

        val stride = width + 1
        for (y in 0 until height) {
            val start = y * stride
            val end = start + width
            assertFalse(
                "row $y (indices $start until $end) must not span a newline",
                text.substring(start, end).contains('\n')
            )
            if (y < height - 1) {
                assertEquals("row $y must be followed by a newline", '\n', text[end])
            } else {
                assertEquals("the last row must end the string", text.length, end)
            }
        }
    }

    /** Ink coverage must rise monotonically with scene brightness across the whole ramp. */
    @Test
    fun toAsciiText_inkCoverageIncreasesWithIntensity() {
        val samples = (0..255 step 15).map { it to inkCoverage(asciiFor(intensity = it)) }

        for (i in 1 until samples.size) {
            val (previousIntensity, previousCoverage) = samples[i - 1]
            val (currentIntensity, currentCoverage) = samples[i]
            assertTrue(
                "intensity $currentIntensity inked $currentCoverage but the darker " +
                    "intensity $previousIntensity inked $previousCoverage",
                currentCoverage >= previousCoverage - COVERAGE_TOLERANCE
            )
        }
    }

    /**
     * Recovers the private sorted charset through the public API.
     *
     * `toAsciiText` picks `sortedChars[round(gray * (size - 1) / 255f)]`. Over gray = 0
     * up to 255 that index rises from 0 to `size - 1` in steps of 0 or 1, so it visits
     * every index in order without skipping. Collapsing runs of the repeated character
     * therefore reproduces the sorted charset exactly.
     */
    private fun recoverSortedCharset(): List<Char> {
        val bitmap = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256) { gray -> Color.rgb(gray, gray, gray) }
        bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 1)
        val text = try {
            AsciiArt.toAsciiText(bitmap, AsciiCharsetPreset.PRINTABLE)
        } finally {
            bitmap.recycle()
        }

        val sorted = ArrayList<Char>()
        for (char in text) {
            if (sorted.isEmpty() || sorted.last() != char) sorted.add(char)
        }
        return sorted
    }

    /** The glyph `toAsciiText` selects for a single pixel of the given grey [intensity]. */
    private fun asciiFor(intensity: Int): Char {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.rgb(intensity, intensity, intensity))
        return try {
            AsciiArt.toAsciiText(bitmap, AsciiCharsetPreset.PRINTABLE).single()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Fraction of the cell a glyph inks when drawn white on black. Deliberately mirrors
     * the measurement in AsciiArt.buildSortedCharset so that the assertions test the
     * ordering rather than the (unchanged) measurement itself.
     */
    private fun inkCoverage(char: Char): Float {
        if (char == ' ') return 0f
        val bitmap = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = TEXT_SIZE_PX
                typeface = Typeface.MONOSPACE
            }
            val bounds = Rect()
            val glyph = charArrayOf(char)
            canvas.drawColor(Color.BLACK)
            paint.getTextBounds(glyph, 0, 1, bounds)
            val x = ((GRID - bounds.width()) / 2f) - bounds.left
            val y = ((GRID - bounds.height()) / 2f) - bounds.top
            canvas.drawText(glyph, 0, 1, x, y, paint)
            val pixels = IntArray(GRID * GRID)
            bitmap.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
            pixels.count { (it and 0xFF) > 0 }.toFloat() / (GRID * GRID)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val GRID = 24
        const val TEXT_SIZE_PX = 20f

        /**
         * One inked pixel out of the 24x24 cell. Absorbs the antialiasing jitter that can
         * make two visually identical-weight glyphs measure a hair apart, without letting
         * a genuine ordering inversion through.
         */
        const val COVERAGE_TOLERANCE = 1f / (GRID * GRID)
    }
}
