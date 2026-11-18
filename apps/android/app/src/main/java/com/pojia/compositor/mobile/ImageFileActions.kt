package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageFileActions(editor: NativeEditor, canvas: CanvasView?, workspace: AndroidWorkspace,
    owner: ImageRequestOwner, showActions: Boolean = true, otherOperationBusy: Boolean = false) {
    val ui = LocalContext.current
    val state = owner.state
    var jpegDialog by rememberSaveable { mutableStateOf(false) }
    var jpegPickingColor by rememberSaveable { mutableStateOf(false) }
    var jpegQuality by rememberSaveable { mutableIntStateOf(85) }
    var jpegBackground by rememberSaveable { mutableIntStateOf(0xffffff) }
    val choiceId = owner.exportChoiceId
    var jpegChoiceId by rememberSaveable { mutableStateOf<Long?>(null) }
    fun openJpeg(choice: Long? = null) {
        jpegQuality = runCatching { ui.getSharedPreferences("image-export", 0).getInt("jpeg-quality", 85) }
            .getOrDefault(85).coerceIn(0, 100)
        jpegBackground = 0xffffff
        jpegPickingColor = false
        jpegChoiceId = choice
        jpegDialog = true
    }
    fun dismissJpeg() {
        if (jpegChoiceId != null && owner.exportChoiceId == jpegChoiceId) owner.dismissExportChoice()
        jpegDialog = false
        jpegPickingColor = false
        jpegChoiceId = null
    }
    val showJpeg = jpegDialog && jpegChoiceId == choiceId
    if (showActions) FlowRow {
        val enabled = !state.busy && choiceId == null && !otherOperationBusy && canvas != null && !canvas.editingInProgress &&
            !workspace.snapshot().resourceGrowthFrozen
        TextButton(enabled = enabled, onClick = { owner.begin(ImageRequestPurpose.IMPORT, editor) }) { Text(ui.getString(R.string.ui_import_image)) }
        TextButton(enabled = enabled, onClick = { owner.begin(ImageRequestPurpose.IMPORT_FILE, editor) }) { Text(ui.getString(R.string.ui_import_image_file)) }
        TextButton(enabled = enabled, onClick = {
            owner.chooseExport(editor)
        }) { Text(ui.getString(R.string.ui_export_as)) }
        TextButton(enabled = enabled, onClick = { owner.begin(ImageRequestPurpose.EXPORT_PNG, editor) }) { Text(ui.getString(R.string.ui_export_png)) }
        TextButton(enabled = enabled, onClick = {
            openJpeg()
        }) { Text(ui.getString(R.string.ui_export_jpeg)) }
        TextButton(enabled = enabled, onClick = { owner.begin(ImageRequestPurpose.EXPORT_TIFF_FLATTENED, editor) }) { Text(ui.getString(R.string.ui_export_tiff_flattened)) }
        TextButton(enabled = enabled, onClick = { owner.begin(ImageRequestPurpose.EXPORT_TIFF, editor) }) { Text(ui.getString(R.string.ui_export_tiff_source_layers)) }
        if (state.canCancel) TextButton(onClick = { owner.cancel() }) { Text(ui.getString(R.string.ui_cancel_image_operation)) }
        if (state.busy) Text(ui.getString(R.string.ui_working))
    }
    if (choiceId != null) {
        var exportFormat by rememberSaveable(choiceId) { mutableStateOf(owner.startingExportFormat()) }
        val sourceWidth = editor.canvasWidth
        val sourceHeight = editor.canvasHeight
        var input by rememberSaveable(choiceId) { mutableStateOf("") }
        var widthEdited by rememberSaveable(choiceId) { mutableStateOf(true) }
        var preset by rememberSaveable(choiceId) { mutableStateOf<Int?>(100) }
        val scale = preset?.let { it / 100.0 }
            ?: input.toIntOrNull()?.let { it.toDouble() / if (widthEdited) sourceWidth else sourceHeight }
        val size = scale?.let { ImageExportSize.scaled(sourceWidth, sourceHeight, it) }
        if (!showJpeg) AlertDialog(onDismissRequest = { owner.dismissExportChoice() },
            title = { Text(ui.getString(R.string.ui_export_as)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                ImageExportFormat.entries.forEach { format ->
                    val label = ui.getString(when (format) {
                        ImageExportFormat.PNG -> R.string.ui_export_png
                        ImageExportFormat.JPEG -> R.string.ui_export_jpeg
                        ImageExportFormat.TIFF -> R.string.ui_export_tiff_flattened
                    })
                    Row(Modifier.fillMaxWidth().selectable(selected = exportFormat == format,
                        role = Role.RadioButton, onClick = { exportFormat = format }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = exportFormat == format, onClick = null)
                        Text(label)
                    }
                }
                ExportSizeFields(widthText = if (preset == null && widthEdited) input else size?.width?.toString().orEmpty(),
                    heightText = if (preset == null && !widthEdited) input else size?.height?.toString().orEmpty(),
                    preset = preset, valid = size != null, onWidth = { preset = null; widthEdited = true; input = it },
                    onHeight = { preset = null; widthEdited = false; input = it }, onPercent = { preset = it })
            } },
            confirmButton = { TextButton(enabled = size != null && !otherOperationBusy, onClick = {
                if (size != null && exportFormat == ImageExportFormat.JPEG) openJpeg(choiceId)
                else if (size != null) owner.launchExportChoice(choiceId, editor, exportFormat, size)
            }) { Text(ui.getString(R.string.ui_export_continue)) } },
            dismissButton = { TextButton(onClick = { owner.dismissExportChoice() }) { Text(ui.getString(R.string.ui_cancel)) } })
        if (showJpeg && size != null) JpegOptionsDialog(jpegQuality, jpegBackground, { jpegQuality = it },
            { jpegPickingColor = true }, ::dismissJpeg, enabled = !otherOperationBusy) {
            if (owner.launchExportChoice(choiceId, editor, ImageExportFormat.JPEG, size,
                    JpegExportOptions(jpegQuality, jpegBackground)) != null)
                ImageExportPreferences.saveJpegQuality(ui, jpegQuality)
            dismissJpeg()
        }
    }
    if (showJpeg && choiceId == null) JpegOptionsDialog(jpegQuality, jpegBackground, { jpegQuality = it },
        { jpegPickingColor = true }, ::dismissJpeg, enabled = !otherOperationBusy) {
        if (owner.begin(ImageRequestPurpose.EXPORT_JPEG, editor, JpegExportOptions(jpegQuality, jpegBackground)) != null)
            ImageExportPreferences.saveJpegQuality(ui, jpegQuality)
        dismissJpeg()
    }
    if (showJpeg && jpegPickingColor) {
        EditorColorPicker(Color((0xff000000L or jpegBackground.toLong()).toInt()), background = true,
            title = ui.getString(R.string.ui_jpeg_background)) { selected ->
            selected?.let { jpegBackground = it.toArgb() and 0xffffff }
            jpegPickingColor = false
        }
    }
}

