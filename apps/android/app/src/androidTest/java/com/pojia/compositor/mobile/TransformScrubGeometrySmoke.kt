package com.pojia.compositor.mobile

/** Literal Mac scrub expectations; typing, pointer ownership and native history are checked separately. */
internal fun transformScrubGeometrySmoke(): String {
    fun value(field: TransformScrubField, start: Double, translation: Double): Double? =
        TransformScrubGeometry.begin(field, start)?.let { TransformScrubGeometry.value(it, translation) }

    check(TransformScrubField.X.moveField == MoveField.X)
    check(TransformScrubField.Y.moveField == MoveField.Y)
    check(TransformScrubField.WIDTH.moveField == MoveField.WIDTH)
    check(TransformScrubField.HEIGHT.moveField == MoveField.HEIGHT)
    check(TransformScrubField.ROTATION.moveField == MoveField.ROTATION)
    check(TransformScrubField.SCALE.moveField == null)

    check(value(TransformScrubField.X, 0.0, .5) == 1.0)
    check(value(TransformScrubField.X, 0.0, -.5) == -1.0)
    check(value(TransformScrubField.Y, 1.25, .25) == 2.0)
    check(value(TransformScrubField.Y, -1.25, -.25) == -2.0)
    check(value(TransformScrubField.X, 0.0, .499) == 0.0)
    check(value(TransformScrubField.X, 0.0, -.499) == 0.0)

    val fixed = checkNotNull(TransformScrubGeometry.begin(TransformScrubField.X, 100.25))
    check(TransformScrubGeometry.value(fixed, 4.0) == 104.0)
    check(TransformScrubGeometry.value(fixed, 9.0) == 109.0)
    check(TransformScrubGeometry.value(fixed, -2.0) == 98.0)
    check(fixed.valueAtDown == 100.25) { "Preview updates must not replace the DOWN value" }
    val edge = checkNotNull(TransformScrubGeometry.begin(TransformScrubField.X, 29_999.0))
    check(TransformScrubGeometry.value(edge, 50.0) == 30_000.0)
    check(TransformScrubGeometry.value(edge, .25) == 29_999.0) {
        "Returning from a clamped preview must still use the original pointer displacement"
    }

    for (field in listOf(TransformScrubField.X, TransformScrubField.Y)) {
        check(value(field, 0.0, 40_000.0) == 30_000.0)
        check(value(field, 0.0, -40_000.0) == -30_000.0)
    }
    for (field in listOf(TransformScrubField.WIDTH, TransformScrubField.HEIGHT)) {
        check(value(field, 10.0, -20.0) == 1.0)
        check(value(field, 10.0, 40_000.0) == 30_000.0)
        check(value(field, 10.25, 1.25) == 12.0)
    }
    check(value(TransformScrubField.SCALE, .1, 0.0) == .1) {
        "Round before clamping: the minimum Scale remains 0.1 rather than zero"
    }
    check(value(TransformScrubField.SCALE, .1, .4) == 1.0)
    check(value(TransformScrubField.SCALE, 100.0, -1_000.0) == .1)
    check(value(TransformScrubField.SCALE, 100.0, 40_000.0) == 30_000.0)
    check(value(TransformScrubField.ROTATION, 0.0, 400.0) == 360.0)
    check(value(TransformScrubField.ROTATION, 0.0, -400.0) == -360.0)
    check(value(TransformScrubField.ROTATION, 359.5, 0.0) == 360.0) {
        "The scrub helper clamps; the existing transform setter handles rotation normalization"
    }

    val typedOutsideScrubRange = checkNotNull(TransformScrubGeometry.begin(TransformScrubField.X, 40_000.0))
    check(typedOutsideScrubRange.valueAtDown == 40_000.0)
    check(TransformScrubGeometry.value(typedOutsideScrubRange, -15_000.0) == 25_000.0) {
        "Do not clamp the DOWN value or change typed-value limits"
    }
    for (field in TransformScrubField.entries) {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            check(TransformScrubGeometry.begin(field, invalid) == null)
            check(value(field, 10.0, invalid) == null)
            check(TransformScrubGeometry.value(TransformScrubBasis(field, invalid), 1.0) == null)
        }
        check(value(field, Double.MAX_VALUE, Double.MAX_VALUE) == null)
        check(value(field, -Double.MAX_VALUE, -Double.MAX_VALUE) == null)
    }
    return "Transform label scrub: fixed DOWN basis, logical-point translation, ties away from zero, step-before-clamp ranges, typed-range separation and nonfinite/overflow rejection passed. No UI, JNI, history or device claim."
}
