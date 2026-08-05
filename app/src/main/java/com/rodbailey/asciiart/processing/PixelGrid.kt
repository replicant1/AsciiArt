package com.rodbailey.asciiart.processing

import androidx.compose.runtime.Immutable

/**
 * The size of a de-res grid, in cells.
 *
 * Separate from the grids themselves because the dimensions outlive the pixels: Image mode
 * returns no colour grid at all, and the UI still has to lay out cells.
 */
data class GridSize(val width: Int, val height: Int) {
    val cellCount: Int get() = width * height
}

/**
 * A grid of ARGB pixels that nothing will overwrite.
 *
 * This is the type that may cross to the main thread and be held in Compose state. Its array
 * is private and its only constructor is internal, so the sole way to obtain one is
 * [ScratchGrid.freeze] — which copies. That is the point: item 4 was a shared scratch buffer
 * escaping to Compose state and being overwritten by the next frame mid-draw, and the rule
 * that prevents it ("copy anything that outlives the call") lived in a comment. Here,
 * forgetting to copy is a type error rather than a race found on a device.
 *
 * The copy is not new cost. Both pipelines already allocate a per-frame array for exactly
 * this reason; [ScratchGrid.freeze] is where that allocation now happens.
 */
@Immutable
class PixelGrid internal constructor(
    private val pixels: IntArray,
    val size: GridSize
) {
    val width: Int get() = size.width
    val height: Int get() = size.height

    /** The pixel at ([x], [y]). */
    operator fun get(x: Int, y: Int): Int = pixels[(y * width) + x]

    /** As [get], but null rather than an exception when ([x], [y]) is off the grid. */
    fun getOrNull(x: Int, y: Int): Int? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        return pixels.getOrNull((y * width) + x)
    }
}

/**
 * A reusable pixel buffer owned by [ImageProcessor] and overwritten by every frame.
 *
 * Never hand one of these to the UI — that is what [freeze] is for. Passing it to a function
 * that reads and returns is fine, and [AsciiArt.toAsciiText] does exactly that; the hazard is
 * only in what outlives the call.
 *
 * The buffer is grown, never shrunk, so it can be longer than [GridSize.cellCount] after the
 * Scale slider moves or a rotation swaps width and height. Every read and write here is
 * bounded by the current size, and [freeze] trims to it.
 *
 * Writes are by flat index rather than (x, y): the camera loop computes its destination
 * through [RotationMap] as `base + stepX * x + stepY * y`, which is what folds rotation into
 * the downsample. A 2D setter would mean recovering coordinates the loop deliberately
 * never computes.
 */
internal class ScratchGrid {

    private var buffer = IntArray(0)

    var size: GridSize = GridSize(0, 0)
        private set

    /** Grows the buffer if this frame needs more room, and records the size in use. */
    fun prepare(size: GridSize): ScratchGrid {
        if (buffer.size < size.cellCount) buffer = IntArray(size.cellCount)
        this.size = size
        return this
    }

    operator fun get(index: Int): Int = buffer[index]

    operator fun set(index: Int, argb: Int) {
        buffer[index] = argb
    }

    /**
     * The raw array, for the platform APIs that take a flat array and a stride — `getPixels`,
     * `setPixels`, `createBitmap`. They copy out of or into it within the call, so nothing
     * retains it. This is the one hole in the ownership rule above; keep its callers to that
     * boundary.
     */
    val raw: IntArray get() = buffer

    /** A copy of the live cells, safe to hand to Compose. */
    fun freeze(): PixelGrid = PixelGrid(buffer.copyOf(size.cellCount), size)
}
