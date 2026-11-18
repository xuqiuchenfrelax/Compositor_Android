package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.math.sin

/** Opt-in app/JNI production-stroke timing; semantic and retained-scene checks are outside timing. */
internal fun strokePipelineSmoke(instrumentation: Instrumentation, measured: Boolean, label: String): String {
    require(label.matches(Regex("[a-z0-9-]{1,40}")))
    val context = instrumentation.targetContext
    val report = JSONObject().put("label", label).put("measured", measured)
        .put("scope", "Ordinary NativeEditor locks/JNI, bounded begin, append64, commit; no rendering or UI latency")
        .put("warmup", if (measured) 3 else 0).put("iterations", if (measured) 10 else 1)
        .put("model", Build.MODEL).put("hardware", Build.HARDWARE).put("sdk", Build.VERSION.SDK_INT)
        .put("debuggable", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    val tileBuffer = ByteBuffer.allocateDirect(256 * 256 * 4)
    fun sceneHash(scene: NativeScene, identities: MutableMap<Long, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : java.io.OutputStream() { override fun write(value: Int) {} }
        val metadata = DataOutputStream(DigestOutputStream(sink, digest))
        val info = scene.metadata()
        metadata.writeLong(info[1]); metadata.writeLong(info[2]); metadata.writeLong(info[3])
        for (layerIndex in 0 until info[3].toInt()) {
            val layer = scene.layerMetadata(layerIndex)
            metadata.writeInt(layer.pixelWidth); metadata.writeInt(layer.pixelHeight)
            metadata.writeBoolean(layer.visible); metadata.writeInt(layer.blend); metadata.writeInt(layer.sampling)
            metadata.writeDouble(layer.opacity)
            layer.transform.numbers().forEach { metadata.writeDouble(it) }
            metadata.writeBoolean(layer.transform.flipX); metadata.writeBoolean(layer.transform.flipY)
            val raster = scene.rasterViewMetadata(layerIndex)
            listOf(raster.phaseX, raster.phaseY, raster.fillRed, raster.fillGreen, raster.fillBlue, raster.fillAlpha)
                .forEach { metadata.writeInt(it) }
            metadata.writeBoolean(raster.hasSampledBase)
            check(!raster.hasSampledBase) { "Benchmark fixture unexpectedly has a sampled base" }
            metadata.writeInt(layer.tileCount)
            for (index in 0 until layer.tileCount) {
                val tile = scene.tileViewMetadata(layerIndex, index)
                listOf(tile.x, tile.y, tile.width, tile.height, tile.sourceX, tile.sourceY,
                    tile.validX, tile.validY, tile.validWidth, tile.validHeight, tile.phaseX, tile.phaseY)
                    .forEach { metadata.writeInt(it) }
                tileBuffer.clear(); tileBuffer.limit(tile.width * tile.height * 4)
                scene.readTile(layerIndex, index, tile.width * 4, tileBuffer)
                val tileHash = hex(MessageDigest.getInstance("SHA-256").digestBuffer(tileBuffer))
                identities.put(tile.readResourceId, tileHash)?.let { previous ->
                    check(previous == tileHash) { "An immutable tile read ID changed bytes" }
                }
                digest.update(tileBuffer.asReadOnlyBuffer())
            }
        }
        metadata.flush()
        return hex(digest.digest())
    }
    val cases = JSONArray()
    for (shape in listOf("diagonal-pressure", "curved-pressure", "duplicate-pressure")) {
        val samples = Array(2000) { index ->
            val t = index.toFloat() / 1999f
            val motion = if (shape == "duplicate-pressure") (index / 2 * 2).toFloat() / 1999f else t
            val x = (20f + 3960f * motion).toDouble()
            val y = if (shape == "curved-pressure") 2000.0 + 1400.0 * sin(motion.toDouble() * Math.PI * 2)
                else (20f + 3960f * motion).toDouble()
            doubleArrayOf(x, y, (.3f + .7f * t).toDouble(), index.toDouble() / 240)
        }
        for (batchSize in listOf(1, 8)) {
            val packets = samples.asList().chunked(batchSize).map { group ->
                DoubleArray(group.size * 4) { group[it / 4][it % 4] }
            }
            val caseReport = JSONObject().put("shape", shape).put("batchSize", batchSize)
                .put("samples", samples.size).put("documentSize", 4000).put("diameter", 48)
                .put("transientLimitBytes", StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES)
            val inputDigest = MessageDigest.getInstance("SHA-256")
            val inputBits = ByteBuffer.allocate(8)
            packets.forEach { packet -> packet.forEach { value ->
                inputBits.clear(); inputBits.putDouble(value); inputDigest.update(inputBits.array())
            } }
            caseReport.put("inputSHA256", hex(inputDigest.digest()))
            val trials = JSONArray()
            val warmup = if (measured) 3 else 0
            val iterations = if (measured) 10 else 1
            // Storage and input arrays are allocated before any timed operation.
            val appendNs = Array(iterations) { LongArray(packets.size) }
            var expectedHash: String? = null
            val runtimeBefore = Debug.getRuntimeStats()
            for (iteration in -warmup until iterations) {
                NativeEditor(4000, addDefaultLayers = false).use { editor ->
                    editor.addLayer()
                    val layer = editor.state().layers.single().id
                    val record = measured && iteration >= 0
                    val started = if (record) System.nanoTime() else 0
                    editor.startStroke(layer, 48f, false)
                    val begun = if (record) System.nanoTime() else 0
                    for (index in packets.indices) {
                        val before = if (record) System.nanoTime() else 0
                        editor.samples(packets[index])
                        if (record) appendNs[iteration][index] = System.nanoTime() - before
                    }
                    val appended = if (record) System.nanoTime() else 0
                    editor.endStroke(false)
                    val finished = if (record) System.nanoTime() else 0
                    if (iteration >= 0) {
                        val identities = HashMap<Long, String>()
                        val semanticHash = checkNotNull(editor.captureScene()).use { retained ->
                            val before = sceneHash(retained, identities)
                            editor.startStroke(layer, 4f, true)
                            editor.samples(doubleArrayOf(samples[0][0], samples[0][1], 1.0, 0.0))
                            editor.endStroke(false)
                            check(sceneHash(retained, identities) == before) { "Retained scene changed after a later stroke" }
                            checkNotNull(editor.captureScene()).use { changed ->
                                check(sceneHash(changed, identities) != before) { "Later erase did not exercise retained ownership" }
                            }
                            before
                        }
                        expectedHash?.let { check(it == semanticHash) { "Identical fresh stroke changed semantic bytes" } }
                        expectedHash = semanticHash
                        trials.put(JSONObject().put("semanticSHA256", semanticHash)
                            .put("beginNs", begun - started).put("appendNs", appended - begun)
                            .put("commitNs", finished - appended).put("totalNs", finished - started)
                            .put("chronologicalAppendNs", JSONArray(appendNs[iteration].toList())))
                    }
                }
            }
            caseReport.put("runtimeBefore", JSONObject(runtimeBefore)).put("runtimeAfter", JSONObject(Debug.getRuntimeStats()))
                .put("trials", trials).put("semanticHashScope", "Source tile read bytes, dimensions, geometry, fill and phase; excludes random UUID/numeric resource IDs; each trial checks retained scene and read-ID immutability")
            cases.put(caseReport)
        }
    }
    report.put("cases", cases)
    val apk = File(context.applicationInfo.sourceDir)
    val apkDigest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) { val count = input.read(bytes); if (count < 0) break; apkDigest.update(bytes, 0, count) }
    }
    report.put("targetApkSHA256", hex(apkDigest.digest()))
    val directory = checkNotNull(context.getExternalFilesDir("stroke-pipeline"))
    val output = File(directory, "$label.json")
    check(!output.exists()) { "Benchmark evidence already exists: $output" }
    output.outputStream().use { stream -> stream.write(report.toString().toByteArray()); stream.fd.sync() }
    return "Production bounded/append64/commit ${if (measured) "timing" else "validation"} passed; six cases, streamed semantic bytes and per-trial retained ownership checks; evidence=$output"
}

private fun MessageDigest.digestBuffer(buffer: ByteBuffer): ByteArray {
    update(buffer.asReadOnlyBuffer())
    return digest()
}
