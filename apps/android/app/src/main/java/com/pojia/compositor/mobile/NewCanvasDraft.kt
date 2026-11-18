package com.pojia.compositor.mobile

import java.util.Locale
import kotlin.math.floor

internal enum class NewCanvasUnit(val title: String, val perInch: Double?) {
    PIXELS("Pixels", null), INCHES("Inches", 1.0), CENTIMETERS("Centimeters", 2.54), MILLIMETERS("Millimeters", 25.4);

    fun pixels(text: String, dpi: Double): Int? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0 || !dpi.isFinite() || dpi !in 1.0..9600.0) return null
        // Positive half-pixel ties round up, matching Swift's default rounded().
        val pixels = perInch?.let { floor(value / it * dpi + 0.5) } ?: value
        if (!pixels.isFinite() || pixels != floor(pixels) || pixels !in 1.0..30000.0) return null
        return pixels.toInt()
    }

    fun text(pixels: Int, dpi: Double): String = perInch?.let {
        String.format(Locale.ROOT, "%.2f", pixels / dpi * it).trimEnd('0').trimEnd('.')
    } ?: pixels.toString()
}

internal enum class NewCanvasBackground(val title: String) {
    TRANSPARENT("Transparent"), WHITE("White"), BLACK("Black")
}
internal data class NewCanvasSpec(val width: Int, val height: Int, val resolution: Double,
    val background: NewCanvasBackground)
internal data class NewCanvasDraft(val width: String = "1920", val height: String = "1080",
    val resolution: String = "72", val unit: NewCanvasUnit = NewCanvasUnit.PIXELS,
    val background: NewCanvasBackground = NewCanvasBackground.TRANSPARENT) {
    fun spec(): NewCanvasSpec? {
        val dpi = resolution.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!dpi.isFinite() || dpi !in 1.0..9600.0) return null
        return NewCanvasSpec(unit.pixels(width, dpi) ?: return null,
            unit.pixels(height, dpi) ?: return null, dpi, background)
    }

    fun withUnit(next: NewCanvasUnit): NewCanvasDraft {
        val value = spec() ?: return copy(unit = next)
        return copy(unit = next, width = next.text(value.width, value.resolution),
            height = next.text(value.height, value.resolution))
    }

    fun withPixels(width: Int, height: Int): NewCanvasDraft {
        val dpi = resolution.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..9600.0 }
            ?: return this
        return copy(width = unit.text(width, dpi), height = unit.text(height, dpi))
    }

    companion object {
        fun rememberedPixels(width: Int, height: Int): NewCanvasDraft = NewCanvasDraft(
            width = width.takeIf { it in 1..30000 }?.toString() ?: "1920",
            height = height.takeIf { it in 1..30000 }?.toString() ?: "1080")
    }
}
