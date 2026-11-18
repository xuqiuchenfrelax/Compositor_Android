package com.pojia.compositor.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun VignetteEditor(value: VignetteModel, onValue: (VignetteModel) -> Unit) {
    val ui = LocalContext.current
    var pickingColor by rememberSaveable { mutableStateOf(false) }
    AdjustmentSlider(ui.getString(R.string.ui_vignette_amount), value.amount, 0.0..100.0) { onValue(value.copy(amount = it)) }
    val color = Color(value.color.red.toFloat(), value.color.green.toFloat(), value.color.blue.toFloat())
    val label = ui.getString(R.string.ui_vignette_color)
    TextButton(onClick = { pickingColor = true }, modifier = Modifier.semantics { contentDescription = label }) {
        Box(Modifier.size(24.dp).background(color).border(1.dp, Color.Gray))
        Text(label)
    }
    AdjustmentSlider(ui.getString(R.string.ui_vignette_midpoint), value.midpoint, 0.0..100.0) { onValue(value.copy(midpoint = it)) }
    AdjustmentSlider(ui.getString(R.string.ui_vignette_roundness), value.roundness, -100.0..100.0) { onValue(value.copy(roundness = it)) }
    AdjustmentSlider(ui.getString(R.string.ui_vignette_feather), value.feather, 0.0..100.0) { onValue(value.copy(feather = it)) }
    AdjustmentSlider(ui.getString(R.string.ui_vignette_highlights), value.highlights, 0.0..100.0) { onValue(value.copy(highlights = it)) }
    if (pickingColor) EditorColorPicker(color, false, label) { chosen ->
        pickingColor = false
        chosen?.let { onValue(value.copy(color = AdjustmentColorModel(it.red.toDouble(), it.green.toDouble(), it.blue.toDouble()))) }
    }
}
