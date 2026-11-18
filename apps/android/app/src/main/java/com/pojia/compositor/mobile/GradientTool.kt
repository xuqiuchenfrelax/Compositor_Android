package com.pojia.compositor.mobile

import kotlin.math.hypot

/** The two gradient geometries exposed by the Mac Gradient tool. */
internal enum class GradientShape { LINEAR, RADIAL }

/** Descriptive alias used by geometry code; the public tool state follows the Mac name. */
internal typealias GradientKind = GradientShape

/** The Mac tool's two source presets. */
internal enum class GradientStyle { FOREGROUND_TO_BACKGROUND, FOREGROUND_TO_TRANSPARENT }

/** A gradient can paint the pixels or the selected layer mask. */
internal enum class GradientTarget { PIXELS, MASK }

/** Settings are captured when a gradient drag starts, just like brush settings. */
internal data class GradientSettings(
    val kind: GradientShape = GradientShape.LINEAR,
    val style: GradientStyle = GradientStyle.FOREGROUND_TO_BACKGROUND,
    val reversed: Boolean = false,
    val opacity: Float = 1f,
    val target: GradientTarget = GradientTarget.PIXELS,
) {
    init {
        require(opacity.isFinite() && opacity in 0.01f..1f) {
            "Gradient opacity must be between 0.01 and 1"
        }
    }
}

/**
 * A pending drag. Coordinates are document pixels; no raster data is stored here, so moving
 * either handle never accumulates an earlier preview. The native transaction owns the pixels.
 */
internal data class GradientDraft(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
) {
    init {
        require(startX.isFinite() && startY.isFinite() && endX.isFinite() && endY.isFinite()) {
            "Gradient endpoints must be finite"
        }
    }

    val dx: Double get() = endX - startX
    val dy: Double get() = endY - startY
    val length: Double get() = hypot(dx, dy)
    val isMeaningful: Boolean get() = length >= MINIMUM_LENGTH

    fun moveStart(x: Double, y: Double) = copy(startX = x, startY = y)
    fun moveEnd(x: Double, y: Double) = copy(endX = x, endY = y)
    fun translate(dx: Double, dy: Double) = copy(
        startX = startX + dx, startY = startY + dy,
        endX = endX + dx, endY = endY + dy,
    )

    /** Linear interpolation parameter. It intentionally extends beyond both handles. */
    fun linearParameter(x: Double, y: Double): Double {
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0) return 0.0
        return ((x - startX) * dx + (y - startY) * dy) / lengthSquared
    }

    /** Radial distance relative to the start handle, normalized by the end handle radius. */
    fun radialParameter(x: Double, y: Double): Double {
        val radius = length
        if (radius <= 0.0) return 0.0
        return hypot(x - startX, y - startY) / radius
    }

    companion object {
        // A click-sized drag is a Mac no-op and is cancelled rather than adding an undo item.
        const val MINIMUM_LENGTH = 0.5
    }
}

internal data class GradientColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
    init {
        require(listOf(red, green, blue, alpha).all { it.isFinite() && it in 0f..1f }) {
            "Gradient color components must be between 0 and 1"
        }
    }

    fun withOpacity(value: Float) = copy(alpha = (alpha * value).coerceIn(0f, 1f))

    /** Interpolate in premultiplied space, avoiding dark fringes in transparent stops. */
    fun mix(other: GradientColor, amount: Float): GradientColor {
        val t = amount.coerceIn(0f, 1f)
        val a = alpha + (other.alpha - alpha) * t
        if (a <= 0f) return GradientColor(0f, 0f, 0f, 0f)
        val r = (red * alpha + (other.red * other.alpha - red * alpha) * t) / a
        val g = (green * alpha + (other.green * other.alpha - green * alpha) * t) / a
        val b = (blue * alpha + (other.blue * other.alpha - blue * alpha) * t) / a
        return GradientColor(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a)
    }
}

/** Immutable payload sent to the retained command queue for one gradient transaction. */
internal data class GradientSpec(
    val settings: GradientSettings,
    val draft: GradientDraft,
    val width: Int,
    val height: Int,
    val foreground: GradientColor,
    val background: GradientColor,
    val layerId: Long,
) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height.toLong() <= 16_000_000L)
        require(layerId > 0)
    }
}

/** Returns the color at a document point for a pending gradient. */
internal fun GradientSettings.sample(
    draft: GradientDraft,
    x: Double,
    y: Double,
    foreground: GradientColor,
    background: GradientColor,
): GradientColor {
    val parameter = when (kind) {
        GradientKind.LINEAR -> draft.linearParameter(x, y)
        GradientKind.RADIAL -> draft.radialParameter(x, y)
    }
    val amount = if (reversed) 1.0 - parameter else parameter
    val end = when (style) {
        GradientStyle.FOREGROUND_TO_BACKGROUND -> background
        GradientStyle.FOREGROUND_TO_TRANSPARENT -> GradientColor(0f, 0f, 0f, 0f)
    }
    return foreground.mix(end, amount.toFloat()).withOpacity(opacity)
}

/** Mask gradients interpolate coverage directly; transparent means zero coverage. */
internal fun GradientSettings.maskSample(
    draft: GradientDraft,
    x: Double,
    y: Double,
    foregroundValue: Float,
    backgroundValue: Float,
): Float {
    val parameter = when (kind) {
        GradientKind.LINEAR -> draft.linearParameter(x, y)
        GradientKind.RADIAL -> draft.radialParameter(x, y)
    }
    val amount = (if (reversed) 1.0 - parameter else parameter).toFloat().coerceIn(0f, 1f)
    val end = when (style) {
        GradientStyle.FOREGROUND_TO_BACKGROUND -> backgroundValue
        GradientStyle.FOREGROUND_TO_TRANSPARENT -> 0f
    }
    return (foregroundValue + (end - foregroundValue) * amount).coerceIn(0f, 1f) * opacity
}

/** CPU fallback for the first mobile release. The command remains one undoable edit. */
internal object GradientRaster {
    fun rgba(spec: GradientSpec): ByteArray {
        val result = ByteArray(spec.width * spec.height * 4)
        for (y in 0 until spec.height) for (x in 0 until spec.width) {
            val color = if (spec.settings.target == GradientTarget.MASK) {
                val value = spec.settings.maskSample(spec.draft, x + .5, y + .5, spec.foreground.red, spec.background.red)
                GradientColor(value, value, value, 1f).withOpacity(spec.settings.opacity)
            } else {
                spec.settings.sample(spec.draft, x + .5, y + .5, spec.foreground, spec.background)
            }
            val at = (y * spec.width + x) * 4
            val alpha = (color.alpha * 255f + .5f).toInt().coerceIn(0, 255)
            fun premultiply(channel: Float) = ((channel * alpha) + 127) / 255
            result[at] = premultiply(color.red).toInt().coerceIn(0, 255).toByte()
            result[at + 1] = premultiply(color.green).toInt().coerceIn(0, 255).toByte()
            result[at + 2] = premultiply(color.blue).toInt().coerceIn(0, 255).toByte()
            result[at + 3] = alpha.toByte()
        }
        return result
    }
}
