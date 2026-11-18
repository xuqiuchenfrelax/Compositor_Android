package com.pojia.compositor.mobile

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sin

internal enum class ShapeKind { RECTANGLE, ELLIPSE, LINE }
internal enum class ShapeLineStyle(val title: String, val wireValue: Int, val dash: Double, val gap: Double) {
    SOLID("Solid", 0, 0.0, 0.0), DOTTED("Dotted", 1, 1.0, 1.0),
    DASHED("Dashed", 2, 3.0, 2.0), LONG_DASH("Long Dash", 3, 7.0, 2.0)
}
internal enum class ShapeLineCap(val title: String, val wireValue: Int) { SQUARE("Square", 1), ROUND("Round", 0) }

internal data class ShapeDraft(val kind: ShapeKind, val anchorX: Double, val anchorY: Double,
    val x: Double, val y: Double, val width: Double, val height: Double,
    val endX: Double = x, val endY: Double = y,
    val lineStyle: ShapeLineStyle = ShapeLineStyle.SOLID, val lineCap: ShapeLineCap = ShapeLineCap.SQUARE,
    val cornerRadius: Double = 0.0)

internal data class ShapeSpec(val kind: ShapeKind, val width: Int, val height: Int,
    val color: Int, val cornerRadius: Double, val lineWidth: Double,
    val startX: Double = 0.0, val startY: Double = 0.0,
    val endX: Double = 1.0, val endY: Double = 1.0,
    val lineStyle: ShapeLineStyle = ShapeLineStyle.SOLID, val lineCap: ShapeLineCap = ShapeLineCap.SQUARE,
    val geometryWidth: Double = width.toDouble(), val geometryHeight: Double = height.toDouble())

/** Swift's nearest whole pixel rounds half ties away from zero. */
internal fun shapePixel(value: Double): Double = if (value < 0) ceil(value - .5) else floor(value + .5)
internal fun lineGridShift(width: Double): Double = width / 2 - floor(width / 2)
internal fun ShapeLineCap.reach(dx: Double, dy: Double, width: Double): Double {
    val length = hypot(dx, dy)
    if (this == ShapeLineCap.ROUND || length == 0.0) return width / 2
    val corner = width / 2 * (abs(dx) + abs(dy)) / length
    return width / 2 + ceil(corner - width / 2 - .0001)
}

internal fun shapeDraft(anchorX: Double, anchorY: Double, pointX: Double, pointY: Double,
    kind: ShapeKind, square: Boolean, fromCenter: Boolean, lineWidth: Double,
    lineStyle: ShapeLineStyle = ShapeLineStyle.SOLID, lineCap: ShapeLineCap = ShapeLineCap.SQUARE,
    cornerRadius: Double = 0.0): ShapeDraft {
    val ax = shapePixel(anchorX); val ay = shapePixel(anchorY)
    var ex = shapePixel(pointX); var ey = shapePixel(pointY)
    if (kind == ShapeKind.LINE) {
        if (square) {
            val dx = pointX - ax; val dy = pointY - ay
            val length = hypot(dx, dy)
            val step = PI / 4
            val angle = shapePixel(atan2(dy, dx) / step) * step
            ex = shapePixel(ax + length * cos(angle)); ey = shapePixel(ay + length * sin(angle))
        }
        return ShapeDraft(kind, ax, ay, ax, ay, 0.0, 0.0, ex, ey, lineStyle, lineCap, cornerRadius)
            .withLineWidth(lineWidth)
    }
    var dx = ex - ax; var dy = ey - ay
    if (square) {
        val side = maxOf(abs(dx), abs(dy))
        dx = if (dx < 0) -side else side; dy = if (dy < 0) -side else side
    }
    val x = if (fromCenter) ax - abs(dx) else minOf(ax, ax + dx)
    val y = if (fromCenter) ay - abs(dy) else minOf(ay, ay + dy)
    val width = if (fromCenter) abs(dx) * 2 else abs(dx)
    val height = if (fromCenter) abs(dy) * 2 else abs(dy)
    return ShapeDraft(kind, ax, ay, x, y, width, height, x + width, y + height, lineStyle, lineCap, cornerRadius)
}

/** Preview and commit use the same shifted endpoints and cap-safe document bounds. */
internal fun ShapeDraft.withLineWidth(lineWidth: Double): ShapeDraft {
    if (kind != ShapeKind.LINE) return this
    val shift = lineGridShift(lineWidth)
    val pad = lineCap.reach(endX - anchorX, endY - anchorY, lineWidth)
    return copy(x = minOf(anchorX, endX) + shift - pad, y = minOf(anchorY, endY) + shift - pad,
        width = abs(endX - anchorX) + 2 * pad, height = abs(endY - anchorY) + 2 * pad)
}

internal fun ShapeDraft.spec(color: Int, cornerRadius: Double, lineWidth: Double): ShapeSpec? {
    if (!lineWidth.isFinite() || lineWidth !in 1.0..5000.0 || !cornerRadius.isFinite()) return null
    val bounds = withLineWidth(lineWidth)
    if (!bounds.width.isFinite() || !bounds.height.isFinite() || bounds.width < 1 || bounds.height < 1 ||
        bounds.width > Int.MAX_VALUE || bounds.height > Int.MAX_VALUE) return null
    // Mac's bitmap context truncates a fractional extent, while its line geometry keeps the original doubles.
    val w = bounds.width.toInt(); val h = bounds.height.toInt()
    if (w.toLong() * h > 16_000_000L) return null
    val shift = lineGridShift(lineWidth)
    return if (kind == ShapeKind.LINE) ShapeSpec(kind, w, h, color or 0xff000000.toInt(), 0.0, lineWidth,
        (anchorX + shift - bounds.x) / bounds.width, (anchorY + shift - bounds.y) / bounds.height,
        (endX + shift - bounds.x) / bounds.width, (endY + shift - bounds.y) / bounds.height,
        lineStyle, lineCap, bounds.width, bounds.height)
    else ShapeSpec(kind, w, h, color, cornerRadius.coerceAtLeast(0.0), lineWidth)
}

