package com.pojia.compositor.mobile

import kotlin.math.ln

/** Original unpremultiplied RGB calibrates the three channels together, as on desktop. */
internal enum class LevelsSampleMode(val label: Int) {
    BLACK(R.string.ui_levels_sample_black),
    GRAY(R.string.ui_levels_sample_gray),
    WHITE(R.string.ui_levels_sample_white);

    fun settings(ranges: List<AdjustmentLevelRange>, rgb: List<Double>): List<AdjustmentLevelRange> {
        require(ranges.size == 4 && rgb.size == 3 && rgb.all { it.isFinite() && it in 0.0..1.0 })
        ranges.forEach(AdjustmentLevelRange::validate)
        val result = ranges.toMutableList()
        result[0] = AdjustmentLevelRange()
        for (channel in 1..3) {
            val range = result[channel]
            val value = rgb[channel - 1] * 255.0
            val changed = when (this) {
                BLACK -> range.copy(black = minOf(range.white - 1.0, maxOf(0.0, value)))
                WHITE -> range.copy(white = maxOf(range.black + 1.0, minOf(255.0, value)))
                GRAY -> {
                    val fraction = (value - range.black) / (range.white - range.black)
                    // The desktop leaves even the output endpoints unchanged for these channels.
                    if (fraction <= 0.0 || fraction >= 1.0) continue
                    range.copy(gamma = (ln(fraction) / ln(.5)).coerceIn(.1, 9.99))
                }
            }
            result[channel] = changed.copy(outputBlack = 0.0, outputWhite = 255.0)
        }
        return result
    }
}
