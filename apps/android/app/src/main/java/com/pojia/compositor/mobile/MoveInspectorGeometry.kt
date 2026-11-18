package com.pojia.compositor.mobile

internal data class MoveScaleBasis(val width: Double, val height: Double)

/** Inspector Scale uses source pixels, committed owning-layer size for an independent mask, or the group begin box. */
internal object MoveInspectorGeometry {
    fun basis(layer: LayerState, target: TransformTarget, groupBox: Transform64? = null): MoveScaleBasis? {
        if (target == TransformTarget.GROUP) return groupBox?.let {
            MoveScaleBasis(it.width, it.height).takeIf { basis ->
                basis.width.isFinite() && basis.height.isFinite() && basis.width > 0 && basis.height > 0
            }
        }
        if (layer.isGroup) return null
        val result = when (target) {
            TransformTarget.LAYER -> MoveScaleBasis(layer.pixelWidth.toDouble(), layer.pixelHeight.toDouble())
            TransformTarget.UNLINKED_MASK -> {
                if (!layer.hasMask || layer.maskLinked) return null
                MoveScaleBasis(layer.transform.width, layer.transform.height)
            }
            TransformTarget.GROUP -> return null
        }
        return result.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
    }

    fun scalePercent(box: Transform64, basis: MoveScaleBasis): Double =
        box.width / maxOf(1.0, basis.width) * 100.0

    fun scaled(box: Transform64, percent: Double, basis: MoveScaleBasis): Transform64? {
        if (!percent.isFinite() || !basis.width.isFinite() || !basis.height.isFinite() ||
            basis.width <= 0 || basis.height <= 0) return null
        val width = basis.width * percent / 100.0
        val height = basis.height * percent / 100.0
        val centerX = box.x + box.width / 2.0
        val centerY = box.y + box.height / 2.0
        val result = box.copy(x = centerX - width / 2.0, y = centerY - height / 2.0,
            width = width, height = height)
        return result.takeIf(MoveGeometry::valid)
    }

    fun flipped(box: Transform64, horizontal: Boolean): Transform64 =
        if (horizontal) box.copy(flipX = !box.flipX) else box.copy(flipY = !box.flipY)
}
