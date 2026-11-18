package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions

internal fun layerLabel(layer: LayerState): String =
    layer.name.ifEmpty { if (layer.isGroup) "Group ${layer.id}" else "Layer ${layer.id}" }

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun LayerPanel(state: EditorState, selected: Long, select: (Long) -> Unit,
    command: (EditorCommand) -> Unit, maskTarget: Boolean, setMaskTarget: (Boolean) -> Unit, modifier: Modifier,
    enabled: Boolean = true, selectionEnabled: Boolean = enabled, targetEnabled: Boolean = enabled,
    selectedRoots: Set<Long> = setOf(selected), selectModified: (Long, Boolean, Boolean) -> Unit = { id, _, _ -> select(id) },
    collapsedGroups: Set<Long> = emptySet(), setGroupCollapsed: (Long, Boolean) -> Unit = { _, _ -> }) {
    val ui = LocalContext.current
    var options by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LayerState?>(null) }
    var choosingParent by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var filterTypes by remember { mutableStateOf(emptySet<LayerFilterType>()) }
    val panelScroll = rememberScrollState()
    val rowScroll = rememberScrollState()
    val layer = state.layers.firstOrNull { it.id == selected }
    val layerActions = state.selectedLayerActions?.takeIf { it.primary == selected && it.revision == state.revision }
    val repeatedTitles = remember(state.layers, ui.resources.configuration) {
        state.layers.groupingBy { ui.layerTitle(it) }.eachCount().filterValues { it > 1 }.keys
    }
    fun panelTitle(item: LayerState): String = if (ui.layerTitle(item) in repeatedTitles)
        ui.getString(R.string.layer_panel_named_id, ui.layerTitle(item), item.id) else ui.layerTitle(item)
    fun targetLabel(item: LayerState): String = ui.getString(when {
        maskTarget && item.hasMask -> R.string.layer_panel_target_mask
        item.isGroup -> R.string.layer_panel_target_group
        else -> R.string.layer_panel_target_content
    })
    val displayLayers = remember(state.layers, collapsedGroups, searchText, filterTypes, selectedRoots) {
        filteredDisplayedLayers(state.layers, collapsedGroups, searchText, filterTypes, selectedRoots)
    }
    val filtering = searchText.isNotBlank() || filterTypes.isNotEmpty()
    fun action(operation: EditorCommand) { if (enabled) command(operation); options = false }
    var opacity by remember(layer?.id, layer?.opacity) { mutableStateOf(layer?.opacity?.toFloat() ?: 1f) }
    var opacityChanged by remember(layer?.id, layer?.opacity) { mutableStateOf(false) }
    @Composable fun RowScope.opacityControl() {
        Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        Slider(value = opacity, enabled = enabled, onValueChange = { opacity = it; opacityChanged = true },
            onValueChangeFinished = { if (opacityChanged && layer != null) {
                command(EditorCommand.SetOpacity(layer.id, opacity.toDouble())); opacityChanged = false
            } }, modifier = Modifier.weight(1f))
    }
    @Composable fun layerCount() {
        Text(if (filtering) "${displayLayers.size}/${state.layers.size}" else "${state.layers.size}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 8.dp))
    }
    @Composable fun targetSummary(showCount: Boolean = false) {
        if (showCount) layerCount()
        Text(if (layer == null) ui.getString(R.string.layer_panel_no_target)
            else ui.getString(R.string.layer_panel_current_target, panelTitle(layer), targetLabel(layer)),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.getString(R.string.layer_panel_multi_select), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(48.dp))
            Text(ui.getString(R.string.layer_panel_tap_to_edit), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ui.getString(R.string.layer_panel_visibility), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(56.dp))
        }
    }
    BoxWithConstraints(modifier) {
        // A phone dock has about 200 dp below its panel picker. Keep its actions fixed
        // while allowing the descriptive headings to scroll away from the layer rows.
        val compact = maxHeight < 320.dp
        val scrollAll = maxHeight < 160.dp
        val panelHeight = constraints.maxHeight
        val listDescription = ui.getString(R.string.ui_layer_list)
        // A short IME viewport cannot reserve a search header and action footer.
        // Keep the search field's scroll/focus ancestors attached across IME changes.
        // At ordinary heights the finite content height leaves this scroll range at zero.
        val panelMouseScroll = if (scrollAll) Modifier.layerMouseScrolling(panelScroll) else Modifier
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp).semantics { if (scrollAll) contentDescription = listDescription }
            .then(panelMouseScroll).verticalScroll(panelScroll)
            .layout { measurable, incoming ->
                val contentConstraints = if (scrollAll) incoming
                    else incoming.copy(minHeight = panelHeight, maxHeight = panelHeight)
                val content = measurable.measure(contentConstraints)
                layout(content.width, content.height) { content.placeRelative(0, 0) }
            }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (searchOpen) {
                    OutlinedTextField(value = searchText, onValueChange = { searchText = it }, singleLine = true,
                        placeholder = { Text(ui.getString(R.string.ui_search_layers)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            contentDescription = ui.getString(R.string.ui_search_layers)
                        })
                } else {
                    Text(ui.getString(R.string.ui_layers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                IconButton(onClick = {
                    if (searchOpen) searchText = ""
                    searchOpen = !searchOpen
                }, modifier = Modifier.semantics {
                    contentDescription = ui.getString(if (searchOpen) R.string.ui_close_search else R.string.ui_search_layers)
                }) { Text(if (searchOpen) "×" else "⌕") }
                Box {
                    IconButton(onClick = { filterMenuOpen = true }, modifier = Modifier.semantics {
                        contentDescription = ui.getString(R.string.ui_filter_layers)
                    }) { Text(if (filterTypes.isEmpty()) "≡" else "≣") }
                    DropdownMenu(expanded = filterMenuOpen, onDismissRequest = { filterMenuOpen = false }) {
                        DropdownMenuItem(text = { Text(ui.getString(R.string.ui_filter_all_layers)) },
                            onClick = { filterTypes = emptySet(); filterMenuOpen = false })
                        HorizontalDivider()
                        @Composable fun item(type: LayerFilterType, label: Int) {
                            DropdownMenuItem(text = { Text(ui.getString(label)) },
                                trailingIcon = { Checkbox(checked = type in filterTypes, onCheckedChange = null) },
                                onClick = { filterTypes = if (type in filterTypes) filterTypes - type else filterTypes + type })
                        }
                        item(LayerFilterType.IMAGES, R.string.ui_filter_images)
                        item(LayerFilterType.SHAPES, R.string.ui_filter_shapes)
                        item(LayerFilterType.TEXT, R.string.ui_filter_text)
                        item(LayerFilterType.FOLDERS, R.string.ui_filter_folders)
                        item(LayerFilterType.ADJUSTMENTS, R.string.ui_filter_adjustments)
                        item(LayerFilterType.SELECTED, R.string.ui_filter_selected)
                    }
                }
                if (!scrollAll) layerCount()
            }
            if (!compact) targetSummary()
            val rowsModifier = if (scrollAll) Modifier.fillMaxWidth() else Modifier.weight(1f, fill = false)
                .semantics { contentDescription = listDescription }.layerMouseScrolling(rowScroll).verticalScroll(rowScroll)
            Column(rowsModifier) {
                if (compact) targetSummary(showCount = scrollAll)
                if (displayLayers.isEmpty() && filtering) {
                    Text(ui.getString(R.string.ui_no_layers_match_filter), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                displayLayers.forEach { item ->
                    var shiftClick by remember(item.id) { mutableStateOf(false) }
                    var toggleClick by remember(item.id) { mutableStateOf(false) }
                    var depth = 0
                    var ancestor = item.parentId
                    while (ancestor != 0L && depth < 64) {
                        depth++
                        ancestor = state.layers.firstOrNull { it.id == ancestor }?.parentId ?: 0
                    }
                    Row(Modifier.fillMaxWidth()
                        .semantics {
                            contentDescription = ui.getString(R.string.ui_select_layer, panelTitle(item))
                            // Selected=true suppresses accessibility clicks in Compose. Selecting an
                            // included layer still clears other roots; the checkbox exposes membership.
                            role = Role.Button
                            stateDescription = ui.getString(if (item.id == selected)
                                R.string.layer_panel_editing_target_state else R.string.layer_panel_not_editing_target_state)
                            // The labelled row needs its own action beside the child toggles.
                            if (selectionEnabled) onClick { select(item.id); true } else disabled()
                        }
                        .background(if (item.id in selectedRoots) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .pointerInput(item.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press) {
                                        shiftClick = event.keyboardModifiers.isShiftPressed
                                        toggleClick = event.keyboardModifiers.isMetaPressed || event.keyboardModifiers.isCtrlPressed
                                    }
                                }
                            }
                        }
                        .combinedClickable(enabled = selectionEnabled,
                            onClick = {
                            val shift = shiftClick; val toggle = toggleClick
                            shiftClick = false; toggleClick = false
                            if (shift || toggle) selectModified(item.id, shift, toggle) else select(item.id)
                            },
                            // PhotoCraft treats a double click on the layer name as an
                            // immediate rename. Keep the existing RenameLayer command and
                            // dialog so validation/history remain centralized in the editor.
                            onDoubleClick = { if (enabled) renaming = item })
                        .padding(start = (minOf(depth, 4) * 12 + 8).dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (item.isGroup) IconButton(onClick = { setGroupCollapsed(item.id, item.id !in collapsedGroups) },
                            modifier = Modifier.size(32.dp).semantics {
                                contentDescription = ui.getString(if (item.id in collapsedGroups) R.string.ui_expand_layer else R.string.ui_collapse_layer, ui.layerTitle(item))
                            }) { Text(if (item.id in collapsedGroups) "▸" else "▾") }
                        Checkbox(checked = item.id in selectedRoots, enabled = selectionEnabled,
                            onCheckedChange = { selectModified(item.id, false, true) },
                            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_include_layer, panelTitle(item)) })
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(panelTitle(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.id == selected) Text(ui.getString(R.string.layer_panel_editing, targetLabel(item)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            // LayerState exposes group/content presence, not the typed source kind.
                            // Text and shapes can have pixel caches, so do not call those raster layers.
                            val kind = ui.getString(when {
                                item.isGroup -> R.string.layer_panel_kind_group
                                item.assetPresent -> R.string.layer_panel_kind_pixel_content
                                else -> R.string.layer_panel_kind_layer
                            })
                            val dimensions = if (!item.isGroup && item.assetPresent && item.pixelWidth > 0 && item.pixelHeight > 0)
                                ui.getString(R.string.layer_panel_source_dimensions, item.pixelWidth, item.pixelHeight)
                            else null
                            Text(listOfNotNull(ui.getString(R.string.layer_panel_id, item.id), kind, dimensions).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val details = buildList {
                                if (item.hasMask) add(if (item.maskEnabled) ui.getString(R.string.ui_mask) else ui.getString(R.string.ui_mask_disabled))
                                if (item.maskSourceId != 0L) add(ui.getString(R.string.ui_clipped))
                            }
                            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(checked = item.visible, enabled = enabled,
                                onCheckedChange = { value -> command(EditorCommand.SetVisible(item.id, value)) },
                                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_layer_visibility, panelTitle(item)) })
                            Text(ui.getString(if (item.visible) R.string.layer_panel_visible else R.string.layer_panel_hidden),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            @Composable fun optionsButton() {
                TextButton(enabled = enabled || targetEnabled, onClick = {
                    options = true
                    if (enabled) command(EditorCommand.RefreshSelectedLayerActions)
                }, modifier = if (scrollAll) Modifier.heightIn(min = 48.dp) else Modifier) { Text(ui.getString(R.string.ui_layer_options)) }
            }
            if (scrollAll) {
                FlowRow(Modifier.fillMaxWidth()) {
                    if (layer != null) optionsButton()
                    TextButton(enabled = enabled, onClick = { command(EditorCommand.AddLayer) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(ui.getString(R.string.ui_add_layer)) }
                    TextButton(enabled = enabled, onClick = { command(EditorCommand.AddGroup) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(ui.getString(R.string.ui_group)) }
                }
            } else {
                if (layer != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    optionsButton()
                    if (compact) {
                        TextButton(enabled = enabled, onClick = { command(EditorCommand.AddLayer) }) { Text(ui.getString(R.string.ui_add_layer)) }
                        TextButton(enabled = enabled, onClick = { command(EditorCommand.AddGroup) }) { Text(ui.getString(R.string.ui_group)) }
                    } else opacityControl()
                }
                if (!compact || layer == null) Row {
                    TextButton(enabled = enabled, onClick = { command(EditorCommand.AddLayer) }) { Text(ui.getString(R.string.ui_add_layer)) }
                    TextButton(enabled = enabled, onClick = { command(EditorCommand.AddGroup) }) { Text(ui.getString(R.string.ui_group)) }
                }
            }
        }
        if (options && layer != null) AlertDialog(onDismissRequest = { options = false },
            title = { Text(ui.layerTitle(layer)) }, confirmButton = { TextButton(onClick = { options = false }) { Text(ui.getString(R.string.ui_done)) } },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (compact) {
                        Text(ui.getString(R.string.ui_opacity), style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) { opacityControl() }
                    }
                    LayerPositionMenus(enabled && selectedRoots.isNotEmpty(), enabled && selectedRoots.size > 2,
                        { action(EditorCommand.AlignLayers(it, selectedRoots.toList())) },
                        { action(EditorCommand.DistributeLayers(it, selectedRoots.toList())) })
                    HorizontalDivider()
                    TextButton(enabled = enabled && layerActions?.canVisibility == true, onClick = {
                        layerActions?.let { action(EditorCommand.ToggleSelectedVisibility(it)) }
                    }) {
                        val show = layerActions?.visibilityValue ?: !layer.visible
                        val multiple = (layerActions?.targetCount ?: selectedRoots.size) > 1
                        Text(ui.getString(if (show) {
                            if (multiple) R.string.ui_show_layers else R.string.ui_show_layer
                        } else if (multiple) R.string.ui_hide_layers else R.string.ui_hide_layer))
                    }
                    Row {
                        TextButton(enabled = enabled, onClick = { options = false; renaming = layer }) { Text(ui.getString(R.string.ui_rename)) }
                        TextButton(enabled = enabled, onClick = { options = false; deleting = true }) { Text(ui.getString(R.string.ui_delete)) }
                    }
                    val siblings = state.layers.filter { it.parentId == layer.parentId }
                    val siblingIndex = siblings.indexOfFirst { it.id == layer.id }
                    Row {
                        TextButton(onClick = { action(EditorCommand.MoveLayer(layer.id, state.layers.indexOfFirst { it.id == siblings[siblingIndex + 1].id })) }, enabled = enabled && siblingIndex < siblings.lastIndex) { Text(ui.getString(R.string.ui_move_up)) }
                        TextButton(onClick = { action(EditorCommand.MoveLayer(layer.id, state.layers.indexOfFirst { it.id == siblings[siblingIndex - 1].id })) }, enabled = enabled && siblingIndex > 0) { Text(ui.getString(R.string.ui_move_down)) }
                    }
                    Text(ui.getString(R.string.ui_folder, state.layers.firstOrNull { it.id == layer.parentId }?.let { ui.layerTitle(it) } ?: ui.getString(R.string.ui_root)),
                        style = MaterialTheme.typography.labelMedium)
                    Row {
                        TextButton(enabled = enabled, onClick = { options = false; choosingParent = true }) { Text(ui.getString(R.string.ui_move_into_group)) }
                        TextButton(onClick = { action(EditorCommand.ReparentLayer(layer.id, 0)) }, enabled = enabled && layer.parentId != 0L) { Text(ui.getString(R.string.ui_move_to_root)) }
                    }
                    HorizontalDivider()
                    if (!layer.hasMask) {
                        Text(ui.getString(R.string.ui_layer_mask), style = MaterialTheme.typography.labelMedium)
                        Row {
                            TextButton(enabled = enabled, onClick = { action(EditorCommand.CreateMask(layer.id, true)) }) { Text(ui.getString(R.string.ui_white_mask)) }
                            TextButton(enabled = enabled, onClick = { action(EditorCommand.CreateMask(layer.id, false)) }) { Text(ui.getString(R.string.ui_black_mask)) }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = layer.maskEnabled, enabled = enabled, onCheckedChange = { value -> action(EditorCommand.EnableMask(layer.id, value)) },
                                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_enable_mask) })
                            Text(ui.getString(R.string.ui_enable_mask))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = layer.maskLinked, enabled = enabled, onCheckedChange = { value -> action(EditorCommand.LinkMask(layer.id, value)) },
                                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_link_mask) })
                            Text(ui.getString(R.string.ui_link_mask))
                        }
                        Row {
                            TextButton(enabled = targetEnabled, onClick = { setMaskTarget(!maskTarget); options = false }) {
                                Text(if (!maskTarget) ui.getString(R.string.ui_edit_mask) else if (layer.isGroup) ui.getString(R.string.ui_stop_editing_mask) else ui.getString(R.string.ui_edit_pixels))
                            }
                            TextButton(enabled = enabled, onClick = { setMaskTarget(false); action(EditorCommand.DeleteMask(layer.id)) }) { Text(ui.getString(R.string.ui_remove_mask)) }
                        }
                        Text(ui.getString(R.string.ui_black_hides_pixels_white_reveals_pixels_choose_the_mask_paint_color_in_the_tool_bar), style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                    if (layer.maskSourceId != 0L) {
                        Text(ui.getString(R.string.ui_clipped_to, state.layers.firstOrNull { it.id == layer.maskSourceId }?.let { ui.layerTitle(it) } ?: ui.getString(R.string.ui_missing_layer)))
                    }
                    TextButton(enabled = enabled && layerActions?.canClipping == true, onClick = {
                        layerActions?.let { action(EditorCommand.ToggleSelectedClipping(it)) }
                    }) {
                        Text(ui.getString(if (layerActions?.releasesClipping == true)
                            R.string.ui_release_clipping_mask else R.string.ui_create_clipping_mask))
                    }
                }
            })
    }
    renaming?.let { original ->
        var name by remember(original.id) { mutableStateOf(original.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(ui.getString(R.string.ui_rename_layer)) },
            text = { OutlinedTextField(value = name, enabled = enabled, onValueChange = { name = it }, label = { Text(ui.getString(R.string.ui_name)) },
                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_layer_name) }) },
            confirmButton = { TextButton(onClick = { command(EditorCommand.RenameLayer(original.id, name)); renaming = null },
                enabled = enabled && name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 16384 && !name.contains('\u0000')) { Text(ui.getString(R.string.ui_save_name)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(ui.getString(R.string.ui_cancel)) } })
    }
    if (choosingParent && layer != null) {
        fun isDescendant(candidate: LayerState): Boolean {
            var ancestor = candidate.parentId
            repeat(64) {
                if (ancestor == layer.id) return true
                if (ancestor == 0L) return false
                ancestor = state.layers.firstOrNull { it.id == ancestor }?.parentId ?: 0
            }
            return true
        }
        val groups = state.layers.filter { it.isGroup && it.id != layer.id && !isDescendant(it) }
        AlertDialog(onDismissRequest = { choosingParent = false }, title = { Text(ui.getString(R.string.ui_choose_group)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (groups.isEmpty()) Text(ui.getString(R.string.ui_add_a_group_first))
                    groups.forEach { group -> TextButton(enabled = enabled, onClick = { command(EditorCommand.ReparentLayer(layer.id, group.id)); choosingParent = false }) { Text(ui.layerTitle(group)) } }
                }
            }, confirmButton = { TextButton(onClick = { choosingParent = false }) { Text(ui.getString(R.string.ui_cancel)) } })
    }
    if (deleting && layer != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(ui.getString(R.string.ui_delete_layer_prompt, ui.layerTitle(layer))) },
        text = { Text(if (layer.isGroup) ui.getString(R.string.ui_this_deletes_the_group_and_all_of_its_children_undo_restores_them) else ui.getString(R.string.ui_undo_restores_this_layer)) },
        confirmButton = { TextButton(enabled = enabled, onClick = { command(EditorCommand.DeleteLayer(layer.id)); deleting = false }) { Text(ui.getString(R.string.ui_delete_layer)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(ui.getString(R.string.ui_cancel)) } })
}

