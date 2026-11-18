package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real JNI transport and immutable capture semantics; deliberately does not change workspace dirty policy. */
internal fun contentIdentitySmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "content-identity-${UUID.randomUUID()}").apply { check(mkdir()) }
    val editor = NativeEditor(8, addDefaultLayers = false)
    val checkpoints = mutableListOf<NativeCheckpointSnapshot>()
    var project: NativeProjectSnapshot? = null
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val outcome = AtomicReference<CheckpointWriteResult?>()
    val failure = AtomicReference<Throwable?>()
    var writer: Thread? = null
    fun image(red: Byte, green: Byte): ByteBuffer = ByteBuffer.allocateDirect(4).apply {
        put(byteArrayOf(red, green, 0, -1)); rewind()
    }
    fun closed(action: () -> Unit) {
        check(runCatching(action).exceptionOrNull() is IllegalStateException) { "Closed owner exposed a native handle" }
    }
    try {
        check(editor.currentContentIdentity() == 0L)
        val a = editor.importPixels(1, 1, image(32, 64))
        val b = editor.importPixels(1, 1, image(96, 16))
        editor.renameLayer(a, "Saved layer")
        val saved = editor.currentContentIdentity()
        check(saved > 0L)
        val revision = editor.currentRevision()
        val checkpoint = editor.captureCheckpointSnapshot().also(checkpoints::add)
        val projectSnapshot = editor.captureProjectSnapshot().also { project = it }
        check(checkpoint.capturedContentIdentity == saved && projectSnapshot.capturedContentIdentity == saved)
        val retained = editor.resourceLedger.snapshot()
        repeat(1_000) {
            check(editor.currentContentIdentity() == saved)
            check(checkpoint.capturedContentIdentity == saved && projectSnapshot.capturedContentIdentity == saved)
        }
        check(editor.resourceLedger.snapshot() == retained) { "Scalar identity reads changed managed reservations" }

        // b is active after import. Selecting a changes revision without changing committed content.
        editor.selectLayer(a)
        check(editor.currentRevision() > revision && editor.currentContentIdentity() == saved)
        editor.selectLayer(b)
        check(editor.currentContentIdentity() == saved)
        editor.renameLayer(a, "Changed layer")
        val changed = editor.currentContentIdentity()
        check(changed != saved)
        val changedRevision = editor.currentRevision()
        editor.undo(false)
        check(editor.currentRevision() > changedRevision && editor.currentContentIdentity() == saved)
        editor.undo(true)
        check(editor.currentContentIdentity() == changed)
        editor.undo(false)
        check(editor.currentContentIdentity() == saved)
        editor.renameLayer(a, "Branched layer")
        val branched = editor.currentContentIdentity()
        check(branched != saved && branched != changed)
        check(checkpoint.capturedContentIdentity == saved && projectSnapshot.capturedContentIdentity == saved)

        // Hold an actual admitted writer before JNI, edit the document, then complete the old snapshot write.
        val admission = CheckpointAdmission.forTest(editor.resourceLedger, beforeWrite = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Late snapshot writer was not released" }
        })
        val late = editor.captureCheckpointSnapshot(admission).also(checkpoints::add)
        val lateRevision = late.capturedRevision
        check(late.capturedContentIdentity == branched)
        val lateFile = File(root, "late.checkpoint")
        writer = Thread({
            try { outcome.set(late.write(lateFile)) } catch (error: Throwable) { failure.set(error) }
        }, "content-identity-writer").apply { start() }
        check(entered.await(10, TimeUnit.SECONDS)) { "Checkpoint writer did not acquire scratch" }
        editor.renameLayer(a, "Edited while captured writer waited")
        val current = editor.currentContentIdentity()
        check(current != branched && late.capturedContentIdentity == branched)
        release.countDown()
        writer.join(10_000)
        check(!writer.isAlive) { "Checkpoint writer did not finish" }
        failure.get()?.let { throw it }
        val result = checkNotNull(outcome.get())
        check(result.successful && result.capturedRevision == lateRevision)
        check(lateFile.length() == late.serializedBytes)
        check(late.capturedContentIdentity == branched && editor.currentContentIdentity() == current)
        // Checkpoint loading deliberately creates a new zero-based identity domain, not a persisted saved tag.
        NativeEditor.load(lateFile).use { loaded ->
            check(loaded.currentContentIdentity() == 0L)
            check(loaded.state().layers.first { it.id == a }.name == "Branched layer")
        }

        editor.close()
        closed { editor.currentContentIdentity() }
        check(checkpoint.capturedContentIdentity == saved && projectSnapshot.capturedContentIdentity == saved)
        check(late.capturedContentIdentity == branched)
        check(checkpoint.write(File(root, "after-editor-close.checkpoint")).successful)
        checkpoint.close(); projectSnapshot.close(); late.close()
        closed { checkpoint.capturedContentIdentity }
        closed { projectSnapshot.capturedContentIdentity }
        closed { late.capturedContentIdentity }
        return "PASS: actual JNI selection/history/capture/late-write/closed-editor/domain-reset; 1000 scalar reads preserve ledger"
    } finally {
        release.countDown()
        writer?.join(10_000)
        checkpoints.forEach { it.close() }
        project?.close()
        editor.close()
        root.deleteRecursively()
    }
}