@Composable
private fun ExportSizeFields(widthText: String, heightText: String, preset: Int?, valid: Boolean,
    onWidth: (String) -> Unit, onHeight: (String) -> Unit, onPercent: (Int) -> Unit) {
    val ui = LocalContext.current
    var presetsOpen by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(widthText, { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) onWidth(it) },
            Modifier.weight(1f), label = { Text(ui.getString(R.string.ui_width)) }, singleLine = true,
            isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(heightText, { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) onHeight(it) },
            Modifier.weight(1f), label = { Text(ui.getString(R.string.ui_height)) }, singleLine = true,
            isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Box {
        TextButton(onClick = { presetsOpen = true }) {
            Text(if (preset == null) ui.getString(R.string.ui_export_scale)
                else ui.getString(R.string.ui_export_scale_percent, preset))
        }
        DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
            for (percent in listOf(25, 50, 75, 100, 200)) DropdownMenuItem(
                text = { Text(ui.getString(R.string.ui_export_scale_percent, percent)) },
                onClick = { onPercent(percent); presetsOpen = false })
        }
    }
    if (!valid) Text(ui.getString(R.string.ui_export_size_invalid))
}

@Composable
private fun JpegOptionsDialog(jpegQuality: Int, jpegBackground: Int, setQuality: (Int) -> Unit,
    pickColor: () -> Unit, dismiss: () -> Unit, enabled: Boolean, confirm: () -> Unit) {
    val ui = LocalContext.current
    AlertDialog(onDismissRequest = dismiss,
            title = { Text(ui.getString(R.string.ui_export_jpeg)) },
            text = { Column {
                Text(ui.getString(R.string.ui_jpeg_quality_percent, jpegQuality))
                Slider(value = jpegQuality.toFloat(), onValueChange = { setQuality(it.roundToInt()) },
                    valueRange = 0f..100f, steps = 99,
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_jpeg_quality) })
                Row {
                    Text(ui.getString(R.string.ui_background))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(40.dp).background(Color((0xff000000L or jpegBackground.toLong()).toInt()))
                        .border(1.dp, Color.Gray).semantics { contentDescription = ui.getString(R.string.ui_jpeg_background) }
                        .clickable(onClick = pickColor))
                }
            } },
            confirmButton = { TextButton(enabled = enabled, onClick = confirm) { Text(ui.getString(R.string.ui_jpeg_export_continue)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(ui.getString(R.string.ui_cancel)) } })
}
