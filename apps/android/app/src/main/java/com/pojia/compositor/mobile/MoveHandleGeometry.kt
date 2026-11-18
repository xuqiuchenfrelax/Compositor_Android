package com.pojia.compositor.mobile

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal sealed class MoveHandleMode {
    data class Resize(val index: Int) : MoveHandleMode()
    object Rotate : MoveHandleMode()
}

/** View coordinates; screenScale converts Mac screen points to Android view pixels. */
internal data class MoveHandleOverlay(val handles: List<MovePoint>, val rotationHandle: MovePoint,
    val screenScale: Double = 1.0) {
    fun hit(point: MovePoint): MoveHandleMode? {
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        val radius = 10 * screenScale
        fun near(other: MovePoint) = hypot(point.x - other.x, point.y - other.y) <= radius
        if (near(rotationHandle)) return MoveHandleMode.Rotate
        handles.indexOfFirst(::near).takeIf { it >= 0 }?.let { return MoveHandleMode.Resize(it) }
        for (edge in 0..3) {
            val start = edge * 2; val end = (start + 2) % 8; val index = start + 1
            val a = handles[start]; val b = handles[end]
            val dx = b.x - a.x; val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 0) continue
            val t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared
            if (t in 0.0..1.0 && hypot(point.x - a.x - t * dx, point.y - a.y - t * dy) <= radius) {
                return MoveHandleMode.Resize(index)
            }
        }
        return null
    }
}

/** LayerTransform/TransformDrag and TransformOverlayGeometry rules, with clockwise y-down rotation. */
internal object MoveHandleGeometry {
    val units = listOf(MovePoint(0.0, 0.0), MovePoint(.5, 0.0), MovePoint(1.0, 0.0),
        MovePoint(1.0, .5), MovePoint(1.0, 1.0), MovePoint(.5, 1.0), MovePoint(0.0, 1.0), MovePoint(0.0, .5))

    fun point(box: Transform64, unit: MovePoint): MovePoint {
        val radians = (box.rotation % 360) * Math.PI / 180
        val x = (unit.x - .5) * box.width; val y = (unit.y - .5) * box.height
        return MovePoint(box.x + box.width / 2 + x * cos(radians) - y * sin(radians),
            box.y + box.height / 2 + x * sin(radians) + y * cos(radians))
    }

    fun overlay(box: Transform64, pointsPerPixel: Double, viewOrigin: MovePoint,
        screenScale: Double = 1.0): MoveHandleOverlay {
        require(pointsPerPixel.isFinite() && pointsPerPixel > 0 && screenScale.isFinite() && screenScale > 0)
        val handles = units.map { unit ->
            val p = point(box, unit)
            MovePoint(viewOrigin.x + p.x * pointsPerPixel, viewOrigin.y + p.y * pointsPerPixel)
        }
        val radians = (box.rotation % 360) * Math.PI / 180
        val rotation = MovePoint(handles[1].x + sin(radians) * 28 * screenScale,
            handles[1].y - cos(radians) * 28 * screenScale)
        return MoveHandleOverlay(handles, rotation, screenScale)
    }

    /** Keep original/start fixed for the entire drag; modifiers are the current event's values. */
    fun resize(original: Transform64, start: MovePoint, point: MovePoint, index: Int,
        lockRatio: Boolean, shift: Boolean, alt: Boolean = false, rounded: Boolean = true, group: Boolean = false): Transform64 {
        require(index in units.indices)
        val handle = units[index]
        val anchorUnit = if (alt) MovePoint(.5, .5) else MovePoint(1 - handle.x, 1 - handle.y)
        val anchor = point(original, anchorUnit)
        val initialHandle = point(original, handle)
        val dx = initialHandle.x + point.x - start.x - anchor.x
        val dy = initialHandle.y + point.y - start.y - anchor.y
        val radians = (original.rotation % 360) * Math.PI / 180
        val span = if (alt) 2.0 else 1.0
        val localX = (dx * cos(radians) + dy * sin(radians)) * span
        val localY = (-dx * sin(radians) + dy * cos(radians)) * span
        val sx = handle.x * 2 - 1; val sy = handle.y * 2 - 1
        val rawWidth = if (sx == 0.0) original.width else localX * sx
        val rawHeight = if (sy == 0.0) original.height else localY * sy
        val mirroredX = rawWidth < 0; val mirroredY = rawHeight < 0
        var width = maxOf(1.0, abs(rawWidth)); var height = maxOf(1.0, abs(rawHeight))
        if (lockRatio != shift) {
            val factor = when {
                sx == 0.0 -> height / original.height
                sy == 0.0 -> width / original.width
                else -> maxOf(1 / minOf(original.width, original.height),
                    (localX * sx * original.width + localY * sy * original.height) /
                        (original.width * original.width + original.height * original.height))
            }
            width = original.width * factor
            height = original.height * factor
        }
        val offsetX = (.5 - anchorUnit.x) * width * if (mirroredX) -1 else 1
        val offsetY = (.5 - anchorUnit.y) * height * if (mirroredY) -1 else 1
        val cx = anchor.x + offsetX * cos(radians) - offsetY * sin(radians)
        val cy = anchor.y + offsetX * sin(radians) + offsetY * cos(radians)
        val updated = original.copy(x = cx - width / 2, y = cy - height / 2, width = width, height = height,
            flipX = original.flipX != mirroredX, flipY = original.flipY != mirroredY)
        return finish(original, updated, rounded, group)
    }

