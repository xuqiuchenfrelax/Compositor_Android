package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.nio.ByteBuffer

/** Actual ABI13 JNI owner; synchronous draft correctness with installed-document resource transfers. */
internal fun transformDraftSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    val editor = NativeEditor(8, false)
    var preview: NativeScene? = null
    val notifications = mutableListOf<Long>()
    fun pixels(scene: NativeScene): ByteArray {
        val output = ByteBuffer.allocateDirect(8 * 8 * 4)
        scene.draw(8, 8, Viewport(0.0, 0.0, 8.0, 8.0), output)
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    try {
        val source = ByteBuffer.allocateDirect(2 * 2 * 4)
        repeat(4) { source.put(byteArrayOf(-1, 0, 0, -1)) }; source.rewind()
        val id = editor.importPixels(2, 2, source)
        editor.selectLayer(id)
        editor.renameLayer(id, "redo fixture"); editor.undo(false)
        val baseline = editor.state()
        check(baseline.redo)
        val original = baseline.layers.single().transform
        val originalPixels = checkNotNull(editor.captureScene()).use(::pixels)
        editor.committed = { notifications.add(it) }
        val baselineInstalled = editor.installedDocumentBytes()
        val beforeBegin = ledger.snapshot()
        val draft = editor.beginTransformDraft(context, id)
        check(editor.hasActiveTransformDraft && draft.isActive)
        val token = draft.info().token
        check(token > 0 && draft.info().baseRevision == baseline.revision)
        check(draft.member().original == original && draft.member().layerId == id)
        val draftMemory = draft.memory()
        check(draftMemory.editPeakBytes >= draftMemory.ownedBytes && draftMemory.previewCapturePeakBytes > 0)
        repeat(99) { index ->
            val updated = draft.update(original.copy(x = original.x + (index + 1) * 2.0 / 99))
            check(updated.baseRevision == baseline.revision && updated.token == token)
            check(editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null)) { "Draft metadata leaked into committed getters/history" }
        }
        check(notifications.isEmpty())
        check(editor.reserveStroke() == null && editor.tryRegisterPendingEdit() == null)
        check(runCatching { editor.setOpacity(id, .5) }.isFailure)
        check(runCatching { editor.startStroke(id, 2f, false) }.isFailure)
        check(runCatching { editor.freezeResourceGrowth() }.isFailure)
        check(runCatching { editor.captureScene() }.isFailure)
        check(runCatching { editor.captureProjectSnapshot() }.isFailure)
        check(runCatching { editor.captureCheckpointSnapshot() }.isFailure)
        check(runCatching { editor.beginTransformDraft(context, id) }.isFailure)
        val badUpdate = runCatching { draft.update(original.copy(x = Double.NaN)) }.exceptionOrNull()
        check(badUpdate is TransformDraftException && badUpdate.status == 1)
        check(draft.info().box.x == original.x + 2.0 && editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null))
        preview = draft.captureScene()
        val movedPixels = pixels(checkNotNull(preview))
        check(!movedPixels.contentEquals(originalPixels))
        check(movedPixels[(0 * 8 + 0) * 4 + 3] == 0.toByte() &&
            movedPixels[(0 * 8 + 2) * 4] == (-1).toByte()) { "Absolute draft preview did not move the source two pixels" }
        val sceneClaim = ledger.snapshot().reservedBytes - beforeBegin.reservedBytes - draft.reservation.bytes
        check(sceneClaim > 0)
        draft.close(); draft.close()
        check(!draft.isActive && !editor.hasActiveTransformDraft && editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null) && notifications.isEmpty())
        check(runCatching { draft.update(original) }.isFailure)
        check(pixels(checkNotNull(preview)).contentEquals(movedPixels)) { "Preview did not survive cancel" }
        check(ledger.snapshot().reservedBytes == beforeBegin.reservedBytes + sceneClaim)
        check(runCatching { editor.freezeResourceGrowth() }.isFailure)
        checkNotNull(preview).close(); preview = null
        check(ledger.snapshot().reservedBytes == beforeBegin.reservedBytes)
        editor.beginTransformDraft(context, id).use { noOp ->
            check(noOp.info().token > token)
            val outcome = noOp.commit()
            check(noOp.commitConfirmed && !outcome.changed && !noOp.isActive)
            check(outcome.revision == baseline.revision && noOp.committedResult == outcome)
        }
        check(editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null) && notifications.isEmpty())
        check(ledger.snapshot().reservedBytes == beforeBegin.reservedBytes) { "No-op retained a needless draft claim" }
        val changed = editor.beginTransformDraft(context, id)
        changed.update(original.copy(x = original.x + 2))
        val result = changed.commit()
        check(result.changed && result.revision == baseline.revision + 1 && changed.commitConfirmed && !changed.isActive)
        check(notifications == listOf(result.revision))
        check(editor.state().layers.single().transform.x == original.x + 2 && !editor.state().redo)
        check(runCatching { changed.commit() }.isFailure)
        val retained = ledger.snapshot().reservedBytes
        check(retained == beforeBegin.reservedBytes - baselineInstalled + editor.installedDocumentBytes()) { "Commit retained a transient or duplicate document claim" }
        changed.close()
        check(ledger.snapshot().reservedBytes == retained) { "Closing a committed token released retained document/history accounting" }
        editor.undo(false)
        check(editor.state().layers == baseline.layers && editor.state().redo)
        editor.undo(true)
        check(editor.state().layers.single().transform.x == original.x + 2)
        // A throwing observer cannot disguise a successful native publication as retryable.
        editor.committed = { throw IllegalStateException("observer fixture") }
        val observerDraft = editor.beginTransformDraft(context, id)
        observerDraft.update(original.copy(x = original.x + 3))
        check(runCatching { observerDraft.commit() }.isFailure)
        check(observerDraft.commitConfirmed && observerDraft.committedResult?.changed == true && !observerDraft.isActive)
        check(editor.state().layers.single().transform.x == original.x + 3)
        check(runCatching { observerDraft.commit() }.isFailure)
        editor.committed = null
        val closing = editor.beginTransformDraft(context, id)
        closing.update(original.copy(x = original.x + 4))
        preview = closing.captureScene()
        val closingPixels = pixels(checkNotNull(preview))
        editor.close()
        check(!closing.isActive && !closing.commitConfirmed)
        check(pixels(checkNotNull(preview)).contentEquals(closingPixels)) { "Preview did not survive document close" }
        checkNotNull(preview).close(); preview = null
        check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
        NativeEditor(8, false).use { refused ->
            val pixels = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(-1, 0, 0, -1)); rewind() }
            val root = refused.importPixels(1, 1, pixels)
            ledger.reserve(ledger.snapshot().availableBytes, "transform competing owner").use {
                check(runCatching { refused.beginTransformDraft(context, root) }.exceptionOrNull() is ResourceBudgetExceededException)
                check(!refused.hasActiveTransformDraft)
            }
            val beforeGroupRefusal = refused.state()
            val selectedBeforeGroupRefusal = refused.selection()
            val claimsBeforeGroupRefusal = ledger.snapshot()
            val groupError = runCatching { refused.beginTransformDraft(context, root, TransformTarget.GROUP) }.exceptionOrNull()
            check(groupError is TransformDraftException && groupError.status == 5)
            check(refused.state() == beforeGroupRefusal && refused.selection() == selectedBeforeGroupRefusal &&
                !refused.hasActiveTransformDraft && !refused.hasUnresolvedMovePress)
            check(ledger.snapshot().reservedBytes == claimsBeforeGroupRefusal.reservedBytes &&
                ledger.snapshot().reservations == claimsBeforeGroupRefusal.reservations)
            refused.createMask(root, true); refused.linkMask(root, false); refused.enableMask(root, false)
            val maskBaseline = refused.state()
            val maskOriginal = maskBaseline.layers.single().transform
            refused.beginTransformDraft(context, root, TransformTarget.UNLINKED_MASK).use { maskDraft ->
                val member = maskDraft.member()
                check(member.hasMask && !member.maskLinked && !member.maskEnabled && !member.hasMaskPlacement)
                check(maskDraft.info().target == TransformTarget.UNLINKED_MASK)
                maskDraft.update(maskOriginal.copy(x = maskOriginal.x + .5))
                check(refused.state().copy(selectedLayerActions = null) == maskBaseline.copy(selectedLayerActions = null))
                check(maskDraft.commit().changed)
            }
            val movedMask = refused.state().layers.single()
            check(movedMask.transform == maskOriginal && movedMask.maskPlacement?.x == maskOriginal.x + .5 && !movedMask.maskEnabled)
            refused.undo(false)
            check(refused.state().layers == maskBaseline.layers)
        }
        groupTransformDraftSmoke(context)
        return "ABI13 actual JNI transform owner: 99 absolute metadata updates; committed getters/history unchanged; exact two-pixel preview; invalid update retained draft; conflict gates; cancel/no-op preserve redo/revision; changed commit one revision/one undo; observer failure confirmed publication; preview survives cancel/document close; disabled unlinked 1x1 mask typed member/commit/undo; claims release after native owners; competing ledger admission refused; typed group/member/bounds/preview/cancel/one-history checks. Canvas/controller are separate selectors; no device performance claim."
    } finally {
        preview?.close(); editor.committed = null; editor.close()
        check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
    }
}

