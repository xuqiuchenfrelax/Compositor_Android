package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Bulk JNI descriptors retain the ordinary getters' geometry, pixel windows, and lifetime. */
internal fun metadataBulkSmoke(instrumentation: Instrumentation): String {
    fun rejected(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: IllegalStateException) { rejected = true }
        check(rejected) { "Invalid scene access was accepted" }
    }
    fun verify(scene: NativeScene, index: Int, width: Int, expected: ByteArray): List<SceneTileView> {
        val views = scene.tileViewsMetadata(index)
        check(views.size == scene.layerMetadata(index).tileCount)
        check(views == views.indices.map { scene.tileViewMetadata(index, it) })
        for ((tileIndex, view) in views.withIndex()) {
            val stride = view.width * 4 + 12
            val pixels = ByteBuffer.allocateDirect(stride * view.height).apply {
                repeat(capacity()) { put(0x5a.toByte()) }; rewind()
            }
            scene.readTile(index, tileIndex, stride, pixels)
            for (y in 0 until view.height) {
                for (x in 0 until view.width) for (channel in 0..3) check(
                    pixels.get(y * stride + x * 4 + channel) ==
                        expected[((view.y + y) * width + view.x + x) * 4 + channel]) {
                    "Bulk tile window differs from independent source pixels"
                }
                for (padding in view.width * 4 until stride) check(pixels.get(y * stride + padding) == 0x5a.toByte())
            }
        }
        return views
    }
    val scratch = File(instrumentation.targetContext.cacheDir, "metadata-bulk-${System.nanoTime()}").apply { check(mkdirs()) }
    try {
        val assets = instrumentation.context.assets
        val spec = assets.open("tile-views/phase-fixtures.json").use { JSONObject(it.reader().readText()) }
        for (stem in listOf("phase-transparent", "phase-fill")) {
            val file = File(scratch, "$stem.checkpoint")
            assets.open("tile-views/$stem.checkpoint").use { input -> file.outputStream().use { input.copyTo(it) } }
            val expected = assets.open("tile-views/$stem.rgba").use { it.readBytes() }
            NativeEditor.load(file).use { editor ->
                checkNotNull(editor.captureScene()).use { scene ->
                    val views = verify(scene, 0, spec.getInt("width"), expected)
                    check(views.any { it.sourceX != 0 && it.sourceY != 0 } && views.all { it.maximumLod == 0 })
                    check(views.all { it.phaseX == spec.getInt("phase_x") && it.phaseY == spec.getInt("phase_y") })
                    editor.close()
                    check(verify(scene, 0, spec.getInt("width"), expected) == views)
                }
            }
        }
        NativeEditor(1024, addDefaultLayers = false).use { editor ->
            editor.addLayer()
            checkNotNull(editor.captureScene()).use { empty ->
                check(empty.layerMetadata(0).tileCount == 0 && empty.tileViewsMetadata(0).isEmpty())
                rejected { empty.tileViewsMetadata(-1) }; rejected { empty.tileViewsMetadata(1) }
                empty.close(); empty.close()
                rejected { empty.tileViewsMetadata(0) }; rejected { empty.tileViewMetadata(0, 0) }
            }
            val width = 513; val height = 259
            val expected = ByteArray(width * height * 4) { offset ->
                val pixel = offset / 4; val x = pixel % width; val y = pixel / width
                when (offset % 4) { 0 -> (13 * x + 7 * y).toByte(); 1 -> (5 * x + 19 * y).toByte()
                    2 -> (3 * x + 11 * y).toByte(); else -> 255.toByte() }
            }
            editor.importPixels(width, height, ByteBuffer.allocateDirect(expected.size).apply { put(expected); rewind() })
            val id = editor.state().layers.last().id
            val retained = checkNotNull(editor.captureScene())
            try {
                val views = verify(retained, 1, width, expected)
                check(views.size == 6 && views.any { it.width == 1 } && views.any { it.height == 3 })
                check(views.any { it.maximumLod > 0 })
                editor.startStroke(id, 8f, false)
                editor.samples(doubleArrayOf(24.0, 24.0, 1.0, 0.0)); editor.endStroke(false)
                checkNotNull(editor.captureScene()).use { changed ->
                    check(changed.tileViewsMetadata(1) == (0 until changed.layerMetadata(1).tileCount).map { changed.tileViewMetadata(1, it) })
                    check(changed.tileViewsMetadata(1) != views)
                }
                check(verify(retained, 1, width, expected) == views)
                editor.undo(false)
                checkNotNull(editor.captureScene()).use { restored -> check(verify(restored, 1, width, expected) == views) }
                rejected { retained.tileViewsMetadata(2) }; rejected { retained.tileViewMetadata(1, views.size) }
                editor.close()
                check(verify(retained, 1, width, expected) == views)
            } finally { retained.close() }
            rejected { retained.tileViewsMetadata(1) }
        }
        return "Bulk/individual descriptors exact for patterned odd-edge and independent phase windows; padded reads exact with padding untouched; empty, invalid layer/tile, closed/idempotent close, paint/undo and scene-after-editor-close passed."
    } finally { scratch.deleteRecursively() }
}
