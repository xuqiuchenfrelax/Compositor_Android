package com.pojia.compositor.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

internal enum class LevelsParameter(val label: Int, val range: ClosedFloatingPointRange<Double>, val decimals: Int) {
    BLACK(R.string.ui_adjustment_black, 0.0..254.0, 0),
    GAMMA(R.string.ui_adjustment_gamma, .1..9.99, 2),
    WHITE(R.string.ui_adjustment_white, 1.0..255.0, 0),
    OUTPUT_BLACK(R.string.ui_adjustment_output_black, 0.0..255.0, 0),
    OUTPUT_WHITE(R.string.ui_adjustment_output_white, 0.0..255.0, 0);

    fun value(value: AdjustmentLevelRange): Double = when (this) {
        BLACK -> value.black
        GAMMA -> value.gamma
        WHITE -> value.white
        OUTPUT_BLACK -> value.outputBlack
        OUTPUT_WHITE -> value.outputWhite
    }

    fun assign(value: AdjustmentLevelRange, number: Double): AdjustmentLevelRange = when (this) {
        BLACK -> value.copy(black = number)
        GAMMA -> value.copy(gamma = number)
        WHITE -> value.copy(white = number)
        OUTPUT_BLACK -> value.copy(outputBlack = number)
        OUTPUT_WHITE -> value.copy(outputWhite = number)
    }

    fun parse(text: String): Double? = text.toDoubleOrNull()?.takeIf { it.isFinite() && it in range }
    fun format(number: Double): String = String.format(Locale.ROOT, "%.${decimals}f", number)

    fun validInRow(inputs: List<String>): Boolean {
        if (parse(inputs[ordinal]) == null) return false
        if (this != BLACK && this != WHITE) return true
        val black = BLACK.parse(inputs[BLACK.ordinal]) ?: return false
        val white = WHITE.parse(inputs[WHITE.ordinal]) ?: return false
        return white >= black + 1.0
    }

    companion object {
        fun parseRow(inputs: List<String>): AdjustmentLevelRange? {
            if (inputs.size != entries.size || entries.any { !it.validInRow(inputs) }) return null
            return AdjustmentLevelRange(inputs[0].toDouble(), inputs[1].toDouble(), inputs[2].toDouble(),
                inputs[3].toDouble(), inputs[4].toDouble())
        }
    }
}

@Composable
internal fun LevelsEditor(draft: AdjustmentEditorDraft, update: (AdjustmentEditorDraft) -> Unit,
    levelsHistogram: LevelsHistogramState = LevelsHistogramState.Loading,
    onRetryLevelsHistogram: () -> Unit = {}) {
    val ui = LocalContext.current
    val focus = LocalFocusManager.current
    val style = draft.style
    val current = style.ranges[style.channel]
    val raw = draft.levelsInputs?.get(style.channel)
        ?: LevelsParameter.entries.map { it.value(current).toString() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(R.string.ui_adjustment_rgb, R.string.ui_adjustment_red, R.string.ui_adjustment_green,
                R.string.ui_adjustment_blue).forEachIndexed { index, label ->
                TextButton(enabled = !draft.commitInFlight,
                    onClick = { update(draft.copy(style = style.withLevels(channelValue = index))) }) {
                    Text(if (style.channel == index) "✓ ${ui.getString(label)}" else ui.getString(label))
                }
            }
        }
        LevelsHistogram(levelsHistogram, style.channel, !draft.commitInFlight, onRetryLevelsHistogram)
        LevelsHandles(draft, output = false, update = update)
        Text(ui.getString(R.string.ui_levels_auto), style = MaterialTheme.typography.labelSmall)
        LevelsAutoMode.entries.forEach { mode ->
            TextButton(enabled = !draft.commitInFlight && levelsHistogram.bins != null, onClick = {
                if (!draft.commitInFlight) levelsHistogram.bins?.let { bins ->
                    focus.clearFocus()
                    update(draft.withAutoLevels(mode, bins))
                }
            }) { Text(ui.getString(mode.label)) }
        }
        if (draft.hasInvalidLevelsInput && LevelsParameter.parseRow(raw) != null)
            Text(ui.getString(R.string.ui_levels_other_channel_invalid), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
        LevelsParameter.entries.forEach { parameter ->
            val label = ui.getString(parameter.label)
            val value = parameter.value(current)
            val invalid = !parameter.validInRow(raw)
            Column {
                OutlinedTextField(value = raw[parameter.ordinal],
                    onValueChange = { update(draft.withLevelsInput(parameter.ordinal, it)) },
                    enabled = !draft.commitInFlight, singleLine = true, isError = invalid,
                    label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = ui.getString(R.string.ui_field_value, label) })
                if (invalid) Text(ui.getString(R.string.ui_levels_valid_range,
                    parameter.range.start.toString(), parameter.range.endInclusive.toString()),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Slider(value = value.toFloat(), valueRange = parameter.range.start.toFloat()..parameter.range.endInclusive.toFloat(),
                    enabled = !draft.commitInFlight,
                    onValueChange = { requested ->
                        if (requested.isFinite()) {
                            focus.clearFocus()
                            update(draft.withLevelsSlider(parameter.ordinal, requested.toDouble()))
                        }
                    }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                        contentDescription = label
                        stateDescription = parameter.format(value)
                        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(),
                            parameter.range.start.toFloat()..parameter.range.endInclusive.toFloat())
                        if (draft.commitInFlight) disabled()
                        setProgress { requested ->
                            if (draft.commitInFlight || !requested.isFinite()) false else {
                                focus.clearFocus()
                                update(draft.withLevelsSlider(parameter.ordinal, requested.toDouble()))
                                true
                            }
                        }
                    })
            }
        }
        LevelsHandles(draft, output = true, update = update)
        Text(ui.getString(R.string.ui_levels_sample), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            LevelsSampleMode.entries.forEach { mode ->
                val label = ui.getString(mode.label)
                TextButton(enabled = !draft.commitInFlight, onClick = {
                    focus.clearFocus()
                    update(draft.toggleLevelsSample(mode))
                }, modifier = Modifier.semantics {
                    contentDescription = ui.getString(R.string.ui_levels_sample_picker, label)
                    selected = draft.levelsSampleMode == mode
                }) {
                    Text(if (draft.levelsSampleMode == mode) "✓ $label" else label)
                }
            }
        }
        draft.levelsSampleMode?.let { mode ->
            Text(ui.getString(R.string.ui_levels_sample_help, ui.getString(mode.label)),
                style = MaterialTheme.typography.labelSmall)
        }
        TextButton(enabled = !draft.commitInFlight, onClick = {
            focus.clearFocus()
            update(draft.resetLevels())
        }) { Text(ui.getString(R.string.ui_levels_reset)) }
    }
}
