package com.pojia.compositor.mobile

/** Fixed independent expectations for the group's begin box, not its active member's pixels. */
internal fun groupScaleGeometrySmoke(): String {
    val layer = LayerState(1, 1.0, true, 0, pixelWidth = 4, pixelHeight = 4, assetPresent = true)
    val group = layer.copy(isGroup = true, pixelWidth = 256, pixelHeight = 256, assetPresent = false)
    val begin = Transform64(20.0, 20.0, 56.0, 36.0)
    for (primary in listOf(layer, group)) {
        val basis = checkNotNull(MoveInspectorGeometry.basis(primary, TransformTarget.GROUP, begin))
        check(basis == MoveScaleBasis(56.0, 36.0))
        check(MoveInspectorGeometry.scalePercent(begin, basis) == 100.0)
        val half = checkNotNull(MoveInspectorGeometry.scaled(begin, 50.0, basis))
        check(half == Transform64(34.0, 29.0, 28.0, 18.0))
        check(MoveInspectorGeometry.scaled(half, 100.0, basis) == begin)
        check(MoveInspectorGeometry.scaled(half, 150.0, basis) == Transform64(6.0, 11.0, 84.0, 54.0))
        val nonuniform = Transform64(10.0, 10.0, 112.0, 90.0, 37.0, true, false)
        check(MoveInspectorGeometry.scalePercent(nonuniform, basis) == 200.0)
        check(MoveInspectorGeometry.scaled(nonuniform, 200.0, basis) ==
            Transform64(10.0, 19.0, 112.0, 72.0, 37.0, true, false))
        check(MoveInspectorGeometry.scaled(begin, 12.5, basis) == Transform64(44.5, 35.75, 7.0, 4.5))
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE))
            check(MoveInspectorGeometry.scaled(begin, invalid, basis) == null)
        // A new committed group edit starts at 100% around the newly committed bounds.
        val next = checkNotNull(MoveInspectorGeometry.basis(primary, TransformTarget.GROUP, half))
        check(MoveInspectorGeometry.scalePercent(half, next) == 100.0)
        check(MoveInspectorGeometry.scaled(half, 200.0, next) == begin)
    }
    check(MoveInspectorGeometry.basis(group, TransformTarget.GROUP) == null)
    check(MoveInspectorGeometry.basis(group, TransformTarget.GROUP, begin.copy(width = Double.NaN)) == null)
    check(MoveInspectorGeometry.basis(group, TransformTarget.GROUP, begin.copy(height = 0.0)) == null)
    check(MoveInspectorGeometry.basis(layer, TransformTarget.LAYER, begin) == MoveScaleBasis(4.0, 4.0))
    return "PASS: group/multiselect begin56x36 basis, center-preserving50/100/150/fraction, nonuniform equal-percent normalization, invalid refusal and next-edit100 reset; no runtime/UI claim."
}
