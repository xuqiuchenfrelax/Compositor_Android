package com.pojia.compositor.mobile

import java.nio.ByteBuffer
import java.io.File

internal data class UnmaskedDrawPlan(val layerIndices: IntArray, val effectiveOpacities: DoubleArray, val blendModes: IntArray)
internal data class NormalDrawPlan(val layerIndices: IntArray, val effectiveOpacities: FloatArray)
internal data class SceneRasterView(val phaseX: Int, val phaseY: Int,
    val fillRed: Int, val fillGreen: Int, val fillBlue: Int, val fillAlpha: Int, val hasSampledBase: Boolean) {
    val hasFill: Boolean get() = fillRed != 0 || fillGreen != 0 || fillBlue != 0 || fillAlpha != 0
}
internal data class SceneTileView(val payloadResourceId: Long, val readResourceId: Long,
    val x: Int, val y: Int, val width: Int, val height: Int,
    val sourceX: Int, val sourceY: Int, val validX: Int, val validY: Int,
    val validWidth: Int, val validHeight: Int, val phaseX: Int, val phaseY: Int, val maximumLod: Int) {
    fun level(requested: Int): Int = minOf(requested, maximumLod)
}

/** Immutable snapshot owns native tiles until close; each owner uses it on one GL or IO thread. */
internal class NativeScene(private var handle: Long,
    internal val resourceLedger: ResourceBudgetLedger = ImageIoAdmission.ledger,
    internal val cacheIdentity: Long = 0,
    private var released: (() -> Unit)? = null) : AutoCloseable {
    companion object {
        /** Distort records contain registry tokens; all NativeScene JNI calls require raw pointers. */
        internal fun takeDistortSnapshot(token: Long, ledger: ResourceBudgetLedger = ImageIoAdmission.ledger): NativeScene {
            try {
                // Allocate the managed owner before removing the native registry owner.
                val scene = NativeScene(0, ledger)
                scene.handle = NativeDistortSessionBridge.takeScene(token)
                check(scene.handle != 0L) { "Distort preview scene ownership is unavailable" }
                return scene
            } catch (failure: Throwable) {
                NativeDistortSessionBridge.destroyScene(token)
                throw failure
            }
        }
        internal inline fun <T> withSamplingScratch(ledger: ResourceBudgetLedger, bytes: Long, render: () -> T): T {
            check(bytes >= 0) { "Invalid native sampling scratch allowance" }
            // Nearest must not acquire even a zero-byte lease: ledger owner slots are bounded.
            if (bytes == 0L) return render()
            return ledger.reserve(bytes, "native sampling reduction cache").use { render() }
        }
    }
    private var deferredClose = false
    internal fun setReleaseCallback(callback: () -> Unit) {
        check(released == null) { "Native scene release callback already installed" }
        released = callback
    }
    /** The GL renderer borrows this scene across frames; `use` must not retire it. */
    internal fun deferClose() { deferredClose = true }
    internal fun releaseDeferred() { deferredClose = false; close() }
    fun metadata(): LongArray = info(handle)
    fun generation(): Long = sceneGeneration(handle)
    /** Native occurrence based memory record used to admit a retained preview lease. */
    internal fun memoryInfo(): LongArray = memoryInfo(handle).also {
        check(it.size == 5 && it[0] >= 0 && it[1] >= 0 && it.drop(2).all { value -> value >= 0 }) {
            "Invalid native scene memory record"
        }
    }
    fun layerMetadata(index: Int): SceneLayer = preciseLayer(layer(handle, index), precision(handle, index))
    fun tileMetadata(layer: Int, index: Int): LongArray = tile(handle, layer, index)
    fun rasterViewMetadata(index: Int): SceneRasterView {
        val values = rasterView(handle, index)
        check(values.size == 7 && values[0] in 0L..255L && values[1] in 0L..255L &&
            (2..5).all { values[it] in 0L..255L } && values[6] in 0L..1L) { "Invalid scene raster view" }
        return SceneRasterView(values[0].toInt(), values[1].toInt(), values[2].toInt(), values[3].toInt(),
            values[4].toInt(), values[5].toInt(), values[6] != 0L)
    }
    fun tileViewMetadata(layer: Int, index: Int): SceneTileView {
        val values = tileView(handle, layer, index)
        check(values.size == 15) { "Invalid scene tile view" }
        return parseTileView(values, 0)
    }
    /** One primitive JNI array per layer preserves the individual getter's immutable descriptors. */
    fun tileViewsMetadata(layer: Int): List<SceneTileView> {
        val values = tileViews(handle, layer)
        check(values.size % 15 == 0) { "Invalid scene tile views" }
        return List(values.size / 15) { parseTileView(values, it * 15) }
    }
    private fun parseTileView(values: LongArray, offset: Int): SceneTileView {
        check((2..14).all { values[offset + it] in 0L..Int.MAX_VALUE.toLong() }) { "Invalid scene tile view" }
        check(values[offset + 4] in 1L..256L && values[offset + 5] in 1L..256L && values[offset + 12] in 0L..255L &&
            values[offset + 13] in 0L..255L && values[offset + 14] in 0L..8L) { "Invalid scene tile dimensions or LOD" }
        return SceneTileView(values[offset], values[offset + 1], values[offset + 2].toInt(), values[offset + 3].toInt(),
            values[offset + 4].toInt(), values[offset + 5].toInt(), values[offset + 6].toInt(), values[offset + 7].toInt(),
            values[offset + 8].toInt(), values[offset + 9].toInt(), values[offset + 10].toInt(), values[offset + 11].toInt(),
            values[offset + 12].toInt(), values[offset + 13].toInt(), values[offset + 14].toInt())
    }
    fun layerStructure(index: Int): LongArray = structure(handle, index)
    fun maskPlacement(index: Int): Transform64? {
        val integers = structure(handle, index)
        if (integers[8] == 0L) return null
        val values = placement(handle, index)
        return Transform64(values[0], values[1], values[2], values[3], values[4], integers[10] != 0L, integers[11] != 0L)
    }
    fun unmaskedDrawPlan(): UnmaskedDrawPlan? {
        val entries = unmaskedPlan(handle) ?: return null
        check(entries.size % 3 == 0) { "Invalid unmasked draw plan" }
        check(entries.indices.filter { it % 3 == 2 }.all { entries[it] in 0L..23L })
        return UnmaskedDrawPlan(IntArray(entries.size / 3) { entries[it * 3].toInt() },
            DoubleArray(entries.size / 3) { Double.fromBits(entries[it * 3 + 1]) },
            IntArray(entries.size / 3) { entries[it * 3 + 2].toInt() })
    }
    fun normalDrawPlan(): NormalDrawPlan? {
        val entries = normalPlan(handle) ?: return null
        return NormalDrawPlan(IntArray(entries.size / 2) { entries[it * 2].toInt() },
            FloatArray(entries.size / 2) { Float.fromBits(entries[it * 2 + 1].toInt()) })
    }
    internal fun samplingScratchBytes(width: Int, height: Int, view: Viewport): Long =
        samplingScratch(handle, width, height, view.x, view.y, view.width, view.height)
    fun draw(width: Int, height: Int, view: Viewport, pixels: ByteBuffer) {
        val bytes = samplingScratchBytes(width, height, view)
        withSamplingScratch(resourceLedger, bytes) {
            render(handle, width, height, view.x, view.y, view.width, view.height, pixels)
        }
    }
    fun readTile(layer: Int, index: Int, rowBytes: Int, pixels: ByteBuffer, level: Int = 0) = read(handle, layer, index, level, rowBytes, pixels)
    fun createColorSampler(limitBytes: Long): LongArray = createColorSampler(handle, limitBytes)
    /** Exact original composite histogram; caller keeps this scene on its IO owner until return. */
    internal fun levelsHistogram(beforeLayerId: Long = 0): DoubleArray {
        check(handle != 0L) { "Scene is closed" }
        require(beforeLayerId >= 0L)
        val bytes = levelsHistogramMemory(handle, beforeLayerId)
        check(bytes > 0) { "Invalid Levels histogram allowance" }
        return resourceLedger.reserve(bytes, "Levels original histogram").use {
            levelsHistogramCompute(handle, beforeLayerId, bytes).also { values ->
                check(values.size == 1024 && values.all { it.isFinite() && it >= 0.0 }) { "Invalid Levels histogram" }
            }
        }
    }

    internal fun levelsSample(beforeLayerId: Long, x: Double, y: Double): List<Double>? {
        check(handle != 0L) { "Scene is closed" }
        require(beforeLayerId >= 0L && x.isFinite() && y.isFinite())
        val bytes = levelsSampleMemory(handle, beforeLayerId)
        check(bytes > 0) { "Invalid Levels sampling allowance" }
        return resourceLedger.reserve(bytes, "Levels original sample").use {
            val values = levelsSampleCompute(handle, beforeLayerId, x, y, bytes)
            check(values.size == 5 && values[0] in 0..1 && values.drop(1).all { it in 0..255 }) {
                "Invalid Levels sample"
            }
            if (values[0] == 0) null else {
                check(values[4] > 0) { "Invalid Levels sample alpha" }
                (1..3).map { minOf(1.0, values[it].toDouble() / values[4].toDouble()) }
            }
        }
    }

    internal fun estimateFlattenedTiffMemory(resolution: Double, size: ImageExportSize? = null): Long = flattenedTiffCall {
        check(handle != 0L) { "Scene is closed" }
        NativeTiffCodec.estimateFlattenedNative(handle, resolution, size?.width ?: 0, size?.height ?: 0).also {
            check(it > 0L) { "Invalid flattened TIFF allowance" }
        }
    }

    internal fun writeFlattenedTiff(file: File, resolution: Double, allowance: Long,
        checkCancelled: () -> Unit = {}, size: ImageExportSize? = null) = flattenedTiffCall {
        check(handle != 0L) { "Scene is closed" }
        NativeTiffCodec.exportFlattenedNative(handle, file.absolutePath.toByteArray(Charsets.UTF_8),
            resolution, allowance, size?.width ?: 0, size?.height ?: 0, Runnable(checkCancelled))
    }

    private inline fun <T> flattenedTiffCall(block: () -> T): T = try { block() }
        catch (failure: NativeCommandException) {
            if (failure.status == 5) throw UiNoticeException(UiNotice(UiNoticeKey.TIFF_FLATTENED_UNSUPPORTED),
                "Flattened TIFF requires an RGBA8 scene with supported rendering", failure)
            throw failure
        }

    override fun close() { if (deferredClose) return; if (handle != 0L) {
        destroy(handle); handle = 0; released?.invoke(); released = null
    } }
    private external fun destroy(handle: Long)
    private external fun info(handle: Long): LongArray
    private external fun memoryInfo(handle: Long): LongArray
    private external fun sceneGeneration(handle: Long): Long
    private external fun layer(handle: Long, index: Int): LongArray
    private external fun precision(handle: Long, index: Int): DoubleArray
    private external fun placement(handle: Long, index: Int): DoubleArray
    private external fun tile(handle: Long, layer: Int, index: Int): LongArray
    private external fun tileView(handle: Long, layer: Int, index: Int): LongArray
    private external fun tileViews(handle: Long, layer: Int): LongArray
    private external fun rasterView(handle: Long, layer: Int): LongArray
    private external fun structure(handle: Long, index: Int): LongArray
    private external fun unmaskedPlan(handle: Long): LongArray?
    private external fun normalPlan(handle: Long): LongArray?
    private external fun render(handle: Long, width: Int, height: Int, x: Double, y: Double, w: Double, h: Double, pixels: ByteBuffer)
    private external fun samplingScratch(handle: Long, width: Int, height: Int, x: Double, y: Double, w: Double, h: Double): Long
    private external fun read(handle: Long, layer: Int, index: Int, level: Int, rowBytes: Int, pixels: ByteBuffer)
    private external fun createColorSampler(handle: Long, limitBytes: Long): LongArray
    private external fun levelsHistogramMemory(handle: Long, beforeLayerId: Long): Long
    private external fun levelsHistogramCompute(handle: Long, beforeLayerId: Long, limitBytes: Long): DoubleArray
    private external fun levelsSampleMemory(handle: Long, beforeLayerId: Long): Long
    private external fun levelsSampleCompute(handle: Long, beforeLayerId: Long, x: Double, y: Double, limitBytes: Long): IntArray
}

