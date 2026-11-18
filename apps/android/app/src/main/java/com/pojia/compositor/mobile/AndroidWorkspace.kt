package com.pojia.compositor.mobile

import android.os.Handler
import android.os.Looper
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class WorkspaceState(
    val editor: NativeEditor? = null, val loading: Boolean = true,
    val recovered: Boolean = false, val recoverySavedRevision: Long = -1,
    val currentRevision: Long = 0, val generation: Long = 0,
    // Revision is provenance metadata; content identities below belong only to this editor lifetime.
    val projectBaseline: Long? = null, val sourceIdentity: String? = null,
    val currentContentIdentity: Long = 0, val projectContentBaseline: Long? = null,
    val recoveryError: String? = null, val saveError: String? = null,
    val recoveryDurabilityUncertain: Boolean = false,
    // Native writer outcome or caller admission/native/JNI preparation refusal; unavailable writer outcomes stay null.
    // Preparation refusal starts no writer and exposes no revision; active pointer durability is separate.
    val lastCheckpointWrite: CheckpointWriteResult? = null,
    val lastCheckpointSnapshot: CheckpointSnapshotInfo? = null,
    val resourceGrowthFrozen: Boolean = false,
    val recoveryErrorNotice: UiNotice? = null, val saveErrorNotice: UiNotice? = null,
) {
    val savedRevision: Long get() = recoverySavedRevision
    val projectDirty: Boolean get() = projectContentBaseline == null || currentContentIdentity != projectContentBaseline
}
internal data class WorkspaceToken(val generation: Long, val editor: NativeEditor,
    val revision: Long, val operationId: Long, val commandEpoch: Long = 0)
internal enum class DirtyDisposition { REJECT, DISCARD }
internal data class InstallResult(val installed: Boolean, val generation: Long = 0,
    val durabilityUncertain: Boolean = false, val reason: String? = null, val errorNotice: UiNotice? = null)
internal class InstallOperation internal constructor(val token: WorkspaceToken, private val publicationGate: Any) {
    private var cancelled = false
    val result = CompletableFuture<InstallResult>()
    fun cancel() = synchronized(publicationGate) { cancelled = true }
    internal val isCancelled: Boolean get() = synchronized(publicationGate) { cancelled }
    internal fun publish(action: () -> Unit) = synchronized(publicationGate) {
        check(!cancelled) { "Workspace installation cancelled" }
        action()
    }
}

/** A command lease expires at replacement or close, including commands still queued on a paused GL thread. */
internal class WorkspaceLease internal constructor(private val workspace: AndroidWorkspace, val token: WorkspaceToken) {
    fun isCurrent(): Boolean = workspace.isCurrent(token)
    fun commandToken(): WorkspaceToken? = workspace.commandToken(token)
    fun execute(action: () -> Unit): Boolean = commandToken()?.let { workspace.execute(it, action) } ?: false
    fun execute(captured: WorkspaceToken, action: () -> Unit): Boolean = workspace.execute(captured, action)
    internal fun executeAccepted(captured: WorkspaceToken, ticket: PendingEditTicket, action: () -> Unit): Boolean =
        workspace.executeAccepted(captured, ticket, action)
}

/** Only the fenced workspace worker may use this lease; old document and renderer growth stay frozen. */
internal class WorkspaceIoLease internal constructor(private val workspace: AndroidWorkspace,
    internal val token: WorkspaceToken, val memory: RetainedMemory) {
    internal var deliveryToken = token
    internal val owner = Thread.currentThread()
    internal val frozenEditors = mutableListOf(token.editor)
    fun saveProject(context: Context, directory: File, replace: Boolean, limitBytes: Long, residentBytes: Long,
        sourceIdentity: String, fault: RecoveryFault = RecoveryFault.NONE): ProjectSaveResult =
        workspace.saveFromLease(this, context, directory, replace, limitBytes, residentBytes, sourceIdentity, fault)
    fun installProjectCandidate(context: Context, staging: File, limitBytes: Long, residentBytes: Long,
        sourceIdentity: String, disposition: DirtyDisposition = DirtyDisposition.REJECT): InstallResult =
        workspace.installFromLease(this, context, staging, limitBytes, residentBytes, sourceIdentity, disposition)
    fun installImageCandidate(sourceIdentity: String, disposition: DirtyDisposition, candidate: () -> NativeEditor): InstallResult =
        workspace.installImageFromLease(this, sourceIdentity, disposition, candidate)
    fun installNewProjectCandidate(context: Context, staging: File, limitBytes: Long, residentBytes: Long,
        disposition: DirtyDisposition): InstallResult =
        workspace.installFromLease(this, context, staging, limitBytes, residentBytes, "new-canvas", disposition, true)
}

