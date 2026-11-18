package com.pojia.compositor.mobile

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class ImageRequestPurpose { IMPORT, IMPORT_FILE, EXPORT_PNG, EXPORT_JPEG, EXPORT_TIFF, EXPORT_TIFF_FLATTENED;
    val isImport: Boolean get() = this == IMPORT || this == IMPORT_FILE
}
internal data class JpegExportOptions(val quality: Int = 85, val backgroundRgb: Int = 0xffffff) {
    init {
        require(quality in 0..100 && backgroundRgb in 0..0xffffff)
    }
}
internal enum class ImageRequestPhase { IDLE, CHOOSING, QUEUED, DECODING, ENCODING, COMMITTING, WRITING, CANCELLING }
internal data class ImagePickerRequest(val id: Long, val purpose: ImageRequestPurpose, val session: String,
    val generation: Long, val revision: Long, val jpegOptions: JpegExportOptions = JpegExportOptions(),
    val outputName: String? = null, val exportSize: ImageExportSize? = null) {
    val key: String get() = "compositor-image-$session-$id-${purpose.name}"
}
internal data class ImageRequestState(val request: ImagePickerRequest? = null,
    val phase: ImageRequestPhase = ImageRequestPhase.IDLE, val error: String? = null,
    val message: String = "", val outputUri: Uri? = null, val completedId: Long = 0, val selectedUri: Uri? = null,
    val errorNotice: UiNotice? = null, val messageNotice: UiNotice? = null) {
    val busy: Boolean get() = phase != ImageRequestPhase.IDLE
    val canCancel: Boolean get() = phase in setOf(ImageRequestPhase.CHOOSING, ImageRequestPhase.QUEUED, ImageRequestPhase.DECODING, ImageRequestPhase.ENCODING)
}
internal class ImageRequestHooks(val beforeDecode: (() -> Unit)? = null, val beforeCommit: (() -> Unit)? = null,
    val beforeWrite: (() -> Unit)? = null)
private class ImageRequestOperation(val request: ImagePickerRequest, val token: WorkspaceToken) {
    var phase = ImageRequestPhase.CHOOSING
    var rejection: String? = null
    var rejectionNotice: UiNotice? = null
    var userCancelled = false
    var uri: Uri? = null
}
private class ImagePickerSlot(val request: ImagePickerRequest, var rejection: String? = null, var rejectionNotice: UiNotice? = null)

