package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ToolHeader(tool: EditorTool, erasing: Boolean, settings: StrokeSettings, foreground: Color,
    maskTarget: Boolean, hasMask: Boolean, pixelTargetAvailable: Boolean, enabled: Boolean, zoomPercent: Double,
    setErasing: (Boolean) -> Unit, setSettings: (StrokeSettings) -> Unit, setMaskTarget: (Boolean) -> Unit,
    setZoom: (Double) -> Unit, chooseForeground: () -> Unit, fieldFocus: (Boolean) -> Unit,
    fieldComplete: () -> Unit, showsSampleRing: Boolean = true, setSampleRing: (Boolean) -> Unit = {},
    smearMode: SmearMode = SmearMode.SMUDGE, setSmearMode: (SmearMode) -> Unit = {},
    healingMode: HealingMode = HealingMode.CONTENT_AWARE, setHealingMode: (HealingMode) -> Unit = {},
    moveBox: Transform64? = null, moveEditable: Boolean = false, movePending: Boolean = false,
    moveSettling: Boolean = false, setMoveField: (MoveField, Double, MoveFieldSession?) -> Boolean = { _, _, _ -> false },
    cancelMove: () -> Unit = {}, moveSwitchPending: Boolean = false,
    startTransform: () -> Unit = {}, applyTransform: () -> Unit = {},
    lockRatio: Boolean = true, setLockRatio: (Boolean) -> Unit = {},
    showControls: Boolean = true, setShowControls: (Boolean) -> Unit = {},
    movePersistent: Boolean = false, moveScalePercent: Double? = null,
    beginMoveField: () -> MoveFieldSession? = { null },
    finishMoveField: (MoveFieldSession?, Boolean) -> Unit = { _, _ -> },
    setMoveScale: (Double, MoveFieldSession?) -> Boolean = { _, _ -> false },
    beginMoveScrub: (TransformScrubField) -> MoveScrubSession? = { null },
    updateMoveScrub: (MoveScrubSession, Double) -> Unit = { _, _ -> },
    finishMoveScrub: (MoveScrubSession, Boolean) -> Unit = { _, _ -> },
    canFlipMove: Boolean = false, flipMove: (Boolean) -> Unit = {},
    canAlignLayers: Boolean = false, canDistributeLayers: Boolean = false,
    alignLayers: (LayerAlignment) -> Unit = {}, distributeLayers: (LayerDistribution) -> Unit = {},
    autoSelect: Boolean = false, setAutoSelect: (Boolean) -> Unit = {},
    duplicateArmed: Boolean = false, setDuplicateArmed: (Boolean) -> Unit = {},
    distortFreeCorners: Boolean = true, setDistortFreeCorners: (Boolean) -> Unit = {},
    distortActive: Boolean = false, distortReady: Boolean = false,
    applyDistort: () -> Unit = {}, cancelDistort: () -> Unit = {},
    applyCrop: () -> Unit = {}, cancelCrop: () -> Unit = {},
    cropRatioChoice: CropRatioChoice = CropRatioChoice.FREE,
    setCropRatioChoice: (CropRatioChoice) -> Unit = {},
    customCropRatioText: String = "4:5",
    setCustomCropRatio: (String) -> Unit = {},
    shapeKind: ShapeKind = ShapeKind.RECTANGLE, setShapeKind: (ShapeKind) -> Unit = {},
    shapeLineWidth: Double = 4.0, setShapeLineWidth: (Double) -> Unit = {},
    shapeLineStyle: ShapeLineStyle = ShapeLineStyle.SOLID, setShapeLineStyle: (ShapeLineStyle) -> Unit = {},
    shapeLineCap: ShapeLineCap = ShapeLineCap.SQUARE, setShapeLineCap: (ShapeLineCap) -> Unit = {},
    shapeCornerRadius: Double = 0.0, setShapeCornerRadius: (Double) -> Unit = {},
    gradientKind: GradientKind = GradientKind.LINEAR, setGradientKind: (GradientKind) -> Unit = {},
    gradientStyle: GradientStyle = GradientStyle.FOREGROUND_TO_TRANSPARENT, setGradientStyle: (GradientStyle) -> Unit = {},
    gradientReversed: Boolean = false, setGradientReversed: (Boolean) -> Unit = {},
    gradientOpacity: Double = 1.0, setGradientOpacity: (Double) -> Unit = {},
    gradientPending: Boolean = false, applyGradient: () -> Unit = {}, cancelGradient: () -> Unit = {},
    marqueeActive: Boolean = false, marqueeShape: Int = PixelSelectionShape.RECTANGLE,
    marqueeOperation: Int = PixelSelectionOperation.REPLACE,
    setMarqueeShape: (Int) -> Unit = {}, setMarqueeOperation: (Int) -> Unit = {},
    fillSelection: () -> Unit = {}, cutSelection: () -> Unit = {}, clearSelection: () -> Unit = {},
    modifySelection: (Int, Int) -> Unit = { _, _ -> },
    selectionAmountSupported: (Int) -> Boolean = { true },
    selectionPending: Boolean = false,
    magicTolerance: Int = 32, magicSampleRadius: Int = 0, magicContiguous: Boolean = true,
    setMagicTolerance: (Int) -> Unit = {}, setMagicSampleRadius: (Int) -> Unit = {},
    setMagicContiguous: (Boolean) -> Unit = {},
    cloneAligned: Boolean = true, cloneAllLayers: Boolean = false, cloneSourceArmed: Boolean = false,
    singleRow: Boolean = false,
    setCloneAligned: (Boolean) -> Unit = {}, setCloneAllLayers: (Boolean) -> Unit = {},
    armCloneSource: () -> Unit = {}, cloneSourceEnabled: Boolean = false, penSeen: Boolean = false) {
    val ui = LocalContext.current
    val focus = LocalFocusManager.current
    var selectionModifyAmount by rememberSaveable { mutableIntStateOf(5) }
    @Composable fun moveChoices() {
        TextButton(onClick = { setAutoSelect(!autoSelect) }, enabled = enabled && !moveSettling && !moveSwitchPending,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_move_auto_select);
                toggleableState = if (autoSelect) ToggleableState.On else ToggleableState.Off }) {
            Text(if (autoSelect) ui.getString(R.string.ui_auto_select_on) else ui.getString(R.string.ui_auto_select_off))
        }
        TextButton(onClick = startTransform, enabled = moveEditable && !movePending && !moveSwitchPending,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_start_transform) }) { Text(ui.getString(R.string.ui_transform_ctrl_t)) }
        if (movePersistent) {
        TextButton(onClick = applyTransform, enabled = movePending && !moveSettling && !moveSwitchPending,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_apply_transform) }) { Text(ui.getString(R.string.ui_apply)) }
        TextButton(onClick = cancelMove, enabled = movePending && !moveSettling && !moveSwitchPending,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_cancel_transform) }) { Text(ui.getString(R.string.ui_cancel)) }
        }
        TextButton(onClick = { setLockRatio(!lockRatio) },
            enabled = enabled && !moveSettling && !moveSwitchPending,
            colors = ButtonDefaults.textButtonColors(containerColor = if (lockRatio) Color.White.copy(alpha = .12f) else Color.Transparent),
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_move_lock_ratio);
                toggleableState = if (lockRatio) ToggleableState.On else ToggleableState.Off }) { Text(ui.getString(R.string.ui_lock_ratio)) }
        TextButton(onClick = { setShowControls(!showControls) },
            enabled = enabled && !moveSettling && !moveSwitchPending,
            colors = ButtonDefaults.textButtonColors(containerColor = if (showControls) Color.White.copy(alpha = .12f) else Color.Transparent),
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_move_show_controls);
                toggleableState = if (showControls) ToggleableState.On else ToggleableState.Off }) { Text(ui.getString(R.string.ui_show_controls)) }
    }
    @Composable fun maskChoices() {
        HeaderChoice(ui.getString(R.string.ui_pixels), !maskTarget, enabled && pixelTargetAvailable && !moveSwitchPending) { setMaskTarget(false) }
        HeaderChoice(ui.getString(R.string.ui_mask), maskTarget, enabled && !moveSwitchPending) { setMaskTarget(true) }
        if (maskTarget && tool == EditorTool.BRUSH) {
            HeaderChoice(ui.getString(R.string.ui_black_hide), !settings.maskPaintWhite, enabled) { setSettings(settings.copy(maskPaintWhite = false)) }
            HeaderChoice(ui.getString(R.string.ui_white_reveal), settings.maskPaintWhite, enabled) { setSettings(settings.copy(maskPaintWhite = true)) }
        }
    }
    @Composable fun cloneChoices(hintModifier: Modifier) {
        TextButton(onClick = armCloneSource, enabled = cloneSourceEnabled,
            modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_clone_set_source) }) {
            Text(if (cloneSourceArmed) ui.getString(R.string.ui_cancel_source) else ui.getString(R.string.ui_set_source))
        }
        HeaderChoice(ui.getString(R.string.ui_aligned), cloneAligned, enabled) { setCloneAligned(!cloneAligned) }
        HeaderChoice(ui.getString(R.string.ui_current_layer), !cloneAllLayers, enabled) { setCloneAllLayers(false) }
        HeaderChoice(ui.getString(R.string.ui_all_layers), cloneAllLayers, enabled) { setCloneAllLayers(true) }
        Text(if (cloneSourceArmed) ui.getString(R.string.ui_tap_canvas_to_set_source) else ui.getString(R.string.ui_set_source_then_paint_alt_click_resets),
            modifier = hintModifier.padding(horizontal = 4.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
    // Keep one stable row throughout landscape typing and pointer handoff. Every
    // control remains available by horizontal scrolling; portrait keeps its rows.
    if (!singleRow && tool == EditorTool.MOVE) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
        moveChoices()
    }
    if (!singleRow && hasMask) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        maskChoices()
    }
    if (!singleRow && tool == EditorTool.CLONE) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
        cloneChoices(Modifier.align(Alignment.CenterVertically))
    }
    Row(Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState()).heightIn(min = 64.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (tool == EditorTool.BRUSH && erasing) ui.getString(R.string.ui_eraser) else if (tool == EditorTool.HAND) ui.getString(R.string.ui_pan) else ui.toolTitle(tool),
            fontSize = 13.sp, style = MaterialTheme.typography.titleSmall)
        when (tool) {
            EditorTool.MOVE -> {
                LayerPositionMenus(enabled && canAlignLayers && !moveSettling && !moveSwitchPending,
                    enabled && canDistributeLayers && !moveSettling && !moveSwitchPending, alignLayers, distributeLayers)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = duplicateArmed, onCheckedChange = setDuplicateArmed,
                        enabled = enabled && !moveSettling && !moveSwitchPending,
                        modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_duplicate_on_drag) })
                    Text(ui.getString(R.string.ui_duplicate))
                }
                if (moveBox != null) {
                    for ((label, field, value) in listOf(Triple("X", MoveField.X, moveBox.x),
                        Triple("Y", MoveField.Y, moveBox.y), Triple(ui.getString(R.string.ui_width), MoveField.WIDTH, moveBox.width),
                        Triple(ui.getString(R.string.ui_height), MoveField.HEIGHT, moveBox.height))) {
                        TransformNumericControl(label, value, moveEditable && !moveSettling && !moveSwitchPending,
                            fieldFocus, beginMoveField, finishMoveField, cancelMove,
                            scrubField = TransformScrubField.entries.first { it.moveField == field },
                            beginScrub = beginMoveScrub, updateScrub = updateMoveScrub, finishScrub = finishMoveScrub) { number, session ->
                            setMoveField(field, number, session)
                        }
                    }
                    moveScalePercent?.let { percent ->
                        TransformNumericControl(ui.getString(R.string.ui_scale), percent, moveEditable && !moveSettling && !moveSwitchPending,
                            fieldFocus, beginMoveField, finishMoveField, cancelMove, suffix = "%",
                            scrubField = TransformScrubField.SCALE, beginScrub = beginMoveScrub,
                            updateScrub = updateMoveScrub, finishScrub = finishMoveScrub, change = setMoveScale)
                    }
                    TransformNumericControl(ui.getString(R.string.ui_rotation), moveBox.rotation, moveEditable && !moveSettling && !moveSwitchPending,
                        fieldFocus, beginMoveField, finishMoveField, cancelMove,
                        scrubField = TransformScrubField.ROTATION, beginScrub = beginMoveScrub,
                        updateScrub = updateMoveScrub, finishScrub = finishMoveScrub) { number, session ->
                        setMoveField(MoveField.ROTATION, number, session)
                    }
                    TextButton(onClick = { focus.clearFocus(); flipMove(true) }, enabled = moveEditable && canFlipMove && !moveSwitchPending,
                        modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_flip_horizontal) }) { Text(ui.getString(R.string.ui_flip_h)) }
                    TextButton(onClick = { focus.clearFocus(); flipMove(false) }, enabled = moveEditable && canFlipMove && !moveSwitchPending,
                        modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_flip_vertical) }) { Text(ui.getString(R.string.ui_flip_v)) }
                } else Text(ui.getString(R.string.ui_select_a_visible_pixel_layer), fontSize = 12.sp)
            }
            EditorTool.BRUSH, EditorTool.CLONE, EditorTool.SMEAR, EditorTool.HEALING -> {
                if (tool == EditorTool.HEALING) HealingMode.entries.forEach { mode ->
                    HeaderChoice(ui.getString(mode.labelResource), healingMode == mode, enabled && !maskTarget) { setHealingMode(mode) }
                }
                if (tool == EditorTool.SMEAR) SmearMode.entries.forEach { mode ->
                    HeaderChoice(ui.getString(mode.labelResource), smearMode == mode, enabled && !maskTarget) { setSmearMode(mode) }
                }
                if (tool == EditorTool.BRUSH) Row {
                    HeaderChoice(ui.getString(R.string.ui_brush), !erasing, enabled) { setErasing(false) }
                    HeaderChoice(ui.getString(R.string.ui_eraser), erasing, enabled) { setErasing(true) }
                }
                NumericControl(ui.getString(R.string.ui_size), settings.diameter.toDouble(), 1.0..2000.0, "px", enabled,
                    fieldFocus = fieldFocus, fieldComplete = fieldComplete) { setSettings(settings.copy(diameter = it.toFloat())) }
                if (tool == EditorTool.BRUSH && penSeen) PressureControl(ui.getString(R.string.ui_pressure_for_size), settings.pressureSize, enabled) {
                    setSettings(settings.copy(pressureSize = it))
                }
                NumericControl(ui.getString(R.string.ui_hardness), settings.hardness * 100.0, 0.0..100.0, "%", enabled,
                    slider = true, fieldFocus = fieldFocus, fieldComplete = fieldComplete) { setSettings(settings.copy(hardness = (it / 100).toFloat())) }
                NumericControl(ui.getString(if (tool == EditorTool.SMEAR) R.string.ui_strength else R.string.ui_opacity), settings.opacity * 100.0, 1.0..100.0, "%", enabled,
                    slider = true, fieldFocus = fieldFocus, fieldComplete = fieldComplete) { setSettings(settings.copy(opacity = (it / 100).toFloat())) }
                if (tool == EditorTool.BRUSH && penSeen) PressureControl(ui.getString(R.string.ui_pressure_for_opacity), settings.pressureOpacity, enabled) {
                    setSettings(settings.copy(pressureOpacity = it))
                }
                if (tool == EditorTool.BRUSH) NumericControl(ui.getString(R.string.ui_smoothing), settings.smoothing.toDouble(), 0.0..100.0, "", enabled,
                    slider = true, fieldFocus = fieldFocus, fieldComplete = fieldComplete) { setSettings(settings.copy(smoothing = it.toFloat())) }
                if (tool == EditorTool.BRUSH && !maskTarget) {
                    Text(ui.getString(R.string.ui_color), fontSize = 12.sp)
                    Box(Modifier.size(44.dp).semantics { contentDescription = ui.getString(R.string.ui_brush_foreground_color) }
                        .clickable(enabled = enabled, onClick = chooseForeground).padding(9.dp)
                        .background(foreground, RoundedCornerShape(4.dp)).border(1.dp, Color.White.copy(alpha = .65f), RoundedCornerShape(4.dp)))
                }
            }
            EditorTool.ZOOM -> NumericControl(ui.getString(R.string.ui_zoom), zoomPercent, 0.1..3200.0, "%", enabled,
                decimals = 2, fieldFocus = fieldFocus, fieldComplete = fieldComplete, change = setZoom)
            EditorTool.EYEDROPPER -> HeaderChoice(ui.getString(R.string.ui_sample_ring), showsSampleRing, enabled) { setSampleRing(!showsSampleRing) }
            EditorTool.MAGIC -> {
                NumericControl(ui.getString(R.string.ui_tolerance), magicTolerance.toDouble(), 0.0..255.0, "", enabled,
                    fieldFocus = fieldFocus, fieldComplete = fieldComplete,
                    change = { setMagicTolerance(it.toInt()) })
                HeaderChoice(ui.getString(R.string.ui_contiguous), magicContiguous, enabled) { setMagicContiguous(!magicContiguous) }
                HeaderChoice(ui.getString(R.string.ui_point), magicSampleRadius == 0, enabled) { setMagicSampleRadius(0) }
                HeaderChoice("3×3", magicSampleRadius == 1, enabled) { setMagicSampleRadius(1) }
                HeaderChoice("5×5", magicSampleRadius == 2, enabled) { setMagicSampleRadius(2) }
                SelectionControls(tool, marqueeOperation, marqueeActive, enabled, selectionPending,
                    setMarqueeOperation, fillSelection, cutSelection, clearSelection, modifySelection,
                    selectionAmountSupported, selectionModifyAmount, { selectionModifyAmount = it })
                Text(ui.getString(R.string.ui_tap_selects_similar_colors_shift_add_alt_subtract_shift_alt_intersect), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorTool.DISTORT -> {
                HeaderChoice(ui.getString(R.string.ui_free_corners), distortFreeCorners, enabled && !moveSettling && !moveSwitchPending) {
                    setDistortFreeCorners(true)
                }
                HeaderChoice(ui.getString(R.string.ui_perspective), !distortFreeCorners, enabled && !moveSettling && !moveSwitchPending) {
                    setDistortFreeCorners(false)
                }
                TextButton(onClick = startTransform,
                    enabled = moveEditable && !distortActive && !moveSwitchPending,
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_start_distort) }) {
                    Text(ui.getString(R.string.ui_start))
                }
                if (distortActive) {
                    TextButton(onClick = applyDistort,
                        enabled = distortReady && !moveSwitchPending,
                        modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_apply_distort) }) { Text(ui.getString(R.string.ui_apply)) }
                    TextButton(onClick = cancelDistort,
                        enabled = !moveSwitchPending,
                        modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_cancel_distort) }) { Text(ui.getString(R.string.ui_cancel)) }
                }
                Text(ui.getString(R.string.ui_corner_preview_is_provided_by_the_native_session), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_distort_corner_hint) })
            }
            EditorTool.CROP -> {
                var customRatioDialogOpen by rememberSaveable { mutableStateOf(false) }
                var customRatioDraft by rememberSaveable { mutableStateOf(customCropRatioText) }
                var customRatioError by remember { mutableStateOf(false) }
                CropRatioChoice.entries.forEach { choice ->
                    HeaderChoice(ui.cropRatioTitle(choice), cropRatioChoice == choice, enabled) {
                        if (choice == CropRatioChoice.CUSTOM) {
                            customRatioDraft = customCropRatioText
                            customRatioError = false
                            customRatioDialogOpen = true
                        } else setCropRatioChoice(choice)
                    }
                }
                if (customRatioDialogOpen) {
                    AlertDialog(
                        onDismissRequest = { customRatioDialogOpen = false },
                        title = { Text(ui.getString(R.string.ui_custom_crop_ratio)) },
                        text = {
                            OutlinedTextField(
                                value = customRatioDraft,
                                onValueChange = { customRatioDraft = it; customRatioError = false },
                                label = { Text(ui.getString(R.string.ui_width_height_ratio)) },
                                singleLine = true,
                                isError = customRatioError,
                                supportingText = if (customRatioError) ({ Text(ui.getString(R.string.ui_invalid_crop_ratio)) }) else null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (parseCropRatio(customRatioDraft) == null) customRatioError = true
                                else {
                                    setCustomCropRatio(customRatioDraft)
                                    setCropRatioChoice(CropRatioChoice.CUSTOM)
                                    customRatioDialogOpen = false
                                }
                            }) { Text(ui.getString(R.string.ui_apply)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { customRatioDialogOpen = false }) { Text(ui.getString(R.string.ui_cancel)) }
                        }
                    )
                }
                TextButton(onClick = applyCrop, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_apply_crop) }) { Text(ui.getString(R.string.ui_apply)) }
                TextButton(onClick = cancelCrop, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_cancel_crop) }) { Text(ui.getString(R.string.ui_cancel)) }
                Text(ui.getString(R.string.ui_drag_outside_to_create_option_symmetric), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorTool.SHAPE -> {
                ShapeKind.entries.forEach { kind ->
                    HeaderChoice(ui.shapeTitle(kind), shapeKind == kind, enabled) {
                        setShapeKind(kind)
                    }
                }
                if (shapeKind == ShapeKind.LINE) {
                    NumericControl(ui.getString(R.string.ui_width), shapeLineWidth, 1.0..5000.0, "px", enabled,
                        slider = true, sliderRange = 1.0..50.0, sliderWhole = true, scrub = true,
                        fieldFocus = fieldFocus, fieldComplete = fieldComplete) { setShapeLineWidth(it) }
                    ShapeLineMenus(shapeLineStyle, shapeLineCap, enabled, setShapeLineStyle, setShapeLineCap)
                }
                if (shapeKind == ShapeKind.RECTANGLE) {
                    NumericControl(ui.getString(R.string.ui_radius), shapeCornerRadius, 0.0..5000.0, "px", enabled,
                        fieldFocus = fieldFocus, fieldComplete = fieldComplete) { setShapeCornerRadius(it) }
                }
                Text(ui.getString(R.string.ui_shift_square_circle_45_option_center_rectangle_ellipse), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorTool.GRADIENT -> {
                HeaderChoice(ui.getString(R.string.ui_linear), gradientKind == GradientKind.LINEAR, enabled) { setGradientKind(GradientKind.LINEAR) }
                HeaderChoice(ui.getString(R.string.ui_radial), gradientKind == GradientKind.RADIAL, enabled) { setGradientKind(GradientKind.RADIAL) }
                HeaderChoice(ui.getString(R.string.ui_fg_bg), gradientStyle == GradientStyle.FOREGROUND_TO_BACKGROUND, enabled) {
                    setGradientStyle(GradientStyle.FOREGROUND_TO_BACKGROUND)
                }
                HeaderChoice(ui.getString(R.string.ui_fg_transparent), gradientStyle == GradientStyle.FOREGROUND_TO_TRANSPARENT, enabled) {
                    setGradientStyle(GradientStyle.FOREGROUND_TO_TRANSPARENT)
                }
                HeaderChoice(ui.getString(R.string.ui_reverse), gradientReversed, enabled) { setGradientReversed(!gradientReversed) }
                NumericControl(ui.getString(R.string.ui_opacity), gradientOpacity, .01..1.0, "", enabled,
                    fieldFocus = fieldFocus, fieldComplete = fieldComplete) { setGradientOpacity(it) }
                TextButton(onClick = applyGradient, enabled = enabled && gradientPending,
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_apply_gradient) }) { Text(ui.getString(R.string.ui_apply)) }
                TextButton(onClick = cancelGradient, enabled = enabled && gradientPending,
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_cancel_gradient) }) { Text(ui.getString(R.string.ui_cancel)) }
                Text(ui.getString(R.string.ui_drag_to_apply_shift_snaps_to_45), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorTool.MARQUEE, EditorTool.LASSO -> {
                if (tool == EditorTool.MARQUEE) {
                    HeaderChoice(ui.getString(R.string.ui_rectangle), marqueeShape == PixelSelectionShape.RECTANGLE, enabled) {
                        setMarqueeShape(PixelSelectionShape.RECTANGLE)
                    }
                    HeaderChoice(ui.getString(R.string.ui_ellipse), marqueeShape == PixelSelectionShape.ELLIPSE, enabled) {
                        setMarqueeShape(PixelSelectionShape.ELLIPSE)
                    }
                }
                SelectionControls(tool, marqueeOperation, marqueeActive, enabled, selectionPending,
                    setMarqueeOperation, fillSelection, cutSelection, clearSelection, modifySelection,
                    selectionAmountSupported, selectionModifyAmount, { selectionModifyAmount = it })
                Text(if (tool == EditorTool.LASSO) ui.getString(R.string.ui_draw_a_freeform_polygon_combine_mode_applies_to_the_next_lasso_esc_cmd_d_clears_delet)
                    else ui.getString(R.string.ui_drag_shape_shift_toggles_ellipse_combine_mode_applies_to_the_next_marquee_esc_cmd_d_c), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Unit
        }
        if (singleRow) {
            if (tool == EditorTool.MOVE) moveChoices()
            if (hasMask) maskChoices()
            if (tool == EditorTool.CLONE) cloneChoices(Modifier)
        }
    }
    }
}

