package com.pojia.compositor.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun MoveSnapLines(canvas: CanvasView?) {
    // Read observable input before the draw lambda so a coalesced transform redraws the overlay.
    val snap = canvas?.moveSnap ?: return
    if (snap.snapX == null && snap.snapY == null) return
    val lines = canvas.snapScreenLines()
    Canvas(Modifier.fillMaxSize()) {
        val color = Color(0xffed63c6)
        lines.first?.let { drawLine(color, Offset(it, 0f), Offset(it, size.height), 1.dp.toPx()) }
        lines.second?.let { drawLine(color, Offset(0f, it), Offset(size.width, it), 1.dp.toPx()) }
    }
}