/** Owns URI results and IO independently of Activities, Compose keys and CanvasView instances. */
internal class ImageRequestOwner(private val context: Context, private val workspace: AndroidWorkspace,
    private val saved: SavedStateHandle = SavedStateHandle()) {
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private val session = UUID.randomUUID().toString()
    private val slots = linkedMapOf<Long, ImagePickerSlot>()
    private var operation: ImageRequestOperation? = null
    private var nextId = 0L
    private var closed = false
    private var binding: Any? = null
    private var launch: ((ImagePickerRequest) -> Unit)? = null
    var state by mutableStateOf(ImageRequestState())
        private set
    var testHooks: ImageRequestHooks? = null
    private val exportHistory = ImageExportHistory(ImageExportPreferences.load(context))

    private var exportChoiceToken by mutableStateOf<WorkspaceToken?>(null)
    val exportChoiceGeneration: Long? get() = exportChoiceToken?.generation
    val exportChoiceId: Long? get() = exportChoiceToken?.operationId

    fun chooseExport(editor: NativeEditor): Boolean {
        checkMain()
        if (closed || state.busy || exportChoiceGeneration != null) return false
        val token = workspace.token(editor) ?: return false
        if (workspace.snapshot().resourceGrowthFrozen || editor.hasPendingEdits || editor.hasActiveStrokes) return false
        exportChoiceToken = token
        return true
    }

    fun dismissExportChoice() {
        checkMain()
        exportChoiceToken = null
    }

    fun launchExportChoice(choiceId: Long, editor: NativeEditor, format: ImageExportFormat, size: ImageExportSize,
        jpegOptions: JpegExportOptions = JpegExportOptions()): ImagePickerRequest? {
        checkMain()
        val token = exportChoiceToken?.takeIf { it.operationId == choiceId && it.editor === editor } ?: return null
        dismissExportChoice()
        val purpose = when (format) {
            ImageExportFormat.PNG -> ImageRequestPurpose.EXPORT_PNG
            ImageExportFormat.JPEG -> ImageRequestPurpose.EXPORT_JPEG
            ImageExportFormat.TIFF -> ImageRequestPurpose.EXPORT_TIFF_FLATTENED
        }
        return beginRequest(purpose, editor, jpegOptions, size, token, format)
    }

    fun startingExportFormat(): ImageExportFormat {
        checkMain()
        val current = workspace.snapshot()
        return exportHistory.startingFormat(current.generation, ImageDocumentSource.decode(current.sourceIdentity)?.format)
    }

    /** Options confirmation precedes SAF; cancelling the destination keeps this choice, like PC. */
    fun confirmExportFormat(generation: Long, format: ImageExportFormat): Boolean {
        checkMain()
        if (closed || state.busy || workspace.snapshot().generation != generation) return false
        exportHistory.confirm(generation, format)
        ImageExportPreferences.save(context, format)
        return true
    }

    init {
        // Saved state is only an interrupted-request hint, never a promise to replay image IO.
        saved.get<Bundle>(SAVED_KEY)?.let { hints ->
            nextId = hints.getLong("nextId")
            @Suppress("DEPRECATION")
            hints.getParcelableArrayList<Bundle>("pickers")?.forEach { hint ->
                val request = request(hint)
                slots[request.id] = ImagePickerSlot(request, INTERRUPTED, UiNotice(UiNoticeKey.IMAGE_INTERRUPTED))
            }
            if (hints.getBoolean("active") || slots.isNotEmpty()) {
                val output = hints.getString("phase") == ImageRequestPhase.WRITING.name ||
                    hints.getBundle("activeRequest")?.getString("purpose") in setOf(ImageRequestPurpose.EXPORT_PNG.name, ImageRequestPurpose.EXPORT_JPEG.name, ImageRequestPurpose.EXPORT_TIFF.name, ImageRequestPurpose.EXPORT_TIFF_FLATTENED.name)
                state = ImageRequestState(error = if (output) INTERRUPTED_OUTPUT else INTERRUPTED,
                    errorNotice = UiNotice(if (output) UiNoticeKey.IMAGE_OUTPUT_INTERRUPTED else UiNoticeKey.IMAGE_INTERRUPTED))
            }
        }
        persist()
    }

    fun pendingPickers(): List<ImagePickerRequest> = synchronized(gate) { slots.values.map { it.request } }
    fun bind(identity: Any, register: (ImagePickerRequest) -> Unit, launcher: (ImagePickerRequest) -> Unit) {
        checkMain()
        binding = identity; launch = launcher
        // register may synchronously deliver an ActivityResultRegistry pending result.
        pendingPickers().forEach(register)
    }
    fun unbind(identity: Any) {
        checkMain()
        if (binding === identity) { binding = null; launch = null }
    }
    fun begin(purpose: ImageRequestPurpose, editor: NativeEditor,
        jpegOptions: JpegExportOptions = JpegExportOptions()): ImagePickerRequest? =
        beginRequest(purpose, editor, jpegOptions, null)

    private fun beginRequest(purpose: ImageRequestPurpose, editor: NativeEditor, jpegOptions: JpegExportOptions,
        exportSize: ImageExportSize?, expected: WorkspaceToken? = null, format: ImageExportFormat? = null): ImagePickerRequest? {
        checkMain()
        if (closed || state.busy || exportChoiceGeneration != null) { report("Another image operation is still active", UiNotice(UiNoticeKey.IMAGE_BUSY)); return null }
        if (launch == null) { report("The image picker is not attached to the current window", UiNotice(UiNoticeKey.IMAGE_PICKER_UNBOUND)); return null }
        if (slots.size >= MAX_PENDING_PICKERS) { report("Finish the outstanding image pickers before starting another request", UiNotice(UiNoticeKey.IMAGE_PICKERS_PENDING)); return null }
        val token = expected ?: workspace.token(editor)
        if (token == null) { report("The image request belongs to a closed or replaced document", UiNotice(UiNoticeKey.IMAGE_CHANGED)); return null }
        val admission = runCatching {
            check(workspace.execute(token) {
                uiCheck(!editor.hasPendingEdits && !editor.hasActiveStrokes, UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
                check(editor.currentRevision() == token.revision) { "Document changed before the image picker opened" }
            }) { "The document cannot accept an image request right now" }
        }
        admission.exceptionOrNull()?.let { report(it.message ?: "Image request failed", uiFailure(it, UiNoticeKey.IMAGE_CHANGED)); return null }
        if (format != null && !confirmExportFormat(token.generation, format)) return null
        val sourceState = workspace.snapshot()
        val projectName = if (sourceState.generation == token.generation)
            ProjectNamePreferences.projectName(context, sourceState.sourceIdentity) else null
        val imageSourceName = if (sourceState.generation == token.generation)
            ImageDocumentSource.decode(sourceState.sourceIdentity)?.name else null
        val outputName = when (purpose) {
            ImageRequestPurpose.IMPORT, ImageRequestPurpose.IMPORT_FILE -> null
            ImageRequestPurpose.EXPORT_PNG -> ImageExportNames.outputName(projectName, "png", imageSourceName = imageSourceName)
            ImageRequestPurpose.EXPORT_JPEG -> ImageExportNames.outputName(projectName, "jpeg", imageSourceName = imageSourceName)
            ImageRequestPurpose.EXPORT_TIFF_FLATTENED -> ImageExportNames.outputName(projectName, "tiff", imageSourceName = imageSourceName)
            ImageRequestPurpose.EXPORT_TIFF -> ImageExportNames.outputName(projectName, "tiff", sourceLayers = true, imageSourceName = imageSourceName)
        }
        val request = ImagePickerRequest(++nextId, purpose, session, token.generation, token.revision, jpegOptions, outputName, exportSize)
        synchronized(gate) {
            operation = ImageRequestOperation(request, token)
            slots[request.id] = ImagePickerSlot(request)
        }
        state = ImageRequestState(request, ImageRequestPhase.CHOOSING)
        persist()
        try { checkNotNull(launch)(request) }
        catch (failure: Exception) {
            synchronized(gate) { slots.remove(request.id); operation = null }
            state = ImageRequestState(request = request, error = failure.message ?: "The image picker could not open", completedId = request.id,
                errorNotice = uiFailure(failure, UiNoticeKey.PICKER_UNAVAILABLE))
            persist()
            return null
        }
        return request
    }

    fun pickerResult(id: Long, purpose: ImageRequestPurpose, uri: Uri?) {
        checkMain()
        val slot = synchronized(gate) { slots[id] }
        if (slot == null) { report("Duplicate or unknown image picker result was ignored", UiNotice(UiNoticeKey.IMAGE_RESULT_INVALID)); return }
        if (slot.request.purpose != purpose) { report("Image picker result purpose did not match its request", UiNotice(UiNoticeKey.IMAGE_RESULT_INVALID)); return }
        synchronized(gate) { slots.remove(id) }
        val active = synchronized(gate) { operation?.takeIf { it.request.id == id } }
        if (slot.rejection != null || active == null || closed) {
            val reason = slot.rejection ?: "Late image picker result belongs to an inactive request"
            report(if (uri != null && !purpose.isImport)
                "$reason. The picker may have created a destination file; no image encoding was started for this result." else reason,
                if (uri != null && !purpose.isImport) UiNotice(UiNoticeKey.IMAGE_LATE_OUTPUT)
                else slot.rejectionNotice ?: UiNotice(UiNoticeKey.IMAGE_CHANGED))
            persist(); return
        }
        if (uri == null) {
            synchronized(gate) { operation = null }
            state = ImageRequestState(request = slot.request, message = "Image selection cancelled", completedId = id, messageNotice = UiNotice(UiNoticeKey.IMAGE_CANCELLED))
            persist(); return
        }
        val failure = validate(active)
        if (failure != null) { finish(active, Result.failure(failure)); return }
        synchronized(gate) { active.phase = ImageRequestPhase.QUEUED; active.uri = uri }
        state = state.copy(phase = ImageRequestPhase.QUEUED, error = null, errorNotice = null, selectedUri = uri)
        persist()
        workspace.runIo(active.token, retainedCompletion = true, work = {
            when (purpose) {
                ImageRequestPurpose.IMPORT, ImageRequestPurpose.IMPORT_FILE -> import(active, uri)
                ImageRequestPurpose.EXPORT_PNG, ImageRequestPurpose.EXPORT_JPEG, ImageRequestPurpose.EXPORT_TIFF,
                ImageRequestPurpose.EXPORT_TIFF_FLATTENED -> export(active, uri)
            }
        }) { result -> finish(active, result.map { if (!purpose.isImport) uri else null }) }
    }

    fun cancel(): Boolean {
        checkMain()
        val active = synchronized(gate) {
            operation?.takeIf { it.phase in setOf(ImageRequestPhase.CHOOSING, ImageRequestPhase.QUEUED, ImageRequestPhase.DECODING, ImageRequestPhase.ENCODING) }
                ?.also { it.rejection = "Image operation cancelled"; it.rejectionNotice = UiNotice(UiNoticeKey.IMAGE_CANCELLED); it.userCancelled = true }
        } ?: return false
        synchronized(gate) {
            slots[active.request.id]?.let { it.rejection = active.rejection; it.rejectionNotice = active.rejectionNotice }
            if (active.phase == ImageRequestPhase.CHOOSING) operation = null else active.phase = ImageRequestPhase.CANCELLING
        }
        state = if (synchronized(gate) { operation == null })
            ImageRequestState(request = active.request, message = "Image operation cancelled", completedId = active.request.id, messageNotice = UiNotice(UiNoticeKey.IMAGE_CANCELLED))
        else state.copy(phase = ImageRequestPhase.CANCELLING, message = "Cancelling image operation…", messageNotice = UiNotice(UiNoticeKey.IMAGE_CANCELLING))
        persist(); return true
    }
    fun clearError() { checkMain(); state = state.copy(error = null, errorNotice = null) }
    fun workspaceChanged(value: WorkspaceState) {
        checkMain()
        exportChoiceToken?.let { token ->
            if (value.editor !== token.editor || value.generation != token.generation ||
                value.currentRevision != token.revision) dismissExportChoice()
        }
        val active = synchronized(gate) { operation } ?: return
        if (synchronized(gate) { active.phase in setOf(ImageRequestPhase.COMMITTING, ImageRequestPhase.WRITING) }) return
        if (value.editor !== active.token.editor || value.generation != active.token.generation || value.currentRevision != active.token.revision) {
            reject(active, "The document changed while the image request was active", UiNotice(UiNoticeKey.IMAGE_CHANGED))
        }
    }
    fun close() {
        checkMain()
        dismissExportChoice()
        closed = true; binding = null; launch = null
        val active = synchronized(gate) { operation }
        active?.let { reject(it, when (it.phase) {
            ImageRequestPhase.WRITING -> "The document closed after image writing started; the destination may contain output"
            ImageRequestPhase.COMMITTING -> "The document closed after image commit started; the final workspace save will handle the committed edit"
            else -> "The document closed before the image request completed"
        }, UiNotice(when (it.phase) {
            ImageRequestPhase.WRITING -> UiNoticeKey.IMAGE_CLOSED_WRITING
            ImageRequestPhase.COMMITTING -> UiNoticeKey.IMAGE_CLOSED_COMMITTING
            else -> UiNoticeKey.IMAGE_CHANGED
        })) }
    }

    private fun validate(active: ImageRequestOperation): Throwable? = runCatching {
        check(workspace.execute(active.token) {
            uiCheck(!active.token.editor.hasPendingEdits && !active.token.editor.hasActiveStrokes, UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
            check(active.token.editor.currentRevision() == active.token.revision) { "Document changed while choosing an image or export destination" }
        }) { "The image request belongs to a closed, replaced or unavailable document" }
    }.exceptionOrNull()
    private fun checkActive(active: ImageRequestOperation) = synchronized(gate) {
        check(operation === active && !closed) { "The image request is no longer active" }
        active.rejection?.let { throw CancellationException(it) }
    }
    private fun phase(active: ImageRequestOperation, next: ImageRequestPhase) {
        synchronized(gate) { checkActive(active); active.phase = next }
        main.post { if (synchronized(gate) { operation === active && active.phase == next && !closed }) { state = state.copy(phase = next); persist() } }
    }
    private fun import(active: ImageRequestOperation, uri: Uri) {
        phase(active, ImageRequestPhase.DECODING)
        testHooks?.beforeDecode?.invoke()
        checkActive(active)
        val memory = active.token.editor.serialized { active.token.editor.retainedMemory() }
        val metadataBytes = Math.addExact(4L * 1024 * 1024, Math.multiplyExact(memory.stateBytes, 3))
        NativeImageIO.decode(context, uri, residentBytes = memory.totalBytes, nativeMetadataBytes = metadataBytes).use { image ->
            testHooks?.beforeCommit?.invoke()
            check(workspace.execute(active.token) {
                uiCheck(!active.token.editor.hasPendingEdits && !active.token.editor.hasActiveStrokes, UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
                check(active.token.editor.currentRevision() == active.token.revision) { "Document changed while decoding the selected image" }
                phase(active, ImageRequestPhase.COMMITTING)
                image.importInto(active.token.editor, fit = true)
            }) { "The image import belongs to a closed or replaced document" }
        }
    }
    private fun export(active: ImageRequestOperation, uri: Uri) {
        checkActive(active)
        var captured: NativeScene? = null
        var project: NativeProjectSnapshot? = null
        var residentBytes = 0L
        var resolution = 72.0
        try {
            check(workspace.execute(active.token) {
                uiCheck(!active.token.editor.hasPendingEdits && !active.token.editor.hasActiveStrokes, UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
                check(active.token.editor.currentRevision() == active.token.revision) { "Document changed while choosing the image destination" }
                checkActive(active)
                if (active.request.purpose == ImageRequestPurpose.EXPORT_TIFF) project = active.token.editor.captureProjectSnapshot()
                else captured = checkNotNull(active.token.editor.captureScene()) { "The document is closed" }
                resolution = active.token.editor.documentResolution()
                residentBytes = active.token.editor.retainedMemory().totalBytes
            }) { "The image export belongs to a closed or replaced document" }
            if (active.request.purpose == ImageRequestPurpose.EXPORT_TIFF_FLATTENED) {
                phase(active, ImageRequestPhase.ENCODING)
                NativeImageIO.exportTiffFlattened(context, uri, checkNotNull(captured), resolution, residentBytes,
                    size = active.request.exportSize,
                    checkCancelled = { checkActive(active) }, beforeWrite = {
                        testHooks?.beforeWrite?.invoke()
                        check(workspace.execute(active.token) {
                            uiCheck(!active.token.editor.hasPendingEdits && !active.token.editor.hasActiveStrokes,
                                UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
                            check(active.token.editor.currentRevision() == active.token.revision) {
                                "Document changed before image writing began"
                            }
                            phase(active, ImageRequestPhase.WRITING)
                        }) { "The image export belongs to a closed or replaced document" }
                    })
                return
            }
            testHooks?.beforeWrite?.invoke()
            // Existing encoder includes provider writing; once entered, cancellation cannot promise rollback.
            check(workspace.execute(active.token) {
                uiCheck(!active.token.editor.hasPendingEdits && !active.token.editor.hasActiveStrokes, UiNoticeKey.PENDING_EDIT) { PENDING_EDIT_MESSAGE }
                check(active.token.editor.currentRevision() == active.token.revision) { "Document changed before image writing began" }
                phase(active, ImageRequestPhase.WRITING)
            }) { "The image export belongs to a closed or replaced document" }
            if (active.request.purpose == ImageRequestPurpose.EXPORT_TIFF)
                NativeImageIO.exportTiffSourceLayers(context, uri, checkNotNull(project), residentBytes)
            else if (active.request.purpose == ImageRequestPurpose.EXPORT_JPEG)
                NativeImageIO.exportJpeg(context, uri, checkNotNull(captured), residentBytes,
                    options = active.request.jpegOptions, size = active.request.exportSize)
            else NativeImageIO.exportPng(context, uri, checkNotNull(captured), residentBytes, size = active.request.exportSize)
        } finally { captured?.close(); project?.close() }
    }
    private fun reject(active: ImageRequestOperation, reason: String, notice: UiNotice) {
        synchronized(gate) {
            active.rejection = reason; active.rejectionNotice = notice; active.userCancelled = false
            slots[active.request.id]?.let { it.rejection = reason; it.rejectionNotice = notice }
            if (active.phase == ImageRequestPhase.CHOOSING) operation = null
        }
        state = if (synchronized(gate) { operation == null }) ImageRequestState(active.request, error = reason, completedId = active.request.id, errorNotice = notice)
            else state.copy(error = reason, errorNotice = notice)
        persist()
    }
    private fun finish(active: ImageRequestOperation, result: Result<Uri?>) {
        checkMain()
        if (synchronized(gate) { operation !== active }) return
        synchronized(gate) { operation = null }
        val failure = if (active.userCancelled) null else active.rejection ?: result.exceptionOrNull()?.let { it.message ?: "Image operation failed" }
        val notice = if (failure == null) null else active.rejectionNotice ?: uiFailure(result.exceptionOrNull(),
            if (active.request.purpose.isImport) UiNoticeKey.IMAGE_IMPORT_FAILED else UiNoticeKey.IMAGE_EXPORT_FAILED)
        if (failure == null && !active.userCancelled && result.isSuccess && result.getOrNull() != null &&
            active.request.purpose == ImageRequestPurpose.EXPORT_PNG) {
            exportHistory.confirm(active.request.generation, ImageExportFormat.PNG)
            ImageExportPreferences.save(context, ImageExportFormat.PNG)
        }
        state = ImageRequestState(active.request, error = failure, errorNotice = notice,
            message = if (failure != null) "" else if (active.userCancelled) "Image operation cancelled"
                else if (active.request.purpose.isImport) "Image imported"
                else if (active.request.purpose == ImageRequestPurpose.EXPORT_TIFF) "TIFF source layers exported"
                else if (active.request.purpose == ImageRequestPurpose.EXPORT_TIFF_FLATTENED) "Flattened TIFF exported"
                else if (active.request.purpose == ImageRequestPurpose.EXPORT_JPEG) "JPEG exported" else "PNG exported",
            outputUri = result.getOrNull(), completedId = active.request.id, selectedUri = active.uri,
            messageNotice = if (failure != null) null else UiNotice(if (active.userCancelled) UiNoticeKey.IMAGE_CANCELLED
                else if (active.request.purpose.isImport) UiNoticeKey.IMAGE_IMPORTED else UiNoticeKey.IMAGE_EXPORTED))
        persist()
    }
    private fun report(message: String, notice: UiNotice) { state = state.copy(error = message, errorNotice = notice) }
    private fun persist() {
        checkMain()
        val hints = Bundle().apply {
            putLong("nextId", nextId)
            putBoolean("active", synchronized(gate) { operation != null })
            putString("phase", state.phase.name)
            putBundle("activeRequest", synchronized(gate) { operation?.request }?.let(::hint))
            putString("acceptedUri", synchronized(gate) { operation?.uri }?.toString())
            putParcelableArrayList("pickers", ArrayList(pendingPickers().map(::hint)))
        }
        if (!hints.getBoolean("active") && slots.isEmpty()) saved.remove<Bundle>(SAVED_KEY) else saved[SAVED_KEY] = hints
    }
    private fun request(hint: Bundle) = ImagePickerRequest(hint.getLong("id"),
        ImageRequestPurpose.valueOf(checkNotNull(hint.getString("purpose"))), checkNotNull(hint.getString("session")),
        hint.getLong("generation"), hint.getLong("revision"),
        JpegExportOptions(hint.getInt("jpegQuality", 85), hint.getInt("jpegBackground", 0xffffff)), hint.getString("outputName"))
    private fun hint(request: ImagePickerRequest) = Bundle().apply {
        putLong("id", request.id); putString("purpose", request.purpose.name); putString("session", request.session)
        putLong("generation", request.generation); putLong("revision", request.revision)
        putInt("jpegQuality", request.jpegOptions.quality); putInt("jpegBackground", request.jpegOptions.backgroundRgb)
        putString("outputName", request.outputName)
    }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Image request UI state belongs to the main thread" } }
    companion object {
        internal const val SAVED_KEY = "image-request-hints"
        private const val MAX_PENDING_PICKERS = 8
        internal const val INTERRUPTED = "The previous image request was interrupted by process restart. Select the image or export destination again; no image operation was resumed."
        internal const val INTERRUPTED_OUTPUT = "The previous image export was interrupted by process restart. The destination may already contain output. Check it before exporting again; no image operation was resumed."
    }
}
