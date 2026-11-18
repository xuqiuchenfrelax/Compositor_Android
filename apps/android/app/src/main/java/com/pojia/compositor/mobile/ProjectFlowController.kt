package com.pojia.compositor.mobile

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Provider copying can be cancelled; native candidate loading has phase-only cancellation. */
internal class ProjectOpenCancellation {
    val transport = ProjectTransportCancellation()
    private val gate = Any()
    @Volatile var loadingCandidate = false; private set
    fun cancel(): Boolean = synchronized(gate) {
        if (loadingCandidate) false else { transport.cancel(); true }
    }
    fun <T> loadCandidate(action: () -> T): T {
        synchronized(gate) { transport.check(); loadingCandidate = true }
        return action()
    }
}

internal enum class ProjectFlowPhase { IDLE, CHOOSING, LISTING, QUEUED, COPYING, LOADING, SAVING, EXPORTING, PUBLISHING, CANCELLING }
internal data class ProjectOpenRequest(val token: WorkspaceToken, val uri: Uri? = null, val file: File? = null, val imageDocument: Boolean = false)
internal data class ProjectCopyRequest(val token: WorkspaceToken, val name: String, val openAfter: ProjectOpenRequest? = null, val newAfter: NewCanvasRequest? = null)
internal data class NewCanvasRequest(val token: WorkspaceToken, val draft: NewCanvasDraft = NewCanvasDraft())
internal data class ProjectFlowState(val phase: ProjectFlowPhase = ProjectFlowPhase.IDLE,
    val message: String = "", val canCancel: Boolean = false,
    val confirmation: ProjectOpenRequest? = null, val savedProjects: List<File>? = null,
    val error: String? = null, val requestId: Long = 0, val exportResult: ProjectExportResult? = null,
    val exportHistory: List<ProjectExportReceipt>? = null,
    val newCanvas: NewCanvasRequest? = null, val newConfirmation: NewCanvasRequest? = null,
    val projectCopy: ProjectCopyRequest? = null,
    val errorNotice: UiNotice? = null, val messageNotice: UiNotice? = null) {
    val busy: Boolean get() = phase != ProjectFlowPhase.IDLE || confirmation != null || savedProjects != null || exportResult != null || exportHistory != null || newCanvas != null || newConfirmation != null || projectCopy != null
    val running: Boolean get() = phase in setOf(ProjectFlowPhase.QUEUED, ProjectFlowPhase.COPYING,
        ProjectFlowPhase.LOADING, ProjectFlowPhase.SAVING, ProjectFlowPhase.EXPORTING, ProjectFlowPhase.PUBLISHING, ProjectFlowPhase.CANCELLING)
}
internal class ProjectFlowHooks(
    val stage: ((Uri, ProjectTransportCancellation, (ProjectTransportProgress) -> Unit) -> StagedProject)? = null,
    val beforeCandidateLoad: (() -> Unit)? = null,
    val exportProgress: ((String, Boolean, ProjectExportCancellation) -> Unit)? = null,
    val newSaveFault: RecoveryFault = RecoveryFault.NONE,
    val copySaveFault: RecoveryFault = RecoveryFault.NONE,
)
private class ProjectFlowOperation(val id: Long, val token: WorkspaceToken,
    val cancellation: ProjectOpenCancellation = ProjectOpenCancellation(), val exportCancellation: ProjectExportCancellation? = null) {
    var cancelRequested = false
    var failureNotice = UiNoticeKey.PROJECT_FAILED
}

