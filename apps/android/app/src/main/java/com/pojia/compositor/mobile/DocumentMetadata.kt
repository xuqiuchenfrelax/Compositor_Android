package com.pojia.compositor.mobile

internal data class Transform64(val x: Double = 0.0, val y: Double = 0.0,
    val width: Double = 1.0, val height: Double = 1.0, val rotation: Double = 0.0,
    val flipX: Boolean = false, val flipY: Boolean = false) {
    fun numbers() = doubleArrayOf(x, y, width, height, rotation)
}

internal data class GuideState(val uuid: String, val horizontal: Boolean, val position: Double)
internal data class DocumentMetadata(val uuid: String = "", val resolution: Double = 72.0,
    val activeLayerId: Long = 0, val guides: List<GuideState> = emptyList())

internal data class SceneLayer(val id: Long, val pixelWidth: Int, val pixelHeight: Int,
    val visible: Boolean, val blend: Int, val sampling: Int, val transform: Transform64,
    val opacity: Double, val tileCount: Int)

internal fun preciseLayer(integers: LongArray, numbers: DoubleArray) = SceneLayer(
    integers[0], integers[1].toInt(), integers[2].toInt(), integers[3] != 0L, integers[4].toInt(),
    integers[5].toInt(), Transform64(numbers[1], numbers[2], numbers[3], numbers[4], numbers[5],
        integers[6] != 0L, integers[7] != 0L), numbers[0], integers[8].toInt())
