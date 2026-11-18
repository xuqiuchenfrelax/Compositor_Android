package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Same-ledger immutable checkpoint owners coexist with real JNI transforms and retain independent claims. */
internal fun checkpointTransformSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    val folder = File(context.cacheDir, "checkpoint-transform-${UUID.randomUUID()}").apply { check(mkdir()) }
    val editor = NativeEditor(8, false)
    var editorOpen = true
    val snapshots = mutableListOf<NativeCheckpointSnapshot>()
    val openSnapshots = mutableSetOf<NativeCheckpointSnapshot>()
    val writer = Executors.newSingleThreadExecutor()
    val holdWriter = AtomicBoolean(false)
    val writerEntered = CountDownLatch(1)
    val writerRelease = CountDownLatch(1)
    val writerScratch = AtomicLong()
    val sourceBytes = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1,
        0, 0, -1, -1, -1, -1, 0, -1)
    fun pixels(value: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(8 * 8 * 4)
        checkNotNull(value.captureDisplayScene()).use { it.draw(8, 8, Viewport(0.0, 0.0, 8.0, 8.0), output) }
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun source(value: NativeEditor): Pair<List<Long>, ByteArray> = checkNotNull(value.captureDisplayScene()).use { scene ->
        val layer = scene.layerMetadata(0)
        check(layer.tileCount == 1 && layer.pixelWidth == 2 && layer.pixelHeight == 2)
        val output = ByteBuffer.allocateDirect(sourceBytes.size)
        scene.readTile(0, 0, 8, output)
        scene.tileViewsMetadata(0).map { it.payloadResourceId } to
            ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun retain(snapshot: NativeCheckpointSnapshot): NativeCheckpointSnapshot {
        snapshots.add(snapshot); openSnapshots.add(snapshot); return snapshot
    }
    fun claims(extraBytes: Long = 0, extraOwners: Int = 0) {
        val document = if (editorOpen) editor.installedDocumentBytes() else 0L
        val actual = ledger.snapshot()
        check(actual.reservedBytes == initial.reservedBytes + document +
            openSnapshots.sumOf { it.memory.heldBytes } + extraBytes &&
            actual.reservations == initial.reservations + (if (editorOpen) 1 else 0) + openSnapshots.size + extraOwners) {
            "Checkpoint/transform claim mismatch: $actual"
        }
    }
    fun released(snapshot: NativeCheckpointSnapshot) {
        snapshot.close(); snapshot.close(); check(openSnapshots.remove(snapshot)); claims()
    }
    try {
        val id = editor.importPixels(2, 2, ByteBuffer.allocateDirect(sourceBytes.size).apply { put(sourceBytes); rewind() })
        editor.setTransform(id, Transform64(2.0, 2.0, 2.0, 2.0)); editor.selectLayer(id)
        editor.renameLayer(id, "Checkpoint redo witness"); editor.undo(false)
        val before = editor.state(); val original = before.layers.single().transform
        val beforePixels = pixels(editor); val beforeSource = source(editor)
        check(before.redo && beforeSource.second.contentEquals(sourceBytes))
        claims()
        val first = retain(editor.captureCheckpointSnapshot())
        val second = retain(editor.captureCheckpointSnapshot(CheckpointAdmission.forTest(ledger, beforeWrite = { scratch ->
            if (holdWriter.get()) {
                writerScratch.set(scratch); writerEntered.countDown()
                check(writerRelease.await(10, TimeUnit.SECONDS)) { "Checkpoint writer release deadline elapsed" }
            }
        })))
        check(first.info == second.info && first.capturedRevision == before.revision)
        claims()
        val baselineFile = File(folder, "baseline.checkpoint")
        check(first.write(baselineFile).successful && baselineFile.length() == first.serializedBytes)
        val frozenBytes = baselineFile.readBytes()
        fun oldSnapshot(snapshot: NativeCheckpointSnapshot, label: String) {
            val file = File(folder, "$label.checkpoint")
            check(snapshot.write(file).let { it.successful && it.revisionCaptured && it.capturedRevision == before.revision })
            check(file.length() == snapshot.serializedBytes && file.readBytes().contentEquals(frozenBytes))
            NativeEditor.load(file).use { restored ->
                check(restored.state().copy(selectedLayerActions = null) == before.copy(undo = false, redo = false, selectedLayerActions = null))
                check(pixels(restored).contentEquals(beforePixels) && source(restored).second.contentEquals(sourceBytes))
            }
        }
        fun sourceUnchanged() {
            val actual = source(editor)
            check(actual.first == beforeSource.first && actual.second.contentEquals(beforeSource.second))
        }
        fun oldSnapshots(label: String) {
            oldSnapshot(first, "$label-first"); oldSnapshot(second, "$label-second")
            claims(); sourceUnchanged()
        }
        check(runCatching { editor.freezeResourceGrowth() }.isFailure)
        editor.captureProjectSnapshot().use {
            check(runCatching { editor.beginTransformDraft(context, id) }.exceptionOrNull().let {
                it is IllegalStateException && it.message == "Saving is still in progress. Try moving again when it finishes."
            })
            check(editor.state() == before && !editor.hasActiveTransformDraft)
        }
        claims()
        val otherLedger = ResourceBudgetLedger(8 * 1024 * 1024L)
        editor.captureCheckpointSnapshot(CheckpointAdmission.forTest(otherLedger)).use { foreign ->
            check(otherLedger.snapshot().reservedBytes == foreign.memory.heldBytes && otherLedger.snapshot().reservations == 1)
            claims()
            check(runCatching { editor.beginTransformDraft(context, id) }.exceptionOrNull().let {
                it is IllegalStateException && it.message == "Saving is still in progress. Try moving again when it finishes."
            })
            check(editor.state() == before && !editor.hasActiveTransformDraft)
            foreign.close(); foreign.close()
        }
        check(otherLedger.snapshot().reservedBytes == 0L && otherLedger.snapshot().reservations == 0)
        editor.beginTransformDraft(context, id).use { draft ->
            check(draft.update(original.copy(x = 3.0)).box.x == 3.0 && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
            claims(draft.reservation.bytes, 1)
            check(runCatching { editor.captureCheckpointSnapshot() }.isFailure)
            check(runCatching { editor.freezeResourceGrowth() }.isFailure)
            oldSnapshot(first, "during-cancel-draft")
        }
        check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels(editor).contentEquals(beforePixels))
        oldSnapshots("cancel")
        editor.beginTransformDraft(context, id).use { draft ->
            check(draft.update(original.copy(x = 4.0)).box.x == 4.0)
            check(draft.update(original).box == original)
            check(draft.commit().let { !it.changed && it.revision == before.revision })
        }
        check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels(editor).contentEquals(beforePixels))
        oldSnapshots("noop")
        val competitorBytes = ledger.snapshot().availableBytes
        ledger.reserve(competitorBytes, "checkpoint transform competing owner").use {
            claims(competitorBytes, 1)
            check(runCatching { editor.beginTransformDraft(context, id) }.exceptionOrNull() is ResourceBudgetExceededException)
            check(runCatching { editor.captureCheckpointSnapshot() }.exceptionOrNull().let {
                it is CheckpointException && it.status == 3 && it.cause is ResourceBudgetExceededException
            })
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && !editor.hasActiveTransformDraft)
            claims(competitorBytes, 1)
        }
        claims()
        val captureAllowance = first.memory.capturePeakBytes - 1
        check(captureAllowance > 0 && ledger.snapshot().availableBytes > captureAllowance)
        val pressureBytes = ledger.snapshot().availableBytes - captureAllowance
        ledger.reserve(pressureBytes, "bounded native checkpoint capture refusal").use {
            val refusal = runCatching { editor.captureCheckpointSnapshot() }.exceptionOrNull()
            check(refusal is CheckpointException && refusal.status == 3 && refusal.cause == null)
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && !editor.hasActiveTransformDraft)
            claims(pressureBytes, 1)
        }
        claims()
        val hookFailure = runCatching {
            editor.captureCheckpointSnapshot(CheckpointAdmission.forTest(ledger, beforeCapture = {
                error("Checkpoint constructor refusal fixture")
            }))
        }.exceptionOrNull()
        check(hookFailure?.message == "Checkpoint constructor refusal fixture")
        claims()
        // This successful begin also proves failed captures did not leak eligible/ineligible owner counts.
        holdWriter.set(true)
        val concurrentFile = File(folder, "writer-claim-overlap.checkpoint")
        val pendingWriter = writer.submit<CheckpointWriteResult> { second.write(concurrentFile) }
        try {
            check(writerEntered.await(10, TimeUnit.SECONDS)) { "Checkpoint writer did not acquire its scratch claim" }
            check(writerScratch.get() == second.memory.writeScratchBytes && !concurrentFile.exists())
            claims(writerScratch.get(), 1)
            editor.beginTransformDraft(context, id).use { draft ->
                check(draft.update(original.copy(x = 5.0)).box.x == 5.0)
                check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
                claims(writerScratch.get() + draft.reservation.bytes, 2)
                val committed = draft.commit()
                check(committed.changed && committed.revision == before.revision + 1 && draft.commitConfirmed)
            }
            check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == original.copy(x = 5.0))
            claims(writerScratch.get(), 1)
            sourceUnchanged()
        } finally { holdWriter.set(false); writerRelease.countDown() }
        check(pendingWriter.get(10, TimeUnit.SECONDS).let { it.successful && it.capturedRevision == before.revision })
        check(concurrentFile.readBytes().contentEquals(frozenBytes))
        claims()
        val changedPixels = pixels(editor)
        check(!changedPixels.contentEquals(beforePixels))
        oldSnapshots("commit")
        editor.undo(false)
        check(editor.state().layers == before.layers && editor.state().redo && pixels(editor).contentEquals(beforePixels))
        oldSnapshots("undo")
        editor.undo(true)
        check(editor.state().layers.single().transform == original.copy(x = 5.0) && pixels(editor).contentEquals(changedPixels))
        oldSnapshots("redo")
        check(runCatching { editor.freezeResourceGrowth() }.isFailure)
        editor.close(); editorOpen = false
        claims()
        oldSnapshot(first, "editor-closed-first"); oldSnapshot(second, "editor-closed-second")
        claims()
        released(first); released(second)
        check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
        check(runCatching { first.write(File(folder, "closed.checkpoint")) }.isFailure)
        check(!File(folder, "closed.checkpoint").exists())
        // With no legitimate retained snapshot, freeze also detects a leaked total owner count after failed capture.
        NativeEditor(8, false).use { probe ->
            val probeId = probe.importPixels(2, 2, ByteBuffer.allocateDirect(sourceBytes.size).apply { put(sourceBytes); rewind() })
            val probeState = probe.state(); val probeClaims = ledger.snapshot()
            ledger.reserve(probeClaims.availableBytes - 32, "checkpoint capture failure count witness").use {
                val failure = runCatching { probe.captureCheckpointSnapshot() }.exceptionOrNull()
                check(failure is CheckpointException && failure.status == 3 && failure.cause == null)
            }
            check(runCatching {
                probe.captureCheckpointSnapshot(CheckpointAdmission.forTest(ledger, beforeCapture = {
                    error("Checkpoint probe constructor refusal")
                }))
            }.exceptionOrNull()?.message == "Checkpoint probe constructor refusal")
            check(probe.state() == probeState && ledger.snapshot().reservedBytes == probeClaims.reservedBytes &&
                ledger.snapshot().reservations == probeClaims.reservations)
            probe.freezeResourceGrowth(); probe.releaseResourceGrowth()
            probe.beginTransformDraft(context, probeId).use { check(!it.commit().changed) }
        }
        claims()
    } finally {
        holdWriter.set(false); writerRelease.countDown(); writer.shutdown()
        try { check(writer.awaitTermination(10, TimeUnit.SECONDS)) { "Checkpoint writer did not terminate" } }
        finally {
            try { snapshots.forEach { it.close() } }
            finally {
                try { editor.close() }
                finally {
                    folder.deleteRecursively()
                    check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
                }
            }
        }
    }
    return "Actual JNI same-ledger checkpoint/transform coexistence: two immutable snapshots preserve exact old state/source/pixels/bytes through cancel/no-op/commit/Undo/Redo and editor close. A latch-held admitted writer scratch claim coexists with one changed Move publication; document/draft/snapshot/writer claims reconcile exactly. Global and native capture refusal, constructor cleanup and foreign-ledger/project snapshot rejection preserve state and release ownership. Freeze still rejects held snapshots. This tests synchronous owner correctness and caller writer admission overlap, not native write execution overlap, UI ordering, RSS, durability faults or performance."
}
