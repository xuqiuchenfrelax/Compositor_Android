package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** The mobile equivalent of PhotoCraft's right dock.
 *
 * The dock owns only presentation state. Layer edits still go through the same
 * EditorCommand path as the desktop-shaped layer panel; rotating or moving the
 * dock therefore cannot create a second document mutation path.
 */
internal enum class InspectorPanel(val label: String) {
    COLOR("Color"), PROPERTIES("Properties"), LAYERS("Layers"), HISTORY("History"), NAVIGATOR("Navigator"), BRUSHES("Brushes"), TYPE("Type"), ADJUSTMENTS("Adjustments")
}

@Composable
internal fun InspectorDock(panel: InspectorPanel, setPanel: (InspectorPanel) -> Unit,
    state: EditorState, selected: Long, select: (Long) -> Unit,
    command: (EditorCommand) -> Unit, maskTarget: Boolean, setMaskTarget: (Boolean) -> Unit,
    modifier: Modifier, enabled: Boolean, selectionEnabled: Boolean, targetEnabled: Boolean,
    selectedRoots: Set<Long>, selectModified: (Long, Boolean, Boolean) -> Unit,
    collapsedGroups: Set<Long>, setGroupCollapsed: (Long, Boolean) -> Unit,
    canvasWidth: Int, canvasHeight: Int, fit: () -> Unit, actual: () -> Unit, zoom: (Int) -> Unit,
    brushSettings: StrokeSettings, setBrushSettings: (StrokeSettings) -> Unit,
    foreground: Color, background: Color, chooseColor: (Boolean) -> Unit,
    swapColors: () -> Unit, defaultColors: () -> Unit,
    setForeground: (Color) -> Unit, setBackground: (Color) -> Unit,
    collapsed: Boolean = false, setCollapsed: (Boolean) -> Unit = {},
    textDraft: TextEditorDraft? = null, selectedText: Boolean = false,
    beginNewText: () -> Unit = {}, beginEditText: () -> Unit = {},
    updateTextDraft: (TextEditorDraft) -> Unit = {}, commitText: (TextLayerModel) -> Unit = {}, cancelText: () -> Unit = {},
    adjustmentDraft: AdjustmentEditorDraft? = null, selectedAdjustment: Boolean = false,
    selectedAdjustmentEditable: Boolean = true,
    beginNewAdjustment: (AdjustmentKind) -> Unit = {}, beginEditAdjustment: () -> Unit = {},
    updateAdjustmentDraft: (AdjustmentEditorDraft) -> Unit = {},
    commitAdjustment: (AdjustmentLayerModel) -> Unit = {}, cancelAdjustment: () -> Unit = {},
    levelsHistogram: LevelsHistogramState = LevelsHistogramState.Loading,
    levelsSamplingBusy: Boolean = false, adjustmentCreationBusy: Boolean = false,
    adjustmentPreviewFailed: Boolean = false,
    retryLevelsHistogram: () -> Unit = {}) {
    val ui = LocalContext.current
    var panelMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(collapsed) { panelMenuOpen = false }
    @Composable fun panelPicker(modifier: Modifier, rail: Boolean) {
        Box(modifier) {
            TextButton(onClick = { panelMenuOpen = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = ui.getString(R.string.ui_choose_inspector_panel, ui.panelTitle(panel))
                }, contentPadding = if (rail) PaddingValues(0.dp) else ButtonDefaults.TextButtonContentPadding) {
                Text(if (rail) ui.panelTitle(panel).take(1) else ui.panelTitle(panel), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("▾", Modifier.padding(start = if (rail) 0.dp else 8.dp))
            }
            DropdownMenu(expanded = panelMenuOpen, onDismissRequest = { panelMenuOpen = false }) {
                InspectorPanel.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(ui.panelTitle(item)) },
                        onClick = { panelMenuOpen = false; setPanel(item) },
                        trailingIcon = { if (item == panel) Text("✓") },
                        modifier = Modifier.semantics { this.selected = item == panel })
                }
            }
        }
    }
    @Composable fun collapseButton() {
        IconButton(onClick = { setCollapsed(true) },
            modifier = Modifier.size(48.dp).semantics { contentDescription = ui.getString(R.string.ui_collapse_inspector_dock) }) {
            Text("‹", fontSize = 22.sp)
        }
    }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Below 160 dp, a 56 dp search field, 48 dp action row and one 48 dp
        // layer target cannot fit below the picker. Put the picker beside the content.
        val short = maxHeight < 160.dp
        InspectorDockLayout(collapsed, short, Modifier.fillMaxSize(), header = {
            if (collapsed) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { setCollapsed(false) },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = ui.getString(R.string.ui_expand_inspector_dock) }) {
                        Text("›", fontSize = 22.sp)
                    }
                    InspectorPanel.entries.forEach { item ->
                        IconButton(onClick = { setPanel(item); setCollapsed(false) },
                            modifier = Modifier.size(48.dp).semantics {
                                contentDescription = ui.getString(R.string.ui_inspector_panel, ui.panelTitle(item))
                                this.selected = item == panel
                            }) {
                            Text(ui.panelTitle(item).take(1), fontSize = 13.sp,
                                color = if (item == panel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (short) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    panelPicker(Modifier.fillMaxWidth(), rail = true)
                    collapseButton()
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    panelPicker(Modifier.weight(1f), rail = false)
                    collapseButton()
                }
            }
        }) {
            if (!collapsed) when (panel) {
                InspectorPanel.COLOR -> ColorPanel(foreground, background, chooseColor, swapColors, defaultColors,
                    setForeground, setBackground,
                    Modifier.fillMaxSize(), enabled)
                InspectorPanel.LAYERS -> LayerPanel(state, selected, select, command, maskTarget, setMaskTarget,
                    Modifier.fillMaxSize(), enabled, selectionEnabled, targetEnabled,
                    selectedRoots, selectModified, collapsedGroups, setGroupCollapsed)
                InspectorPanel.PROPERTIES -> PropertiesPanel(state, selected, command, Modifier.fillMaxSize(), enabled)
                InspectorPanel.HISTORY -> HistoryPanel(state, command, Modifier.fillMaxSize(), enabled)
                InspectorPanel.NAVIGATOR -> NavigatorPanel(canvasWidth, canvasHeight, fit, actual, zoom,
                    Modifier.fillMaxSize(), enabled)
                InspectorPanel.BRUSHES -> BrushesPanel(brushSettings, setBrushSettings,
                    Modifier.fillMaxSize(), enabled)
                InspectorPanel.TYPE -> if (textDraft != null) {
                    TextLayerEditor(textDraft, updateTextDraft, commitText, cancelText, Modifier.fillMaxSize())
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.getString(R.string.ui_tool_type), style = MaterialTheme.typography.titleSmall)
                    Button(onClick = beginNewText, enabled = enabled) { Text(ui.getString(R.string.ui_new_text)) }
                    if (selectedText) OutlinedButton(onClick = beginEditText, enabled = enabled) {
                        Text(ui.getString(R.string.ui_edit_selected_text))
                    }
                }
                InspectorPanel.ADJUSTMENTS -> if (adjustmentDraft != null) {
                    AdjustmentLayerEditor(adjustmentDraft, updateAdjustmentDraft, commitAdjustment, cancelAdjustment,
                        Modifier.fillMaxSize(), levelsHistogram, retryLevelsHistogram, levelsSamplingBusy, adjustmentPreviewFailed)
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.getString(R.string.ui_adjustment_layer), style = MaterialTheme.typography.titleSmall)
                    if (adjustmentCreationBusy) Text(ui.getString(R.string.ui_adjustment_creating),
                        style = MaterialTheme.typography.bodySmall)
                    listOf(R.string.ui_adjustment_image_group to AdjustmentKind.imageAdjustments,
                        R.string.ui_adjustment_filter_group to AdjustmentKind.filters).forEach { (label, kinds) ->
                        Text(ui.getString(label), style = MaterialTheme.typography.titleSmall)
                        kinds.forEach { kind ->
                            OutlinedButton(onClick = { beginNewAdjustment(kind) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                                Text(ui.getString(kind.label))
                            }
                        }
                    }
                    if (selectedAdjustment) OutlinedButton(onClick = beginEditAdjustment, enabled = enabled && selectedAdjustmentEditable,
                        modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_edit_selected_adjustment)) }
                    if (selectedAdjustment && !selectedAdjustmentEditable)
                        Text(ui.getString(R.string.ui_adjustment_supported_blend_opacity))
                }
            }
        }
    }
}

