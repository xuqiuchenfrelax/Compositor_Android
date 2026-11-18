package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.Locale

@Composable
internal fun DocumentSettings(editor: NativeEditor, metadata: DocumentMetadata, canvas: CanvasView?,
    showActions: Boolean = true, failed: (UiNoticeKey) -> Unit) {
    val ui = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var editingGuide by remember { mutableStateOf<GuideState?>(null) }
    if (showActions) TextButton(onClick = { if (canvas?.editingInProgress != true) open = true }, enabled = canvas != null && !canvas.editingInProgress) { Text(ui.getString(R.string.ui_document)) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(ui.getString(R.string.ui_document_settings)) },
        confirmButton = { TextButton(onClick = { open = false }) { Text(ui.getString(R.string.ui_done)) } }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                var dpi by remember(metadata.resolution) { mutableStateOf(metadata.resolution.toString()) }
                var dirty by remember(metadata.resolution) { mutableStateOf(false) }
                OutlinedTextField(value = dpi, onValueChange = { dpi = it; dirty = true }, label = { Text(ui.getString(R.string.ui_resolution_pixels_inch)) },
                    singleLine = true, modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_document_resolution) })
                TextButton(onClick = {
                    val value = dpi.toDoubleOrNull()
                    if (value == null || !value.isFinite() || value !in 1.0..9600.0) failed(UiNoticeKey.DOCUMENT_RESOLUTION_INVALID)
                    else if (dirty) canvas?.command(EditorCommand.SetResolution(value))
                }, enabled = dirty) { Text(ui.getString(R.string.ui_apply_resolution)) }
                Text(ui.getString(R.string.ui_canvas_inches, editor.canvasWidth / metadata.resolution, editor.canvasHeight / metadata.resolution),
                    style = MaterialTheme.typography.labelSmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(ui.getString(R.string.ui_guides_count, metadata.guides.size), style = MaterialTheme.typography.titleSmall)
                metadata.guides.forEach { guide ->
                    key(guide.uuid) {
                        Row {
                            TextButton(onClick = { editingGuide = guide }, modifier = Modifier.weight(1f)) {
                                Text(ui.getString(R.string.ui_guide_position, if (guide.horizontal) ui.getString(R.string.ui_horizontal) else ui.getString(R.string.ui_vertical), guide.position.toString()))
                            }
                            TextButton(onClick = { canvas?.command(EditorCommand.RemoveGuide(guide.uuid)) }) { Text(ui.getString(R.string.ui_delete)) }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { editingGuide = GuideState(UUID.randomUUID().toString().uppercase(Locale.ROOT), true, 0.0) },
                        enabled = metadata.guides.size < 1000) { Text(ui.getString(R.string.ui_add_guide)) }
                    TextButton(onClick = { canvas?.command(EditorCommand.SetGuides(emptyList())) }, enabled = metadata.guides.isNotEmpty()) { Text(ui.getString(R.string.ui_clear_guides)) }
                }
            }
        })
    editingGuide?.let { original ->
        var horizontal by remember(original.uuid) { mutableStateOf(original.horizontal) }
        var position by remember(original.uuid) { mutableStateOf(original.position.toString()) }
        var dirty by remember(original.uuid) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editingGuide = null }, title = { Text(ui.getString(R.string.ui_guide)) },
            text = { Column {
                Row {
                    FilterChip(selected = horizontal, onClick = { horizontal = true; dirty = true }, label = { Text(ui.getString(R.string.ui_horizontal)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !horizontal, onClick = { horizontal = false; dirty = true }, label = { Text(ui.getString(R.string.ui_vertical)) })
                }
                OutlinedTextField(value = position, onValueChange = { position = it; dirty = true }, label = { Text(ui.getString(R.string.ui_position_pixels)) }, singleLine = true)
            } }, confirmButton = { TextButton(onClick = {
                val value = position.toDoubleOrNull()
                if (value == null || !value.isFinite() || kotlin.math.abs(value) > 1_000_000) failed(UiNoticeKey.GUIDE_POSITION_INVALID)
                else {
                    if (dirty || metadata.guides.none { it.uuid == original.uuid }) canvas?.command(EditorCommand.PutGuide(original.copy(horizontal = horizontal, position = value)))
                    editingGuide = null
                }
            }) { Text(ui.getString(R.string.ui_save_guide)) } }, dismissButton = { TextButton(onClick = { editingGuide = null }) { Text(ui.getString(R.string.ui_cancel)) } })
    }
}
