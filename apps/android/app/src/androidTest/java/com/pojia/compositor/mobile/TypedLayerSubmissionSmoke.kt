package com.pojia.compositor.mobile

import java.util.concurrent.TimeUnit

/** The new immutable commands execute through the real retained queue and native transaction. */
internal fun typedLayerSubmissionSmoke(): String {
    NativeEditor(128, addDefaultLayers = false).use { editor ->
        val owner = typedLayerSubmissionOwner(editor)
        val style = TextLayerModel(content = "中文🚀 retained", fontSize = 18.0)
        val draft = TextEditorDraft(style, pointX = 16.0, pointY = 32.0)
        check(owner.begin(draft.submissionId, TypedLayerSubmissionKind.TEXT))
        check(!owner.begin(draft.submissionId, TypedLayerSubmissionKind.TEXT))
        check(!owner.begin("parallel-adjustment", TypedLayerSubmissionKind.ADJUSTMENT))
        check(editor.commandDispatcher.submit(SubmitTextLayer(draft, style, owner), null, null))
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        val textLayer = editor.state().layers.single()
        check(editor.textStyleOrNull(textLayer.id) == style)
        val textPresentation = editor.state()
        val textStyles = editor.typedLayerStylesForPresentation(textPresentation)
        check(textStyles.layerId == textLayer.id && textStyles.revision == textPresentation.revision &&
            textStyles.text == style && textStyles.adjustment == null)
        check(owner.outcome?.published == true && owner.pendingId == null)
        check(owner.busy && owner.presentationPendingId == draft.submissionId)
        check(!owner.begin("before-current-presentation", TypedLayerSubmissionKind.ADJUSTMENT))
        owner.finishUnexecuted()
        check(owner.busy && !owner.consumePresentation(draft.submissionId, textPresentation.revision - 1))
        check(owner.consumePresentation(draft.submissionId, textPresentation.revision) && !owner.busy)
        check(typedLayerSubmissionOwner(editor) === owner)
        check(!typedLayerSubmissionOwner(editor).begin(draft.submissionId, TypedLayerSubmissionKind.TEXT))
        editor.undo(false)
        check(editor.state().layers.isEmpty()) { "Repeated apply created extra text undo items" }
        check(editor.textStyleOrNull(textLayer.id) == null && editor.adjustmentStyleOrNull(textLayer.id) == null) {
            "A stale text layer ID from before undo must not reach a failing JNI lookup"
        }
        check(editor.typedLayerStylesForPresentation(textPresentation).text == null)
        editor.undo(true)
        check(editor.textStyleOrNull(textLayer.id) == style)

        val invalid = TextEditorDraft(style, layerId = Long.MAX_VALUE)
        check(owner.begin(invalid.submissionId, TypedLayerSubmissionKind.TEXT))
        check(editor.commandDispatcher.submit(SubmitTextLayer(invalid, style, owner), null, null))
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        check(owner.outcome?.published == false && owner.pendingId == null)
        check(editor.state().layers.size == 1)
        check(owner.begin(invalid.submissionId, TypedLayerSubmissionKind.TEXT))
        owner.finish(invalid.submissionId, false)

        val adjustment = AdjustmentEditorDraft(AdjustmentLayerModel.create(AdjustmentKind.INVERT))
        check(owner.begin(adjustment.submissionId, TypedLayerSubmissionKind.ADJUSTMENT))
        check(editor.commandDispatcher.submit(SubmitAdjustmentLayer(adjustment, adjustment.style, owner), null, null))
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        val adjustmentLayer = editor.state().layers.single { it.id != textLayer.id }
        check(editor.adjustmentStyleOrNull(adjustmentLayer.id)?.kind == AdjustmentKind.INVERT)
        val adjustmentPresentation = editor.state()
        val adjustmentStyles = editor.typedLayerStylesForPresentation(adjustmentPresentation)
        check(adjustmentStyles.layerId == adjustmentLayer.id && adjustmentStyles.text == null &&
            adjustmentStyles.adjustment?.kind == AdjustmentKind.INVERT)
        check(owner.outcome?.published == true && owner.outcome?.kind == TypedLayerSubmissionKind.ADJUSTMENT)
        check(owner.busy && owner.consumePresentation(adjustment.submissionId, adjustmentPresentation.revision))
        check(!owner.begin(adjustment.submissionId, TypedLayerSubmissionKind.ADJUSTMENT))
        editor.undo(false)
        check(editor.state().layers.size == 1)
        check(editor.adjustmentStyleOrNull(adjustmentLayer.id) == null && editor.textStyleOrNull(adjustmentLayer.id) == null)
        check(editor.typedLayerStylesForPresentation(adjustmentPresentation).adjustment == null)
        editor.deleteLayer(textLayer.id)
        check(editor.textStyleOrNull(textLayer.id) == null && editor.adjustmentStyleOrNull(textLayer.id) == null)
        editor.undo(false)
        check(editor.textStyleOrNull(textLayer.id) == style)
    }
    return "Typed retained text/adjustment commands: Chinese create/readback, atomic presentation styles, stale IDs after undo/delete return null, one undo item, shared admission, duplicate rejection, prepublication failure retry; UI rotation/picker/IME not exercised"
}