/** The body keeps its composition position when the IME changes the available height. */
@Composable
private fun InspectorDockLayout(collapsed: Boolean, rail: Boolean, modifier: Modifier,
    header: @Composable () -> Unit, content: @Composable () -> Unit) {
    Layout(modifier = modifier, content = {
        Box { header() }
        if (!collapsed) {
            if (rail) VerticalDivider() else HorizontalDivider()
            Box { content() }
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (collapsed) {
            val top = measurables[0].measure(Constraints.fixed(width, height))
            layout(width, height) { top.placeRelative(0, 0) }
        } else if (rail) {
            val railWidth = minOf(48.dp.roundToPx(), width)
            val dividerWidth = minOf(1.dp.roundToPx(), width - railWidth)
            val top = measurables[0].measure(Constraints.fixed(railWidth, height))
            val divider = measurables[1].measure(Constraints.fixed(dividerWidth, height))
            val body = measurables[2].measure(Constraints.fixed(width - railWidth - dividerWidth, height))
            layout(width, height) {
                top.placeRelative(0, 0)
                divider.placeRelative(railWidth, 0)
                body.placeRelative(railWidth + dividerWidth, 0)
            }
        } else {
            val top = measurables[0].measure(constraints.copy(minWidth = width, minHeight = 0))
            val dividerHeight = minOf(1.dp.roundToPx(), height - top.height)
            val divider = measurables[1].measure(Constraints.fixed(width, dividerHeight))
            val body = measurables[2].measure(Constraints.fixed(width, height - top.height - dividerHeight))
            layout(width, height) {
                top.placeRelative(0, 0)
                divider.placeRelative(0, top.height)
                body.placeRelative(0, top.height + dividerHeight)
            }
        }
    }
}

@Composable
private fun ColorPanel(foreground: Color, background: Color, chooseColor: (Boolean) -> Unit,
    swapColors: () -> Unit, defaultColors: () -> Unit, setForeground: (Color) -> Unit,
    setBackground: (Color) -> Unit, modifier: Modifier, enabled: Boolean) {
    val ui = LocalContext.current
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ui.getString(R.string.ui_color), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ColorSwatch(ui.getString(R.string.ui_foreground), foreground, enabled) { chooseColor(false) }
            ColorSwatch(ui.getString(R.string.ui_background), background, enabled) { chooseColor(true) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = swapColors) { Text(ui.getString(R.string.ui_swap)) }
            OutlinedButton(enabled = enabled, onClick = defaultColors) { Text(ui.getString(R.string.ui_default)) }
        }
        Text(ui.getString(R.string.ui_swatches), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Color.Black to R.string.ui_black, Color.White to R.string.ui_white,
                Color.Red to R.string.ui_color_red, Color.Yellow to R.string.ui_color_yellow,
                Color.Green to R.string.ui_color_green, Color.Cyan to R.string.ui_color_cyan,
                Color.Blue to R.string.ui_color_blue, Color.Magenta to R.string.ui_color_magenta).forEach { (swatch, name) ->
                Button(onClick = { setForeground(swatch) }, enabled = enabled,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = ui.getString(R.string.ui_set_foreground_color, ui.getString(name))
                    }, contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = swatch,
                        contentColor = if (.299f * swatch.red + .587f * swatch.green + .114f * swatch.blue > .5f)
                            Color.Black else Color.White)) { }
            }
        }
        TextButton(enabled = enabled, onClick = { setBackground(foreground) }) { Text(ui.getString(R.string.ui_use_foreground_as_background)) }
        Text(ui.getString(R.string.ui_tap_a_large_color_tile_to_open_the_picker_swatches_set_foreground), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.ColorSwatch(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color,
                contentColor = if (.299f * color.red + .587f * color.green + .114f * color.blue > .5f)
                    Color.Black else Color.White)) {
            Text(label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private data class BrushPreset(val name: Int, val diameter: Float, val hardness: Float,
    val opacity: Float, val smoothing: Float)

private val brushPresets = listOf(
    BrushPreset(R.string.ui_basic, 40f, 1f, 1f, 0f),
    BrushPreset(R.string.ui_soft, 96f, .18f, .72f, 12f),
    BrushPreset(R.string.ui_airbrush, 180f, 0f, .28f, 24f),
    BrushPreset(R.string.ui_ink, 18f, 1f, 1f, 4f),
    BrushPreset(R.string.ui_pencil, 8f, .82f, .86f, 18f),
    BrushPreset(R.string.ui_large, 240f, .52f, .64f, 8f),
)

@Composable
private fun BrushesPanel(settings: StrokeSettings, setSettings: (StrokeSettings) -> Unit,
    modifier: Modifier, enabled: Boolean) {
    val ui = LocalContext.current
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(ui.getString(R.string.ui_brushes), style = MaterialTheme.typography.titleSmall)
        Text(ui.getString(R.string.ui_preset_changes_apply_to_the_next_stroke), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        brushPresets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val selected = settings.diameter == preset.diameter &&
                        settings.hardness == preset.hardness && settings.opacity == preset.opacity &&
                        settings.smoothing == preset.smoothing
                    OutlinedButton(onClick = {
                        setSettings(settings.copy(diameter = preset.diameter, hardness = preset.hardness,
                            opacity = preset.opacity, smoothing = preset.smoothing))
                    }, enabled = enabled, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface)) {
                        Text(ui.getString(preset.name))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(ui.getString(R.string.ui_brush_summary, settings.diameter.toInt(), (settings.hardness * 100).toInt()),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PropertiesPanel(state: EditorState, selected: Long, command: (EditorCommand) -> Unit,
    modifier: Modifier, enabled: Boolean) {
    val ui = LocalContext.current
    val layer = state.layers.firstOrNull { it.id == selected }
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (layer == null) {
            Text(ui.getString(R.string.ui_no_layer_selected), style = MaterialTheme.typography.titleSmall)
            return@Column
        }
        Text(ui.layerTitle(layer), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_layer_properties, ui.layerTitle(layer)) })
        Text(if (layer.isGroup) ui.getString(R.string.ui_group) else ui.getString(R.string.ui_pixel_layer), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.getString(R.string.ui_visible), Modifier.weight(1f))
            Switch(checked = layer.visible, enabled = enabled,
                onCheckedChange = { command(EditorCommand.SetVisible(layer.id, it)) })
        }
        var opacity by remember(layer.id, layer.opacity) { mutableStateOf(layer.opacity.toFloat()) }
        Text(ui.getString(R.string.ui_opacity_summary, String.format(Locale.ROOT, "%.0f%%", opacity * 100)))
        Slider(value = opacity, enabled = enabled, onValueChange = { opacity = it },
            onValueChangeFinished = { command(EditorCommand.SetOpacity(layer.id, opacity.toDouble())) })
        Text(ui.getString(R.string.ui_position), style = MaterialTheme.typography.labelMedium)
        val box = layer.transform
        Text(ui.getString(R.string.ui_position_summary, box.x, box.y), style = MaterialTheme.typography.bodySmall)
        Text(ui.getString(R.string.ui_transform_summary, box.width, box.height, box.rotation),
            style = MaterialTheme.typography.bodySmall)
        if (layer.hasMask) Text(if (layer.maskEnabled) ui.getString(R.string.ui_mask_enabled) else ui.getString(R.string.ui_mask_disabled),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryPanel(state: EditorState, command: (EditorCommand) -> Unit,
    modifier: Modifier, enabled: Boolean) {
    val ui = LocalContext.current
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(ui.getString(R.string.ui_history), style = MaterialTheme.typography.titleSmall)
        Text(ui.getString(R.string.ui_revision, state.revision), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled && state.undo, onClick = { command(EditorCommand.Undo(false)) }) { Text(ui.getString(R.string.ui_undo)) }
            Button(enabled = enabled && state.redo, onClick = { command(EditorCommand.Undo(true)) }) { Text(ui.getString(R.string.ui_redo)) }
        }
        Text(if (state.undo) ui.getString(R.string.ui_previous_document_state_available) else ui.getString(R.string.ui_no_earlier_state),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavigatorPanel(canvasWidth: Int, canvasHeight: Int, fit: () -> Unit, actual: () -> Unit, zoom: (Int) -> Unit,
    modifier: Modifier, enabled: Boolean) {
    val ui = LocalContext.current
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(ui.getString(R.string.ui_navigator), style = MaterialTheme.typography.titleSmall)
        Text(ui.getString(R.string.ui_canvas_pixels, canvasWidth, canvasHeight), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = enabled, onClick = fit) { Text(ui.getString(R.string.ui_fit)) }
            OutlinedButton(enabled = enabled, onClick = actual,
                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_actual_pixels) }) { Text("100%") }
            OutlinedButton(enabled = enabled, onClick = { zoom(-1) },
                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_zoom_out) }) { Text("−") }
            OutlinedButton(enabled = enabled, onClick = { zoom(1) },
                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_zoom_in) }) { Text("+") }
        }
        Text(ui.getString(R.string.ui_pinch_or_use_two_fingers_on_the_canvas_to_navigate), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