@Composable
private fun ShapeLineMenus(style: ShapeLineStyle, cap: ShapeLineCap, enabled: Boolean,
    changeStyle: (ShapeLineStyle) -> Unit, changeCap: (ShapeLineCap) -> Unit) {
    val ui = LocalContext.current
    var stylesOpen by remember { mutableStateOf(false) }
    var capsOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ui.getString(R.string.ui_style), fontSize = 12.sp)
        Box {
            TextButton(onClick = { stylesOpen = true }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_line_style); stateDescription = ui.lineStyleTitle(style) }) {
                LineStyleSample(style); Spacer(Modifier.width(6.dp)); Text(ui.lineStyleTitle(style))
            }
            DropdownMenu(expanded = stylesOpen, onDismissRequest = { stylesOpen = false }) {
                ShapeLineStyle.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(ui.lineStyleTitle(value)) }, leadingIcon = { LineStyleSample(value) },
                        modifier = Modifier.semantics { selected = value == style },
                        onClick = { changeStyle(value); stylesOpen = false })
                }
            }
        }
        Text(ui.getString(R.string.ui_cap), fontSize = 12.sp)
        Box {
            TextButton(onClick = { capsOpen = true }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_line_cap); stateDescription = ui.lineCapTitle(cap) }) { Text(ui.lineCapTitle(cap)) }
            DropdownMenu(expanded = capsOpen, onDismissRequest = { capsOpen = false }) {
                ShapeLineCap.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(ui.lineCapTitle(value)) }, modifier = Modifier.semantics { selected = value == cap },
                        onClick = { changeCap(value); capsOpen = false })
                }
            }
        }
    }
}

