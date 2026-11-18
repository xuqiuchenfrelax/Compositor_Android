package com.pojia.compositor.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.Saver
import kotlin.math.roundToInt

internal enum class TextStyleField(val maximumCharacters: Int) {
    FONT(201), COLOR(64), SIZE(20), LEADING(20), TRACKING(20), BOX_WIDTH(20), BOX_HEIGHT(20);

    fun active(style: TextLayerModel): Boolean = when (this) {
        BOX_WIDTH, BOX_HEIGHT -> style.boxWidth != null
        else -> true
    }
}

/**
 * Hoisted editor state. Keep this value with the owning workspace/session so recreation and recovery do not
 * rasterize or discard text. Composing offsets are transient UTF-16 indices and are never serialized to a project.
 */
internal data class TextEditorDraft(
    val style: TextLayerModel,
    val layerId: Long? = null,
    val pointX: Double = 0.0,
    val pointY: Double = 0.0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val composingStart: Int = -1,
    val composingEnd: Int = -1,
    val submissionId: String = java.util.UUID.randomUUID().toString(),
    val commitInFlight: Boolean = false,
    val styleInputs: Map<TextStyleField, String> = emptyMap(),
) {
    init {
        require(selectionStart in 0..style.content.length && selectionEnd in 0..style.content.length)
        require((composingStart < 0 && composingEnd < 0) ||
            (composingStart in 0..style.content.length && composingEnd in composingStart..style.content.length))
        require(submissionId.isNotBlank())
        require(styleInputs.all { (field, text) -> text.length <= field.maximumCharacters })
    }

    val hasComposition: Boolean get() = composingStart >= 0

    val hasInvalidStyleInput: Boolean get() = styleInputs.keys.any(::invalidStyleField)

    fun editStyleField(field: TextStyleField, input: String): TextEditorDraft {
        val text = input.take(field.maximumCharacters)
        return copy(style = styleForInput(field, text) ?: style, styleInputs = styleInputs + (field to text))
    }

    fun withTextBox(width: Double?, height: Double?): TextEditorDraft = copy(
        style = style.withTextBox(width, height),
        styleInputs = styleInputs - TextStyleField.BOX_WIDTH - TextStyleField.BOX_HEIGHT)

    fun invalidStyleField(field: TextStyleField): Boolean {
        val text = styleInputs[field] ?: return false
        if (!field.active(style)) return false
        // Checking the controls must not expand per-character font/color runs.
        return when (field) {
            TextStyleField.FONT -> text.isBlank() || text.length > 200 || text.any(Char::isTextNewline)
            TextStyleField.COLOR -> parseTextColor(text) == null
            TextStyleField.SIZE -> text.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..2000.0 } != true
            TextStyleField.LEADING -> text.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..5000.0 } != true
            TextStyleField.TRACKING -> text.toDoubleOrNull()?.let { it.isFinite() && it in -100.0..1000.0 } != true
            TextStyleField.BOX_WIDTH, TextStyleField.BOX_HEIGHT -> styleForInput(field, text) == null
        }
    }

    private fun styleForInput(field: TextStyleField, text: String): TextLayerModel? = try {
        when (field) {
            TextStyleField.FONT -> if (text.isBlank() || text.length > 200 || text.any(Char::isTextNewline)) null
                else style.setFont(text, selectionStart, selectionEnd)
            TextStyleField.COLOR -> parseTextColor(text)?.let {
                style.setColor(it[0], it[1], it[2], selectionStart, selectionEnd)
            }
            else -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { number ->
                when (field) {
                    TextStyleField.SIZE -> if (number in 1.0..2000.0) style.copy(fontSize = number).validate() else null
                    TextStyleField.LEADING -> if (number in 0.0..5000.0) style.copy(leading = number).validate() else null
                    TextStyleField.TRACKING -> if (number in -100.0..1000.0) style.copy(tracking = number).validate() else null
                    TextStyleField.BOX_WIDTH -> if (number in 16.0..30_000.0) style.withTextBox(number, style.boxHeight) else null
                    TextStyleField.BOX_HEIGHT -> if (number in 16.0..30_000.0) style.withTextBox(style.boxWidth, number) else null
                    else -> null
                }
            }
        }
    } catch (_: IllegalArgumentException) { null }

    fun textFieldValue(): TextFieldValue = TextFieldValue(style.content,
        selection = TextRange(selectionStart, selectionEnd),
        composition = if (hasComposition) TextRange(composingStart, composingEnd) else null)

    fun update(value: TextFieldValue, retainSelection: Boolean = false): TextEditorDraft {
        val clean = TextToolContract.sanitize(value.text)
        val nextStyle = style.replacingContent(clean)
        // Like Mac CanvasTextView.resignFirstResponder, keep a selected range
        // while formatting controls own focus. An IME blur callback can collapse
        // the old field's selection even though its text has not changed.
        val selection = if (retainSelection && clean == style.content &&
            selectionStart != selectionEnd && value.selection.collapsed)
            TextRange(selectionStart, selectionEnd) else value.selection
        val composition = value.composition
        return copy(style = nextStyle,
            selectionStart = selection.start.coerceIn(0, clean.length), selectionEnd = selection.end.coerceIn(0, clean.length),
            composingStart = composition?.min?.coerceIn(0, clean.length) ?: -1,
            composingEnd = composition?.max?.coerceIn(0, clean.length) ?: -1)
    }
}

