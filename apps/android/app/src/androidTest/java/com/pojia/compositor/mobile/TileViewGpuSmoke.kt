package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.opengl.GLES30
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Host-authored v4 views reach existing checkpoint/JNI/GLES paths without a special native test entry. */
internal fun tileViewGpuSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit): String {
    val context = instrumentation.targetContext
    val assets = instrumentation.context.assets
    val specification = assets.open("tile-views/phase-fixtures.json").use { JSONObject(it.reader().readText()) }
    val width = specification.getInt("width"); val height = specification.getInt("height")
    val scratch = File(context.cacheDir.canonicalFile, "tile-view-gpu-${System.nanoTime()}").apply { check(mkdirs()) }
    val reports = ArrayList<String>()
    try {
        for (stem in listOf("phase-transparent", "phase-fill")) {
            val file = File(scratch, "$stem.checkpoint")
            assets.open("tile-views/$stem.checkpoint").use { input -> file.outputStream().use { input.copyTo(it) } }
            val expected = assets.open("tile-views/$stem.rgba").use { it.readBytes() }
            check(expected.size == width * height * 4)
            NativeEditor.load(file).use { editor ->
                check(editor.canvasWidth == width && editor.canvasHeight == height && editor.state().layers.size == 1)
                val layer = editor.state().layers.single()
                check(layer.pixelWidth == width && layer.pixelHeight == height && layer.transform == Transform64(0.0, 0.0,
                    width.toDouble(), height.toDouble(), 0.0, false, false) && layer.opacity == 1.0)
                val retained = checkNotNull(editor.captureScene())
                try {
                    fun descriptors(scene: NativeScene): List<SceneTileView> {
                        val surface = scene.rasterViewMetadata(0)
                        check(surface.phaseX == specification.getInt("phase_x") && surface.phaseY == specification.getInt("phase_y"))
                        check(surface.hasFill == (stem == "phase-fill"))
                        if (surface.hasFill) {
                            val fill = specification.getJSONArray("fill")
                            check(listOf(surface.fillRed, surface.fillGreen, surface.fillBlue, surface.fillAlpha) == (0..3).map { fill.getInt(it) })
                        }
                        check(scene.layerMetadata(0).tileCount == specification.getInt("tile_count"))
                        val views = (0 until scene.layerMetadata(0).tileCount).map { scene.tileViewMetadata(0, it) }
                        check(scene.tileViewsMetadata(0) == views) { "Bulk phase descriptors differ from individual getters" }
                        check(views.all { it.phaseX == surface.phaseX && it.phaseY == surface.phaseY && it.maximumLod == 0 })
                        check(views.any { it.sourceX != 0 && it.sourceY != 0 }) { "Fixture contains no actual offset view" }
                        check(views.map { it.readResourceId }.toSet().size == views.size)
                        for ((index, view) in views.withIndex()) {
                            val legacy = scene.tileMetadata(0, index)
                            check(legacy.contentEquals(longArrayOf(view.readResourceId, view.x.toLong(), view.y.toLong(),
                                view.width.toLong(), view.height.toLong())))
                            val stride = view.width * 4 + 12
                            val pixels = ByteBuffer.allocateDirect(stride * view.height).apply { repeat(capacity()) { put(0x5a) }; rewind() }
                            scene.readTile(0, index, stride, pixels)
                            for (y in 0 until view.height) {
                                for (x in 0 until view.width) for (channel in 0..3) check(pixels.get(y * stride + x * 4 + channel) ==
                                    expected[((view.y + y) * width + view.x + x) * 4 + channel]) { "Offset tile window differs from independent source coordinates" }
                                for (padding in view.width * 4 until stride) check(pixels.get(y * stride + padding) == 0x5a.toByte())
                            }
                            val beforeUnsupportedRead = ByteArray(pixels.capacity()) { pixels.get(it) }
                            try { scene.readTile(0, index, stride, pixels, level = 1); error("Offset tile accepted an unsafe LOD") }
                            catch (failure: IllegalStateException) { check(failure.message?.contains("not supported") == true) }
                            check(beforeUnsupportedRead.indices.all { pixels.get(it) == beforeUnsupportedRead[it] }) {
                                "Unsupported LOD modified the destination buffer"
                            }
                        }
                        return views
                    }
                    val initialViews = descriptors(retained)
                    val cpu = ByteBuffer.allocateDirect(expected.size)
                    retained.draw(width, height, Viewport(0.0, 0.0, width.toDouble(), height.toDouble()), cpu)
                    check(ByteArray(cpu.capacity()).also { cpu.rewind(); cpu.get(it) }.contentEquals(expected))
                    val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
                    renderer.onSurfaceCreated(null, null)
                    var maximumDisplayDifference = 0
                    fun verify(view: Viewport, outputWidth: Int, outputHeight: Int, fallback: Boolean, opacity: Double = 1.0) {
                        renderer.viewport = view; renderer.onSurfaceChanged(null, outputWidth, outputHeight); renderer.onDrawFrame(null)
                        val pixels = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
                        GLES30.glReadPixels(0, 0, outputWidth, outputHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                        check((renderer.fallbackBytes > 0) == fallback && renderer.totalGpuBytes <= renderer.textureBudgetBytes)
                        val framebuffer = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
                        check(framebuffer[0] == 0 && !GLES30.glIsEnabled(GLES30.GL_BLEND))
                        for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
                            val sx = floor(view.x + (x + 0.5) * view.width / outputWidth).toInt()
                            val sy = floor(view.y + (y + 0.5) * view.height / outputHeight).toInt()
                            val source = if (sx in 0 until width && sy in 0 until height)
                                IntArray(4) { ((expected[(sy * width + sx) * 4 + it].toInt() and 255) * opacity).roundToInt() }
                                else IntArray(4)
                            val background = if ((x / 16 + (outputHeight - 1 - y) / 16) % 2 == 0) 0.19 else 0.24
                            for (channel in 0..3) {
                                val oracle = if (channel == 3) 255 else (source[channel] + background * (255 - source[3])).roundToInt()
                                val actual = pixels.get(((outputHeight - 1 - y) * outputWidth + x) * 4 + channel).toInt() and 255
                                maximumDisplayDifference = maxOf(maximumDisplayDifference, abs(actual - oracle))
                                check(abs(actual - oracle) <= 1) { "$stem view/checker mismatch $x,$y/$channel: $actual != $oracle" }
                            }
                        }
                    }
                    val full = Viewport(0.0, 0.0, width.toDouble(), height.toDouble())
                    val halfWidth = (width + 1) / 2; val halfHeight = (height + 1) / 2
                    val half = Viewport(-0.5, -0.5, halfWidth * 2.0, halfHeight * 2.0)
                    val fillFallback = stem == "phase-fill"
                    verify(full, width, height, fillFallback)
                    val uploads = renderer.uploadCount; val allocations = renderer.textureAllocations
                    verify(half, halfWidth, halfHeight, fillFallback)
                    repeat(3) { verify(full, width, height, fillFallback) }
                    check(renderer.uploadCount == uploads && renderer.textureAllocations == allocations)
                    check((1..8).all { renderer.lodUploads[it] == 0L }) { "Phase views reached an unsupported mip level" }
                    checkNotNull(editor.captureScene()).use { check(descriptors(it) == initialViews) }
                    editor.setOpacity(layer.id, 0.5)
                    verify(full, width, height, fillFallback, 0.5)
                    check(renderer.uploadCount == uploads)
                    check(descriptors(retained) == initialViews)
                    editor.undo(false); verify(full, width, height, fillFallback)
                    renderer.setTextureBudget(1024 * 1024)
                    verify(full, width, height, true)
                    check(renderer.cacheBytes == 0) { "LOD0 budget fallback retained an oversized texture page" }
                    renderer.setTextureBudget(64 * 1024 * 1024)
                    verify(full, width, height, fillFallback)
                    recreateContext(); renderer.onSurfaceCreated(null, null)
                    verify(full, width, height, fillFallback)
                    val restored = File(scratch, "$stem-restored.checkpoint")
                    editor.save(restored)
                    NativeEditor.load(restored).use { recovered -> checkNotNull(recovered.captureScene()).use { scene ->
                        descriptors(scene)
                        scene.draw(width, height, full, cpu)
                        check(ByteArray(cpu.capacity()).also { cpu.rewind(); cpu.get(it) }.contentEquals(expected))
                    } }
                    editor.close(); renderer.onDrawFrame(null)
                    check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 0)
                    check(descriptors(retained) == initialViews) // Old view payload/read windows outlive the editor.
                    reports.add("$stem phase=${specification.getInt("phase_x")},${specification.getInt("phase_y")} tiles=${initialViews.size} independent source/window exact, checker maximumByteDifference=$maximumDisplayDifference (acceptance1); LOD0, warm identity/cache, opacity/undo, budget fallback/restore, EGL recreate, v4 restore and retained close passed")
                } finally { retained.close() }
            }
        }
        return reports.joinToString("\n") + "\nCheckpoint load creates fresh payloads; this device test does not prove cross-layer shared-payload/different-window cache aliasing."
    } finally { scratch.deleteRecursively() }
}