@Composable
private fun LineStyleSample(style: ShapeLineStyle) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(56.dp, 16.dp)) {
        val width = 2.dp.toPx()
        shapeLinePieces(width.toDouble(), size.height / 2.0, (size.width - width).toDouble(),
            size.height / 2.0, width.toDouble(), style, ShapeLineCap.ROUND).forEach { piece ->
            val start = Offset(piece.startX.toFloat(), piece.startY.toFloat())
            if (piece.dot) drawCircle(color, width / 2, start)
            else drawLine(color, start, Offset(piece.endX.toFloat(), piece.endY.toFloat()), width, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun PressureControl(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (!enabled) colors.onSurface.copy(alpha = .12f)
        else if (checked) colors.primary else colors.surfaceVariant
    val tint = if (!enabled) colors.onSurface.copy(alpha = .38f)
        else if (checked) colors.onPrimary else colors.onSurfaceVariant
    Box(Modifier.size(48.dp).semantics { contentDescription = label }
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).background(background, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val unit = size.width / 22
                drawCircle(tint, 9 * unit, Offset(11 * unit, 11 * unit), style = Stroke(1.4f * unit))
                val nib = Path().apply {
                    moveTo(7 * unit, 15 * unit); lineTo(9 * unit, 8 * unit)
                    lineTo(14 * unit, 5 * unit); lineTo(17 * unit, 8 * unit)
                    lineTo(14 * unit, 13 * unit); close()
                }
                drawPath(nib, tint, style = Stroke(1.4f * unit))
                drawLine(tint, Offset(7 * unit, 15 * unit), Offset(12 * unit, 10 * unit), 1.4f * unit)
            }
        }
    }
}

@Composable
private fun SelectionControls(tool: EditorTool, operation: Int, active: Boolean, enabled: Boolean,
    pending: Boolean, setOperation: (Int) -> Unit, fill: () -> Unit, cut: () -> Unit, clear: () -> Unit,
    modifySelection: (Int, Int) -> Unit, selectionAmountSupported: (Int) -> Boolean,
    sharedAmount: Int, setSharedAmount: (Int) -> Unit) {
    val ui = LocalContext.current
    val editable = enabled && !pending
    HeaderChoice(ui.getString(R.string.ui_replace), operation == PixelSelectionOperation.REPLACE, editable) { setOperation(PixelSelectionOperation.REPLACE) }
    HeaderChoice(ui.getString(R.string.ui_add), operation == PixelSelectionOperation.ADD, editable) { setOperation(PixelSelectionOperation.ADD) }
    HeaderChoice(ui.getString(R.string.ui_subtract), operation == PixelSelectionOperation.SUBTRACT, editable) { setOperation(PixelSelectionOperation.SUBTRACT) }
    HeaderChoice(ui.getString(R.string.ui_intersect), operation == PixelSelectionOperation.INTERSECT, editable) { setOperation(PixelSelectionOperation.INTERSECT) }
    val name = when (tool) {
        EditorTool.MAGIC -> ui.getString(R.string.ui_magic_wand)
        EditorTool.LASSO -> ui.getString(R.string.ui_lasso)
        else -> ui.getString(R.string.ui_marquee)
    }
    TextButton(onClick = fill, enabled = editable && active,
        modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_fill_named_selection, name) }) { Text(ui.getString(R.string.ui_fill)) }
    TextButton(onClick = cut, enabled = editable && active,
        modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_cut_named_selection, name) }) { Text(ui.getString(R.string.ui_cut)) }
    TextButton(onClick = clear, enabled = pending || (enabled && active),
        modifier = Modifier.semantics { contentDescription = if (pending) ui.getString(R.string.ui_cancel_named_selection, name) else ui.getString(R.string.ui_clear_named_selection, name) }) {
        Text(if (pending) ui.getString(R.string.ui_cancel) else ui.getString(R.string.ui_clear))
    }
    SelectionAmountControls(active = active, enabled = enabled && !pending,
        supported = selectionAmountSupported, modifySelection = modifySelection,
        sharedAmount = sharedAmount, setSharedAmount = setSharedAmount)
}

