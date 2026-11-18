package com.pojia.compositor.mobile

import android.content.Context

/** JNI preserves the native status; presentation must never classify an English diagnostic. */
internal class NativeCommandException(val status: Int) :
    IllegalStateException("Native editor command failed ($status)")

/** Retained UI state carries a stable typed notice; technical error strings remain separate. */
internal data class UiNotice(val key: UiNoticeKey, val arguments: List<Any> = emptyList()) {
    constructor(key: UiNoticeKey, vararg arguments: Any) : this(key, arguments.toList())
    fun text(context: Context): String = when (key) {
        UiNoticeKey.COPYING_PROJECT_PROGRESS -> {
            val copied = arguments[0] as Int
            val total = arguments[1] as Int
            context.getString(key.resource, context.resources.getQuantityString(
                R.plurals.notice_copying_project_assets, total, copied, total))
        }
        UiNoticeKey.EXPORT_RECOVERY, UiNoticeKey.EXPORT_RECOVERY_LIMITED -> {
            val cleaned = arguments[0] as Int
            val review = arguments[1] as Int
            context.getString(key.resource,
                context.resources.getQuantityString(R.plurals.notice_export_copies_cleaned, cleaned, cleaned),
                context.resources.getQuantityString(R.plurals.notice_export_records_review, review, review))
        }
        else -> context.getString(key.resource, *arguments.toTypedArray())
    }
}

