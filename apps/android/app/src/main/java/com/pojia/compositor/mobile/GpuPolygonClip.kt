package com.pojia.compositor.mobile

/** GL-thread scratch; the returned polygon owns its coordinates independently of the next tile. */
internal class GpuPolygonClip {
    companion object {
        // An exact convex quad clipped by four half-planes has at most eight vertices.
        // Allow the general 4 * 2^4 bound too, without assuming floating-point convexity.
        const val MAXIMUM_VERTICES = 64
    }
    private val first = DoubleArray(MAXIMUM_VERTICES * 4)
    private val second = DoubleArray(MAXIMUM_VERTICES * 4)

    /** Input corner order is top-left, top-right, bottom-left, bottom-right. */
    fun setCorner(corner: Int, x: Double, y: Double) {
        val offset = (if (corner < 2) corner else 5 - corner) * 4
        first[offset] = x; first[offset + 1] = y
        first[offset + 2] = if (corner % 2 == 0) 0.0 else 1.0
        first[offset + 3] = if (corner < 2) 0.0 else 1.0
    }

    fun clip(left: Double, right: Double, top: Double, bottom: Double): DoubleArray? {
        var input = first; var output = second; var count = 4
        for (edge in 0..3) {
            if (count == 0) return null
            val axis = edge / 2
            val bound = when (edge) { 0 -> left; 1 -> right; 2 -> top; else -> bottom }
            val lower = edge % 2 == 0
            var written = 0
            var previous = (count - 1) * 4
            var previousInside = if (lower) input[previous + axis] >= bound else input[previous + axis] <= bound
            for (index in 0 until count) {
                val point = index * 4
                val inside = if (lower) input[point + axis] >= bound else input[point + axis] <= bound
                if (inside != previousInside) {
                    val fraction = (bound - input[previous + axis]) / (input[point + axis] - input[previous + axis])
                    for (field in 0..3)
                        output[written + field] = input[previous + field] +
                            (input[point + field] - input[previous + field]) * fraction
                    output[written + axis] = bound
                    written += 4
                }
                if (inside) {
                    for (field in 0..3) output[written + field] = input[point + field]
                    written += 4
                }
                previous = point; previousInside = inside
            }
            count = written / 4
            val oldInput = input; input = output; output = oldInput
        }
        return if (count < 3) null else input.copyOf(count * 4)
    }
}
