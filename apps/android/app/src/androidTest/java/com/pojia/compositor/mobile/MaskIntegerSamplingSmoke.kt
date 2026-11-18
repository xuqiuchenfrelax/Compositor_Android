package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.opengl.EGL14
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fixed independent Mac gray bytes over real PNG/project decoding and GLES CPU-fallback display. */
internal fun maskIntegerSamplingSmoke(instrumentation: Instrumentation): String {
    check(EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) { "Run maskIntegerSampling inside gpuSmoke" }
    val context = instrumentation.targetContext
    val assets = instrumentation.context.assets
    val ledger = ImageIoAdmission.ledger
    val initialClaims = ledger.snapshot()
    val scratch = File(context.cacheDir.canonicalFile, "p38-mask-integer-${System.nanoTime()}").apply { check(mkdirs()) }
    val evidence = File(context.filesDir, "p38-mask-integer-${System.nanoTime()}.json")
    val observations = JSONArray()
    val report = JSONObject().put("status", "RUNNING").put("tolerance", 2)
        .put("scope", "132 fixed actual Mac 1x1 mask samples; native PNG load/CPU render and GLES CPU-fallback/checker")
        .put("fixtures", observations)
    var failure: Throwable? = null
    var maximumCpuError = 0
    var maximumDisplayError = 0
    var blackStages = 0
    var whiteStages = 0
    try {
        val golden = assets.open("p38-mask-goldens.json").use { JSONObject(it.reader().readText()) }
        check(golden.getInt("schema") == 1 && golden.getInt("tolerance") == 2 && golden.getInt("fixtureCount") == 132)
        val fixtures = golden.getJSONArray("fixtures")
        check(fixtures.length() == 132)
        val names = (0 until fixtures.length()).map { fixtures.getJSONObject(it).getString("name") }
        val expectedNames = (0..31).flatMap { geometry -> (0..3).map { "fresh_${geometry}__random$it" } } +
            listOf("fresh_153__random2", "fresh_91__checker", "independent_random_edge_637_anchor__original",
                "independent_random_edge_709_anchor__original")
        check(names == expectedNames && names.toSet().size == 132) { "Fixed regression sample set changed" }
        report.put("sources", golden.getJSONArray("sources"))
        for (index in 0 until fixtures.length()) {
            val record = fixtures.getJSONObject(index)
            val name = record.getString("name")
            val result = JSONObject().put("name", name).put("status", "RUNNING")
                .put("expected", record.getJSONArray("expected"))
                .put("observationSource", record.getJSONObject("observationSource"))
                .put("observationIndex", record.getInt("observationIndex"))
                .put("observationRecordSHA256", record.getString("observationRecordSHA256"))
            observations.put(result)
            val beforeClaims = ledger.snapshot()
            val directory = File(scratch, "$index.comp")
            var editor: NativeEditor? = null
            var renderer: GpuDisplayRenderer? = null
            var caseFailure: Throwable? = null
            try {
                copyProjectAssets(assets, record.getString("package"), directory)
                p38MaskAssertPackage(directory, record)
                val width = record.getInt("maskWidth")
                val height = record.getInt("maskHeight")
                val raw = record.getJSONArray("rawMask")
                check(raw.length() == width * height)
                var borderSum = 0L
                var borderCount = 0L
                for (y in 0 until height) for (x in 0 until width) {
                    val value = raw.getInt(y * width + x)
                    check(value in 0..255)
                    if (x == 0 || y == 0 || x + 1 == width || y + 1 == height) {
                        borderSum += value; borderCount++
                    }
                }
                val white = borderSum * 2 >= borderCount * 255
                val stage = if (white) "white" else "black"
                if (white) whiteStages++ else blackStages++
                check(borderSum == record.getLong("borderSum") && borderCount == record.getLong("borderCount") &&
                    white == record.getBoolean("whiteBackground") && stage == record.getString("selectedMacStage"))
                val original = record.getJSONObject("originalMacObservation")
                val originalInput = original.getJSONObject("input")
                check(originalInput.getString("name") == name && originalInput.getInt("width") == width &&
                    originalInput.getInt("height") == height && originalInput.getJSONArray("mask").length() == raw.length())
                check((0 until raw.length()).all { originalInput.getJSONArray("mask").getInt(it) == raw.getInt(it) })
                check((0..6).all { originalInput.getJSONArray("transform").getDouble(it) ==
                    record.getJSONArray("maskPlacement").getDouble(it) })
                val expected = IntArray(4) { record.getJSONArray("expected").getInt(it) }
                check(expected.all { it == original.getInt(stage) }) { "$name expected bytes were not copied from original Mac stage" }
                // Both enum fields remain Nearest. Independent placement exercises the existing
                // high-quality mask placement kernel; this does not enable Smooth/High enum support.
                check(record.getString("layerSampling") == "Nearest" && record.getString("maskSampling") == "Nearest")
                val current = NativeEditor.loadProjectCandidate(context, directory, 64L * 1024 * 1024, 0)
                editor = current
                val state = current.state()
                val layer = state.layers.single()
                val owner = p38MaskTransform(record.getJSONArray("ownerTransform"))
                val placement = p38MaskTransform(record.getJSONArray("maskPlacement"))
                check(current.canvasWidth == 1 && current.canvasHeight == 1 &&
                    state.document.uuid == record.getString("documentID") && state.document.resolution == 72.0 &&
                    state.document.activeLayerId == layer.id && state.document.guides.isEmpty())
                check(layer.uuid == record.getString("layerID") && layer.name == name && layer.assetPresent &&
                    !layer.isGroup && layer.parentId == 0L && layer.maskSourceId == 0L && layer.visible &&
                    layer.opacity == 1.0 && layer.blend == 0 && layer.pixelWidth == 1 && layer.pixelHeight == 1 &&
                    layer.transform == owner && layer.sampling == 0 && layer.hasMask && layer.maskEnabled &&
                    !layer.maskLinked && layer.maskWidth == width && layer.maskHeight == height &&
                    layer.maskPlacement == placement && layer.maskSampling == 0 && !state.undo && !state.redo)
                val generation = current.currentSceneGeneration()
                val installedClaims = ledger.snapshot()
                check(installedClaims.reservations == beforeClaims.reservations + 1 &&
                    installedClaims.reservedBytes > beforeClaims.reservedBytes)
                val witness = p38MaskWitness(current, record)
                p38MaskAssertClaims(installedClaims, ledger.snapshot(), "$name source scene release")
                val cpu = ByteBuffer.allocateDirect(4)
                check(current.draw(1, 1, 0.0, 0.0, 1.0, 1.0, cpu))
                val actualCpu = IntArray(4) { cpu.get(it).toInt() and 255 }
                result.put("cpu", p38MaskArray(actualCpu)).put("selectedMacStage", stage)
                    .put("borderSum", borderSum).put("borderCount", borderCount)
                val cpuError = actualCpu.indices.maxOf { abs(actualCpu[it] - expected[it]) }
                result.put("maximumCpuError", cpuError)
                maximumCpuError = maxOf(maximumCpuError, cpuError)
                check(cpuError <= 2) { "$name CPU differs from independent Mac golden: ${actualCpu.toList()} vs ${expected.toList()}" }

                val gpu = GpuDisplayRenderer(current) { throw IllegalStateException("$name: $it") }
                renderer = gpu
                gpu.enableLod = false
                gpu.viewport = Viewport(0.0, 0.0, 1.0, 1.0)
                gpu.onSurfaceCreated(null, null); gpu.onSurfaceChanged(null, 1, 1)
                val display = ByteBuffer.allocateDirect(4)
                var firstDisplay: IntArray? = null
                repeat(3) { frame ->
                    gpu.onDrawFrame(null)
                    check(gpu.cacheBytes == 0 && gpu.cacheLiveBytes == 0L && gpu.pageCount == 0 &&
                        gpu.fallbackBytes == 4 && gpu.surfaceBytes == 4 && gpu.totalGpuBytes == 4 &&
                        gpu.uploadCount == 0L && gpu.textureAllocations == 0L && !gpu.completedFrameUsesTiles) {
                        "$name must use one 4-byte CPU fallback surface with no source texture pages"
                    }
                    display.clear()
                    GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, display)
                    check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "$name GL error after fallback frame $frame" }
                    val actualDisplay = IntArray(4) { display.get(it).toInt() and 255 }
                    val expectedDisplay = IntArray(4) { channel -> if (channel == 3) 255 else
                        (expected[channel] + 0.19 * (255 - expected[3])).roundToInt() }
                    val cpuDisplay = IntArray(4) { channel -> if (channel == 3) 255 else
                        (actualCpu[channel] + 0.19 * (255 - actualCpu[3])).roundToInt() }
                    val displayError = actualDisplay.indices.maxOf { abs(actualDisplay[it] - expectedDisplay[it]) }
                    maximumDisplayError = maxOf(maximumDisplayError, displayError)
                    result.put("display", p38MaskArray(actualDisplay)).put("expectedDisplay", p38MaskArray(expectedDisplay))
                        .put("maximumDisplayError", displayError)
                    check(displayError <= 2) { "$name GLES fallback differs from Mac + checker: ${actualDisplay.toList()} vs ${expectedDisplay.toList()}" }
                    check(actualDisplay.indices.all { abs(actualDisplay[it] - cpuDisplay[it]) <= 1 }) {
                        "$name GLES display failed to composite the actual CPU pixel over the checker"
                    }
                    firstDisplay?.let { check(actualDisplay.contentEquals(it)) { "$name warm fallback output changed" } }
                    if (firstDisplay == null) firstDisplay = actualDisplay
                }
                check(current.state() == state && current.currentSceneGeneration() == generation &&
                    p38MaskWitness(current, record) == witness) { "$name rendering changed document/history/source/mask" }
                p38MaskAssertClaims(installedClaims, ledger.snapshot(), "$name draw/scene release")
                p38MaskAssertPackage(directory, record)
                result.put("sourcePixelsSHA256", witness.sourceSHA256).put("rawMaskSHA256", witness.maskSHA256)
                    .put("sourceResourceIDs", JSONArray(witness.resources)).put("stateAndAssetsUnchanged", true)
                    .put("fallbackBytes", gpu.fallbackBytes).put("cacheBytes", gpu.cacheBytes)
                result.put("status", "PASS")
            } catch (error: Throwable) {
                caseFailure = error; result.put("status", "FAIL").put("error", error.stackTraceToString()); throw error
            } finally {
                p38MaskCleanup(caseFailure, result, {
                    editor?.let { current ->
                        current.close(); current.close()
                        check(current.captureScene() == null && current.state().layers.isEmpty())
                        check(!current.draw(1, 1, 0.0, 0.0, 1.0, 1.0, ByteBuffer.allocateDirect(4)))
                    }
                }, {
                    renderer?.let { gpu ->
                        // A closed editor's next GL callback retires fallback/tiles, then displays
                        // both checker tones without any composition storage.
                        gpu.onDrawFrame(null)
                        check(gpu.cacheBytes == 0 && gpu.cacheLiveBytes == 0L && gpu.pageCount == 0 &&
                            gpu.fallbackBytes == 0 && gpu.surfaceBytes == 0 && gpu.totalGpuBytes == 0)
                        gpu.onSurfaceChanged(null, 32, 1); gpu.onDrawFrame(null)
                        check(gpu.cacheBytes == 0 && gpu.fallbackBytes == 0 && gpu.surfaceBytes == 0)
                        val empty = ByteBuffer.allocateDirect(32 * 4)
                        GLES30.glReadPixels(0, 0, 32, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, empty)
                        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                        for (x in 0 until 32) for (channel in 0..3) {
                            val expected = if (channel == 3) 255 else ((if (x < 16) 0.19 else 0.24) * 255).roundToInt()
                            check(abs((empty.get(x * 4 + channel).toInt() and 255) - expected) <= 1)
                        }
                    }
                }, {
                    p38MaskAssertClaims(beforeClaims, ledger.snapshot(), "$name close")
                    result.put("closedGpuBytes", renderer?.totalGpuBytes ?: 0).put("ledgerRestored", true)
                }, {
                    check(directory.deleteRecursively())
                })
            }
        }
        check(blackStages > 0 && whiteStages > 0)
        report.put("status", "PASS").put("caseCount", observations.length())
            .put("maximumCpuError", maximumCpuError).put("maximumDisplayError", maximumDisplayError)
            .put("blackStages", blackStages).put("whiteStages", whiteStages)
        return "132 fixed independent Mac mask samples within original 2-byte CPU/display gates; " +
            "exact white RGBA source, gray8 masks and Double placements; three actual GLES fallback/checker frames each, " +
            "4-byte fallback/no tile pages, close checker/resource counters and per-case ledger restoration. " +
            "maxCPU=$maximumCpuError maxDisplay=$maximumDisplayError black=$blackStages white=$whiteStages; evidence=${evidence.absolutePath}. " +
            "Not masked GPU acceleration, Smooth/High enum support, full raster parity or real-device performance."
    } catch (error: Throwable) {
        failure = error; report.put("status", "FAIL").put("error", error.stackTraceToString()); throw error
    } finally {
        p38MaskCleanup(failure, report, {
            check(scratch.deleteRecursively())
        }, {
            p38MaskAssertClaims(initialClaims, ledger.snapshot(), "complete maskIntegerSampling")
            report.put("ledgerRestored", true)
        }, {
            evidence.writeText(report.toString(2))
        })
    }
}

