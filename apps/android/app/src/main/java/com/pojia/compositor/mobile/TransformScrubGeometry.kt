package com.pojia.compositor.mobile

internal enum class TransformScrubField(val lowerBound: Double, val upperBound: Double,
    val moveField: MoveField? = null) {
    X(-30_000.0, 30_000.0, MoveField.X),
    Y(-30_000.0, 30_000.0, MoveField.Y),
    WIDTH(1.0, 30_000.0, MoveField.WIDTH),
    HEIGHT(1.0, 30_000.0, MoveField.HEIGHT),
    SCALE(.1, 30_000.0),
    ROTATION(-360.0, 360.0, MoveField.ROTATION)
}

internal data class TransformScrubBasis(val field: TransformScrubField, val valueAtDown: Double)

/** Mac NumericScrub: fixed starting value, one unit per logical point, whole steps before range limits. */
internal object TransformScrubGeometry {
    fun begin(field: TransformScrubField, valueAtDown: Double): TransformScrubBasis? =
        if (valueAtDown.isFinite()) TransformScrubBasis(field, valueAtDown) else null

    fun value(basis: TransformScrubBasis, translationDp: Double): Double? {
        if (!basis.valueAtDown.isFinite() || !translationDp.isFinite()) return null
        val proposed = basis.valueAtDown + translationDp
        if (!proposed.isFinite()) return null
        return MoveGeometry.rounded(proposed).coerceIn(basis.field.lowerBound, basis.field.upperBound)
    }
}