@Composable
internal fun LayerPositionMenus(canAlign: Boolean, canDistribute: Boolean,
    align: (LayerAlignment) -> Unit, distribute: (LayerDistribution) -> Unit) {
    val ui = LocalContext.current
    var alignOpen by remember { mutableStateOf(false) }
    var distributeOpen by remember { mutableStateOf(false) }
    Row {
        Box {
            TextButton(enabled = canAlign, onClick = { alignOpen = true }) { Text(ui.getString(R.string.ui_align_layers)) }
            DropdownMenu(expanded = alignOpen, onDismissRequest = { alignOpen = false }) {
                LayerAlignment.entries.forEach { alignment ->
                    DropdownMenuItem(text = { Text(ui.layerAlignmentTitle(alignment)) }, onClick = {
                        alignOpen = false; align(alignment)
                    })
                }
            }
        }
        Box {
            TextButton(enabled = canDistribute, onClick = { distributeOpen = true }) { Text(ui.getString(R.string.ui_distribute_layers)) }
            DropdownMenu(expanded = distributeOpen, onDismissRequest = { distributeOpen = false }) {
                LayerDistribution.entries.forEach { distribution ->
                    DropdownMenuItem(text = { Text(ui.layerDistributionTitle(distribution)) }, onClick = {
                        distributeOpen = false; distribute(distribution)
                    })
                }
            }
        }
    }
}
