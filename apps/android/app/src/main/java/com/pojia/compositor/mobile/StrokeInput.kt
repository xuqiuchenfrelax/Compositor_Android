package com.pojia.compositor.mobile

import kotlin.math.hypot
import kotlin.math.max

internal data class StrokeInputSample(val x: Double, val y: Double, val pressure: Double, val time: Double)

/** Desktop string smoothing in document coordinates, with pressure and time left on each sample. */
internal class StrokeInput(settings: StrokeSettings, zoom: Double) {
    init { require(zoom.isFinite() && zoom > 0) }
    private val radius = settings.smoothing.toDouble() / max(0.01, zoom)
    private var anchor: StrokeInputSample? = null
    private var pointer: StrokeInputSample? = null
    private var ended = false

    fun append(sample: StrokeInputSample): StrokeInputSample? {
        check(!ended) { "Stroke input has ended" }
        require(sample.x.isFinite() && sample.y.isFinite() && sample.pressure.isFinite() &&
            sample.pressure in 0.0..1.0 && sample.time.isFinite() && sample.time >= 0 &&
            (pointer == null || sample.time >= checkNotNull(pointer).time)) { "Invalid stroke input" }
        pointer = sample
        val previous = anchor
        if (previous == null || radius == 0.0) { anchor = sample; return sample }
        val dx = sample.x - previous.x; val dy = sample.y - previous.y
        val distance = hypot(dx, dy)
        if (distance <= radius) return null
        val step = (distance - radius) / distance
        return sample.copy(x = previous.x + dx * step, y = previous.y + dy * step).also { anchor = it }
    }

    /** Mouse-up catches the brush up to the pointer, using that endpoint's pressure and timestamp. */
    fun finish(): StrokeInputSample? {
        check(!ended) { "Stroke input has ended" }
        ended = true
        val endpoint = pointer
        val painted = anchor
        anchor = null; pointer = null
        return if (radius > 0 && endpoint != null && painted != null &&
            (endpoint.x != painted.x || endpoint.y != painted.y)) endpoint else null
    }

    fun cancel() { ended = true; anchor = null; pointer = null }
}
