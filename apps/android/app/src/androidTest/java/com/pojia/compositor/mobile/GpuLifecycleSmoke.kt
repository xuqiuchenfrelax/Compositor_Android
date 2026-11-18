package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.ComponentCallbacks2
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Exercises GLSurfaceView's real paused queue; a pbuffer always has a current context. */
internal fun gpuLifecycleSmoke(instrumentation: Instrumentation): String {
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    var canvas: CanvasView? = null
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (canvas == null && System.nanoTime() < deadline) {
        instrumentation.runOnMainSync { canvas = findCanvas(activity.window.decorView) }
        if (canvas == null) Thread.sleep(20)
    }
    val previousView = checkNotNull(canvas) { "Lifecycle fixture did not attach the actual canvas" }
    val previousEditor = checkNotNull(activity.retainedEditorForTest())
    val previousState = previousEditor.state()
    fun pixels(editor: NativeEditor, size: Int): ByteArray {
        val buffer = ByteBuffer.allocateDirect(size * size * 4)
        check(editor.draw(size, size, 0f, 0f, editor.canvasWidth.toFloat(), editor.canvasHeight.toFloat(), buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val previousPixels = pixels(previousEditor, 8)
    val previousTiles = checkNotNull(previousEditor.captureScene()).use { scene ->
        previousState.layers.indices.sumOf { scene.layerMetadata(it).tileCount }
    }
    val fixtureBytes = ByteArray(8 * 8 * 4) { index -> byteArrayOf(96, -112, -64, -1)[index % 4] }
    val fixtureId = UUID.randomUUID().toString()
    // A valid recovered document can contain no tiles; the lifecycle oracle requires a real texture.
    val workspace = activity.retainedWorkspaceForTest()
    check(workspace.installCandidate("gpu-lifecycle-$fixtureId", DirtyDisposition.DISCARD) {
        NativeEditor(8, addDefaultLayers = false).also { fixture ->
            val buffer = ByteBuffer.allocateDirect(fixtureBytes.size).apply { put(fixtureBytes); rewind() }
            fixture.importPixels(8, 8, buffer)
        }
    }.result.get(10, TimeUnit.SECONDS).installed)
    val editor = checkNotNull(workspace.snapshot().editor)
    check(editor !== previousEditor && pixels(editor, 8).contentEquals(fixtureBytes))
    val fixtureTiles = checkNotNull(editor.captureScene()).use { scene ->
        check(editor.state().layers.size == 1)
        scene.layerMetadata(0).tileCount.also { check(it > 0) { "Opaque lifecycle fixture has no native tiles" } }
    }
    canvas = null
    val bindDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (canvas == null && System.nanoTime() < bindDeadline) {
        instrumentation.runOnMainSync {
            val candidate = findCanvas(activity.window.decorView)
            if (candidate != null && candidate !== previousView && candidate.isAttachedToWindow &&
                candidate.holder.surface.isValid && candidate.width > 0 && candidate.height > 0 &&
                activity.retainedEditorForTest() === editor) canvas = candidate
        }
        if (canvas == null) Thread.sleep(20)
    }
    val view = checkNotNull(canvas) { "Lifecycle replacement did not bind a new actual canvas" }
    val evidence = File(instrumentation.targetContext.cacheDir, "p21-gpu-lifecycle-fixture-$fixtureId.json")
    evidence.writeText(JSONObject().put("originalDocumentUuid", previousState.document.uuid)
        .put("originalPixel8Sha256", hash(previousPixels)).put("originalPixelsAllZero", previousPixels.all { it == 0.toByte() })
        .put("originalNativeTileCount", previousTiles).put("fixturePixel8Sha256", hash(fixtureBytes))
        .put("fixtureRgba", "96,144,192,255").put("fixtureNativeTileCount", fixtureTiles)
        .put("newActualCanvasBound", true).put("fixtureDocumentUuid", editor.state().document.uuid).toString(2))
    fun image(): ByteArray {
        val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
        check(editor.draw(64, 64, 0f, 0f, editor.canvasWidth.toFloat(), editor.canvasHeight.toFloat(), pixels))
        return ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
    }
    fun stats(): GpuStats {
        val latch = CountDownLatch(1)
        var result: GpuStats? = null
        view.debugGpuStats { result = it; latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "Paused GL queue stopped executing" }
        return checkNotNull(result)
    }
    fun awaitStats(predicate: (GpuStats) -> Boolean): GpuStats {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var value = stats()
        while (!predicate(value) && System.nanoTime() < limit) { Thread.sleep(20); value = stats() }
        check(predicate(value)) { "GPU lifecycle condition timed out: $value" }
        return value
    }
    val original = image(); val revision = editor.state().revision
    try {
        instrumentation.runOnMainSync { view.requestRender() }
        val warm = awaitStats { it.bytes > 0 && it.currentContext }
        instrumentation.runOnMainSync { view.onPause() }
        val paused = stats()
        check(!paused.currentContext) { "GLSurfaceView retained a current EGL context while paused" }
        instrumentation.runOnMainSync {
            view.handleMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            view.restoreTextureBudget()
        }
        val pending = stats()
        check(!pending.currentContext && pending.bytes == paused.bytes && pending.surfaceBytes == paused.surfaceBytes && pending.deletes == paused.deletes) {
            "Paused trim discarded accounting or issued contextless GL deletes: $paused → $pending"
        }
        instrumentation.runOnMainSync { view.onResume(); view.requestRender() }
        val restored = awaitStats { it.currentContext && it.bytes > 0 && it.deletes > warm.deletes && it.uploads > warm.uploads }
        check(image().contentEquals(original) && editor.state().revision == revision) { "GPU trim changed document pixels or history" }
        return "GLSurfaceView lifecycle passed: explicit opaque 8×8 RGBA fixture bound to a new actual canvas, native tiles=$fixtureTiles; pause→no-current-context trim+restore preserved accounting; resume applied deferred deletes+reuploads, pixels/revision unchanged (tile=${restored.bytes / 1048576.0}MiB+surface=${restored.surfaceBytes / 1048576.0}MiB). Fixture=${evidence.name}; recovered-before-fixture zero=${previousPixels.all { it == 0.toByte() }} tiles=$previousTiles"
    } finally {
        instrumentation.runOnMainSync { view.onResume(); activity.finish() }
        instrumentation.waitForIdleSync()
        activity.retainedWorkspaceForTest().closeAsync().get(10, TimeUnit.SECONDS)
    }
}
