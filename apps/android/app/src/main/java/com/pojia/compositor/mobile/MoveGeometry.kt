package com.pojia.compositor.mobile

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class MovePoint(val x: Double, val y: Double)
internal data class MoveBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)
internal data class MoveSnapTargets(val xs: List<Double>, val ys: List<Double>)
internal data class MoveResult(val box: Transform64, val snapX: Double? = null, val snapY: Double? = null)
internal data class MoveSelectionGeometry(val target: TransformTarget, val box: Transform64, val members: List<Long>)
internal enum class MoveField { X, Y, WIDTH, HEIGHT, ROTATION }

/** Mac LayerTransform and TransformSnap rules, in document pixels until display conversion. */
internal object MoveGeometry {
    fun rounded(value: Double): Double = if (value < 0) ceil(value - .5) else floor(value + .5)
    fun valid(box: Transform64): Boolean = box.numbers().all { it.isFinite() } &&
        abs(box.x) <= 1_000_000 && abs(box.y) <= 1_000_000 &&
        box.width in 1.0..300_000.0 && box.height in 1.0..300_000.0

    fun validGroup(box: Transform64): Boolean = box.numbers().all { it.isFinite() } &&
        box.width > 0 && box.height > 0

    fun contains(box: Transform64, point: MovePoint): Boolean {
        val angle = (box.rotation % 360) * Math.PI / 180
        val x = point.x - box.x - box.width / 2
        val y = point.y - box.y - box.height / 2
        return abs(x * cos(angle) + y * sin(angle)) <= box.width / 2 &&
            abs(-x * sin(angle) + y * cos(angle)) <= box.height / 2
    }

