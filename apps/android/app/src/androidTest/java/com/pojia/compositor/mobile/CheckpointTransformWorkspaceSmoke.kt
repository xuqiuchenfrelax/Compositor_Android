package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private data class CheckpointOverlapExpected(
    val state: EditorState, val pixels: ByteArray,
    val info: CheckpointSnapshotInfo, val memory: CheckpointSnapshotMemory,
)
private data class CheckpointOverlapWritten(
    val target: RecoveryTarget, val file: File, val revision: Long, val bytes: ByteArray,
)
private class CheckpointOverlapAttempt(val revision: Long) {
    val requestedTarget = AtomicReference<RecoveryTarget?>()
    val capturedTarget = AtomicReference<RecoveryTarget?>()
    val admitted = CompletableFuture<Pair<CheckpointSnapshotInfo, ResourceBudgetSnapshot>>()
    val releaseWriter = CountDownLatch(1)
    val written = CompletableFuture<CheckpointOverlapWritten>()
}
private class CheckpointOverlapGate(val revision: Long) {
    val attempts = listOf(CheckpointOverlapAttempt(revision), CheckpointOverlapAttempt(revision + 1))
    val nextAttempt = AtomicInteger()
    val current = AtomicReference<CheckpointOverlapAttempt?>()
    val oldSaved = CompletableFuture<WorkspaceState>()
    val newSaved = CompletableFuture<WorkspaceState>()
}

