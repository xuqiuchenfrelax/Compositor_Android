package com.pojia.compositor.mobile

import java.nio.ByteBuffer

internal data class PixelSelectionRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    fun normalized(): PixelSelectionRect {
        val left = minOf(x, x + width); val top = minOf(y, y + height)
        return PixelSelectionRect(left, top, kotlin.math.abs(width), kotlin.math.abs(height))
    }
}

internal object PixelSelectionShape {
    const val RECTANGLE = 0
    const val ELLIPSE = 1
    const val COMPOSITE = 2
    const val POLYGON = 3
}

internal object PixelSelectionOperation {
    const val REPLACE = 0
    const val ADD = 1
    const val SUBTRACT = 2
    const val INTERSECT = 3
}

/** Selection edge transforms match the desktop Select → Modify commands. */
internal object PixelSelectionAmountOperation {
    const val EXPAND = 0
    const val CONTRACT = 1
    const val FEATHER = 2

    fun title(operation: Int): String = when (operation) {
        EXPAND -> "Expand"
        CONTRACT -> "Contract"
        FEATHER -> "Feather"
        else -> error("Unknown selection amount operation: $operation")
    }

    fun maximum(operation: Int): Int = if (operation == FEATHER) 250 else 500
}

internal object PixelSelectionFillTarget {
    const val PIXELS = 0
    const val MASK = 1
}

/** Session-only document-space bounds and shape. It is absent from EditorState/save files. */
internal data class PixelSelection(
    val instance: Long, val revision: Long, val generation: Long, val epoch: Long,
    val x: Double, val y: Double, val width: Double, val height: Double, val active: Boolean,
    val shape: Int = PixelSelectionShape.RECTANGLE) {
    fun request(out: ByteBuffer, nextX: Double, nextY: Double, nextWidth: Double, nextHeight: Double,
        nextShape: Int = PixelSelectionShape.RECTANGLE) {
        out.putLong(0, instance); out.putLong(8, revision); out.putLong(16, generation); out.putLong(24, epoch)
        out.putDouble(32, nextX); out.putDouble(40, nextY)
        out.putDouble(48, nextWidth); out.putDouble(56, nextHeight)
        out.putInt(64, nextShape); out.putInt(68, 0)
    }
    fun combineRequest(out: ByteBuffer, nextX: Double, nextY: Double, nextWidth: Double, nextHeight: Double,
        nextShape: Int, operation: Int) {
        out.putLong(0, instance); out.putLong(8, revision); out.putLong(16, generation); out.putLong(24, epoch)
        out.putDouble(32, nextX); out.putDouble(40, nextY)
        out.putDouble(48, nextWidth); out.putDouble(56, nextHeight)
        out.putInt(64, nextShape); out.putInt(68, operation); out.putInt(72, 0); out.putInt(76, 0)
    }
    companion object {
        fun read(record: ByteBuffer): PixelSelection {
            check(record.capacity() == 72 && record.getInt(64) in 0..1 && record.getInt(68) in 0..3)
            return PixelSelection(record.getLong(0), record.getLong(8), record.getLong(16), record.getLong(24),
                record.getDouble(32), record.getDouble(40), record.getDouble(48), record.getDouble(56),
                record.getInt(64) != 0, record.getInt(68))
        }
    }
}

internal data class PixelSelectionRegion(
    val x: Double, val y: Double, val width: Double, val height: Double,
    val shape: Int, val operation: Int,
    val polygon: List<PixelSelectionPoint> = emptyList())

internal data class PixelSelectionPoint(val x: Double, val y: Double)

internal data class PixelSelectionRegions(
    val instance: Long, val revision: Long, val generation: Long, val epoch: Long,
    val active: Boolean, val regions: List<PixelSelectionRegion>) {
    companion object {
        fun read(header: ByteBuffer, records: ByteBuffer): PixelSelectionRegions {
            check(header.capacity() == 40 && records.capacity() % 40 == 0)
            val count = header.getInt(36)
            check(count >= 0 && count <= records.capacity() / 40)
            val regions = ArrayList<PixelSelectionRegion>(count)
            repeat(count) { offset ->
                val base = offset * 40
                regions += PixelSelectionRegion(records.getDouble(base), records.getDouble(base + 8),
                    records.getDouble(base + 16), records.getDouble(base + 24),
                    records.getInt(base + 32), records.getInt(base + 36))
            }
            return PixelSelectionRegions(header.getLong(0), header.getLong(8), header.getLong(16),
                header.getLong(24), header.getInt(32) != 0, regions)
        }
    }
}

internal object NativePixelSelectionBridge {
    init { System.loadLibrary("compositor_android") }
    fun read(document: Long, output: ByteBuffer): Int = readNative(document, output)
    fun set(document: Long, request: ByteBuffer, output: ByteBuffer): Int =
        setNative(document, request, output)
    fun combine(document: Long, request: ByteBuffer, output: ByteBuffer): Int =
        combineNative(document, request, output)
    fun combinePolygon(document: Long, request: ByteBuffer, points: ByteBuffer, pointCount: Int,
        output: ByteBuffer): Int = combinePolygonNative(document, request, points, pointCount, output)
    fun combinePath(document: Long, request: ByteBuffer, points: ByteBuffer, pointCount: Int,
        loopCounts: ByteBuffer, loopCount: Int, output: ByteBuffer): Int =
        combinePathNative(document, request, points, pointCount, loopCounts, loopCount, output)
    fun readRegions(document: Long, header: ByteBuffer, regions: ByteBuffer): Int =
        readRegionsNative(document, header, regions)
    fun readPolygon(document: Long, regionIndex: Int, points: ByteBuffer, countOutput: ByteBuffer): Int =
        readPolygonNative(document, regionIndex, points, countOutput)
    fun fill(document: Long, request: ByteBuffer): Int = fillNative(document, request)
    fun fillBounded(document: Long, request: ByteBuffer, transientLimitBytes: Long): Int =
        fillBoundedNative(document, request, transientLimitBytes)
    fun modify(document: Long, request: ByteBuffer, output: ByteBuffer): Int =
        modifyNative(document, request, output)
    private external fun readNative(document: Long, output: ByteBuffer): Int
    private external fun setNative(document: Long, request: ByteBuffer, output: ByteBuffer): Int
    private external fun combineNative(document: Long, request: ByteBuffer, output: ByteBuffer): Int
    private external fun combinePolygonNative(document: Long, request: ByteBuffer, points: ByteBuffer,
        pointCount: Int, output: ByteBuffer): Int
    private external fun combinePathNative(document: Long, request: ByteBuffer, points: ByteBuffer,
        pointCount: Int, loopCounts: ByteBuffer, loopCount: Int, output: ByteBuffer): Int
    private external fun readRegionsNative(document: Long, header: ByteBuffer, regions: ByteBuffer): Int
    private external fun readPolygonNative(document: Long, regionIndex: Int, points: ByteBuffer,
        countOutput: ByteBuffer): Int
    private external fun fillNative(document: Long, request: ByteBuffer): Int
    private external fun fillBoundedNative(document: Long, request: ByteBuffer, transientLimitBytes: Long): Int
    private external fun modifyNative(document: Long, request: ByteBuffer, output: ByteBuffer): Int
}
