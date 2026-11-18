package com.pojia.compositor.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

internal fun roundedGrainSlider(value: Double, decimals: Int): Double {
    val step = if (decimals == 1) 10.0 else 1.0
    return floor(value * step + .5) / step
}

@Composable
internal fun GrainEditor(value: GrainModel, update: (GrainModel) -> Unit, validity: (Boolean) -> Unit) {
    val ui = LocalContext.current
    var invalid by remember { mutableStateOf(emptySet<Int>()) }
    fun valid(index: Int, accepted: Boolean) {
        invalid = if (accepted) invalid - index else invalid + index
        validity(invalid.isEmpty())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GrainControl(ui.getString(R.string.ui_grain_amount), value.amount, 0.0..100.0, 0,
            update = { update(value.copy(amount = it)) }, valid = { valid(0, it) })
        GrainControl(ui.getString(R.string.ui_grain_size), value.size, .5..20.0, 1, logarithmic = true,
            update = { update(value.copy(size = it)) }, valid = { valid(1, it) })
        GrainControl(ui.getString(R.string.ui_grain_roughness), value.roughness, 0.0..100.0, 0,
            update = { update(value.copy(roughness = it)) }, valid = { valid(2, it) })
    }
}

@Composable
private fun GrainControl(label: String, value: Double, range: ClosedFloatingPointRange<Double>, decimals: Int,
    logarithmic: Boolean = false, update: (Double) -> Unit, valid: (Boolean) -> Unit) {
    val ui = LocalContext.current
    val focus = LocalFocusManager.current
    fun formatted(number: Double) = String.format(Locale.ROOT, "%.${decimals}f", number)
    var text by remember(label) { mutableStateOf(formatted(value)) }
    var focused by remember { mutableStateOf(false) }
    fun parsed(value: String) = value.toDoubleOrNull()?.takeIf { it.isFinite() && it in range }
    LaunchedEffect(value, focused) { if (!focused && parsed(text) != null) text = formatted(value) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(text, onValueChange = { next ->
                text = next
                val number = parsed(next)
                valid(number != null)
                if (number != null) update(number)
            }, singleLine = true, isError = parsed(text) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(108.dp).semantics { contentDescription = ui.getString(R.string.ui_field_value, label) }
                    .onFocusChanged {
                        if (focused && !it.isFocused) {
                            val accepted = parsed(text) != null
                            if (accepted) text = formatted(value)
                            valid(accepted)
                        }
                        focused = it.isFocused
                    })
        }
        if (parsed(text) == null) Text(ui.getString(R.string.ui_grain_valid_range, range.start.toString(), range.endInclusive.toString()),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        val low = if (logarithmic) ln(range.start) else range.start
        val high = if (logarithmic) ln(range.endInclusive) else range.endInclusive
        Slider(value = (if (logarithmic) ln(value) else value).toFloat(), valueRange = low.toFloat()..high.toFloat(),
            onValueChange = {
                focus.clearFocus()
                val next = roundedGrainSlider(if (logarithmic) exp(it.toDouble()) else it.toDouble(), decimals).coerceIn(range)
                text = formatted(next)
                valid(true)
                update(next)
            }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}