/** Actual workspace AUTOSAVE ownership overlap; no UI, physical concurrency, or crash-durability claim. */
internal fun checkpointTransformWorkspaceSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val baseline = ledger.snapshot()
    val root = File(context.cacheDir, "checkpoint-transform-workspace-${UUID.randomUUID()}")
        .apply { check(mkdir()) }
    val workspaces = mutableListOf<AndroidWorkspace>()
    val gates = mutableListOf<CheckpointOverlapGate>()
    val activeGates = mutableListOf<AtomicReference<CheckpointOverlapGate?>>()
    val drafts = mutableListOf<NativeTransformDraft>()
    var bodyFailure: Throwable? = null
    fun image(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(8 * 8 * 4)
        check(editor.draw(8, 8, 0.0, 0.0, 8.0, 8.0, output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun expected(editor: NativeEditor): CheckpointOverlapExpected {
        val state = editor.state()
        val pixels = image(editor)
        // Independent pre-writer inventory; never inferred by decoding a writer's output.
        return editor.captureCheckpointSnapshot().use {
            check(it.capturedRevision == state.revision)
            CheckpointOverlapExpected(state, pixels, it.info, it.memory)
        }
    }
    fun cold(file: File, value: CheckpointOverlapExpected) {
        NativeEditor.load(file).use {
            check(it.state().copy(selectedLayerActions = null) == value.state.copy(undo = false, redo = false, selectedLayerActions = null)) {
                "Cold checkpoint metadata/history differs from its pre-writer expected revision"
            }
            check(image(it).contentEquals(value.pixels)) { "Cold checkpoint pixels changed during Move overlap" }
        }
    }
    fun fixture(): NativeEditor = NativeEditor(8, false).also { editor ->
        try {
            val rgba = byteArrayOf(
                -1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1,
                -1, -1, 0, -1, -1, 0, -1, -1, 0, -1, -1, -1,
            )
            val id = editor.importPixels(3, 2, ByteBuffer.allocateDirect(rgba.size).apply { put(rgba); rewind() })
            editor.setTransform(id, Transform64(1.0, 1.0, 3.0, 2.0))
        } catch (failure: Throwable) { editor.close(); throw failure }
    }
    try {
        for (commitBeforeRelease in listOf(true, false)) {
            val order = if (commitBeforeRelease) "commit-before-release" else "release-before-commit"
            val folder = File(root, order)
            val activeGate = AtomicReference<CheckpointOverlapGate?>().also(activeGates::add)
            val workspaceRef = AtomicReference<AndroidWorkspace?>()
            val opened = CompletableFuture<WorkspaceState>()
            val hooks = RecoveryPublicationHooks(
                beforeCheckpoint = { kind, target, _ ->
                    activeGate.get()?.let { gate ->
                        check(kind == RecoverySaveKind.AUTOSAVE) { "Overlap must start from real AUTOSAVE, not flush" }
                        val index = gate.nextAttempt.getAndIncrement()
                        check(index in gate.attempts.indices) { "Unexpected extra checkpoint during overlap" }
                        val attempt = gate.attempts[index]
                        check(attempt.requestedTarget.compareAndSet(null, target))
                        // Before capture, projectClean is inherited from the previous pointer.
                        check(target.projectClean == (index == 0))
                        gate.current.set(attempt)
                    }
                },
                afterCheckpoint = { kind, target, file, revision ->
                    activeGate.get()?.let { gate ->
                        check(kind == RecoverySaveKind.AUTOSAVE)
                        val attempt = checkNotNull(gate.current.get())
                        val requested = checkNotNull(attempt.requestedTarget.get())
                        check(requested.generation == target.generation && requested.fileName == target.fileName &&
                            requested.documentUuid == target.documentUuid && requested.projectBaseline == target.projectBaseline &&
                            requested.sourceIdentity == target.sourceIdentity && revision == attempt.revision)
                        // Both N and N+1 contain edits after the saved project; capture owns this relation.
                        check(target.projectClean == false)
                        check(attempt.capturedTarget.compareAndSet(null, target))
                        val workspace = checkNotNull(workspaceRef.get())
                        check(workspace.snapshot().lastCheckpointWrite?.let {
                            it.successful && it.capturedRevision == revision
                        } == true)
                        check(attempt.written.complete(CheckpointOverlapWritten(checkNotNull(attempt.capturedTarget.get()), file, revision, file.readBytes())))
                    }
                },
            )
            val admission = CheckpointAdmission.forTest(ledger, beforeWrite = { scratch ->
                activeGate.get()?.let { gate ->
                    val attempt = checkNotNull(gate.current.get())
                    val info = checkNotNull(workspaceRef.get()!!.snapshot().lastCheckpointSnapshot)
                    check(info.capturedRevision == attempt.revision)
                    check(scratch > 0)
                    check(attempt.admitted.complete(info to ledger.snapshot()))
                    check(attempt.releaseWriter.await(10, TimeUnit.SECONDS)) { "Overlap writer release timed out" }
                }
            })
            val workspace = AndroidWorkspace(folder, recoveryHooks = hooks, checkpointAdmission = admission,
                changed = { state ->
                    if (!state.loading) opened.complete(state)
                    activeGate.get()?.let { gate ->
                        if (state.recoverySavedRevision == gate.revision) gate.oldSaved.complete(state)
                        if (state.recoverySavedRevision == gate.revision + 1) gate.newSaved.complete(state)
                    }
                }).also { workspaceRef.set(it); workspaces.add(it) }
            check(opened.get(10, TimeUnit.SECONDS).let { it.editor != null && it.recoveryError == null })
            check(workspace.installCandidate("overlap-$order", DirtyDisposition.DISCARD, candidate = ::fixture)
                .result.get(10, TimeUnit.SECONDS).installed)
            val editor = checkNotNull(workspace.snapshot().editor)
            check(editor.resourceLedger === ledger)
            val store = WorkspaceRecoveryStore(folder)
            val installed = workspace.snapshot()
            val initialTarget = checkNotNull(store.read())
            val initialPointer = File(folder, "active.json").readBytes()
            val id = editor.state().layers.single().id
            lateinit var old: CheckpointOverlapExpected
            lateinit var beforeCapture: ResourceBudgetSnapshot
            lateinit var gate: CheckpointOverlapGate
            // The real autosave worker cannot capture until this exact N oracle is complete.
            editor.serialized {
                editor.renameLayer(id, "Captured N / $order")
                old = expected(editor)
                check(old.state.revision == installed.currentRevision + 1)
                beforeCapture = ledger.snapshot()
                gate = CheckpointOverlapGate(old.state.revision).also { activeGate.set(it); gates.add(it) }
            }
            val first = gate.attempts[0]
            val admitted = first.admitted.get(10, TimeUnit.SECONDS)
            check(admitted.first == old.info)
            check(admitted.second.reservedBytes == beforeCapture.reservedBytes + old.memory.heldBytes + old.memory.writeScratchBytes)
            check(admitted.second.reservations == beforeCapture.reservations + 2)
            check(workspace.snapshot().let {
                it.recoverySavedRevision == installed.recoverySavedRevision && it.currentRevision == old.state.revision &&
                    it.projectDirty && it.projectBaseline == installed.projectBaseline && it.sourceIdentity == installed.sourceIdentity
            })
            check(File(folder, "active.json").readBytes().contentEquals(initialPointer))
            check(store.read() == initialTarget)

            val draft = editor.beginTransformDraft(context, id).also(drafts::add)
            val original = old.state.layers.single().transform
            val moved = original.copy(x = original.x + 2.0, y = original.y + 1.0)
            draft.update(moved)
            check(editor.state().copy(selectedLayerActions = null) == old.state.copy(selectedLayerActions = null)) { "Move preview changed the writer's committed revision" }
            check(ledger.snapshot().reservedBytes == admitted.second.reservedBytes + draft.reservation.bytes)
            lateinit var fresh: CheckpointOverlapExpected
            fun commitMove() = editor.serialized {
                val result = draft.commit()
                check(result.changed && result.revision == old.state.revision + 1 && draft.commitConfirmed)
                check(editor.state().layers.single().transform == moved)
                fresh = expected(editor)
                check(fresh.state.revision == old.state.revision + 1 && fresh.state.undo)
                check(!fresh.pixels.contentEquals(old.pixels))
                check(workspace.snapshot().let {
                    it.currentRevision == fresh.state.revision && it.projectDirty &&
                        it.projectBaseline == installed.projectBaseline && it.sourceIdentity == installed.sourceIdentity
                })
            }
            if (commitBeforeRelease) commitMove()
            first.releaseWriter.countDown()
            val oldWritten = first.written.get(10, TimeUnit.SECONDS)
            val oldSaved = gate.oldSaved.get(10, TimeUnit.SECONDS)
            check(oldWritten.revision == old.state.revision && oldWritten.target != initialTarget)
            check(oldWritten.file.length() == old.info.serializedBytes)
            check(store.read() == oldWritten.target)
            check(oldSaved.let {
                it.recoverySavedRevision == old.state.revision && it.projectDirty &&
                    it.currentRevision == old.state.revision + (if (commitBeforeRelease) 1 else 0) &&
                    it.projectBaseline == installed.projectBaseline && it.sourceIdentity == installed.sourceIdentity
            }) { "Old snapshot publication was mislabeled as the later Move revision" }
            if (!commitBeforeRelease) {
                check(editor.hasActiveTransformDraft && editor.state().copy(selectedLayerActions = null) == old.state.copy(selectedLayerActions = null))
                check(gate.nextAttempt.get() == 1) { "An uncommitted preview started a new autosave" }
                commitMove()
            }
            val second = gate.attempts[1]
            val nextAdmitted = second.admitted.get(10, TimeUnit.SECONDS)
            cold(oldWritten.file, old)
            check(nextAdmitted.first == fresh.info)
            check(workspace.snapshot().let {
                it.recoverySavedRevision == old.state.revision && it.currentRevision == fresh.state.revision && it.projectDirty
            }) { "Fresh capture admission claimed to save N+1 before its writer completed" }
            check(store.read() == oldWritten.target && oldWritten.file.readBytes().contentEquals(oldWritten.bytes))
            second.releaseWriter.countDown()
            val freshWritten = second.written.get(10, TimeUnit.SECONDS)
            val newSaved = gate.newSaved.get(10, TimeUnit.SECONDS)
            check(newSaved.let {
                it.recoverySavedRevision == fresh.state.revision && it.currentRevision == fresh.state.revision &&
                    it.projectDirty && it.projectBaseline == installed.projectBaseline && it.sourceIdentity == installed.sourceIdentity &&
                    !it.recoveryDurabilityUncertain && it.saveError == null
            }) { "Autosave must advance recovery while retaining source-project dirty metadata" }
            check(freshWritten.target != oldWritten.target && freshWritten.target.generation == oldWritten.target.generation)
            check(freshWritten.file.length() == fresh.info.serializedBytes && store.read() == freshWritten.target)
            check(oldWritten.file.readBytes().contentEquals(oldWritten.bytes))
            cold(oldWritten.file, old)
            cold(freshWritten.file, fresh)
            activeGate.set(null)
            check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == fresh.state.revision)
            check(store.read() == freshWritten.target)
            workspace.closeAsync().get(10, TimeUnit.SECONDS)

            val reopened = CompletableFuture<WorkspaceState>()
            val recovery = AndroidWorkspace(folder, changed = { if (!it.loading) reopened.complete(it) }).also(workspaces::add)
            val restored = reopened.get(10, TimeUnit.SECONDS)
            check(restored.recovered && restored.recoveryError == null && !restored.recoveryDurabilityUncertain)
            check(restored.let {
                it.currentRevision == fresh.state.revision && it.recoverySavedRevision == fresh.state.revision && it.projectDirty &&
                    it.projectBaseline == installed.projectBaseline && it.sourceIdentity == installed.sourceIdentity
            })
            val coldEditor = checkNotNull(restored.editor)
            check(coldEditor.state().copy(selectedLayerActions = null) == fresh.state.copy(undo = false, redo = false, selectedLayerActions = null))
            check(image(coldEditor).contentEquals(fresh.pixels))
            recovery.closeAsync().get(10, TimeUnit.SECONDS)
            check(ledger.snapshot().let { it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations }) {
                "Workspace overlap leaked a document, checkpoint, draft, or writer reservation"
            }
        }
        return "Real workspace AUTOSAVE N acquired holder and writer scratch before Move began: commit-before-release and release-before-commit both preserved old checkpoint N geometry/pixels and honest recovery/source-dirty metadata; a fresh AUTOSAVE advanced recovery to N+1, flush and pointer-driven cold reopen matched committed Move with empty history, and close returned shared-ledger claims. Latch sequencing proves ownership overlap, not physical native execution overlap, crash durability, or UI behavior."
    } catch (failure: Throwable) {
        bodyFailure = failure
        throw failure
    } finally {
        // Disarm failure-path hooks before releasing independent blockers and closing owners.
        activeGates.forEach { it.set(null) }
        gates.forEach { gate -> gate.attempts.forEach { it.releaseWriter.countDown() } }
        var cleanupFailure: Throwable? = null
        fun clean(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        drafts.asReversed().forEach { clean { it.close() } }
        workspaces.asReversed().forEach { clean { it.closeAsync().get(10, TimeUnit.SECONDS) } }
        if (cleanupFailure == null) root.deleteRecursively()
        cleanupFailure?.let { failure -> bodyFailure?.addSuppressed(failure) ?: throw failure }
    }
}
