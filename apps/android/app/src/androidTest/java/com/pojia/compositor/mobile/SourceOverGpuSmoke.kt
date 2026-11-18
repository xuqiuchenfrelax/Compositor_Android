package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.io.File
import kotlin.math.abs

/** Exact CoreGraphics observations include Double opacity boundaries. */
internal fun sourceOverGpuSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit): String {
    val bytes = instrumentation.context.assets.open("probe-observations.json").use { it.readBytes() }
    val fixtures = JSONArray(bytes.toString(Charsets.UTF_8))
    check(fixtures.length() == 185)
    val results = JSONArray()
    val restoredResults = JSONArray()
    var failures = 0
    var maximum = 0
    var completedOperations = 0
    var activeIndex = -1
    var activeScenario = -1
    val progress = File(instrumentation.targetContext.cacheDir, "source-over-gpu-progress.json")
    fun writeProgress(status: String, failure: Throwable? = null) {
        val report = JSONObject().put("status", status).put("completedOperations", completedOperations)
            .put("completedCases", results.length()).put("totalCases", fixtures.length() * 3)
            .put("activeIndex", activeIndex).put("activeScenario", activeScenario)
            .put("failedCases", failures).put("maximumByteDifference", maximum)
            .put("restoredContextCases", restoredResults.length())
        if (failure != null) report.put("error", failure.toString())
        val temporary = File(progress.parentFile, progress.name + ".tmp")
        temporary.outputStream().use { it.write(report.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(progress)) { "Could not publish GPU smoke progress" }
    }
    class Scenario(val editor: NativeEditor, val renderer: GpuDisplayRenderer) {
        var layers = emptyList<Long>()
    }
    val scenarios = ArrayList<Scenario>(3)
    val gpu = ByteBuffer.allocateDirect(4)
    val cpu = ByteBuffer.allocateDirect(4)
    fun observe(index: Int, scenario: Int, restored: Boolean = false) {
        val fixture = fixtures.getJSONObject(index)
        val (editor, renderer) = scenarios[scenario].let { it.editor to it.renderer }
        gpu.clear(); cpu.clear()
        renderer.onDrawFrame(null)
        check(renderer.fallbackBytes == 0 && renderer.readComposition(gpu))
        check(editor.draw(1, 1, 0.0, 0.0, 1.0, 1.0, cpu))
        val expected = fixture.getJSONArray(if (scenario == 0) "normalSurface" else "normalRaw")
        val actual = IntArray(4) { gpu.get(it).toInt() and 255 }
        val native = IntArray(4) { cpu.get(it).toInt() and 255 }
        var difference = 0
        repeat(4) { difference = maxOf(difference, abs(actual[it] - expected.getInt(it)), abs(native[it] - expected.getInt(it))) }
        maximum = maxOf(maximum, difference)
        if (difference != 0) failures++
        val observation = JSONObject().put("name", fixture.getJSONObject("input").getString("name"))
            .put("scenario", scenario).put("gpu", JSONArray(actual.toList())).put("cpu", JSONArray(native.toList()))
            .put("expected", expected).put("maximumByteDifference", difference)
        (if (restored) restoredResults else results).put(observation)
        renderer.onDrawFrame(null)
        check(renderer.lastFrameUploads == 0L && renderer.lastFrameAllocations == 0L && renderer.lastFrameEvictions == 0L)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
    }
    var primaryFailure: Throwable? = null
    try {
        writeProgress("running")
        repeat(3) {
            val editor = NativeEditor(1, addDefaultLayers = false)
            val renderer = GpuDisplayRenderer(editor) { error(it) }
            scenarios.add(Scenario(editor, renderer))
            renderer.viewport = Viewport(0.0, 0.0, 1.0, 1.0); renderer.enableLod = false
            renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1, 1)
        }
        for (index in 0 until fixtures.length()) {
            activeIndex = index
            val input = fixtures.getJSONObject(index).getJSONObject("input")
            for (scenario in 0..2) {
                activeScenario = scenario
                val state = scenarios[scenario]
                val editor = state.editor
                // Remove the child before its group and the old destination last.
                state.layers.forEach(editor::deleteLayer)
                val layers = ArrayList<Long>(3)
                fun add(name: String): Long {
                    val color = input.getJSONArray(name)
                    return editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
                        repeat(4) { put(color.getInt(it).toByte()) }; rewind()
                    })
                }
                val destination = if (scenario != 0) add("destination") else null
                val source = add("source")
                editor.setAppearanceForTest(LayerState(source, input.getDouble("opacity"), true, 0))
                layers.add(source)
                if (scenario == 2) {
                    val group = editor.addGroup()
                    editor.reparentLayer(source, group); layers.add(group)
                }
                if (destination != null) layers.add(destination)
                state.layers = layers
                observe(index, scenario)
            }
            if (index == 91) {
                // Keep all three editors/documents alive across actual EGL context loss.
                recreateContext()
                scenarios.forEach { it.renderer.onSurfaceCreated(null, null); it.renderer.onSurfaceChanged(null, 1, 1) }
                for (scenario in 0..2) {
                    activeScenario = scenario
                    observe(index, scenario, restored = true)
                }
            }
            completedOperations = index + 1
            if (completedOperations % 16 == 0) writeProgress("running")
        }
        check(results.length() == 555 && restoredResults.length() == 3)
        val report = JSONObject().put("operations", fixtures.length()).put("scenariosPerOperation", 3)
            .put("tolerance", 0).put("failedCases", failures).put("maximumByteDifference", maximum)
            .put("fixtureSHA256", java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }).put("results", results)
            .put("restoredContextResults", restoredResults)
        val output = File(instrumentation.targetContext.cacheDir, "source-over-gpu.json")
        output.outputStream().use { it.write(report.toString().toByteArray()); it.fd.sync() }
        check(failures == 0) { "Actual CG exact source-over GPU/CPU failures=$failures maximum=$maximum; $output" }
    } catch (failure: Throwable) {
        primaryFailure = failure
        writeProgress("failed", failure)
        throw failure
    } finally {
        var cleanupFailure: Throwable? = null
        scenarios.forEach {
            try {
                it.editor.close(); it.renderer.onDrawFrame(null); check(it.renderer.totalGpuBytes == 0)
            } catch (failure: Throwable) {
                if (primaryFailure != null) primaryFailure!!.addSuppressed(failure)
                else if (cleanupFailure == null) cleanupFailure = failure
                else cleanupFailure!!.addSuppressed(failure)
            }
        }
        cleanupFailure?.let { writeProgress("failed", it); throw it }
    }
    writeProgress("complete")
    return "185 actual CG operations x surface/flat/group: 555 exact GPU/CPU observations; zero fallback; warm cache/context/close passed"
}
