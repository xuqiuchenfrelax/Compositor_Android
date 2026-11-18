package com.pojia.compositor.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** The graph uses the same shape-preserving cubic Hermite interpolation as Mac CurvesSettings. */
internal fun adjustmentCurveValue(points: List<AdjustmentCurvePoint>, x: Double): Double {
    val i = points.indexOfLast { it.x <= x }.coerceIn(0, points.lastIndex - 1)
    fun delta(j: Int) = (points[j + 1].y - points[j].y) / (points[j + 1].x - points[j].x)
    fun slope(j: Int): Double {
        if (j == 0) return delta(0)
        if (j == points.lastIndex) return delta(j - 1)
        val left = delta(j - 1); val right = delta(j)
        return if (left * right <= 0.0) 0.0 else 2.0 / (1.0 / left + 1.0 / right)
    }
    val h = points[i + 1].x - points[i].x
    val t = ((x - points[i].x) / h).coerceIn(0.0, 1.0)
    return ((2*t*t*t-3*t*t+1)*points[i].y + (t*t*t-2*t*t+t)*h*slope(i) +
        (-2*t*t*t+3*t*t)*points[i+1].y + (t*t*t-t*t)*h*slope(i+1)).coerceIn(0.0, 255.0)
}

@Composable
internal fun AdjustmentCurvesEditor(style: AdjustmentLayerModel, update: (AdjustmentLayerModel) -> Unit) {
    val ui = LocalContext.current
    var selected by remember(style.curveChannel) { mutableIntStateOf(0) }
    var dragging by remember(style.curveChannel) { mutableIntStateOf(-1) }
    var graphSize by remember { mutableStateOf(IntSize.Zero) }
    val currentStyle by rememberUpdatedState(style)
    val currentUpdate by rememberUpdatedState(update)
    val points = style.curves[style.curveChannel]
    fun replace(values: List<AdjustmentCurvePoint>) {
        val latest = currentStyle
        currentUpdate(latest.withCurves(values = latest.curves.toMutableList().also { it[latest.curveChannel] = values }))
    }
    fun begin(position: Offset): Int {
        if (graphSize.width == 0 || graphSize.height == 0) return -1
        val current = currentStyle.curves[currentStyle.curveChannel]
        val x = (position.x.toDouble() / graphSize.width * 255).coerceIn(0.0, 255.0)
        val y = (255 - position.y.toDouble() / graphSize.height * 255).coerceIn(0.0, 255.0)
        val nearest = current.indices.minBy { hypot(current[it].x - x, current[it].y - y) }
        val index = if (hypot(current[nearest].x - x, current[nearest].y - y) < 14) nearest
        else if (current.size < 32 && x > 1 && x < 254 && current.all { kotlin.math.abs(it.x - x) > 1 }) {
            val values = (current + AdjustmentCurvePoint(x, y)).sortedBy { it.x }
            replace(values); values.indexOfFirst { it.x == x }
        } else -1
        if (index >= 0) selected = index
        return index
    }
    fun move(position: Offset) {
        val current = currentStyle.curves[currentStyle.curveChannel]
        if (dragging !in current.indices || graphSize.width == 0 || graphSize.height == 0) return
        val old = current[dragging]
        val x = if (dragging == 0 || dragging == current.lastIndex || current[dragging + 1].x - current[dragging - 1].x <= .002) old.x else
            (position.x.toDouble() / graphSize.width * 255).coerceIn(current[dragging - 1].x + .001, current[dragging + 1].x - .001)
        val y = (255 - position.y.toDouble() / graphSize.height * 255).coerceIn(0.0, 255.0)
        replace(current.toMutableList().also { it[dragging] = AdjustmentCurvePoint(x, y) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf(R.string.ui_adjustment_rgb, R.string.ui_adjustment_red, R.string.ui_adjustment_green, R.string.ui_adjustment_blue)
                .forEachIndexed { index, label -> TextButton(onClick = { update(style.withCurves(channelValue = index)) }) {
                    Text(if (style.curveChannel == index) "✓ ${ui.getString(label)}" else ui.getString(label))
                } }
        }
        val graphLabel = ui.getString(R.string.ui_adjustment_curve_graph)
        val accent = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(220.dp).background(Color.Black.copy(alpha = .35f))
            .onSizeChanged { graphSize = it }.semantics { contentDescription = graphLabel }
            .pointerInput(style.curveChannel) { detectTapGestures { begin(it) } }
            .pointerInput(style.curveChannel) { detectDragGestures(onDragStart = { dragging = begin(it) },
                onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 }) { change, _ ->
                if (dragging >= 0) { change.consume(); move(change.position) }
            } }) {
            fun position(x: Double, y: Double) = Offset((x / 255 * size.width).toFloat(), ((1 - y / 255) * size.height).toFloat())
            repeat(5) { i ->
                val f = i / 4f
                drawLine(Color.White.copy(alpha = .12f), Offset(f * size.width, 0f), Offset(f * size.width, size.height))
                drawLine(Color.White.copy(alpha = .12f), Offset(0f, f * size.height), Offset(size.width, f * size.height))
            }
            val path = Path()
            repeat(256) { x ->
                val p = position(x.toDouble(), adjustmentCurveValue(points, x.toDouble()))
                if (x == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            points.forEachIndexed { i, p -> drawCircle(if (selected == i) accent else Color.White, 5.dp.toPx(), position(p.x, p.y)) }
        }
        Text(ui.getString(R.string.ui_adjustment_curve_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            points.forEachIndexed { i, p -> TextButton(onClick = { selected = i }) {
                Text((if (selected == i) "✓ " else "") + ui.getString(R.string.ui_adjustment_curve_point, i + 1, p.x, p.y))
            } }
        }
        val index = selected.coerceIn(points.indices)
        val point = points[index]
        if (index > 0 && index < points.lastIndex && points[index + 1].x - points[index - 1].x > .002) {
            AdjustmentSlider(ui.getString(R.string.ui_adjustment_curve_input), point.x, points[index - 1].x + .001..points[index + 1].x - .001) { x ->
                replace(points.toMutableList().also { it[index] = point.copy(x = x) })
            }
        }
        AdjustmentSlider(ui.getString(R.string.ui_adjustment_curve_output), point.y, 0.0..255.0) { y ->
            replace(points.toMutableList().also { it[index] = point.copy(y = y) })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(enabled = points.size < 32, onClick = {
                val left = (0 until points.lastIndex).maxBy { points[it + 1].x - points[it].x }
                val x = (points[left].x + points[left + 1].x) / 2
                replace(points.toMutableList().also { it.add(left + 1, AdjustmentCurvePoint(x, adjustmentCurveValue(points, x))) })
                selected = left + 1
            }) { Text(ui.getString(R.string.ui_adjustment_curve_add)) }
            TextButton(enabled = index > 0 && index < points.lastIndex, onClick = {
                replace(points.toMutableList().also { it.removeAt(index) }); selected = 0
            }) { Text(ui.getString(R.string.ui_adjustment_curve_remove)) }
            TextButton(onClick = { replace(listOf(AdjustmentCurvePoint(0.0, 0.0), AdjustmentCurvePoint(255.0, 255.0))); selected = 0 }) {
                Text(ui.getString(R.string.ui_adjustment_curve_reset))
            }
        }
    }
}
