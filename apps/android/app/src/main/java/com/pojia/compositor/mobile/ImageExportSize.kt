package com.pojia.compositor.mobile

import kotlin.math.roundToInt

internal data class ImageExportSize(val width: Int, val height: Int) {
    init { require(isSupported(width, height)) }

    companion object {
        const val MAX_SIDE = 30000
        const val MAX_PIXELS = 16_777_216L

        fun isSupported(width: Int, height: Int): Boolean =
            width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS

        fun scaled(sourceWidth: Int, sourceHeight: Int, scale: Double): ImageExportSize? {
            if (sourceWidth <= 0 || sourceHeight <= 0 || !scale.isFinite() || scale <= 0) return null
            val w = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val h = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            return if (isSupported(w, h)) ImageExportSize(w, h) else null
        }
    }
}
