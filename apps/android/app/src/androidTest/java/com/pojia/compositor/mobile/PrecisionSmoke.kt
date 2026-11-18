package com.pojia.compositor.mobile

import android.content.Context
import android.opengl.GLES30
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal fun precisionMetadataSmoke(context: Context): String {
    val file = File.createTempFile("precise-checkpoint-", ".checkpoint", context.cacheDir)
    try {
        NativeEditor(32, addDefaultLayers = false).use { editor ->
            val pixels = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(80, 20, 10, -1)); rewind() }
            val first = editor.importPixels(1, 1, pixels); val second = editor.addGroup()
            check(editor.state().document.activeLayerId == second)
            editor.undo(false)
            val undone = editor.state()
            check(undone.redo && undone.document.activeLayerId == first)
            var notified = -1L
            editor.committed = { notified = it }
            editor.selectLayer(0)
            val selected = editor.state()
            check(selected.redo && selected.undo == undone.undo && selected.revision > undone.revision && notified == selected.revision) {
                "Persisted selection created history, cleared redo, or missed commit notification"
            }
            editor.undo(true)
            check(editor.state().layers.any { it.id == editor.state().document.activeLayerId })
            editor.selectLayer(first)
            val opacity = 0.1234567890123456
            val geometry = Transform64(0.1234567890123, 0.9876543210987, 8.0000000001, 7.9999999999, 1e40, true, false)
            editor.setTransform(first, geometry); editor.setOpacity(first, opacity)
            editor.setVisible(first, false); editor.setVisible(first, true); editor.setBlend(first, 2); editor.setBlend(first, 0)
            editor.renameLayer(first, "Precise 🎨")
            editor.createMask(first, true)
            check(editor.state().layers.first { it.id == first }.maskPlacement == null)
            editor.enableMask(first, false); editor.linkMask(first, false); editor.linkMask(first, true); editor.enableMask(first, true)
            val layer = editor.state().layers.first { it.id == first }
            check(layer.opacity.toRawBits() == opacity.toRawBits() && layer.transform == geometry && layer.maskPlacement == null) {
                "Single-field patches quantized precision or materialized inherited mask placement"
            }
            val placement = Transform64(0.3333333333333, 0.6666666666667, 1.0000000001, 1.9999999999, 360000000090.0, false, true)
            editor.setMaskPlacement(first, placement, sampling = 2)
            editor.enableMask(first, false); editor.linkMask(first, false)
            check(editor.state().layers.first { it.id == first }.let { it.maskPlacement == placement && it.maskSampling == 2 && it.opacity == opacity })
            editor.setSampling(first, 1)
            val dpi = 300.1234567890123
            val guides = listOf(GuideState(UUID.randomUUID().toString().uppercase(Locale.ROOT), true, 12.1234567890123),
                GuideState(UUID.randomUUID().toString().uppercase(Locale.ROOT), false, -0.0000000001234567))
            editor.setResolution(dpi); editor.setGuides(guides)
            editor.selectLayer(second)
            val before = editor.state()
            check(UUID.fromString(before.document.uuid).toString().equals(before.document.uuid, ignoreCase = true))
            check(before.layers.all { UUID.fromString(it.uuid).toString().equals(it.uuid, ignoreCase = true) })
            editor.save(file)
            NativeEditor.load(file).use { restored ->
                val loaded = restored.state()
                check(loaded.layers == before.layers && loaded.document == before.document && loaded.revision == before.revision) {
                    "Checkpoint v3 changed precise values, UUIDs, optional placement, sampling or active selection"
                }
                check(!loaded.undo && !loaded.redo)
                restored.selectLayer(first); restored.deleteLayer(first)
                check(restored.state().document.activeLayerId == 0L || restored.state().layers.any { it.id == restored.state().document.activeLayerId })
                restored.undo(false)
                check(restored.state().layers.any { it.id == restored.state().document.activeLayerId })
            }
            editor.setGuides(emptyList()); editor.undo(false)
            check(editor.state().document.guides == guides) { "Guide edit did not participate in document undo" }
        }
        val directory = File(context.cacheDir, "precision-workspace-${System.nanoTime()}").apply { check(mkdirs()) }
        var workspace: AndroidWorkspace? = null
        try {
            val opened = CountDownLatch(1); val state = AtomicReference<WorkspaceState>()
            workspace = AndroidWorkspace(directory) { value -> state.set(value); if (value.editor != null) opened.countDown() }
            check(opened.await(10, TimeUnit.SECONDS))
            val editor = checkNotNull(state.get().editor)
            val selected = editor.state().layers.first().id
            editor.selectLayer(selected); editor.setResolution(144.1234567890123)
            val expected = editor.state().document
            workspace.flushAsync().get(10, TimeUnit.SECONDS)
            // Only selection changes after a saved content revision must also schedule recovery persistence.
            val next = editor.state().layers.last().id
            editor.selectLayer(next)
            val revision = editor.currentRevision()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (state.get().savedRevision != revision && System.nanoTime() < deadline) Thread.sleep(10)
            check(state.get().savedRevision == revision) { "Selection-only change was omitted from debounced autosave" }
            workspace.closeAsync().get(10, TimeUnit.SECONDS); workspace = null
            val recovery = WorkspaceRecoveryStore(directory)
            NativeEditor.load(recovery.checkpoint(checkNotNull(recovery.read()))).use { restored ->
                check(restored.state().document == expected.copy(activeLayerId = next)) { "Workspace recovery lost persisted metadata/active selection" }
            }
        } finally { workspace?.closeAsync()?.get(10, TimeUnit.SECONDS); directory.deleteRecursively() }
    } finally { file.delete() }
    return "Precision metadata passed: Double fields/UUIDs, atomic appearance+mask flags/nil placement, sampling metadata, active selection/no history/no redo clear/notification, v3 recovery, DPI/guides undo, selection-only autosave"
}

