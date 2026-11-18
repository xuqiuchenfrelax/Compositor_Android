package com.pojia.compositor.mobile

/** A stroke captures this value at pointer-down; later control changes affect the next stroke. */
internal data class StrokeSettings(
    val diameter: Float = 40f,
    val hardness: Float = 1f,
    val opacity: Float = 1f,
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val smoothing: Float = 0f,
    val maskPaintWhite: Boolean = false,
    val pressureSize: Boolean = false,
    val pressureOpacity: Boolean = false,
) {
    init {
        require(diameter.isFinite() && diameter in 1f..2000f) { "Brush size must be between 1 and 2000" }
        require(hardness.isFinite() && hardness in 0f..1f) { "Hardness must be between 0 and 1" }
        require(opacity.isFinite() && opacity in 0.01f..1f) { "Opacity must be between 0.01 and 1" }
        require(red.isFinite() && red in 0f..1f && green.isFinite() && green in 0f..1f &&
            blue.isFinite() && blue in 0f..1f) { "Color components must be between 0 and 1" }
        require(smoothing.isFinite() && smoothing in 0f..100f) { "Smoothing must be between 0 and 100" }
    }
    fun savedValues(): List<Any> = listOf(diameter, hardness, opacity, red, green, blue, smoothing,
        maskPaintWhite, pressureSize, pressureOpacity)

    companion object {
        fun restore(values: List<Any>): StrokeSettings {
            require(values.size == 8 || values.size == 10) { "Unsupported saved brush settings" }
            return StrokeSettings(values[0] as Float, values[1] as Float, values[2] as Float,
                values[3] as Float, values[4] as Float, values[5] as Float, values[6] as Float,
                values[7] as Boolean, values.getOrNull(8) as? Boolean ?: false,
                values.getOrNull(9) as? Boolean ?: false)
        }
    }
}

internal enum class CanvasToolMode { BRUSH, CLONE, SMEAR, HEALING, EYEDROPPER, HAND, ZOOM, MOVE, CROP, SHAPE, GRADIENT, MARQUEE, LASSO, MAGIC, IDLE }

/** Device events follow Mac's fallback; direct native samples retain their strict validation. */
internal fun normalizedPenPressure(pressure: Float, stylus: Boolean): Double =
    if (stylus && pressure.isFinite()) pressure.coerceIn(0f, 1f).toDouble() else 1.0
