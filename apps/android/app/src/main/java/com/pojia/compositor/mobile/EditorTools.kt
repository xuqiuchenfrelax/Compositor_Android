package com.pojia.compositor.mobile

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.graphics.Color

internal enum class EditorTool(val title: String, val key: String, val available: Boolean = false) {
    MOVE("Move / Transform", "V", true), DISTORT("Distort", "⇧D", true), MARQUEE("Marquee", "M", true), LASSO("Lasso", "L", true),
    MAGIC("Magic", "W", true), CROP("Crop", "C", true), BRUSH("Brush", "B", true),
    HEALING("Spot Healing", "J", true), CLONE("Clone Stamp", "S", true), SMEAR("Smear", "R", true),
    GRADIENT("Gradient", "G", true), SHAPE("Shape", "U", true), TYPE("Type", "T", true),
    EYEDROPPER("Eyedropper", "I", true), HAND("Hand", "H", true), ZOOM("Zoom", "Z", true),
    IDLE("Select a tool", "A", true);
}

internal val editorToolSaver = Saver<EditorTool, String>(save = { it.name }, restore = { EditorTool.valueOf(it) })
internal val shapeKindSaver = Saver<ShapeKind, String>(save = { it.name }, restore = { ShapeKind.valueOf(it) })
internal val shapeLineStyleSaver = Saver<ShapeLineStyle, String>(save = { it.name }, restore = { ShapeLineStyle.valueOf(it) })
internal val shapeLineCapSaver = Saver<ShapeLineCap, String>(save = { it.name }, restore = { ShapeLineCap.valueOf(it) })
internal val editorColorSaver = listSaver<Color, Float>(
    save = { listOf(it.red, it.green, it.blue, it.alpha) }, restore = { Color(it[0], it[1], it[2], it[3]) })
internal val strokeSettingsSaver = listSaver<StrokeSettings, Any>(
    save = { it.savedValues() }, restore = { StrokeSettings.restore(it) })