internal val textEditorDraftSaver: Saver<TextEditorDraft?, Any> = Saver(
    save = { draft -> draft?.let { listOf(it.style.toMacJson().toString(), it.layerId ?: 0L, it.pointX, it.pointY,
        it.selectionStart, it.selectionEnd, it.composingStart, it.composingEnd, it.submissionId,
        org.json.JSONObject().apply { it.styleInputs.forEach { (field, text) -> put(field.name, text) } }.toString()) } },
    restore = { saved ->
        val fields = saved as? List<*>
        if (fields == null || fields.size !in 8..10) null else runCatching {
            val style = TextLayerModel.fromMacJson(org.json.JSONObject(fields[0] as String))
            TextEditorDraft(style, (fields[1] as Number).toLong().takeIf { it != 0L },
                (fields[2] as Number).toDouble(), (fields[3] as Number).toDouble(),
                (fields[4] as Number).toInt(), (fields[5] as Number).toInt(),
                (fields[6] as Number).toInt(), (fields[7] as Number).toInt(),
                if (fields.size >= 9) fields[8] as String else java.util.UUID.randomUUID().toString(),
                styleInputs = if (fields.size == 10) org.json.JSONObject(fields[9] as String).let { inputs ->
                    inputs.keys().asSequence().associate { key -> TextStyleField.valueOf(key) to inputs.getString(key) }
                } else emptyMap())
        }.getOrNull()
    })

/**
 * Text controls for the native typed-layer workflow. Parent owns the draft and must wire commit/cancel to the native
 * text transaction; this view deliberately has no raster fallback. Font names and measurements stay in Mac project
 * units while the controls are on screen.
 */
