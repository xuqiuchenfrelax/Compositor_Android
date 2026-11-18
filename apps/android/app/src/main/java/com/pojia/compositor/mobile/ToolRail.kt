package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun ToolRail(tool: EditorTool, erasing: Boolean, enabled: Boolean, select: (EditorTool) -> Unit,
    palette: @Composable () -> Unit) {
    val ui = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var scrollHeight by remember { mutableIntStateOf(0) }
    val rowHeight = with(LocalDensity.current) { 48.dp.roundToPx() }
    fun page(direction: Int) {
        val step = maxOf(rowHeight, scrollHeight * 3 / 4)
        scope.launch { scroll.animateScrollTo((scroll.value + direction * step).coerceIn(0, scroll.maxValue)) }
    }
    Column(Modifier.width(56.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally) {
        ToolRailScrollButton(true, scroll.canScrollBackward) { page(-1) }
        Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { scrollHeight = it.height }
            .verticalScroll(scroll).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EditorTool.entries.filter { it != EditorTool.IDLE }.forEach { item ->
                val selected = item == tool
                val active = enabled && item.available
                Box(Modifier.size(48.dp)
                    .semantics {
                        contentDescription = if (item == EditorTool.BRUSH && erasing) ui.getString(R.string.ui_eraser_e) else ui.getString(R.string.ui_tool_shortcut, ui.toolTitle(item), item.key)
                        this.selected = selected
                        if (!item.available) stateDescription = ui.getString(R.string.ui_not_implemented)
                    }.clickable(enabled = active, role = Role.Button) { select(item) }
                    .padding(5.dp)
                    .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(7.dp))
                    .border(1.dp, if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    ToolIcon(item, erasing, if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                }
            }
            Spacer(Modifier.height(8.dp))
            palette()
        }
        ToolRailScrollButton(false, scroll.canScrollForward) { page(1) }
    }
}

@Composable
private fun ToolRailScrollButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ui = LocalContext.current
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = ui.getString(if (up) R.string.ui_previous_tools else R.string.ui_more_tools)
        }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (up) "↑" else "↓", fontSize = 16.sp)
            Text(ui.getString(if (up) R.string.ui_tools_up else R.string.ui_tools_down), fontSize = 10.sp)
        }
    }
}

@Composable
private fun ToolIcon(tool: EditorTool, erasing: Boolean, tint: Color) {
    val ui = LocalContext.current
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 20
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, point(x, y), point(x2, y2), 1.5f * u)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRect(tint, point(x, y), Size(w * u, h * u), style = Stroke(1.4f * u))
        fun circle(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, point(x, y), style = Stroke(1.4f * u))
        when (tool) {
            EditorTool.MOVE -> { line(3f,3f,17f,17f); line(3f,3f,3f,9f); line(3f,3f,9f,3f); line(17f,17f,11f,17f); line(17f,17f,17f,11f) }
            // Four independent corner handles mirror the Mac free-distort affordance.  The
            // actual worker/session is deliberately owned by CanvasView; this icon keeps the
            // entry point stable while that native path is being wired.
            EditorTool.DISTORT -> {
                box(3f, 3f, 14f, 14f)
                circle(3f, 3f, 2f); circle(17f, 3f, 2f); circle(17f, 17f, 2f); circle(3f, 17f, 2f)
            }
            EditorTool.MARQUEE -> { for (x in listOf(3f,8f,13f)) { line(x,3f,x+3,3f); line(x,17f,x+3,17f) }; for (y in listOf(6f,11f)) { line(3f,y,3f,y+3); line(17f,y,17f,y+3) } }
            EditorTool.LASSO -> { drawOval(tint, point(2f,3f), Size(16*u,11*u), style=Stroke(1.4f*u)); circle(7f,14f,2f); line(7f,16f,10f,19f) }
            EditorTool.MAGIC -> { line(4f,17f,15f,6f); line(6f,2f,6f,6f); line(4f,4f,8f,4f); line(16f,12f,16f,16f); line(14f,14f,18f,14f) }
            EditorTool.CROP -> { line(5f,1f,5f,15f); line(5f,15f,19f,15f); line(1f,5f,15f,5f); line(15f,5f,15f,19f); line(6f,14f,14f,6f) }
            EditorTool.BRUSH -> if (erasing) { val p=Path().apply { moveTo(2*u,13*u); lineTo(11*u,3*u); lineTo(18*u,10*u); lineTo(12*u,17*u); lineTo(6*u,17*u); close() }; drawPath(p,tint,style=Stroke(1.4f*u)); line(6f,9f,13f,16f) }
                else { line(8f,12f,16f,3f); line(11f,14f,18f,5f); drawOval(tint,point(2f,12f),Size(8*u,6*u)); }
            EditorTool.HEALING -> { line(3f,14f,14f,3f); line(6f,17f,17f,6f); line(3f,14f,6f,17f); line(14f,3f,17f,6f); circle(10f,10f,1f) }
            EditorTool.CLONE -> { circle(10f,5f,3f); line(9f,8f,7f,13f); line(11f,8f,13f,13f); box(3f,13f,14f,4f) }
            EditorTool.SMEAR -> { val p=Path().apply { moveTo(10*u,1*u); cubicTo(8*u,6*u,3*u,10*u,4*u,14*u); cubicTo(6*u,21*u,18*u,17*u,16*u,11*u); close() }; drawPath(p,tint,style=Stroke(1.4f*u)) }
            EditorTool.GRADIENT -> { box(2f,4f,16f,12f); for(x in 3..17) drawLine(tint.copy(alpha=(x-2)/16f),point(x.toFloat(),5f),point(x.toFloat(),15f),u) }
            EditorTool.SHAPE -> { box(2f,3f,11f,11f); circle(13f,13f,5f) }
            EditorTool.TYPE -> { line(3f,3f,17f,3f); line(10f,3f,10f,17f); line(6f,17f,14f,17f) }
            EditorTool.EYEDROPPER -> { line(3f,17f,14f,6f); line(6f,18f,17f,7f); line(3f,17f,6f,18f); line(11f,4f,18f,11f); line(14f,1f,19f,6f) }
            EditorTool.HAND -> { val p=Path().apply { moveTo(3*u,10*u); lineTo(7*u,13*u); lineTo(7*u,4*u); lineTo(9*u,4*u); lineTo(9*u,10*u); lineTo(9*u,2*u); lineTo(11*u,2*u); lineTo(11*u,10*u); lineTo(11*u,4*u); lineTo(13*u,4*u); lineTo(13*u,11*u); lineTo(13*u,6*u); lineTo(15*u,6*u); lineTo(15*u,15*u); lineTo(12*u,18*u); lineTo(7*u,18*u); close() }; drawPath(p,tint,style=Stroke(1.3f*u)) }
            EditorTool.ZOOM -> { circle(8f,8f,5.5f); line(12f,12f,18f,18f); line(5f,8f,11f,8f); line(8f,5f,8f,11f) }
            EditorTool.IDLE -> Unit
        }
    }
}
