package com.pojia.compositor.mobile

import android.opengl.GLES30
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Relationship scenes use the same retained snapshot for selection, revision and CPU pixels. */
internal fun structureGpuSmoke(): String = NativeEditor(64, addDefaultLayers = false).use { editor ->
    fun solid(red: Int, green: Int, blue: Int, alpha: Int): ByteBuffer =
        ByteBuffer.allocateDirect(64 * 64 * 4).apply {
            repeat(64 * 64) { put(red.toByte()).put(green.toByte()).put(blue.toByte()).put(alpha.toByte()) }; rewind()
        }
    val base = editor.importPixels(64, 64, solid(180, 40, 20, 255))
    val paint = editor.importPixels(64, 64, solid(20, 60, 100, 128))
    val group = editor.addGroup()
    editor.renameLayer(group, "GPU folder 🎨")
    editor.reparentLayer(base, group); editor.reparentLayer(paint, group)
    editor.createMask(group, true); editor.createMask(paint, false)
    editor.clipLayer(paint, base)
    editor.startStroke(paint, 32f, true, mask = true)
    editor.samples(doubleArrayOf(32.0, 32.0, 1.0, 0.0)); editor.endStroke(false)
    val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
    renderer.viewport = Viewport(0f, 0f, 64f, 64f)
    renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 64, 64)
    val actual = ByteBuffer.allocateDirect(64 * 64 * 4)
    val expected = ByteBuffer.allocateDirect(actual.capacity())
    fun verifyPixels() {
        GLES30.glReadPixels(0, 0, 64, 64, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        for (y in 0 until 64) for (x in 0 until 64) for (channel in 0..3) {
            val offset = (y * 64 + x) * 4
            val alpha = expected.get(offset + 3).toInt() and 255
            val background = if ((x / 16 + (63 - y) / 16) % 2 == 0) 0.19f else 0.24f
            val oracle = if (channel == 3) 255 else
                ((expected.get(offset + channel).toInt() and 255) + background * (255 - alpha)).roundToInt()
            val pixel = actual.get(((63 - y) * 64 + x) * 4 + channel).toInt() and 255
            check(abs(pixel - oracle) <= 1) { "Relationship snapshot GPU mismatch at $x,$y channel $channel: $pixel != $oracle" }
        }
    }
    editor.draw(64, 64, 0f, 0f, 64f, 64f, expected)
    renderer.onDrawFrame(null); verifyPixels()
    check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 64 * 64 * 4) { "Relationship scene entered raster tile fast path" }
    renderer.setTextureBudget(1024 * 1024); renderer.onDrawFrame(null); verifyPixels()
    check(renderer.totalGpuBytes <= 1024 * 1024)

    checkNotNull(editor.captureScene()).use { captured ->
        check(captured.metadata()[4] == 1L)
        check(captured.layerStructure(1)[1] == base)
        captured.draw(64, 64, renderer.viewport, expected)
        val retained = ByteArray(expected.capacity()).also { expected.rewind(); expected.get(it); expected.rewind() }
        editor.enableMask(paint, false)
        val updated = ByteBuffer.allocateDirect(expected.capacity())
        editor.draw(64, 64, 0f, 0f, 64f, 64f, updated)
        check((0 until updated.capacity()).any { updated.get(it) != retained[it] }) { "Snapshot race fixture edit did not change pixels" }
        val reference = CpuReferenceRenderer(editor) { throw IllegalStateException(it) }
        reference.viewport = renderer.viewport
        reference.onSurfaceCreated(null, null); reference.configureSurface(64, 64, 1024 * 1024)
        reference.drawScene(captured, null)
        verifyPixels()
        check(captured.metadata()[0] < editor.currentRevision()) { "Retained snapshot revision advanced with document" }
        reference.release()
        renderer.onDrawFrame(null)
        expected.clear(); expected.put(updated); expected.rewind()
        verifyPixels()
    }
    editor.close(); renderer.onDrawFrame(null)
    check(renderer.totalGpuBytes == 0)
    "Relationship GPU passed: group/mask/live-clip explicit bounded CPU fallback±1byte; captured scene pixels/revision survive a later mask edit; next frame updates"
}