internal enum class UiNoticeKey(val resource: Int) {
    COLOR_SAMPLE_FAILED(R.string.notice_color_sample_failed),
    CONTEXT_SWITCH_FAILED(R.string.notice_context_switch_failed),
    TRANSFORM_FAILED(R.string.notice_transform_failed),
    TRANSFORM_REFRESH_FAILED(R.string.notice_transform_refresh_failed),
    TRANSFORM_SELECT_LAYER(R.string.notice_transform_select_layer),
    TRANSFORM_ADMISSION_FAILED(R.string.notice_transform_admission_failed),
    DISTORT_UNAVAILABLE(R.string.notice_distort_unavailable),
    CLONE_SOURCE_PENDING(R.string.notice_clone_source_pending),
    CLONE_SOURCE_BOUNDS(R.string.notice_clone_source_bounds),
    STROKE_CANCEL_FAILED(R.string.notice_stroke_cancel_failed),
    TRANSFORM_PENDING(R.string.notice_transform_pending),
    EDITOR_COMMAND_FAILED(R.string.notice_editor_command_failed),
    DISTORT_SELECT_LAYER(R.string.notice_distort_select_layer),
    DISTORT_FAILED(R.string.notice_distort_failed),
    DISTORT_COMMIT_UNRESOLVED(R.string.notice_distort_commit_unresolved),
    DISTORT_CANCEL_FAILED(R.string.notice_distort_cancel_failed),
    SELECTION_FAILED(R.string.notice_selection_failed),
    SHAPE_LIMIT(R.string.notice_shape_limit),
    SMEAR_MASK_UNSUPPORTED(R.string.ui_smear_mask_unsupported),
    HEALING_PIXELS_ONLY(R.string.ui_healing_pixels_only),
    HEALING_REGION_LIMIT(R.string.ui_healing_region_limit),
    HEALING_FAILED(R.string.ui_healing_failed),
    CLONE_POSITION(R.string.notice_clone_position),
    PAINT_SELECT_LAYER(R.string.notice_paint_select_layer),
    CLONE_PIXELS_ONLY(R.string.notice_clone_pixels_only),
    CLONE_SOURCE_REQUIRED(R.string.notice_clone_source_required),
    CANVAS_RENDER_FAILED(R.string.notice_canvas_render_failed),
    RECOVERY_OPEN_FAILED(R.string.notice_recovery_open_failed),
    RECOVERY_SAVE_FAILED(R.string.notice_recovery_save_failed),
    RECOVERY_DURABILITY(R.string.notice_recovery_durability),
    PROJECT_FAILED(R.string.notice_project_failed),
    PROJECT_LIST_FAILED(R.string.notice_project_list_failed),
    PROJECT_OPEN_FAILED(R.string.notice_project_open_failed),
    PROJECT_SAVE_FAILED(R.string.notice_project_save_failed),
    PROJECT_SAVE_UNCERTAIN(R.string.notice_project_save_uncertain),
    PROJECT_EXPORT_FAILED(R.string.notice_project_export_failed),
    NEW_CANVAS_FAILED(R.string.notice_new_canvas_failed),
    PICKER_UNAVAILABLE(R.string.notice_picker_unavailable),
    PENDING_EDIT(R.string.notice_pending_edit),
    PROJECT_CHANGED(R.string.notice_project_changed),
    NEW_CANVAS_CHANGED(R.string.notice_new_canvas_changed),
    EXPORT_CHANGED(R.string.notice_export_changed),
    EXPORT_PERMISSION(R.string.notice_export_permission),
    LAYER_NAME_REQUIRED(R.string.notice_layer_name_required),
    READING_PROJECTS(R.string.notice_reading_projects),
    READING_EXPORTS(R.string.notice_reading_exports),
    WAITING_NEW_CANVAS(R.string.notice_waiting_new_canvas),
    SAVING_CURRENT_PROJECT(R.string.notice_saving_current_project),
    CREATING_CANVAS(R.string.notice_creating_canvas),
    WAITING_PROJECT_COPY(R.string.notice_waiting_project_copy),
    WAITING_PROJECT_OPEN(R.string.notice_waiting_project_open),
    OPENING_PROJECT(R.string.notice_opening_project),
    OPENING_IMAGE(R.string.notice_opening_image),
    COPYING_PROJECT(R.string.notice_copying_project),
    COPYING_PROJECT_PROGRESS(R.string.notice_copying_project_progress),
    WAITING_PROJECT_SAVE(R.string.notice_waiting_project_save),
    SAVING_PROJECT(R.string.notice_saving_project),
    PREPARING_EXPORT(R.string.notice_preparing_export),
    EXPORTING_PROJECT(R.string.notice_exporting_project),
    PUBLISHING_PROJECT(R.string.notice_publishing_project),
    CANCELLING(R.string.notice_cancelling),
    EXPORT_RECORD_FAILED(R.string.notice_export_record_failed),
    EXPORT_RECOVERY(R.string.notice_export_recovery),
    EXPORT_RECOVERY_LIMITED(R.string.notice_export_recovery_limited),
    IMAGE_BUSY(R.string.notice_image_busy),
    IMAGE_PICKER_UNBOUND(R.string.notice_image_picker_unbound),
    IMAGE_PICKERS_PENDING(R.string.notice_image_pickers_pending),
    IMAGE_CHANGED(R.string.notice_image_changed),
    IMAGE_RESULT_INVALID(R.string.notice_image_result_invalid),
    IMAGE_LATE_OUTPUT(R.string.notice_image_late_output),
    IMAGE_INTERRUPTED(R.string.notice_image_interrupted),
    IMAGE_OUTPUT_INTERRUPTED(R.string.notice_image_output_interrupted),
    IMAGE_CANCELLED(R.string.notice_image_cancelled),
    IMAGE_CANCELLING(R.string.notice_image_cancelling),
    IMAGE_IMPORTED(R.string.notice_image_imported),
    IMAGE_EXPORTED(R.string.notice_image_exported),
    IMAGE_IMPORT_FAILED(R.string.notice_image_import_failed),
    IMAGE_EXPORT_FAILED(R.string.notice_image_export_failed),
    TIFF_FLATTENED_UNSUPPORTED(R.string.notice_tiff_flattened_unsupported),
    IMAGE_CLOSED_WRITING(R.string.notice_image_closed_writing),
    IMAGE_CLOSED_COMMITTING(R.string.notice_image_closed_committing),
    PROJECT_LIMIT(R.string.notice_project_limit),
    INVALID_PROJECT(R.string.notice_invalid_project),
    MEMORY_LIMIT(R.string.notice_memory_limit),
    UNSUPPORTED_PROJECT(R.string.notice_unsupported_project),
    CORRUPT_PROJECT(R.string.notice_corrupt_project),
    FILE_ACCESS_DENIED(R.string.notice_file_access_denied),
    FILE_IO_FAILED(R.string.notice_file_io_failed),
    SOURCE_CHANGED(R.string.notice_source_changed),
    DISK_LIMIT(R.string.notice_disk_limit),
    OPERATION_CANCELLED(R.string.notice_operation_cancelled),
    DOCUMENT_RESOLUTION_INVALID(R.string.ui_enter_a_resolution_from_1_to_9600_pixels_inch),
    GUIDE_POSITION_INVALID(R.string.ui_enter_a_finite_guide_position_between_1000000_and_1000000_pixels),
    EDITOR_REFRESH_FAILED(R.string.ui_could_not_refresh_the_editor),
}

