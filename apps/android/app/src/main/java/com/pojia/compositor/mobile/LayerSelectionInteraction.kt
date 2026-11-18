package com.pojia.compositor.mobile

internal fun displayedLayers(layers: List<LayerState>, collapsedGroups: Set<Long> = emptySet()): List<LayerState> {
    val children = layers.asReversed().groupBy { it.parentId }
    return buildList {
        fun append(parent: Long) {
            children[parent].orEmpty().forEach { layer ->
                add(layer)
                if (layer.isGroup && layer.id !in collapsedGroups) append(layer.id)
            }
        }
        append(0)
    }
}

/** The list's range anchor is independent of its active layer. Native canvas selection
 * replaces the list's tracking state; repeated Shift clicks can shrink their prior range. */
internal class LayerSelectionInteraction {
    data class Selection(val roots: List<Long>, val primary: Long)
    private var anchor: Long? = null
    private var rangeBase: Set<Long>? = null
    private var expected: Set<Long>? = null

    fun select(order: List<Long>, current: List<Long>, primary: Long, clicked: Long,
        shift: Boolean, toggle: Boolean): Selection {
        require(clicked in order)
        val before = current.toSet()
        if (expected != before || anchor !in order) {
            anchor = primary.takeIf { it in before && it in order }
            rangeBase = null
        }
        val next = if (shift) {
            val start = order.indexOf(anchor ?: clicked)
            val end = order.indexOf(clicked)
            val base = if (toggle) before else rangeBase ?: before
            if (rangeBase == null) rangeBase = before
            base + order.subList(minOf(start, end), maxOf(start, end) + 1)
        } else {
            anchor = clicked
            rangeBase = null
            if (toggle) if (clicked in before) before - clicked else before + clicked
            else setOf(clicked)
        }
        expected = next
        val roots = order.filter { it in next }
        return Selection(roots, clicked.takeIf { it in next } ?: roots.firstOrNull() ?: 0)
    }
}
