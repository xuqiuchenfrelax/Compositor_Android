package com.pojia.compositor.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private fun AdjustmentColorModel.color() = Color(red.toFloat(), green.toFloat(), blue.toFloat())

@Composable
internal fun GradientMapEditor(value: GradientMapModel, onValue: (GradientMapModel) -> Unit) {
    val ui = LocalContext.current
    var pickingHighlights by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val colors = listOf(value.shadows.color(), value.highlights.color()).let { if (value.reversed) it.reversed() else it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(20.dp).background(Brush.horizontalGradient(colors)).border(1.dp, Color.Gray))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false, true).forEach { highlights ->
                val label = ui.getString(if (highlights) R.string.ui_adjustment_highlight_color else R.string.ui_adjustment_shadow_color)
                TextButton(onClick = { pickingHighlights = highlights }, modifier = Modifier.semantics { contentDescription = label }) {
                    Box(Modifier.size(24.dp).background(if (highlights) value.highlights.color() else value.shadows.color()).border(1.dp, Color.Gray))
                    Text(ui.getString(if (highlights) R.string.ui_adjustment_highlights else R.string.ui_adjustment_shadows))
                }
            }
        }
        AdjustmentToggle(ui.getString(R.string.ui_adjustment_reverse), value.reversed) { onValue(value.copy(reversed = it)) }
    }
    pickingHighlights?.let { highlights ->
        EditorColorPicker((if (highlights) value.highlights else value.shadows).color(), highlights,
            ui.getString(if (highlights) R.string.ui_adjustment_highlight_color else R.string.ui_adjustment_shadow_color)) { chosen ->
            pickingHighlights = null
            chosen?.let {
                val endpoint = AdjustmentColorModel(it.red.toDouble(), it.green.toDouble(), it.blue.toDouble())
                onValue(if (highlights) value.copy(highlights = endpoint) else value.copy(shadows = endpoint))
            }
        }
    }
}
