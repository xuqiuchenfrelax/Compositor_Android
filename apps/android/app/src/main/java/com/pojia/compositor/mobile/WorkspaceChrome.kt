package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun WorkspaceToolbar(fileOpen: Boolean, layersVisible: Boolean, narrow: Boolean, dirty: Boolean,
    canUndo: Boolean, canRedo: Boolean, enabled: Boolean, newCanvas: () -> Unit, file: () -> Unit, layers: () -> Unit,
    undo: () -> Unit, redo: () -> Unit, fit: () -> Unit, actual: () -> Unit, zoom: (Int) -> Unit) {
    val ui = LocalContext.current
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .horizontalScroll(rememberScrollState()).heightIn(min = 52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = newCanvas, enabled = enabled) { Text(ui.getString(R.string.ui_new), fontSize = 12.sp) }
        TextButton(onClick = file, enabled = enabled, shape = RoundedCornerShape(7.dp),
            colors = ButtonDefaults.textButtonColors(containerColor = if (fileOpen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
            Text(ui.getString(R.string.ui_file), fontSize = 12.sp)
        }
        if (narrow) TextButton(onClick = layers,
            colors = ButtonDefaults.textButtonColors(containerColor = if (layersVisible) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
            Text(ui.getString(R.string.ui_layers), fontSize = 12.sp)
        }
        Text(if (dirty) ui.getString(R.string.ui_untitled) else ui.getString(R.string.ui_untitled_129), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp).semantics { contentDescription = if (dirty) ui.getString(R.string.ui_current_document_unsaved_changes) else ui.getString(R.string.ui_current_document) })
        TextButton(onClick = undo, enabled = enabled && canUndo) { Text(ui.getString(R.string.ui_undo), fontSize = 12.sp) }
        TextButton(onClick = redo, enabled = enabled && canRedo) { Text(ui.getString(R.string.ui_redo), fontSize = 12.sp) }
        TextButton(onClick = fit, enabled = enabled) { Text(ui.getString(R.string.ui_fit), fontSize = 12.sp) }
        TextButton(onClick = actual, enabled = enabled, modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_actual_pixels) }) { Text("100%", fontSize = 12.sp) }
        TextButton(onClick = { zoom(-1) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_zoom_out) }) { Text("−", fontSize = 18.sp) }
        TextButton(onClick = { zoom(1) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_zoom_in) }) { Text("+", fontSize = 18.sp) }
    }
}

@Composable
internal fun WorkspaceStatus(width: Int, height: Int, zoom: Double, tool: EditorTool, erasing: Boolean, mask: Boolean, busy: Boolean) {
    val ui = LocalContext.current
    val help = when {
        busy -> ui.getString(R.string.ui_working)
        tool == EditorTool.BRUSH && mask -> ui.getString(R.string.ui_black_hides_white_reveals_escape_cancels)
        tool == EditorTool.BRUSH -> if (erasing) ui.getString(R.string.ui_drag_to_erase_escape_cancels_two_fingers_to_pan) else ui.getString(R.string.ui_drag_to_paint_escape_cancels_two_fingers_to_pan)
        tool == EditorTool.CLONE -> ui.getString(R.string.ui_set_source_or_alt_click_drag_to_clone_two_fingers_to_pan)
        tool == EditorTool.HEALING -> ui.getString(R.string.ui_help_healing)
        tool == EditorTool.SMEAR -> ui.getString(R.string.ui_help_smear)
        tool == EditorTool.MOVE -> ui.getString(R.string.ui_help_move)
        tool == EditorTool.CROP -> ui.getString(R.string.ui_help_crop)
        tool == EditorTool.SHAPE -> ui.getString(R.string.ui_help_shape)
        tool == EditorTool.GRADIENT -> ui.getString(R.string.ui_help_gradient)
        tool == EditorTool.MARQUEE || tool == EditorTool.LASSO -> ui.getString(R.string.ui_help_selection)
        tool == EditorTool.MAGIC -> ui.getString(R.string.ui_help_magic)
        tool == EditorTool.EYEDROPPER -> ui.getString(R.string.ui_help_sample)
        tool == EditorTool.TYPE -> ui.getString(R.string.ui_help_text)
        tool == EditorTool.HAND -> ui.getString(R.string.ui_drag_to_pan_pinch_to_zoom)
        tool == EditorTool.ZOOM -> ui.getString(R.string.ui_click_to_zoom_pinch_to_zoom)
        tool == EditorTool.DISTORT -> ui.getString(R.string.ui_distort_setup_native_corner_preview_pending)
        else -> ui.getString(R.string.ui_select_a_tool_two_fingers_to_pan_zoom)
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .horizontalScroll(rememberScrollState()).heightIn(min = 30.dp).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(Locale.ROOT, "%.1f%%", zoom), fontSize = 11.sp,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_zoom_status) })
        Text(ui.getString(R.string.ui_canvas_pixels, width, height), fontSize = 11.sp,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_canvas_dimensions) })
        Text("sRGB", fontSize = 11.sp)
        Text(help, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
