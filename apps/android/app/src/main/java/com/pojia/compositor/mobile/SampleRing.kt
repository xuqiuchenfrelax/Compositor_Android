package com.pojia.compositor.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The comparison overlay observes input and never intercepts canvas events. */
@Composable
internal fun SampleRing(state: SampleRingState?) {
    if (state == null) return
    val ui = LocalContext.current
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = ui.getString(R.string.ui_sample_ring) }) {
        val radius = 43.dp.toPx()
        val center = Offset(state.x, state.y)
        drawCircle(Color(.45f, .45f, .45f), radius, center, style = Stroke(24.dp.toPx()))
        val topLeft = center - Offset(radius, radius)
        val size = Size(radius * 2, radius * 2)
        drawArc(Color(state.sampled), 180f, 180f, false, topLeft, size, style = Stroke(16.dp.toPx()))
        drawArc(Color(state.original), 0f, 180f, false, topLeft, size, style = Stroke(16.dp.toPx()))
    }
}
