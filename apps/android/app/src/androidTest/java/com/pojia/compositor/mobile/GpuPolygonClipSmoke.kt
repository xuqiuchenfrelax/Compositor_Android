package com.pojia.compositor.mobile

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** Frozen allocating renderer algorithm versus reusable scratch, including raw Double payloads. */
internal fun gpuPolygonClipSmoke(): String {
    val clip = GpuPolygonClip()
    var cases = 0; var surviving = 0; var rejected = 0; var maximumVertices = 0
    var held: DoubleArray? = null; var heldBits: LongArray? = null
    fun reference(local: DoubleArray, left: Double, right: Double, top: Double, bottom: Double): DoubleArray? {
        var polygon = listOf(0, 1, 3, 2).map { corner ->
            doubleArrayOf(local[corner * 2], local[corner * 2 + 1], if (corner % 2 == 0) 0.0 else 1.0,
                if (corner < 2) 0.0 else 1.0)
        }
        for ((axis, bound, lower) in listOf(Triple(0, left, true), Triple(0, right, false),
            Triple(1, top, true), Triple(1, bottom, false))) {
            if (polygon.isEmpty()) return null
            val output = ArrayList<DoubleArray>()
            var previous = polygon.last()
            var previousInside = if (lower) previous[axis] >= bound else previous[axis] <= bound
            for (point in polygon) {
                val inside = if (lower) point[axis] >= bound else point[axis] <= bound
                if (inside != previousInside) {
                    val fraction = (bound - previous[axis]) / (point[axis] - previous[axis])
                    output.add(DoubleArray(4) { previous[it] + (point[it] - previous[it]) * fraction }.apply { this[axis] = bound })
                }
                if (inside) output.add(point)
                previous = point; previousInside = inside
            }
            polygon = output
        }
        if (polygon.size < 3) return null
        val result = DoubleArray(polygon.size * 4)
        polygon.forEachIndexed { corner, point -> point.copyInto(result, corner * 4) }
        return result
    }
    fun checkCase(local: DoubleArray, left: Double, right: Double, top: Double, bottom: Double) {
        val expected = reference(local, left, right, top, bottom)
        for (corner in 0..3) clip.setCorner(corner, local[corner * 2], local[corner * 2 + 1])
        val actual = clip.clip(left, right, top, bottom)
        cases++
        check((actual == null) == (expected == null)) { "Clip rejection differs in case $cases" }
        if (actual != null) {
            checkNotNull(expected)
            check(actual.size == expected.size) { "Clip vertex count differs in case $cases" }
            for (field in actual.indices) check(actual[field].toRawBits() == expected[field].toRawBits()) {
                "Clip raw Double bits differ in case $cases field $field: ${actual[field]} != ${expected[field]}"
            }
            surviving++; maximumVertices = maxOf(maximumVertices, actual.size / 4)
        } else rejected++
        // Previously returned polygons must survive arbitrary reuse, including rejected next tiles.
        held?.let { previous ->
            val bits = checkNotNull(heldBits)
            check(previous.indices.all { previous[it].toRawBits() == bits[it] }) { "Scratch overwrote a retained polygon" }
        }
        if (actual != null) { held = actual; heldBits = LongArray(actual.size) { actual[it].toRawBits() } }
    }

    val square = doubleArrayOf(-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, 1.0)
    checkCase(square, -2.0, 2.0, -2.0, 2.0)
    checkCase(square, 2.0, 3.0, 2.0, 3.0)
    checkCase(square, -1.0, 1.0, -1.0, 1.0)
    checkCase(square, 1.0, 1.0, -1.0, 1.0)
    checkCase(square, -1.0, 1.0, 1.0, 1.0)
    checkCase(square, -0.0, 0.0, -0.0, 0.0)
    checkCase(DoubleArray(8), -0.0, 0.0, -0.0, 0.0)
    checkCase(doubleArrayOf(-0.0, -0.0, 0.0, -0.0, -0.0, 0.0, 0.0, 0.0), -1.0, 1.0, -1.0, 1.0)
    checkCase(doubleArrayOf(-2.0, 0.0, 0.0, -2.0, 0.0, 2.0, 2.0, 0.0), -1.5, 1.5, -1.5, 1.5)
    check(maximumVertices == 8) { "Eight-vertex intersection fixture was not exercised" }
    checkCase(doubleArrayOf(-1e150, -1e150, 1e150, -1e150, -1e150, 1e150, 1e150, 1e150),
        -0.0001, 0.0001, -0.0002, 0.0002)
    checkCase(doubleArrayOf(-Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE,
        -Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE), -0.0, 0.0, -0.0, 0.0)

    val random = Random(0x503838434C4950L)
    repeat(24_000) { iteration ->
        val tileX = random.nextInt(1 shl 20); val tileY = random.nextInt(1 shl 20)
        val tileWidth = random.nextInt(256) + 1; val tileHeight = random.nextInt(256) + 1
        val pixelWidth = tileX + tileWidth + random.nextInt(4096) + 1
        val pixelHeight = tileY + tileHeight + random.nextInt(4096) + 1
        val scale = Math.scalb(1.0, random.nextInt(91) - 30)
        val width = (random.nextDouble() + .01) * scale
        val height = if (iteration % 31 == 0) 0.0 else (random.nextDouble() + .01) * scale
        val origin = if (iteration % 7 == 0) Math.scalb(1.0, 52) else random.nextDouble() * scale
        val transformX = origin + (random.nextDouble() - .5) * scale
        val transformY = -origin + (random.nextDouble() - .5) * scale
        val viewportX = origin + (random.nextDouble() - .5) * scale
        val viewportY = -origin + (random.nextDouble() - .5) * scale
        val rotation = if (iteration % 13 == 0) 90.0 * random.nextInt(4) else (random.nextDouble() - .5) * 1440
        val flipX = random.nextBoolean(); val flipY = random.nextBoolean()
        val angle = (rotation % 360.0) * Math.PI / 180
        val cosine = cos(angle); val sine = sin(angle)
        val rightX = tileX.toDouble() + tileWidth; val bottomY = tileY.toDouble() + tileHeight
        val oldPoints = doubleArrayOf(tileX.toDouble(), tileY.toDouble(), rightX, tileY.toDouble(),
            tileX.toDouble(), bottomY, rightX, bottomY)
        val oldLocal = DoubleArray(8); val newLocal = DoubleArray(8)
        val centerX = (transformX - viewportX) + width / 2
        val centerY = (transformY - viewportY) + height / 2
        for (corner in 0..3) {
            val oldSourceX = (oldPoints[corner * 2] / pixelWidth - 0.5) * width * if (flipX) -1 else 1
            val oldSourceY = (oldPoints[corner * 2 + 1] / pixelHeight - 0.5) * height * if (flipY) -1 else 1
            oldLocal[corner * 2] = centerX + cosine * oldSourceX - sine * oldSourceY
            oldLocal[corner * 2 + 1] = centerY + sine * oldSourceX + cosine * oldSourceY
            val pointX = if (corner % 2 == 0) tileX.toDouble() else rightX
            val pointY = if (corner < 2) tileY.toDouble() else bottomY
            val sourceX = (pointX / pixelWidth - 0.5) * width * if (flipX) -1 else 1
            val sourceY = (pointY / pixelHeight - 0.5) * height * if (flipY) -1 else 1
            newLocal[corner * 2] = centerX + cosine * sourceX - sine * sourceY
            newLocal[corner * 2 + 1] = centerY + sine * sourceX + cosine * sourceY
        }
        check(oldLocal.indices.all { oldLocal[it].toRawBits() == newLocal[it].toRawBits() }) {
            "Removing source/local scratch changed transformed coordinates at $iteration"
        }
        val cx = (oldLocal[0] + oldLocal[2] + oldLocal[4] + oldLocal[6]) / 4
        val cy = (oldLocal[1] + oldLocal[3] + oldLocal[5] + oldLocal[7]) / 4
        val roiWidth = if (iteration % 17 == 0) 0.0 else (random.nextDouble() + .00001) * scale
        val roiHeight = if (iteration % 19 == 0) 0.0 else (random.nextDouble() + .00001) * scale
        val left = if (iteration % 4 == 0) cx + 2 * scale else if (iteration % 5 == 0) oldLocal[0] else cx - roiWidth / 2
        val top = if (iteration % 11 == 0) oldLocal[1] else cy - roiHeight / 2
        val right = left + roiWidth; val bottom = top + roiHeight
        checkCase(oldLocal, left, right, top, bottom)
        // Same ROI and source must produce the same bits after a previous clip mutated both buffers.
        if (iteration % 101 == 0) checkCase(newLocal, left, right, top, bottom)
    }
    check(cases >= 24_000 && surviving > 1000 && rejected > 1000 && maximumVertices <= GpuPolygonClip.MAXIMUM_VERTICES) {
        "Insufficient clip coverage: $cases cases, $surviving retained, $rejected rejected, $maximumVertices vertices"
    }
    return "Polygon clip raw-Double reference passed: $cases cases, $surviving retained, $rejected rejected; " +
        "maximum observed vertices=$maximumVertices, scratch=${GpuPolygonClip.MAXIMUM_VERTICES} vertices/buffer, independent output lifetime"
}
