package com.pojia.compositor.mobile

import android.content.Context

/** Presentation labels only. Persisted enum names, wire values and user-authored names stay unchanged. */
internal fun EditorTool.titleResource(): Int = when (this) {
    EditorTool.MOVE -> R.string.ui_tool_move
    EditorTool.DISTORT -> R.string.ui_tool_distort
    EditorTool.MARQUEE -> R.string.ui_marquee
    EditorTool.LASSO -> R.string.ui_lasso
    EditorTool.MAGIC -> R.string.ui_tool_magic
    EditorTool.CROP -> R.string.ui_tool_crop
    EditorTool.BRUSH -> R.string.ui_brush
    EditorTool.HEALING -> R.string.ui_tool_healing
    EditorTool.CLONE -> R.string.ui_tool_clone
    EditorTool.SMEAR -> R.string.ui_tool_smear
    EditorTool.GRADIENT -> R.string.ui_tool_gradient
    EditorTool.SHAPE -> R.string.ui_tool_shape
    EditorTool.TYPE -> R.string.ui_tool_type
    EditorTool.EYEDROPPER -> R.string.ui_tool_eyedropper
    EditorTool.HAND -> R.string.ui_tool_hand
    EditorTool.ZOOM -> R.string.ui_zoom
    EditorTool.IDLE -> R.string.ui_tool_idle
}

internal fun Context.toolTitle(tool: EditorTool): String = getString(tool.titleResource())

internal fun Context.panelTitle(panel: InspectorPanel): String = getString(when (panel) {
    InspectorPanel.COLOR -> R.string.ui_color
    InspectorPanel.PROPERTIES -> R.string.ui_properties
    InspectorPanel.LAYERS -> R.string.ui_layers
    InspectorPanel.HISTORY -> R.string.ui_history
    InspectorPanel.NAVIGATOR -> R.string.ui_navigator
    InspectorPanel.BRUSHES -> R.string.ui_brushes
    InspectorPanel.TYPE -> R.string.ui_tool_type
    InspectorPanel.ADJUSTMENTS -> R.string.ui_adjustments
})

internal val AdjustmentKind.label: Int get() = when (this) {
    AdjustmentKind.INVERT -> R.string.ui_adjustment_invert
    AdjustmentKind.EXPOSURE -> R.string.ui_adjustment_exposure_kind
    AdjustmentKind.LEVELS -> R.string.ui_adjustment_levels
    AdjustmentKind.CURVES -> R.string.ui_adjustment_curves
    AdjustmentKind.BLACK_WHITE -> R.string.ui_adjustment_black_white
    AdjustmentKind.COLOR_BALANCE -> R.string.ui_adjustment_color_balance
    AdjustmentKind.HUE_SATURATION -> R.string.ui_adjustment_hue_saturation
    AdjustmentKind.GRADIENT_MAP -> R.string.ui_adjustment_gradient_map
    AdjustmentKind.GRAIN -> R.string.ui_adjustment_grain
    AdjustmentKind.ADD_NOISE -> R.string.ui_adjustment_add_noise
    AdjustmentKind.VIGNETTE -> R.string.ui_adjustment_vignette
}

internal fun Context.shapeTitle(shape: ShapeKind): String = getString(when (shape) {
    ShapeKind.RECTANGLE -> R.string.ui_rectangle
    ShapeKind.ELLIPSE -> R.string.ui_ellipse
    ShapeKind.LINE -> R.string.ui_line
})

internal fun Context.lineStyleTitle(style: ShapeLineStyle): String = getString(when (style) {
    ShapeLineStyle.SOLID -> R.string.ui_solid
    ShapeLineStyle.DOTTED -> R.string.ui_dotted
    ShapeLineStyle.DASHED -> R.string.ui_dashed
    ShapeLineStyle.LONG_DASH -> R.string.ui_long_dash
})

internal fun Context.lineCapTitle(cap: ShapeLineCap): String = getString(when (cap) {
    ShapeLineCap.SQUARE -> R.string.ui_square
    ShapeLineCap.ROUND -> R.string.ui_round
})

internal fun Context.layerAlignmentTitle(alignment: LayerAlignment): String = getString(when (alignment) {
    LayerAlignment.LEFT -> R.string.ui_align_left_edges
    LayerAlignment.HORIZONTAL_CENTER -> R.string.ui_align_horizontal_centers
    LayerAlignment.RIGHT -> R.string.ui_align_right_edges
    LayerAlignment.TOP -> R.string.ui_align_top_edges
    LayerAlignment.VERTICAL_CENTER -> R.string.ui_align_vertical_centers
    LayerAlignment.BOTTOM -> R.string.ui_align_bottom_edges
})

internal fun Context.layerDistributionTitle(distribution: LayerDistribution): String = getString(when (distribution) {
    LayerDistribution.HORIZONTAL_CENTERS -> R.string.ui_distribute_horizontal_centers
    LayerDistribution.VERTICAL_CENTERS -> R.string.ui_distribute_vertical_centers
    LayerDistribution.HORIZONTAL_SPACING -> R.string.ui_distribute_horizontal_spacing
    LayerDistribution.VERTICAL_SPACING -> R.string.ui_distribute_vertical_spacing
})

internal fun Context.cropRatioTitle(choice: CropRatioChoice): String = when (choice) {
    CropRatioChoice.FREE -> getString(R.string.ui_free)
    CropRatioChoice.ORIGINAL -> getString(R.string.ui_canvas)
    CropRatioChoice.CUSTOM -> getString(R.string.ui_custom_ratio)
    else -> choice.title // Language-independent aspect ratio, e.g. 16:9.
}

internal fun Context.canvasUnitTitle(unit: NewCanvasUnit): String = getString(when (unit) {
    NewCanvasUnit.PIXELS -> R.string.ui_pixels
    NewCanvasUnit.INCHES -> R.string.ui_inches
    NewCanvasUnit.CENTIMETERS -> R.string.ui_centimeters
    NewCanvasUnit.MILLIMETERS -> R.string.ui_millimeters
})

internal fun Context.canvasBackgroundTitle(background: NewCanvasBackground): String = getString(when (background) {
    NewCanvasBackground.TRANSPARENT -> R.string.ui_transparent
    NewCanvasBackground.WHITE -> R.string.ui_white
    NewCanvasBackground.BLACK -> R.string.ui_black
})

internal fun Context.selectionAmountTitle(operation: Int): String = getString(when (operation) {
    PixelSelectionAmountOperation.EXPAND -> R.string.ui_expand
    PixelSelectionAmountOperation.CONTRACT -> R.string.ui_contract
    PixelSelectionAmountOperation.FEATHER -> R.string.ui_feather
    else -> error("Unknown selection amount operation: $operation")
})

/** Translate only generated fallback labels; names entered by users retain their original bytes. */
internal fun Context.layerTitle(layer: LayerState): String = layer.name.ifEmpty {
    getString(if (layer.isGroup) R.string.ui_group_number else R.string.ui_layer_number, layer.id)
}

internal val HueRange.label: Int get() = when (this) {
    HueRange.MASTER -> R.string.ui_adjustment_master
    HueRange.REDS -> R.string.ui_hue_range_reds
    HueRange.YELLOWS -> R.string.ui_hue_range_yellows
    HueRange.GREENS -> R.string.ui_hue_range_greens
    HueRange.CYANS -> R.string.ui_hue_range_cyans
    HueRange.BLUES -> R.string.ui_hue_range_blues
    HueRange.MAGENTAS -> R.string.ui_hue_range_magentas
}
