package com.pojia.compositor.mobile

import kotlin.math.abs

internal fun moveGeometrySmoke(): String {
    check(MoveGeometry.rounded(-.5) == -1.0 && MoveGeometry.rounded(.5) == 1.0)
    check(MoveGeometry.rounded(-1.5) == -2.0 && MoveGeometry.rounded(1.5) == 2.0)
    val original = Transform64(-.5, .5, 15.5, 16.5, -12.5)
    val none = MoveSnapTargets(emptyList(), emptyList())
    val horizontal = MoveGeometry.drag(original, MovePoint(10.0, 20.0), MovePoint(20.0, 30.0), true, false, none, 1.0)
    check(horizontal.box == original.copy(x = 10.0, y = 1.0, width = 16.0, height = 17.0, rotation = -13.0))
    val vertical = MoveGeometry.drag(original, MovePoint(10.0, 20.0), MovePoint(12.0, 50.0), true, false, none, 1.0)
    check(vertical.box.x == -1.0 && vertical.box.y == 31.0)
    val rectangle = Transform64(20.0, 30.0, 20.0, 10.0, 90.0)
    val bounds = MoveGeometry.bounds(rectangle)
    check(abs(bounds.left - 25.0) < 1e-10 && abs(bounds.top - 25.0) < 1e-10)
    check(MoveGeometry.contains(rectangle, MovePoint(30.0, 44.0)))
    check(!MoveGeometry.contains(rectangle, MovePoint(39.0, 35.0)))
    val snapped = MoveGeometry.drag(rectangle, MovePoint(0.0, 0.0), MovePoint(0.0, 0.0), false, true,
        MoveSnapTargets(listOf(25.5, 24.5), listOf(25.25)), 2.0)
    check(snapped.box.x == 20.5 && snapped.box.y == 30.25 && snapped.snapX == 25.5)
    check(MoveGeometry.drag(rectangle, MovePoint(0.0, 0.0), MovePoint(0.0, 0.0), false, false,
        MoveSnapTargets(listOf(25.5), listOf(25.25)), 2.0).box == rectangle)
    check(MoveGeometry.field(rectangle, MoveField.X, -30_001.125)?.x == -30_001.125)
    check(MoveGeometry.field(rectangle, MoveField.WIDTH, 300_000.0)?.width == 300_000.0)
    check(MoveGeometry.field(rectangle, MoveField.WIDTH, 300_000.1) == null)
    check(MoveGeometry.field(rectangle, MoveField.X, Double.NaN) == null)
    check(MoveGeometry.field(rectangle, MoveField.ROTATION, -720.25)?.rotation == -.25)
    val folder = LayerState(1, 1.0, true, 0, isGroup = true)
    val first = LayerState(2, 0.0, true, 0, parentId = 1, assetPresent = true,
        transform = Transform64(10.25, 15.75, 4.0, 6.0))
    val hidden = first.copy(id = 3, visible = false)
    val state = EditorState(layers = listOf(folder, first, hidden))
    check(MoveGeometry.target(first, state.layers, false) == TransformTarget.LAYER)
    check(MoveGeometry.target(first, listOf(folder.copy(visible = false), first), false) == null)
    check(MoveGeometry.target(folder, state.layers, false) == TransformTarget.GROUP)
    check(MoveGeometry.selection(state, listOf(folder.id), folder.id, false) ==
        MoveSelectionGeometry(TransformTarget.GROUP, first.transform, listOf(first.id)))
    check(MoveGeometry.targets(state, 99, 100, 80).xs == listOf(0.0, 100.0, 50.0, 10.0, 12.0, 14.0))
    val mask = first.copy(hasMask = true, maskEnabled = false, maskLinked = false,
        maskPlacement = Transform64(1.25, 2.5, 1.0, 1.0))
    check(MoveGeometry.target(mask, listOf(folder, mask), true) == TransformTarget.UNLINKED_MASK)
    check(MoveGeometry.box(mask, TransformTarget.UNLINKED_MASK) == mask.maskPlacement)
    groupSelectionGeometrySmoke()
    return "Mac geometry: half-away rounding; fixed original and per-event Shift axis; rotated AABB snapping/tie order; fractional typed values and validity limits; visible hierarchy/zero-opacity and independent disabled mask targeting."
}

