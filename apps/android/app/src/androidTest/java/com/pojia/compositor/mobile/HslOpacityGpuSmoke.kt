package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Display
import java.util.concurrent.TimeUnit
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Frozen actual CG bytes; opacity is exact, HSL retains the original two-byte gate. */
internal fun hslOpacityGpuSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit,
    phase: String = "all"): String {
    // Keep the target foreground: some devices suspend offscreen GPU work while another app is active.
    val token = "p122-gpu-${System.nanoTime()}"
    check(validatedDebugWorkspaceToken(token) == token)
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
    val workspace = activity.retainedWorkspaceForTest()
    var primaryFailure: Throwable? = null
    try {
        check(workspace.recoveryDirectoryForTest().canonicalFile ==
            File(instrumentation.targetContext.filesDir, "debug-workspaces/$token").canonicalFile)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var focused = false
        while (!focused && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
            if (!focused) Thread.sleep(25)
        }
        check(focused) { "GPU test foreground host did not gain focus" }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "GPU foreground isolated workspace: $token; display=0\n")
        })
        return hslOpacityGpuPixelsSmoke(instrumentation, recreateContext, phase) {
            instrumentation.runOnMainSync {
                check(activity.hasWindowFocus()) { "GPU test foreground host lost focus; not a pixel result" }
            }
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        try {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            workspace.closeAsync().get(12, TimeUnit.SECONDS)
        } catch (cleanup: Throwable) {
            val failure = primaryFailure
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}

private fun hslOpacityGpuPixelsSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit,
    phase: String, checkForeground: () -> Unit): String {
    check(phase in setOf("all", "mechanism", "opacity", "hsl", "direct", "groups"))
    fun sha(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val assets = instrumentation.context.assets
    val bytes = assets.open("p85-hsl-opacity/fixtures.json").use { it.readBytes() }
    val binding = assets.open("p85-hsl-opacity/binding.json").use { JSONObject(it.reader().readText()) }
    check(sha(bytes) == binding.getString("assetSHA256"))
    val all = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("observations")
    check(all.length() == binding.getInt("cases") && all.length() == 2472)
    val expectedBytes = ArrayList<Byte>()
    val cases = ArrayList<JSONObject>()
    for (index in 0 until all.length()) {
        val record = all.getJSONObject(index)
        val expected = record.getJSONArray("expected")
        check(expected.length() == record.getInt("width") * record.getInt("height") * 4)
        repeat(expected.length()) { expectedBytes.add(expected.getInt(it).also { value -> check(value in 0..255) }.toByte()) }
        if (phase == "all" || record.getString("phase") == phase) cases.add(record)
    }
    check(sha(expectedBytes.toByteArray()) == binding.getString("expectedRGBA8SHA256"))
    check(cases.size == if (phase == "all") all.length() else binding.getJSONObject("phaseCounts").getInt(phase))
    val output = File(instrumentation.targetContext.cacheDir, "p85-hsl-opacity-gpu-$phase.json")
    val progressFile = File(instrumentation.targetContext.cacheDir, "p85-hsl-opacity-gpu-$phase-progress.json")
    val results = JSONArray()
    val restored = JSONArray()
    var failures = 0
    var maximumGpu = 0
    var maximumCpu = 0
    var activeIndex = -1
    fun publish(file: File, report: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { it.write(report.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(file)) { "Could not publish $file" }
    }
    fun progress(status: String, failure: Throwable? = null) {
        val report = JSONObject().put("status", status).put("phase", phase).put("completedCases", results.length())
            .put("totalCases", cases.size).put("activeIndex", activeIndex).put("failedCases", failures)
            .put("maximumGpuDifference", maximumGpu).put("maximumCpuDifference", maximumCpu)
            .put("contextRestoredCases", restored.length())
        if (activeIndex in cases.indices) report.put("activeName", cases[activeIndex].getString("name"))
        if (failure != null) report.put("error", failure.toString())
        publish(progressFile, report)
    }
    fun report(status: String, failure: Throwable? = null) {
        val value = JSONObject().put("status", status).put("phase", phase).put("binding", binding)
            .put("totalCases", cases.size).put("completedCases", results.length()).put("failedCases", failures)
            .put("maximumGpuDifference", maximumGpu).put("maximumCpuDifference", maximumCpu)
            .put("results", results).put("restoredContextResults", restored)
            .put("scope", "Actual GLES/CPU unmasked opacity, staged masked-source HSL and Double groups; original185 unchanged; no native mask, UI or performance claim")
        if (failure != null) value.put("error", failure.toString())
        publish(output, value)
    }
    val editor = NativeEditor(2, addDefaultLayers = false)
    val renderer = GpuDisplayRenderer(editor) { error(it) }
    val input = ByteBuffer.allocateDirect(16)
    val gpu = ByteBuffer.allocateDirect(16)
    val cpu = ByteBuffer.allocateDirect(16)
    var ids = emptyList<Long>()
    fun observe(record: JSONObject, contextRestored: Boolean = false): IntArray {
        val width = record.getInt("width"); val height = record.getInt("height")
        val size = width * height * 4
        renderer.onDrawFrame(null)
        check(renderer.fallbackBytes == 0 && renderer.readComposition(gpu))
        cpu.clear()
        check(editor.draw(width, height, 0.0, 0.0, width.toDouble(), height.toDouble(), cpu))
        val actual = IntArray(size) { offset ->
            val y = offset / 4 / width; val x = offset / 4 % width
            gpu.get(((height - 1 - y) * width + x) * 4 + offset % 4).toInt() and 255
        }
        val native = IntArray(size) { cpu.get(it).toInt() and 255 }
        val expected = record.getJSONArray("expected")
        var gpuError = 0; var cpuError = 0
        repeat(size) {
            gpuError = maxOf(gpuError, abs(actual[it] - expected.getInt(it)))
            cpuError = maxOf(cpuError, abs(native[it] - expected.getInt(it)))
        }
        maximumGpu = maxOf(maximumGpu, gpuError); maximumCpu = maxOf(maximumCpu, cpuError)
        val tolerance = record.getInt("tolerance")
        if (gpuError > tolerance || cpuError > tolerance) failures++
        val observation = JSONObject().put("name", record.getString("name")).put("phase", record.getString("phase"))
            .put("tolerance", tolerance).put("gpu", JSONArray(actual.toList())).put("cpu", JSONArray(native.toList()))
            .put("expected", expected).put("gpuDifference", gpuError).put("cpuDifference", cpuError)
        (if (contextRestored) restored else results).put(observation)
        renderer.onDrawFrame(null)
        check(renderer.lastFrameUploads == 0L && renderer.lastFrameAllocations == 0L && renderer.lastFrameEvictions == 0L)
        check(renderer.readComposition(gpu))
        repeat(size) { offset ->
            val y = offset / 4 / width; val x = offset / 4 % width
            check((gpu.get(((height - 1 - y) * width + x) * 4 + offset % 4).toInt() and 255) == actual[offset])
        }
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        return actual
    }
    var primaryFailure: Throwable? = null
    try {
        progress("running")
        renderer.enableLod = false
        renderer.onSurfaceCreated(null, null)
        for ((index, record) in cases.withIndex()) {
            activeIndex = index
            // Reverse storage order removes children before their ancestor groups.
            ids.asReversed().forEach(editor::deleteLayer)
            val definitions = record.getJSONArray("layers")
            val nextIds = ArrayList<Long>(definitions.length())
            for (layerIndex in 0 until definitions.length()) {
                val definition = definitions.getJSONObject(layerIndex)
                val id = if (definition.getBoolean("group")) editor.addGroup() else {
                    val pixels = definition.getJSONArray("pixels")
                    input.clear(); repeat(pixels.length()) { input.put(pixels.getInt(it).toByte()) }; input.rewind()
                    editor.importPixels(record.getInt("width"), record.getInt("height"), input)
                }
                editor.setTransform(id, Transform64(0.0, 0.0, record.getInt("width").toDouble(), record.getInt("height").toDouble(), 0.0, false, false))
                editor.setAppearanceForTest(LayerState(id, definition.getDouble("opacity"), true, definition.getInt("mode")))
                nextIds.add(id)
            }
            for (layerIndex in nextIds.indices) {
                val parent = definitions.getJSONObject(layerIndex).getInt("parent")
                if (parent >= 0) editor.reparentLayer(nextIds[layerIndex], nextIds[parent])
            }
            ids = nextIds
            val width = record.getInt("width"); val height = record.getInt("height")
            renderer.viewport = Viewport(0.0, 0.0, width.toDouble(), height.toDouble())
            renderer.onSurfaceChanged(null, width, height)
            val actual = observe(record)
            if (index == cases.size / 2 || index == cases.lastIndex) {
                // The editor, document and current Double hierarchy survive actual EGL loss.
                recreateContext()
                renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, width, height)
                check(observe(record, contextRestored = true).contentEquals(actual))
            }
            if ((index + 1) % 16 == 0) { checkForeground(); progress("running") }
        }
        check(results.length() == cases.size && restored.length() == 2)
        report("pixels-collected")
        check(failures == 0) { "P85 GPU/CPU CG failures=$failures gpu=$maximumGpu cpu=$maximumCpu; $output" }
    } catch (failure: Throwable) {
        primaryFailure = failure
        report("failed", failure); progress("failed", failure)
        throw failure
    } finally {
        try {
            try {
                editor.close(); renderer.onDrawFrame(null); check(renderer.totalGpuBytes == 0)
            } finally { renderer.retireSceneAdmission() }
        } catch (failure: Throwable) {
            if (primaryFailure != null) primaryFailure!!.addSuppressed(failure)
            else { report("failed", failure); progress("failed", failure); throw failure }
        }
    }
    report("complete"); progress("complete")
    return "P85 $phase: ${cases.size} frozen CG GPU/CPU cases passed per-case exact/tolerance2 gates; zero fallback, warm cache, retained-document EGL restore and texture close; $output"
}
