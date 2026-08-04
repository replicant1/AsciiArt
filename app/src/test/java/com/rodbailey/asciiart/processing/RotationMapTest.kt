package com.rodbailey.asciiart.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [rotationMap] replaced a separate rotation pass in the camera pipeline, so the property
 * that matters is that it lands every pixel exactly where the old pass did. The reference
 * implementation below is the previous `CameraFrameAnalyzer.rotateColorGridIfNeeded`,
 * copied verbatim, so these tests fail if the new mapping alters the orientation at all.
 *
 * Plain JVM tests — the mapping is pure arithmetic with no Android dependency, which is
 * the whole reason it was extracted rather than inlined into the downsample loop.
 */
class RotationMapTest {

    @Test
    fun rotationMap_matchesThePreviousRotationForEveryRightAngle() {
        // Deliberately non-square and non-symmetric: a square grid or symmetric contents
        // would let a transposed or mirrored mapping pass.
        val width = 5
        val height = 3
        val source = IntArray(width * height) { it + 1 }

        for (degrees in listOf(0, 90, 180, 270)) {
            val map = rotationMap(width, height, degrees)
            val actual = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    actual[map.base + (map.stepX * x) + (map.stepY * y)] = source[(y * width) + x]
                }
            }

            assertArrayEquals(
                "rotation $degrees should match the previous implementation",
                referenceRotate(source, width, height, degrees),
                actual
            )
        }
    }

    @Test
    fun rotationMap_swapsDestinationDimensionsOnQuarterTurns() {
        val width = 5
        val height = 3

        for (degrees in listOf(0, 180)) {
            val map = rotationMap(width, height, degrees)
            assertEquals("rotation $degrees keeps width", width, map.destWidth)
            assertEquals("rotation $degrees keeps height", height, map.destHeight)
        }
        for (degrees in listOf(90, 270)) {
            val map = rotationMap(width, height, degrees)
            assertEquals("rotation $degrees swaps width", height, map.destWidth)
            assertEquals("rotation $degrees swaps height", width, map.destHeight)
        }
    }

    /** Every source pixel must land on a distinct, in-range destination index. */
    @Test
    fun rotationMap_isABijectionOntoTheDestinationGrid() {
        val width = 7
        val height = 4
        val size = width * height

        for (degrees in listOf(0, 90, 180, 270)) {
            val map = rotationMap(width, height, degrees)
            val hits = IntArray(size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = map.base + (map.stepX * x) + (map.stepY * y)
                    assertEquals(
                        "rotation $degrees put ($x,$y) out of range at $index",
                        true,
                        index in 0 until size
                    )
                    hits[index]++
                }
            }
            assertArrayEquals(
                "rotation $degrees should hit every destination index exactly once",
                IntArray(size) { 1 },
                hits
            )
        }
    }

    @Test
    fun rotationMap_normalisesOutOfRangeAndNegativeAngles() {
        val width = 5
        val height = 3

        assertEquals(rotationMap(width, height, 90), rotationMap(width, height, 450))
        assertEquals(rotationMap(width, height, 270), rotationMap(width, height, -90))
        assertEquals(rotationMap(width, height, 0), rotationMap(width, height, 360))
    }

    /** Angles CameraX never reports fall back to identity rather than a partial rotation. */
    @Test
    fun rotationMap_fallsBackToIdentityForNonRightAngles() {
        val identity = rotationMap(5, 3, 0)

        assertEquals(identity, rotationMap(5, 3, 45))
        assertEquals(identity, rotationMap(5, 3, 1))
    }

    /**
     * Verbatim copy of the rotation this replaced, from CameraFrameAnalyzer before the
     * downsample loop absorbed it. Do not "simplify" — its value is being the old code.
     */
    private fun referenceRotate(colors: IntArray, width: Int, height: Int, rotationDegrees: Int): IntArray {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return colors.copyOf()

        val rotated = IntArray(colors.size)
        when (normalized) {
            90 -> {
                val newWidth = height
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIndex = (y * width) + x
                        val newX = height - 1 - y
                        val newY = x
                        rotated[(newY * newWidth) + newX] = colors[srcIndex]
                    }
                }
            }
            180 -> {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIndex = (y * width) + x
                        val newX = width - 1 - x
                        val newY = height - 1 - y
                        rotated[(newY * width) + newX] = colors[srcIndex]
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
                        rotated[(newY * newWidth) + newX] = colors[srcIndex]
                    }
                }
            }
            else -> return colors
        }
        return rotated
    }
}
