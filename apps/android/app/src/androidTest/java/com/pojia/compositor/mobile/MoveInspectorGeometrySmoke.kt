package com.pojia.compositor.mobile

/** Independent numeric expectations; UI text events and native publication have separate selectors. */
internal fun moveInspectorGeometrySmoke(): String {
    val basis = MoveScaleBasis(40.0, 20.0)
    val placed = Transform64(10.0, 20.0, 80.0, 60.0, 37.0, true, false)
    check(MoveInspectorGeometry.scalePercent(placed, basis) == 200.0)
    val fifty = Transform64(40.0, 45.0, 20.0, 10.0, 37.0, true, false)
    val hundred = Transform64(30.0, 40.0, 40.0, 20.0, 37.0, true, false)
    val equalNumericEdit = Transform64(10.0, 30.0, 80.0, 40.0, 37.0, true, false)
    check(MoveInspectorGeometry.scaled(placed, 50.0, basis) == fifty)
    check(MoveInspectorGeometry.scaled(fifty, 100.0, basis) == hundred) {
        "Scale must use original pixels rather than multiplying the preceding preview"
    }
    check(MoveInspectorGeometry.scaled(placed, 200.0, basis) == equalNumericEdit && equalNumericEdit != placed) {
        "An equal numeric Scale edit must still normalize a nonuniform height about its center"
    }
    check(MoveInspectorGeometry.scaled(equalNumericEdit, 200.0, basis) == equalNumericEdit)
    check(MoveInspectorGeometry.scaled(placed, 12.8125, basis) ==
        Transform64(47.4375, 48.71875, 5.125, 2.5625, 37.0, true, false))
    check(MoveInspectorGeometry.scaled(Transform64(0.0, 0.0, 4000.0, 2000.0), .05,
        MoveScaleBasis(4000.0, 2000.0)) == Transform64(1999.0, 999.5, 2.0, 1.0))
    check(MoveInspectorGeometry.scaled(Transform64(), 40_000.0, MoveScaleBasis(1.0, 1.0)) ==
        Transform64(-199.5, -199.5, 400.0, 400.0)) { "Typed Scale must not inherit the scrub range" }
    for (percent in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.MAX_VALUE, 1.0, 1_000_000.0)) {
        check(MoveInspectorGeometry.scaled(placed, percent, basis) == null) { "Accepted invalid Scale $percent" }
    }
    check(MoveInspectorGeometry.scaled(placed.copy(x = 1_000_000.0, width = 300_000.0), 50.0, basis) == null)
    check(MoveInspectorGeometry.scaled(placed.copy(rotation = Double.NaN), 50.0, basis) == null)

    val horizontal = placed.copy(flipX = false)
    val vertical = placed.copy(flipY = true)
    check(MoveInspectorGeometry.flipped(placed, true) == horizontal)
    check(MoveInspectorGeometry.flipped(placed, false) == vertical)
    check(MoveInspectorGeometry.flipped(horizontal, true) == placed)
    check(MoveInspectorGeometry.flipped(vertical, false) == placed)
    check(MoveInspectorGeometry.flipped(horizontal, false) == MoveInspectorGeometry.flipped(vertical, true))

    val layer = LayerState(1, 1.0, true, 0, pixelWidth = 40, pixelHeight = 20,
        transform = placed, assetPresent = true)
    check(MoveInspectorGeometry.basis(layer, TransformTarget.LAYER) == basis)
    val mask = layer.copy(hasMask = true, maskLinked = false, maskWidth = 8, maskHeight = 8,
        transform = Transform64(0.0, 0.0, 200.0, 100.0),
        maskPlacement = Transform64(80.0, 35.0, 40.0, 30.0))
    val maskBasis = checkNotNull(MoveInspectorGeometry.basis(mask, TransformTarget.UNLINKED_MASK))
    check(maskBasis == MoveScaleBasis(200.0, 100.0))
    check(MoveInspectorGeometry.scalePercent(checkNotNull(mask.maskPlacement), maskBasis) == 20.0)
    check(MoveInspectorGeometry.scaled(checkNotNull(mask.maskPlacement), 50.0, maskBasis) ==
        Transform64(50.0, 25.0, 100.0, 50.0)) { "Independent mask Scale must use its committed owning layer" }
    check(MoveInspectorGeometry.basis(mask.copy(maskLinked = true), TransformTarget.LAYER) == basis)
    check(MoveInspectorGeometry.basis(mask.copy(maskLinked = true), TransformTarget.UNLINKED_MASK) == null)
    check(MoveInspectorGeometry.basis(mask.copy(hasMask = false), TransformTarget.UNLINKED_MASK) == null)
    check(MoveInspectorGeometry.basis(layer, TransformTarget.GROUP) == null)
    check(MoveInspectorGeometry.basis(layer.copy(pixelWidth = 0), TransformTarget.LAYER) == null)
    check(MoveInspectorGeometry.basis(mask.copy(transform = mask.transform.copy(height = Double.NaN)),
        TransformTarget.UNLINKED_MASK) == null)
    return "Independent Scale40x20/nonuniform200/50/100/fraction/typed-range/invalid-result expectations, local Flip parity and geometry preservation, original-pixel versus committed-owner independent-mask basis passed. No UI, JNI, pixels or lifecycle claim."
}
