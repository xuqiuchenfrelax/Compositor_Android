package com.pojia.compositor.mobile

import android.app.Instrumentation
import kotlin.math.abs
import kotlin.math.hypot

/** Geometry and native tile transaction smoke for Shape. */
internal fun shapeSmoke(@Suppress("UNUSED_PARAMETER") instrumentation: Instrumentation): String {
    val line = shapeDraft(10.0, 20.0, 90.0, 35.0, ShapeKind.LINE, square = true,
        fromCenter = false, lineWidth = 4.0)
    val lineSpec = checkNotNull(line.spec(0xffff0000.toInt(), 0.0, 4.0))
    val dx = lineSpec.endX - lineSpec.startX
    val dy = lineSpec.endY - lineSpec.startY
    check(hypot(dx, dy) > .25) { "Shape line endpoints collapsed" }
    check(abs(dy) < 1e-12 && dx > 0) { "Shallow Shift line did not snap horizontally" }
    val diagonal = checkNotNull(shapeDraft(10.0, 20.0, 50.0, 51.0, ShapeKind.LINE, true,
        false, 4.0).spec(0xffff0000.toInt(), 0.0, 4.0))
    check(abs(abs((diagonal.endX - diagonal.startX) * diagonal.geometryWidth) -
        abs((diagonal.endY - diagonal.startY) * diagonal.geometryHeight)) < 1e-10) {
        "Diagonal Shift line did not snap to 45 degrees in document coordinates"
    }
    val square = shapeDraft(30.0, 30.0, 46.0, 40.0, ShapeKind.RECTANGLE, square = true,
        fromCenter = false, lineWidth = 1.0)
    check(abs(square.width - square.height) < .001) { "Shift rectangle was not square" }
    val centered = shapeDraft(30.0, 30.0, 40.0, 35.0, ShapeKind.ELLIPSE, square = false,
        fromCenter = true, lineWidth = 1.0)
    check(abs((centered.x + centered.width / 2.0) - 30.0) < .001 &&
        abs((centered.y + centered.height / 2.0) - 30.0) < .001) { "Option ellipse was not centered" }
    val point = checkNotNull(shapeDraft(0.0, 0.0, 0.0, 0.0, ShapeKind.LINE, false, false, 1.0)
        .spec(0xff000000.toInt(), 0.0, 1.0))
    check(point.width == 1 && point.height == 1) { "Single-click line cap did not retain its area" }
    check(shapeDraft(0.0, 0.0, 4_001.0, 4_001.0, ShapeKind.RECTANGLE, false, false, 1.0)
        .spec(0, 0.0, 1.0) == null) { "Shape memory cap was bypassed" }

    NativeEditor(64, addDefaultLayers = false).use { editor ->
        val spec = checkNotNull(square.spec(0xff00ffff.toInt(), 0.0, 1.0))
        val before = editor.currentRevision()
        editor.createShape(spec, square.x, square.y)
        check(editor.state().layers.single().pixelWidth == spec.width)
        check(editor.currentRevision() > before)
        editor.undo(false)
        check(editor.state().layers.isEmpty()) { "Shape undo did not remove layer" }
        editor.undo(true)
        check(editor.state().layers.single().pixelHeight == spec.height)
    }
    return "Shape geometry/native smoke passed: line endpoints, Shift 45-degree snap, Option center, cap, one-step undo/redo"
}
