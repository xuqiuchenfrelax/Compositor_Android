package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real retained duplicate, command/save barrier, immutable .comp and cold recovery. */
internal fun duplicateWorkspaceIoSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    val root = File(context.cacheDir, "duplicate-workspace-io-${UUID.randomUUID()}").apply { check(mkdir()) }
    val artifacts = File(context.filesDir, "android-duplicate-workspace-io")
    val workspaces = mutableListOf<AndroidWorkspace>()
    var controller: TransformController? = null
    var currentOwner: TransformController.Owner? = null
    var retained: NativeProjectSnapshot? = null
    val limit = 128L * 1024 * 1024
    val recoveryWrites = AtomicInteger()
    val capturedDuringPreview = AtomicInteger()
    val previewActive = AtomicReference(false)
    var failure: Throwable? = null
    fun waitFor(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        check(condition()) { message }
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(8 * 8 * 4)
        check(editor.draw(8, 8, 0.0, 0.0, 8.0, 8.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    // Runtime IDs can change after .comp import. UUIDs and their relationships cannot.
    fun persistentLayers(state: EditorState): Map<String, Triple<LayerState, String?, String?>> {
        val ids = state.layers.associate { it.id to it.uuid }
        return state.layers.associate { layer -> layer.uuid to Triple(
            layer.copy(id = 0, parentId = 0, maskSourceId = 0), ids[layer.parentId], ids[layer.maskSourceId]) }
    }
    fun opened(folder: File, hooks: RecoveryPublicationHooks? = null): AndroidWorkspace {
        val workspace = AndroidWorkspace(folder, recoveryHooks = hooks).also(workspaces::add)
        waitFor("Duplicate IO workspace did not open") { !workspace.snapshot().loading }
        check(workspace.snapshot().editor != null && workspace.snapshot().recoveryError == null)
        return workspace
    }
    try {
        check(!artifacts.exists() || artifacts.deleteRecursively()); check(artifacts.mkdir())
        val hooks = RecoveryPublicationHooks(beforeCheckpoint = { _, _, _ ->
            if (previewActive.get()) capturedDuringPreview.incrementAndGet()
        }, afterCheckpoint = { _, _, _, _ -> recoveryWrites.incrementAndGet() })
        val folder = File(root, "recovery")
        val workspace = opened(folder, hooks)
        check(workspace.installCandidate("duplicate-io-fixture", DirtyDisposition.DISCARD, candidate = {
            NativeEditor(8, false).also { editor ->
                try {
                    val rgba = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1,
                        0, 0, -1, -1, -1, -1, 0, -1)
                    val image = editor.importPixels(2, 2, ByteBuffer.allocateDirect(rgba.size).apply { put(rgba); rewind() })
                    editor.renameLayer(image, "Pixels")
                    editor.setTransform(image, Transform64(1.0, 1.0, 2.0, 2.0))
                    editor.createMask(image, true); editor.linkMask(image, false)
                    val group = editor.addGroup(); editor.renameLayer(group, "Folder")
                    editor.reparentLayer(image, group)
                    editor.addLayer()
                    val hidden = editor.state().document.activeLayerId
                    editor.renameLayer(hidden, "Hidden child"); editor.reparentLayer(hidden, group)
                    editor.setVisible(hidden, false)
                    editor.selectLayer(group)
                } catch (error: Throwable) { editor.close(); throw error }
            }
        }).result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == editor.currentRevision())
        val originalPointer = File(folder, "active.json").readBytes()
        val writes = recoveryWrites.get()
        val lane = TransformController(editor, context, { action -> action() }).also { controller = it }
        lateinit var before: EditorState
        lateinit var beforePixels: ByteArray
        lateinit var owner: TransformController.Owner
        editor.serialized {
            // A real dirty edit schedules AUTOSAVE, and the owner is reserved
            // before that worker can capture. Preview must defer this source too.
            editor.renameLayer(editor.state().layers.single { !it.visible }.id, "Hidden child / queued autosave")
            before = editor.state(); beforePixels = pixels(editor)
            owner = checkNotNull(lane.begin(before.document.activeLayerId, TransformTarget.GROUP,
                1, 1, 1, press = MovePressInput(MovePoint(1.5, 1.5)), duplicateAtDown = true))
                .also { currentOwner = it }
        }
        val sourceIds = before.layers.map { it.uuid }.toSet()
        waitFor("Duplicate DOWN did not become active") { lane.snapshot()?.phase == TransformController.Phase.ACTIVE }
        val originalBox = checkNotNull(lane.snapshot()?.info).box
        previewActive.set(true)
        check(lane.firstDrag(owner))
        val moved = originalBox.copy(x = originalBox.x + 3.0, y = originalBox.y + 1.0)
        check(lane.update(owner, moved, checkNotNull(lane.snapshot()?.info).sampling))
        waitFor("Duplicate topology preview did not publish") {
            editor.duplicateLayerPresentation()?.state?.layers?.size == before.layers.size * 2 &&
                lane.snapshot()?.let { it.accepted?.sequence == it.appliedSequence && it.appliedSequence > 0 } == true
        }
        val preview = checkNotNull(editor.duplicateLayerPresentation())
        val copyUuids = preview.state.layers.map { it.uuid }.toSet() - sourceIds
        check(copyUuids.size == before.layers.size && preview.selection.roots.size == 1)
        check(preview.state.layers.single { it.id == preview.selection.primary }.name == "Folder copy")
        check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && editor.selection().primary == before.document.activeLayerId)
        check(runCatching { editor.captureProjectSnapshot() }.isFailure)
        check(runCatching { editor.captureCheckpointSnapshot() }.isFailure)

        // Ordinary commands reject during preview; the save waits for the retained owner.
        val saveStarted = CompletableFuture<ProjectSaveOperation>()
        val target = File(root, "duplicate.comp")
        check(!editor.commandDispatcher.submitAction(null, null) { })
        val saveBarrier = editor.commandDispatcher.barrier()
        check(!saveBarrier.isDone)
        saveBarrier.whenComplete { _, error ->
            if (error != null) saveStarted.completeExceptionally(error)
            else try {
                saveStarted.complete(workspace.saveProject(context, target, false, limit,
                    editor.retainedMemory().totalBytes + 1024 * 1024, "duplicate-io-saved"))
            } catch (failure: Throwable) { saveStarted.completeExceptionally(failure) }
        }
        val hold = CountDownLatch(1)
        check(!hold.await(800, TimeUnit.MILLISECONDS))
        val workerFence = CompletableFuture<Unit>()
        workspace.runIo({ workerFence.complete(Unit); Unit }, {})
        workerFence.get(10, TimeUnit.SECONDS)
        check(!saveBarrier.isDone && !saveStarted.isDone && !target.exists())
        check(recoveryWrites.get() == writes && capturedDuringPreview.get() == 0)
        check(File(folder, "active.json").readBytes().contentEquals(originalPointer))
        val flush = workspace.flushAsync()
        check(!flush.isDone)
        previewActive.set(false)
        val terminal = lane.finish(owner).get(10, TimeUnit.SECONDS)
        currentOwner = null
        check(terminal.committed && !terminal.cancelled && terminal.error == null)
        val committed = editor.state()
        check(committed.revision == before.revision + 1 && committed.layers.size == before.layers.size * 2)
        check(committed.layers.map { it.uuid }.toSet() - sourceIds == copyUuids)
        check(!editor.hasActiveTransformDraft && editor.duplicateLayerPresentation() == null)
        val committedPixels = pixels(editor)
        check(!committedPixels.contentEquals(beforePixels))
        val saveOperation = saveStarted.get(10, TimeUnit.SECONDS)
        val saveBarrierCompletedAfterCommit = saveBarrier.isDone && !saveBarrier.isCompletedExceptionally
        check(saveBarrierCompletedAfterCommit)
        val saved = saveOperation.result.get(10, TimeUnit.SECONDS)
        check(saved.successful && saved.baselineUpdated && saved.capturedRevision == committed.revision)
        check(flush.get(10, TimeUnit.SECONDS) == committed.revision)
        check(workspace.snapshot().let { !it.projectDirty && it.recoverySavedRevision == committed.revision })

        val manifest = JSONObject(File(target, "manifest.json").readText())
        check(manifest.getInt("version") == 17)
        val records = manifest.getJSONArray("layers")
        val savedUuids = (0 until records.length()).map { records.getJSONObject(it).getString("id") }.toSet()
        check(savedUuids == committed.layers.map { it.uuid }.toSet())
        check(committed.layers.filter { it.isGroup }.all { group ->
            (0 until records.length()).map { records.getJSONObject(it) }.single { it.getString("id") == group.uuid }
                .let { it.getBoolean("isGroup") && !it.has("imageFile") }
        })
        NativeEditor.loadProjectCandidate(context, target, limit, 0).use { cold ->
            check(persistentLayers(cold.state()) == persistentLayers(committed))
            check(cold.state().layers.single { it.id == cold.state().document.activeLayerId }.uuid ==
                committed.layers.single { it.id == committed.document.activeLayerId }.uuid)
            check(pixels(cold).contentEquals(committedPixels))
        }
        check(target.copyRecursively(File(artifacts, "saved.comp")))
        val recovery = WorkspaceRecoveryStore(folder)
        val recoveryFile = recovery.checkpoint(checkNotNull(recovery.read()))
        check(recoveryFile.copyTo(File(artifacts, "recovery-checkpoint.bin")).isFile)
        var coldCheckpointSemanticEqual = false
        var coldCheckpointBytesDoubled = false
        var coldCheckpointPixelsEqual = false
        NativeEditor.load(recoveryFile).use { cold ->
            val coldState = cold.state()
            val expectedCold = committed.copy(undo = false, redo = false)
            coldCheckpointSemanticEqual = coldState.copy(bytes = expectedCold.bytes, selectedLayerActions = null) == expectedCold.copy(selectedLayerActions = null)
            coldCheckpointBytesDoubled = committed.bytes > 0L &&
                coldState.bytes == Math.multiplyExact(committed.bytes, 2L)
            coldCheckpointPixelsEqual = pixels(cold).contentEquals(committedPixels)
            File(artifacts, "cold-checkpoint-observation.json").writeText(JSONObject()
                .put("expectedState", expectedCold.toString()).put("coldState", coldState.toString())
                .put("liveRasterBytes", committed.bytes).put("coldRasterBytes", coldState.bytes)
                .put("semanticEqual", coldCheckpointSemanticEqual)
                .put("bytesDoubled", coldCheckpointBytesDoubled)
                .put("pixelsEqual", coldCheckpointPixelsEqual).toString(2))
            check(coldCheckpointSemanticEqual)
            check(coldCheckpointBytesDoubled)
            check(coldCheckpointPixelsEqual)
        }
        editor.undo(false)
        check(editor.state().layers == before.layers && pixels(editor).contentEquals(beforePixels))
        editor.undo(true)
        check(editor.state().layers == committed.layers && pixels(editor).contentEquals(committedPixels))
        retained = editor.captureProjectSnapshot()
        val retainedRevision = checkNotNull(retained).capturedRevision
        lane.close(); controller = null
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        val afterClose = File(root, "after-close.comp")
        NativeProjectWriteControl(limit, 0).use { control ->
            check(checkNotNull(retained).write(context, afterClose, false, control).let {
                it.successful && it.capturedRevision == retainedRevision
            })
        }
        checkNotNull(retained).close(); retained = null
        NativeEditor.loadProjectCandidate(context, afterClose, limit, 0).use { cold ->
            check(persistentLayers(cold.state()) == persistentLayers(committed))
            check(pixels(cold).contentEquals(committedPixels))
        }
        File(artifacts, "result.json").writeText(JSONObject().put("selector", "duplicateWorkspaceIo")
            .put("beforeLayers", before.layers.size).put("afterLayers", committed.layers.size)
            .put("revisionDelta", committed.revision - before.revision).put("copiedUUIDs", JSONArray(copyUuids.sorted()))
            .put("saveRevision", saved.capturedRevision).put("capturesDuringPreview", capturedDuringPreview.get())
            .put("ordinarySubmitRejectedDuringPreview", true).put("saveBarrierPendingDuringPreview", true)
            .put("saveBarrierCompletedAfterCommit", saveBarrierCompletedAfterCommit)
            .put("coldCheckpointSemanticEqual", coldCheckpointSemanticEqual)
            .put("coldCheckpointBytesDoubled", coldCheckpointBytesDoubled)
            .put("coldCheckpointPixelsEqual", coldCheckpointPixelsEqual)
            .put("undoRedo", true).put("coldProject", true).put("coldCheckpoint", true)
            .put("snapshotSurvivesClose", true).toString())
        return "duplicate preview/save barrier, one copy history, UUID/topology/mask .comp + checkpoint cold roundtrip and immutable snapshot after close"
    } catch (error: Throwable) { failure = error; throw error }
    finally {
        previewActive.set(false)
        currentOwner?.let { owner -> runCatching { controller?.cancel(owner)?.get(10, TimeUnit.SECONDS) } }
        runCatching { controller?.close() }.onFailure { failure?.addSuppressed(it) }
        runCatching { retained?.close() }.onFailure { failure?.addSuppressed(it) }
        workspaces.asReversed().forEach { workspace ->
            runCatching { workspace.closeAsync().get(10, TimeUnit.SECONDS) }.onFailure {
                if (failure != null) failure!!.addSuppressed(it) else throw it
            }
        }
        check(root.deleteRecursively())
        val final = ledger.snapshot()
        check(final.reservedBytes == initial.reservedBytes && final.reservations == initial.reservations) {
            "Duplicate IO resources leaked: before=$initial after=$final"
        }
    }
}
