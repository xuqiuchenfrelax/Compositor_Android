package com.pojia.compositor.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

internal enum class ExposureParameter(val label: Int, val range: ClosedFloatingPointRange<Double>,
    val decimals: Int, val logarithmic: Boolean = false) {
    EXPOSURE(R.string.ui_adjustment_exposure, -20.0..20.0, 2),
    OFFSET(R.string.ui_adjustment_offset, -0.5..0.5, 4),
    GAMMA(R.string.ui_adjustment_gamma, .01..9.99, 2, true);

    fun value(style: AdjustmentLayerModel): Double = when (this) {
        EXPOSURE -> style.exposure
        OFFSET -> style.offset
        GAMMA -> style.exposureGamma
    }

    fun assign(style: AdjustmentLayerModel, value: Double): AdjustmentLayerModel = when (this) {
        EXPOSURE -> style.withExposure(value = value)
        OFFSET -> style.withExposure(offsetValue = value)
        GAMMA -> style.withExposure(gammaValue = value)
    }

    fun parse(text: String): Double? = text.toDoubleOrNull()?.takeIf { it.isFinite() && it in range }
    fun format(value: Double): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    // Swift's rounded() uses nearest, with ties away from zero. Only slider changes
    // are quantized; exact text input and the stored Double are not rounded.
    fun roundSlider(value: Double): Double {
        val step = 10.0.pow(decimals)
        val bounded = value.coerceIn(range)
        val scaled = abs(bounded) * step
        val integer = floor(scaled)
        val magnitude = (integer + if (scaled - integer >= .5) 1.0 else 0.0) / step
        return (if (bounded < 0) -magnitude else magnitude).coerceIn(range)
    }
}

@Composable
internal fun ExposureEditor(draft: AdjustmentEditorDraft, update: (AdjustmentEditorDraft) -> Unit) {
    val ui = LocalContext.current
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposureParameter.entries.forEach { parameter ->
            val label = ui.getString(parameter.label)
            val value = parameter.value(draft.style)
            val text = draft.exposureInputs?.get(parameter.ordinal) ?: parameter.format(value)
            val invalid = parameter.parse(text) == null
            Column {
                OutlinedTextField(value = text, onValueChange = { update(draft.withExposureInput(parameter.ordinal, it)) },
                    enabled = !draft.commitInFlight, singleLine = true, isError = invalid,
                    // Decimal keyboards need not expose a minus key. These signed
                    // parameters also accept precise pasted scientific notation.
                    label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = ui.getString(R.string.ui_field_value, label) })
                if (invalid) Text(ui.getString(R.string.ui_exposure_valid_range,
                    parameter.range.start.toString(), parameter.range.endInclusive.toString()),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                val low = if (parameter.logarithmic) ln(parameter.range.start) else parameter.range.start
                val high = if (parameter.logarithmic) ln(parameter.range.endInclusive) else parameter.range.endInclusive
                Slider(value = (if (parameter.logarithmic) ln(value) else value).toFloat(),
                    valueRange = low.toFloat()..high.toFloat(), enabled = !draft.commitInFlight,
                    onValueChange = { position ->
                        if (position.isFinite()) {
                            focus.clearFocus()
                            val number = if (parameter.logarithmic) exp(position.toDouble()) else position.toDouble()
                            update(draft.withExposureSlider(parameter.ordinal, number))
                        }
                    }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                        contentDescription = label
                        stateDescription = parameter.format(value)
                        // Touch uses log space for Gamma; assistive actions and
                        // spoken values use the actual parameter, like the field.
                        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(),
                            parameter.range.start.toFloat()..parameter.range.endInclusive.toFloat())
                        if (draft.commitInFlight) disabled()
                        setProgress { requested ->
                            if (draft.commitInFlight || !requested.isFinite()) false else {
                                focus.clearFocus()
                                update(draft.withExposureSlider(parameter.ordinal, requested.toDouble()))
                                true
                            }
                        }
                    })
            }
        }
    }
}
