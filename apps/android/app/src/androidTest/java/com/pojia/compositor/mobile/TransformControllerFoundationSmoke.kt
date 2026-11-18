package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Deliberately holds the control lane and main acknowledgment to exercise ownership boundaries. */
internal fun transformControllerFoundationSmoke(instrumentation: Instrumentation): String {
    val baselineClaims = ImageIoAdmission.ledger.snapshot()
    val editor = NativeEditor(8, false)
    val callbacks = ConcurrentLinkedQueue<() -> Unit>()
    val delivered = mutableListOf<TransformController.Snapshot>()
    val controller = TransformController(editor, instrumentation.targetContext, { callbacks.add(it) }, { delivered.add(it) })
    fun awaitPhase(phase: TransformController.Phase) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (controller.snapshot()?.phase != phase && System.nanoTime() < deadline) Thread.sleep(5)
        check(controller.snapshot()?.phase == phase)
    }
    fun acknowledge() {
        instrumentation.runOnMainSync {
            while (true) (callbacks.poll() ?: break).invoke()
        }
    }
    try {
        val source = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(-1, 0, 0, -1)); rewind() }
        val root = editor.importPixels(1, 1, source)
        val original = editor.state().layers.single().transform
        val before = editor.state()
        editor.startStroke(root, 1f, false)
        val legacyStrokeScene = checkNotNull(editor.captureScene())
        try {
            editor.endStroke(true)
            check(runCatching { editor.beginTransformDraft(instrumentation.targetContext, root) }.isFailure)
        } finally { legacyStrokeScene.close() }
        editor.beginTransformDraft(instrumentation.targetContext, root).close()
        val owner = editor.serialized {
            val value = checkNotNull(controller.begin(root, TransformTarget.LAYER, 10, 20, 30))
            check(editor.activeTransformCompletion() === value.finished && !value.finished.isDone)
            check(editor.reserveStroke() == null && editor.tryRegisterPendingEdit() == null)
            check(controller.begin(root, TransformTarget.LAYER, 10, 20, 31) == null)
            repeat(100) { index -> check(controller.update(value, original.copy(x = (index + 1) / 50.0))) }
            check(!controller.update(value, original.copy(x = Double.NaN)))
            check(controller.finish(value) === value.finished)
            check(!controller.update(value, original.copy(x = 99.0)))
            value
        }
        awaitPhase(TransformController.Phase.TERMINAL_WAITING)
        check(!owner.finished.isDone && callbacks.size == 1)
        check(editor.state().layers.single().transform.x == 2.0)
        check(editor.state().revision == before.revision + 1)
        check(editor.reserveStroke() == null)
        acknowledge()
        val committed = owner.finished.get(10, TimeUnit.SECONDS)
        check(committed.committed && !committed.cancelled && committed.error == null)
        check(delivered.single().phase == TransformController.Phase.TERMINAL_WAITING)
        check(editor.activeTransformCompletion() == null)

        val cancelledBaseline = editor.state()
        val cancel = checkNotNull(controller.begin(root, TransformTarget.LAYER, 10, 21, 40))
        awaitPhase(TransformController.Phase.ACTIVE)
        check(controller.update(cancel, original.copy(x = 3.0)))
        controller.cancel(cancel)
        awaitPhase(TransformController.Phase.TERMINAL_WAITING)
        acknowledge()
        check(cancel.finished.get(10, TimeUnit.SECONDS).cancelled)
        check(editor.state().copy(selectedLayerActions = null) == cancelledBaseline.copy(selectedLayerActions = null))

        val pendingCancel = editor.serialized {
            val value = checkNotNull(controller.begin(root, TransformTarget.LAYER, 10, 21, 41))
            check(controller.update(value, original.copy(x = 7.0)))
            controller.finish(value)
            controller.cancel(value)
            value
        }
        awaitPhase(TransformController.Phase.TERMINAL_WAITING)
        acknowledge()
        check(pendingCancel.finished.get(10, TimeUnit.SECONDS).cancelled)
        check(editor.state().copy(selectedLayerActions = null) == cancelledBaseline.copy(selectedLayerActions = null))

        editor.committed = { error("controller observer fixture") }
        val observer = checkNotNull(controller.begin(root, TransformTarget.LAYER, 10, 22, 50))
        check(controller.update(observer, original.copy(x = 4.0)))
        controller.finish(observer)
        awaitPhase(TransformController.Phase.TERMINAL_WAITING)
        acknowledge()
        val receipt = observer.finished.get(10, TimeUnit.SECONDS)
        check(receipt.committed && !receipt.cancelled && receipt.result?.changed == true && receipt.error != null)
        check(editor.state().layers.single().transform.x == 4.0)
        check(controller.finish(observer).get() === receipt)
        editor.committed = null
        return "Retained transform foundation: synchronous prequeue reservation; absolute coalescing/final accepted update; " +
            "one main callback held through terminal; conflicting input rejected until acknowledgment; cancel restores committed state; " +
            "observer failure preserves confirmed commit without replay. " + transformAutosaveFoundationSmoke(instrumentation) +
            " No Canvas/GPU/performance claim."
    } finally {
        editor.committed = null
        controller.snapshot()?.owner?.let { owner ->
            controller.cancel(owner)
            awaitPhase(TransformController.Phase.TERMINAL_WAITING)
            acknowledge()
        }
        controller.close(); editor.close()
        val finalClaims = ImageIoAdmission.ledger.snapshot()
        check(finalClaims.reservedBytes == baselineClaims.reservedBytes && finalClaims.reservations == baselineClaims.reservations)
    }
}