/**
 * Coordinates a Distort preview snapshot between the UI/Canvas owner and the
 * GL renderer. The lease keeps the native scene alive while a frame is using
 * it, and makes retirement safe when a queued GL event is discarded.
 */
internal class DistortPreviewLease(internal val scene: NativeScene, internal val previewToken: Long = 0,
    internal val baseRevision: Long? = null) {
    private enum class State { CANVAS, RENDERER, RETIRED, CLOSED }
    private var state = State.CANVAS
    private var activeBorrows = 0

    /** Transfer the scene to the renderer. Returns false after retirement. */
    @Synchronized
    internal fun transferToRenderer(): Boolean {
        if (state != State.CANVAS) return false
        scene.deferClose()
        state = State.RENDERER
        return true
    }

    /** Borrow for one GL frame. Retired leases cannot start another frame. */
    @Synchronized
    internal fun acquire(): NativeScene? {
        if (state != State.RENDERER) return null
        activeBorrows++
        return scene
    }

    @Synchronized
    internal fun releaseBorrow() {
        check(activeBorrows > 0) { "Distort preview borrow underflow" }
        activeBorrows--
        if (activeBorrows == 0 && state == State.RETIRED) closeLocked()
    }

    /** Invalidate the lease from Canvas code, including when queueEvent is skipped. */
    @Synchronized
    internal fun retire() {
        when (state) {
            State.CANVAS -> { state = State.CLOSED; scene.releaseDeferred() }
            State.RENDERER -> {
                state = State.RETIRED
                if (activeBorrows == 0) closeLocked()
            }
            State.RETIRED, State.CLOSED -> Unit
        }
    }

    /** Drop the renderer's reference. Idempotent for stale clear events. */
    @Synchronized
    internal fun rendererReleased() {
        when (state) {
            State.RENDERER -> {
                if (activeBorrows == 0) closeLocked() else state = State.RETIRED
            }
            State.RETIRED -> if (activeBorrows == 0) closeLocked()
            State.CANVAS, State.CLOSED -> Unit
        }
    }

    @Synchronized
    private fun closeLocked() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        scene.releaseDeferred()
    }
}