/** Classification uses typed outcomes only; the English exception message is never a lookup key. */
internal fun uiFailure(error: Throwable?, fallback: UiNoticeKey): UiNotice {
    if (error != null) android.util.Log.w("CompositorOperation", fallback.name, error)
    if (error is UiNoticeException) return error.notice
    val status = when (error) {
        is NativeCommandException -> error.status.takeIf { it == 3 }
        is TransformDraftException -> error.status.takeIf { it == 3 }
        is ProjectImportException -> error.status
        is CheckpointException -> error.status
        else -> null
    }
    val key = when {
        error is CheckpointException && error.write?.destinationPublished == true && !error.write.durabilityConfirmed -> UiNoticeKey.RECOVERY_DURABILITY
        status == 4 && error is CheckpointException -> UiNoticeKey.PENDING_EDIT
        status == 3 || error is OutOfMemoryError || error is ResourceBudgetExceededException -> UiNoticeKey.MEMORY_LIMIT
        status == 5 -> UiNoticeKey.UNSUPPORTED_PROJECT
        status == 7 -> UiNoticeKey.FILE_IO_FAILED
        status == 8 -> UiNoticeKey.CORRUPT_PROJECT
        status == 9 -> UiNoticeKey.OPERATION_CANCELLED
        error is SecurityException -> UiNoticeKey.FILE_ACCESS_DENIED
        error is ProjectTransportException -> when (error.failure) {
            ProjectTransportFailure.UNSUPPORTED -> UiNoticeKey.UNSUPPORTED_PROJECT
            ProjectTransportFailure.LIMIT -> UiNoticeKey.PROJECT_LIMIT
            ProjectTransportFailure.IO -> UiNoticeKey.FILE_IO_FAILED
            ProjectTransportFailure.CHANGED -> UiNoticeKey.SOURCE_CHANGED
            ProjectTransportFailure.CANCELLED -> UiNoticeKey.OPERATION_CANCELLED
            ProjectTransportFailure.DISK -> UiNoticeKey.DISK_LIMIT
            ProjectTransportFailure.INVALID -> UiNoticeKey.INVALID_PROJECT
        }
        error is java.io.IOException -> UiNoticeKey.FILE_IO_FAILED
        else -> fallback
    }
    return UiNotice(key)
}

internal class UiNoticeException(val notice: UiNotice, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal inline fun uiCheck(condition: Boolean, key: UiNoticeKey, message: () -> Any) {
    if (!condition) throw UiNoticeException(UiNotice(key), message().toString())
}

internal fun ImageRequestState.localizedError(context: Context): String? =
    error?.let { (errorNotice ?: UiNotice(if (request?.purpose?.isImport != true)
        UiNoticeKey.IMAGE_EXPORT_FAILED else UiNoticeKey.IMAGE_IMPORT_FAILED)).text(context) }

internal fun WorkspaceState.localizedRecoveryError(context: Context): String? =
    recoveryError?.let { (recoveryErrorNotice ?: UiNotice(UiNoticeKey.RECOVERY_OPEN_FAILED)).text(context) }

internal fun WorkspaceState.localizedSaveError(context: Context): String? =
    saveError?.let { (saveErrorNotice ?: UiNotice(UiNoticeKey.RECOVERY_SAVE_FAILED)).text(context) }

internal fun ProjectSaveResult.failureNotice(): UiNotice {
    val error = when {
        status != 0 -> ProjectImportException(status)
        recoveryWrite?.successful == false -> CheckpointException(recoveryWrite.status, recoveryWrite)
        else -> null
    }
    return uiFailure(error, if (committed) UiNoticeKey.PROJECT_SAVE_UNCERTAIN else UiNoticeKey.PROJECT_SAVE_FAILED)
}