/**
 * Compact mobile equivalent of the Mac Select → Modify amount panel. Each command opens a
 * focused numeric dialog so the options bar stays usable on narrow phones while tablets retain
 * the same one row command layout. Values are deliberately whole document pixels and are
 * validated before crossing the native command gate.
 */
@Composable
private fun SelectionAmountControls(active: Boolean, enabled: Boolean, supported: (Int) -> Boolean,
    modifySelection: (Int, Int) -> Unit, sharedAmount: Int, setSharedAmount: (Int) -> Unit) {
    val ui = LocalContext.current
    var dialogOperation by rememberSaveable { mutableStateOf<Int?>(null) }
    var input by rememberSaveable { mutableStateOf("5") }
    val operation = dialogOperation
    val maximum = operation?.let(PixelSelectionAmountOperation::maximum) ?: 500
    val amount = input.trim().toIntOrNull()
    val valid = amount != null && amount in 1..maximum
    listOf(
        PixelSelectionAmountOperation.EXPAND,
        PixelSelectionAmountOperation.CONTRACT,
        PixelSelectionAmountOperation.FEATHER,
    ).forEach { candidate ->
        TextButton(
            onClick = {
                dialogOperation = candidate
                input = sharedAmount.toString()
            },
            enabled = enabled && active && supported(candidate),
            modifier = Modifier.semantics {
                contentDescription = ui.getString(R.string.ui_selection_operation, ui.selectionAmountTitle(candidate))
                if (!supported(candidate)) stateDescription = ui.getString(R.string.ui_unavailable_for_this_selection)
            }
        ) { Text(ui.selectionAmountTitle(candidate)) }
    }
    if (operation != null) {
        AlertDialog(
            onDismissRequest = { dialogOperation = null },
            title = { Text(ui.getString(R.string.ui_selection_operation, ui.selectionAmountTitle(operation))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(16) },
                        singleLine = true,
                        label = { Text(ui.getString(R.string.ui_amount)) },
                        suffix = { Text("px") },
                        isError = input.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (valid && active && enabled && supported(operation)) {
                                val accepted = checkNotNull(amount)
                                setSharedAmount(accepted)
                                modifySelection(operation, accepted)
                                dialogOperation = null
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = ui.getString(R.string.ui_selection_amount, ui.selectionAmountTitle(operation))
                        }
                    )
                    Text(
                        ui.getString(R.string.ui_selection_amount_range, maximum),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val accepted = checkNotNull(amount)
                        setSharedAmount(accepted)
                        modifySelection(operation, accepted)
                        dialogOperation = null
                    },
                    enabled = valid && active && enabled && supported(operation),
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_apply_selection_amount) }
                ) { Text(ui.getString(R.string.ui_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { dialogOperation = null },
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_cancel_selection_amount) }) {
                    Text(ui.getString(R.string.ui_cancel))
                }
            }
        )
    }
}

