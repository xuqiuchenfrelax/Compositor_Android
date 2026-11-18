package com.pojia.compositor.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A published draft cannot be replayed by another screen in this document lifetime. */
internal class TypedLayerSubmissionState {
    private var active: String? = null
    private val published = mutableSetOf<String>()
    @Synchronized fun begin(id: String): Boolean {
        if (active != null || id in published) return false
        active = id
        return true
    }
    @Synchronized fun finish(id: String, committed: Boolean): Boolean {
        if (active != id) return false
        if (committed) published.add(id)
        active = null
        return true
    }
}

internal enum class TypedLayerSubmissionKind { TEXT, ADJUSTMENT, ADJUSTMENT_CREATE }

/** Allocated before publication; recording the native ID cannot invoke an observer or throw. */
internal class AdjustmentLayerPublicationReceipt {
    var layerId: Long = 0
        internal set
}
internal data class TypedLayerSubmissionOutcome(val id: String, val kind: TypedLayerSubmissionKind,
    val published: Boolean, val failure: String?, val publishedRevision: Long? = null,
    val publishedLayerId: Long? = null, val createdAdjustmentStyle: AdjustmentLayerModel? = null)

/** Contains no screen or editor reference; the weak registry follows the retained document. */
internal class TypedLayerSubmissionOwner {
    private val state = TypedLayerSubmissionState()
    private val publishedOutcomes = mutableMapOf<String, TypedLayerSubmissionOutcome>()
    var pendingId by mutableStateOf<String?>(null)
        private set
    var presentationPendingId by mutableStateOf<String?>(null)
        private set
    val busy: Boolean get() = pendingId != null || presentationPendingId != null
    private var pendingKind: TypedLayerSubmissionKind? = null
    var outcome by mutableStateOf<TypedLayerSubmissionOutcome?>(null)
        private set
    @Synchronized fun begin(id: String, kind: TypedLayerSubmissionKind): Boolean {
        if (presentationPendingId != null) return false
        if (!state.begin(id)) return false
        outcome = null
        pendingKind = kind
        pendingId = id
        return true
    }
    @Synchronized fun finish(id: String, published: Boolean, failure: String? = null, publishedRevision: Long? = null,
        publishedLayerId: Long? = null, createdAdjustmentStyle: AdjustmentLayerModel? = null) {
        if (!state.finish(id, published)) return
        val result = TypedLayerSubmissionOutcome(id, checkNotNull(pendingKind), published, failure, publishedRevision,
            publishedLayerId, createdAdjustmentStyle)
        if (published) {
            publishedOutcomes[id] = result
            presentationPendingId = id
        }
        outcome = result
        pendingKind = null
        pendingId = null
    }
    /** Called after the current screen has applied and composed a matching presentation. */
    @Synchronized fun consumePresentation(id: String, revision: Long): Boolean {
        if (presentationPendingId != id) return false
        val result = publishedOutcomes[id] ?: return false
        if (result.publishedRevision?.let { revision < it } == true) return false
        presentationPendingId = null
        // The composed, saveable draft owns these settings now; historical receipts only need identity.
        if (result.createdAdjustmentStyle != null) {
            val delivered = result.copy(createdAdjustmentStyle = null)
            publishedOutcomes[id] = delivered
            if (outcome?.id == id) outcome = delivered
        }
        return true
    }
    @Synchronized fun finishUnexecuted() {
        pendingId?.let { finish(it, false, "Typed layer command did not execute") }
    }
    @Synchronized fun outcomeFor(id: String?): TypedLayerSubmissionOutcome? = id?.let {
        publishedOutcomes[it] ?: outcome?.takeIf { result -> result.id == it }
    }
}

private val typedLayerSubmissionOwners = java.util.WeakHashMap<NativeEditor, TypedLayerSubmissionOwner>()
internal fun typedLayerSubmissionOwner(editor: NativeEditor): TypedLayerSubmissionOwner = synchronized(typedLayerSubmissionOwners) {
    typedLayerSubmissionOwners.getOrPut(editor) { TypedLayerSubmissionOwner() }
}

/** Immutable input uses the retained dispatcher, whose tickets drain before document close. */
internal class SubmitTextLayer(private val draft: TextEditorDraft, private val style: TextLayerModel,
    private val owner: TypedLayerSubmissionOwner) : EditorCommand {
    override fun apply(editor: NativeEditor) = submitTypedLayer(editor, owner, draft.submissionId) {
        editor.commitTextLayer(draft.layerId, style, draft.pointX, draft.pointY)
    }
}

internal class SubmitAdjustmentLayer(private val draft: AdjustmentEditorDraft, private val style: AdjustmentLayerModel,
    private val owner: TypedLayerSubmissionOwner) : EditorCommand {
    override fun apply(editor: NativeEditor) {
        val receipt = AdjustmentLayerPublicationReceipt()
        submitTypedLayer(editor, owner, draft.submissionId, receipt) {
            check(draft.baseRevision < 0 || editor.currentRevision() == draft.baseRevision) {
                "The document changed before the adjustment was applied"
            }
            editor.commitAdjustmentLayer(draft.layerId, style, receipt)
        }
    }
}

/** Creation publishes the default layer before a separate, cancelable settings edit begins. */
internal class CreateAdjustmentLayer(private val submissionId: String, private val style: AdjustmentLayerModel,
    private val owner: TypedLayerSubmissionOwner) : EditorCommand {
    override fun apply(editor: NativeEditor) {
        val receipt = AdjustmentLayerPublicationReceipt()
        submitTypedLayer(editor, owner, submissionId, receipt, style) {
            editor.commitAdjustmentLayer(null, style, receipt)
        }
    }
}

private fun submitTypedLayer(editor: NativeEditor, owner: TypedLayerSubmissionOwner, id: String,
    receipt: AdjustmentLayerPublicationReceipt? = null, createdAdjustmentStyle: AdjustmentLayerModel? = null,
    action: () -> Unit) {
    var before: Long? = null
    var published = false
    var failure: String? = null
    try {
        before = editor.currentRevision()
        action()
        published = true
    } catch (error: Throwable) {
        failure = error.message
        throw error
    } finally {
        // An observer or receipt can throw after native publication. Never replay that edit.
        val revision = runCatching { editor.currentRevision() }.getOrNull()
        published = published || (before != null && revision?.let { it != before } == true)
        owner.finish(id, published, failure, if (published) revision else null,
            if (published) receipt?.layerId?.takeIf { it > 0 } else null,
            if (published) createdAdjustmentStyle else null)
    }
}
