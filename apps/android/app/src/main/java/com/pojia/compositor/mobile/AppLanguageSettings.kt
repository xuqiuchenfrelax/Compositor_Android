package com.pojia.compositor.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role

@Composable
internal fun AppLanguageSettings(enabled: Boolean = true) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) {
        Text(context.getString(R.string.language_settings))
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(context.getString(R.string.language_settings)) },
        text = { Column {
            AppLanguage.entries.forEach { language ->
                val selected = AppLanguages.current(context) == language
                Row(Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = {
                    open = false
                    AppLanguages.set(context, language)
                }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = null)
                    Text(context.getString(language.label))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { open = false }) {
            Text(context.getString(R.string.ui_close))
        } })
}
