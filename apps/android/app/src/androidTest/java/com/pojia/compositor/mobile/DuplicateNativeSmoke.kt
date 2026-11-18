package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.nio.ByteBuffer

internal fun duplicateNativeSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val baselineClaims = ImageIoAdmission.ledger.snapshot()
    val editor = NativeEditor(16, false)
    var ordinal = 0L
    var owner: TransformController.Owner? = null
    var currentDraft: NativeTransformDraft? = null
    fun begin(): NativeTransformDraft {
        val selected = editor.selection()
        val value = TransformController.Owner(++ordinal, 1, 1, ordinal, false,
            selected.primary, TransformTarget.LAYER, duplicateAtDown = true)
        check(editor.reserveTransformControl(value) {})
        owner = value
        return checkNotNull(editor.beginMovePress(context, value, MovePressInput(MovePoint(.5, .5)))).also {
            currentDraft = it
        }
    }
    fun retire() {
        owner?.let { editor.retireTransformControl(it) }
        owner = null
    }
    fun prepare(): NativeTransformDraft {
        val down = begin()
        down.settleDuplicatePredecessor()
        down.commit()
        return editor.beginTransformDraft(context, checkNotNull(owner).rootLayerId,
            TransformTarget.LAYER, checkNotNull(owner)).also { currentDraft = it }
    }
    try {
        val bytes = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(64, 0, 0, -1)); rewind() }
        val source = editor.importPixels(1, 1, bytes)
        editor.selectLayer(source)
        val before = editor.state()
        val down = begin()
        check(editor.state().layers.size == before.layers.size)
        down.commit(); retire()
        check(editor.state().layers.size == before.layers.size)

        val draft = prepare()
        check(editor.promoteDuplicate(context, draft) == DuplicateOutcome.PROMOTED)
        val beforePresentation = ImageIoAdmission.ledger.snapshot()
        editor.refreshDuplicatePresentation(draft)
        val presentationClaims = ImageIoAdmission.ledger.snapshot()
        check(presentationClaims.reservations == beforePresentation.reservations + 1 &&
            presentationClaims.reservedBytes > beforePresentation.reservedBytes)
        val preview = checkNotNull(editor.duplicateLayerPresentation())
        check(preview.state.layers.size == before.layers.size + 1)
        check(preview.state.layers.last().id != source)
        check(preview.mapping.any { it.sourceId == source && it.copyId == preview.selection.primary && it.root })
        check(editor.state().layers.size == before.layers.size)
        val original = draft.info().box
        draft.update(original.copy(x = original.x + 2), draft.info().sampling)
        val result = draft.commit(); retire()
        check(ImageIoAdmission.ledger.snapshot().reservations < presentationClaims.reservations)
        check(result.changed && editor.duplicateLayerPresentation() == null)
        check(editor.state().layers.size == before.layers.size + 1)
        editor.undo(false)
        check(editor.state().layers.size == before.layers.size)
        editor.undo(true)
        check(editor.state().layers.size == before.layers.size + 1)

        editor.selectLayer(source)
        val cancel = prepare()
        check(editor.promoteDuplicate(context, cancel) == DuplicateOutcome.PROMOTED)
        editor.refreshDuplicatePresentation(cancel)
        val cancelPresentationClaims = ImageIoAdmission.ledger.snapshot().reservations
        val cancelled = cancel.cancelWithResult(); retire()
        check(ImageIoAdmission.ledger.snapshot().reservations < cancelPresentationClaims)
        check(cancelled != null && editor.duplicateLayerPresentation() == null)
        check(editor.state().layers.size == before.layers.size + 1)

        editor.selectLayer(source)
        val acknowledgedCancel = prepare()
        check(editor.promoteDuplicate(context, acknowledgedCancel) == DuplicateOutcome.PROMOTED)
        val noQuery = object : DuplicateCancelTransport {
            override fun query(document: Long, instance: Long, token: Long, output: ByteBuffer): Int =
                error("Successful cancel must reconcile from its returned result")
        }
        check(acknowledgedCancel.cancelWithResult(noQuery) != null); retire()
        check(!editor.hasUnresolvedDuplicateCancel)

        editor.selectLayer(source)
        val uncertain = prepare()
        var deliveries = 0
        val lost = object : DuplicateTransport {
            override fun promote(document: Long, request: ByteBuffer, limit: Long, resident: Long,
                output: ByteBuffer): Int {
                deliveries++
                NativeDuplicateBridge.promote(document, request, limit, resident, output)
                throw IllegalStateException("duplicate JNI return lost after publication")
            }
        }
        check(editor.promoteDuplicate(context, uncertain, lost) == DuplicateOutcome.PROMOTED)
        check(deliveries == 1)
        uncertain.cancelWithResult(); retire()
        check(!editor.hasUnresolvedDuplicate && !editor.hasUnresolvedDuplicateCancel)

        val beforeUnknownClose = ImageIoAdmission.ledger.snapshot()
        val closing = NativeEditor(16, false)
        var closingOwner: TransformController.Owner? = null
        try {
            val pixel = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(1, 2, 3, -1)); rewind() }
            val closingSource = closing.importPixels(1, 1, pixel)
            closing.selectLayer(closingSource)
            val selected = closing.selection()
            val owner = TransformController.Owner(999, 1, 1, 999, false,
                selected.primary, TransformTarget.LAYER, duplicateAtDown = true)
            closingOwner = owner
            check(closing.reserveTransformControl(owner) {})
            val press = checkNotNull(closing.beginMovePress(context, owner,
                MovePressInput(MovePoint(.5, .5))))
            press.settleDuplicatePredecessor()
            press.commit()
            val closingDraft = closing.beginTransformDraft(context, owner.rootLayerId,
                TransformTarget.LAYER, owner)
            val unknown = object : DuplicateTransport {
                override fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int =
                    error("duplicate receipt unavailable")
            }
            check(runCatching { closing.promoteDuplicate(context, closingDraft, unknown) }.isFailure)
            check(closing.hasUnresolvedDuplicate)
        } finally {
            closingOwner?.let(closing::retireTransformControl)
            closing.close()
        }
        val afterUnknownClose = ImageIoAdmission.ledger.snapshot()
        check(afterUnknownClose.reservedBytes == beforeUnknownClose.reservedBytes &&
            afterUnknownClose.reservations == beforeUnknownClose.reservations)
        return "Duplicate JNI promotion: first-drag-only copy, read-only topology/mapping, one commit entry, undo/redo, cancel and exact lost-delivery recovery."
    } finally {
        owner?.let { value ->
            runCatching { editor.recoverDuplicate() }
            runCatching { editor.recoverDuplicateCancel() }
            currentDraft?.let { runCatching { editor.cancelTransformDraft(it) } }
            runCatching { editor.retireTransformControl(value) }
        }
        editor.close()
        val finalClaims = ImageIoAdmission.ledger.snapshot()
        check(finalClaims.reservedBytes == baselineClaims.reservedBytes &&
            finalClaims.reservations == baselineClaims.reservations)
    }
}