/** Real folder/multiroot requests retain their declared target and expand each visible pixel member once. */
private fun groupTransformDraftSmoke(context: android.content.Context) {
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    NativeEditor(16, false).use { editor ->
        fun pixel(red: Byte, x: Double, y: Double, width: Double, height: Double): Long {
            val id = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
                put(byteArrayOf(red, 0, 0, -1)); rewind()
            })
            editor.setTransform(id, Transform64(x, y, width, height))
            return id
        }
        val folder = editor.addGroup()
        val first = pixel(64, 2.0, 3.0, 2.0, 4.0)
        val second = pixel(96, 8.0, 9.0, 4.0, 2.0)
        editor.reparentLayer(first, folder); editor.reparentLayer(second, folder)
        editor.setOpacity(second, 0.0)
        val hidden = pixel(32, -100.0, -100.0, 200.0, 200.0)
        editor.reparentLayer(hidden, folder); editor.setVisible(hidden, false)
        val empty = editor.addGroup()
        editor.selectLayer(folder)
        val baseline = editor.state()
        val content = editor.currentContentIdentity()
        val stable = ledger.snapshot()
        val notifications = mutableListOf<Long>()
        editor.committed = { notifications.add(it) }
        val expected = Transform64(2.0, 3.0, 10.0, 8.0)
        editor.beginTransformDraft(context, folder, TransformTarget.GROUP).use { draft ->
            check(draft.info().target == TransformTarget.GROUP && draft.info().rootCount == 1 && draft.info().memberCount == 2)
            check(draft.info().box == expected && draft.members().map { it.layerId }.toSet() == setOf(first, second))
            draft.update(expected.copy(x = 4.0))
            check(editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null) && editor.currentContentIdentity() == content && notifications.isEmpty())
            draft.captureScene().use { scene ->
                val output = ByteBuffer.allocateDirect(16 * 16 * 4)
                scene.draw(16, 16, Viewport(0.0, 0.0, 16.0, 16.0), output)
                check(output.get((3 * 16 + 2) * 4 + 3) == 0.toByte() &&
                    output.get((3 * 16 + 4) * 4 + 3) == (-1).toByte())
            }
        }
        check(editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null) && !editor.hasActiveTransformDraft && !editor.hasUnresolvedMovePress && notifications.isEmpty())
        check(ledger.snapshot().reservedBytes == stable.reservedBytes && ledger.snapshot().reservations == stable.reservations)
        editor.selectLayers(listOf(folder, first), folder)
        val beforeCommit = editor.state()
        editor.beginTransformDraft(context, folder, TransformTarget.GROUP).use { draft ->
            check(draft.info().target == TransformTarget.GROUP && draft.info().rootCount == 2 && draft.info().memberCount == 2)
            check(draft.info().box == expected && draft.members().map { it.layerId }.toSet() == setOf(first, second))
            draft.update(expected.copy(x = 3.0))
            val result = draft.commit()
            check(result.changed && result.revision == beforeCommit.revision + 1 && draft.commitConfirmed)
        }
        val committed = editor.state()
        check(notifications == listOf(committed.revision))
        check(committed.layers.first { it.id == first }.transform.x == 3.0 &&
            committed.layers.first { it.id == second }.transform.x == 9.0 &&
            committed.layers.first { it.id == hidden } == beforeCommit.layers.first { it.id == hidden })
        editor.undo(false)
        check(editor.state().layers == beforeCommit.layers)
        editor.undo(true)
        check(editor.state().layers == committed.layers && !editor.hasActiveTransformDraft && !editor.hasUnresolvedMovePress)
        editor.selectLayer(empty)
        val beforeEmpty = editor.state()
        val emptyClaims = ledger.snapshot()
        check(runCatching { editor.beginTransformDraft(context, empty, TransformTarget.GROUP) }.isFailure)
        check(editor.state() == beforeEmpty && !editor.hasActiveTransformDraft && !editor.hasUnresolvedMovePress)
        check(ledger.snapshot().reservedBytes == emptyClaims.reservedBytes && ledger.snapshot().reservations == emptyClaims.reservations)
    }
    check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
}