private data class P38MaskWitness(val sourceSHA256: String, val maskSHA256: String, val resources: List<Long>)

private fun p38MaskWitness(editor: NativeEditor, record: JSONObject): P38MaskWitness {
    val resources = checkNotNull(editor.captureScene()).use { scene ->
        val layer = scene.layerMetadata(0)
        check(layer.tileCount == 1 && layer.pixelWidth == 1 && layer.pixelHeight == 1)
        check(scene.unmaskedDrawPlan() == null) { "Masked fixture unexpectedly bypassed CPU fallback" }
        val source = ByteBuffer.allocateDirect(4)
        scene.readTile(0, 0, 4, source)
        val bytes = ByteArray(4) { source.get(it) }
        check(bytes.indices.all { (bytes[it].toInt() and 255) == record.getJSONArray("source").getInt(it) })
        check(p38MaskSha(bytes) == record.getString("sourcePixelsSHA256"))
        val tile = scene.tileViewMetadata(0, 0)
        listOf(tile.payloadResourceId, tile.readResourceId)
    }
    val width = record.getInt("maskWidth")
    val expected = record.getJSONArray("rawMask")
    val raw = ByteBuffer.allocateDirect(width * record.getInt("maskHeight"))
    editor.maskPixels(editor.state().layers.single().id, width, raw)
    val bytes = ByteArray(raw.capacity()) { raw.get(it) }
    check(bytes.indices.all { (bytes[it].toInt() and 255) == expected.getInt(it) }) { "Raw gray8 mask changed during PNG import/render" }
    val maskHash = p38MaskSha(bytes)
    check(maskHash == record.getString("rawMaskSHA256"))
    return P38MaskWitness(record.getString("sourcePixelsSHA256"), maskHash, resources)
}

