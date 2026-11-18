package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType

/** Draft and request ownership stay in the retained project flow through Activity recreation. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NewCanvasDialog(projects: ProjectFlowController) {
    val ui = LocalContext.current
    projects.state.newCanvas?.let { request ->
        val draft = request.draft
        val spec = draft.spec()
        AlertDialog(onDismissRequest = { projects.cancelNewCanvas() }, title = { Text(ui.getString(R.string.ui_new_canvas)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = draft.width, onValueChange = { text -> projects.updateNewCanvas { it.copy(width = text) } },
                    label = { Text(ui.getString(R.string.ui_width)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_new_canvas_width) })
                OutlinedTextField(value = draft.height, onValueChange = { text -> projects.updateNewCanvas { it.copy(height = text) } },
                    label = { Text(ui.getString(R.string.ui_height)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_new_canvas_height) })
                FlowRow { NewCanvasUnit.entries.forEach { unit ->
                    FilterChip(selected = unit == draft.unit, onClick = { projects.updateNewCanvas { it.withUnit(unit) } },
                        label = { Text(ui.canvasUnitTitle(unit)) })
                } }
                OutlinedTextField(value = draft.resolution, onValueChange = { text -> projects.updateNewCanvas { it.copy(resolution = text) } },
                    label = { Text(ui.getString(R.string.ui_resolution_pixels_inch)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_new_canvas_resolution) })
                FlowRow {
                    TextButton(onClick = { projects.updateNewCanvas { it.copy(resolution = "72") } }) { Text("72 DPI") }
                    TextButton(onClick = { projects.updateNewCanvas { it.copy(resolution = "300") } }) { Text("300 DPI") }
                }
                Text(ui.getString(R.string.ui_background))
                FlowRow { NewCanvasBackground.entries.forEach { background ->
                    FilterChip(selected = background == draft.background,
                        onClick = { projects.updateNewCanvas { it.copy(background = background) } }, label = { Text(ui.canvasBackgroundTitle(background)) })
                } }
                FlowRow {
                    TextButton(onClick = { projects.updateNewCanvas { it.withPixels(1920, 1080) } }) { Text("1080p") }
                    TextButton(onClick = { projects.updateNewCanvas { it.withPixels(3840, 2160) } }) { Text("4K") }
                    TextButton(onClick = { projects.updateNewCanvas { it.copy(width = it.height, height = it.width) } }) { Text(ui.getString(R.string.ui_swap_dimensions)) }
                }
                if (spec == null) Text(ui.getString(R.string.ui_enter_positive_sizes_up_to_30_000_pixels_per_side_and_a_resolution_from_1_to_9_600_dp))
                else Text(ui.getString(R.string.ui_canvas_spec, spec.width, spec.height, spec.resolution.toString(), ui.canvasBackgroundTitle(spec.background)))
                Text(ui.getString(R.string.ui_this_opens_a_new_canvas_in_place_of_the_current_document_save_a_project_copy_to_keep_))
            } }, confirmButton = { TextButton(enabled = spec != null, onClick = { projects.createNewCanvas() }) { Text(ui.getString(R.string.ui_create_canvas)) } },
            dismissButton = { TextButton(onClick = { projects.cancelNewCanvas() }) { Text(ui.getString(R.string.ui_cancel)) } })
    }
    projects.state.newConfirmation?.let {
        AlertDialog(onDismissRequest = { projects.cancelNewCanvas() }, title = { Text(ui.getString(R.string.ui_unsaved_project_changes)) },
            text = { Text(ui.getString(R.string.ui_save_a_project_copy_on_this_device_before_creating_the_new_canvas_or_discard_the_curr)) },
            confirmButton = { Column {
                TextButton(onClick = { projects.saveAndCreateNewCanvas() }) { Text(ui.getString(R.string.ui_save_and_create)) }
                if (projects.hasLocalSource())
                    TextButton(onClick = { projects.saveCopyAndCreateNewCanvas() }) { Text(ui.getString(R.string.ui_save_copy_and_create)) }
            } },
            dismissButton = { Column {
                TextButton(onClick = { projects.discardAndCreateNewCanvas() }) { Text(ui.getString(R.string.ui_discard_changes_and_create)) }
                TextButton(onClick = { projects.cancelNewCanvas() }) { Text(ui.getString(R.string.ui_cancel)) }
            } })
    }
}