/** Mac typed transforms retain fractions and reject invalid values instead of clamping them. */
@Composable
private fun TransformNumericControl(label: String, value: Double, enabled: Boolean,
    fieldFocus: (Boolean) -> Unit, begin: () -> MoveFieldSession?,
    fieldComplete: (MoveFieldSession?, Boolean) -> Unit, cancel: () -> Unit,
    suffix: String? = null, scrubField: TransformScrubField,
    beginScrub: (TransformScrubField) -> MoveScrubSession?,
    updateScrub: (MoveScrubSession, Double) -> Unit, finishScrub: (MoveScrubSession, Boolean) -> Unit,
    change: (Double, MoveFieldSession?) -> Boolean) {
    val ui = LocalContext.current
    val focus = LocalFocusManager.current
    fun formatted(number: Double) = if (kotlin.math.abs(number - kotlin.math.round(number)) < .005)
        String.format(Locale.ROOT, "%.0f", number) else String.format(Locale.ROOT, "%.2f", number)
    var text by remember { mutableStateOf(formatted(value)) }
    var focused by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<MoveFieldSession?>(null) }
    LaunchedEffect(value, focused) { if (!focused) { text = formatted(value); invalid = false } }
    fun finish(): Boolean {
        val number = text.toDoubleOrNull()
        val accepted = number != null && number.isFinite() && !invalid
        invalid = !accepted
        return accepted
    }
    fun complete() {
        if (!finish()) return
        val captured = session
        completed = true; focused = false; fieldFocus(false)
        focus.clearFocus(); fieldComplete(captured, true)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TransformScrubLabel(label, scrubField, enabled, begin = { field ->
            focus.clearFocus()
            beginScrub(field)
        }, update = updateScrub, finish = finishScrub)
        OutlinedTextField(value = text, onValueChange = {
            if (focused && text != it) {
                text = it
                val number = it.toDoubleOrNull()
                invalid = number == null || !number.isFinite() || !change(number, session)
            }
        },
            enabled = enabled, singleLine = true, isError = invalid,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { complete() }),
            modifier = Modifier.width(96.dp).heightIn(min = 48.dp)
                .semantics { contentDescription = ui.getString(R.string.ui_move_field_value, label) }
                .onFocusChanged {
                    if (focused != it.isFocused) {
                        val shouldFinish = focused && !cancelled && !completed
                        focused = it.isFocused; fieldFocus(it.isFocused)
                        if (it.isFocused) { cancelled = false; completed = false; session = begin() }
                        if (shouldFinish) {
                            if (!finish()) text = formatted(value)
                            completed = true; fieldComplete(session, false)
                        }
                    }
                }.onPreviewKeyEvent { event ->
                    if (BuildConfig.DEBUG && event.key in setOf(Key.DirectionLeft, Key.DirectionRight,
                        Key.DirectionUp, Key.DirectionDown, Key.Enter, Key.Escape)) android.util.Log.d("CompositorKey",
                        "field=$label type=${event.type} key=${event.key} focused=$focused cancelled=$cancelled")
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { complete(); true }
                        Key.Escape -> {
                            cancelled = true; cancel()
                            text = formatted(value); invalid = false
                            focus.clearFocus(); focused = false; fieldFocus(false)
                            fieldComplete(null, true); true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) false else {
                                val number = (text.toDoubleOrNull() ?: value) +
                                    (if (event.key == Key.DirectionUp) 1 else -1) * (if (event.isShiftPressed) 10 else 1)
                                if (number.isFinite() && change(number, session)) { text = formatted(number); invalid = false }
                                else invalid = true
                                true
                            }
                        }
                        else -> false
                    }
                })
        if (suffix != null) Text(suffix, fontSize = 12.sp)
    }
}

