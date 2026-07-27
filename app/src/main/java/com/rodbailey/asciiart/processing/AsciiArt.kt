package com.rodbailey.asciiart.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.roundToInt

enum class AsciiDisplayMode {
    IMAGE_ONLY,
    ASCII_OVERLAY,
    ASCII_ONLY
}

enum class AsciiCharsetPreset {
    PRINTABLE,
    EXTENDED
}

object AsciiArt {
    private val sortedCharsetCache = mutableMapOf<AsciiCharsetPreset, List<Char>>()
    private const val densityGridWidth = 24
    private const val densityGridHeight = 24
    private const val densityTextSizePx = 20f

    fun toAsciiText(grayscaleBitmap: Bitmap, preset: AsciiCharsetPreset): String {
        val sortedChars = sortedCharsetCache.getOrPut(preset) {
            buildCharacterSet(preset).sortedBy { measureVisualDensity(it) }
        }
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

    private fun buildCharacterSet(preset: AsciiCharsetPreset): List<Char> = when (preset) {
        AsciiCharsetPreset.PRINTABLE -> (32..126).map { it.toChar() }
        AsciiCharsetPreset.EXTENDED -> {
            val chars = mutableListOf<Char>()
            for (code in 32..255) {
                val char = code.toChar()
                if (!char.isISOControl()) {
                    chars += char
                }
            }
            chars
        }
    }

    private fun measureVisualDensity(character: Char): Float {
        if (character == ' ') {
            return 0f
        }

        val bitmap = Bitmap.createBitmap(
            densityGridWidth,
            densityGridHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = densityTextSizePx
            typeface = Typeface.MONOSPACE
        }

        val text = character.toString()
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val x = ((densityGridWidth - bounds.width()) / 2f) - bounds.left
        val y = ((densityGridHeight - bounds.height()) / 2f) - bounds.top
        canvas.drawText(text, x, y, paint)

        val pixels = IntArray(densityGridWidth * densityGridHeight)
        bitmap.getPixels(pixels, 0, densityGridWidth, 0, 0, densityGridWidth, densityGridHeight)
        bitmap.recycle()

        val activePixelCount = pixels.count { (it and 0xFF) > 0 }
        return activePixelCount.toFloat() / (densityGridWidth * densityGridHeight)
    }
}
