package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercise lifecycle admission while a real retained command owns the editor lock. */
internal fun workspaceFlushAsyncSmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "flush-async-${System.nanoTime()}")
        .apply { check(mkdirs()) }
    val main = Handler(Looper.getMainLooper())
    val owned = mutableListOf<AndroidWorkspace>()
    val releases = mutableListOf<CountDownLatch>()
    try {
        for (mode in listOf("flush", "command-error", "close", "cancel-close")) {
            val folder = File(root, mode)
            val ready = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1).also(releases::add)
            val actionExited = AtomicBoolean(false)
            val armed = AtomicBoolean(false)
            val writes = AtomicInteger()
            val editorRef = AtomicReference<NativeEditor>()
            val workspace = AndroidWorkspace(folder, recoveryHooks = RecoveryPublicationHooks(
                beforeCheckpoint = { kind, _, _ ->
                    if (armed.get() && kind in setOf(RecoverySaveKind.FLUSH, RecoverySaveKind.CLOSE, RecoverySaveKind.AUTOSAVE)) {
                        check(actionExited.get()) { "$mode checkpoint overtook the held command" }
                        if (kind != RecoverySaveKind.AUTOSAVE)
                            check(!editorRef.get().hasPendingEdits) { "$mode lifecycle checkpoint preceded ticket settlement" }
                        writes.incrementAndGet()
                    }
                }
            ), changed = { if (!it.loading) ready.countDown() }).also(owned::add)
            check(ready.await(10, TimeUnit.SECONDS)) { "$mode workspace bootstrap did not finish" }
            check(workspace.installCandidate("flush-async-$mode", DirtyDisposition.DISCARD) {
                NativeEditor(8, addDefaultLayers = false).also { it.addLayer() }
            }.result.get(10, TimeUnit.SECONDS).installed)
            instrumentation.waitForIdleSync()
            val editor = checkNotNull(workspace.snapshot().editor).also(editorRef::set)
            val token = checkNotNull(workspace.token(editor))
            val lease = checkNotNull(workspace.lease(editor))
            val before = editor.state()
            val layer = before.layers.single().id
            val pointerBefore = File(folder, "active.json").readBytes()
            val expectedName = "Settled $mode"
            armed.set(true)
            check(editor.commandDispatcher.submitAction(lease, token) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "$mode command release timed out" }
                try {
                    editor.renameLayer(layer, expectedName)
                    if (mode == "command-error") error("Expected flush fixture command error")
                } finally { actionExited.set(true) }
            })
            check(entered.await(10, TimeUnit.SECONDS)) { "$mode command did not acquire editor lock" }

            val returned = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val flushRef = AtomicReference<Future<Long>>()
            val closeRef = AtomicReference<Future<*>>()
            check(main.post {
                try {
                    val flush = workspace.flushAsync().also(flushRef::set)
                    check(!flush.isDone) { "$mode flush completed while its command was held" }
                    if (mode == "cancel-close") check(flush.cancel(false))
                    if (mode == "close" || mode == "cancel-close")
                        closeRef.set(workspace.closeAsync())
                } catch (error: Throwable) { failure.set(error) }
                finally { returned.countDown() }
            })
            // A deadline tests responsiveness; latches determine ordering, not elapsed sleeps.
            check(returned.await(2, TimeUnit.SECONDS)) { "$mode lifecycle admission blocked the main thread on editor work" }
            failure.get()?.let { throw it }
            check(!actionExited.get() && writes.get() == 0)
            check(File(folder, "active.json").readBytes().contentEquals(pointerBefore))
            closeRef.get()?.let { check(!it.isDone) { "$mode close skipped its retained command" } }
            release.countDown()

            val flush = checkNotNull(flushRef.get())
            if (mode == "cancel-close") check(flush.isCancelled)
            else {
                val flushed = flush.get(10, TimeUnit.SECONDS)
                if (mode != "close") check(flushed == before.revision + 1)
            }
            closeRef.get()?.get(10, TimeUnit.SECONDS)
            if (mode != "close" && mode != "cancel-close") {
                editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
                check(editor.pendingEditCount == 0 && editor.state().layers.single().name == expectedName)
                if (mode == "command-error") {
                    check(editor.commandDispatcher.latestCompletion?.error == "Expected flush fixture command error")
                    check(editor.commandDispatcher.submit(EditorCommand.RenameLayer(layer, "Retry saved"), lease,
                        checkNotNull(workspace.token(editor))))
                    check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == before.revision + 2)
                }
                workspace.closeAsync().get(10, TimeUnit.SECONDS)
            }
            check(actionExited.get() && writes.get() > 0)
            val store = WorkspaceRecoveryStore(folder)
            NativeEditor.load(store.checkpoint(checkNotNull(store.read()))).use { recovered ->
                check(recovered.state().layers.single().name == if (mode == "command-error") "Retry saved" else expectedName)
                check(recovered.currentRevision() == before.revision + if (mode == "command-error") 2 else 1)
            }
            // A closed owner cannot reopen saving or publish another checkpoint.
            val closedPointer = File(folder, "active.json").readBytes()
            check(workspace.flushAsync().get(2, TimeUnit.SECONDS) == workspace.snapshot().savedRevision)
            check(File(folder, "active.json").readBytes().contentEquals(closedPointer))
        }
        return "Main-thread flush and close admission returned while a real retained command held the editor lock; checkpoints waited for settlement and recovered accepted edits. Command error allowed later retry; cancelling flush preserved close drain and final recovery. Closed-owner flush preserved the pointer. Latch-controlled fixture; no UI rendering, replacement-race or frame-rate claim."
    } finally {
        releases.forEach { it.countDown() }
        owned.forEach { it.closeAsync().get(20, TimeUnit.SECONDS) }
        root.deleteRecursively()
    }
}
