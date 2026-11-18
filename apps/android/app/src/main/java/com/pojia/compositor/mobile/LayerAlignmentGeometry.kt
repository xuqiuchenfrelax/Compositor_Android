package com.pojia.compositor.mobile

import kotlin.math.cos
import kotlin.math.sin

internal enum class LayerAlignment { LEFT, HORIZONTAL_CENTER, RIGHT, TOP, VERTICAL_CENTER, BOTTOM }
internal enum class LayerDistribution { HORIZONTAL_CENTERS, VERTICAL_CENTERS, HORIZONTAL_SPACING, VERTICAL_SPACING }

internal data class AlignmentItem(val members: List<LayerState>, val box: MoveBounds)

/** PC LayerAlign.swift semantics: visible leaf layers, selected folders as one item, integer offsets. */
internal object LayerAlignmentGeometry {
    fun items(state: EditorState, selectedRoots: Collection<Long>): List<AlignmentItem> {
        val selected = selectedRoots.toHashSet()
        if (selected.isEmpty()) return emptyList()
        val byId = state.layers.associateBy { it.id }
        fun selectedAncestor(id: Long): Long? {
            var current = byId[id]?.parentId ?: return null
            repeat(64) {
                if (current == 0L) return null
                if (current in selected) return current
                current = byId[current]?.parentId ?: 0L
            }
            return null
        }
        val grouped = linkedMapOf<Long, MutableList<LayerState>>()
        fun visible(layer: LayerState): Boolean {
            var current = layer
            repeat(65) {
                if (!current.visible) return false
                if (current.parentId == 0L) return true
                current = byId[current.parentId]?.takeIf { it.isGroup } ?: return false
            }
            return false
        }
        state.layers.filter { it.assetPresent && !it.isGroup && visible(it) }.forEach { layer ->
            var owner = layer.id.takeIf { it in selected }
            var ancestor = selectedAncestor(layer.id)
            while (ancestor != null) {
                owner = ancestor
                ancestor = selectedAncestor(ancestor)
            }
            if (owner != null) grouped.getOrPut(owner) { mutableListOf() }.add(layer)
        }
        return grouped.values.mapNotNull { members ->
            val boxes = members.map { uprightBounds(it.transform) }
            if (boxes.isEmpty()) null else AlignmentItem(members, boxes.reduce(::union))
        }
    }

    fun alignmentOffsets(items: List<AlignmentItem>, alignment: LayerAlignment, canvasWidth: Int, canvasHeight: Int, selection: MoveBounds? = null): List<MovePoint> {
        if (items.isEmpty()) return emptyList()
        val target = selection ?: if (items.size == 1) MoveBounds(0.0, 0.0, canvasWidth.toDouble(), canvasHeight.toDouble())
        else items.map { it.box }.reduce(::union)
        return items.map { item ->
            val b = item.box
            val dx = when (alignment) {
                LayerAlignment.LEFT -> target.left - b.left
                LayerAlignment.HORIZONTAL_CENTER -> centerX(target) - centerX(b)
                LayerAlignment.RIGHT -> target.right - b.right
                else -> 0.0
            }
            val dy = when (alignment) {
                LayerAlignment.TOP -> target.top - b.top
                LayerAlignment.VERTICAL_CENTER -> centerY(target) - centerY(b)
                LayerAlignment.BOTTOM -> target.bottom - b.bottom
                else -> 0.0
            }
            MovePoint(MoveGeometry.rounded(dx), MoveGeometry.rounded(dy))
        }
    }

    fun distributionOffsets(items: List<AlignmentItem>, distribution: LayerDistribution): List<MovePoint> {
        if (items.size <= 2) return items.map { MovePoint(0.0, 0.0) }
        val horizontal = distribution == LayerDistribution.HORIZONTAL_CENTERS || distribution == LayerDistribution.HORIZONTAL_SPACING
        val order = items.indices.sortedBy { index ->
            val b = items[index].box
            if (horizontal) centerX(b) else centerY(b)
        }
        val first = items[order.first()].box; val last = items[order.last()].box
        val result = MutableList(items.size) { MovePoint(0.0, 0.0) }
        val steps = (order.size - 1).toDouble()
        if (distribution == LayerDistribution.HORIZONTAL_CENTERS || distribution == LayerDistribution.VERTICAL_CENTERS) {
            val from = if (horizontal) centerX(first) else centerY(first)
            val to = if (horizontal) centerX(last) else centerY(last)
            order.forEachIndexed { rank, index ->
                val b = items[index].box
                val center = if (horizontal) centerX(b) else centerY(b)
                val delta = MoveGeometry.rounded(from + (to - from) * rank / steps - center)
                result[index] = if (horizontal) MovePoint(delta, 0.0) else MovePoint(0.0, delta)
            }
        } else {
            val start = if (horizontal) first.left else first.top
            val span = (if (horizontal) last.right else last.bottom) - start
            val total = order.sumOf { index -> val b = items[index].box; if (horizontal) b.right - b.left else b.bottom - b.top }
            val gap = (span - total) / steps
            var position = start
            order.forEach { index ->
                val b = items[index].box
                val edge = if (horizontal) b.left else b.top
                val delta = MoveGeometry.rounded(position - edge)
                result[index] = if (horizontal) MovePoint(delta, 0.0) else MovePoint(0.0, delta)
                position += (if (horizontal) b.right - b.left else b.bottom - b.top) + gap
            }
        }
        return result
    }

    fun changes(state: EditorState, selectedRoots: Collection<Long>, alignment: LayerAlignment?, distribution: LayerDistribution?, canvasWidth: Int, canvasHeight: Int, selection: MoveBounds? = null): List<LayerTransformChange> {
        val items = items(state, selectedRoots)
        val offsets = when {
            alignment != null -> alignmentOffsets(items, alignment, canvasWidth, canvasHeight, selection)
            distribution != null -> distributionOffsets(items, distribution)
            else -> return emptyList()
        }
        return items.zip(offsets).flatMap { (item, offset) ->
            if (offset.x == 0.0 && offset.y == 0.0) emptyList()
            else item.members.map { layer -> LayerTransformChange(layer.id, layer.transform.copy(x = layer.transform.x + offset.x, y = layer.transform.y + offset.y)) }
        }
    }

    private fun centerX(box: MoveBounds) = box.left + (box.right - box.left) / 2.0
    private fun centerY(box: MoveBounds) = box.top + (box.bottom - box.top) / 2.0

    private fun uprightBounds(transform: Transform64): MoveBounds {
        val radians = (transform.rotation % 360) * Math.PI / 180
        val centerX = transform.x + transform.width / 2
        val centerY = transform.y + transform.height / 2
        val points = listOf(-.5 to -.5, .5 to -.5, .5 to .5, -.5 to .5).map { (u, v) ->
            val x = u * transform.width; val y = v * transform.height
            MovePoint(centerX + x * cos(radians) - y * sin(radians),
                centerY + x * sin(radians) + y * cos(radians))
        }
        return MoveBounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    private fun union(a: MoveBounds, b: MoveBounds) = MoveBounds(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
}
