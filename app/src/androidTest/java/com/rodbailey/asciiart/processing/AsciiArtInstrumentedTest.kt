package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies how [AsciiArt] maps pixel intensity onto its density-sorted character set.
 *
 * These are instrumented tests because the density measurement draws each glyph with a
 * real [Canvas] and [Paint] onto a real [Bitmap] — none of which have usable behaviour
 * under the local JVM's stubbed android.jar.
 */
@RunWith(AndroidJUnit4::class)
class AsciiArtInstrumentedTest {

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
     * mapping and ordering rather than the measurement itself.
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
