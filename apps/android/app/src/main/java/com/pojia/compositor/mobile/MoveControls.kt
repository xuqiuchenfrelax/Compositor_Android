package com.pojia.compositor.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Drawing only: the shipping CanvasView owns hit testing and the retained native transaction. */
@Composable
internal fun MoveControls(canvas: CanvasView?, box: Transform64?) {
    if (canvas == null || box == null || (!canvas.moveShowControls && !canvas.movePersistent)) return
    val geometry = canvas.moveOverlay(box)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        fun MovePoint.offset() = Offset(x.toFloat(), y.toFloat())
        val outline = Path().apply {
            moveTo(geometry.handles[0].x.toFloat(), geometry.handles[0].y.toFloat())
            for (index in listOf(2, 4, 6)) lineTo(geometry.handles[index].x.toFloat(), geometry.handles[index].y.toFloat())
            close()
            moveTo(geometry.handles[1].x.toFloat(), geometry.handles[1].y.toFloat())
            lineTo(geometry.rotationHandle.x.toFloat(), geometry.rotationHandle.y.toFloat())
        }
        drawPath(outline, accent, style = Stroke(1.dp.toPx()))
        val side = 7.dp.toPx()
        for (point in geometry.handles) {
            val corner = point.offset() - Offset(side / 2, side / 2)
            drawRect(Color.White, corner, Size(side, side))
            drawRect(accent, corner, Size(side, side), style = Stroke(1.dp.toPx()))
        }
        drawCircle(Color.White, 4.dp.toPx(), geometry.rotationHandle.offset())
        drawCircle(accent, 4.dp.toPx(), geometry.rotationHandle.offset(), style = Stroke(1.dp.toPx()))
    }
}