private fun p38MaskAssertPackage(directory: File, record: JSONObject) {
    val hashes = record.getJSONObject("filesSHA256")
    val expectedPaths = hashes.keys().asSequence().toSet()
    val actual = directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).invariantSeparatorsPath }.toSet()
    check(actual == expectedPaths)
    for (path in expectedPaths) check(p38MaskSha(File(directory, path).readBytes()) == hashes.getString(path)) { "Input package changed: $path" }
}

private fun p38MaskTransform(values: JSONArray): Transform64 {
    check(values.length() == 7)
    return Transform64(values.getDouble(0), values.getDouble(1), values.getDouble(2), values.getDouble(3),
        values.getDouble(4), values.getInt(5) != 0, values.getInt(6) != 0)
}

private fun p38MaskSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private fun p38MaskArray(values: IntArray) = JSONArray().also { array -> values.forEach { array.put(it) } }

private fun p38MaskAssertClaims(before: ResourceBudgetSnapshot, after: ResourceBudgetSnapshot, label: String) {
    check(after.reservedBytes == before.reservedBytes && after.reservations == before.reservations &&
        after.residentBytes == before.residentBytes && after.availableBytes == before.availableBytes) {
        "$label leaked managed resource claims: before=$before after=$after"
    }
}

private fun p38MaskCleanup(primary: Throwable?, report: JSONObject, vararg actions: () -> Unit) {
    var first = primary
    for (action in actions) try { action() } catch (cleanup: Throwable) {
        report.put("status", "FAIL").put("cleanupError", cleanup.stackTraceToString())
        if (first == null) first = cleanup else first.addSuppressed(cleanup)
    }
    if (primary == null) first?.let { throw it }
}
