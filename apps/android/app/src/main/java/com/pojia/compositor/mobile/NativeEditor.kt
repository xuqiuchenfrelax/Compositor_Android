package com.pojia.compositor.mobile

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import kotlin.math.cos
import kotlin.math.sin

internal data class LayerState(val id: Long, val opacity: Double, val visible: Boolean, val blend: Int,
    val name: String = "", val parentId: Long = 0, val maskSourceId: Long = 0,
    val isGroup: Boolean = false, val hasMask: Boolean = false, val maskEnabled: Boolean = true,
    val maskLinked: Boolean = true, val maskWidth: Int = 0, val maskHeight: Int = 0,
    val pixelWidth: Int = 0, val pixelHeight: Int = 0, val transform: Transform64 = Transform64(),
    val sampling: Int = 0, val uuid: String = "", val assetPresent: Boolean = false,
    val maskPlacement: Transform64? = null, val maskSampling: Int = 0,
    val kind: LayerKind = if (isGroup) LayerKind.FOLDER else LayerKind.IMAGE)
internal data class LayerTransformChange(val id: Long, val transform: Transform64)
internal data class EditorState(
    val layers: List<LayerState> = emptyList(), val undo: Boolean = false,
    val redo: Boolean = false, val revision: Long = 0, val bytes: Long = 0,
    val document: DocumentMetadata = DocumentMetadata(),
    val selectedLayerActions: SelectedLayerActions? = null,
)
internal data class TypedLayerStyleSnapshot(val revision: Long, val layerId: Long,
    val text: TextLayerModel? = null, val adjustment: AdjustmentLayerModel? = null)
/** Native writer outcome, or a snapshot preparation refusal synthesized from caller admission/native capture/JNI status.
 * An unavailable revision does not imply that no internal state copy occurred. Transport failures have no result. */
internal data class CheckpointWriteResult(val status: Int, val capturedRevision: Long,
    val revisionCaptured: Boolean, val destinationPublished: Boolean, val durabilityConfirmed: Boolean) {
    val successful: Boolean get() = status == 0 && revisionCaptured && destinationPublished && durabilityConfirmed
    companion object {
        fun fromNative(values: LongArray): CheckpointWriteResult {
            check(values.size == 5 && values[0] in 0..Int.MAX_VALUE.toLong() && values[1] >= 0 &&
                values.sliceArray(2..4).all { it == 0L || it == 1L }) { "Checkpoint write outcome is unavailable" }
            val result = CheckpointWriteResult(values[0].toInt(), values[1], values[2] != 0L,
                values[3] != 0L, values[4] != 0L)
            check((!result.destinationPublished || result.revisionCaptured) &&
                (!result.durabilityConfirmed || result.destinationPublished) &&
                (result.revisionCaptured || result.capturedRevision == 0L) &&
                (result.status != 0 || result.successful)) { "Checkpoint write outcome is inconsistent" }
            return result
        }
    }
}
internal class CheckpointException(val status: Int, val write: CheckpointWriteResult? = null) : IllegalStateException(when {
    write?.destinationPublished == true && !write.durabilityConfirmed ->
        "A checkpoint file was published, but its storage sync was not confirmed."
    write != null && !write.destinationPublished && status != 4 -> "The checkpoint file was not published ($status)."
    write?.destinationPublished == true -> "The checkpoint file was published and synced, but the operation failed ($status)."
    else -> when (status) {
        3 -> "The saved document exceeds this device's memory budget"
        4 -> "Finish or cancel the active stroke before saving"
        7 -> "The document could not be read or saved"
        8 -> "The saved document is damaged or unsupported"
        else -> "Document checkpoint operation failed ($status)"
    }
})
internal data class RetainedMemory(val revision: Long, val tileBytes: Long, val stateBytes: Long,
    val imageCatalogBytes: Long, val totalBytes: Long)
internal data class BrushCursor(val x: Double, val y: Double, val layerId: Long, val mask: Boolean) {
    init { require(x.isFinite() && y.isFinite() && layerId > 0) }
}
/** One-shot command-gate wakeups; registering after an unlock cannot lose the redraw. */
private class DisplayCommandLock : ReentrantLock() {
    private val wakeGate = Any()
    private val waiters = ArrayList<() -> Unit>()
    @Volatile private var hasWaiters = false

    fun awaitAvailable(wake: () -> Unit): AutoCloseable {
        synchronized(wakeGate) { waiters.add(wake); hasWaiters = true }
        // The lock may have become available before registration. Recheck without
        // waiting; the outer unlock delivers this registration and any older ones.
        if (tryLock()) unlock()
        return AutoCloseable {
            synchronized(wakeGate) { waiters.remove(wake); hasWaiters = waiters.isNotEmpty() }
        }
    }

    override fun unlock() {
        val outer = isHeldByCurrentThread && holdCount == 1
        super.unlock()
        if (outer && hasWaiters) {
            // Bound this pass and avoid allocating a collection after publication.
            var remaining = synchronized(wakeGate) { waiters.size }
            while (remaining-- > 0) {
                val wake = synchronized(wakeGate) {
                    if (waiters.isEmpty()) null
                    else waiters.removeAt(0).also { hasWaiters = waiters.isNotEmpty() }
                } ?: break
                // Wakeups only schedule work; a display callback cannot fail an editor command.
                runCatching(wake)
            }
        }
    }
}

internal const val PENDING_EDIT_MESSAGE = "Wait for pending edits to finish, then try again."
internal class PendingEditTicket internal constructor(internal val owner: NativeEditor, internal val id: Long,
    private val settleAction: () -> Unit) {
    fun settle() = settleAction()
}

