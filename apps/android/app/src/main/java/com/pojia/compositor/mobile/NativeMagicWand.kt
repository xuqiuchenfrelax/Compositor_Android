package com.pojia.compositor.mobile

/** Native Magic Wand tracing over the same rendered document pixels used by the editor. */
internal data class NativeWandLoop(val points: List<PixelSelectionPoint>, val signedArea: Double)

internal object NativeMagicWandBridge {
    init { System.loadLibrary("compositor_android") }

    /** Packed as selectedPixelCount, loopCount, each loop's count, then x/y pairs. */
    fun trace(document: Long, x: Double, y: Double, radius: Int, tolerance: Int,
        contiguous: Boolean): IntArray = traceNative(document, x, y, radius, tolerance, contiguous)

    private external fun traceNative(document: Long, x: Double, y: Double, radius: Int,
        tolerance: Int, contiguous: Boolean): IntArray
}

internal data class NativeWandResult(val selectedPixels: Long, val loops: List<NativeWandLoop>)

