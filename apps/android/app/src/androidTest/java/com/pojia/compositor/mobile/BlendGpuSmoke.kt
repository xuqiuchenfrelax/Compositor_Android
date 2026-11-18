package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.opengl.GLES30
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs

/** Reads the transparent GPU target against copied independent desktop bytes, with no CPU display fallback. */
internal fun blendGpuSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit): String {
    val fixture = instrumentation.context.assets.open("blend-fixtures-p17.json").bufferedReader().use { JSONObject(it.readText()) }
    val observations = fixture.getJSONArray("observations")
    check(observations.length() == 360)
    var maximumGpu = 0
    var maximumCpu = 0
    NativeEditor(1, addDefaultLayers = false).use { editor ->
        val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
        renderer.viewport = Viewport(0.0, 0.0, 1.0, 1.0)
        renderer.enableLod = false
        renderer.setTextureBudget(1024)
        renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1, 1)
        val gpu = ByteBuffer.allocateDirect(4)
        val cpu = ByteBuffer.allocateDirect(4)
        for (index in 0 until observations.length()) {
            val record = observations.getJSONObject(index)
            val sample = record.getJSONObject("sample")
            editor.state().layers.forEach { editor.deleteLayer(it.id) }
            fun add(name: String): Long {
                val color = sample.getJSONArray(name)
                return editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
                    repeat(4) { put(color.getInt(it).toByte()) }; rewind()
                })
            }
            add("destination")
            val source = add("source")
            editor.setAppearanceForTest(LayerState(source, sample.getDouble("opacity"), true, record.getInt("mode")))
            renderer.onDrawFrame(null)
            check(renderer.fallbackBytes == 0 && renderer.readComposition(gpu)) { "Desktop fixture $index used CPU display" }
            check(editor.draw(1, 1, 0f, 0f, 1f, 1f, cpu))
            val expected = record.getJSONArray("expected")
            for (channel in 0..3) {
                val a = gpu.get(channel).toInt() and 255
                val c = cpu.get(channel).toInt() and 255
                val e = expected.getInt(channel)
                maximumGpu = maxOf(maximumGpu, abs(a - e)); maximumCpu = maxOf(maximumCpu, abs(c - e))
                check(abs(a - e) <= 2 && abs(c - e) <= 2) {
                    "Desktop fixture $index mode=${record.getInt("mode")} sample=${sample.getString("name")} channel=$channel gpu=$a cpu=$c expected=$e"
                }
            }
            check(renderer.totalGpuBytes <= renderer.textureBudgetBytes && GLES30.glGetError() == GLES30.GL_NO_ERROR)
            renderer.onDrawFrame(null)
            check(renderer.lastFrameUploads == 0L && renderer.lastFrameAllocations == 0L && renderer.lastFrameEvictions == 0L)
            if (index == 179) {
                recreateContext(); renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1, 1)
            }
        }
        renderer.setTextureBudget(0); renderer.onDrawFrame(null)
        check(renderer.totalGpuBytes == 0)
        renderer.setTextureBudget(1024); renderer.onDrawFrame(null)
        check(renderer.fallbackBytes == 0 && renderer.readComposition(gpu))
        editor.close(); renderer.onDrawFrame(null); check(renderer.totalGpuBytes == 0)
    }
    val translucent = translucentGpuSmoke()
    val budget = budgetGpuSmoke()
    val group = groupGpuSmoke(recreateContext)
    return "360 independent CG/CI fixtures, all24 modes: transparent actual GPU maximumByteDifference=$maximumGpu, CPU=$maximumCpu (original acceptance2), no CPU display; warm cache, context recreation, trim/restore/close; $translucent; $budget; $group"
}