@Composable
internal fun TextLayerEditor(
    draft: TextEditorDraft,
    onDraftChanged: (TextEditorDraft) -> Unit,
    onCommit: (TextLayerModel) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = draft.style
    val enabled = !draft.commitInFlight
    val ui = LocalContext.current
    key(draft.submissionId) {
        var contentFocused by remember { mutableStateOf(false) }
        Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.getString(R.string.ui_text_layer), style = MaterialTheme.typography.titleMedium)
            Text(ui.getString(R.string.ui_font_substitution), style = MaterialTheme.typography.bodySmall)
            if (draft.commitInFlight) Text(ui.getString(R.string.ui_working), style = MaterialTheme.typography.bodySmall)
            StyleInputField(draft, TextStyleField.FONT, style.fontName, ui.getString(R.string.ui_font_face),
                ui.getString(R.string.ui_text_font_invalid), enabled, Modifier.fillMaxWidth(), onDraftChanged)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StyleInputField(draft, TextStyleField.SIZE, style.fontSize.toString(), ui.getString(R.string.ui_font_size_px),
                    ui.getString(R.string.ui_text_numeric_invalid), enabled, Modifier.weight(1f), onDraftChanged)
                StyleInputField(draft, TextStyleField.LEADING, style.leading.toString(), ui.getString(R.string.ui_font_leading_px),
                    ui.getString(R.string.ui_text_numeric_invalid), enabled, Modifier.weight(1f), onDraftChanged)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StyleInputField(draft, TextStyleField.TRACKING, style.tracking.toString(), ui.getString(R.string.ui_font_tracking_px),
                    ui.getString(R.string.ui_text_numeric_invalid), enabled, Modifier.weight(1f), onDraftChanged)
                StyleInputField(draft, TextStyleField.COLOR, style.colorHex(), ui.getString(R.string.ui_text_color_hex),
                    ui.getString(R.string.ui_text_color_invalid), enabled, Modifier.weight(1f), onDraftChanged)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(enabled = enabled, onClick = {
                    onDraftChanged(draft.withTextBox(null, null))
                }) { Text((if (style.boxWidth == null) "✓ " else "") + ui.getString(R.string.ui_text_point)) }
                TextButton(enabled = enabled, onClick = {
                    if (style.boxWidth == null)
                        onDraftChanged(draft.withTextBox(320.0, 160.0))
                }) { Text((if (style.boxWidth != null) "✓ " else "") + ui.getString(R.string.ui_text_box)) }
            }
            if (style.boxWidth != null && style.boxHeight != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StyleInputField(draft, TextStyleField.BOX_WIDTH, style.boxWidth.toString(), ui.getString(R.string.ui_text_box_width),
                        ui.getString(R.string.ui_text_numeric_invalid), enabled, Modifier.weight(1f), onDraftChanged)
                    StyleInputField(draft, TextStyleField.BOX_HEIGHT, style.boxHeight.toString(), ui.getString(R.string.ui_text_box_height),
                        ui.getString(R.string.ui_text_numeric_invalid), enabled, Modifier.weight(1f), onDraftChanged)
                }
                Text(ui.getString(R.string.ui_text_box_wrap), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.getString(R.string.ui_paragraph) + ":")
                TextButton(enabled = enabled, onClick = { onDraftChanged(draft.copy(style = style.copy(alignment = TextParagraphAlignment.LEFT))) }) { Text(if (style.alignment == TextParagraphAlignment.LEFT) "✓ ${ui.getString(R.string.ui_align_left)}" else ui.getString(R.string.ui_align_left)) }
                TextButton(enabled = enabled, onClick = { onDraftChanged(draft.copy(style = style.copy(alignment = TextParagraphAlignment.CENTER))) }) { Text(if (style.alignment == TextParagraphAlignment.CENTER) "✓ ${ui.getString(R.string.ui_align_center)}" else ui.getString(R.string.ui_align_center)) }
                TextButton(enabled = enabled, onClick = { onDraftChanged(draft.copy(style = style.copy(alignment = TextParagraphAlignment.RIGHT))) }) { Text(if (style.alignment == TextParagraphAlignment.RIGHT) "✓ ${ui.getString(R.string.ui_align_right)}" else ui.getString(R.string.ui_align_right)) }
            }
            OutlinedTextField(value = draft.textFieldValue(), enabled = enabled,
                onValueChange = { onDraftChanged(draft.update(it, retainSelection = !contentFocused)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp)
                    .onFocusChanged {
                        val leaving = contentFocused && !it.isFocused
                        contentFocused = it.isFocused
                        // onBlur may publish a collapsed value before this callback.
                        // The range shown by this composition is the one the user selected.
                        if (leaving && draft.selectionStart != draft.selectionEnd)
                            onDraftChanged(draft.copy(composingStart = -1, composingEnd = -1))
                    }, label = { Text(ui.getString(R.string.ui_text_content)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), minLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, enabled = enabled) { Text(ui.getString(R.string.ui_cancel)) }
                Button(onClick = { onCommit(style.validate()) }, enabled = enabled && !draft.hasComposition && !draft.hasInvalidStyleInput) { Text(ui.getString(R.string.ui_apply)) }
            }
        }
    }
}

/** Raw input belongs to the saved draft, including text that cannot yet update the style. */
@Composable
private fun StyleInputField(draft: TextEditorDraft, field: TextStyleField, fallback: String, label: String,
    error: String, enabled: Boolean, modifier: Modifier, onDraftChanged: (TextEditorDraft) -> Unit) {
    val invalid = draft.invalidStyleField(field)
    OutlinedTextField(value = draft.styleInputs[field] ?: fallback, enabled = enabled, onValueChange = { input ->
        val value = if (field == TextStyleField.COLOR) input.uppercase() else input
        onDraftChanged(draft.editStyleField(field, value))
    }, label = { Text(label) }, singleLine = true, isError = invalid,
        supportingText = if (invalid) ({ Text(error) }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = modifier.padding(vertical = 2.dp))
}

private fun TextLayerModel.colorHex(): String {
    fun channel(value: Double) = (value * 255.0).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(channel(red), channel(green), channel(blue))
}

private fun parseTextColor(value: String): DoubleArray? {
    val digits = value.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val color = digits.toLongOrNull(16) ?: return null
    return doubleArrayOf(((color shr 16) and 255) / 255.0, ((color shr 8) and 255) / 255.0, (color and 255) / 255.0)
}