    fun rotate(original: Transform64, start: MovePoint, point: MovePoint,
        shift: Boolean, rounded: Boolean = true, group: Boolean = false): Transform64 {
        val cx = original.x + original.width / 2; val cy = original.y + original.height / 2
        val delta = atan2(point.y - cy, point.x - cx) - atan2(start.y - cy, start.x - cx)
        var rotation = original.rotation + delta * 180 / Math.PI
        if (shift) rotation = MoveGeometry.rounded(rotation / 15) * 15
        return finish(original, original.copy(rotation = rotation), rounded, group)
    }

    /** Crop.swift snappedResizePoint: nudge the pointer before rounding, with the drag's own derivative. */
    fun resizeSnapped(original: Transform64, start: MovePoint, point: MovePoint, index: Int,
        lockRatio: Boolean, shift: Boolean, alt: Boolean, snap: Boolean, targets: MoveSnapTargets,
        pointsPerPixel: Double, screenScale: Double = 1.0, group: Boolean = false): MoveResult {
        fun update(pointer: MovePoint, rounded: Boolean = false) =
            resize(original, start, pointer, index, lockRatio, shift, alt, rounded, group)
        if (!snap || original.rotation % 360 != 0.0) return MoveResult(update(point, rounded = true))
        val handle = units[index]
        val grab = point(original, handle)
        val at = MovePoint(grab.x + point.x - start.x, grab.y + point.y - start.y)
        val tolerance = 10 * screenScale / maxOf(pointsPerPixel, .0001)
        fun edge(box: Transform64, horizontal: Boolean): Double {
            val minimum = if (horizontal) box.x else box.y
            val maximum = minimum + if (horizontal) box.width else box.height
            val pointer = if (horizontal) at.x else at.y
            return if (abs(minimum - pointer) <= abs(maximum - pointer)) minimum else maximum
        }
        fun nearest(value: Double, lines: List<Double>): Double? {
            var best: Double? = null
            for (line in lines) {
                if (abs(line - value) <= tolerance && (best == null || abs(line - value) < abs(best - value))) best = line
            }
            return best
        }
        val draft = update(point)
        var snapX = if (handle.x != .5) nearest(edge(draft, true), targets.xs) else null
        var snapY = if (handle.y != .5) nearest(edge(draft, false), targets.ys) else null
        if (lockRatio != shift && snapX != null && snapY != null) {
            if (abs(snapX - edge(draft, true)) <= abs(snapY - edge(draft, false))) snapY = null else snapX = null
        }
        var pointer = point
        for (axis in 0..1) {
            val horizontal = axis == 0
            val target = if (horizontal) snapX else snapY
            if (target == null) continue
            val before = edge(update(pointer), horizontal)
            val nudged = if (horizontal) pointer.copy(x = pointer.x + 1) else pointer.copy(y = pointer.y + 1)
            val perPixel = edge(update(nudged), horizontal) - before
            if (abs(perPixel) <= .01) continue
            val delta = (target - before) / perPixel
            pointer = if (horizontal) pointer.copy(x = pointer.x + delta) else pointer.copy(y = pointer.y + delta)
        }
        return MoveResult(update(pointer, rounded = true), snapX, snapY)
    }

    private fun finish(original: Transform64, updated: Transform64, rounded: Boolean, group: Boolean): Transform64 {
        // Mac validates the unrounded candidate first, then EditorCanvas rounds the returned placement.
        val valid = if (if (group) MoveGeometry.validGroup(updated) else MoveGeometry.valid(updated)) updated else original
        if (!rounded) return valid
        return valid.copy(x = MoveGeometry.rounded(valid.x), y = MoveGeometry.rounded(valid.y),
            width = maxOf(1.0, MoveGeometry.rounded(valid.width)), height = maxOf(1.0, MoveGeometry.rounded(valid.height)),
            rotation = MoveGeometry.rounded(valid.rotation))
    }
}