/** A ViewModel owns this flow. It holds application context and no Activity, canvas or composition callback. */
internal class ProjectFlowController(context: Context, private val workspace: AndroidWorkspace) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 0L
    private var picker: Pair<Long, WorkspaceToken>? = null
    private var exportPicker = false
    private var exportPickerName: String? = null
    private var imagePicker = false
    private var operation: ProjectFlowOperation? = null
    private var closed = false
    private var startupCleanupScheduled = false
    private val startupCancellation = ProjectTransportCancellation()
    @Volatile internal var startupCleanupResult: Result<ProjectStagingReclaim>? = null
        private set
    internal var testHooks: ProjectFlowHooks? = null
    var exportRecoveryNotice by mutableStateOf<String?>(null)
        private set
    var exportRecoveryNoticeUi by mutableStateOf<UiNotice?>(null)
        private set
    var state by mutableStateOf(ProjectFlowState())
        private set
    private fun onMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Project flow actions require the main thread" }
    private fun report(message: String, notice: UiNotice = UiNotice(UiNoticeKey.PROJECT_FAILED)) {
        state = state.copy(error = message, errorNotice = notice)
    }
    fun clearError() { onMain(); state = state.copy(error = null, errorNotice = null) }

    private fun prepare(editor: NativeEditor): WorkspaceToken? {
        if (closed || operation != null || picker != null) return null
        val token = workspace.token(editor) ?: return null
        // This gate can reject a retired editor without calling into its closed native handle.
        var pending = false
        if (!workspace.execute(token) {
            pending = editor.hasPendingEdits
            if (!pending) editor.endStroke(true)
        }) return null
        if (pending) { report(PENDING_EDIT_MESSAGE, UiNotice(UiNoticeKey.PENDING_EDIT)); return null }
        return workspace.token(editor)
    }
    private fun exact(token: WorkspaceToken): Boolean = token.editor.serialized {
        workspace.isCurrent(token) && token.editor.currentRevision() == token.revision
    }
    fun chooseProject(editor: NativeEditor): Boolean {
        onMain()
        if (state.busy) return false
        val token = prepare(editor) ?: return false
        val id = ++nextId
        picker = id to token
        exportPicker = false; exportPickerName = null
        imagePicker = false
        state = ProjectFlowState(ProjectFlowPhase.CHOOSING, requestId = id)
        return true
    }
    fun pickerLaunchFailed(message: String) {
        onMain(); picker = null; exportPicker = false; exportPickerName = null; imagePicker = false
        state = ProjectFlowState(error = message, requestId = state.requestId, errorNotice = UiNotice(UiNoticeKey.PICKER_UNAVAILABLE))
    }
    fun chooseImageDocument(editor: NativeEditor): Boolean {
        if (!chooseProject(editor)) return false
        imagePicker = true
        return true
    }
    fun imagePickerResult(uri: Uri?) {
        onMain()
        if (imagePicker) acceptPickerResult(uri)
    }
    fun pickerResult(uri: Uri?) {
        onMain()
        if (!imagePicker) acceptPickerResult(uri)
    }
    private fun acceptPickerResult(uri: Uri?) {
        onMain()
        if (closed) return
        if (exportPicker) return
        val pending = picker ?: return // Process-restored/unrequested and duplicate results cannot start an import.
        val opensImage = imagePicker
        picker = null; imagePicker = false
        state = ProjectFlowState(requestId = pending.first)
        if (uri == null) return
        if (!exact(pending.second)) {
            report("The document changed while choosing a project. Choose the project again.", UiNotice(UiNoticeKey.PROJECT_CHANGED))
            return
        }
        requestOpen(ProjectOpenRequest(pending.second, uri = uri, imageDocument = opensImage))
    }
    private fun requestOpen(request: ProjectOpenRequest) {
        if (workspace.snapshot().projectDirty) state = state.copy(confirmation = request)
        else startOpen(request, DirtyDisposition.REJECT)
    }
    fun cancelConfirmation() { onMain(); state = state.copy(confirmation = null) }
    fun discardAndOpen() {
        onMain()
        val request = state.confirmation ?: return
        state = state.copy(confirmation = null)
        if (!exact(request.token)) {
            report("The document changed before opening the project. Choose it again.", UiNotice(UiNoticeKey.PROJECT_CHANGED))
            return
        }
        startOpen(request, DirtyDisposition.DISCARD)
    }
    fun chooseSaveAndOpen() = saveBeforeOpen(asCopy = false)
    fun chooseSaveCopyAndOpen() = saveBeforeOpen(asCopy = true)

    private fun saveBeforeOpen(asCopy: Boolean) {
        onMain()
        val request = state.confirmation ?: return
        if (!exact(request.token)) {
            state = state.copy(confirmation = null)
            report("The document changed before opening the project. Choose it again.", UiNotice(UiNoticeKey.PROJECT_CHANGED))
            return
        }
        val source = workspace.snapshot().sourceIdentity
        if (!asCopy && source?.startsWith("local:") == true) {
            state = state.copy(confirmation = null)
            saveLocal(request.token.editor, null, request, source)
        } else {
            state = state.copy(confirmation = null, projectCopy = ProjectCopyRequest(request.token, suggestedCopyName(), request))
        }
    }
    fun listSaved(editor: NativeEditor) {
        onMain()
        if (state.busy) return
        val token = prepare(editor) ?: return
        val id = ++nextId
        state = ProjectFlowState(ProjectFlowPhase.LISTING, "Reading saved projects…", requestId = id, messageNotice = UiNotice(UiNoticeKey.READING_PROJECTS))
        workspace.runIo(token, true, { LocalProjectLibrary.projects(context) }) { result ->
            if (closed || state.requestId != id) return@runIo
            state = ProjectFlowState(requestId = id)
            if (!workspace.isCurrent(token)) return@runIo
            result.fold(onSuccess = { state = state.copy(savedProjects = it) },
                onFailure = { report(it.message ?: "Saved projects are unavailable", uiFailure(it, UiNoticeKey.PROJECT_LIST_FAILED)) })
        }
    }
    fun closeSavedList() { onMain(); state = state.copy(savedProjects = null) }
    fun openSaved(editor: NativeEditor, file: File) {
        onMain()
        if (state.savedProjects?.contains(file) != true) return
        val token = prepare(editor) ?: return
        state = state.copy(savedProjects = null)
        requestOpen(ProjectOpenRequest(token, file = file))
    }
    private fun begin(token: WorkspaceToken, message: String, notice: UiNotice, failureNotice: UiNoticeKey,
        export: ProjectExportCancellation? = null): ProjectFlowOperation {
        val active = ProjectFlowOperation(++nextId, token, exportCancellation = export)
        operation = active
        active.failureNotice = failureNotice
        state = ProjectFlowState(ProjectFlowPhase.QUEUED, message, canCancel = true, requestId = active.id, messageNotice = notice)
        return active
    }
    private fun phase(active: ProjectFlowOperation, phase: ProjectFlowPhase, message: String, canCancel: Boolean, notice: UiNotice) {
        main.post {
            if (!closed && operation === active && state.phase != ProjectFlowPhase.CANCELLING && workspace.isCurrent(active.token)) {
                if (phase == ProjectFlowPhase.COPYING && active.cancellation.loadingCandidate) return@post
                state = state.copy(phase = phase, message = message, messageNotice = notice,
                    canCancel = canCancel && !active.cancellation.loadingCandidate)
            }
        }
    }
    private fun finish(active: ProjectFlowOperation, failure: Throwable?) {
        if (closed || operation !== active) return
        operation = null
        val cancelled = active.cancelRequested || (failure as? ProjectTransportException)?.failure == ProjectTransportFailure.CANCELLED
        val visibleFailure = failure?.takeUnless { cancelled || !workspace.isCurrent(active.token) }
        state = ProjectFlowState(requestId = active.id, error = visibleFailure?.let { it.message ?: active.failureNotice.name },
            errorNotice = visibleFailure?.let { uiFailure(it, active.failureNotice) })
    }
    fun chooseNewCanvas(editor: NativeEditor): Boolean {
        onMain()
        if (state.busy) return false
        val token = prepare(editor) ?: return false
        state = ProjectFlowState(requestId = ++nextId, newCanvas = NewCanvasRequest(token, NewCanvasPreferences.load(context)))
        return true
    }
    fun updateNewCanvas(change: (NewCanvasDraft) -> NewCanvasDraft) {
        onMain()
        val request = state.newCanvas ?: return
        state = state.copy(newCanvas = request.copy(draft = change(request.draft)))
    }
    fun cancelNewCanvas() {
        onMain()
        if (state.newCanvas != null || state.newConfirmation != null)
            state = state.copy(newCanvas = null, newConfirmation = null)
    }
    fun createNewCanvas() {
        onMain()
        val request = state.newCanvas ?: return
        if (request.draft.spec() == null) return
        if (!exact(request.token)) { cancelNewCanvas(); report("The document changed. Start New canvas again.", UiNotice(UiNoticeKey.NEW_CANVAS_CHANGED)); return }
        if (workspace.snapshot().projectDirty) state = state.copy(newCanvas = null, newConfirmation = request)
        else { state = state.copy(newCanvas = null); startNewCanvas(request, DirtyDisposition.REJECT) }
    }
    fun discardAndCreateNewCanvas() {
        onMain()
        val request = state.newConfirmation ?: return
        state = state.copy(newConfirmation = null)
        startNewCanvas(request, DirtyDisposition.DISCARD)
    }
    fun hasLocalSource(): Boolean = workspace.snapshot().sourceIdentity?.startsWith("local:") == true

    fun saveAndCreateNewCanvas() = saveBeforeNewCanvas(asCopy = false)
    fun saveCopyAndCreateNewCanvas() = saveBeforeNewCanvas(asCopy = true)

    private fun saveBeforeNewCanvas(asCopy: Boolean) {
        onMain()
        val request = state.newConfirmation ?: return
        if (!exact(request.token)) {
            state = state.copy(newConfirmation = null)
            report("The document changed. Start New canvas again.", UiNotice(UiNoticeKey.NEW_CANVAS_CHANGED))
            return
        }
        if (request.draft.spec() == null) return
        val source = workspace.snapshot().sourceIdentity
        if (!asCopy && source?.startsWith("local:") == true) {
            state = state.copy(newConfirmation = null)
            saveLocal(request.token.editor, null, null, source, request)
        } else {
            state = state.copy(newConfirmation = null,
                projectCopy = ProjectCopyRequest(request.token, suggestedCopyName(), newAfter = request))
        }
    }
    private fun startNewCanvas(request: NewCanvasRequest, disposition: DirtyDisposition) {
        if (closed || !exact(request.token)) { report("The document changed. Start New canvas again.", UiNotice(UiNoticeKey.NEW_CANVAS_CHANGED)); return }
        val spec = request.draft.spec() ?: return
        val active = begin(request.token, "Waiting to create canvas…", UiNotice(UiNoticeKey.WAITING_NEW_CANVAS), UiNoticeKey.NEW_CANVAS_FAILED)
        val hooks = testHooks
        try {
            workspace.withFrozenIo(request.token, true, { lease ->
                active.cancellation.transport.check()
                val admission = ProjectAdmissionPolicy.forContext(context, lease.memory)
                // Once candidate installation starts, cancellation cannot retract a published recovery pointer.
                active.cancellation.loadCandidate {
                    phase(active, ProjectFlowPhase.LOADING, "Creating canvas…", false, UiNotice(UiNoticeKey.CREATING_CANVAS))
                    val storage = ProjectStagingStorage(NativeProjectIO.ownedPath(context, File(context.filesDir, "project-staging")))
                    storage.create().use { staging ->
                        BlankCanvasProject.write(staging.directory, spec, admission)
                        hooks?.beforeCandidateLoad?.invoke()
                        val installed = lease.installNewProjectCandidate(context, staging.directory,
                            admission.limitBytes, admission.residentBytes, disposition)
                        if (!installed.installed) throw UiNoticeException(installed.errorNotice ?: UiNotice(UiNoticeKey.NEW_CANVAS_FAILED),
                            installed.reason ?: "The new canvas could not be created")
                        installed
                    }
                }
            }) { result ->
                if (result.isSuccess) NewCanvasPreferences.save(context, spec)
                finish(active, result.exceptionOrNull())
            }
        } catch (failure: Exception) { finish(active, failure) }
    }
    private fun startImageDocument(request: ProjectOpenRequest, disposition: DirtyDisposition) {
        val active = begin(request.token, "Opening image…", UiNotice(UiNoticeKey.OPENING_IMAGE), UiNoticeKey.IMAGE_IMPORT_FAILED)
        try {
            workspace.withFrozenIo(request.token, true, { lease ->
                active.cancellation.transport.check()
                val uri = checkNotNull(request.uri)
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: if (uri.scheme == "file") uri.path?.let { File(it).name } else null
                val source = ImageDocumentSource.fromName(name)
                active.cancellation.loadCandidate {
                    phase(active, ProjectFlowPhase.LOADING, "Opening image…", false, UiNotice(UiNoticeKey.OPENING_IMAGE))
                    val installed = lease.installImageCandidate(source.identity(), disposition) {
                        testHooks?.beforeCandidateLoad?.invoke()
                        val metadataBytes = Math.addExact(4L * 1024 * 1024, Math.multiplyExact(lease.memory.stateBytes, 3))
                        NativeImageIO.decode(context, uri, residentBytes = lease.memory.totalBytes,
                            nativeMetadataBytes = metadataBytes).use { image ->
                            val candidate = NativeEditor.rectangular(image.width, image.height)
                            try {
                                image.importInto(candidate, fit = false)
                                candidate
                            } catch (failure: Throwable) { candidate.close(); throw failure }
                        }
                    }
                    if (!installed.installed) throw UiNoticeException(installed.errorNotice ?: UiNotice(UiNoticeKey.IMAGE_IMPORT_FAILED),
                        installed.reason ?: "The image document could not be opened")
                    installed
                }
            }) { result -> finish(active, result.exceptionOrNull()) }
        } catch (failure: Exception) { finish(active, failure) }
    }
    private fun startOpen(request: ProjectOpenRequest, disposition: DirtyDisposition) {
        if (request.imageDocument) { startImageDocument(request, disposition); return }
        val active = begin(request.token, if (request.uri != null) "Waiting to copy project…" else "Waiting to open saved project…",
            UiNotice(if (request.uri != null) UiNoticeKey.WAITING_PROJECT_COPY else UiNoticeKey.WAITING_PROJECT_OPEN), UiNoticeKey.PROJECT_OPEN_FAILED)
        val hooks = testHooks
        try {
            workspace.withFrozenIo(request.token, true, { lease ->
                active.cancellation.transport.check()
                val admission = ProjectAdmissionPolicy.forContext(context, lease.memory)
                fun load(directory: File, source: String, displayName: String): InstallResult = active.cancellation.loadCandidate {
                    phase(active, ProjectFlowPhase.LOADING, "Opening project…", false, UiNotice(UiNoticeKey.OPENING_PROJECT))
                    hooks?.beforeCandidateLoad?.invoke()
                    val result = lease.installProjectCandidate(context, directory, admission.limitBytes,
                        admission.residentBytes, source, disposition)
                    if (!result.installed) throw UiNoticeException(result.errorNotice ?: UiNotice(UiNoticeKey.PROJECT_OPEN_FAILED),
                        result.reason ?: "The project could not be opened")
                    ProjectNamePreferences.remember(context, source, displayName)
                    result
                }
                if (request.file != null) load(request.file, "local:${request.file.name}", request.file.name)
                else {
                    phase(active, ProjectFlowPhase.COPYING, "Copying project…", true, UiNotice(UiNoticeKey.COPYING_PROJECT))
                    var lastProgress = 0L
                    val progress: (ProjectTransportProgress) -> Unit = { value ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 100 || value.bytesCopied == value.declaredBytes) {
                            lastProgress = now
                            phase(active, ProjectFlowPhase.COPYING,
                                "Copying project: ${value.assetsCopied} / ${value.assetCount} assets", true,
                                UiNotice(UiNoticeKey.COPYING_PROJECT_PROGRESS, value.assetsCopied, value.assetCount))
                        }
                    }
                    val uri = checkNotNull(request.uri)
                    val staged = hooks?.stage?.invoke(uri, active.cancellation.transport, progress)
                        ?: ProjectTransport.forContext(context).stage(uri, active.cancellation.transport, progress)
                    staged.use { load(it.directory, it.sourceIdentity, it.displayName) }
                }
            }) { result -> finish(active, result.exceptionOrNull()) }
        } catch (failure: Exception) { finish(active, failure) }
    }
    private fun suggestedCopyName(): String {
        val source = workspace.snapshot().sourceIdentity
        return ProjectCopyNames.suggested(ProjectNamePreferences.projectName(context, source),
            ImageDocumentSource.decode(source)?.name)
    }
    fun chooseSaveCopy(editor: NativeEditor) {
        onMain()
        if (state.busy) return
        val token = prepare(editor) ?: return
        state = ProjectFlowState(requestId = ++nextId, projectCopy = ProjectCopyRequest(token, suggestedCopyName()))
    }
    fun updateCopyName(name: String) {
        onMain()
        state.projectCopy?.let { state = state.copy(projectCopy = it.copy(name = name.take(512))) }
    }
    fun cancelSaveCopy() {
        onMain()
        val request = state.projectCopy ?: return
        state = state.copy(projectCopy = null, confirmation = request.openAfter, newConfirmation = request.newAfter)
    }
    fun confirmSaveCopy() {
        onMain()
        val request = state.projectCopy ?: return
        val name = ProjectCopyNames.filename(request.name) ?: return
        state = state.copy(projectCopy = null)
        if (!exact(request.token)) {
            report("The document changed. Save the project copy again.", UiNotice(UiNoticeKey.PROJECT_CHANGED)); return
        }
        saveCopy(request.token.editor, name, request.openAfter, request.newAfter)
    }
    fun save(editor: NativeEditor) {
        onMain()
        if (state.busy) return
        val source = workspace.snapshot().sourceIdentity
        if (source?.startsWith("local:") != true) {
            chooseSaveCopy(editor)
            return
        }
        saveLocal(editor, null, null, source)
    }
    fun saveCopy(editor: NativeEditor, requestedName: String? = null, openAfter: ProjectOpenRequest? = null, newAfter: NewCanvasRequest? = null) =
        saveLocal(editor, requestedName, openAfter, null, newAfter)

    private fun saveLocal(editor: NativeEditor, requestedName: String?, openAfter: ProjectOpenRequest?, replaceSource: String?, newAfter: NewCanvasRequest? = null) {
        onMain()
        if (state.busy) return
        val token = prepare(editor) ?: return
        if (editor.state().layers.any { it.name.isBlank() }) {
            report("Give every layer a name before saving the project", UiNotice(UiNoticeKey.LAYER_NAME_REQUIRED)); return
        }
        val copyName = requestedName ?: suggestedCopyName()
        val hooks = testHooks
        val active = begin(token, "Waiting to save project…", UiNotice(UiNoticeKey.WAITING_PROJECT_SAVE), UiNoticeKey.PROJECT_SAVE_FAILED)
        try {
            workspace.withFrozenIo(token, true, { lease ->
                active.cancellation.transport.check()
                val admission = ProjectAdmissionPolicy.forContext(context, lease.memory)
                active.cancellation.loadCandidate {
                    phase(active, ProjectFlowPhase.SAVING, "Saving project on this device…", false, UiNotice(UiNoticeKey.SAVING_PROJECT))
                    val destination = if (replaceSource != null) LocalProjectLibrary.existingTarget(context, replaceSource)
                        else LocalProjectLibrary.newTarget(context, copyName)
                    lease.saveProject(context, destination, replaceSource != null, admission.limitBytes, admission.residentBytes,
                        "local:${destination.name}", (if (newAfter != null) hooks?.newSaveFault else hooks?.copySaveFault) ?: RecoveryFault.NONE)
                }
            }) { result ->
                val failure = result.exceptionOrNull() ?: result.getOrNull()?.let { saved ->
                    if (saved.committed && saved.baselineUpdated && saved.error == null) null else UiNoticeException(saved.failureNotice(), saved.error ?: if (saved.committed)
                        "The project was written, but its save status could not be confirmed" else "The project could not be saved")
                }
                val continueReplacement = failure == null && !closed && operation === active && !active.cancelRequested &&
                    (openAfter != null || newAfter != null)
                finish(active, failure)
                if (continueReplacement) {
                    if (!exact(token)) {
                        report("The document changed before replacing it. Start again.",
                            UiNotice(if (newAfter != null) UiNoticeKey.NEW_CANVAS_CHANGED else UiNoticeKey.PROJECT_CHANGED))
                    } else {
                        val current = workspace.token(editor)
                        if (current != null) {
                            if (newAfter != null) startNewCanvas(newAfter.copy(token = current), DirtyDisposition.REJECT)
                            else startOpen(checkNotNull(openAfter).copy(token = current), DirtyDisposition.REJECT)
                        }
                    }
                }
            }
        } catch (failure: Exception) { finish(active, failure) }
    }
    fun chooseExport(editor: NativeEditor): Boolean {
        onMain()
        if (state.busy) return false
        val token = prepare(editor) ?: return false
        if (editor.state().layers.any { it.name.isBlank() }) { report("Give every layer a name before exporting a project copy", UiNotice(UiNoticeKey.LAYER_NAME_REQUIRED)); return false }
        val id = ++nextId; picker = id to token; exportPicker = true; imagePicker = false
        exportPickerName = suggestedCopyName()
        state = ProjectFlowState(ProjectFlowPhase.CHOOSING, requestId = id)
        return true
    }
    fun exportPickerResult(uri: Uri?) {
        onMain()
        if (closed || !exportPicker) return
        val pending = picker ?: return
        val copyName = exportPickerName
        picker = null; exportPicker = false; exportPickerName = null; state = ProjectFlowState(requestId = pending.first)
        if (uri == null) return
        if (!exact(pending.second)) { report("The document changed while choosing an export folder. Choose it again.", UiNotice(UiNoticeKey.EXPORT_CHANGED)); return }
        try {
            context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (failure: Exception) {
            report("This folder did not grant persistent read and write access. Choose another export folder.", UiNotice(UiNoticeKey.EXPORT_PERMISSION)); return
        }
        val cancellation = ProjectExportCancellation()
        val active = begin(pending.second, "Preparing project export…", UiNotice(UiNoticeKey.PREPARING_EXPORT), UiNoticeKey.PROJECT_EXPORT_FAILED, cancellation)
        try {
            workspace.withFrozenIo(pending.second, true, { lease ->
                cancellation.transport.check()
                val admission = ProjectAdmissionPolicy.forContext(context, lease.memory)
                val storage = ProjectStagingStorage(NativeProjectIO.ownedPath(context, File(context.filesDir, "project-staging")))
                storage.create().use { staging ->
                    val packageDirectory = File(staging.directory, "export.comp")
                    lease.token.editor.captureFrozenProjectSnapshot().use { snapshot ->
                        NativeProjectWriteControl(admission.limitBytes, admission.residentBytes).use { control ->
                            cancellation.attach(control)
                            val written = try { snapshot.write(context, packageDirectory, false, control) }
                                finally { cancellation.detach() }
                            check(written.successful) { ProjectImportException(written.status).message ?: "Private export staging failed" }
                        }
                        cancellation.transport.check()
                        ProjectExporter.forContext(context).publish(packageDirectory, uri, snapshot.capturedRevision, cancellation, suggestedName = copyName) { message, canCancel ->
                            main.post {
                                if (!closed && operation === active && state.phase != ProjectFlowPhase.CANCELLING) state = state.copy(
                                    phase = if (canCancel) ProjectFlowPhase.EXPORTING else ProjectFlowPhase.PUBLISHING,
                                    message = message, canCancel = canCancel,
                                    messageNotice = UiNotice(if (canCancel) UiNoticeKey.EXPORTING_PROJECT else UiNoticeKey.PUBLISHING_PROJECT))
                            }
                            testHooks?.exportProgress?.invoke(message, canCancel, cancellation)
                        }
                    }
                }
            }) { result ->
                if (!closed && operation === active) {
                    operation = null
                    val outcome = result.getOrElse { error -> ProjectExportResult(active.token.revision,
                        ProjectExportCommit.NOT_COMMITTED, ProjectExportCleanup.NOT_NEEDED,
                        cancelled = active.cancelRequested, error = error.message) }
                    // A committed file result survives stale workspace delivery. No baseline or source is patched.
                    state = ProjectFlowState(requestId = active.id, exportResult = outcome)
                }
            }
        } catch (failure: Exception) { operation = null; state = ProjectFlowState(error = failure.message, errorNotice = uiFailure(failure, UiNoticeKey.PROJECT_EXPORT_FAILED)) }
    }
    fun acknowledgeExport() {
        onMain()
        val result = state.exportResult ?: return
        state = state.copy(exportResult = null)
        if (result.commit == ProjectExportCommit.COMMITTED_VERIFIED) result.receiptId?.let { id ->
            workspace.runMaintenance({ ProjectExportReceipts(context).remove(id) }) {}
        }
    }
    fun listExportHistory() {
        onMain()
        if (state.busy || closed) return
        val id = ++nextId
        state = ProjectFlowState(ProjectFlowPhase.LISTING, "Reading export records…", requestId = id, messageNotice = UiNotice(UiNoticeKey.READING_EXPORTS))
        workspace.runMaintenance({ ProjectExportReceipts(context).readAll() }) { result ->
            if (!closed && state.requestId == id) state = ProjectFlowState(requestId = id,
                exportHistory = result.getOrNull(), error = result.exceptionOrNull()?.message,
                errorNotice = result.exceptionOrNull()?.let { uiFailure(it, UiNoticeKey.EXPORT_RECORD_FAILED) })
        }
    }
    fun closeExportHistory() { onMain(); state = state.copy(exportHistory = null) }
    fun forgetExportRecord(id: String) {
        onMain()
        if (state.exportHistory?.none { it.id == id } != false || closed) return
        val request = state.requestId
        workspace.runMaintenance({ ProjectExportReceipts(context).remove(id); ProjectExportReceipts(context).readAll() }) { result ->
            if (!closed && state.requestId == request && state.exportHistory != null) result.fold(
                onSuccess = { state = state.copy(exportHistory = it) }, onFailure = { report(it.message ?: "Export record could not be removed", uiFailure(it, UiNoticeKey.EXPORT_RECORD_FAILED)) })
        }
    }
    fun dismissExportRecoveryNotice() { onMain(); exportRecoveryNotice = null; exportRecoveryNoticeUi = null }
    fun cancel(): Boolean {
        onMain()
        val active = operation ?: return false
        if (!(active.exportCancellation?.cancel() ?: active.cancellation.cancel())) {
            state = state.copy(canCancel = false)
            return false
        }
        active.cancelRequested = true
        state = state.copy(phase = ProjectFlowPhase.CANCELLING, message = "Cancelling…", canCancel = false, messageNotice = UiNotice(UiNoticeKey.CANCELLING))
        return true
    }
    fun workspaceChanged(value: WorkspaceState) {
        onMain()
        if (!closed && !startupCleanupScheduled && !value.loading) {
            startupCleanupScheduled = true
            // This bounded sweep shares the workspace worker without freezing or changing the document.
            workspace.runMaintenance({
                val reclaimed = ProjectTransport.forContext(context).reclaimOrphans(startupCancellation)
                reclaimed to ProjectExportReceipts(context).recover(DocumentsProjectExportTarget(context.contentResolver))
            }) { result -> if (!closed) {
                startupCleanupResult = result.map { it.first }
                result.getOrNull()?.second?.let { recovery ->
                    if (recovery.removed + recovery.retained + recovery.uncertain > 0 || recovery.limited) {
                        exportRecoveryNoticeUi = UiNotice(if (recovery.limited) UiNoticeKey.EXPORT_RECOVERY_LIMITED else UiNoticeKey.EXPORT_RECOVERY,
                            recovery.removed, recovery.retained + recovery.uncertain)
                        exportRecoveryNotice = "Export recovery: ${recovery.removed} interrupted copies cleaned up; ${recovery.retained + recovery.uncertain} records need review." +
                            if (recovery.limited) " Some records could not be read safely. External files were kept." else " Open Export records to review retained copies."
                    }
                }
            } }
        }
        state.projectCopy?.let { pending ->
            if (value.editor !== pending.token.editor || value.generation != pending.token.generation)
                state = state.copy(projectCopy = null, error = "The document changed. Save the project copy again.",
                    errorNotice = UiNotice(UiNoticeKey.PROJECT_CHANGED))
        }
        (state.newCanvas ?: state.newConfirmation)?.let { pending ->
            if (value.editor !== pending.token.editor || value.generation != pending.token.generation)
                state = state.copy(newCanvas = null, newConfirmation = null, error = "The document changed. Start New canvas again.", errorNotice = UiNotice(UiNoticeKey.NEW_CANVAS_CHANGED))
        }
        state.confirmation?.let { pending ->
            if (value.editor !== pending.token.editor || value.generation != pending.token.generation)
                state = state.copy(confirmation = null, error = "The document changed. Choose the project again.", errorNotice = UiNotice(UiNoticeKey.PROJECT_CHANGED))
        }
    }
    override fun close() {
        onMain()
        closed = true; picker = null; exportPickerName = null
        startupCancellation.cancel()
        operation?.cancellation?.cancel()
        operation?.exportCancellation?.cancel()
        operation = null
        state = ProjectFlowState()
    }
}
