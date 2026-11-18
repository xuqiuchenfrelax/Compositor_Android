package com.pojia.compositor.mobile

/** Exact optional-field presence and double precision RGB from the native project model. */
internal data class LineShapeMetadata(val fieldFlags: Int, val red: Double, val green: Double,
    val blue: Double, val cornerRadius: Double, val lineWidth: Double, val startX: Double,
    val startY: Double, val endX: Double, val endY: Double, val style: Int, val cap: Int)