/** Explicit four-quadrant oracle detects large-angle normalization and absolute-Float coordinate loss. */
internal fun precisionGpuSmoke(): String {
    val red = byteArrayOf(-1, 0, 0, -1); val green = byteArrayOf(0, -1, 0, -1)
    val blue = byteArrayOf(0, 0, -1, -1); val yellow = byteArrayOf(-1, -1, 0, -1)
    NativeEditor(30000, addDefaultLayers = false).use { editor ->
        val source = ByteBuffer.allocateDirect(16).apply { put(red); put(green); put(blue); put(yellow); rewind() }
        val id = editor.importPixels(2, 2, source)
        val geometry = Transform64(28000.123456789, 10000.987654321, 2.0, 2.0, 360000000090.0)
        editor.setTransform(id, geometry)
        val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
        renderer.enableLod = false; renderer.viewport = Viewport(geometry.x, geometry.y, 2.0, 2.0)
        renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 64, 64); renderer.onDrawFrame(null)
        check(renderer.lastFrameExactPixelLayers == 0) { "Fractional/rotated mapping must retain the Double geometry path" }
        val actual = ByteBuffer.allocateDirect(64 * 64 * 4)
        GLES30.glReadPixels(0, 0, 64, 64, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
        val expected = listOf(blue, red, yellow, green)
        val cpu = ByteBuffer.allocateDirect(16)
        check(editor.draw(2, 2, geometry.x, geometry.y, 2.0, 2.0, cpu))
        for (index in 0..3) for (channel in 0..3) check(abs((cpu.get(index * 4 + channel).toInt() and 255) - (expected[index][channel].toInt() and 255)) <= 1)
        for (y in 0 until 64) for (x in 0 until 64) for (channel in 0..3) {
            val oracle = expected[(y / 32) * 2 + x / 32][channel].toInt() and 255
            val value = actual.get(((63 - y) * 64 + x) * 4 + channel).toInt() and 255
            check(abs(value - oracle) <= 1) { "Double local geometry/rotation GPU mismatch at $x,$y/$channel: $value != $oracle" }
        }
        check(renderer.fallbackBytes == 0 && GLES30.glGetError() == GLES30.GL_NO_ERROR)
        // A sub-Float-ULP ROI crosses a source pixel boundary at a large document coordinate.
        editor.setTransform(id, geometry.copy(rotation = 0.0))
        renderer.viewport = Viewport(geometry.x + 1 - 0.00001, geometry.y + 0.5, 0.00002, 0.00002)
        renderer.onDrawFrame(null)
        check(renderer.lastFrameExactPixelLayers == 0) { "Sub-Float-ULP viewport cannot enter integer sampling" }
        GLES30.glReadPixels(0, 0, 64, 64, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
        for (y in 0 until 64) for (x in 0 until 64) for (channel in 0..3) {
            val oracle = (if (x < 32) red else green)[channel].toInt() and 255
            val value = actual.get((y * 64 + x) * 4 + channel).toInt() and 255
            check(abs(value - oracle) <= 1) { "Sub-Float-ULP viewport lost source boundary at $x,$y/$channel" }
        }
        editor.close(); renderer.onDrawFrame(null)
    }
    fun stroke(originX: Double, originY: Double): ByteArray = NativeEditor(30000, addDefaultLayers = false).use { editor ->
        val id = editor.importPixels(256, 256, ByteBuffer.allocateDirect(256 * 256 * 4))
        editor.setTransform(id, Transform64(originX, originY, 1.0, 1.0))
        editor.startStroke(id, 0.015f, false)
        editor.samples(doubleArrayOf(originX + 0.501234567, originY + 0.501987654, 1.0, 0.0))
        editor.endStroke(false)
        checkNotNull(editor.captureScene()).use { scene ->
            val pixels = ByteBuffer.allocateDirect(256 * 256 * 4)
            check(scene.layerMetadata(0).tileCount == 1)
            scene.readTile(0, 0, 256 * 4, pixels)
            ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
        }
    }
    val local = stroke(0.0, 0.0); val distant = stroke(28000.123456789, 10000.987654321)
    check(local.any { it != 0.toByte() })
    check(local.indices.all { abs((local[it].toInt() and 255) - (distant[it].toInt() and 255)) <= 1 }) {
        "Double stroke mapping changed after translating the layer to a large coordinate"
    }
    // Hidden retained sources must not silently take nearest GPU sampling when export rejects their sampler.
    NativeEditor(1, addDefaultLayers = false).use { editor ->
        val id = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply { put(red); rewind() })
        editor.setSampling(id, 2); editor.setVisible(id, false)
        checkNotNull(editor.captureScene()).use { scene ->
            check(scene.normalDrawPlan() == null)
            val failure = runCatching { scene.draw(1, 1, Viewport(0.0, 0.0, 1.0, 1.0), ByteBuffer.allocateDirect(4)) }.exceptionOrNull()
            // Unsupported sampling is a typed native status; exception wording is
            // localized and must not be used as the contract.
            check(failure is NativeCommandException && failure.status == 5)
        }
        val failures = ArrayList<String>()
        val renderer = GpuDisplayRenderer(editor) { failures.add(it) }
        renderer.viewport = Viewport(0.0, 0.0, 1.0, 1.0)
        renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1, 1); renderer.onDrawFrame(null)
        // The renderer reports the native status through a user-facing error
        // callback. Its text is localized and may change with the platform;
        // the observable contract here is that fallback is selected and no
        // unsupported source is uploaded to the tile cache.
        check(failures.isNotEmpty() && renderer.cacheBytes == 0 && renderer.fallbackBytes > 0)
        editor.close(); renderer.onDrawFrame(null)
    }
    return "Precision GPU passed: large fractional coordinates/large angle fmod and sub-Float-ULP ROI independent CPU/GPU oracles, translated Double stroke mapping; hidden non-nearest source rejected consistently"
}