internal data class ShapeLinePiece(val startX: Double, val startY: Double, val endX: Double, val endY: Double,
    val round: Boolean, val dot: Boolean = false)

/** Caps are included in the visible dash lengths, matching Mac strokeShapeLine. */
internal fun shapeLinePieces(startX: Double, startY: Double, endX: Double, endY: Double,
    width: Double, style: ShapeLineStyle, cap: ShapeLineCap): Sequence<ShapeLinePiece> = sequence {
    val length = hypot(endX - startX, endY - startY)
    val dx = if (length > 0) (endX - startX) / length else 1.0
    val dy = if (length > 0) (endY - startY) / length else 0.0
    fun piece(a: Double, b: Double, round: Boolean, dot: Boolean = false): ShapeLinePiece =
        ShapeLinePiece(startX + dx * (a - width / 2), startY + dy * (a - width / 2),
            startX + dx * (b - width / 2), startY + dy * (b - width / 2), round, dot)
    val total = length + width
    if (style == ShapeLineStyle.SOLID) {
        yield(if (cap == ShapeLineCap.SQUARE) piece(0.0, total, false)
            else ShapeLinePiece(startX, startY, endX, endY, true, length == 0.0))
    } else {
        val dash = style.dash * width; val period = (style.dash + style.gap) * width
        var distance = 0.0
        while (distance < total) {
            val next = minOf(total, distance + dash)
            if (cap == ShapeLineCap.SQUARE) yield(piece(distance, next, false))
            else {
                val a = distance + width / 2; val b = next - width / 2
                if (b - a > .001) yield(piece(a, b, true))
                else yield(piece((distance + next) / 2, (distance + next) / 2, true, true))
            }
            distance += period
        }
    }
}

/** Reference rasterizer retained for geometry/color tests; product commits use ABI18 native tiles. */
internal object ShapeRaster {
    fun rgba(spec: ShapeSpec): ByteArray {
        val result = ByteArray(spec.width * spec.height * 4)
        val alpha = (spec.color ushr 24) and 255
        fun premultiply(channel: Int) = ((channel * alpha + 127) / 255).toByte()
        val red = premultiply((spec.color ushr 16) and 255)
        val green = premultiply((spec.color ushr 8) and 255)
        val blue = premultiply(spec.color and 255)
        val radius = spec.cornerRadius.coerceAtMost(minOf(spec.width, spec.height) / 2.0)
        val sx = spec.startX * spec.geometryWidth; val sy = spec.startY * spec.geometryHeight
        val ex = spec.endX * spec.geometryWidth; val ey = spec.endY * spec.geometryHeight
        val vx = ex - sx; val vy = ey - sy; val length = hypot(vx, vy)
        val ux = if (length > 0) vx / length else 1.0
        val uy = if (length > 0) vy / length else 0.0
        for (y in 0 until spec.height) for (x in 0 until spec.width) {
            val px = x + .5; val py = y + .5
            val inside = when (spec.kind) {
                ShapeKind.ELLIPSE -> {
                    val dx = (px - spec.width / 2.0) / (spec.width / 2.0).coerceAtLeast(.5)
                    val dy = (py - spec.height / 2.0) / (spec.height / 2.0).coerceAtLeast(.5)
                    dx * dx + dy * dy <= 1.0
                }
                ShapeKind.RECTANGLE -> {
                    if (radius <= 0) true else {
                        val qx = maxOf(radius - px, 0.0, px - (spec.width - radius))
                        val qy = maxOf(radius - py, 0.0, py - (spec.height - radius))
                        qx * qx + qy * qy <= radius * radius
                    }
                }
                ShapeKind.LINE -> {
                    val half = spec.lineWidth / 2
                    val along = (px - sx) * ux + (py - sy) * uy + half
                    val across = -(px - sx) * uy + (py - sy) * ux
                    val total = length + spec.lineWidth
                    fun covered(a: Double, b: Double): Boolean {
                        if (spec.lineCap == ShapeLineCap.SQUARE)
                            return along >= a && along <= b && abs(across) <= half
                        val start = a + half; val end = b - half
                        val center = if (end - start > .001) along.coerceIn(start, end) else (a + b) / 2
                        return (along - center) * (along - center) + across * across <= half * half
                    }
                    if (spec.lineStyle == ShapeLineStyle.SOLID) covered(0.0, total)
                    else {
                        val period = (spec.lineStyle.dash + spec.lineStyle.gap) * spec.lineWidth
                        val candidate = floor(along / period).toLong()
                        (candidate - 1..candidate + 1).any { index ->
                            val a = index * period
                            a >= 0 && a < total && covered(a, minOf(total, a + spec.lineStyle.dash * spec.lineWidth))
                        }
                    }
                }
            }
            if (inside) {
                val at = (y * spec.width + x) * 4
                result[at] = red; result[at + 1] = green; result[at + 2] = blue; result[at + 3] = alpha.toByte()
            }
        }
        return result
    }
}