/** Complete independently frozen multi-layer fixture set; all failures are saved before rejecting the suite. */
internal fun groupBlendGoldenSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit, evidenceLabel: String? = null): String {
    val evidenceName = evidenceLabel?.let {
        check(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid group evidence label" }
        "p17-$it.json"
    } ?: "p17-groups-03.json"
    return independentBlendGoldenSmoke(instrumentation, recreateContext,
        "p17-unmasked-groups/fixtures.json", 48, evidenceName, "group blend")
}

/** The independent Divide holdout retains exact desktop JSON doubles and all failed observations. */
internal fun divideGoldenSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit, evidenceLabel: String): String {
    check(evidenceLabel.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid Divide evidence label" }
    val binding = instrumentation.context.assets.open("p17-divide-zero/provenance.json").bufferedReader().use { JSONObject(it.readText()) }
    return independentBlendGoldenSmoke(instrumentation, recreateContext,
        "p17-divide-zero/fixtures.json", 128, "p17-$evidenceLabel.json", "Divide", binding, true)
}

private fun independentBlendGoldenSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit,
    assetPath: String, expectedCount: Int, evidenceName: String, suite: String,
    binding: JSONObject? = null, collectCaseErrors: Boolean = false): String {
    val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("gpu-blend"))
    val output = java.io.File(directory, evidenceName)
    check(!output.exists()) { "Evidence already exists: $output" }
    fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val fixtureBytes = instrumentation.context.assets.open(assetPath).use { it.readBytes() }
    val fixtureSha = sha256(fixtureBytes)
    val fixture = JSONObject(fixtureBytes.toString(Charsets.UTF_8))
    val observations = fixture.getJSONArray("observations")
    check(observations.length() == expectedCount)
    val expectedBytes = ArrayList<Byte>()
    for (index in 0 until observations.length()) {
        val record = observations.getJSONObject(index)
        val input = record.getJSONObject("input")
        val expected = record.getJSONArray("expected")
        check(expected.length() == input.getInt("width") * input.getInt("height") * 4)
        for (offset in 0 until expected.length()) {
            val value = expected.getInt(offset); check(value in 0..255); expectedBytes.add(value.toByte())
        }
    }
    val expectedSha = sha256(expectedBytes.toByteArray())
    binding?.let {
        check(it.getInt("cases") == expectedCount && it.getInt("tolerance") == 2)
        check(fixtureSha == it.getString("assetSHA256") && expectedSha == it.getString("expectedRGBA8SHA256"))
    }
    val results = org.json.JSONArray()
    var failedCases = 0
    for (index in 0 until observations.length()) {
        val record = observations.getJSONObject(index)
        val input = record.getJSONObject("input")
        val width = input.getInt("width"); val height = input.getInt("height")
        var caseFailed = false
        var result: JSONObject? = null
        try {
            NativeEditor(maxOf(width, height), addDefaultLayers = false).use { editor ->
                val definitions = input.getJSONArray("layers")
                val ids = LongArray(definitions.length())
                for (i in ids.indices) {
                    val layer = definitions.getJSONObject(i)
                    ids[i] = if (layer.getBoolean("group")) editor.addGroup() else {
                        val image = layer.getJSONArray("image")
                        editor.importPixels(layer.getInt("width"), layer.getInt("height"),
                            ByteBuffer.allocateDirect(image.length()).apply {
                                repeat(image.length()) { put(image.getInt(it).toByte()) }; rewind()
                            })
                    }
                    val transform = layer.getJSONArray("transform")
                    editor.setTransform(ids[i], Transform64(transform.getDouble(0), transform.getDouble(1),
                        transform.getDouble(2), transform.getDouble(3), transform.getDouble(4),
                        transform.getInt(5) != 0, transform.getInt(6) != 0))
                    editor.setAppearanceForTest(LayerState(ids[i], layer.getDouble("opacity"), layer.getBoolean("visible"), layer.getInt("mode")))
                }
                for (i in ids.indices) {
                    val parent = definitions.getJSONObject(i).getInt("parent")
                    if (parent >= 0) editor.reparentLayer(ids[i], ids[parent])
                }
                val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
                try {
                    renderer.enableLod = false; renderer.viewport = Viewport(0.0, 0.0, width.toDouble(), height.toDouble())
                    renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, width, height)
                    val gpu = ByteBuffer.allocateDirect(width * height * 4); val cpu = ByteBuffer.allocateDirect(gpu.capacity())
                    fun read(): IntArray {
                        renderer.onDrawFrame(null)
                        check(renderer.fallbackBytes == 0 && renderer.readComposition(gpu))
                        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                        return IntArray(gpu.capacity()) { offset ->
                            val y = offset / 4 / width; val x = offset / 4 % width
                            gpu.get(((height - 1 - y) * width + x) * 4 + offset % 4).toInt() and 255
                        }
                    }
                    val actual = read()
                    check(editor.draw(width, height, renderer.viewport.x, renderer.viewport.y, renderer.viewport.width, renderer.viewport.height, cpu))
                    val cpuBytes = IntArray(cpu.capacity()) { cpu.get(it).toInt() and 255 }
                    val expected = record.getJSONArray("expected")
                    var gpuError = 0; var cpuError = 0; val differing = org.json.JSONArray()
                    for (offset in actual.indices) {
                        val e = expected.getInt(offset)
                        gpuError = maxOf(gpuError, abs(actual[offset] - e)); cpuError = maxOf(cpuError, abs(cpuBytes[offset] - e))
                        if (abs(actual[offset] - e) > 2 || abs(cpuBytes[offset] - e) > 2)
                            differing.put(JSONObject().put("offset", offset).put("expected", e).put("gpu", actual[offset]).put("cpu", cpuBytes[offset]))
                    }
                    caseFailed = gpuError > 2 || cpuError > 2
                    result = JSONObject().put("name", input.getString("name")).put("gpuMaximumByteDifference", gpuError)
                        .put("cpuMaximumByteDifference", cpuError).put("actualGpu", org.json.JSONArray(actual.toList()))
                        .put("actualCpu", org.json.JSONArray(cpuBytes.toList())).put("expected", expected).put("failures", differing)
                    val uploads = renderer.uploadCount
                    check(read().contentEquals(actual) && renderer.uploadCount == uploads && renderer.lastFrameUploads == 0L &&
                        renderer.lastFrameAllocations == 0L && renderer.lastFrameEvictions == 0L)
                    checkNotNull(editor.captureScene()).use { retained ->
                        val plan = checkNotNull(retained.unmaskedDrawPlan())
                        val pixels = ByteBuffer.allocateDirect(cpu.capacity())
                        retained.draw(width, height, renderer.viewport, pixels)
                        editor.setVisible(ids[0], false); read()
                        check(checkNotNull(retained.unmaskedDrawPlan()).layerIndices.contentEquals(plan.layerIndices))
                        val after = ByteBuffer.allocateDirect(cpu.capacity()); retained.draw(width, height, renderer.viewport, after)
                        check((0 until after.capacity()).all { after.get(it) == pixels.get(it) })
                        editor.undo(false); check(read().contentEquals(actual))
                    }
                    if (index == expectedCount / 2 - 1) { recreateContext(); renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, width, height); check(read().contentEquals(actual)) }
                } finally {
                    editor.close(); renderer.onDrawFrame(null); check(renderer.totalGpuBytes == 0)
                }
            }
        } catch (error: Exception) {
            if (!collectCaseErrors) throw error
            caseFailed = true
            result = (result ?: JSONObject().put("name", input.getString("name")).put("expected", record.getJSONArray("expected")))
                .put("runtimeFailure", error.toString()).put("runtimeStack", android.util.Log.getStackTraceString(error))
        }
        if (caseFailed) failedCases++
        results.put(checkNotNull(result))
    }
    check(output.createNewFile()) { "Evidence already exists: $output" }
    output.outputStream().use { stream ->
        stream.write(JSONObject().put("cases", results).put("failedCases", failedCases).put("tolerance", 2)
            .put("fixtureAsset", assetPath).put("fixtureSHA256", fixtureSha).put("expectedRGBA8SHA256", expectedSha)
            .put("expectedCount", expectedCount).put("timingCollected", false).toString().toByteArray()); stream.fd.sync()
    }
    check(failedCases == 0) { "Independent$expectedCount $suite GPU/CPU strict tolerance2 failures=$failedCases; all observations preserved: $output" }
    return "Independent$expectedCount actual transparent $suite GPU/CPU match frozen desktop tolerance2; warm/capturedplan/history/context/close passed; $output"
}
