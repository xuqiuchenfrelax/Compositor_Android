package com.pojia.compositor.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Transient, read-only source data; snapshots are defensively copied and never saved into the draft. */
internal class LevelsHistogramState private constructor(
    val bins: List<List<Double>>?, val loading: Boolean, val failed: Boolean,
) {
    companion object {
        val Loading = LevelsHistogramState(null, loading = true, failed = false)
        val Failed = LevelsHistogramState(null, loading = false, failed = true)
        fun ready(flat: DoubleArray): LevelsHistogramState {
            require(flat.size == 4 * 256) { "Invalid Levels histogram length" }
            val bins = List(4) { channel -> List(256) { index -> flat[channel * 256 + index] } }
            validateLevelsHistogram(bins)
            return LevelsHistogramState(bins, loading = false, failed = false)
        }
    }
}

@Composable
internal fun LevelsHistogram(state: LevelsHistogramState, channel: Int, enabled: Boolean,
    onRetry: () -> Unit) {
    require(channel in 0..3)
    val ui = LocalContext.current
    val channelLabel = ui.getString(listOf(R.string.ui_adjustment_rgb, R.string.ui_adjustment_red,
        R.string.ui_adjustment_green, R.string.ui_adjustment_blue)[channel])
    val label = ui.getString(R.string.ui_levels_histogram_channel, channelLabel)
    val bins = state.bins?.get(channel)
    val scale = bins?.let(::levelsHistogramDisplayScale) ?: 0.0
    val status = ui.getString(when {
        state.loading -> R.string.ui_levels_histogram_loading
        state.failed -> R.string.ui_levels_histogram_failed
        scale == 0.0 -> R.string.ui_levels_histogram_empty
        else -> R.string.ui_levels_histogram_ready
    })
    Column {
        Box(Modifier.fillMaxWidth().height(120.dp).background(Color.Black.copy(alpha = .25f))) {
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics {
                contentDescription = label
                stateDescription = status
            }) {
                if (bins != null && scale > 0.0) {
                    val color = listOf(Color.Gray, Color.Red, Color.Green, Color.Blue)[channel]
                    val width = size.width / 256f
                    bins.forEachIndexed { index, weight ->
                        val height = size.height * (weight / scale).coerceIn(0.0, 1.0).toFloat()
                        drawRect(color, Offset(index * width, size.height - height), Size(width + 1f, height))
                    }
                }
            }
            if (state.loading || state.failed) Text(status, Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        Text(ui.getString(R.string.ui_levels_histogram_source), style = MaterialTheme.typography.labelSmall)
        if (state.failed) TextButton(enabled = enabled, onClick = onRetry) {
            Text(ui.getString(R.string.ui_levels_histogram_retry))
        }
    }
}
