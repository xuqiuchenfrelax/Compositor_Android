package com.pojia.compositor.mobile

import kotlin.math.ln
import kotlin.math.pow

/** Matches LevelsAuto.settings: the 0.1% tails are discarded only after a strict threshold crossing. */
internal enum class LevelsAutoMode(val label: Int) {
    CONTRAST(R.string.ui_levels_auto_contrast),
    COLOR(R.string.ui_levels_auto_color),
    NEUTRAL(R.string.ui_levels_auto_neutral);

    fun settings(histogram: List<List<Double>>): List<AdjustmentLevelRange> {
        validateLevelsHistogram(histogram)
        val ranges = MutableList(4) { AdjustmentLevelRange() }
        fun endpoints(bins: List<Double>): Pair<Double, Double>? {
            val total = bins.sum()
            if (total <= 0.0) return null
            var sum = 0.0
            var low = 0
            var high = 255
            for (index in 0..255) {
                sum += bins[index]
                if (sum > total * .001) { low = index; break }
            }
            sum = 0.0
            for (index in 255 downTo 0) {
                sum += bins[index]
                if (sum > total * .001) { high = index; break }
            }
            return if (low < high) low.toDouble() to high.toDouble() else null
        }
        if (this == CONTRAST) {
            val limits = histogram.drop(1).mapNotNull(::endpoints)
            val low = limits.minOfOrNull { it.first }
            val high = limits.maxOfOrNull { it.second }
            if (low != null && high != null && low < high)
                ranges[0] = AdjustmentLevelRange(black = low, white = high)
        } else {
            for (channel in 1..3) {
                val (low, high) = endpoints(histogram[channel]) ?: continue
                var range = AdjustmentLevelRange(black = low, white = high)
                if (this == NEUTRAL) {
                    val total = histogram[channel].sum()
                    var sum = 0.0
                    histogram[channel].forEachIndexed { index, weight ->
                        val input = ((index.toDouble() / 255.0 * 255.0 - range.black) /
                            (range.white - range.black)).coerceIn(0.0, 1.0)
                        val result = (range.outputBlack + input.pow(1.0 / range.gamma) *
                            (range.outputWhite - range.outputBlack)) / 255.0
                        sum += result * weight
                    }
                    val mean = sum / total
                    if (mean > 0.0 && mean < 1.0)
                        range = range.copy(gamma = (ln(mean) / ln(.5)).coerceIn(.1, 9.99))
                }
                ranges[channel] = range
            }
        }
        return ranges
    }
}

internal fun validateLevelsHistogram(histogram: List<List<Double>>) {
    require(histogram.size == 4 && histogram.all { bins -> bins.size == 256 &&
        bins.all { it.isFinite() && it >= 0.0 } && bins.sum().isFinite() }) { "Invalid Levels histogram" }
}

/** Display scaling only; the original bins remain unchanged for automatic settings. */
internal fun levelsHistogramDisplayScale(bins: List<Double>): Double {
    require(bins.size == 256 && bins.all { it.isFinite() && it >= 0.0 }) { "Invalid Levels histogram channel" }
    val peak = bins.filter { it > 0.0 }.maxOrNull() ?: 0.0
    if (peak <= 0.0) return 0.0
    val interior = bins.drop(1).dropLast(1).filter { it > 0.0 }.sorted()
    if (interior.isEmpty()) return peak
    val typicalPeak = interior[((interior.size - 1) * .95).toInt()]
    return minOf(peak, typicalPeak * 4.0)
}