/** Real scheduled recovery must wait for input acknowledgment without blocking other workspace IO. */
private fun transformAutosaveFoundationSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val folder = File(context.cacheDir, "transform-autosave-${UUID.randomUUID()}")
    val captureCalls = AtomicInteger()
    val blockWriter = AtomicBoolean(false)
    val pauseHook = AtomicBoolean(false)
    val hookEntered = CountDownLatch(1)
    val hookRelease = CountDownLatch(1)
    val workspace = AndroidWorkspace(folder, recoveryHooks = RecoveryPublicationHooks(beforeCheckpoint = { kind, _, file ->
        if (kind == RecoverySaveKind.AUTOSAVE) {
            captureCalls.incrementAndGet()
            if (pauseHook.compareAndSet(true, false)) {
                hookEntered.countDown()
                check(hookRelease.await(10, TimeUnit.SECONDS)) { "Autosave hook race was not released" }
            }
            if (blockWriter.get()) {
                check(file.mkdir())
                File(file, "sentinel").writeBytes(byteArrayOf(19, 83, 42))
            }
        }
    }))
    val callbacks = ConcurrentLinkedQueue<() -> Unit>()
    var controller: TransformController? = null
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        check(condition()) { message }
    }
    fun acknowledge() = instrumentation.runOnMainSync {
        while (true) (callbacks.poll() ?: break).invoke()
    }
    fun maintenance() {
        val done = CompletableFuture<Boolean>()
        workspace.runMaintenance({ true }) { result ->
            result.fold({ done.complete(it) }, { done.completeExceptionally(it) })
        }
        check(done.get(10, TimeUnit.SECONDS)) { "Deferred autosave blocked the workspace worker" }
    }
    fun cold(expected: EditorState) {
        val store = WorkspaceRecoveryStore(folder)
        NativeEditor.load(store.checkpoint(checkNotNull(store.read()))).use { recovered ->
            val actual = recovered.state()
            check(actual.document == expected.document && actual.layers == expected.layers && actual.revision == expected.revision)
        }
    }
    try {
        waitFor("Transform autosave workspace did not open") { !workspace.snapshot().loading }
        check(workspace.snapshot().recoveryError == null)
        check(workspace.installCandidate("transform-autosave", DirtyDisposition.DISCARD) {
            NativeEditor(8, false).also { editor ->
                editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(-1, 0, 0, -1)); rewind() })
            }
        }.result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        val root = editor.state().layers.single().id
        val control = TransformController(editor, context, { callbacks.add(it) }, {})
        controller = control
        val savedBeforeCancel = workspace.snapshot().savedRevision
        val pointerBeforeCancel = File(folder, "active.json").readBytes()
        val capturesBeforeCancel = captureCalls.get()
        val outcomeBeforeCancel = workspace.snapshot().lastCheckpointWrite
        val snapshotBeforeCancel = workspace.snapshot().lastCheckpointSnapshot
        val (cancel, cancelSave) = editor.serialized {
            editor.setOpacity(root, .75)
            val scheduled = checkNotNull(workspace.scheduledAutosaveForTest())
            checkNotNull(control.begin(root, TransformTarget.LAYER, 1, 1, 1)) to scheduled
        }
        waitFor("Autosave cancel draft did not begin") { control.snapshot()?.phase == TransformController.Phase.ACTIVE }
        cancelSave.get(10, TimeUnit.SECONDS)
        maintenance()
        check(!cancel.finished.isDone && editor.hasActiveTransformDraft)
        check(workspace.snapshot().saveError == null && workspace.snapshot().savedRevision == savedBeforeCancel)
        check(captureCalls.get() == capturesBeforeCancel && File(folder, "active.json").readBytes().contentEquals(pointerBeforeCancel))
        check(workspace.snapshot().lastCheckpointWrite == outcomeBeforeCancel &&
            workspace.snapshot().lastCheckpointSnapshot == snapshotBeforeCancel)
        val cancelledState = editor.state()
        control.cancel(cancel)
        waitFor("Autosave cancel terminal did not arrive") { control.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING }
        maintenance()
        check(!cancel.finished.isDone && workspace.snapshot().savedRevision == savedBeforeCancel &&
            captureCalls.get() == capturesBeforeCancel)
        acknowledge()
        check(cancel.finished.get(10, TimeUnit.SECONDS).cancelled)
        waitFor("Cancel did not resume the earlier dirty autosave") { workspace.snapshot().savedRevision == cancelledState.revision }
        check(workspace.snapshot().saveError == null && editor.state().copy(selectedLayerActions = null) == cancelledState.copy(selectedLayerActions = null))
        cold(cancelledState)

        val savedBeforeCommit = workspace.snapshot().savedRevision
        val capturesBeforeCommit = captureCalls.get()
        val (commit, commitSave) = editor.serialized {
            editor.setOpacity(root, .5)
            val scheduled = checkNotNull(workspace.scheduledAutosaveForTest())
            checkNotNull(control.begin(root, TransformTarget.LAYER, 1, 1, 2)) to scheduled
        }
        waitFor("Autosave commit draft did not begin") { control.snapshot()?.phase == TransformController.Phase.ACTIVE }
        check(control.update(commit, editor.state().layers.single().transform.copy(x = 2.0)))
        commitSave.get(10, TimeUnit.SECONDS)
        maintenance()
        check(workspace.snapshot().saveError == null && captureCalls.get() == capturesBeforeCommit)
        control.finish(commit)
        waitFor("Autosave commit terminal did not arrive") { control.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING }
        val committedState = editor.state()
        maintenance()
        check(committedState.layers.single().transform.x == 2.0 && !commit.finished.isDone)
        check(workspace.snapshot().savedRevision == savedBeforeCommit && captureCalls.get() == capturesBeforeCommit)
        acknowledge()
        check(commit.finished.get(10, TimeUnit.SECONDS).committed)
        waitFor("Commit did not autosave its final revision after acknowledgment") {
            workspace.snapshot().savedRevision == committedState.revision
        }
        check(workspace.snapshot().saveError == null)
        cold(committedState)

        // An actual incompatible destination still reports the native writer refusal.
        blockWriter.set(true)
        val pointerBeforeFailure = File(folder, "active.json").readBytes()
        editor.renameLayer(root, "Autosave filesystem refusal")
        waitFor("Autosave hid a real filesystem failure") { workspace.snapshot().saveError != null }
        check(workspace.snapshot().savedRevision == committedState.revision)
        check(workspace.snapshot().lastCheckpointWrite?.successful == false)
        check(File(folder, "active.json").readBytes().contentEquals(pointerBeforeFailure))
        blockWriter.set(false)
        check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == editor.currentRevision())
        check(workspace.snapshot().saveError == null)

        // The preflight sees no draft, but a new owner starts while the hook is paused outside
        // the editor command gate. The post-hook check must defer before native snapshot capture.
        val savedBeforeRace = workspace.snapshot().savedRevision
        val pointerBeforeRace = File(folder, "active.json").readBytes()
        val capturesBeforeRace = captureCalls.get()
        pauseHook.set(true)
        editor.renameLayer(root, "Draft starts during autosave hook")
        val raceSave = checkNotNull(workspace.scheduledAutosaveForTest())
        check(hookEntered.await(10, TimeUnit.SECONDS)) { "Autosave did not reach its outside-gate hook" }
        val raceOwner = checkNotNull(control.begin(root, TransformTarget.LAYER, 1, 1, 3))
        waitFor("Hook race draft did not begin") { control.snapshot()?.phase == TransformController.Phase.ACTIVE }
        hookRelease.countDown()
        raceSave.get(10, TimeUnit.SECONDS)
        maintenance()
        check(captureCalls.get() == capturesBeforeRace + 1 && editor.hasActiveTransformDraft)
        check(workspace.snapshot().savedRevision == savedBeforeRace && workspace.snapshot().saveError == null)
        check(workspace.snapshot().lastCheckpointSnapshot == null && workspace.snapshot().lastCheckpointWrite == null)
        check(File(folder, "active.json").readBytes().contentEquals(pointerBeforeRace))
        val raceState = editor.state()
        control.cancel(raceOwner)
        waitFor("Hook race terminal did not arrive") { control.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING }
        maintenance()
        check(!raceOwner.finished.isDone && workspace.snapshot().savedRevision == savedBeforeRace)
        acknowledge()
        check(raceOwner.finished.get(10, TimeUnit.SECONDS).cancelled)
        waitFor("Hook race cancel did not resume autosave") { workspace.snapshot().savedRevision == raceState.revision }
        check(captureCalls.get() == capturesBeforeRace + 2 && workspace.snapshot().saveError == null)
        cold(raceState)

        return "Scheduled autosave defers across active drafts and held terminal acknowledgment without capture/error or worker blocking; " +
            "cancel saves prior dirty state, commit saves the final revision, a draft starting during the outside-gate hook defers before native capture, cold checkpoints match, and real filesystem writer failures remain visible."
    } finally {
        hookRelease.countDown()
        blockWriter.set(false)
        controller?.let { control ->
            control.snapshot()?.owner?.let { owner ->
                control.cancel(owner)
                waitFor("Autosave fixture terminal cleanup did not arrive") { control.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING }
                acknowledge()
            }
            control.close()
        }
        try { workspace.closeAsync().get(10, TimeUnit.SECONDS) }
        finally { folder.deleteRecursively() }
    }
}