/** Shared native snapshots can render while IO saves; destruction waits for lifetime readers. */
internal class NativeEditor private constructor(documentSize: Int, addDefaultLayers: Boolean,
    checkpoint: File?, checkpointBudget: RecoveryBudget?, importedLoader: (() -> Long)?,
    internal val resourceLedger: ResourceBudgetLedger = ImageIoAdmission.ledger,
    preadmittedClaim: ResourceBudgetLedger.Reservation? = null, documentHeight: Int = documentSize) : AutoCloseable {
    constructor(documentSize: Int = SIZE, addDefaultLayers: Boolean = true) :
        this(documentSize, addDefaultLayers, null, null, null)
    companion object {
        init { System.loadLibrary("compositor_android") }
        private val NATIVE_ABI_VERSION = BuildConfig.COMPOSITOR_NATIVE_ABI_VERSION
        const val SIZE = 2048
        private val nextPreviewIdentity = java.util.concurrent.atomic.AtomicLong()
        internal fun createWithLedgerForTest(documentSize: Int, ledger: ResourceBudgetLedger,
            addDefaultLayers: Boolean = true) = NativeEditor(documentSize, addDefaultLayers, null, null, null, ledger)
        internal fun rectangular(width: Int, height: Int): NativeEditor {
            require(width in 1..30000 && height in 1..30000)
            return NativeEditor(width, false, null, null, null, documentHeight = height)
        }
        fun load(file: File) = RecoveryAdmission().load(file)
        internal fun loadWithBudget(file: File, budget: RecoveryBudget, ledger: ResourceBudgetLedger,
            claim: ResourceBudgetLedger.Reservation) =
            NativeEditor(SIZE, false, file, budget, null, ledger, claim)
        /** Returns an independent candidate; no workspace or recovery file is accepted. */
        fun loadProjectCandidate(context: android.content.Context, directory: File,
            limitBytes: Long, residentBytes: Long): NativeEditor =
            loadProjectCandidateOwned(context, directory, limitBytes, residentBytes, false)
        internal fun loadCancelledProjectCandidateForTest(context: android.content.Context, directory: File,
            limitBytes: Long, residentBytes: Long): NativeEditor =
            loadProjectCandidateOwned(context, directory, limitBytes, residentBytes, true)
        private fun loadProjectCandidateOwned(context: android.content.Context, directory: File,
            limitBytes: Long, residentBytes: Long, cancelled: Boolean): NativeEditor {
            require(limitBytes > 0 && residentBytes in 0..limitBytes)
            val ledger = ImageIoAdmission.ledger
            val allowance = minOf(limitBytes - residentBytes, ledger.snapshot().availableBytes)
            if (allowance == 0L) throw ResourceBudgetExceededException(1, 0, "project candidate")
            val claim = ledger.reserve(allowance, "project candidate")
            try {
                return NativeEditor(SIZE, false, null, null, {
                    NativeProjectIO.load(context, directory, Math.addExact(residentBytes, allowance), residentBytes, cancelled)
                }, ledger, claim)
            } catch (error: Throwable) { claim.close(); throw error }
        }
    }
    private val lifetime = ReentrantReadWriteLock()
    private val commands = DisplayCommandLock()
    private var handle = 0L
    private var strokeActive = false
    private var healingStrokeActive = false
    private var brushCursor: BrushCursor? = null
    private var resourceGrowthFrozen = false
    private var retainedHandles = 0
    private var transformDraft: NativeTransformDraft? = null
    private var documentClaim: ResourceBudgetLedger.Reservation? = preadmittedClaim
    private val retainedRecord = NativeTransformBridge.record(24)
    private val sceneMemoryRecord = NativeTransformBridge.record(40)
    private val displayMemoryRecord = NativeTransformBridge.record(48)
    private val sceneHandleRecord = NativeTransformBridge.record(8)
    private var strokePublication: ResourceBudgetLedger.Reservation? = null
    private var strokeTransfer: ResourceBudgetLedger.TransferPlan? = null
    private var publicationEdit: ResourceBudgetLedger.Reservation? = null
    private var publicationTransfer: ResourceBudgetLedger.TransferPlan? = null
    private var transformControl: TransformController.Owner? = null
    private var cancelTransformControl: (() -> Unit)? = null
    private var retainedTransform: TransformController? = null
    private var nextDistortOperation = 1L
    private var activeDistortToken = 0L
    private var distortSessionActive = false
    internal fun retainedTransformController(context: android.content.Context): TransformController = commands.withLock {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        retainedTransform ?: run {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            TransformController(this, context.applicationContext, { action -> handler.post { action() }; Unit })
                .also { retainedTransform = it }
        }
    }

    /**
     * Opens the P55 worker while the document command gate is held. The native session borrows
     * the document, so every subsequent session operation below is fenced by this editor's
     * lifetime read lock; closeNative cannot destroy the document while a session is live.
     */
    internal fun beginDistortSession(layer: Long): NativeDistortSession {
        return commands.withLock { lifetime.read {
            check(handle != 0L && !commandClosing) { "Document is closed or closing" }
            check(!resourceGrowthFrozen && !distortSessionActive && !strokeActive && strokes.isEmpty() && transformDraft == null &&
                transformControl == null && pendingEdits.isEmpty()) {
                "Finish or cancel the active edit before distorting"
            }
            check(layer > 0L) { "Distort requires a pixel layer" }
            val budget = resourceLedger.snapshot()
            // Keep half the currently available envelope for the output worker and leave the
            // other half for P54 commit metadata settlement. This is conservative admission;
            // the native Control never exceeds the pinned envelope.
            val envelope = minOf(budget.availableBytes,
                maxOf(budget.residentBytes, budget.availableBytes / 2))
            check(envelope > 0L && budget.residentBytes <= envelope) {
                "Not enough memory for a Distort session"
            }
            val arena = resourceLedger.tryReserve(envelope, "Distort native session")
                ?: throw ResourceBudgetExceededException(envelope, budget.availableBytes, "Distort native session")
            val draft = NativeDistortSessionBridge.record(NativeDistortSessionBridge.DRAFT_BYTES)
            try {
                val token = NativeDistortSessionBridge.begin(handle, layer, envelope, budget.residentBytes, draft)
                check(token > 0L) { "Native Distort session admission failed" }
                activeDistortToken = token
                distortSessionActive = true
                val ids = NativeDistortSessionBridge.draft(draft)
                val identity = DistortDraftIdentity(ids[0].toULong(), ids[1].toULong(), ids[2].toULong(),
                    ids[3].toULong(), ids[4].toULong())
                NativeDistortSession(this@NativeEditor, token, identity,
                    arena, envelope, (budget.availableBytes - envelope).coerceAtLeast(0L))
            } catch (error: Throwable) {
                arena.close()
                throw error
            }
        } }
    }

    internal fun distortSubmit(token: Long, quad: java.nio.ByteBuffer, ticket: java.nio.ByteBuffer): Int =
        commands.withLock { lifetime.read { NativeDistortSessionBridge.submitQuad(token, quad, ticket) } }
    internal fun distortPublish(token: Long, ticket: java.nio.ByteBuffer): Int =
        commands.withLock { lifetime.read { NativeDistortSessionBridge.publish(token, ticket) } }
    internal fun distortSnapshot(token: Long, output: java.nio.ByteBuffer): Int =
        commands.withLock { lifetime.read { NativeDistortSessionBridge.snapshot(token, output) } }
    internal fun distortCancel(token: Long): Int =
        commands.withLock { lifetime.read { NativeDistortSessionBridge.cancelDraft(token) } }
    internal fun distortSeal(token: Long): Int =
        commands.withLock { lifetime.read { NativeDistortSessionBridge.sealJoin(token) } }
    internal fun distortDestroy(token: Long): Int = commands.withLock {
        NativeDistortSessionBridge.destroySession(token)
    }
    internal fun endDistortSession(token: Long) = commands.withLock {
        if (activeDistortToken == token) { activeDistortToken = 0L; distortSessionActive = false }
    }
    /**
     * Construct the P54 owner without dispatching Apply.  The session stores the
     * owner before calling commit so a transport or ledger exception cannot make
     * an unresolved transaction unreachable.
     */
    internal fun commitDistortSession(session: NativeDistortSession): NativeDistortCommitOwner {
        return commands.withLock { lifetime.read {
            check(handle != 0L && !commandClosing) { "Document is closed or closing" }
            val documentClaim = checkNotNull(this@NativeEditor.documentClaim)
            val operation = nextDistortOperation++
            check(operation > 0L) { "Distort operation identity exhausted" }
            NativeDistortCommitOwner(handle, session.draft, session.ticket(), operation.toULong(),
                resourceLedger, documentClaim, session.arenaReservation, session.growthBytes,
                session.nativeEnvelopeLimit)
        } }
    }
    internal fun recoverDistortCommit(owner: NativeDistortCommitOwner): Int {
        var changedRevision: Long? = null
        try {
            return commands.withLock { lifetime.read {
                check(handle != 0L && !commandClosing) { "Document is closed or closing" }
                val before = revision(handle)
                try { owner.recover() }
                finally { revision(handle).takeIf { it != before }?.let { changedRevision = it } }
            } }
        } finally { changedRevision?.let { committed?.invoke(it) } }
    }
    private var displayHandles = 0
    private var budgetedSceneHandles = 0
    private var budgetedCheckpointHandles = 0
    private val displayIdleGate = Object()
    private var nextPendingEdit = 0L
    private val pendingEdits = mutableSetOf<Long>()
    @Volatile private var visiblePendingEditCount = 0
    private val strokes = mutableSetOf<StrokeReservation>()
    private var activeReservation: StrokeReservation? = null
    private var commandClosing = false
    private val closeGate = Any()
    internal val commandDispatcher = RetainedCommandDispatcher(this)
    var canvasWidth: Int
        private set
    var canvasHeight: Int
        private set
    @Volatile var committed: ((Long) -> Unit)? = null
    internal val cloneStamp = CloneStampState()
    internal fun <T> serialized(action: () -> T): T = commands.withLock(action)
    internal fun trySerialized(action: () -> Unit): Boolean {
        if (!commands.tryLock()) return false
        try { action(); return true } finally { commands.unlock() }
    }
    internal fun awaitCommandsAvailable(wake: () -> Unit): AutoCloseable = commands.awaitAvailable(wake)
    internal fun tryUiUpdate(action: () -> Unit): Boolean {
        if (!commands.tryLock()) return false
        try {
            if (hasPendingEdits) return false
            action()
            return true
        } finally { commands.unlock() }
    }
    internal val pendingEditCount: Int get() = commands.withLock { pendingEdits.size }
    internal val hasPendingEdits: Boolean get() = visiblePendingEditCount != 0
    internal val hasActiveStrokes: Boolean get() = commands.withLock { strokeActive || strokes.isNotEmpty() }
    internal val hasActiveTransformDraft: Boolean get() = commands.withLock { transformDraft != null }
    internal val hasActiveDistortSession: Boolean get() = commands.withLock { distortSessionActive }
    /** Register before queueing an accepted edit; completion and rejection may settle it more than once. */
    internal fun tryRegisterPendingEdit(): PendingEditTicket? = commands.withLock {
        if (pendingMovePublication != null && transformControl == null) runCatching { recoverMovePress(cancel = true) }
        if (handle == 0L || resourceGrowthFrozen || commandClosing || distortSessionActive || transformDraft != null || transformControl != null || pendingEdits.size >= 64 ||
            (strokeActive && strokes.isEmpty())) return@withLock null
        val id = ++nextPendingEdit
        pendingEdits.add(id)
        visiblePendingEditCount = pendingEdits.size
        PendingEditTicket(this, id) { commands.withLock {
            pendingEdits.remove(id); visiblePendingEditCount = pendingEdits.size
        } }
    }
    internal fun ownsPendingEdit(ticket: PendingEditTicket): Boolean = commands.withLock {
        ticket.owner === this && ticket.id in pendingEdits
    }
    internal fun reserveStroke(): StrokeReservation? = commands.withLock {
        if (handle == 0L || commandClosing || resourceGrowthFrozen || distortSessionActive || transformDraft != null || transformControl != null || pendingEdits.isNotEmpty() || strokes.size >= 64) null
        else StrokeReservation(java.util.concurrent.CompletableFuture()).also { strokes.add(it) }
    }
    internal fun strokeReservations(): List<StrokeReservation> = commands.withLock { strokes.toList() }
    internal fun finishReservation(reservation: StrokeReservation) = commands.withLock {
        if (activeReservation === reservation) activeReservation = null
        if (strokes.remove(reservation)) reservation.finished.complete(Unit)
        Unit
    }
    internal fun cancelReservedStrokes() = commands.withLock {
        try { if (handle != 0L) endStroke(true) }
        finally { strokes.toList().forEach(::finishReservation) }
    }
    /** A retiring canvas may cancel only the stroke reservations it still owns. */
    internal fun cancelReservations(owned: Collection<StrokeReservation>) = commands.withLock {
        if (owned.any { it in strokes }) {
            try { if (activeReservation in owned) endStroke(true, releaseReservations = false) }
            finally { owned.forEach(::finishReservation) }
        }
    }
    internal fun withStrokeReservation(reservation: StrokeReservation, begin: Boolean, action: () -> Unit): Boolean = commands.withLock {
        if (reservation !in strokes || reservation.finished.isDone || commandClosing || resourceGrowthFrozen || distortSessionActive || transformDraft != null) false
        else if (begin && strokeActive) false
        else if (!begin && activeReservation !== reservation) false
        else {
            if (begin) activeReservation = reservation
            action()
            true
        }
    }
    /** Closing rejects new input before cancelling native work and releasing its waiting ordinary edits. */
    internal fun prepareCommandClose() {
        if (hasUnresolvedMovePress) runCatching { recoverMovePress(cancel = true) }
        if (hasUnresolvedDuplicate) runCatching { recoverDuplicate() }
        val (cancel, retained) = commands.withLock { commandClosing = true; cancelTransformControl to retainedTransform }
        retained?.close()
        cancel?.invoke()
        commands.withLock {
            if (pendingDuplicatePublication == null) {
                transformDraft?.let { draft -> lifetime.read { draft.cancelNative(handle) } }
            }
            cancelReservedStrokes()
        }
    }
    internal fun reserveTransformControl(owner: TransformController.Owner, cancel: () -> Unit): Boolean = commands.withLock {
        if (pendingMovePublication != null && transformControl == null) runCatching { recoverMovePress(cancel = true) }
        if (handle == 0L || commandClosing || resourceGrowthFrozen || distortSessionActive || transformControl != null || transformDraft != null ||
            strokeActive || strokes.isNotEmpty() || pendingEdits.isNotEmpty()) false
        else { transformControl = owner; cancelTransformControl = cancel; true }
    }
    internal fun retireTransformControl(owner: TransformController.Owner) = commands.withLock {
        if (transformControl === owner) { transformControl = null; cancelTransformControl = null }
    }
    internal fun activeTransformCompletion() = commands.withLock { transformControl?.finished }
    /** Explicit synchronous first slice; callers must settle it before ordinary editor commands. */
    internal fun beginTransformDraft(context: android.content.Context, rootLayerId: Long,
        target: TransformTarget = TransformTarget.LAYER,
        controlOwner: TransformController.Owner? = null): NativeTransformDraft {
        awaitDisplayScenesReleased()
        return commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        check(!resourceGrowthFrozen && !distortSessionActive && transformDraft == null && !strokeActive && strokes.isEmpty()) {
            "Finish or cancel the active edit before transforming"
        }
        check(pendingEdits.isEmpty()) { PENDING_EDIT_MESSAGE }
        check(transformControl === controlOwner) { "Transform control belongs to another input owner" }
        // Checkpoints own a full retained-state claim; legacy external owners still fence edits.
        check(retainedHandles == displayHandles + budgetedSceneHandles + budgetedCheckpointHandles) { "Saving is still in progress. Try moving again when it finishes." }
        val selected = selection()
        if (target == TransformTarget.GROUP || selected.roots.size > 1) {
            check(selected.primary == rootLayerId && target == TransformTarget.GROUP) {
                "Group transforms require the authoritative selection and primary"
            }
            if (selected.roots.size == 1 && state().layers.firstOrNull { it.id == rootLayerId }?.isGroup != true)
                throw TransformDraftException(5)
            val draft = beginMovePressLocked(context, controlOwner, MovePressInput(MovePoint(0.0, 0.0)))
                ?: throw IllegalStateException("The selection has no transformable members")
            try {
                if (draft.info().target != target) throw TransformDraftException(5)
            } catch (error: Throwable) {
                draft.close()
                throw error
            }
            return@read draft
        }
        require(rootLayerId > 0)
        val estimate = NativeTransformBridge.record(48)
        NativeTransformBridge.requireSuccess(NativeTransformBridge.estimate(handle, rootLayerId, target.nativeValue, estimate))
        val memory = NativeTransformBridge.memory(estimate)
        val resident = checkNotNull(documentClaim).bytes
        val limit = Math.addExact(resident, memory.editPeakBytes)
        val lease = ImageIoAdmission.reserve(context, resourceLedger,
            Math.addExact(memory.editPeakBytes, ImageIoAdmission.NATIVE_METADATA_ALLOWANCE), 0, "transform draft")
        try {
            val draft = NativeTransformDraft(this, context, lease, resident, limit)
            NativeTransformBridge.requireSuccess(NativeTransformBridge.begin(handle, rootLayerId, target.nativeValue,
                limit, resident, draft.infoRecord))
            draft.token = draft.infoRecord.getLong(0)
            draft.active = true
            transformDraft = draft
            draft
        } catch (error: Throwable) {
            lease.close(); throw error
        }
        } }.also { notifyMoveSelection() }
    }
    private class PendingMovePublication(val peak: ResourceBudgetLedger.Reservation,
        val lease: ResourceBudgetLedger.Reservation, val plan: ResourceBudgetLedger.SplitTransferPlan,
        val shell: NativeTransformDraft, val request: java.nio.ByteBuffer,
        val receipt: java.nio.ByteBuffer, val instance: Long, val operation: Long,
        val owner: TransformController.Owner?, val existing: NativeTransformDraft?, val transport: MovePressTransport) {
        var adopted = false
        var cancelled = false
    }
    private var pendingMovePublication: PendingMovePublication? = null
    private var nextMoveOperation = 0L
    private class PendingDuplicatePublication(val draft: NativeTransformDraft, val originalBytes: Long,
        val request: ByteBuffer, val receipt: ByteBuffer, val instance: Long, val operation: Long,
        val transport: DuplicateTransport)
    private var pendingDuplicatePublication: PendingDuplicatePublication? = null
    private var nextDuplicateOperation = 0L
    private var duplicatePresentation: DraftLayerPresentation? = null
    private var pendingDuplicateCancel: NativeTransformDraft? = null
    internal val hasUnresolvedDuplicate: Boolean get() = commands.withLock { pendingDuplicatePublication != null }
    internal val hasUnresolvedDuplicateCancel: Boolean get() = commands.withLock { pendingDuplicateCancel != null }
    internal fun markPendingDuplicateCancel(draft: NativeTransformDraft) {
        check(commands.isHeldByCurrentThread && pendingDuplicateCancel == null)
        pendingDuplicateCancel = draft
    }
    internal fun finishPendingDuplicateCancel(draft: NativeTransformDraft) {
        check(commands.isHeldByCurrentThread && pendingDuplicateCancel === draft)
        pendingDuplicateCancel = null; resourceGrowthFrozen = false
    }
    internal fun freezePendingDuplicateCancel(draft: NativeTransformDraft) {
        check(commands.isHeldByCurrentThread && pendingDuplicateCancel === draft)
        resourceGrowthFrozen = true
    }
    internal fun recoverDuplicateCancel(): TransformCommitResult? = commands.withLock { lifetime.read {
        val draft = checkNotNull(pendingDuplicateCancel)
        draft.recoverCancelNative(handle)
    } }
    internal fun presentation(): Pair<EditorState, NativeSelection> = commands.withLock {
        duplicatePresentation?.let { return@withLock it.state to it.selection }
        state() to selection()
    }
    internal fun presentationSelection(): NativeSelection = commands.withLock {
        duplicatePresentation?.selection ?: selection()
    }
    internal fun duplicateLayerPresentation(): DraftLayerPresentation? = commands.withLock { duplicatePresentation }
    internal fun refreshDuplicatePresentation(draft: NativeTransformDraft) = commands.withLock {
        val base = state()
        val next = draft.layerPresentation(base, selection())
        duplicatePresentation?.close()
        duplicatePresentation = next
    }
    internal fun promoteDuplicate(context: android.content.Context, draft: NativeTransformDraft,
        transport: DuplicateTransport = DirectDuplicateTransport): DuplicateOutcome {
        awaitDisplayScenesReleased()
        return commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing && !resourceGrowthFrozen && pendingDuplicatePublication == null)
        check(transformDraft === draft && draft.active && transformControl != null)
        check(nextDuplicateOperation < Long.MAX_VALUE)
        val selection = selection()
        val operation = ++nextDuplicateOperation
        val request = NativeTransformBridge.record(56)
        val receipt = NativeTransformBridge.record(216)
        val memory = NativeTransformBridge.record(48)
        NativeDuplicateBridge.request(request, selection, operation, draft.token)
        NativeTransformBridge.requireSuccess(NativeDuplicateBridge.estimate(handle, request, memory))
        val estimate = NativeTransformBridge.memory(memory)
        val oldBytes = draft.reservation.bytes
        val additional = maxOf(estimate.editPeakBytes, estimate.ownedBytes, estimate.previewOwnedBytes)
        ImageIoAdmission.resize(context, draft.reservation, Math.addExact(oldBytes, additional), 0)
        val pending = PendingDuplicatePublication(draft, oldBytes, request, receipt, selection.instance, operation, transport)
        try { setInstalledLimit(handle, Math.addExact(installedDocumentBytes(), draft.reservation.bytes)) }
        catch (error: Throwable) { draft.reservation.resize(oldBytes); throw error }
        pendingDuplicatePublication = pending
        try {
            var returned = false
            var returnedStatus = 0
            try {
                returnedStatus = transport.promote(handle, request, Math.addExact(installedDocumentBytes(), draft.reservation.bytes),
                    Math.addExact(installedDocumentBytes(), oldBytes), receipt)
                returned = true
                val query = transport.query(handle, selection.instance, operation, receipt)
                if (query == 2 && returnedStatus != 0) {
                    pendingDuplicatePublication = null
                    draft.reservation.resize(oldBytes)
                    setInstalledLimit(handle, installedDocumentBytes())
                    NativeTransformBridge.requireSuccess(returnedStatus)
                }
                NativeTransformBridge.requireSuccess(query)
                finishDuplicatePublication(pending)
            } catch (delivery: Throwable) {
                if (pendingDuplicatePublication == null) throw delivery
                try {
                    val query = transport.query(handle, selection.instance, operation, receipt)
                    if (query == 2 && (!returned || returnedStatus != 0)) {
                        pendingDuplicatePublication = null
                        draft.reservation.resize(oldBytes)
                        setInstalledLimit(handle, installedDocumentBytes())
                        throw delivery
                    }
                    NativeTransformBridge.requireSuccess(query)
                    finishDuplicatePublication(pending)
                } catch (recovery: Throwable) {
                    if (recovery !== delivery) delivery.addSuppressed(recovery)
                    if (pendingDuplicatePublication != null) resourceGrowthFrozen = true
                    throw delivery
                }
            }
        } catch (error: Throwable) {
            if (pendingDuplicatePublication == null) {
                if (!draft.duplicatePromoted) draft.reservation.resize(oldBytes)
                setInstalledLimit(handle, installedDocumentBytes())
            } else resourceGrowthFrozen = true
            throw error
        }
        } }
    }
    private fun finishDuplicatePublication(pending: PendingDuplicatePublication): DuplicateOutcome {
        val raw = pending.receipt
        check(raw.getLong(0) == pending.instance && raw.getLong(8) == pending.operation)
        val status = raw.getInt(40)
        check(raw.getLong(16) == pending.request.getLong(16) &&
            raw.getLong(32) == pending.request.getLong(32))
        val outcome = if (status == 0) NativeDuplicateBridge.receipt(raw).also {
            check(it.draft.token == pending.draft.token)
            check(raw.getLong(24) == pending.request.getLong(24) +
                if (it.outcome == DuplicateOutcome.PROMOTED) 1 else 0)
            check((it.outcome == DuplicateOutcome.PROMOTED) == (it.acceptedRoots > 0))
            check(it.copiedLayers >= it.acceptedRoots)
            check(it.memory.ownedBytes <= pending.draft.reservation.bytes)
        }.outcome else null
        if (outcome == DuplicateOutcome.PROMOTED) pending.draft.duplicatePromoted = true
        NativeTransformBridge.requireSuccess(pending.transport.acknowledge(handle, pending.instance, pending.operation))
        pendingDuplicatePublication = null
        resourceGrowthFrozen = false
        if (outcome != DuplicateOutcome.PROMOTED) pending.draft.reservation.resize(pending.originalBytes)
        setInstalledLimit(handle, installedDocumentBytes())
        NativeTransformBridge.requireSuccess(status)
        return checkNotNull(outcome)
    }
    internal fun recoverDuplicate(): DuplicateOutcome = commands.withLock { lifetime.read {
        val pending = checkNotNull(pendingDuplicatePublication)
        try {
            NativeTransformBridge.requireSuccess(pending.transport.query(handle, pending.instance,
                pending.operation, pending.receipt))
            finishDuplicatePublication(pending)
        } catch (error: Throwable) {
            if (pendingDuplicatePublication != null) resourceGrowthFrozen = true
            throw error
        }
    } }
    private var moveSelectionNotification: Long? = null
    private val moveNotificationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val moveNotificationDelivery = Runnable { notifyMoveSelection() }
    internal val hasUnresolvedMovePress: Boolean get() = commands.withLock { pendingMovePublication != null }
    internal fun selection(): NativeSelection = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        val header = NativeTransformBridge.record(72)
        val empty = NativeTransformBridge.record(0)
        NativeTransformBridge.requireSuccess(NativeMovePressBridge.read(handle, header, empty))
        val roots = NativeTransformBridge.record(Math.multiplyExact(header.getInt(64), 8))
        NativeTransformBridge.requireSuccess(NativeMovePressBridge.read(handle, header, roots))
        NativeMovePressBridge.selection(header, roots)
    } }
    internal fun pixelSelection(): PixelSelection = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        val record = NativeTransformBridge.record(72)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.read(handle, record))
        PixelSelection.read(record)
    } }
    internal fun pixelSelectionRegions(): PixelSelectionRegions = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        val header = NativeTransformBridge.record(40)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.readRegions(handle, header, NativeTransformBridge.record(0)))
        val count = header.getInt(36)
        val records = NativeTransformBridge.record(Math.multiplyExact(count, 40))
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.readRegions(handle, header, records))
        val parsed = PixelSelectionRegions.read(header, records)
        if (parsed.regions.none { it.shape == PixelSelectionShape.POLYGON }) return@read parsed
        val hydrated = parsed.regions.mapIndexed { index, region ->
            if (region.shape != PixelSelectionShape.POLYGON) region
            else {
                val countOutput = NativeTransformBridge.record(4)
                NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.readPolygon(handle, index,
                    NativeTransformBridge.record(0), countOutput))
                val pointCount = countOutput.getInt(0)
                require(pointCount in 3..65536) { "Invalid native lasso polygon" }
                val encoded = NativeTransformBridge.record(Math.multiplyExact(pointCount, 16))
                NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.readPolygon(handle, index, encoded, countOutput))
                region.copy(polygon = List(pointCount) { point ->
                    PixelSelectionPoint(encoded.getDouble(point * 16), encoded.getDouble(point * 16 + 8))
                })
            }
        }
        parsed.copy(regions = hydrated)
    } }
    internal fun setPixelSelection(rect: PixelSelectionRect?, shape: Int = PixelSelectionShape.RECTANGLE): PixelSelection = commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        val current = pixelSelection()
        val request = NativeTransformBridge.record(72)
        val output = NativeTransformBridge.record(72)
        val next = rect ?: PixelSelectionRect(0.0, 0.0, 0.0, 0.0)
        require(shape == PixelSelectionShape.RECTANGLE || shape == PixelSelectionShape.ELLIPSE) { "Unsupported pixel selection shape" }
        current.request(request, next.x, next.y, next.width, next.height, if (rect == null) PixelSelectionShape.RECTANGLE else shape)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.set(handle, request, output))
        PixelSelection.read(output)
    } }
    internal fun combinePixelSelection(rect: PixelSelectionRect, shape: Int, operation: Int): PixelSelection = commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        require(shape == PixelSelectionShape.RECTANGLE || shape == PixelSelectionShape.ELLIPSE) { "Unsupported pixel selection shape" }
        require(operation in PixelSelectionOperation.ADD..PixelSelectionOperation.INTERSECT) { "Unsupported pixel selection operation" }
        val current = pixelSelection()
        val request = NativeTransformBridge.record(80)
        val output = NativeTransformBridge.record(72)
        request.putLong(0, current.instance); request.putLong(8, current.revision)
        request.putLong(16, current.generation); request.putLong(24, current.epoch)
        request.putDouble(32, rect.x); request.putDouble(40, rect.y)
        request.putDouble(48, rect.width); request.putDouble(56, rect.height)
        request.putInt(64, shape); request.putInt(68, operation)
        request.putInt(72, 0); request.putInt(76, 0)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.combine(handle, request, output))
        PixelSelection.read(output)
    } }
    internal fun combinePixelSelectionPolygon(points: List<PixelSelectionPoint>, operation: Int): PixelSelection = commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        require(points.size >= 3 && points.size <= 4096) { "Invalid lasso polygon" }
        require(operation in PixelSelectionOperation.REPLACE..PixelSelectionOperation.INTERSECT) {
            "Unsupported pixel selection operation"
        }
        val current = pixelSelection()
        val request = NativeTransformBridge.record(40)
        request.putLong(0, current.instance); request.putLong(8, current.revision)
        request.putLong(16, current.generation); request.putLong(24, current.epoch)
        request.putInt(32, operation); request.putInt(36, 0)
        val encoded = NativeTransformBridge.record(Math.multiplyExact(points.size, 16))
        points.forEachIndexed { index, point ->
            require(point.x.isFinite() && point.y.isFinite()) { "Invalid lasso point" }
            encoded.putDouble(index * 16, point.x); encoded.putDouble(index * 16 + 8, point.y)
        }
        val output = NativeTransformBridge.record(72)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.combinePolygon(handle, request, encoded,
            points.size, output))
        PixelSelection.read(output)
    } }
    /** Applies a bounded native Expand/Contract/Feather operation to the current pixel
     * selection. Feather is supported for one replacement rectangle or ellipse; native
     * rejects composite and polygon selections atomically. */
    internal fun modifyPixelSelection(operation: Int, amount: Int): PixelSelection = commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        require(operation in PixelSelectionAmountOperation.EXPAND..PixelSelectionAmountOperation.FEATHER) {
            "Unsupported pixel selection amount operation"
        }
        require(amount in 1..PixelSelectionAmountOperation.maximum(operation)) {
            "Invalid pixel selection amount"
        }
        val current = pixelSelection()
        check(current.active) { "No pixel selection" }
        val request = NativeTransformBridge.record(48)
        val output = NativeTransformBridge.record(72)
        request.putLong(0, current.instance); request.putLong(8, current.revision)
        request.putLong(16, current.generation); request.putLong(24, current.epoch)
        request.putInt(32, operation); request.putInt(36, amount)
        request.putInt(40, 0); request.putInt(44, 0)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.modify(handle, request, output))
        PixelSelection.read(output)
    } }
    internal fun pixelSelectionPolygon(regionIndex: Int): List<PixelSelectionPoint> = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        val count = NativeTransformBridge.record(4)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.readPolygon(handle, regionIndex,
            NativeTransformBridge.record(0), count))
        val n = count.getInt(0)
        require(n >= 0 && n <= 65536) { "Invalid native polygon count" }
        if (n == 0) return@read emptyList()
        val encoded = NativeTransformBridge.record(Math.multiplyExact(n, 16))
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.readPolygon(handle, regionIndex, encoded, count))
        List(n) { index -> PixelSelectionPoint(encoded.getDouble(index * 16), encoded.getDouble(index * 16 + 8)) }
    } }
    /** Render and trace once, with explicit temporary admission in addition to the document claim. */
    internal fun magicWand(x: Double, y: Double, radius: Int, tolerance: Int,
        contiguous: Boolean): NativeWandResult = commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing && !resourceGrowthFrozen && !strokeActive &&
            transformDraft == null && transformControl == null) { "Magic Wand is unavailable during an edit" }
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 &&
            x < canvasWidth && y < canvasHeight && radius in 0..2 && tolerance in 0..255)
        val pixels = canvasWidth.toLong() * canvasHeight
        check(pixels in 1..4_194_304) { "Magic Wand supports canvases up to 4,194,304 pixels" }
        NativeTransformBridge.requireSuccess(NativeTransformBridge.estimateScene(handle, sceneMemoryRecord))
        val captureBytes = sceneMemoryRecord.getLong(32).also { check(it >= 0) }
        // The desktop flood-fill stack can temporarily retain both realloc buffers.
        // Include packed/JVM outline copies, scene capture, and render graph metadata.
        val bytes = Math.addExact(101L * pixels + 8L * 1024 * 1024 +
            4096L * state().layers.size + 192L * 1024, captureBytes)
        resourceLedger.reserve(bytes, "Magic Wand trace").use {
            decodeWandResult(NativeMagicWandBridge.trace(handle, x, y, radius, tolerance, contiguous),
                canvasWidth, canvasHeight)
        }
    } }
    internal fun applyMagicWand(x: Double, y: Double, radius: Int, tolerance: Int,
        contiguous: Boolean, operation: Int, expected: PixelSelection,
        cancellation: MagicWandCancellation) = commands.withLock { lifetime.read {
        try {
            if (cancellation.cancelled) return@read
            check(pixelSelection() == expected) { "Selection changed before Magic Wand started" }
            val traced = magicWand(x, y, radius, tolerance, contiguous)
            cancellation.commitIfActive {
                combinePixelSelectionPath(traced, operation, expected)
            }
        } finally {
            // Clear intent belongs to the accepted command, so Activity retirement cannot lose it.
            cancellation.finish { setPixelSelection(null) }
        }
    } }
    private fun combinePixelSelectionPath(result: NativeWandResult, operation: Int,
        expected: PixelSelection) {
        require(operation in PixelSelectionOperation.REPLACE..PixelSelectionOperation.INTERSECT)
        val request = NativeTransformBridge.record(40)
        request.putLong(0, expected.instance); request.putLong(8, expected.revision)
        request.putLong(16, expected.generation); request.putLong(24, expected.epoch)
        request.putInt(32, operation); request.putInt(36, 0)
        val count = result.loops.sumOf { it.points.size }
        val points = NativeTransformBridge.record(Math.multiplyExact(count, 16))
        val counts = NativeTransformBridge.record(Math.multiplyExact(result.loops.size, 4))
        var offset = 0
        result.loops.forEachIndexed { index, loop ->
            counts.putInt(index * 4, loop.points.size)
            loop.points.forEach { point ->
                points.putDouble(offset, point.x); points.putDouble(offset + 8, point.y); offset += 16
            }
        }
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.combinePath(handle, request,
            points, count, counts, result.loops.size, NativeTransformBridge.record(72)))
    }
    internal fun fillPixelSelection(argb: Int, layer: Long = 0L,
        transientLimitBytes: Long = StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES): PixelSelection =
        editAdmitted(transientLimitBytes) { admittedBytes ->
        if (admittedBytes == 0L) throw ResourceBudgetExceededException(1, 0, "selection fill working memory")
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        check(!strokeActive) { "Finish the active stroke before filling a selection" }
        val current = pixelSelection()
        check(current.active) { "No pixel selection" }
        val request = NativeTransformBridge.record(64)
        request.putLong(0, current.instance); request.putLong(8, current.revision)
        request.putLong(16, current.generation); request.putLong(24, current.epoch)
        request.putLong(32, layer)
        request.putFloat(40, ((argb ushr 16) and 0xff) / 255f)
        request.putFloat(44, ((argb ushr 8) and 0xff) / 255f)
        request.putFloat(48, (argb and 0xff) / 255f)
        request.putFloat(52, ((argb ushr 24) and 0xff) / 255f)
        // The authoritative layer selection determines whether the marquee edits
        // pixels or the selected mask. Native validates this focus atomically.
        request.putInt(56, if (selection().mask) PixelSelectionFillTarget.MASK else PixelSelectionFillTarget.PIXELS)
        request.putInt(60, 0)
        NativeTransformBridge.requireSuccess(NativePixelSelectionBridge.fillBounded(handle, request, admittedBytes))
        pixelSelection()
    }
    internal fun beginMovePress(context: android.content.Context, controlOwner: TransformController.Owner,
        input: MovePressInput, transport: MovePressTransport = DirectMovePressTransport): NativeTransformDraft? = commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing && !resourceGrowthFrozen)
        check(transformControl === controlOwner && pendingEdits.isEmpty() && !strokeActive && strokes.isEmpty())
        check(retainedHandles == displayHandles + budgetedSceneHandles + budgetedCheckpointHandles)
        beginMovePressLocked(context, controlOwner, input, transport)
    } }.also { notifyMoveSelection() }
    /** Exact-receipt recovery also serves cancellation/retirement. No new operation is issued.
     * A still-unclassified result retains all claims and remains fenced for a later retry. */
    internal fun recoverMovePress(cancel: Boolean = true): NativeTransformDraft? = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        val pending = pendingMovePublication ?: return@read transformDraft
        try {
            NativeTransformBridge.requireSuccess(pending.transport.query(handle, pending.instance,
                pending.operation, pending.receipt))
            finishMovePublication(pending, cancel)
        } catch (error: Throwable) {
            if (pendingMovePublication != null) resourceGrowthFrozen = true
            throw error
        }
    } }.also { notifyMoveSelection() }
    private fun notifyMoveSelection() {
        if (commands.isHeldByCurrentThread) { moveNotificationHandler.post(moveNotificationDelivery); return }
        val revision = commands.withLock { moveSelectionNotification.also { moveSelectionNotification = null } }
        if (revision != null) runCatching { committed?.invoke(revision) }.onFailure {
            android.util.Log.e("CompositorMove", "Selection published; workspace notification failed", it)
        }
    }
    private fun beginMovePressLocked(context: android.content.Context, controlOwner: TransformController.Owner?,
        input: MovePressInput, transport: MovePressTransport = DirectMovePressTransport): NativeTransformDraft? {
        check(pendingMovePublication == null)
        check(nextMoveOperation < Long.MAX_VALUE) { "Move operation IDs are exhausted" }
        val request = NativeTransformBridge.record(88)
        val receipt = NativeTransformBridge.record(248)
        val header = NativeTransformBridge.record(72)
        NativeTransformBridge.requireSuccess(NativeMovePressBridge.read(handle, header, NativeTransformBridge.record(0)))
        val instance = header.getLong(0)
        val operation = ++nextMoveOperation
        val existing = transformDraft
        NativeMovePressBridge.request(request, header, operation, existing?.token ?: 0, input)
        val installed = installedDocumentBytes()
        val resident = Math.addExact(installed, existing?.reservation?.bytes ?: 0)
        val peak = ImageIoAdmission.reserveAvailable(context, resourceLedger, "atomic move press")
        val lease = try { resourceLedger.reserve(0, "transform draft") }
            catch (error: Throwable) { peak.close(); throw error }
        var prepared: ResourceBudgetLedger.SplitTransferPlan? = null
        try {
            val limit = Math.addExact(resident, peak.bytes)
            val shell = NativeTransformDraft(this, context, lease, resident, limit)
            val upper = Math.addExact(installed, peak.bytes)
            val plan = resourceLedger.prepareSplitTransfer(checkNotNull(documentClaim), peak, lease, upper)
            prepared = plan
            val pending = PendingMovePublication(peak, lease, plan, shell, request, receipt, instance, operation, controlOwner, existing, transport)
            // Installed limit is a prepublication check; the native document is still unchanged.
            setInstalledLimit(handle, upper)
            pendingMovePublication = pending
            var returned = false
            var status = 0
            try {
                status = transport.press(handle, request, limit, resident, receipt)
                returned = true
                // Read the fixed authoritative receipt even on an apparently valid return. A damaged
                // transport record must never supply an adopted token or a publication verdict.
                val query = transport.query(handle, instance, operation, receipt)
                if (query == 2) {
                    check(status != 0) { "Move publication has no operation receipt" }
                    plan.abort(); pendingMovePublication = null; peak.close(); lease.close()
                    setInstalledLimit(handle, installed)
                    NativeTransformBridge.requireSuccess(status)
                }
                NativeTransformBridge.requireSuccess(query)
                return finishMovePublication(pending, pending.owner?.chain?.cancelled == true)
            } catch (delivery: Throwable) {
                if (pendingMovePublication == null) throw delivery
                // A thrown transport, malformed raw receipt or failed ack can all follow publication.
                // Recover from the fixed native receipt, never repeat begin or infer token liveness.
                try {
                    val query = transport.query(handle, instance, operation, receipt)
                    if (query == 2 && !returned) {
                        // No other operation can enter this command lane or evict this ID.
                        plan.abort(); pendingMovePublication = null; peak.close(); lease.close()
                        setInstalledLimit(handle, installed)
                        throw delivery
                    }
                    NativeTransformBridge.requireSuccess(query)
                    return finishMovePublication(pending, pending.owner?.chain?.cancelled == true)
                } catch (recovery: Throwable) {
                    if (recovery !== delivery) delivery.addSuppressed(recovery)
                    if (pendingMovePublication != null) resourceGrowthFrozen = true
                    throw delivery
                }
            }
        } catch (error: Throwable) {
            if (pendingMovePublication == null) {
                prepared?.abort(); peak.close()
                if (transformDraft?.reservation !== lease) lease.close()
            } else resourceGrowthFrozen = true
            throw error
        }
    }
    private fun finishMovePublication(pending: PendingMovePublication, cancel: Boolean): NativeTransformDraft? {
        val receipt = pending.receipt
        check(receipt.getLong(0) == pending.instance && receipt.getLong(72) == pending.operation)
        val status = receipt.getInt(80)
        val outcome = receipt.getInt(84)
        check(status in 0..9 && outcome in 0..3)
        val ownsDraft = status == 0 && outcome == 2
        val reusedDraft = status == 0 && outcome == 3
        val target = receipt.getInt(176)
        if (ownsDraft || reusedDraft) check(target in 0..2 && receipt.getLong(96) > 0 &&
            receipt.getInt(184) > 0 && receipt.getInt(188) > 0)
        val draftBytes = if (ownsDraft) maxOf(receipt.getLong(208), receipt.getLong(216)) else 0L
        check(draftBytes >= 0 && receipt.getLong(56) >= 0)
        val draft = if (reusedDraft) checkNotNull(pending.existing) else pending.shell
        if (reusedDraft) {
            check(draft.token == receipt.getLong(96))
            // A receipt records the press, not current token liveness. Cancellation can precede
            // a lost acknowledgment or happen through the existing public draft's close path.
            if (pending.cancelled) check(!draft.active && transformDraft !== draft)
            else check(draft.active && transformDraft === draft)
        }
        check(pending.plan.confirm(receipt.getLong(56), draftBytes)) { "Move ownership requires reconciliation" }
        if (ownsDraft && !pending.cancelled) {
            // All rejecting accounting is complete. Refreshing a queried live receipt is idempotent;
            // a cancelled original receipt must never resurrect its historical token.
            draft.token = receipt.getLong(96)
            for (offset in 0 until 104 step 8) draft.infoRecord.putLong(offset, receipt.getLong(96 + offset))
            draft.active = true; transformDraft = draft; pending.adopted = true
        }
        if (cancel && (ownsDraft || reusedDraft) && !pending.cancelled) {
            if (draft.active) draft.cancelNative(handle)
            pending.cancelled = true
        }
        setInstalledLimit(handle, installedDocumentBytes())
        NativeTransformBridge.requireSuccess(pending.transport.acknowledge(handle, pending.instance, pending.operation))
        pendingMovePublication = null
        if (receipt.getInt(88) != 0) moveSelectionNotification = receipt.getLong(8)
        resourceGrowthFrozen = false
        if (!ownsDraft) pending.lease.close()
        if (ownsDraft || reusedDraft) {
            pending.owner?.rootLayerId = receipt.getLong(32)
            pending.owner?.target = when (target) { 0 -> TransformTarget.LAYER; 1 -> TransformTarget.UNLINKED_MASK; else -> TransformTarget.GROUP }
        }
        NativeTransformBridge.requireSuccess(status)
        return draft.takeIf { (ownsDraft || reusedDraft) && !pending.cancelled }
    }
    internal fun <T> withTransformDraft(draft: NativeTransformDraft, action: (Long) -> T): T = commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing && !resourceGrowthFrozen) { "Transform owner is unavailable" }
        check(transformDraft === draft && draft.active) { "Transform token is retired or belongs to another document" }
        action(handle)
    } }
    internal fun retireTransformDraft(draft: NativeTransformDraft, retainClaim: Boolean) {
        check(commands.isHeldByCurrentThread && transformDraft === draft)
        if (!retainClaim) pendingMovePublication?.let { pending ->
            if (pending.existing === draft || (pending.adopted && pending.shell === draft)) pending.cancelled = true
        }
        draft.active = false
        transformDraft = null
        if (draft.duplicatePromoted) {
            duplicatePresentation?.close()
            duplicatePresentation = null
        }
        if (!retainClaim) draft.reservation.close()
    }
    internal fun cancelTransformDraft(draft: NativeTransformDraft,
        transport: DuplicateCancelTransport = DirectDuplicateCancelTransport): TransformCommitResult? = commands.withLock { lifetime.read {
        check(pendingDuplicatePublication == null) { "Duplicate promotion delivery remains unresolved" }
        if (pendingDuplicateCancel === draft) return@read draft.recoverCancelNative(handle)
        if (transformDraft !== draft || !draft.active) return@read draft.cancelledResult
        check(handle != 0L) { "Document is closed" }
        draft.cancelNative(handle, transport)
    } }
    internal fun retainTransformScene() {
        check(commands.isHeldByCurrentThread)
        ++retainedHandles; ++displayHandles
    }
    internal fun releaseTransformScene(): Unit = commands.withLock {
        --retainedHandles; --displayHandles
        synchronized(displayIdleGate) { displayIdleGate.notifyAll() }
        Unit
    }
    internal fun prepareTransformTransfer(edit: ResourceBudgetLedger.Reservation, upper: Long): ResourceBudgetLedger.TransferPlan {
        check(publicationTransfer == null) { "Document resource ownership requires reconciliation" }
        val plan = resourceLedger.prepareTransfer(checkNotNull(documentClaim), edit, upper)
        publicationEdit = edit; publicationTransfer = plan
        return plan
    }
    internal fun abortPreparedTransfer(plan: ResourceBudgetLedger.TransferPlan) {
        plan.abort()
        if (publicationTransfer === plan) { publicationTransfer = null; publicationEdit = null }
    }
    internal fun confirmPreparedTransfer(plan: ResourceBudgetLedger.TransferPlan, receipt: Long): Boolean {
        // A malformed managed receipt cannot cause replay/release of native committed work.
        // Reconcile once with the allocation-free native committed cache; retain both claims
        // until document destruction if even that authoritative inventory is inconsistent.
        val confirmed = plan.confirm(receipt) || plan.confirm(cachedRetainedBytes())
        if (confirmed && publicationTransfer === plan) { publicationTransfer = null; publicationEdit = null }
        return confirmed
    }
    internal fun installedDocumentBytes(): Long = checkNotNull(documentClaim).bytes
    internal fun displayAdmissionInfoForTest(): LongArray = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        NativeTransformBridge.requireSuccess(NativeTransformBridge.displayAdmission(handle, displayMemoryRecord))
        LongArray(6) { displayMemoryRecord.getLong(it * 8).also { value -> check(value >= 0) } }
    } }
    internal fun setInstalledLimit(nativeHandle: Long, bytes: Long) =
        NativeTransformBridge.requireSuccess(NativeTransformBridge.setRetainedLimit(nativeHandle, bytes))
    /** Session-only input memory; reading or updating it never enters document history. */
    internal var lastBrushCursor: BrushCursor?
        get() = commands.withLock { brushCursor }
        set(value) = commands.withLock {
            check(handle != 0L) { "Document is closed" }
            brushCursor = value
        }
    internal fun freezeResourceGrowth(): RetainedMemory = commands.withLock {
        check(!resourceGrowthFrozen) { "Document resources are already frozen" }
        check(transformDraft == null && transformControl == null) { "Finish or cancel the active transform before freezing" }
        check(pendingEdits.isEmpty()) { PENDING_EDIT_MESSAGE }
        endStroke(true)
        check(retainedHandles == 0) { "Unreleased scenes or snapshots require an explicit external reservation" }
        resourceGrowthFrozen = true
        try { retainedMemory() } catch (error: Throwable) { resourceGrowthFrozen = false; throw error }
    }
    internal fun releaseResourceGrowth() = commands.withLock { resourceGrowthFrozen = false }
    internal fun renderWithGrowth(action: () -> Unit): Boolean = commands.withLock {
        if (handle == 0L || commandClosing || resourceGrowthFrozen || transformDraft != null || transformControl != null) false else { action(); true }
    }
    internal fun renderWithDisplayGrowth(action: () -> Unit): Boolean = commands.withLock {
        if (handle == 0L || commandClosing || resourceGrowthFrozen || (transformControl != null && transformDraft == null)) false
        else { action(); true }
    }
    fun captureDisplayScene(): NativeScene? = commands.withLock {
        transformDraft?.captureScene() ?: captureSceneOwned(null, display = true)
    }
    /** Independently budgeted immutable preview; it may outlive edits or document destruction. */
    internal fun captureAdjustmentPreviewScene(layerId: Long, style: AdjustmentLayerModel,
        expectedRevision: Long = currentRevision()): NativeScene =
        commands.withLock { lifetime.read {
            check(handle != 0L && !commandClosing && !resourceGrowthFrozen) { "Document is unavailable" }
            check(transformDraft == null && transformControl == null && !strokeActive) {
                "Finish the active edit before previewing an adjustment"
            }
            val json = style.toMacJson().toString().toByteArray(Charsets.UTF_8)
            val estimate = NativeTransformBridge.record(8)
            NativeTransformBridge.requireSuccess(NativeTransformBridge.estimateAdjustmentPreview(handle,
                layerId, expectedRevision, json.size.toLong(), estimate))
            val peak = estimate.getLong(0).also { check(it > 0) }
            val claim = resourceLedger.reserve(peak, "adjustment preview")
            var raw = 0L
            var scene: NativeScene? = null
            try {
                raw = snapshotAdjustmentPreview(handle, layerId, expectedRevision, json, peak, 0)
                check(raw != 0L) { "Adjustment preview returned no scene" }
                scene = NativeScene(raw, resourceLedger, cacheIdentity = nextPreviewIdentity.incrementAndGet())
                val memory = scene.memoryInfo()
                val retainedBytes = Math.addExact(memory[2], memory[3])
                check(retainedBytes <= peak) { "Preview retained inventory exceeds admitted peak" }
                claim.resize(retainedBytes)
                val retained = checkNotNull(scene)
                // Full shared ownership is charged, so this scene needs no document/display
                // handle credit and its retirement must never acquire the command lock.
                retained.setReleaseCallback { claim.close() }
                return@read retained
            } catch (error: Throwable) {
                scene?.close()
                if (scene == null && raw != 0L) destroyScene(raw)
                claim.close()
                throw error
            }
        } }
    internal fun captureDisplayScene(admission: DisplaySceneAdmission): NativeScene? {
        if (!admission.beginAttempt()) return null
        if (!commands.tryLock()) {
            admission.deferUntilCommands(::awaitCommandsAvailable)
            return null
        }
        try {
            return (transformDraft?.captureScene() ?: captureSceneOwned(admission, display = true)).also {
                if (it != null) admission.captured() else if (handle == 0L) admission.unavailable()
            }
        } catch (error: Throwable) {
            admission.unavailable()
            throw error
        } finally { commands.unlock() }
    }
    private fun cachedRetainedBytes(): Long {
        NativeTransformBridge.requireSuccess(NativeTransformBridge.retained(handle, retainedRecord))
        return retainedRecord.getLong(16).also { check(it >= 0) }
    }

    /**
     * A display scene retains immutable native tiles already owned by the
     * installed document.  Reserve only the incremental scene claim in the
     * managed ledger; the native budgeted snapshot still validates its full
     * occurrence based peak against its own allocation-free measurement.
     */
    private fun displaySceneClaim(rawCaptureBytes: Long): Long {
        require(rawCaptureBytes >= 0)
        // Match native publication admission: tiny scenes remain occurrence
        // based because metadata dominates and there is no material tile
        // payload to credit.
        if (rawCaptureBytes < 4L * 1024 * 1024) return rawCaptureBytes
        val installed = checkNotNull(documentClaim).bytes
        return maxOf(ImageIoAdmission.NATIVE_METADATA_ALLOWANCE,
            (rawCaptureBytes - installed).coerceAtLeast(0L))
    }
    /** Wait until the renderer retires its scene before a mutating command can
     * prune history and release a tile still referenced by that scene. */
    private fun awaitDisplayScenesReleased() {
        while (commands.withLock { displayHandles > 0 }) {
            synchronized(displayIdleGate) { displayIdleGate.wait(50L) }
        }
    }
    private fun admitPublication(maximumAllowance: Long = Long.MAX_VALUE): Pair<ResourceBudgetLedger.Reservation, ResourceBudgetLedger.TransferPlan> {
        val installed = checkNotNull(documentClaim)
        val allowance = minOf(resourceLedger.snapshot().availableBytes, maximumAllowance)
        val edit = resourceLedger.reserve(allowance, "document publication")
        try {
            val upper = Math.addExact(installed.bytes, allowance)
            val plan = prepareTransformTransfer(edit, upper)
            try { setInstalledLimit(handle, upper) } catch (error: Throwable) { abortPreparedTransfer(plan); throw error }
            return edit to plan
        } catch (error: Throwable) { edit.close(); throw error }
    }
    private fun confirmPublication(@Suppress("UNUSED_PARAMETER") edit: ResourceBudgetLedger.Reservation, plan: ResourceBudgetLedger.TransferPlan) {
        val bytes = cachedRetainedBytes()
        check(confirmPreparedTransfer(plan, bytes)) { "Published document resource receipt requires reconciliation" }
        setInstalledLimit(handle, bytes)
    }
    fun retainedMemory(): RetainedMemory = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        check(transformDraft == null) { "Finish or cancel the active transform before querying committed memory" }
        val values = memoryInfo(handle)
        RetainedMemory(values[0], values[1], values[2], values[3], values[4])
    } }
    init {
        try {
            check(abiVersion() == NATIVE_ABI_VERSION) { "Native editor ABI does not match the Android binding" }
            // Managed owners are initialized before acquiring a native document.
            handle = importedLoader?.invoke() ?: if (checkpoint == null) {
                if (documentHeight == documentSize) create(documentSize) else createRectangular(documentSize, documentHeight)
            } else {
                val budget = checkNotNull(checkpointBudget)
                val loaded = loadCheckpoint(checkpoint.absolutePath.toByteArray(Charsets.UTF_8), budget.limitBytes, budget.residentBytes)
                if (loaded[0] != 0L) throw CheckpointException(loaded[0].toInt())
                loaded[1]
            }
            val dimensions = info(handle)
            canvasWidth = dimensions[0].toInt(); canvasHeight = dimensions[1].toInt()
            if (addDefaultLayers && checkpoint == null && importedLoader == null) {
                val white = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(-1, -1, -1, -1)); rewind() }
                val background = addPixels(handle, 1, 1, white)
                fillBackground(handle, background, documentSize.toDouble())
                addBlank(handle, canvasWidth, canvasHeight)
            }
            val bytes = cachedRetainedBytes()
            val admitted = documentClaim
            if (admitted == null) documentClaim = resourceLedger.reserve(bytes, "installed document")
            else {
                // Decoding completed under this already admitted owner. Adopt by shrinking;
                // no release/reacquire window or second competing persistent reservation.
                if (bytes > admitted.bytes)
                    throw ResourceBudgetExceededException(bytes, admitted.bytes, "loaded document retention")
                admitted.resize(bytes)
            }
            setInstalledLimit(handle, bytes)
            // A committed document must fit alongside its own first display scene.
            // Other owners still use the ledger's existing temporary-pressure admission.
            // Validate loaded candidates before any workspace can install this editor.
            NativeTransformBridge.requireSuccess(NativeTransformBridge.setDisplayLimit(handle, resourceLedger.limitBytes))
        } catch (error: Throwable) {
            try { if (handle != 0L) destroy(handle) }
            finally {
                handle = 0
                try { documentClaim?.close() }
                finally { commandDispatcher.shutdown() }
            }
            throw error
        }
    }
    fun state(): EditorState = commands.withLock { lifetime.read {
        if (handle == 0L) return EditorState()
        val document = info(handle)
        val layers = (0 until document[2].toInt()).map {
            val attributes = preciseLayer(layer(handle, it), precision(handle, it))
            val id = attributes.id
            val relationship = structure(handle, id)
            val mask = placement(handle, id)
            LayerState(id, attributes.opacity, attributes.visible, attributes.blend,
                name(handle, id).toString(Charsets.UTF_8), relationship[0], relationship[1],
                relationship[2] != 0L, relationship[3] != 0L, relationship[4] != 0L, relationship[5] != 0L,
                relationship[6].toInt(), relationship[7].toInt(), attributes.pixelWidth, attributes.pixelHeight,
                attributes.transform, attributes.sampling, layerUuid(handle, id), assetPresent(handle, id),
                if (relationship[8] != 0L) Transform64(mask[0], mask[1], mask[2], mask[3], mask[4],
                relationship[10] != 0L, relationship[11] != 0L) else null, relationship[9].toInt(),
                LayerKind.fromNative(relationship[12]))
        }
        val meta = metadata(handle)
        val ids = guideIds(handle); val positions = guidePositions(handle); val axes = guideAxes(handle)
        val guides = ids.indices.map { GuideState(ids[it], axes[it] != 0, positions[it]) }
        EditorState(layers, document[3] != 0L, document[4] != 0L, document[5], document[6],
            DocumentMetadata(documentUuid(handle), resolution(handle), meta[0], guides), selectedLayerActionsLocked())
    } }

    /** A denied menu query must not make the rest of the layer list unavailable. */
    private fun selectedLayerActionsLocked(): SelectedLayerActions? {
        check(commands.isHeldByCurrentThread)
        if (strokeActive || transformDraft != null || transformControl != null || distortSessionActive || resourceGrowthFrozen)
            return null
        val record = NativeTransformBridge.record(NativeSelectedLayerActionsBridge.INFO_BYTES)
        var status = NativeSelectedLayerActionsBridge.estimate(handle, record)
        if (status == 3 || status == 4) return null
        NativeSelectedLayerActionsBridge.requireSuccess(status)
        if (record.getInt(88) == 0) return NativeSelectedLayerActionsBridge.result(record)
        val peak = record.getLong(72)
        check(peak > 0L) { "Invalid selected-layer action query allowance" }
        val claim = try { resourceLedger.reserve(peak, "selected-layer menu query") }
            catch (_: ResourceBudgetExceededException) { return null }
        return claim.use {
            status = NativeSelectedLayerActionsBridge.read(handle, peak, 0, record)
            if (status == 3 || status == 4) null
            else {
                NativeSelectedLayerActionsBridge.requireSuccess(status)
                NativeSelectedLayerActionsBridge.result(record)
            }
        }
    }

    internal fun selectedLayerActions(): SelectedLayerActions? = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        selectedLayerActionsLocked()
    } }
    fun startStroke(id: Long, size: Float, erasing: Boolean, mask: Boolean = false,
        transientLimitBytes: Long = StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES) {
        awaitDisplayScenesReleased()
        commands.withLock { lifetime.read {
        check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
        check(transformDraft == null && transformControl == null) { "Finish or cancel the active transform before painting" }
        if (handle == 0L || strokeActive) return
        require(transientLimitBytes > 0) { "Stroke allowance must be positive" }
        beginAdmittedStroke(transientLimitBytes) { admittedBytes ->
            if (mask) beginMask(handle, id, size, erasing, admittedBytes)
            else begin(handle, id, size, erasing, admittedBytes)
        }
        } }
    }
    fun startStroke(id: Long, settings: StrokeSettings, erasing: Boolean = false, mask: Boolean = false,
        transientLimitBytes: Long = StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES) {
        awaitDisplayScenesReleased()
        commands.withLock { lifetime.read {
        check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
        check(transformDraft == null && transformControl == null) { "Finish or cancel the active transform before painting" }
        if (handle == 0L || strokeActive) return
        require(transientLimitBytes > 0) { "Stroke allowance must be positive" }
        beginAdmittedStroke(transientLimitBytes) { admittedBytes ->
            beginConfigured(handle, id, settings.diameter, settings.hardness, settings.opacity,
                settings.red, settings.green, settings.blue, erasing && !mask, mask,
                settings.maskPaintWhite, settings.pressureSize, settings.pressureOpacity, admittedBytes)
        }
        } }
    }
    internal fun startCloneStroke(id: Long, settings: StrokeSettings, offsetX: Double,
        offsetY: Double, sampleAllLayers: Boolean,
        transientLimitBytes: Long = StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES) {
        awaitDisplayScenesReleased()
        commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
        check(!strokeActive && !distortSessionActive && transformDraft == null && transformControl == null) {
            "Finish or cancel the active edit before cloning"
        }
        check(!selection().mask) { "Clone Stamp paints image pixels, not a mask" }
        require(transientLimitBytes > 0 && offsetX.isFinite() && offsetY.isFinite())
        beginAdmittedStroke(transientLimitBytes) { admittedBytes ->
            beginClone(handle, id, settings.diameter, settings.hardness, settings.opacity,
                offsetX, offsetY, sampleAllLayers, admittedBytes)
        }
        } }
    }
    internal fun startSmearStroke(id: Long, settings: StrokeSettings, mode: SmearMode,
        transientLimitBytes: Long = StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES) {
        awaitDisplayScenesReleased()
        commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
        check(!strokeActive && !distortSessionActive && transformDraft == null && transformControl == null) {
            "Finish or cancel the active edit before smearing"
        }
        check(!selection().mask) { "Smear edits image pixels, not a mask" }
        require(transientLimitBytes > 0)
        beginAdmittedStroke(transientLimitBytes) { admittedBytes ->
            beginSmear(handle, id, settings.diameter, settings.hardness, settings.opacity,
                mode.nativeValue, admittedBytes)
        }
        } }
    }
    internal fun startHealingStroke(id: Long, settings: StrokeSettings, mode: HealingMode,
        seed: Long = newHealingSeed(),
        transientLimitBytes: Long = StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES) {
        awaitDisplayScenesReleased()
        commands.withLock { lifetime.read {
        check(handle != 0L && !commandClosing) { "Document is closed or closing" }
        check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
        check(!strokeActive && !distortSessionActive && transformDraft == null && transformControl == null) {
            "Finish or cancel the active edit before healing"
        }
        uiCheck(!selection().mask, UiNoticeKey.HEALING_PIXELS_ONLY) { "Spot Healing edits pixels, not a mask" }
        require(transientLimitBytes > 0 && seed in 0..0xffffffffL)
        beginAdmittedStroke(transientLimitBytes) { admittedBytes ->
            checkHealingStatus(beginHealing(handle, id, settings.diameter, settings.hardness, settings.opacity,
                mode.nativeValue, seed, admittedBytes))
            healingStrokeActive = true
        }
        } }
    }
    private fun finishActiveStroke(cancel: Boolean) {
        if (healingStrokeActive) checkHealingStatus(finishHealing(handle, cancel))
        else finish(handle, cancel)
    }
    private fun beginAdmittedStroke(transientLimitBytes: Long, beginAction: (Long) -> Unit) {
        // A stroke keeps its claim until completion; leave room for other documents and scenes.
        healingStrokeActive = false
        val admission = admitPublication(transientLimitBytes)
        strokePublication = admission.first; strokeTransfer = admission.second
        try {
            val admittedBytes = admission.first.bytes
            if (admittedBytes == 0L) throw ResourceBudgetExceededException(1, 0, "stroke working memory")
            beginAction(admittedBytes)
            strokeActive = true
        }
        catch (error: Throwable) { settleStrokePublication(); throw error }
    }
    private fun settleStrokePublication() {
        val edit = strokePublication ?: return
        val plan = checkNotNull(strokeTransfer)
        confirmPublication(edit, plan)
        strokePublication = null; strokeTransfer = null
    }
    fun samples(values: DoubleArray): Unit = commands.withLock { lifetime.read {
        check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
        if (handle == 0L || !strokeActive) return
        try { append(handle, values) }
        catch (error: Exception) {
            try { finishActiveStroke(true) } catch (_: Exception) { /* Native allocation failure can already abort it. */ }
            strokeActive = false
            settleStrokePublication()
            throw error
        }
    } }
    private fun <T> edit(action: () -> T): T = editAdmitted(Long.MAX_VALUE) { action() }
    private fun <T> editAdmitted(maximumAllowance: Long, action: (Long) -> T): T {
        require(maximumAllowance > 0) { "Edit allowance must be positive" }
        awaitDisplayScenesReleased()
        var revision: Long? = null
        val result = commands.withLock { lifetime.read {
            check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
            check(handle != 0L) { "Document is closed" }
            check(!distortSessionActive && transformDraft == null && transformControl == null) { "Finish or cancel the active transform before editing" }
            val before = revision(handle)
            val admission = if (strokeTransfer == null) admitPublication(maximumAllowance) else null
            val admittedBytes = admission?.first?.bytes ?: checkNotNull(strokePublication).bytes
            try {
                val result = action(admittedBytes)
                val after = revision(handle)
                if (after != before) revision = after
                result
            } finally {
                // A native action may publish before JNI transport/observer throws. Always reconcile
                // the allocation-free receipt, without replaying the action.
                admission?.let { confirmPublication(it.first, it.second) }
            }
        } }
        revision?.let { committed?.invoke(it) }
        return result
    }
    fun endStroke(cancel: Boolean): Unit = endStroke(cancel, releaseReservations = true)
    private fun endStroke(cancel: Boolean, releaseReservations: Boolean): Unit = commands.withLock {
        try { if (resourceGrowthFrozen) {
            check(cancel && !strokeActive) { "Document resource growth is frozen" }
        } else if (strokeActive) edit {
            try { finishActiveStroke(cancel) }
            catch (error: Exception) {
                try { finishActiveStroke(true) } catch (_: Exception) { /* Native failure can already abort it. */ }
                throw error
            } finally { strokeActive = false; settleStrokePublication() }
        } } finally {
            if (cancel && releaseReservations) strokes.toList().forEach(::finishReservation)
        }
    }
    internal fun endReservedStroke(cancel: Boolean): Unit = endStroke(cancel, releaseReservations = false)
    fun currentRevision(): Long = lifetime.read {
        check(handle != 0L) { "Document is closed" }
        revision(handle)
    }
    /** Committed content tag in 0..Long.MAX_VALUE; JNI rejects unsigned values outside that domain.
     * Compare only within this native document lifetime. Loading/importing starts a new domain at zero;
     * never persist this tag in a recovery pointer or compare an old saved tag across reloads. */
    fun currentContentIdentity(): Long = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        contentIdentity(handle)
    } }
    fun currentSceneGeneration(): Long = lifetime.read {
        check(handle != 0L) { "Document is closed" }
        sceneGeneration(handle)
    }
    fun setOpacity(id: Long, value: Double) = edit { check(!strokeActive); opacity(handle, id, value) }
    fun setVisible(id: Long, value: Boolean) = edit { check(!strokeActive); visible(handle, id, value) }
    internal fun toggleSelectedVisibility(actions: SelectedLayerActions) = applySelectedLayerAction(actions,
        NativeSelectedLayerActionsBridge.VISIBILITY)
    internal fun toggleSelectedClipping(actions: SelectedLayerActions) = applySelectedLayerAction(actions,
        NativeSelectedLayerActionsBridge.CLIPPING)
    private fun applySelectedLayerAction(actions: SelectedLayerActions, action: Int) {
        val request = actions.request(action)
        editAdmitted(Long.MAX_VALUE) { allowance ->
            check(!strokeActive)
            val resident = installedDocumentBytes()
            NativeSelectedLayerActionsBridge.requireSuccess(NativeSelectedLayerActionsBridge.apply(handle, request,
                Math.addExact(resident, allowance), resident))
        }
    }
    fun setBlend(id: Long, value: Int) = edit { check(!strokeActive); blend(handle, id, value) }
    fun setTransform(id: Long, value: Transform64) = edit { check(!strokeActive); transform(handle, id, value.numbers(), value.flipX, value.flipY) }
    fun setTransforms(changes: List<LayerTransformChange>) = edit {
        check(!strokeActive); require(changes.isNotEmpty())
        val ids = LongArray(changes.size)
        val numbers = DoubleArray(changes.size * 5)
        val flips = IntArray(changes.size * 2)
        changes.forEachIndexed { index, change ->
            ids[index] = change.id
            val values = change.transform.numbers()
            values.copyInto(numbers, index * 5)
            flips[index * 2] = if (change.transform.flipX) 1 else 0
            flips[index * 2 + 1] = if (change.transform.flipY) 1 else 0
        }
        setTransforms(handle, ids, numbers, flips)
    }
    fun setSampling(id: Long, value: Int) = edit { check(!strokeActive); sampling(handle, id, value) }
    fun selectLayer(id: Long) = selectLayers(if (id == 0L) emptyList() else listOf(id), id, false)
    fun selectLayers(roots: List<Long>, primary: Long, mask: Boolean = false) = edit {
        check(!strokeActive)
        val header = NativeTransformBridge.record(72)
        NativeTransformBridge.requireSuccess(NativeMovePressBridge.read(handle, header, NativeTransformBridge.record(0)))
        val request = NativeTransformBridge.record(48)
        for (offset in 0 until 32 step 8) request.putLong(offset, header.getLong(offset))
        request.putLong(32, primary); request.putInt(40, if (mask) 1 else 0); request.putInt(44, 0)
        val ids = NativeTransformBridge.record(Math.multiplyExact(roots.size, 8))
        roots.forEachIndexed { index, id -> ids.putLong(index * 8, id) }
        val resident = installedDocumentBytes()
        val limit = Math.addExact(resident, checkNotNull(publicationEdit).bytes)
        NativeTransformBridge.requireSuccess(NativeMovePressBridge.set(handle, request, ids, limit, resident, header))
    }
    fun setResolution(value: Double) = edit { check(!strokeActive); setResolution(handle, value) }
    fun crop(width: Int, height: Int, offsetX: Double, offsetY: Double) = edit {
        check(!strokeActive)
        require(width > 0 && height > 0 && offsetX.isFinite() && offsetY.isFinite())
        crop(handle, width, height, offsetX, offsetY)
        val dimensions = info(handle)
        canvasWidth = dimensions[0].toInt()
        canvasHeight = dimensions[1].toInt()
    }
    fun createShape(spec: ShapeSpec, x: Double, y: Double): Long = edit {
        check(!strokeActive)
        require(spec.width > 0 && spec.height > 0 && spec.width.toLong() * spec.height <= 16_000_000L)
        if (spec.kind == ShapeKind.LINE) {
            require(spec.color ushr 24 == 255) { "Line color must be opaque" }
            applyLineShape(handle, spec.width, spec.height, x, y, spec.geometryWidth, spec.geometryHeight,
                spec.lineWidth, spec.startX, spec.startY, spec.endX, spec.endY,
                spec.lineStyle.wireValue, spec.lineCap.wireValue, spec.color, checkNotNull(publicationEdit).bytes)
        } else applyShape(handle, spec.kind.ordinal, spec.width, spec.height, x, y,
            spec.cornerRadius, spec.lineWidth, spec.startX * spec.width, spec.startY * spec.height,
            spec.endX * spec.width, spec.endY * spec.height, spec.color)
    }
    /** Membership and metadata must share the command gate: a presented ID can outlive undo/delete. */
    private fun typedLayerExistsLocked(layerId: Long): Boolean {
        check(commands.isHeldByCurrentThread && handle != 0L)
        return (0 until info(handle)[2].toInt()).any { layer(handle, it)[0] == layerId }
    }
    internal fun typedLayerStylesForPresentation(presented: EditorState): TypedLayerStyleSnapshot = commands.withLock { lifetime.read {
        check(handle != 0L)
        val id = presented.document.activeLayerId
        val empty = TypedLayerStyleSnapshot(presented.revision, id)
        if (id <= 0 || presented.revision != revision(handle) || metadata(handle)[0] != id) empty
        else empty.copy(text = textStyleOrNull(id), adjustment = adjustmentStyleOrNull(id))
    } }
    internal fun textStyleOrNull(layerId: Long): TextLayerModel? = commands.withLock { lifetime.read {
        check(handle != 0L && layerId > 0)
        if (!typedLayerExistsLocked(layerId)) return@read null
        try {
            val bytes = textLayerJson(handle, layerId) ?: return@read null
            TextLayerModel.fromMacJson(org.json.JSONObject(bytes.toString(Charsets.UTF_8)))
        } catch (error: NativeCommandException) {
            if (error.status == 5) null else throw error // CP_UNSUPPORTED means this is an ordinary raster layer.
        }
    } }

    /** Render the canonical cache and atomically publish it with the typed style in one native undo item. */
    internal fun commitTextLayer(layerId: Long?, style: TextLayerModel, atX: Double, atY: Double): Long =
        editAdmitted(Long.MAX_VALUE) { allowance ->
            style.validate()
            if (layerId == null && style.content.isBlank()) return@editAdmitted 0L
            val old = layerId?.let { id -> state().layers.firstOrNull { it.id == id }
                ?: throw IllegalStateException("Text layer no longer exists") }
            val styleBytes = style.toMacJson().toString().toByteArray(Charsets.UTF_8)
            val layoutReserve = Math.addExact(1L * 1024 * 1024, Math.multiplyExact(style.content.length.toLong(), 64L))
            val rasterAllowance = allowance - styleBytes.size - layoutReserve
            require(rasterAllowance > 0) { "Text layer exceeds the available transient memory budget" }
            TextLayerRasterizer.render(style, rasterAllowance).use { raster ->
                val placement = if (old == null) {
                    val metrics = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = android.graphics.Typeface.create(style.fontName, android.graphics.Typeface.NORMAL)
                        textSize = style.fontSize.toFloat()
                    }.fontMetrics
                    val baseline = TextLayerLayoutContract.PADDING_PX + TextLayerLayoutContract.lineHeight(style) - metrics.descent
                    Transform64(atX - TextLayerLayoutContract.PADDING_PX, atY - baseline,
                        raster.width.toDouble(), raster.height.toDouble())
                } else {
                    // Inspector edits have no explicit handle transform. Like Mac applyText,
                    // keep the current scale and transformed upper-left for point and box text.
                    val before = old.transform
                    val width = raster.width * before.width / old.pixelWidth.coerceAtLeast(1)
                    val height = raster.height * before.height / old.pixelHeight.coerceAtLeast(1)
                    val radians = before.rotation * Math.PI / 180.0
                    val anchorX = before.x + before.width / 2 - before.width / 2 * kotlin.math.cos(radians) + before.height / 2 * kotlin.math.sin(radians)
                    val anchorY = before.y + before.height / 2 - before.width / 2 * kotlin.math.sin(radians) - before.height / 2 * kotlin.math.cos(radians)
                    val x = anchorX - width / 2 + width / 2 * kotlin.math.cos(radians) - height / 2 * kotlin.math.sin(radians)
                    val y = anchorY - height / 2 + width / 2 * kotlin.math.sin(radians) + height / 2 * kotlin.math.cos(radians)
                    before.copy(x = x, y = y, width = width, height = height)
                }
                val transform = ByteBuffer.allocateDirect(48).order(java.nio.ByteOrder.nativeOrder())
                    .putDouble(placement.x).putDouble(placement.y).putDouble(placement.width).putDouble(placement.height)
                    .putDouble(placement.rotation).putInt(if (placement.flipX) 1 else 0).putInt(if (placement.flipY) 1 else 0)
                    .apply { rewind() }
                commitTextLayer(handle, layerId ?: 0L, styleBytes,
                    raster.width, raster.height, raster.pixels.buffer, raster.rowBytes, transform, allowance)
            }
        }
    internal fun lineShape(id: Long): LineShapeMetadata? = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        val record = NativeTransformBridge.record(88)
        getLineShape(handle, id, record)
        if (record.getInt(0) == 0) null else LineShapeMetadata(record.getInt(4),
            record.getDouble(8), record.getDouble(16), record.getDouble(24), record.getDouble(32),
            record.getDouble(40), record.getDouble(48), record.getDouble(56), record.getDouble(64),
            record.getDouble(72), record.getInt(80), record.getInt(84))
    } }
    fun createGradient(spec: GradientSpec): Long = edit {
        check(!strokeActive)
        val target = state().layers.firstOrNull { it.id == spec.layerId }
            ?: throw IllegalStateException("Gradient target layer no longer exists")
        check(!target.isGroup && target.assetPresent) { "Gradient requires a pixel layer" }
        val transform = if (spec.settings.target == GradientTarget.MASK) target.maskPlacement ?: target.transform else target.transform
        val width = if (spec.settings.target == GradientTarget.MASK) target.maskWidth else target.pixelWidth
        val height = if (spec.settings.target == GradientTarget.MASK) target.maskHeight else target.pixelHeight
        check(width > 0 && height > 0)
        fun local(x: Double, y: Double): Pair<Double, Double> {
            val radians = -transform.rotation * Math.PI / 180.0
            val dx = x - (transform.x + transform.width / 2.0)
            val dy = y - (transform.y + transform.height / 2.0)
            val rx = dx * cos(radians) - dy * sin(radians)
            val ry = dx * sin(radians) + dy * cos(radians)
            var u = rx / transform.width + .5
            var v = ry / transform.height + .5
            if (transform.flipX) u = 1.0 - u
            if (transform.flipY) v = 1.0 - v
            return u * width to v * height
        }
        val start = local(spec.draft.startX, spec.draft.startY)
        val end = local(spec.draft.endX, spec.draft.endY)
        applyGradient(handle, spec.layerId, spec.settings.kind.ordinal, spec.settings.style.ordinal,
            spec.settings.reversed, spec.settings.target == GradientTarget.MASK,
            start.first, start.second, end.first, end.second,
            spec.foreground.red, spec.foreground.green, spec.foreground.blue, spec.foreground.alpha,
            spec.background.red, spec.background.green, spec.background.blue, spec.background.alpha,
            spec.settings.opacity.toFloat())
        0L
    }
    fun setGuides(values: List<GuideState>) = edit {
        check(!strokeActive)
        setGuides(handle, values.map { it.uuid }.toTypedArray(), IntArray(values.size) { if (values[it].horizontal) 1 else 0 },
            DoubleArray(values.size) { values[it].position })
    }
    fun putGuide(value: GuideState) = edit {
        check(!strokeActive)
        val current = state().document.guides
        val next = if (current.any { it.uuid.equals(value.uuid, ignoreCase = true) })
            current.map { if (it.uuid.equals(value.uuid, ignoreCase = true)) value.copy(uuid = it.uuid) else it } else current + value
        setGuides(handle, next.map { it.uuid }.toTypedArray(), IntArray(next.size) { if (next[it].horizontal) 1 else 0 },
            DoubleArray(next.size) { next[it].position })
    }
    fun removeGuide(uuid: String) = edit {
        check(!strokeActive)
        val next = state().document.guides.filterNot { it.uuid.equals(uuid, ignoreCase = true) }
        setGuides(handle, next.map { it.uuid }.toTypedArray(), IntArray(next.size) { if (next[it].horizontal) 1 else 0 },
            DoubleArray(next.size) { next[it].position })
    }
    fun undo(redo: Boolean) = edit { if (!strokeActive) {
        history(handle, redo)
        val dimensions = info(handle)
        canvasWidth = dimensions[0].toInt()
        canvasHeight = dimensions[1].toInt()
    } }
    fun addLayer() = edit { if (!strokeActive) addBlank(handle, canvasWidth, canvasHeight) }
    fun addGroup(): Long = edit { check(!strokeActive); addGroup(handle, canvasWidth, canvasHeight) }
    fun renameLayer(id: Long, value: String) = edit { check(!strokeActive); rename(handle, id, value.toByteArray(Charsets.UTF_8)) }
    fun deleteLayer(id: Long) = edit { check(!strokeActive); removeLayer(handle, id) }
    fun moveLayer(id: Long, index: Int) = edit { check(!strokeActive); moveLayer(handle, id, index) }
    fun reparentLayer(id: Long, parentId: Long) = edit { check(!strokeActive); parent(handle, id, parentId) }
    fun clipLayer(id: Long, sourceId: Long) = edit { check(!strokeActive); maskSource(handle, id, sourceId) }
    fun createMask(id: Long, white: Boolean) = edit {
        check(!strokeActive)
        val layer = state().layers.first { it.id == id }
        createMask(handle, id, if (layer.isGroup) canvasWidth else layer.pixelWidth,
            if (layer.isGroup) canvasHeight else layer.pixelHeight, white)
    }
    fun deleteMask(id: Long) = edit { check(!strokeActive); removeMask(handle, id) }
    fun enableMask(id: Long, enabled: Boolean) = edit { check(!strokeActive); maskEnabled(handle, id, enabled) }
    fun linkMask(id: Long, linked: Boolean) = edit { check(!strokeActive); maskLinked(handle, id, linked) }
    fun setMaskPlacement(id: Long, value: Transform64?, sampling: Int = 0) = edit {
        check(!strokeActive); maskPlacement(handle, id, value?.numbers(), value?.flipX ?: false, value?.flipY ?: false, sampling)
    }
    fun maskPixels(id: Long, rowBytes: Int, pixels: ByteBuffer) = lifetime.read {
        check(handle != 0L); readMask(handle, id, rowBytes, pixels)
    }
    fun importPixels(width: Int, height: Int, pixels: ByteBuffer, fit: Boolean = false): Long = edit {
        check(!strokeActive) { "Document cannot import during a stroke" }
        if (fit) addPixelsTransformed(handle, width, height, pixels)
        else addPixels(handle, width, height, pixels)
    }
    fun importTiff(image: NativeTiffCodec.Prepared, fit: Boolean = false): LongArray = editAdmitted(Long.MAX_VALUE) { allowance ->
        check(!strokeActive) { "Document cannot import during a stroke" }
        image.commit(handle, canvasWidth, canvasHeight, fit, allowance)
    }
    fun save(file: File): Long = confirmedCheckpoint(saveCheckpointResult(file, false))
    internal fun saveFrozenCheckpoint(file: File): Long = confirmedCheckpoint(saveCheckpointResult(file, true))
    private fun confirmedCheckpoint(result: CheckpointWriteResult): Long {
        if (!result.successful) throw CheckpointException(result.status, result)
        return result.capturedRevision
    }
    internal fun saveCheckpointResult(file: File, frozenOwner: Boolean = false): CheckpointWriteResult {
        commands.withLock {
            check(resourceGrowthFrozen == frozenOwner) { "Document checkpoint owner does not match its growth gate" }
            check(transformDraft == null && transformControl == null) { "Finish or cancel the active transform before saving" }
            ++retainedHandles
        }
        try {
            return lifetime.read {
                check(handle != 0L) { "Document is closed" }
                // Capture happens in native code; the external reference blocks admission during IO.
                CheckpointWriteResult.fromNative(saveCheckpoint(handle, file.absolutePath.toByteArray(Charsets.UTF_8)))
            }
        } finally { commands.withLock { --retainedHandles } }
    }
    fun draw(width: Int, height: Int, x: Double, y: Double, w: Double, h: Double, pixels: ByteBuffer): Boolean = commands.withLock { lifetime.read {
        check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
        check(transformDraft == null) { "Use an explicit transform preview scene" }
        if (handle == 0L) return false
        val bytes = samplingScratch(handle, width, height, x, y, w, h)
        NativeScene.withSamplingScratch(resourceLedger, bytes) {
            render(handle, width, height, x, y, w, h, pixels)
        }
        true
    } }
    fun draw(width: Int, height: Int, x: Float, y: Float, w: Float, h: Float, pixels: ByteBuffer) =
        draw(width, height, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), pixels)
    override fun close() {
        check(!commandDispatcher.isOwnerThread()) { "A command cannot close its own dispatcher" }
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || activeTransformCompletion() == null) {
            "Close an active transform asynchronously so its main acknowledgment can finish"
        }
        synchronized(closeGate) {
            if (commands.withLock { handle == 0L }) return
            var failure: Throwable? = null
            var interrupted = false
            try { prepareCommandClose() } catch (error: Throwable) { failure = error }
            val drained = commandDispatcher.barrier()
            while (true) {
                try { drained.get(); break }
                catch (error: InterruptedException) { interrupted = true }
                catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                    break
                }
            }
            commands.withLock { retainedTransform }?.awaitClosed()
            try { closeNative() }
            catch (error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
            finally {
                commandDispatcher.shutdown()
                if (interrupted) Thread.currentThread().interrupt()
            }
            failure?.let { throw it }
        }
    }
    internal fun adjustmentStyleOrNull(layerId: Long): AdjustmentLayerModel? = commands.withLock { lifetime.read {
        check(handle != 0L && layerId > 0)
        if (!typedLayerExistsLocked(layerId)) return@read null
        try {
            val bytes = adjustmentLayerJson(handle, layerId) ?: return@read null
            AdjustmentLayerModel.fromMacJson(org.json.JSONObject(bytes.toString(Charsets.UTF_8)))
        } catch (error: NativeCommandException) {
            if (error.status == 5) null else throw error // CP_UNSUPPORTED means this layer has no supported adjustment record.
        }
    } }

    /** Adjustment metadata is committed atomically as a typed project record and never flattened into source pixels. */
    internal fun commitAdjustmentLayer(layerId: Long?, style: AdjustmentLayerModel,
        publicationReceipt: AdjustmentLayerPublicationReceipt? = null): Long =
        editAdmitted(Long.MAX_VALUE) { allowance ->
            val old = layerId?.let { id -> state().layers.firstOrNull { it.id == id }
                ?: throw IllegalStateException("Adjustment layer no longer exists") }
            if (old != null) {
                check(old.blend == 0 && (old.opacity == 0.0 || old.opacity == 1.0)) {
                    "This adjustment subset supports only Normal blend and opacity 0 or 1"
                }
            }
            val bytes = style.toMacJson().toString().toByteArray(Charsets.UTF_8)
            val publishedLayer = setAdjustmentLayer(handle, layerId ?: 0L, bytes, allowance)
            // Save the exact native result before budget reconciliation or workspace observers run.
            publicationReceipt?.layerId = publishedLayer
            check(publishedLayer > 0) { "Adjustment publication returned no layer identity" }
            publishedLayer
        }
    private fun closeNative(): Unit = commands.withLock { lifetime.write {
        if (handle == 0L) return
        val closing = handle; handle = 0; committed = null; brushCursor = null; pendingEdits.clear(); visiblePendingEditCount = 0
        try { if (strokeActive) finish(closing, true) }
        finally {
            destroy(closing); strokeActive = false
            pendingMovePublication?.let { pending ->
                pending.plan.abort(); pending.peak.close(); pending.lease.close()
            }
            pendingMovePublication = null
            publicationTransfer?.abort(); publicationTransfer = null
            publicationEdit?.close(); publicationEdit = null
            transformDraft?.active = false
            transformDraft?.reservation?.close(); transformDraft = null
            pendingDuplicatePublication = null; pendingDuplicateCancel = null
            duplicatePresentation?.close(); duplicatePresentation = null
            strokeTransfer?.abort(); strokeTransfer = null
            strokePublication?.close(); strokePublication = null
            documentClaim?.close(); documentClaim = null
        }
    } }
    internal fun documentResolution(): Double = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        resolution(handle)
    } }
    fun captureScene(): NativeScene? = captureSceneOwned(null)
    private fun captureSceneOwned(admission: DisplaySceneAdmission?, display: Boolean = false): NativeScene? = commands.withLock { lifetime.read {
        if (handle == 0L) null else {
            check(!resourceGrowthFrozen) { "Document resource growth is frozen" }
            check(transformDraft == null) { "Use an explicit transform preview scene" }
            // Brush's separately admitted transient preview retains its legacy capture contract.
            var rawBytes = 0L
            val lease = if (strokeActive) null else {
                NativeTransformBridge.requireSuccess(NativeTransformBridge.estimateScene(handle, sceneMemoryRecord))
                rawBytes = sceneMemoryRecord.getLong(32).also { check(it >= 0) }
                // Export and sampling snapshots can outlive later edits or document close.
                // Charge their full retained inventory instead of borrowing document tiles.
                val bytes = if (display) displaySceneClaim(rawBytes) else rawBytes
                if (admission != null) {
                    // The installed document is a separate lease, not the ledger's resident baseline.
                    val maximum = resourceLedger.limitBytes - resourceLedger.snapshot().residentBytes - checkNotNull(documentClaim).bytes
                    if (bytes > maximum)
                        throw ResourceBudgetExceededException(bytes, maximum.coerceAtLeast(0), "committed display scene")
                }
                if (admission == null || bytes > resourceLedger.limitBytes)
                    resourceLedger.reserve(bytes, "committed display scene")
                else resourceLedger.tryReserve(bytes, "committed display scene") ?: run {
                    admission.defer(bytes)
                    return@read null
                }
            }
            var captured = 0L
            try {
                captured = if (lease == null) snapshot(handle) else {
                    // The native budget is deliberately the raw occurrence based
                    // estimate. The managed claim above accounts only the
                    // incremental ownership because the document already holds
                    // the immutable tile payloads.
                    NativeTransformBridge.requireSuccess(NativeTransformBridge.snapshotBudgeted(handle,
                        rawBytes, 0, sceneHandleRecord))
                    sceneHandleRecord.getLong(0)
                }
                check(captured != 0L)
                NativeScene(captured, resourceLedger) {
                    commands.withLock {
                        --retainedHandles
                        if (lease != null) {
                            if (display) --displayHandles else --budgetedSceneHandles
                        }
                        synchronized(displayIdleGate) { displayIdleGate.notifyAll() }
                    }
                    lease?.close()
                }.also {
                    ++retainedHandles
                    // Legacy stroke scenes have no independent inventory and must retire before
                    // Move, even when a consumer retains one after the stroke has finished.
                    if (lease != null) {
                        if (display) ++displayHandles else ++budgetedSceneHandles
                    }
                }
            } catch (error: Throwable) {
                if (captured != 0L) destroyScene(captured)
                lease?.close(); throw error
            }
        }
    } }
    fun captureProjectSnapshot(): NativeProjectSnapshot = captureProjectSnapshotOwned(false)
    internal fun captureFrozenProjectSnapshot(): NativeProjectSnapshot = captureProjectSnapshotOwned(true)
    private fun captureProjectSnapshotOwned(frozenOwner: Boolean): NativeProjectSnapshot = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        check(resourceGrowthFrozen == frozenOwner) { "Project snapshot owner does not match its growth gate" }
        check(transformDraft == null && transformControl == null) { "Finish or cancel the active transform before capturing a project" }
        NativeProjectSnapshot({ snapshotProject(handle) }, { commands.withLock { --retainedHandles } }).also { ++retainedHandles }
    } }
    fun captureCheckpointSnapshot(admission: CheckpointAdmission = CheckpointAdmission()): NativeCheckpointSnapshot =
        captureCheckpointSnapshotOwned(false, admission)
    internal fun captureFrozenCheckpointSnapshot(admission: CheckpointAdmission = CheckpointAdmission()): NativeCheckpointSnapshot =
        captureCheckpointSnapshotOwned(true, admission)
    private fun captureCheckpointSnapshotOwned(frozenOwner: Boolean, admission: CheckpointAdmission): NativeCheckpointSnapshot = commands.withLock { lifetime.read {
        check(handle != 0L) { "Document is closed" }
        check(resourceGrowthFrozen == frozenOwner) { "Checkpoint snapshot owner does not match its growth gate" }
        check(transformDraft == null && transformControl == null) { "Finish or cancel the active transform before capturing a checkpoint" }
        val independentlyBudgeted = admission.sharesLedger(resourceLedger)
        val reservation = try { admission.reserveCapture() }
            catch (error: ResourceBudgetExceededException) { throw CheckpointException(3).also { it.initCause(error) } }
        try {
            NativeCheckpointSnapshot({
                admission.beforeCapture(reservation)
                snapshotCheckpoint(handle, reservation.bytes, 0)
            }, admission, reservation, {
                commands.withLock {
                    --retainedHandles
                    if (independentlyBudgeted) --budgetedCheckpointHandles
                    Unit
                }
            }).also {
                ++retainedHandles
                if (independentlyBudgeted) ++budgetedCheckpointHandles
            }
        } catch (error: Throwable) { reservation.close(); throw error }
    } }
    private external fun destroyScene(handle: Long)
    private external fun snapshotProject(handle: Long): LongArray
    private external fun snapshotCheckpoint(handle: Long, limitBytes: Long, residentBytes: Long): LongArray
    private external fun memoryInfo(handle: Long): LongArray
    private external fun snapshot(handle: Long): Long
    private external fun snapshotAdjustmentPreview(handle: Long, layer: Long, expectedRevision: Long,
        settingsJson: ByteArray, limitBytes: Long, residentBytes: Long): Long
    private external fun create(size: Int): Long
    private external fun createRectangular(width: Int, height: Int): Long
    private external fun destroy(handle: Long)
    private external fun addBlank(handle: Long, width: Int, height: Int): Long
    private external fun addPixels(handle: Long, width: Int, height: Int, pixels: ByteBuffer): Long
    private external fun addPixelsTransformed(handle: Long, width: Int, height: Int, pixels: ByteBuffer): Long
    private external fun fillBackground(handle: Long, id: Long, size: Double)
    private external fun loadCheckpoint(path: ByteArray, limitBytes: Long, residentBytes: Long): LongArray
    private external fun saveCheckpoint(handle: Long, path: ByteArray): LongArray
    private external fun info(handle: Long): LongArray
    private external fun abiVersion(): Int
    private external fun contentIdentity(handle: Long): Long
    private external fun revision(handle: Long): Long
    private external fun sceneGeneration(handle: Long): Long
    private external fun layer(handle: Long, index: Int): LongArray
    private external fun precision(handle: Long, index: Int): DoubleArray
    private external fun metadata(handle: Long): LongArray
    private external fun documentUuid(handle: Long): String
    private external fun resolution(handle: Long): Double
    private external fun layerUuid(handle: Long, id: Long): String
    private external fun assetPresent(handle: Long, id: Long): Boolean
    private external fun guideIds(handle: Long): Array<String>
    private external fun guidePositions(handle: Long): DoubleArray
    private external fun guideAxes(handle: Long): IntArray
    private external fun setResolution(handle: Long, value: Double)
    private external fun crop(handle: Long, width: Int, height: Int, offsetX: Double, offsetY: Double)
    private external fun addPixelsAt(handle: Long, width: Int, height: Int, pixels: ByteBuffer, x: Double, y: Double): Long
    private external fun applyShape(handle: Long, kind: Int, width: Int, height: Int, x: Double, y: Double,
        cornerRadius: Double, lineWidth: Double, startX: Double, startY: Double, endX: Double, endY: Double,
        color: Int): Long
    private external fun applyLineShape(handle: Long, width: Int, height: Int, x: Double, y: Double,
        geometryWidth: Double, geometryHeight: Double, lineWidth: Double,
        startX: Double, startY: Double, endX: Double, endY: Double,
        style: Int, cap: Int, color: Int, transientLimitBytes: Long): Long
    private external fun commitTextLayer(handle: Long, layer: Long, styleJson: ByteArray, width: Int, height: Int,
        pixels: ByteBuffer, rowBytes: Int, transform: ByteBuffer, transientLimitBytes: Long): Long
    private external fun textLayerJson(handle: Long, layer: Long): ByteArray?
    private external fun setAdjustmentLayer(handle: Long, layer: Long, settingsJson: ByteArray,
        transientLimitBytes: Long): Long
    private external fun adjustmentLayerJson(handle: Long, layer: Long): ByteArray?
    private external fun getLineShape(handle: Long, id: Long, output: ByteBuffer)
    private external fun applyGradient(handle: Long, layer: Long, kind: Int, style: Int, reversed: Boolean,
        targetMask: Boolean, startX: Double, startY: Double, endX: Double, endY: Double,
        foregroundRed: Float, foregroundGreen: Float, foregroundBlue: Float, foregroundAlpha: Float,
        backgroundRed: Float, backgroundGreen: Float, backgroundBlue: Float, backgroundAlpha: Float,
        opacity: Float)
    private external fun activeLayer(handle: Long, id: Long)
    private external fun setGuides(handle: Long, ids: Array<String>, axes: IntArray, positions: DoubleArray)
    private external fun placement(handle: Long, id: Long): DoubleArray
    private external fun structure(handle: Long, id: Long): LongArray
    private external fun name(handle: Long, id: Long): ByteArray
    private external fun rename(handle: Long, id: Long, value: ByteArray)
    private external fun addGroup(handle: Long, width: Int, height: Int): Long
    private external fun removeLayer(handle: Long, id: Long)
    private external fun moveLayer(handle: Long, id: Long, index: Int)
    private external fun parent(handle: Long, id: Long, parentId: Long)
    private external fun maskSource(handle: Long, id: Long, sourceId: Long)
    private external fun createMask(handle: Long, id: Long, width: Int, height: Int, white: Boolean)
    private external fun removeMask(handle: Long, id: Long)
    private external fun maskEnabled(handle: Long, id: Long, enabled: Boolean)
    private external fun maskLinked(handle: Long, id: Long, linked: Boolean)
    private external fun maskPlacement(handle: Long, id: Long, numbers: DoubleArray?, flipX: Boolean, flipY: Boolean, sampling: Int)
    private external fun readMask(handle: Long, id: Long, rowBytes: Int, pixels: ByteBuffer)
    private external fun beginMask(handle: Long, id: Long, diameter: Float, erasing: Boolean, transientLimitBytes: Long)
    private external fun opacity(handle: Long, id: Long, value: Double)
    private external fun visible(handle: Long, id: Long, value: Boolean)
    private external fun blend(handle: Long, id: Long, value: Int)
    private external fun transform(handle: Long, id: Long, numbers: DoubleArray, flipX: Boolean, flipY: Boolean)
    private external fun setTransforms(handle: Long, ids: LongArray, numbers: DoubleArray, flips: IntArray)
    private external fun sampling(handle: Long, id: Long, value: Int)
    private external fun begin(handle: Long, id: Long, diameter: Float, erasing: Boolean, transientLimitBytes: Long)
    private external fun beginConfigured(handle: Long, id: Long, diameter: Float, hardness: Float,
        opacity: Float, red: Float, green: Float, blue: Float, erasing: Boolean, mask: Boolean,
        maskPaintWhite: Boolean, pressureSize: Boolean, pressureOpacity: Boolean, transientLimitBytes: Long)
    private external fun beginSmear(handle: Long, id: Long, diameter: Float, hardness: Float,
        strength: Float, mode: Int, transientLimitBytes: Long)
    private external fun beginClone(handle: Long, id: Long, diameter: Float, hardness: Float,
        opacity: Float, offsetX: Double, offsetY: Double, sampleAllLayers: Boolean, transientLimitBytes: Long)
    private external fun append(handle: Long, samples: DoubleArray)
    private external fun beginHealing(handle: Long, id: Long, diameter: Float, hardness: Float,
        opacity: Float, mode: Int, seed: Long, transientLimitBytes: Long): Int
    private external fun finishHealing(handle: Long, cancel: Boolean): Int
    private external fun finish(handle: Long, cancel: Boolean)
    private external fun history(handle: Long, redo: Boolean)
    private external fun render(handle: Long, width: Int, height: Int, x: Double, y: Double, w: Double, h: Double, pixels: ByteBuffer)
    private external fun samplingScratch(handle: Long, width: Int, height: Int, x: Double, y: Double, w: Double, h: Double): Long
}
