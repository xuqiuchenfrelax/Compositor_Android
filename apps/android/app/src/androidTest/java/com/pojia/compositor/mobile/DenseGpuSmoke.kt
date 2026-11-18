package com.pojia.compositor.mobile

import android.opengl.GLES30
import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs

/** Runs in the caller's actual 1024² EGL pbuffer; no display/device configuration changes. */
internal fun denseGpuSmoke(recreateContext: () -> Unit): String = NativeEditor(addDefaultLayers = false).use { editor ->
    val source = ByteBuffer.allocateDirect(2048 * 2048 * 4)
    // A high-frequency source whose independently averaged 2×2 box is [128,120,140,255].
    val samples = Array(4) { IntArray(4) }
    for (y in 0 until 2048) for (x in 0 until 2048) {
        val red = if (x % 2 == 0) 240 else 16
        val green = if (y % 2 == 0) 200 else 40
        val blue = if ((x + y) % 2 == 0) 220 else 60
        source.put(red.toByte()).put(green.toByte()).put(blue.toByte()).put(255.toByte())
        if (x < 2 && y < 2) samples[y * 2 + x] = intArrayOf(red, green, blue, 255)
    }
    source.rewind()
    repeat(10) { editor.importPixels(2048, 2048, source) }
    val nativeBytes = editor.state().bytes
    check(editor.state().layers.size == 10)
    check(nativeBytes in (160L * 1024 * 1024)..(161L * 1024 * 1024)) { "Fixture did not retain ten dense 2048² layers" }
    // This oracle reads the input pattern, never calls the core LOD reader.
    val expected = IntArray(4) { channel -> (samples.sumOf { it[channel] } + 2) / 4 }
    val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
    renderer.viewport = Viewport(0f, 0f, 2048f, 2048f)
    renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1024, 1024)
    renderer.onDrawFrame(null); GLES30.glFinish()
    check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
    check(renderer.lodUploads[1] == 640L) { "Fit view did not select LOD 1 for all ten dense layers: ${renderer.lodUploads.toList()}" }
    check(renderer.cacheBytes == 40 * 1024 * 1024) { "LOD fit cache should occupy 40 MiB" }
    check(renderer.surfaceBytes == 4 * 1024 * 1024 && renderer.totalGpuBytes == 44 * 1024 * 1024)
    val actual = ByteBuffer.allocateDirect(1024 * 1024 * 4)
    fun verifyPixels() {
        GLES30.glReadPixels(0, 0, 1024, 1024, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        for (pixel in 0 until 1024 * 1024) for (channel in 0..3) {
            val value = actual.get(pixel * 4 + channel).toInt() and 255
            check(abs(value - expected[channel]) <= 1) { "Independent LOD oracle mismatch at $pixel channel $channel: $value != ${expected[channel]}" }
        }
    }
    verifyPixels()
    check(renderer.pageCount == 10 && renderer.lastFrameDraws == 11) { "Dense tiles were not batched into ten layer/page draws" }
    val warmDraws = renderer.lastFrameDraws
    val warmPages = renderer.pageCount
    val warmUploads = renderer.uploadCount
    val warmEvictions = renderer.evictionCount
    val warmAllocations = renderer.textureAllocations
    // Keep the P12 workload/quantiles, but retain chronological wall times instead of only a sorted total.
    // onDrawFrame contains CPU work and GLES calls that can themselves block; it is not pure CPU time.
    val drawWallTimes = LongArray(30)
    val rendererWallTimes = LongArray(30)
    val finishWaitTimes = LongArray(30)
    val totalTimes = LongArray(30)
    val runtimeKeys = listOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.bytes-allocated")
    fun runtimeStats() = runtimeKeys.associateWith { key -> runCatching { Debug.getRuntimeStat(key) }.getOrNull() }
    val runtimeBefore = runtimeStats()
    for (frame in totalTimes.indices) {
        val start = System.nanoTime()
        renderer.onDrawFrame(null)
        val submitted = System.nanoTime()
        GLES30.glFinish()
        val finished = System.nanoTime()
        drawWallTimes[frame] = submitted - start
        rendererWallTimes[frame] = renderer.lastFrameNanos
        finishWaitTimes[frame] = finished - submitted
        totalTimes[frame] = finished - start
    }
    val runtimeAfter = runtimeStats()
    val times = totalTimes.sorted()
    val timingDetails = JSONObject().put("scope", "30 back-to-back EGL pbuffer draws; not input or display latency")
        .put("columns", JSONArray(listOf("frame", "drawWallNs", "rendererWallNs", "finishWaitWallNs", "totalWallNs")))
        .put("chronologicalSamples", JSONArray().also { rows ->
            for (frame in totalTimes.indices) rows.put(JSONArray(listOf(frame.toLong(), drawWallTimes[frame],
                rendererWallTimes[frame], finishWaitTimes[frame], totalTimes[frame])))
        }).put("runtimeStatsBefore", JSONObject(runtimeBefore)).put("runtimeStatsAfter", JSONObject(runtimeAfter))
        .put("runtimeStatsScope", "Optional ART counters around entire batch; no per-frame attribution or causality")
    check(renderer.uploadCount == warmUploads && renderer.evictionCount == warmEvictions && renderer.textureAllocations == warmAllocations) {
        "Dense fit view thrashed or allocated textures in warm frames"
    }
    renderer.setTextureBudget(16 * 1024 * 1024)
    renderer.onDrawFrame(null); GLES30.glFinish(); verifyPixels()
    check(renderer.cacheBytes <= 16 * 1024 * 1024 && renderer.lodUploads[2] == 640L) { "Pressure budget did not select a coarser resident mip" }
    check(renderer.totalGpuBytes <= 16 * 1024 * 1024) { "Composition surface exceeded the pressure budget" }
    val pressureUploads = renderer.uploadCount; val pressureEvictions = renderer.evictionCount
    repeat(5) { renderer.onDrawFrame(null); GLES30.glFinish() }
    check(renderer.uploadCount == pressureUploads && renderer.evictionCount == pressureEvictions) { "Pressure view thrashed the cache" }
    renderer.setTextureBudget(0)
    renderer.onDrawFrame(null); GLES30.glFinish()
    check(renderer.totalGpuBytes == 0) { "Hidden app did not release its tile and composition textures" }
    renderer.setTextureBudget(64 * 1024 * 1024)
    renderer.onDrawFrame(null); GLES30.glFinish(); verifyPixels()
    recreateContext()
    renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1024, 1024)
    val beforeContextUploads = renderer.uploadCount
    renderer.onDrawFrame(null); GLES30.glFinish(); verifyPixels()
    check(renderer.uploadCount == beforeContextUploads + 640 && renderer.pageCount == 10) { "Recreated EGL context reused stale GPU names" }
    renderer.onDrawFrame(null)
    check(renderer.lastFrameUploads == 0L && renderer.lastFrameAllocations == 0L)
    // A 1:1 window sees one source tile per layer, not all 640 tiles of the document.
    recreateContext()
    val zoom = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
    zoom.viewport = Viewport(256f, 256f, 256f, 256f)
    zoom.onSurfaceCreated(null, null); zoom.onSurfaceChanged(null, 256, 256)
    zoom.onDrawFrame(null); GLES30.glFinish()
    check(zoom.lodUploads[0] == 10L && zoom.lodUploads.drop(1).sum() == 0L) { "Offscreen source tiles forced a blurry zoom mip: ${zoom.lodUploads.toList()}" }
    check(zoom.pageCount == 1 && zoom.cacheBytes == 16 * 1024 * 1024)
    val zoomPixels = ByteBuffer.allocateDirect(256 * 256 * 4)
    GLES30.glReadPixels(0, 0, 256, 256, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, zoomPixels)
    for (y in 0 until 256) for (x in 0 until 256) for (channel in 0..3) {
        val value = zoomPixels.get(((255 - y) * 256 + x) * 4 + channel).toInt() and 255
        val sample = samples[(y % 2) * 2 + x % 2][channel]
        check(abs(value - sample) <= 1) { "1:1 zoom source oracle mismatch at $x,$y channel $channel: $value != $sample" }
    }
    val zoomUploads = zoom.uploadCount; val zoomEvictions = zoom.evictionCount
    repeat(5) { zoom.onDrawFrame(null); GLES30.glFinish() }
    check(zoom.uploadCount == zoomUploads && zoom.evictionCount == zoomEvictions)
    "Dense GPU: 10×2048² RGBA8 layers, CPU raster=${nativeBytes / 1048576.0}MiB, " +
        "1024² LOD1 independent-box oracle ±1byte, tileResident40MiB+composition4MiB, " +
        "warm submit+glFinish median=${times[15] / 1e6}ms p95=${times[28] / 1e6}ms, " +
        "warmUploads=0 warmEvictions=0 warmPageAllocations=0, draws=$warmDraws pages=$warmPages, cachePeak=${renderer.peakTextureBytes / 1048576.0}MiB, " +
        "uploaded=${renderer.uploadedBytes / 1048576.0}MiB, allocations=${renderer.textureAllocations}, " +
        "16MiB pressure→LOD2 resident10MiB stable, hidden→0MiB, restore/EGLcontext recreation→pixels matched, " +
        "256² 1:1 zoom→10 LOD0 uploads/one16MiB page, source oracle±1byte, warmUploads=0 warmEvictions=0\n" +
        "DenseTimingDetails=$timingDetails"
}
