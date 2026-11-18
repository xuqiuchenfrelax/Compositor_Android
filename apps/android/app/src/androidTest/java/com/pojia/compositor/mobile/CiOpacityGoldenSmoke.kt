package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.opengl.GLES30
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Independent desktop bytes exercise actual PNG/package decoding and the GLES CPU-fallback display. */
internal fun ciOpacityGoldenSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val assets = instrumentation.context.assets
    val scratch = File(context.cacheDir.canonicalFile, "ci-opacity-golden-${System.nanoTime()}").apply { check(mkdirs()) }
    val fixtures = assets.open("ci-opacity-goldens.json").use { JSONObject(it.reader().readText()) }.getJSONArray("fixtures")
    val results = ArrayList<String>()
    try {
        for (index in 0 until fixtures.length()) {
            val record = fixtures.getJSONObject(index)
            val directory = File(scratch, "$index.comp")
            copyProjectAssets(assets, record.getString("package"), directory)
            NativeEditor.loadProjectCandidate(context, directory, 64L * 1024 * 1024, 0).use { editor ->
                check(editor.canvasWidth == 1 && editor.canvasHeight == 1 && editor.state().layers.size == 2)
                val top = editor.state().layers.last()
                check(top.blend == 3 && top.opacity == 0.73 && top.hasMask && top.maskPlacement == null)
                checkNotNull(editor.captureScene()).use { scene ->
                    for ((layer, key) in listOf(0 to "destination", 1 to "source")) {
                        val pixels = ByteBuffer.allocateDirect(4)
                        scene.readTile(layer, 0, 4, pixels)
                        val expected = record.getJSONArray(key)
                        for (channel in 0..3) check((pixels.get(channel).toInt() and 255) == expected.getInt(channel)) {
                            "Golden premultiplied $key byte changed in PNG decoding"
                        }
                    }
                }
                val mask = ByteBuffer.allocateDirect(1)
                editor.maskPixels(top.id, 1, mask)
                check((mask.get(0).toInt() and 255) == record.getInt("mask"))
                val golden = record.getJSONArray("expected")
                val cpu = ByteBuffer.allocateDirect(4)
                check(editor.draw(1, 1, 0.0, 0.0, 1.0, 1.0, cpu))
                val actualCpu = IntArray(4) { cpu.get(it).toInt() and 255 }
                for (channel in 0..3) check(abs(actualCpu[channel] - golden.getInt(channel)) <= 2) {
                    "Android masked Color Burn differs from independent desktop golden: ${actualCpu.toList()}"
                }
                val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
                try {
                    renderer.viewport = Viewport(0.0, 0.0, 1.0, 1.0)
                    renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1, 1); renderer.onDrawFrame(null)
                    check(renderer.cacheBytes == 0 && renderer.fallbackBytes == 4 && renderer.surfaceBytes == 4)
                    val pixels = ByteBuffer.allocateDirect(4)
                    GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                    check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                    val display = IntArray(4) { pixels.get(it).toInt() and 255 }
                    for (channel in 0..3) {
                        val expected = if (channel == 3) 255 else
                            (golden.getInt(channel) + 0.19 * (255 - golden.getInt(3))).roundToInt()
                        check(abs(display[channel] - expected) <= 2) { "GLES fallback differs from desktop + checker: ${display.toList()}" }
                    }
                    results.add("case$index desktop=${(0..3).map { golden.getInt(it) }} native=${actualCpu.toList()} display=${display.toList()}")
                } finally { editor.close(); renderer.onDrawFrame(null) }
            }
        }
        return "Masked Color Burn: two independent desktop goldens within retained 2-byte tolerance; exact PNG premultiplied sources/destination and raw gray masks; actual GLES fallback/checker and bounded 4-byte surfaces. ${results.joinToString("; ")}"
    } finally { scratch.deleteRecursively() }
}