@Composable
private fun HeaderChoice(label: String, selected: Boolean, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, shape = RoundedCornerShape(7.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (selected) Color.White.copy(alpha = .12f) else Color.Transparent),
        contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, fontSize = 12.sp) }
}

@Composable
internal fun NumericControl(label: String, value: Double, range: ClosedFloatingPointRange<Double>, unit: String,
    enabled: Boolean, slider: Boolean = false, decimals: Int = 0, fieldFocus: (Boolean) -> Unit = {},
    fieldComplete: () -> Unit = {}, sliderRange: ClosedFloatingPointRange<Double> = range,
    sliderWhole: Boolean = false, scrub: Boolean = false, change: (Double) -> Unit) {
    val ui = LocalContext.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current.density
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(change)
    fun formatted(number: Double) = if (decimals == 0) String.format(Locale.ROOT, "%.0f", number)
        else String.format(Locale.ROOT, "%.${decimals}f", number).trimEnd('0').trimEnd('.')
    var text by remember { mutableStateOf(formatted(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = formatted(value) }
    fun finish() {
        val number = text.toDoubleOrNull()
        if (number != null && number.isFinite()) change(number.coerceIn(range))
        text = formatted(if (number != null && number.isFinite()) number.coerceIn(range) else value)
    }
    fun complete() {
        finish()
        focus.clearFocus()
        focused = false; fieldFocus(false)
        fieldComplete()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.sp, modifier = if (!scrub) Modifier else Modifier
            .semantics { contentDescription = ui.getString(R.string.ui_field_scrub, label) }
            .pointerInput(enabled, range, density) {
                if (enabled) {
                    var number = currentValue
                    detectHorizontalDragGestures(onDragStart = { focus.clearFocus(); number = currentValue },
                        onHorizontalDrag = { event, delta ->
                            event.consume()
                            number = (number + delta / density).coerceIn(range)
                            currentChange(shapePixel(number).coerceIn(range))
                        })
                }
            })
        if (slider) Slider(value = value.toFloat().coerceIn(sliderRange.start.toFloat(), sliderRange.endInclusive.toFloat()),
            onValueChange = { change(if (sliderWhole) shapePixel(it.toDouble()) else it.toDouble()) }, enabled = enabled,
            valueRange = sliderRange.start.toFloat()..sliderRange.endInclusive.toFloat(),
            modifier = Modifier.width(90.dp).semantics { contentDescription = ui.getString(R.string.ui_field_slider, label) })
        OutlinedTextField(value = text, onValueChange = { text = it }, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { complete() }),
            modifier = Modifier.width(if (decimals == 0) 70.dp else 88.dp).heightIn(min = 48.dp)
                .semantics { contentDescription = ui.getString(R.string.ui_field_value, label) }
                .onFocusChanged {
                    if (focused != it.isFocused) {
                        if (focused) finish()
                        focused = it.isFocused; fieldFocus(it.isFocused)
                    }
                }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.Escape -> { complete(); true }
                        Key.DirectionUp, Key.DirectionDown -> {
                            val step = if (event.isShiftPressed) 10.0 else 1.0
                            val updated = ((text.toDoubleOrNull() ?: value) + if (event.key == Key.DirectionUp) step else -step).coerceIn(range)
                            text = formatted(updated); change(updated); true
                        }
                        else -> false
                    }
                })
        if (unit.isNotEmpty()) Text(unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
