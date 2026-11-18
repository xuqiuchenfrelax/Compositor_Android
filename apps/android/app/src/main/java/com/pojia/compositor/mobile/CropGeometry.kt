package com.pojia.compositor.mobile

import kotlin.math.roundToInt
import kotlin.math.abs

internal enum class CropRatioChoice(val title: String) {
    FREE("Free"), ORIGINAL("Original"), SQUARE("1:1"), FOUR_THREE("4:3"),
    THREE_FOUR("3:4"), SIXTEEN_NINE("16:9"), NINE_SIXTEEN("9:16"),
    CINEMATIC_239("2.39:1"), CUSTOM("Custom");

    fun value(canvasWidth: Int, canvasHeight: Int, custom: Double? = null): Double? = when (this) {
        FREE -> null
        ORIGINAL -> if (canvasHeight > 0) canvasWidth.toDouble() / canvasHeight else null
        SQUARE -> 1.0
        FOUR_THREE -> 4.0 / 3.0
        THREE_FOUR -> 3.0 / 4.0
        SIXTEEN_NINE -> 16.0 / 9.0
        NINE_SIXTEEN -> 9.0 / 16.0
        CINEMATIC_239 -> 2.39
        CUSTOM -> custom?.takeIf { it.isFinite() && it in 0.01..100.0 }
    }
}

/** Parses the Width:Height syntax used by the Mac crop popover. */
internal fun parseCropRatio(text: String): Double? {
    val parts = text.trim().split(':', limit = 2)
    if (parts.size != 2) return null
    fun parsePart(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()
    val width = parsePart(parts[0]) ?: return null
    val height = parsePart(parts[1]) ?: return null
    if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return null
    return (width / height).takeIf { it.isFinite() && it in 0.01..100.0 }
}

/** Document-space crop rectangle. Coordinates are always normalized and finite. */
internal data class CropRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() }) }
    val width get() = right - left
    val height get() = bottom - top
    fun normalized() = CropRect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
    fun clamped(width: Int, height: Int): CropRect {
        val n = normalized()
        return CropRect(n.left.coerceIn(0.0, width.toDouble()), n.top.coerceIn(0.0, height.toDouble()),
            n.right.coerceIn(0.0, width.toDouble()), n.bottom.coerceIn(0.0, height.toDouble())).normalized()
    }
    fun snapped(width: Int, height: Int): CropRect {
        val c = clamped(width, height)
        var left = c.left.roundToInt().toDouble().coerceIn(0.0, width.toDouble())
        var top = c.top.roundToInt().toDouble().coerceIn(0.0, height.toDouble())
        var right = c.right.roundToInt().toDouble().coerceIn(0.0, width.toDouble())
        var bottom = c.bottom.roundToInt().toDouble().coerceIn(0.0, height.toDouble())
        if (right <= left) {
            right = (left + 1.0).coerceAtMost(width.toDouble())
            left = (right - 1.0).coerceAtLeast(0.0)
        }
        if (bottom <= top) {
            bottom = (top + 1.0).coerceAtMost(height.toDouble())
            top = (bottom - 1.0).coerceAtLeast(0.0)
        }
        return CropRect(left, top, right, bottom)
    }
    fun translated(dx: Double, dy: Double, width: Int, height: Int): CropRect {
        val n = normalized()
        val w = n.width.coerceIn(1.0, width.toDouble())
        val h = n.height.coerceIn(1.0, height.toDouble())
        val left = (n.left + dx).coerceIn(0.0, width - w)
        val top = (n.top + dy).coerceIn(0.0, height - h)
        return CropRect(left, top, left + w, top + h)
    }
}

internal fun cropCreate(start: Pair<Double, Double>, end: Pair<Double, Double>, ratio: Double?, symmetric: Boolean): CropRect {
    var dx = end.first - start.first
    var dy = end.second - start.second
    if (ratio != null && ratio.isFinite() && ratio > 0.0) {
        if (abs(dx) > abs(dy) * ratio) dy = (if (dy < 0) -1 else 1) * abs(dx) / ratio
        else dx = (if (dx < 0) -1 else 1) * abs(dy) * ratio
    }
    return if (symmetric) CropRect(start.first - abs(dx), start.second - abs(dy),
        start.first + abs(dx), start.second + abs(dy))
    else CropRect(minOf(start.first, start.first + dx), minOf(start.second, start.second + dy),
        maxOf(start.first, start.first + dx), maxOf(start.second, start.second + dy))
}

internal enum class CropHandle { NONE, BODY, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

internal fun CropRect.hitHandle(x: Double, y: Double, radius: Double): CropHandle {
    val n = normalized()
    fun near(a: Double, b: Double) = kotlin.math.abs(a - b) <= radius
    val left = near(x, n.left); val right = near(x, n.right); val top = near(y, n.top); val bottom = near(y, n.bottom)
    return when {
        left && top -> CropHandle.TOP_LEFT; right && top -> CropHandle.TOP_RIGHT
        left && bottom -> CropHandle.BOTTOM_LEFT; right && bottom -> CropHandle.BOTTOM_RIGHT
        left && y in n.top..n.bottom -> CropHandle.LEFT; right && y in n.top..n.bottom -> CropHandle.RIGHT
        top && x in n.left..n.right -> CropHandle.TOP; bottom && x in n.left..n.right -> CropHandle.BOTTOM
        x in n.left..n.right && y in n.top..n.bottom -> CropHandle.BODY
        else -> CropHandle.NONE
    }
}
