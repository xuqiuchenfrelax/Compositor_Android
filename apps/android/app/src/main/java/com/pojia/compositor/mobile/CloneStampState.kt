package com.pojia.compositor.mobile

import kotlin.math.ceil
import kotlin.math.floor

internal data class CloneStampPoint(val x: Double, val y: Double)
internal data class CloneStampSnapshot(val source: CloneStampPoint?, val offset: CloneStampPoint?,
    val aligned: Boolean, val sampleAllLayers: Boolean)
internal data class CloneStampStroke(val sourceVersion: Long, val offsetX: Double, val offsetY: Double,
    val aligned: Boolean, val sampleAllLayers: Boolean)

/** Session-only clone source and alignment belong to the document, not its Activity. */
internal class CloneStampState {
    private var source: CloneStampPoint? = null
    private var offset: CloneStampPoint? = null
    private var aligned = true
    private var sampleAllLayers = false
    private var sourceVersion = 0L

    @Synchronized fun snapshot() = CloneStampSnapshot(source, offset, aligned, sampleAllLayers)
    @Synchronized fun setSource(x: Double, y: Double) {
        require(x.isFinite() && y.isFinite()) { "Invalid Clone Stamp source" }
        source = CloneStampPoint(x, y)
        offset = null
        sourceVersion++
    }
    @Synchronized fun setAligned(value: Boolean) { aligned = value }
    @Synchronized fun setSampleAllLayers(value: Boolean) { sampleAllLayers = value }
    @Synchronized fun capture(x: Double, y: Double): CloneStampStroke? {
        require(x.isFinite() && y.isFinite()) { "Invalid Clone Stamp position" }
        val start = source ?: return null
        val delta = offset.takeIf { aligned } ?: CloneStampPoint(
            roundedPixel(start.x - x), roundedPixel(start.y - y))
        return CloneStampStroke(sourceVersion, delta.x, delta.y, aligned, sampleAllLayers)
    }
    /** Failed native begins never fix an alignment; resetting source invalidates an older capture. */
    @Synchronized fun started(stroke: CloneStampStroke) {
        if (stroke.sourceVersion == sourceVersion) offset = CloneStampPoint(stroke.offsetX, stroke.offsetY)
    }
    @Synchronized fun samplePoint(x: Double, y: Double, active: Boolean = false): CloneStampPoint? {
        val start = source ?: return null
        val delta = offset
        return if (delta != null && (aligned || active)) CloneStampPoint(x + delta.x, y + delta.y) else start
    }
    private fun roundedPixel(value: Double): Double = if (value < 0) ceil(value - .5) else floor(value + .5)
}