/** Mac canTransform/groupTransformMembers: visible pixel members, one shared box, and 64 inspected IDs. */
private fun groupSelectionGeometrySmoke() {
    val folder = LayerState(1, 1.0, true, 0, isGroup = true)
    val pixel = LayerState(2, 0.0, true, 0, parentId = folder.id, assetPresent = true,
        transform = Transform64(10.25, 15.75, 4.0, 6.0))
    val hidden = pixel.copy(id = 3, visible = false, transform = Transform64(-100.0, -100.0, 200.0, 200.0))
    val blank = pixel.copy(id = 4, assetPresent = false)
    fun selection(layers: List<LayerState>, roots: List<Long> = listOf(folder.id), primary: Long = roots.first(),
        mask: Boolean = false) = MoveGeometry.selection(EditorState(layers = layers), roots, primary, mask)
    for (layers in listOf(listOf(folder), listOf(folder, hidden), listOf(folder, blank))) {
        check(MoveGeometry.target(folder, layers, false) == null)
        check(selection(layers) == null && MoveGeometry.selectionBox(EditorState(layers = layers), listOf(folder.id)) == null)
    }
    val outer = folder.copy(id = 5, visible = false)
    check(selection(listOf(outer, folder.copy(parentId = outer.id), pixel)) == null)
    val otherFolder = folder.copy(id = 6)
    check(selection(listOf(folder, otherFolder, hidden, blank), listOf(folder.id, otherFolder.id)) == null)
    val nested = folder.copy(id = 7, parentId = folder.id)
    val child = pixel.copy(id = 8, parentId = nested.id, transform = Transform64(20.0, 25.0, 2.0, 3.0))
    val layers = listOf(folder, pixel, nested, child, hidden, blank)
    val expected = MoveSelectionGeometry(TransformTarget.GROUP, Transform64(10.25, 15.75, 11.75, 12.25),
        listOf(pixel.id, child.id))
    check(selection(layers, listOf(folder.id, nested.id, pixel.id)) == expected)
    check(selection(layers, listOf(folder.id, nested.id, pixel.id), mask = true) == expected)
    check(MoveGeometry.selectionBox(EditorState(layers = layers), listOf(folder.id, nested.id, pixel.id)) == expected.box)
    val independent = pixel.copy(id = 9, parentId = 0, hasMask = true, maskLinked = false,
        maskPlacement = Transform64(-100.0, -200.0, 1.0, 1.0))
    val mixed = listOf(hidden.copy(parentId = 0, hasMask = true, maskLinked = false), independent)
    check(selection(mixed, listOf(hidden.id, independent.id), hidden.id, true) ==
        MoveSelectionGeometry(TransformTarget.GROUP, independent.transform, listOf(independent.id)))
    check(selection(listOf(independent), listOf(independent.id), independent.id, true) ==
        MoveSelectionGeometry(TransformTarget.UNLINKED_MASK, checkNotNull(independent.maskPlacement), listOf(independent.id)))
    check(selection(layers, emptyList(), 0) == null)
    check(selection(layers, listOf(folder.id), 999) == null)
    val rotated = pixel.copy(transform = Transform64(20.0, 30.0, 20.0, 10.0, 90.0))
    val rotatedBox = checkNotNull(selection(listOf(folder, rotated))).box
    check(abs(rotatedBox.x - 25.0) < 1e-10 && abs(rotatedBox.y - 25.0) < 1e-10 &&
        abs(rotatedBox.width - 10.0) < 1e-10 && abs(rotatedBox.height - 20.0) < 1e-10)
    val tiny = pixel.copy(transform = Transform64(1.25, 2.5, .25, .5))
    check(selection(listOf(folder, tiny))?.box == Transform64(1.25, 2.5, 1.0, 1.0))
    val chain = (1L..64L).map { id -> folder.copy(id = id, parentId = id - 1) }
    val at63 = pixel.copy(id = 100, parentId = 63)
    check(selection(chain.take(63) + at63)?.members == listOf(at63.id))
    val at64 = at63.copy(parentId = 64)
    check(selection(chain + at64) == null)
    check(selection(chain + at64, listOf(1, 2), 1)?.members == listOf(at64.id))
}
