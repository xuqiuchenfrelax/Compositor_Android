package com.pojia.compositor.mobile

/** Retraced bridges join compound selection contours for even-odd fill but are not boundaries. */
internal fun selectionOutlineEdges(points: List<PixelSelectionPoint>): IntArray {
    if (points.size < 2) return IntArray(0)
    // Keep the two-point lasso preview visible before it encloses an area.
    if (points.size == 2) return intArrayOf(0)
    val visible = BooleanArray(points.size) { true }
    val unmatched = HashMap<Pair<PixelSelectionPoint, PixelSelectionPoint>, ArrayDeque<Int>>()
    points.forEachIndexed { index, start ->
        val end = points[(index + 1) % points.size]
        if (start == end) {
            visible[index] = false
        } else {
            val reverse = end to start
            val opposite = unmatched[reverse]
            if (opposite != null && opposite.isNotEmpty()) {
                visible[index] = false
                visible[opposite.removeLast()] = false
                if (opposite.isEmpty()) unmatched.remove(reverse)
            } else unmatched.getOrPut(start to end) { ArrayDeque() }.addLast(index)
        }
    }
    val edges = IntArray(visible.count { it })
    var target = 0
    visible.forEachIndexed { index, included -> if (included) edges[target++] = index }
    return edges
}
