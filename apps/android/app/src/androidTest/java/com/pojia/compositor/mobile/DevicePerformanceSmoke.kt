package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.opengl.GLES30
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.sin

/** Isolated documents and the shipping renderer. Pbuffer completion is not display/input latency. */
internal fun devicePerformanceSmoke(instrumentation: Instrumentation, label: String, sustainedSeconds: Int, denseLayers: Int): String {
    require(label.matches(Regex("[a-z0-9-]{1,40}")) && sustainedSeconds in 0..1200 && denseLayers in 1..8)
    val context = instrumentation.targetContext
    val directory = File(context.cacheDir, "device-performance/$label")
    check(!directory.exists() && directory.mkdirs()) { "Performance evidence must have a unique label" }
    val reportFile = File(directory, "result.json")
    val samplesFile = File(directory, "frames.jsonl")
    val report = JSONObject().put("label", label).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
        .put("debuggable", context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        .put("scope", "Shipping JNI/GLES in a 1024x1024 EGL pbuffer; isolated dense2048x$denseLayers document. No physical display, pointer latency, stylus or full product performance approval.")
        .put("sustainedRequestedSeconds", sustainedSeconds)
    fun publish(status: String, failure: Throwable? = null) {
        report.put("status", status)
        failure?.let { report.put("error", it.stackTraceToString()) }
        val temporary = File(directory, "result.tmp")
        temporary.writeText(report.toString(2)); check(temporary.renameTo(reportFile))
    }
    fun memory(phase: String): JSONObject {
        val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = File("/proc/self/status").readLines().filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
        return JSONObject().put("phase", phase).put("elapsedMs", SystemClock.elapsedRealtime())
            .put("pssKiB", info.totalPss).put("privateDirtyKiB", info.totalPrivateDirty)
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("procStatus", JSONArray(status)).put("memoryStats", JSONObject(info.memoryStats))
            .put("thermalStatus", context.getSystemService(PowerManager::class.java).currentThermalStatus)
            .put("batteryTenthsCelsius", battery?.getIntExtra("temperature", -1))
    }
    fun quantiles(values: List<Long>): JSONObject {
        check(values.isNotEmpty()); val ordered = values.sorted()
        fun at(q: Double) = ordered[(ceil(q * ordered.size).toInt() - 1).coerceIn(0, ordered.lastIndex)] / 1e6
        return JSONObject().put("n", values.size).put("p50Ms", at(.5)).put("p95Ms", at(.95))
            .put("p99Ms", at(.99)).put("maxMs", ordered.last() / 1e6)
            .put("over16_7Ms", values.count { it > 16_700_000 }).put("over33_3Ms", values.count { it > 33_300_000 })
    }
    val snapshots = JSONArray(); report.put("memorySamples", snapshots)
    val ledger = ImageIoAdmission.ledger
    val claimsBefore = ledger.snapshot()
    var editor: NativeEditor? = null
    var renderer: GpuDisplayRenderer? = null
    val checkpoint = File(directory, "owned-document.checkpoint")
    fun pixels(owner: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(128 * 128 * 4)
        check(owner.draw(128, 128, 0.0, 0.0, 2048.0, 2048.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    try {
        publish("setup"); snapshots.put(memory("before-fixture"))
        val fixture = NativeEditor(2048, addDefaultLayers = false); editor = fixture
        val source = ByteBuffer.allocateDirect(2048 * 2048 * 4)
        repeat(2048 * 2048) { p ->
            // Coarse blocks keep the center witness constant across the GPU's 2x2 LOD footprint.
            source.put((40 + ((p % 2048) / 64 + (p / 2048) / 64) % 127).toByte())
                .put(90).put(170.toByte()).put(255.toByte())
        }
        val layers = (0 until denseLayers).map { source.rewind(); fixture.importPixels(2048, 2048, source) }
        check(fixture.state().layers.size == denseLayers && fixture.state().bytes >= denseLayers * 16L * 1024 * 1024)
        val retained = fixture.retainedMemory()
        val beforeDraw = ledger.snapshot()
        report.put("beforeFirstDraw", JSONObject().put("tileBytes", retained.tileBytes)
            .put("stateBytes", retained.stateBytes).put("imageCatalogBytes", retained.imageCatalogBytes)
            .put("totalRetainedBytes", retained.totalBytes).put("installedDocumentBytes", fixture.installedDocumentBytes())
            .put("ledgerLimit", beforeDraw.limitBytes).put("ledgerReserved", beforeDraw.reservedBytes)
            .put("ledgerAvailable", beforeDraw.availableBytes).put("ledgerReservations", beforeDraw.reservations))
        snapshots.put(memory("fixture-before-first-draw")); publish("fixture-ready")
        val view = GpuDisplayRenderer(fixture) { error(it) }; renderer = view
        view.viewport = Viewport(0.0, 0.0, 2048.0, 2048.0)
        view.onSurfaceCreated(null, null); view.onSurfaceChanged(null, 1024, 1024)
        val setupStart = System.nanoTime(); view.onDrawFrame(null); GLES30.glFinish()
        report.put("coldFirstDrawMs", (System.nanoTime() - setupStart) / 1e6)
        check(view.fallbackBytes == 0 && GLES30.glGetError() == GLES30.GL_NO_ERROR)
        repeat(12) { view.onDrawFrame(null); GLES30.glFinish() }
        val original = pixels(fixture)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // Raw glReadPixels row 512 maps to top-down row 511, centered at document (1023, 1023).
        val fullGpu = ByteBuffer.allocateDirect(1024 * 1024 * 4)
        check(view.readComposition(fullGpu))
        val cpuProbe = ByteBuffer.allocateDirect(4)
        check(fixture.draw(1, 1, 1022.0, 1022.0, 2.0, 2.0, cpuProbe))
        report.put("centerWitness", JSONObject().put("topDownPixel", "511,511").put("documentCenter", "1023,1023").put("allowedDifference", 0)
            .put("gpu", JSONArray((0..3).map { fullGpu.get((512 * 1024 + 511) * 4 + it).toInt() and 255 }))
            .put("cpu", JSONArray((0..3).map { cpuProbe.get(it).toInt() and 255 })))
        repeat(4) { c -> check(kotlin.math.abs((fullGpu.get((512 * 1024 + 511) * 4 + c).toInt() and 255) -
            (cpuProbe.get(c).toInt() and 255)) == 0) { "GPU workload did not match its CPU center witness" } }
        report.put("fixture", JSONObject().put("width", 2048).put("height", 2048).put("denseLayers", denseLayers)
            .put("retainedBytes", fixture.state().bytes).put("viewport", "1024x1024, cyclic pan ±5%, zoom 92–108%, LOD enabled")
            .put("nativeLedgerLimitBytes", ledger.limitBytes).put("frameColumns", JSONArray(listOf("index", "elapsedNs", "drawNs", "finishNs", "totalNs", "uploads", "allocations", "evictions"))))
        val frameTimes = ArrayList<Long>(); val firstMinute = ArrayList<Long>(); val lastMinute = ArrayList<Long>()
        val started = System.nanoTime(); val duration = sustainedSeconds * 1_000_000_000L
        var nextMemory = started; var frame = 0
        val uploadsBefore = view.uploadCount; val allocationsBefore = view.textureAllocations
        val runtimeBefore = Debug.getRuntimeStats(); var gpuPeak = view.totalGpuBytes
        samplesFile.bufferedWriter().use { writer ->
            do {
                val frameStart = System.nanoTime(); val elapsed = frameStart - started
                val phase = frame % 120 / 120.0 * 2 * Math.PI
                val extent = 2048.0 * (1 + .08 * sin(phase)); val offset = 102.4 * sin(phase)
                view.viewport = Viewport((2048 - extent) / 2 + offset, (2048 - extent) / 2, extent, extent)
                view.onDrawFrame(null); val submitted = System.nanoTime(); GLES30.glFinish(); val finished = System.nanoTime()
                check(view.fallbackBytes == 0 && GLES30.glGetError() == GLES30.GL_NO_ERROR)
                val total = finished - frameStart; frameTimes.add(total)
                if (elapsed < 60_000_000_000L) firstMinute.add(total)
                if (duration == 0L || elapsed >= duration - 60_000_000_000L) lastMinute.add(total)
                gpuPeak = maxOf(gpuPeak, view.totalGpuBytes)
                writer.write(JSONArray(listOf(frame.toLong(), elapsed, submitted - frameStart, finished - submitted,
                    total, view.lastFrameUploads, view.lastFrameAllocations, view.lastFrameEvictions)).toString()); writer.newLine()
                if (frameStart >= nextMemory) {
                    snapshots.put(memory("pan-zoom-$frame")); nextMemory = frameStart + 30_000_000_000L
                    report.put("completedFrames", frame).put("sustainedElapsedSeconds", elapsed / 1e9)
                    writer.flush(); publish("rendering")
                }
                frame++
                if (sustainedSeconds > 0) LockSupport.parkNanos((16_666_667L - (System.nanoTime() - frameStart)).coerceAtLeast(0))
            } while (if (sustainedSeconds > 0) System.nanoTime() - started < duration else frame < 120)
        }
        report.put("render", quantiles(frameTimes)).put("firstMinuteRender", quantiles(firstMinute))
            .put("lastMinuteRender", quantiles(lastMinute)).put("renderElapsedSeconds", (System.nanoTime() - started) / 1e9)
            .put("gpuPeakAccountedBytes", gpuPeak).put("panZoomUploadDelta", view.uploadCount - uploadsBefore)
            .put("panZoomTextureAllocationDelta", view.textureAllocations - allocationsBefore)
            .put("runtimeBefore", JSONObject(runtimeBefore)).put("runtimeAfter", JSONObject(Debug.getRuntimeStats()))
        view.viewport = Viewport(0.0, 0.0, 2048.0, 2048.0)
        val appendTimes = ArrayList<Long>(); val appendVisibleTimes = ArrayList<Long>(); val commitTimes = ArrayList<Long>()
        val settings = StrokeSettings(diameter = 32f, hardness = .65f, opacity = .8f, red = .95f, green = .1f, blue = .2f)
        repeat(12) { iteration ->
            val before = fixture.currentRevision()
            fixture.startStroke(layers.last(), settings)
            repeat(4) { batch ->
                val points = DoubleArray(8 * 4)
                repeat(8) { i -> val n = batch * 8 + i; points[i * 4] = 900.0 + n * 7
                    points[i * 4 + 1] = 1024.0 + 10 * sin(n * .25); points[i * 4 + 2] = 1.0; points[i * 4 + 3] = n * .008 }
                val start = System.nanoTime(); fixture.samples(points); val appended = System.nanoTime()
                view.onDrawFrame(null); GLES30.glFinish(); val visible = System.nanoTime()
                if (iteration >= 2) { appendTimes.add(appended - start); appendVisibleTimes.add(visible - start) }
            }
            val start = System.nanoTime(); fixture.endStroke(false); val end = System.nanoTime()
            check(fixture.currentRevision() == before + 1)
            val changed = pixels(fixture); check(!changed.contentEquals(original))
            fixture.undo(false); check(pixels(fixture).contentEquals(original))
            fixture.undo(true); check(pixels(fixture).contentEquals(changed)); fixture.undo(false)
            if (iteration >= 2) commitTimes.add(end - start)
        }
        report.put("appendBatchOf8", quantiles(appendTimes)).put("appendThroughGpuFinish", quantiles(appendVisibleTimes))
            .put("commit", quantiles(commitTimes)).put("strokeScope", "Two warmups, ten32-sample strokes, four8-sample JNI batches; preview GPU completion excludes View/OS/display latency; each commit/Undo/Redo checked")
        snapshots.put(memory("after-strokes"))
        val saveStarted = System.nanoTime(); fixture.save(checkpoint)
        report.put("checkpointSaveMs", (System.nanoTime() - saveStarted) / 1e6).put("checkpointBytes", checkpoint.length())
            .put("checkpointRenderSHA256", sha(original))
        fixture.close(); editor = null; view.onDrawFrame(null); check(view.totalGpuBytes == 0)
        view.retireSceneAdmission(); renderer = null
        val reopenStarted = System.nanoTime()
        NativeEditor.load(checkpoint).use { reopened ->
            report.put("checkpointReopenMs", (System.nanoTime() - reopenStarted) / 1e6)
            check(pixels(reopened).contentEquals(original) && reopened.state().layers.size == denseLayers)
        }
        check(ledger.snapshot().reservedBytes == claimsBefore.reservedBytes && ledger.snapshot().reservations == claimsBefore.reservations)
        report.put("claimsReturned", true).put("checkpointReopenPixelsExact", true)
        snapshots.put(memory("after-close")); publish("complete")
        return "Actual JNI/GLES dense2048x$denseLayers baseline complete; ${frameTimes.size} pbuffer frames, preview/commit/UndoRedo and save/reopen verified; $reportFile"
    } catch (error: Throwable) { publish("failed", error); throw error }
    finally {
        try { editor?.let { it.endStroke(true); it.close() } }
        finally { renderer?.let { view -> try { view.onDrawFrame(null) } finally { view.retireSceneAdmission() } }; checkpoint.delete() }
    }
}
