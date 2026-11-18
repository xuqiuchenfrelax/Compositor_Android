package com.pojia.compositor.mobile

import java.io.File

/** Native TIFF stages every page as color-managed premultiplied sRGB16. Source
 * ICC/DPI are inspectable metadata; edited projects store the working sRGB data. */
internal object NativeTiffCodec {
    init { System.loadLibrary("compositor_android") }
    data class PageInfo(val width: Int, val height: Int, val sourceBits: Int,
        val xResolution: Double, val yResolution: Double, val resolutionUnit: Int,
        val sourceOrientation: Int, val sourceIccBytes: Int)
    class Prepared internal constructor(private var token: Long, val pages: List<PageInfo>) : AutoCloseable {
        // Decode reserves a peak envelope. Only the returned native pages remain
        // alive afterward; return the unused envelope before admitting publication.
        internal val retainedBytes: Long = pages.fold(1024L * 1024) { bytes, page ->
            Math.addExact(bytes, Math.addExact(Math.multiplyExact(page.width.toLong() * page.height, 8L),
                Math.addExact(page.sourceIccBytes.toLong(), 1024L)))
        }
        @Synchronized internal fun commit(document: Long, canvasWidth: Int, canvasHeight: Int, fit: Boolean,
            publicationAllowance: Long): LongArray {
            check(token != 0L) { "TIFF import is closed" }
            return commitNative(token, document, canvasWidth, canvasHeight, fit, publicationAllowance)
        }
        @Synchronized internal fun sourceFirstPixel16(page: Int): IntArray {
            check(token != 0L) { "Deep import is closed" }
            return sourceFirstPixelNative(token, page)
        }
        @Synchronized internal fun workingPixel16(page: Int, x: Int, y: Int): IntArray {
            check(token != 0L) { "Deep import is closed" }
            return workingPixelNative(token, page, x, y)
        }
        @Synchronized override fun close() {
            if (token != 0L) { closeNative(token); token = 0L }
        }
    }
    fun prepare(file: File, allowance: Long): Prepared {
        require(allowance > 0)
        return prepared(prepareNative(file.absolutePath.toByteArray(Charsets.UTF_8), allowance))
    }
    fun preparePngPackage(directory: File, allowance: Long): Prepared {
        require(allowance > 0)
        return prepared(preparePngPackageNative(directory.absolutePath.toByteArray(Charsets.UTF_8), allowance))
    }
    private fun prepared(token: Long): Prepared {
        check(token != 0L) { "TIFF decoder returned no image" }
        try {
            val values = metadataNative(token)
            check(values.isNotEmpty() && values.size % 8 == 0)
            val pages = values.asList().chunked(8).map { p ->
                PageInfo(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3], p[4], p[5].toInt(), p[6].toInt(), p[7].toInt())
            }
            return Prepared(token, pages)
        } catch (failure: Throwable) { closeNative(token); throw failure }
    }
    external fun exportSourceLayersNative(snapshot: Long, path: ByteArray, allowance: Long)
    external fun estimateFlattenedNative(scene: Long, resolution: Double, width: Int, height: Int): Long
    external fun exportFlattenedNative(scene: Long, path: ByteArray, resolution: Double,
        allowance: Long, width: Int, height: Int, checkCancelled: Runnable)
    private external fun prepareNative(path: ByteArray, allowance: Long): Long
    private external fun preparePngPackageNative(path: ByteArray, allowance: Long): Long
    private external fun sourceFirstPixelNative(token: Long, page: Int): IntArray
    private external fun workingPixelNative(token: Long, page: Int, x: Int, y: Int): IntArray
    private external fun metadataNative(token: Long): DoubleArray
    private external fun commitNative(token: Long, document: Long, canvasWidth: Int, canvasHeight: Int, fit: Boolean,
        publicationAllowance: Long): LongArray
    private external fun closeNative(token: Long)
}
