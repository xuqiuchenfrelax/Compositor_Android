package com.pojia.compositor.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun HueSaturationEditor(settings: HueSaturationModel, update: (HueSaturationModel) -> Unit) {
    val ui = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            HueRange.entries.forEach { range -> TextButton(enabled = !settings.colorize,
                onClick = { update(settings.copy(range = range)) }) {
                Text((if (settings.range == range) "✓ " else "") + ui.getString(range.label))
            } }
        }
        val current = settings.current
        AdjustmentSlider(ui.getString(R.string.ui_adjustment_hue), current.hue,
            if (settings.colorize) 0.0..360.0 else -180.0..180.0) { update(settings.withCurrent(current.copy(hue = roundedHueSlider(it)))) }
        AdjustmentSlider(ui.getString(R.string.ui_adjustment_saturation), current.saturation,
            if (settings.colorize) 0.0..100.0 else -100.0..100.0) { update(settings.withCurrent(current.copy(saturation = roundedHueSlider(it)))) }
        AdjustmentSlider(ui.getString(R.string.ui_adjustment_lightness), current.lightness, -100.0..100.0) {
            update(settings.withCurrent(current.copy(lightness = roundedHueSlider(it))))
        }
        if (settings.range != HueRange.MASTER && !settings.colorize) {
            HueSpectrumEditor(settings, update)
            AdjustmentToggle(ui.getString(R.string.ui_adjustment_invert_range), settings.invertRange) {
                update(settings.copy(invertRange = it))
            }
        }
        AdjustmentToggle(ui.getString(R.string.ui_adjustment_colorize), settings.colorize) {
            update(if (it) HueSaturationModel.colorizeStart() else HueSaturationModel())
        }
        TextButton(onClick = { update(if (settings.colorize) HueSaturationModel.colorizeStart() else HueSaturationModel()) }) {
            Text(ui.getString(R.string.ui_adjustment_hsv_reset))
        }
    }
}

@Composable
private fun HueSpectrumEditor(settings: HueSaturationModel, update: (HueSaturationModel) -> Unit) {
    val ui = LocalContext.current
    var selected by remember(settings.range) { mutableIntStateOf(0) }
    var width by remember { mutableStateOf(0) }
    var dragging by remember(settings.range) { mutableIntStateOf(-1) }
    val latest by rememberUpdatedState(settings)
    val onUpdate by rememberUpdatedState(update)
    fun degrees(position: Offset): Double = if (width > 0) (position.x.toDouble() / width * 360).coerceIn(0.0, 360.0) else 0.0
    fun nearest(degrees: Double): Int = latest.band.handles.indices.minBy { index ->
        val gap = abs(wrapHue(latest.band.handles[index]) - degrees) % 360
        min(gap, 360 - gap)
    }
    fun move(position: Offset, handle: Int) {
        val style = latest
        onUpdate(style.withBand(style.band.withHandle(handle, degrees(position))))
    }
    @Composable fun Spectrum(after: Boolean) {
        val label = ui.getString(if (after) R.string.ui_adjustment_spectrum_after else R.string.ui_adjustment_spectrum_before)
        Canvas(Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = label }) {
            repeat(72) { slice ->
                val hue = slice / 72.0 * 360
                val shown = if (after) settings.shiftedHue(hue) else hue
                drawRect(Color.hsv(shown.toFloat(), 1f, 1f), Offset(slice * size.width / 72, 0f), Size(size.width / 72 + 1, size.height))
            }
        }
    }
    Spectrum(false)
    val graphLabel = ui.getString(R.string.ui_adjustment_hue_band)
    val foreground = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(32.dp).onSizeChanged { width = it.width }
        .semantics { contentDescription = graphLabel }
        .pointerInput(settings.range) { detectTapGestures { p -> selected = nearest(degrees(p)); move(p, selected) } }
        .pointerInput(settings.range) { detectDragGestures(onDragStart = { p -> dragging = nearest(degrees(p)); selected = dragging; move(p, dragging) },
            onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 }) { change, _ ->
            if (dragging >= 0) { change.consume(); move(change.position, dragging) }
        } }) {
        settings.band.handles.forEachIndexed { index, handle ->
            val x = wrapHue(handle).toFloat() / 360 * size.width
            val inner = index == 1 || index == 2
            drawRect(if (index == selected) accent else foreground,
                Offset(x - if (inner) 1.dp.toPx() else 4.dp.toPx(), if (inner) 0f else size.height / 2 - 3.dp.toPx()),
                Size(if (inner) 2.dp.toPx() else 8.dp.toPx(), if (inner) size.height else 6.dp.toPx()))
        }
    }
    Spectrum(true)
    val handles = listOf(R.string.ui_adjustment_falloff_start, R.string.ui_adjustment_range_start,
        R.string.ui_adjustment_range_end, R.string.ui_adjustment_falloff_end)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        handles.forEachIndexed { index, label -> TextButton(onClick = { selected = index }) {
            Text((if (index == selected) "✓ " else "") + ui.getString(label))
        } }
    }
    AdjustmentSlider(ui.getString(handles[selected]), wrapHue(settings.band.handles[selected]), 0.0..360.0) { value ->
        update(settings.withBand(settings.band.withHandle(selected, value)))
    }
}

/** Match Swift rounded() for slider drags; imported fractional settings stay intact. */
internal fun roundedHueSlider(value: Double): Double = if (value < 0)
    -kotlin.math.floor(-value + 0.5) else kotlin.math.floor(value + 0.5)
