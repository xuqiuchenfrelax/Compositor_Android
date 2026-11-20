package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ProjectFileError(projects: ProjectFlowController) {
    val state = projects.state
    if (state.error == null) return
    Text((state.errorNotice ?: UiNotice(UiNoticeKey.PROJECT_FAILED)).text(LocalContext.current),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().clickable { projects.clearError() }.padding(horizontal = 14.dp, vertical = 8.dp))
}

/** Rendering can come and go; the retained owner alone starts work, owns cancellation and receives results. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProjectFileActions(editor: NativeEditor, canvas: CanvasView?, workspaceState: WorkspaceState,
    projects: ProjectFlowController, launchPicker: () -> Unit, launchExportPicker: () -> Unit, launchImagePicker: () -> Unit,
    showActions: Boolean = true) {
    val ui = LocalContext.current
    val state = projects.state
    NewCanvasDialog(projects)
    state.projectCopy?.let { request ->
        val valid = ProjectCopyNames.filename(request.name) != null
        AlertDialog(onDismissRequest = { projects.cancelSaveCopy() },
            title = { Text(ui.getString(R.string.ui_save_project_copy)) },
            text = { Column {
                OutlinedTextField(value = request.name, onValueChange = { projects.updateCopyName(it) },
                    label = { Text(ui.getString(R.string.ui_project_copy_name)) }, singleLine = true, isError = !valid)
                if (!valid) Text(ui.getString(R.string.ui_project_copy_name_invalid))
                Text(ui.getString(R.string.ui_project_copy_name_collision))
            } },
            confirmButton = { TextButton(enabled = valid, onClick = { projects.confirmSaveCopy() }) {
                Text(ui.getString(R.string.ui_save_project_copy_confirm))
            } },
            dismissButton = { TextButton(onClick = { projects.cancelSaveCopy() }) { Text(ui.getString(R.string.ui_cancel)) } })
    }
    LaunchedEffect(canvas, workspaceState.resourceGrowthFrozen) {
        if (!workspaceState.resourceGrowthFrozen) canvas?.requestRender()
    }
    state.exportHistory?.let { records ->
        AlertDialog(onDismissRequest = { projects.closeExportHistory() }, title = { Text(ui.getString(R.string.ui_export_records)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                projects.exportRecoveryNotice?.let { Text(projects.exportRecoveryNoticeUi?.text(ui) ?: UiNotice(UiNoticeKey.EXPORT_RECORD_FAILED).text(ui)) }
                Text(ui.getString(R.string.ui_review_the_selected_destination_before_exporting_again_forgetting_a_local_record_keep))
                if (records.isEmpty()) Text(ui.getString(R.string.ui_no_retained_export_records))
                records.forEach { receipt ->
                    Text(receipt.finalName)
                    Text(if (receipt.phase == "COMPLETE") ui.getString(R.string.ui_complete_bytes_were_verified_before_interruption_provider_persistence_is_unconfirmed)
                        else if (receipt.phase in setOf("PUBLISHING", "UNKNOWN", "CREATING")) ui.getString(R.string.ui_publication_or_creation_is_uncertain_inspect_the_destination_external_items_were_kept)
                        else ui.getString(R.string.ui_an_interrupted_temporary_copy_remains_cleanup_could_not_be_completed))
                    Text(ui.getString(R.string.ui_export_destination, receipt.owned.firstOrNull()?.parent?.displayName ?: ui.getString(R.string.ui_selected_folder)))
                    TextButton(onClick = { projects.forgetExportRecord(receipt.id) }) { Text(ui.getString(R.string.ui_forget_local_record_keep_files)) }
                }
            } }, confirmButton = { TextButton(onClick = { projects.closeExportHistory(); projects.dismissExportRecoveryNotice() }) { Text(ui.getString(R.string.ui_close)) } })
    }
    if (showActions) FlowRow {
        TextButton(enabled = canvas != null && !canvas.editingInProgress && !state.busy && !workspaceState.resourceGrowthFrozen,
            onClick = {
                if (canvas?.editingInProgress != true && projects.chooseImageDocument(editor)) {
                    try { launchImagePicker() }
                    catch (failure: Exception) { projects.pickerLaunchFailed(failure.message ?: ui.getString(R.string.ui_open_image)) }
                }
            }) { Text(ui.getString(R.string.ui_open_image)) }
        TextButton(enabled = canvas != null && !canvas.editingInProgress && !state.busy && !workspaceState.resourceGrowthFrozen,
            onClick = {
                if (canvas?.editingInProgress == true) return@TextButton
                if (projects.chooseProject(editor)) {
                    try { launchPicker() }
                    catch (failure: Exception) { projects.pickerLaunchFailed(failure.message ?: ui.getString(R.string.ui_the_project_picker_could_not_be_opened)) }
                }
            }) { Text(ui.getString(R.string.ui_open_project_copy)) }
        TextButton(enabled = canvas != null && !canvas.editingInProgress && !state.busy && !workspaceState.resourceGrowthFrozen,
            onClick = { if (canvas?.editingInProgress != true) projects.save(editor) }) { Text(ui.getString(R.string.ui_save_project)) }
        TextButton(enabled = canvas != null && !canvas.editingInProgress && !state.busy && !workspaceState.resourceGrowthFrozen,
            onClick = { if (canvas?.editingInProgress != true) projects.chooseSaveCopy(editor) }) { Text(ui.getString(R.string.ui_save_project_copy)) }
        TextButton(enabled = canvas?.editingInProgress != true && !state.busy && !workspaceState.resourceGrowthFrozen,
            onClick = { if (canvas?.editingInProgress != true) projects.listSaved(editor) }) { Text(ui.getString(R.string.ui_saved_projects)) }
        TextButton(enabled = canvas != null && !canvas.editingInProgress && !state.busy && !workspaceState.resourceGrowthFrozen,
            onClick = {
                if (canvas?.editingInProgress == true) return@TextButton
                if (projects.chooseExport(editor)) try { launchExportPicker() }
                catch (failure: Exception) { projects.pickerLaunchFailed(failure.message ?: ui.getString(R.string.ui_the_export_folder_picker_could_not_be_opened)) }
            }) { Text(ui.getString(R.string.ui_export_project_copy)) }
        TextButton(enabled = canvas?.editingInProgress != true && !state.busy && !workspaceState.resourceGrowthFrozen,
            onClick = { projects.listExportHistory() }) { Text(ui.getString(R.string.ui_export_records)) }
        if (projects.exportRecoveryNotice != null) TextButton(enabled = !state.busy,
            onClick = { projects.listExportHistory() }) { Text(ui.getString(R.string.ui_export_review_needed)) }
    }
    state.exportResult?.let { result ->
        val message = when (result.commit) {
            ProjectExportCommit.COMMITTED_VERIFIED -> ui.getString(R.string.ui_export_confirmed, result.displayName)
            ProjectExportCommit.UNKNOWN -> ui.getString(R.string.ui_export_unknown, result.displayName)
            ProjectExportCommit.NOT_COMMITTED -> if (result.cancelled) ui.getString(R.string.ui_project_export_was_cancelled_before_publication) else ui.getString(R.string.ui_export_unconfirmed, UiNotice(UiNoticeKey.PROJECT_EXPORT_FAILED).text(ui))
        }
        AlertDialog(onDismissRequest = { projects.acknowledgeExport() }, title = { Text(ui.getString(R.string.ui_project_export)) },
            text = { Text(message + if (result.cleanup in setOf(ProjectExportCleanup.RETAINED, ProjectExportCleanup.UNKNOWN))
                ui.getString(R.string.ui_some_temporary_export_items_remain_in_the_selected_folder_existing_projects_were_not_) else "") },
            confirmButton = { TextButton(onClick = { projects.acknowledgeExport() }) { Text(ui.getString(R.string.ui_close)) } })
    }
    if (state.phase == ProjectFlowPhase.LISTING) Text(state.messageNotice?.text(ui) ?: UiNotice(UiNoticeKey.PROJECT_FAILED).text(ui))
    state.confirmation?.let { request ->
        AlertDialog(onDismissRequest = { projects.cancelConfirmation() }, title = { Text(ui.getString(R.string.ui_unsaved_project_changes)) },
            text = { Text(ui.getString(if (request.imageDocument) R.string.ui_open_image_discard_prompt
                else R.string.ui_cancel_to_save_a_project_copy_first_or_discard_these_changes_and_open_the_selected_pr)) },
            confirmButton = { Column {
                TextButton(onClick = { projects.chooseSaveAndOpen() }) { Text(ui.getString(R.string.ui_save_and_open)) }
                if (workspaceState.sourceIdentity?.startsWith("local:") == true)
                    TextButton(onClick = { projects.chooseSaveCopyAndOpen() }) { Text(ui.getString(R.string.ui_save_copy_and_open)) }
                TextButton(onClick = { projects.discardAndOpen() }) { Text(ui.getString(R.string.ui_discard_changes_and_open)) }
            } },
            dismissButton = { TextButton(onClick = { projects.cancelConfirmation() }) { Text(ui.getString(R.string.ui_cancel)) } })
    }
    state.savedProjects?.let { saved ->
        AlertDialog(onDismissRequest = { projects.closeSavedList() }, title = { Text(ui.getString(R.string.ui_saved_project_copies)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (saved.isEmpty()) Text(ui.getString(R.string.ui_no_saved_project_copies_yet_use_save_project_copy_to_keep_an_editable_version_on_this))
                    saved.forEach { file -> TextButton(onClick = { projects.openSaved(editor, file) }) { Text(LocalProjectLibrary.label(file)) } }
                }
            }, confirmButton = { TextButton(onClick = { projects.closeSavedList() }) { Text(ui.getString(R.string.ui_close)) } })
    }
    if (state.running) AlertDialog(onDismissRequest = {}, title = { Text(ui.getString(R.string.ui_project)) },
        text = { Column { CircularProgressIndicator(); Text(state.messageNotice?.text(ui) ?: UiNotice(UiNoticeKey.PROJECT_FAILED).text(ui)) } }, confirmButton = {},
        dismissButton = { TextButton(enabled = state.canCancel, onClick = { projects.cancel() }) { Text(ui.getString(R.string.ui_cancel)) } })
}