    fun bounds(box: Transform64): MoveBounds {
        val angle = (box.rotation % 360) * Math.PI / 180
        val halfWidth = (abs(cos(angle)) * box.width + abs(sin(angle)) * box.height) / 2
        val halfHeight = (abs(sin(angle)) * box.width + abs(cos(angle)) * box.height) / 2
        val cx = box.x + box.width / 2; val cy = box.y + box.height / 2
        return MoveBounds(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
    }

    fun visible(layer: LayerState, layers: List<LayerState>): Boolean {
        val byId = layers.associateBy { it.id }
        var current = layer
        val seen = HashSet<Long>()
        var depth = 0
        while (true) {
            if (!current.visible || !seen.add(current.id) || depth > 64) return false
            if (current.parentId == 0L) return true
            current = byId[current.parentId]?.takeIf { it.isGroup } ?: return false
            depth++
        }
    }

    fun target(layer: LayerState?, layers: List<LayerState>, mask: Boolean): TransformTarget? {
        if (layer == null || !visible(layer, layers)) return null
        if (layer.isGroup) return groupGeometry(layers, listOf(layer.id))?.target
        if (!layer.assetPresent) return null
        return if (mask && layer.hasMask && !layer.maskLinked) TransformTarget.UNLINKED_MASK else TransformTarget.LAYER
    }

    fun box(layer: LayerState, target: TransformTarget): Transform64 =
        if (target == TransformTarget.UNLINKED_MASK) layer.maskPlacement ?: layer.transform else layer.transform

    fun selection(state: EditorState, roots: Collection<Long>, primary: Long, mask: Boolean): MoveSelectionGeometry? {
        if (primary == 0L || primary !in roots) return null
        val layer = state.layers.firstOrNull { it.id == primary } ?: return null
        // Mac classifies the entire selection before considering an independent mask.
        if (roots.size > 1 || layer.isGroup) return groupGeometry(state.layers, roots)
        val target = target(layer, state.layers, mask) ?: return null
        return MoveSelectionGeometry(target, box(layer, target), listOf(layer.id))
    }

    fun selectionBox(state: EditorState, roots: Collection<Long>): Transform64? = groupGeometry(state.layers, roots)?.box

    private fun groupGeometry(layers: List<LayerState>, roots: Collection<Long>): MoveSelectionGeometry? {
        val selected = roots.toHashSet()
        val children = layers.groupBy { it.parentId }
        val included = HashSet<Long>()
        val visited = HashSet<Long>()
        fun visit(parent: Long, depth: Int, selectedDistance: Int) {
            if (depth > 64) return
            children[parent].orEmpty().forEach { layer ->
                if (!visited.add(layer.id) || !layer.visible) return@forEach
                val distance = if (layer.id in selected) 0 else selectedDistance + 1
                if (layer.isGroup) visit(layer.id, depth + 1, distance)
                else if (layer.assetPresent && distance < 64) included.add(layer.id)
            }
        }
        visit(0, 0, 64)
        var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
        val members = ArrayList<Long>(included.size)
        layers.forEach { layer ->
            if (layer.id in included) {
                members.add(layer.id)
                val box = bounds(layer.transform)
                left = minOf(left, box.left); top = minOf(top, box.top)
                right = maxOf(right, box.right); bottom = maxOf(bottom, box.bottom)
            }
        }
        if (!left.isFinite()) return null
        return MoveSelectionGeometry(TransformTarget.GROUP,
            Transform64(left, top, maxOf(1.0, right-left), maxOf(1.0, bottom-top), 0.0, false, false), members)
    }

    fun targets(state: EditorState, moving: Long, width: Int, height: Int,
        roots: Collection<Long> = listOf(moving)): MoveSnapTargets {
        val xs = mutableListOf(0.0, width.toDouble(), width / 2.0)
        val ys = mutableListOf(0.0, height.toDouble(), height / 2.0)
        // Build once at gesture start, in visible leaf drawing order like Mac LayerOrder.
        val selected = roots.toHashSet()
        val byId = state.layers.associateBy { it.id }
        fun movingMember(layer: LayerState): Boolean {
            var current: LayerState? = layer
            repeat(64) {
                val value = current ?: return false
                if (value.id in selected) return true
                current = byId[value.parentId]
            }
            return false
        }
        val children = state.layers.groupBy { it.parentId }
        fun visit(parent: Long, depth: Int) {
            if (depth > 64) return
            children[parent].orEmpty().forEach { layer ->
                if (!layer.visible) return@forEach
                if (!layer.isGroup && !movingMember(layer) && layer.assetPresent) {
                    val b = bounds(layer.transform)
                    xs.addAll(listOf(rounded(b.left), rounded((b.left + b.right) / 2), rounded(b.right)))
                    ys.addAll(listOf(rounded(b.top), rounded((b.top + b.bottom) / 2), rounded(b.bottom)))
                }
                if (layer.isGroup) visit(layer.id, depth + 1)
            }
        }
        visit(0, 0)
        // Grid and guides enter this list only when their display settings exist and show them.
        return MoveSnapTargets(xs, ys)
    }

    fun drag(original: Transform64, down: MovePoint, point: MovePoint, shift: Boolean,
        snap: Boolean, targets: MoveSnapTargets, pointsPerPixel: Double): MoveResult {
        var dx = point.x - down.x; var dy = point.y - down.y
        if (shift) { if (abs(dx) >= abs(dy)) dy = 0.0 else dx = 0.0 }
        val draft = original.copy(x = rounded(original.x + dx), y = rounded(original.y + dy),
            width = maxOf(1.0, rounded(original.width)), height = maxOf(1.0, rounded(original.height)),
            rotation = rounded(original.rotation))
        if (!snap) return MoveResult(draft)
        val b = bounds(draft)
        val tolerance = 10 / maxOf(pointsPerPixel, .0001)
        fun nearest(values: List<Double>, lines: List<Double>): Pair<Double, Double?> {
            var best = 0.0; var target: Double? = null
            for (value in values) for (line in lines) {
                val move = line - value
                if (abs(move) > tolerance || (target != null && abs(best) <= abs(move))) continue
                best = move; target = line
            }
            return best to target
        }
        val x = nearest(listOf(b.left, (b.left + b.right) / 2, b.right), targets.xs)
        val y = nearest(listOf(b.top, (b.top + b.bottom) / 2, b.bottom), targets.ys)
        return MoveResult(draft.copy(x = draft.x + x.first, y = draft.y + y.first), x.second, y.second)
    }

    /** Typed values do not use the scrubbing range or whole-pixel rounding. */
    fun field(box: Transform64, field: MoveField, value: Double, group: Boolean = false): Transform64? {
        if (!value.isFinite()) return null
        val updated = when (field) {
            MoveField.X -> box.copy(x = value)
            MoveField.Y -> box.copy(y = value)
            MoveField.WIDTH -> box.copy(width = value)
            MoveField.HEIGHT -> box.copy(height = value)
            MoveField.ROTATION -> box.copy(rotation = value % 360)
        }
        return updated.takeIf { if (group) validGroup(it) else valid(it) }
    }
}