/** One worker owns recovery and candidate IO; editor command locks serialize the final installation. */
internal class AndroidWorkspace(private val directory: File,
    private val recoveryAdmission: RecoveryAdmission = RecoveryAdmission(),
    private val recoveryHooks: RecoveryPublicationHooks? = null,
    private val checkpointAdmission: CheckpointAdmission = CheckpointAdmission(),
    private var changed: ((WorkspaceState) -> Unit)? = null) {
    private val lock = Any()
    private val publicationGate = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    // Waiting for an editor command must not block main or the recovery/install writer.
    private val lifecycleWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var closePreparationSubmitted = false
    private var notificationPending = false
    private val store = WorkspaceRecoveryStore(directory)
    private var target: RecoveryTarget? = null
    private var state = WorkspaceState()
    private var pending: ScheduledFuture<*>? = null
    private var deferredAutosave: CompletableFuture<TransformController.Terminal>? = null
    private var closing = false
    private var installing = false
    private var creatingNew = false
    private var operationId = 0L
    private var install: InstallOperation? = null
    private val projectSaves = mutableSetOf<ProjectSaveOperation>()
    private var commandEpoch = 0L
    private var frozenIo: WorkspaceIoLease? = null
    private var closeJob: Future<*>? = null
    private val closePreparation = CompletableFuture<Throwable?>()
    init { worker.execute { open() } }

    internal fun snapshot(): WorkspaceState = synchronized(lock) { state }
    internal fun recoveryDirectoryForTest(): File = directory
    internal fun scheduledAutosaveForTest(): Future<*>? = synchronized(lock) { pending }
    fun token(editor: NativeEditor? = null): WorkspaceToken? {
        val value = synchronized(lock) { if (closing) null else state.editor } ?: return null
        if (editor != null && editor !== value) return null
        return value.serialized {
            // A captured editor may have retired while waiting for its command gate.
            if (synchronized(lock) { closing || state.editor !== value }) return@serialized null
            val revision = value.currentRevision()
            synchronized(lock) {
                if (closing || state.editor !== value) null
                else WorkspaceToken(state.generation, value, revision, ++operationId, commandEpoch)
            }
        }
    }
    fun lease(editor: NativeEditor): WorkspaceLease? = token(editor)?.let { WorkspaceLease(this, it) }
    internal fun commandToken(original: WorkspaceToken): WorkspaceToken? {
        if (synchronized(lock) { closing || frozenIo != null || !matches(original) }) return null
        return token(original.editor)
    }
    internal fun isCurrent(token: WorkspaceToken): Boolean = synchronized(lock) {
        !closing && state.generation == token.generation && state.editor === token.editor
    }
    internal fun execute(token: WorkspaceToken, action: () -> Unit): Boolean = token.editor.serialized {
        if (synchronized(lock) { closing || installing || frozenIo != null || commandEpoch != token.commandEpoch || !matches(token) }) false
        else { action(); true }
    }
    /** Only the retained dispatcher owns accepted tickets; close drains them before saving and destruction. */
    internal fun executeAccepted(token: WorkspaceToken, ticket: PendingEditTicket, action: () -> Unit): Boolean = token.editor.serialized {
        if (!token.editor.ownsPendingEdit(ticket) || synchronized(lock) {
                installing || frozenIo != null || commandEpoch != token.commandEpoch || !matches(token) }) false
        else { action(); true }
    }
    private fun matches(token: WorkspaceToken) = state.generation == token.generation && state.editor === token.editor
    private fun deliver(token: WorkspaceToken, action: () -> Unit) = token.editor.serialized {
        if (isCurrent(token)) action()
    }
    private fun notifyState() {
        synchronized(lock) {
            if (closing || notificationPending) return
            notificationPending = true
        }
        main.post { deliverStateNotification() }
    }
    private fun deliverStateNotification() {
        val editor = synchronized(lock) {
            if (closing) { notificationPending = false; return }
            state.editor
        }
        val deliver = {
            val notification = synchronized(lock) {
                notificationPending = false
                if (!closing && state.editor === editor) changed?.let { it to state } else null
            }
            if (notification != null) notification.first(notification.second)
            else if (synchronized(lock) { !closing && state.editor !== editor }) notifyState()
        }
        // A notification can be queued immediately before a long retained command starts.
        // Preserve its freshness check without making the main looper await that command.
        if (editor == null) deliver()
        else if (!editor.trySerialized(deliver)) main.postDelayed({ deliverStateNotification() }, 32)
    }
    private fun bind(editor: NativeEditor, generation: Long) {
        editor.committed = {
            // NativeEditor.edit notifies after releasing its command gate. Reacquire it so out-of-order
            // callbacks publish one current revision/content pair, never an old revision with a new tag.
            editor.serialized {
                if (synchronized(lock) { !closing && state.editor === editor && state.generation == generation }) {
                    val revision = editor.currentRevision()
                    val identity = editor.currentContentIdentity()
                    synchronized(lock) {
                        if (!closing && state.editor === editor && state.generation == generation) {
                            state = state.copy(currentRevision = revision, currentContentIdentity = identity)
                            scheduleSaveLocked()
                            notifyState()
                        }
                    }
                }
            }
        }
    }
    private data class ProjectBaseline(val revision: Long?, val identity: Long?, val source: String?)
    private data class RecoveryCapture(val target: RecoveryTarget, val snapshot: NativeCheckpointSnapshot)
    private data class RecoveryWrite(val target: RecoveryTarget, val revision: Long)
    private fun writeRecoveryCheckpoint(kind: RecoverySaveKind, recovery: RecoveryTarget,
        editor: NativeEditor, frozenOwner: Boolean = false, baseline: ProjectBaseline? = null): RecoveryWrite =
        writeRecoverySnapshot(kind, checkNotNull(captureRecoveryCheckpoint(kind, recovery, editor, frozenOwner, baseline)))
    private fun captureRecoveryCheckpoint(kind: RecoverySaveKind, recovery: RecoveryTarget,
        editor: NativeEditor, frozenOwner: Boolean = false, baseline: ProjectBaseline? = null,
        autosaveToken: WorkspaceToken? = null): RecoveryCapture? {
        val file = store.checkpoint(recovery)
        synchronized(lock) { state = state.copy(lastCheckpointWrite = null, lastCheckpointSnapshot = null) }
        recoveryHooks?.beforeCheckpoint?.invoke(kind, recovery, file)
        return try { editor.serialized {
            val completion = if (autosaveToken != null) editor.activeTransformCompletion() else null
            if (completion != null) {
                deferAutosave(checkNotNull(autosaveToken), completion)
                return@serialized null
            }
            val project = baseline ?: synchronized(lock) {
                check(state.editor === editor) { "Recovery baseline belongs to a different document" }
                ProjectBaseline(state.projectBaseline, state.projectContentBaseline, state.sourceIdentity)
            }
            val captured = if (frozenOwner) editor.captureFrozenCheckpointSnapshot(checkpointAdmission)
                else editor.captureCheckpointSnapshot(checkpointAdmission)
            try {
                val clean = project.identity != null && captured.capturedContentIdentity == project.identity
                RecoveryCapture(recovery.copy(projectBaseline = project.revision, sourceIdentity = project.source,
                    projectClean = clean), captured)
            } catch (error: Throwable) { captured.close(); throw error }
        } } catch (error: CheckpointException) {
            // Caller admission or native/JNI preparation starts no writer and exposes no available revision or size.
            val refused = CheckpointWriteResult(error.status, 0, false, false, false)
            synchronized(lock) { state = state.copy(lastCheckpointWrite = refused) }
            throw CheckpointException(error.status, refused).also { it.initCause(error) }
        }
    }
    private fun writeRecoverySnapshot(kind: RecoverySaveKind, captured: RecoveryCapture): RecoveryWrite {
        val recovery = captured.target
        val (file, written) = captured.snapshot.use { snapshot ->
            val file = store.checkpoint(recovery)
            synchronized(lock) { state = state.copy(lastCheckpointSnapshot = snapshot.info) }
            file to snapshot.write(file)
        }
        synchronized(lock) { state = state.copy(lastCheckpointWrite = written) }
        if (!written.successful) throw CheckpointException(written.status, written)
        recoveryHooks?.afterCheckpoint?.invoke(kind, recovery, file, written.capturedRevision)
        return RecoveryWrite(recovery, written.capturedRevision)
    }
    private fun commitRecoveryPointer(kind: RecoverySaveKind, recovery: RecoveryTarget,
        fault: RecoveryFault = RecoveryFault.NONE, publish: (() -> Unit) -> Unit = { it() },
        probe: PublicationProbe? = null, canCommit: () -> Boolean = { true }): PointerCommit =
        store.commit(recovery, if (fault == RecoveryFault.NONE) recoveryHooks?.pointerFault?.invoke(kind, recovery) ?: fault else fault,
            publish, probe ?: recoveryHooks?.publicationProbe?.invoke(kind, recovery), canCommit)
    private fun open() {
        var opening: NativeEditor? = null
        try {
            check(directory.exists() || directory.mkdirs()) { "The recovery folder could not be created" }
            val existing = store.read()
            val recovered = existing != null || store.legacy.exists()
            val editor = if (existing != null) recoveryAdmission.load(store.checkpoint(existing))
                else if (store.legacy.exists()) recoveryAdmission.load(store.legacy) else NativeEditor()
            opening = editor
            check(existing == null || editor.state().document.uuid.equals(existing.documentUuid, ignoreCase = true)) {
                "Recovery checkpoint identity does not match its pointer"
            }
            existing?.projectBaseline?.let { baseline ->
                check(baseline <= editor.currentRevision()) { "Recovery project baseline exceeds the checkpoint revision" }
            }
            val openingRevision = editor.currentRevision()
            val identity = editor.currentContentIdentity()
            val clean = existing?.projectClean ?: (existing?.projectBaseline?.let { it == openingRevision } ?: false)
            val contentBaseline = if (clean) identity else null
            var next = existing ?: store.target(1, editor, null, null)
            var durability: String? = null
            if (existing == null) {
                next = writeRecoveryCheckpoint(RecoverySaveKind.BOOTSTRAP, next, editor,
                    baseline = ProjectBaseline(null, null, null)).target
                durability = commitRecoveryPointer(RecoverySaveKind.BOOTSTRAP, next,
                    publish = { action -> synchronized(publicationGate) {
                        check(synchronized(lock) { !closing }) { "Workspace closed before recovery publication" }
                        action()
                    } }, canCommit = { synchronized(lock) { !closing } }).durabilityError
            }
            val revision = editor.currentRevision()
            synchronized(publicationGate) { synchronized(lock) {
                if (closing) return
                target = next
                state = WorkspaceState(editor = editor, loading = false, recovered = recovered,
                    recoverySavedRevision = revision, currentRevision = revision, generation = next.generation,
                    projectBaseline = next.projectBaseline, sourceIdentity = next.sourceIdentity,
                    currentContentIdentity = identity, projectContentBaseline = contentBaseline, saveError = durability,
                    saveErrorNotice = durability?.let { UiNotice(UiNoticeKey.RECOVERY_DURABILITY) },
                    recoveryDurabilityUncertain = durability != null, lastCheckpointWrite = state.lastCheckpointWrite,
                    lastCheckpointSnapshot = state.lastCheckpointSnapshot)
                bind(editor, next.generation)
                opening = null
            } }
            notifyState()
        } catch (error: Exception) {
            synchronized(publicationGate) { synchronized(lock) { state = WorkspaceState(loading = false,
                recoveryError = "${error.message ?: "Recovery failed"}. The existing saved document has been kept.",
                recoveryErrorNotice = uiFailure(error, UiNoticeKey.RECOVERY_OPEN_FAILED),
                lastCheckpointWrite = state.lastCheckpointWrite, lastCheckpointSnapshot = state.lastCheckpointSnapshot) } }
            notifyState()
        } finally { opening?.close() }
    }
    private fun scheduleSaveLocked(delay: Long = 700) {
        if (closing || frozenIo != null || state.editor == null) return
        if (deferredAutosave != null) return
        pending?.cancel(false)
        val captured = WorkspaceToken(state.generation, state.editor!!, state.currentRevision, ++operationId)
        pending = worker.schedule({ saveCurrent(captured) }, delay, TimeUnit.MILLISECONDS)
    }
    private fun deferAutosave(token: WorkspaceToken, completion: CompletableFuture<TransformController.Terminal>) {
        synchronized(lock) {
            if (closing || !matches(token) || deferredAutosave === completion) return
            deferredAutosave = completion
        }
        // Completion follows main acknowledgment. Neither the main looper nor this IO worker waits.
        completion.whenComplete { _, _ -> synchronized(lock) {
            if (deferredAutosave === completion) {
                deferredAutosave = null
                if (!closing && matches(token)) scheduleSaveLocked(0)
            }
        } }
    }
    private fun saveCurrent(token: WorkspaceToken, kind: RecoverySaveKind = RecoverySaveKind.AUTOSAVE): Long {
        val current = synchronized(lock) { if (matches(token)) state else null } ?: return -1
        val recovery = synchronized(lock) { if (matches(token)) target else null } ?: return -1
        if (token.editor.currentRevision() == current.recoverySavedRevision && !current.recoveryDurabilityUncertain)
            return current.recoverySavedRevision
        return try {
            // An already-active draft must not enter capture hooks or clear the last checkpoint outcome.
            // Capture checks again after its hook, because a new draft may start while that hook runs.
            val deferred = kind == RecoverySaveKind.AUTOSAVE && token.editor.serialized {
                token.editor.activeTransformCompletion()?.let {
                    deferAutosave(token, it)
                    true
                } ?: false
            }
            if (deferred) return current.recoverySavedRevision
            // Local baseline/source remain authoritative after an uncertain source-save pointer commit.
            val next = store.attempt(recovery).copy(projectBaseline = current.projectBaseline, sourceIdentity = current.sourceIdentity)
            val captured = captureRecoveryCheckpoint(kind, next, token.editor,
                autosaveToken = if (kind == RecoverySaveKind.AUTOSAVE) token else null)
                ?: return current.recoverySavedRevision
            val written = writeRecoverySnapshot(kind, captured)
            val revision = written.revision
            val capturedTarget = written.target
            val latestRevision = token.editor.currentRevision()
            fun canCommit() = synchronized(lock) { matches(token) && (!closing || kind == RecoverySaveKind.CLOSE) }
            val committed = commitRecoveryPointer(kind, capturedTarget, publish = { action -> synchronized(publicationGate) {
                check(canCommit()) { "Workspace changed before recovery publication" }
                action()
            } }, canCommit = { canCommit() })
            synchronized(lock) {
                if (matches(token)) {
                    target = capturedTarget
                    state = state.copy(recoverySavedRevision = revision,
                        saveError = committed.durabilityError, recoveryDurabilityUncertain = committed.durabilityError != null,
                        saveErrorNotice = committed.durabilityError?.let { UiNotice(UiNoticeKey.RECOVERY_DURABILITY) })
                    if (latestRevision != revision) scheduleSaveLocked()
                }
            }
            notifyState()
            // A visible rename is adopted, but flush/close must not claim confirmed durability.
            committed.durabilityError?.let { throw IllegalStateException(it) }
            revision
        } catch (error: Exception) {
            synchronized(lock) {
                if (matches(token)) {
                    if (error is CheckpointException && error.status == 4) scheduleSaveLocked(200)
                    else state = state.copy(saveError = error.message ?: "Recovery save failed",
                        saveErrorNotice = uiFailure(error, UiNoticeKey.RECOVERY_SAVE_FAILED))
                }
            }
            notifyState(); throw error
        }
    }
    fun startNewDocument() = synchronized(lock) {
        if (closing || creatingNew || state.editor != null) return@synchronized
        creatingNew = true
        worker.execute {
            try { store.preserveRejected(); open() }
            catch (error: Exception) {
                synchronized(publicationGate) { synchronized(lock) {
                    state = WorkspaceState(loading = false, recoveryError = error.message ?: "Recovery failed",
                        recoveryErrorNotice = uiFailure(error, UiNoticeKey.RECOVERY_OPEN_FAILED))
                } }
                notifyState()
            } finally { synchronized(lock) { creatingNew = false } }
        }
    }
    fun <T> runIo(work: () -> T, done: (Result<T>) -> Unit) {
        token()?.let { runIo(it, work, done) }
    }
    /** Private filesystem maintenance belongs to the workspace lifetime, not a document generation. */
    fun <T> runMaintenance(work: () -> T, done: (Result<T>) -> Unit) = synchronized(lock) {
        if (closing) {
            main.post { done(Result.failure(IllegalStateException("Workspace closed before maintenance"))) }
            return@synchronized
        }
        worker.execute {
            val result = runCatching {
                check(synchronized(lock) { !closing }) { "Workspace closed before maintenance" }
                work()
            }
            main.post { done(result) }
        }
    }
    fun <T> runIo(token: WorkspaceToken, work: () -> T, done: (Result<T>) -> Unit) = runIo(token, false, work, done)
    fun <T> runIo(token: WorkspaceToken, retainedCompletion: Boolean, work: () -> T, done: (Result<T>) -> Unit) = synchronized(lock) {
        if (closing || !matches(token)) {
            if (retainedCompletion) main.post { done(Result.failure(IllegalStateException("Workspace changed before IO"))) }
            return@synchronized
        }
        worker.execute {
            // A retained owner must finish its own operation even after the originating generation retires.
            val result = runCatching {
                check(isCurrent(token)) { "Workspace changed before IO" }
                work()
            }
            main.post { if (retainedCompletion) done(result) else deliver(token) { done(result) } }
        }
    }
    fun <T> withFrozenIo(expectedToken: WorkspaceToken, work: (WorkspaceIoLease) -> T,
        done: (Result<T>) -> Unit): Future<T> = withFrozenIo(expectedToken, false, work, done)
    fun <T> withFrozenIo(expectedToken: WorkspaceToken, retainedCompletion: Boolean, work: (WorkspaceIoLease) -> T,
        done: (Result<T>) -> Unit): Future<T> {
        val pendingFailure = expectedToken.editor.serialized {
            check(synchronized(lock) { !closing && matches(expectedToken) }) { "Workspace changed before resource admission" }
            if (expectedToken.editor.hasPendingEdits) IllegalStateException(PENDING_EDIT_MESSAGE) else null
        }
        if (pendingFailure != null) {
            val result = Result.failure<T>(pendingFailure)
            main.post { if (retainedCompletion) done(result) else deliver(expectedToken) { done(result) } }
            return CompletableFuture<T>().also { it.completeExceptionally(pendingFailure) }
        }
        return synchronized(lock) {
            check(!closing && matches(expectedToken)) { "Workspace changed before resource admission" }
            worker.submit<T> {
                var lease: WorkspaceIoLease? = null
                val result = runCatching {
                    expectedToken.editor.serialized {
                        check(!expectedToken.editor.hasPendingEdits) { PENDING_EDIT_MESSAGE }
                        check(isCurrent(expectedToken) && expectedToken.editor.currentRevision() == expectedToken.revision) {
                            "Workspace changed while choosing a project"
                        }
                        val memory = expectedToken.editor.freezeResourceGrowth()
                        try {
                            synchronized(lock) {
                                check(!closing && frozenIo == null && matches(expectedToken)) { "Workspace admission was cancelled" }
                                ++commandEpoch
                                pending?.cancel(false); pending = null
                                val captured = WorkspaceToken(state.generation, expectedToken.editor, memory.revision, ++operationId, commandEpoch)
                                lease = WorkspaceIoLease(this, captured, memory)
                                frozenIo = lease
                                state = state.copy(resourceGrowthFrozen = true)
                            }
                        } catch (error: Throwable) { expectedToken.editor.releaseResourceGrowth(); throw error }
                    }
                    notifyState()
                    work(checkNotNull(lease))
                }
                var delivery = expectedToken
                lease?.let { owned ->
                    // Only this admission's installation may change the callback's document identity.
                    delivery = owned.deliveryToken
                    try { owned.frozenEditors.forEach { runCatching { it.releaseResourceGrowth() } } }
                    finally {
                        synchronized(lock) {
                            if (frozenIo === owned) {
                                frozenIo = null; ++commandEpoch
                                state = state.copy(resourceGrowthFrozen = false)
                                scheduleSaveLocked()
                            }
                        }
                        runCatching { notifyState() }
                    }
                }
                main.post { if (retainedCompletion) done(result) else deliver(delivery) { done(result) } }
                result.getOrThrow()
            }
        }
    }
    fun saveProject(context: Context, directory: File, replace: Boolean, limitBytes: Long, residentBytes: Long,
        sourceIdentity: String, done: (ProjectSaveResult) -> Unit = {}): ProjectSaveOperation {
        require(sourceIdentity.length <= 4096)
        val captured = checkNotNull(token()) { "Workspace is unavailable" }
        return captured.editor.serialized {
            check(isCurrent(captured)) { "Workspace changed before project snapshot" }
            val snapshot = captured.editor.captureProjectSnapshot()
            val operation = ProjectSaveOperation(captured.copy(revision = snapshot.capturedRevision), publicationGate)
            try {
                synchronized(lock) {
                    check(!closing && frozenIo == null && matches(captured)) { "Workspace is unavailable for save" }
                    projectSaves += operation
                    worker.execute {
                        val result = try {
                            snapshot.use { saveSnapshot(context, directory, replace, limitBytes, residentBytes,
                                sourceIdentity, operation, it, false) }
                        } catch (error: Throwable) {
                            ProjectSaveResult(ProjectWriteResult(longArrayOf(1, snapshot.capturedRevision, 0, 0)), error = error.message)
                        }
                        synchronized(lock) { projectSaves -= operation }
                        operation.result.complete(result)
                        main.post { deliver(operation.token) { done(result) } }
                    }
                }
            } catch (error: Throwable) { snapshot.close(); throw error }
            operation
        }
    }
    internal fun saveFromLease(lease: WorkspaceIoLease, context: Context, directory: File, replace: Boolean,
        limitBytes: Long, residentBytes: Long, sourceIdentity: String, fault: RecoveryFault): ProjectSaveResult {
        check(Thread.currentThread() === lease.owner && synchronized(lock) { frozenIo === lease && !closing && matches(lease.token) }) {
            "Resource admission lease is unavailable or used off its worker"
        }
        require(sourceIdentity.length <= 4096 && residentBytes >= lease.memory.totalBytes) {
            "The save reservation omits frozen document/history resources"
        }
        val operation = ProjectSaveOperation(lease.token, publicationGate)
        synchronized(lock) {
            check(!closing && frozenIo === lease && matches(lease.token)) { "Workspace closed before save started" }
            projectSaves += operation
        }
        return try {
            lease.token.editor.captureFrozenProjectSnapshot().use { snapshot ->
                saveSnapshot(context, directory, replace, limitBytes, residentBytes, sourceIdentity, operation, snapshot, true, fault)
            }
        } finally { synchronized(lock) { projectSaves -= operation } }
    }
    private fun saveSnapshot(context: Context, directory: File, replace: Boolean, limitBytes: Long, residentBytes: Long,
        sourceIdentity: String, operation: ProjectSaveOperation, snapshot: NativeProjectSnapshot, frozenOwner: Boolean, fault: RecoveryFault = RecoveryFault.NONE): ProjectSaveResult {
        val written = ProjectWriteResult(longArrayOf(1, snapshot.capturedRevision, 0, 0))
        var checkpointAttempted = false
        try {
            NativeProjectWriteControl(limitBytes, residentBytes).use { control ->
                operation.attach(control)
                try { snapshot.write(context, directory, replace, control, written) }
                finally { operation.detach() }
            }
            if (!written.successful) return ProjectSaveResult(written, error = ProjectImportException(written.status).message)
            val token = operation.token
            if (!isCurrent(token) || operation.isCancelled) return ProjectSaveResult(written,
                error = "The file was saved; its workspace is no longer current")
            // A checkpoint at least as new as the captured project is required before persisting its baseline.
            val oldTarget = synchronized(lock) { if (matches(token)) target else null }
                ?: return ProjectSaveResult(written, error = "The file was saved; recovery is unavailable")
            val savingIdentity = snapshot.capturedContentIdentity
            val attempt = store.attempt(oldTarget).copy(projectBaseline = written.capturedRevision, sourceIdentity = sourceIdentity)
            checkpointAttempted = true
            val recovery = writeRecoveryCheckpoint(RecoverySaveKind.PROJECT_SAVE, attempt, token.editor, frozenOwner,
                ProjectBaseline(written.capturedRevision, savingIdentity, sourceIdentity))
            val revision = recovery.revision
            val next = recovery.target
            check(revision >= written.capturedRevision) { "The saved project is newer than its recovery checkpoint" }
            return token.editor.serialized {
                val committed = commitRecoveryPointer(RecoverySaveKind.PROJECT_SAVE, next, fault, publish = { action -> synchronized(publicationGate) {
                    check(isCurrent(token) && !operation.isCancelled) { "Saved project workspace changed before baseline commit" }
                    action()
                } }, canCommit = { isCurrent(token) && !operation.isCancelled })
                var updated = false
                val currentRevision = token.editor.currentRevision()
                val currentIdentity = token.editor.currentContentIdentity()
                synchronized(lock) {
                    // The pointer rename committed even if its directory sync is uncertain.
                    if (matches(token)) {
                        target = next
                        state = state.copy(recoverySavedRevision = revision, saveError = committed.durabilityError,
                            saveErrorNotice = committed.durabilityError?.let { UiNotice(UiNoticeKey.RECOVERY_DURABILITY) },
                            recoveryDurabilityUncertain = committed.durabilityError != null)
                    }
                    if (!closing && matches(token) && committed.durabilityError == null) {
                        updated = true
                        state = state.copy(projectBaseline = written.capturedRevision, sourceIdentity = sourceIdentity,
                            currentRevision = currentRevision, currentContentIdentity = currentIdentity,
                            projectContentBaseline = savingIdentity, saveError = null, saveErrorNotice = null,
                            recoveryDurabilityUncertain = false)
                    }
                }
                notifyState()
                ProjectSaveResult(written, updated, committed.durabilityError != null,
                    committed.durabilityError ?: if (!updated) "The project was saved after its workspace closed" else null,
                    recoveryWrite = this.snapshot().lastCheckpointWrite)
            }
        } catch (error: Throwable) {
            // Never erase native committed/durability fields when later pointer or Java work fails.
            return ProjectSaveResult(written, error = error.message ?: "Project save failed",
                recoveryWrite = if (checkpointAttempted) this.snapshot().lastCheckpointWrite else null)
        }
    }
    internal fun installImageFromLease(lease: WorkspaceIoLease, sourceIdentity: String,
        disposition: DirtyDisposition, candidate: () -> NativeEditor): InstallResult {
        check(Thread.currentThread() === lease.owner && synchronized(lock) { frozenIo === lease && !closing && matches(lease.token) }) {
            "Image candidate admission lease is unavailable"
        }
        require(ImageDocumentSource.decode(sourceIdentity) != null) { "Invalid image document source" }
        val operation = InstallOperation(lease.token, publicationGate)
        synchronized(publicationGate) { synchronized(lock) { install?.cancel(); install = operation } }
        installCandidate(operation, sourceIdentity, disposition, RecoveryFault.NONE, null, true, candidate)
        return operation.result.get()
    }
    internal fun installFromLease(lease: WorkspaceIoLease, context: Context, staging: File,
        limitBytes: Long, residentBytes: Long, sourceIdentity: String, disposition: DirtyDisposition,
        newDocument: Boolean = false): InstallResult {
        check(Thread.currentThread() === lease.owner && synchronized(lock) { frozenIo === lease && !closing && matches(lease.token) }) {
            "Resource admission lease is unavailable or used off its worker"
        }
        require(sourceIdentity.length <= 4096) { "The project source identity exceeds its limit" }
        require(residentBytes >= lease.memory.totalBytes) { "The import reservation omits frozen document/history resources" }
        val operation = InstallOperation(lease.token, publicationGate)
        synchronized(publicationGate) { synchronized(lock) { install?.cancel(); install = operation } }
        installCandidate(operation, sourceIdentity, disposition, RecoveryFault.NONE, null, newDocument) {
            NativeEditor.loadProjectCandidate(context, staging, limitBytes, residentBytes)
        }
        return operation.result.get()
    }
    fun installProjectCandidate(context: Context, staging: File, limitBytes: Long, residentBytes: Long,
        sourceIdentity: String, disposition: DirtyDisposition = DirtyDisposition.REJECT): InstallOperation =
        installCandidate(sourceIdentity, disposition) {
            NativeEditor.loadProjectCandidate(context, staging, limitBytes, residentBytes)
        }

    /** Cancellation is checked between phases; it does not interrupt a native decoder already running. */
    internal fun installCandidate(sourceIdentity: String, disposition: DirtyDisposition = DirtyDisposition.REJECT,
        fault: RecoveryFault = RecoveryFault.NONE, probe: PublicationProbe? = null,
        candidate: () -> NativeEditor): InstallOperation {
        require(sourceIdentity.length <= 4096)
        val captured = checkNotNull(token()) { "Workspace is unavailable" }
        val operation = InstallOperation(captured, publicationGate)
        synchronized(publicationGate) { synchronized(lock) {
            check(!closing && matches(captured)) { "Workspace changed before installation" }
            install?.cancel(); install = operation
            worker.execute { installCandidate(operation, sourceIdentity, disposition, fault, probe, load = candidate) }
        } }
        return operation
    }
    private fun installCandidate(operation: InstallOperation, source: String, disposition: DirtyDisposition,
        fault: RecoveryFault, probe: PublicationProbe?, newDocument: Boolean = false, load: () -> NativeEditor) {
        val token = operation.token
        var candidate: NativeEditor? = null
        var published = false
        var failure: String? = null
        var failureNotice: UiNotice? = null
        var expectedRevision = token.revision
        fun valid() = !operation.isCancelled && synchronized(lock) { !closing && matches(token) && install === operation }
        try {
            check(valid()) { "Workspace installation cancelled" }
            token.editor.serialized {
                uiCheck(!token.editor.hasPendingEdits, UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
                check(valid() && token.editor.currentRevision() == token.revision) { "Workspace changed during installation" }
                val identity = token.editor.currentContentIdentity()
                check(disposition == DirtyDisposition.DISCARD || snapshot().projectContentBaseline?.let { it == identity } == true) {
                    "Unsaved project changes require an explicit discard choice"
                }
                token.editor.endStroke(true)
                // Native cancellation advances its display revision when a preview had changed.
                expectedRevision = token.editor.currentRevision()
            }
            if (newDocument) {
                // Keep the latest outgoing document, including edits that had not reached autosave yet.
                // This unpointed attempt preserves the latest outgoing state without changing the
                // active pointer or saved revision when decoding/installing the new document fails.
                val oldTarget = synchronized(lock) {
                    check(frozenIo?.token == token && matches(token)) { "New canvas requires its resource admission lease" }
                    checkNotNull(target) { "Current document recovery is unavailable" }
                }
                val outgoing = store.attempt(oldTarget).copy(projectBaseline = snapshot().projectBaseline,
                    sourceIdentity = snapshot().sourceIdentity)
                val retainedRevision = writeRecoveryCheckpoint(RecoverySaveKind.OUTGOING_NEW, outgoing, token.editor, true)
                check(valid() && retainedRevision.revision == expectedRevision) { "Current document changed before recovery preservation" }
            }
            candidate = load()
            check(valid()) { "Workspace installation cancelled" }
            val next = checkNotNull(candidate)
            val baseline = next.currentRevision()
            val nextBaseline = if (newDocument) null else baseline
            val nextSource = if (newDocument && source == "new-canvas") null else source
            val identity = next.currentContentIdentity()
            val nextContentBaseline = if (newDocument) null else identity
            val initialTarget = store.target(token.generation + 1, next, nextBaseline, nextSource)
            val nextTarget = writeRecoveryCheckpoint(RecoverySaveKind.CANDIDATE_INSTALL, initialTarget, next,
                baseline = ProjectBaseline(nextBaseline, nextContentBaseline, nextSource)).target
            synchronized(lock) { frozenIo }?.let { admission ->
                next.freezeResourceGrowth()
                admission.frozenEditors += next
            }
            check(valid()) { "Workspace installation cancelled" }
            var result: PointerCommit? = null
            token.editor.serialized {
                uiCheck(!token.editor.hasPendingEdits, UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
                check(valid() && token.editor.currentRevision() == expectedRevision) { "Workspace changed during installation" }
                synchronized(lock) { installing = true; pending?.cancel(false); pending = null }
                try {
                    token.editor.endStroke(true)
                    result = commitRecoveryPointer(RecoverySaveKind.CANDIDATE_INSTALL, nextTarget, fault,
                        { action -> operation.publish(action) }, probe) { valid() }
                    // Rename has committed: even an fsync failure or a concurrent close must adopt the candidate.
                    synchronized(lock) {
                        target = nextTarget
                        deferredAutosave = null
                        state = WorkspaceState(editor = next, loading = false, recovered = false,
                            recoverySavedRevision = baseline, currentRevision = baseline, generation = nextTarget.generation,
                            projectBaseline = nextBaseline, sourceIdentity = nextSource,
                            currentContentIdentity = identity, projectContentBaseline = nextContentBaseline,
                            saveError = result!!.durabilityError,
                            saveErrorNotice = result!!.durabilityError?.let { UiNotice(UiNoticeKey.RECOVERY_DURABILITY) },
                            recoveryDurabilityUncertain = result!!.durabilityError != null,
                            lastCheckpointWrite = state.lastCheckpointWrite, lastCheckpointSnapshot = state.lastCheckpointSnapshot)
                        state = state.copy(resourceGrowthFrozen = frozenIo != null)
                        frozenIo?.deliveryToken = WorkspaceToken(nextTarget.generation, next, baseline, ++operationId, commandEpoch)
                        bind(next, nextTarget.generation)
                        token.editor.committed = null
                        published = true
                    }
                } finally { synchronized(lock) { installing = false } }
            }
            runCatching { token.editor.close() }
            candidate = null
            notifyState()
            operation.result.complete(InstallResult(true, nextTarget.generation, result!!.durabilityError != null,
                result!!.durabilityError))
        } catch (error: Throwable) {
            failure = error.message ?: "Project installation failed"
            failureNotice = uiFailure(error, if (ImageDocumentSource.decode(source) != null) UiNoticeKey.IMAGE_IMPORT_FAILED
                else if (newDocument) UiNoticeKey.NEW_CANVAS_FAILED else UiNoticeKey.PROJECT_OPEN_FAILED)
        } finally {
            if (!published) {
                runCatching { candidate?.close() }
                // Native writing may rename before reporting a directory-sync failure.
                // Retain every attempt; no recovery checkpoint cleanup is authorized here.
                synchronized(lock) {
                    if (!closing && matches(token)) scheduleSaveLocked()
                }
                operation.result.complete(InstallResult(false, reason = failure ?: "Project installation cancelled",
                    errorNotice = failureNotice ?: UiNotice(UiNoticeKey.OPERATION_CANCELLED)))
            }
        }
    }
    fun flushAsync(): Future<Long> {
        val captured: WorkspaceToken
        val result: CompletableFuture<Long>
        synchronized(lock) {
            val editor = state.editor
            if (closing || editor == null) return CompletableFuture.completedFuture(state.savedRevision)
            // Identity is sufficient here; the worker captures the authoritative content after its barrier.
            captured = WorkspaceToken(state.generation, editor, state.currentRevision, ++operationId, commandEpoch)
            result = CompletableFuture()
            pending?.cancel(false); pending = null
            try {
                lifecycleWorker.execute { admitFlush(captured, result) }
            } catch (failure: java.util.concurrent.RejectedExecutionException) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }
    private fun admitFlush(captured: WorkspaceToken, result: CompletableFuture<Long>) {
        if (result.isCancelled) return
        try {
            val commands = captured.editor.serialized {
                val valid = synchronized(lock) { !closing && matches(captured) &&
                    commandEpoch == captured.commandEpoch && frozenIo == null }
                if (!valid) return@serialized null
                captured.editor.cancelReservedStrokes()
                captured.editor.commandDispatcher.barrier()
            }
            if (commands == null) {
                result.complete(synchronized(lock) { if (closing) state.savedRevision else -1L })
                return
            }
            // Transform acknowledgment may require main. Never wait for it on either workspace worker.
            commands.whenComplete { _, error ->
                if (error != null) result.completeExceptionally(error)
                else if (!result.isCancelled) synchronized(lock) {
                    if (closing) result.complete(state.savedRevision)
                    else try {
                        worker.execute {
                            if (!result.isCancelled) try {
                                val revision = if (!isCurrent(captured)) -1L else {
                                    captured.editor.endStroke(true)
                                    saveCurrent(captured, RecoverySaveKind.FLUSH)
                                }
                                result.complete(revision)
                            } catch (failure: Throwable) { result.completeExceptionally(failure) }
                        }
                    } catch (failure: java.util.concurrent.RejectedExecutionException) {
                        result.completeExceptionally(failure)
                    }
                }
            }
        } catch (failure: Throwable) { result.completeExceptionally(failure) }
    }
    fun closeAsync(): Future<*> {
        val job = synchronized(publicationGate) { closeLocked() }
        synchronized(lock) {
            if (!closePreparationSubmitted) {
                closePreparationSubmitted = true
                try {
                    lifecycleWorker.execute {
                        // Closing already fences new GL leases; accepted commands still drain.
                        val failure = runCatching { synchronized(lock) { state.editor }?.prepareCommandClose() }.exceptionOrNull()
                        closePreparation.complete(failure)
                    }
                } catch (failure: java.util.concurrent.RejectedExecutionException) {
                    closePreparation.complete(failure)
                }
            }
        }
        return job
    }
    private fun closeLocked(): Future<*> = synchronized(lock) {
        closeJob?.let { return@synchronized it }
        closing = true; changed = null; install?.cancel(); projectSaves.forEach { it.cancel() }
        pending?.cancel(false); pending = null
        deferredAutosave = null
        // Cancelling a lifecycle Future must never interrupt the drain or skip its final checkpoint.
        val result = object : CompletableFuture<Unit>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        }
        worker.execute {
            val current = synchronized(lock) { state }
            val editor = current.editor
            var failure: Throwable? = null
            try {
                failure = closePreparation.get()
                if (editor != null) {
                    editor.commandDispatcher.barrier().get()
                    editor.endStroke(true)
                    saveCurrent(WorkspaceToken(current.generation, editor, current.currentRevision, ++operationId), RecoverySaveKind.CLOSE)
                }
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            } finally {
                try { editor?.close() }
                catch (error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
                finally {
                    synchronized(lock) { state = state.copy(editor = null) }
                    lifecycleWorker.shutdown()
                    worker.shutdown()
                }
            }
            if (failure != null) result.completeExceptionally(checkNotNull(failure)) else result.complete(Unit)
        }
        result.also { closeJob = it }
    }
}

internal const val DEBUG_WORKSPACE_TOKEN_EXTRA = "com.pojia.compositor.mobile.DEBUG_WORKSPACE_TOKEN"

/** A debug Activity can own a separate recovery store without opening the user's workspace. */
internal fun validatedDebugWorkspaceToken(token: String?): String? =
    if (BuildConfig.DEBUG && token != null && token.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}"))) token else null

private fun activityWorkspaceDirectory(application: Application, savedState: SavedStateHandle): File {
    val token = if (BuildConfig.DEBUG)
        validatedDebugWorkspaceToken(savedState.get<Any>(DEBUG_WORKSPACE_TOKEN_EXTRA) as? String) else null
    return if (token == null) File(application.filesDir, "workspace")
        else File(application.filesDir, "debug-workspaces/$token")
}

/** Configuration changes retain the workspace generation, editor and single recovery writer. */
internal class WorkspaceViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    var state by mutableStateOf(WorkspaceState())
        private set
    @Volatile private var projectOwner: ProjectFlowController? = null
    @Volatile private var imageOwner: ImageRequestOwner? = null
    val workspace: AndroidWorkspace = AndroidWorkspace(activityWorkspaceDirectory(application, savedState),
        RecoveryAdmission.forContext(application), checkpointAdmission = CheckpointAdmission.forContext(application)) {
        state = it
        projectOwner?.workspaceChanged(it)
        imageOwner?.workspaceChanged(it)
    }
    // Publish the owner only after construction; workspace notifications are posted to the main looper.
    val projects: ProjectFlowController = ProjectFlowController(application.applicationContext, workspace).also { projectOwner = it }
    val images: ImageRequestOwner = ImageRequestOwner(application.applicationContext, workspace, savedState).also { imageOwner = it }
    override fun onCleared() { projectOwner = null; imageOwner = null; projects.close(); images.close(); workspace.closeAsync() }
}
