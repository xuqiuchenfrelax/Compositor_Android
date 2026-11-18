package com.pojia.compositor.mobile

import android.content.Context
import android.app.Instrumentation
import android.content.Intent
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun recoverySmoke(context: Context): String {
    val directory = File(context.cacheDir, "recovery-smoke-${System.nanoTime()}").apply { check(mkdirs()) }
    val checkpoint = File(directory, "项目-🎨.checkpoint")
    fun image(editor: NativeEditor): ByteArray {
        val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
        check(editor.draw(64, 64, 0f, 0f, 64f, 64f, pixels))
        return ByteArray(pixels.capacity()).also { pixels.position(0); pixels.get(it) }
    }
    try {
        for ((width, height) in listOf(1 to 30_000, 30_000 to 1, 47 to 30_000)) {
            NativeEditor(64, addDefaultLayers = false).use { editor ->
                var notified = -1L
                editor.committed = { notified = it }
                editor.importPixels(width, height, ByteBuffer.allocateDirect(width * height * 4), fit = true)
                check(notified == editor.currentRevision() && notified == 1L)
                checkNotNull(editor.captureScene()).use { scene ->
                    val placement = scene.layerMetadata(0)
                    check(placement.pixelWidth == width && placement.pixelHeight == height)
                    check(kotlin.math.min(placement.transform.width, placement.transform.height) == 1.0)
                    check(kotlin.math.abs(placement.transform.width / placement.transform.height - width.toFloat() / height) < 0.001f)
                    check(kotlin.math.abs(placement.transform.x + placement.transform.width / 2 - 32f) < 0.001f &&
                        kotlin.math.abs(placement.transform.y + placement.transform.height / 2 - 32f) < 0.001f)
                }
                editor.undo(false)
                check(editor.state().layers.isEmpty() && notified == editor.currentRevision())
            }
        }
        var original: ByteArray
        var savedRevision: Long
        NativeEditor(64).use { editor ->
            val layer = editor.state().layers.last().id
            editor.startStroke(layer, 8f, false)
            editor.samples(doubleArrayOf(32.0, 32.0, 1.0, 0.0, 48.0, 32.0, 1.0, 0.1))
            try { editor.save(checkpoint); error("Active stroke was saved") }
            catch (error: CheckpointException) { check(error.status == 4) }
            editor.endStroke(false)
            original = image(editor)
            savedRevision = editor.save(checkpoint)
            check(savedRevision == editor.state().revision)
            editor.startStroke(layer, 20f, true)
            editor.samples(doubleArrayOf(32.0, 32.0, 1.0, 0.0))
            editor.endStroke(true)
            check(image(editor).contentEquals(original))
        }
        NativeEditor.load(checkpoint).use { restored ->
            check(restored.canvasWidth == 64 && restored.canvasHeight == 64)
            check(restored.state().revision == savedRevision && !restored.state().undo && !restored.state().redo)
            check(image(restored).contentEquals(original)) { "Checkpoint recovery changed exact pixels" }
        }
        val workspaceDirectory = File(directory, "workspace").apply { check(mkdirs()) }
        checkpoint.copyTo(File(workspaceDirectory, "current.checkpoint"))
        val opened = CountDownLatch(1)
        val editorReference = AtomicReference<NativeEditor>()
        val workspace = AndroidWorkspace(workspaceDirectory) { state ->
            state.editor?.let { editorReference.set(it); opened.countDown() }
        }
        check(opened.await(10, TimeUnit.SECONDS)) { "Workspace recovery did not open" }
        val editor = editorReference.get()
        val layer = editor.state().layers.last().id
        editor.startStroke(layer, 8f, false)
        editor.samples(doubleArrayOf(8.0, 8.0, 1.0, 0.0))
        editor.endStroke(false)
        val committed = image(editor)
        editor.startStroke(layer, 16f, true)
        editor.samples(doubleArrayOf(32.0, 32.0, 1.0, 0.0))
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        check(image(editor).contentEquals(committed)) { "Background flush did not cancel provisional stroke" }
        val taskStarted = CountDownLatch(1)
        val allowTask = CountDownLatch(1)
        workspace.runIo({ taskStarted.countDown(); check(allowTask.await(10, TimeUnit.SECONDS)); editor.state().revision }) { }
        check(taskStarted.await(10, TimeUnit.SECONDS))
        val closing = workspace.closeAsync()
        check(!closing.isDone) { "Workspace destroyed editor before queued IO finished" }
        allowTask.countDown(); closing.get(10, TimeUnit.SECONDS)
        check(editor.state().layers.isEmpty())
        val store = WorkspaceRecoveryStore(workspaceDirectory)
        NativeEditor.load(store.checkpoint(checkNotNull(store.read()))).use {
            check(image(it).contentEquals(committed)) { "Workspace final checkpoint changed committed pixels" }
        }
        val damagedDirectory = File(directory, "damaged").apply { check(mkdirs()) }
        val damaged = File(damagedDirectory, "current.checkpoint").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val failed = CountDownLatch(1)
        val fresh = CountDownLatch(1)
        val recovery = AndroidWorkspace(damagedDirectory) { state ->
            if (state.recoveryError != null) failed.countDown()
            if (state.editor != null) fresh.countDown()
        }
        check(failed.await(10, TimeUnit.SECONDS)) { "Damaged checkpoint was silently replaced" }
        check(damaged.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        recovery.startNewDocument()
        check(fresh.await(10, TimeUnit.SECONDS))
        recovery.closeAsync().get(10, TimeUnit.SECONDS)
        check(damagedDirectory.listFiles()!!.any { it.name.startsWith("rejected-") &&
            it.readBytes().contentEquals(byteArrayOf(1, 2, 3)) }) { "Explicit fresh document discarded damaged checkpoint" }
        return "Recovery passed: thin-source fitted import/notification/undo, UTF-8 supplementary path, exact pixels/revision, empty loaded history, active stroke rejection, background cancel/flush, queued IO lifetime, preserved damaged checkpoint"
    } finally { directory.deleteRecursively() }
}

internal fun activityRecoverySmoke(instrumentation: Instrumentation): String {
    val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val first = instrumentation.startActivitySync(intent) as MainActivity
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var editor = first.retainedEditorForTest()
    while (editor == null && System.nanoTime() < deadline) {
        Thread.sleep(20)
        instrumentation.waitForIdleSync()
        editor = first.retainedEditorForTest()
    }
    val retained = checkNotNull(editor) { "Activity recovery did not finish opening" }
    val original = retained.state()
    retained.addLayer()
    val edited = retained.state()
    check(edited.layers.size == original.layers.size + 1 && edited.undo)
    val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
    instrumentation.runOnMainSync { first.recreate() }
    val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
    instrumentation.removeMonitor(monitor)
    checkNotNull(recreated) { "Activity recreation did not finish" }
    instrumentation.waitForIdleSync()
    val after = recreated.retainedEditorForTest()
    check(after === retained) { "Activity recreation loaded a different native document" }
    check(after.state() == edited) { "Activity recreation changed revision or undo state" }
    after.undo(false)
    check(after.state().layers == original.layers) { "Undo history was lost on configuration recreation" }
    val workspace = recreated.retainedWorkspaceForTest()
    instrumentation.runOnMainSync { recreated.finish() }
    instrumentation.waitForIdleSync()
    workspace.closeAsync().get(10, TimeUnit.SECONDS)
    return "Activity recreation passed: retained native identity, exact revision/layers, usable in-memory undo, single workspace writer"
}
