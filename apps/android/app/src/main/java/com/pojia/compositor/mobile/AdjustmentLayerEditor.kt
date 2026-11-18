package com.pojia.compositor.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class AdjustmentEditorDraft(val style: AdjustmentLayerModel, val layerId: Long? = null,
    val submissionId: String = java.util.UUID.randomUUID().toString(), val commitInFlight: Boolean = false,
    val exposureInputs: List<String>? = null, val levelsInputs: List<List<String>>? = null,
    val levelsSampleMode: LevelsSampleMode? = null, val baseRevision: Long = -1L) {
    init {
        require(submissionId.isNotBlank())
        require(exposureInputs == null || (exposureInputs.size == ExposureParameter.entries.size &&
            exposureInputs.all { it.length <= 64 }))
        require(levelsInputs == null || (levelsInputs.size == 4 && levelsInputs.all { row ->
            row.size == LevelsParameter.entries.size && row.all { it.length <= 64 } }))
    }

    val hasInvalidExposureInput: Boolean get() = style.kind == AdjustmentKind.EXPOSURE &&
        exposureInputs?.let { inputs -> ExposureParameter.entries.any { it.parse(inputs[it.ordinal]) == null } } == true

    val hasInvalidLevelsInput: Boolean get() = style.kind == AdjustmentKind.LEVELS &&
        levelsInputs?.any { LevelsParameter.parseRow(it) == null } == true

    private fun levelInputs(): List<List<String>> = levelsInputs ?: style.ranges.map { range ->
        LevelsParameter.entries.map { it.value(range).toString() }
    }

    fun withLevelsInput(index: Int, text: String): AdjustmentEditorDraft {
        val parameter = LevelsParameter.entries[index]
        if (text.length > 64) return this
        val inputs = levelInputs().toMutableList()
        val row = inputs[style.channel].toMutableList().also { it[index] = text }
        inputs[style.channel] = row
        val complete = LevelsParameter.parseRow(row)
        val number = parameter.parse(text)
        val single = number?.takeIf { parameter.validInRow(row) }?.let {
            parameter.assign(style.ranges[style.channel], it)
        }?.takeIf { candidate -> runCatching { candidate.validate() }.isSuccess }
        val range = complete ?: single
        return copy(style = range?.let { changed -> style.withLevels(rangeValues = style.ranges.toMutableList().also {
            it[style.channel] = changed
        }) } ?: style, levelsInputs = inputs)
    }

    fun withLevelsSlider(index: Int, value: Double): AdjustmentEditorDraft {
        val parameter = LevelsParameter.entries[index]
        if (!value.isFinite()) return this
        val current = style.ranges[style.channel]
        val bounded = value.coerceIn(parameter.range)
        val next = when (parameter) {
            LevelsParameter.BLACK -> bounded.coerceAtMost(current.white - 1.0)
            LevelsParameter.WHITE -> bounded.coerceAtLeast(current.black + 1.0)
            else -> bounded
        }
        val inputs = levelInputs().toMutableList()
        val row = inputs[style.channel].toMutableList().also { it[index] = next.toString() }
        inputs[style.channel] = row
        val range = LevelsParameter.parseRow(row) ?: parameter.assign(current, next)
        return copy(style = style.withLevels(rangeValues = style.ranges.toMutableList().also {
            it[style.channel] = range
        }), levelsInputs = inputs)
    }

    fun withLevelsHandle(handle: LevelsHandle, xValue: Double): AdjustmentEditorDraft {
        if (commitInFlight || style.kind != AdjustmentKind.LEVELS || !xValue.isFinite()) return this
        return withLevelsHandleRange(handle, handle.moved(style.ranges[style.channel], xValue))
    }

    fun withLevelsHandleValue(handle: LevelsHandle, value: Double): AdjustmentEditorDraft {
        if (commitInFlight || style.kind != AdjustmentKind.LEVELS || !value.isFinite()) return this
        return withLevelsHandleRange(handle, handle.valueMoved(style.ranges[style.channel], value))
    }

    private fun withLevelsHandleRange(handle: LevelsHandle, range: AdjustmentLevelRange): AdjustmentEditorDraft {
        val inputs = levelInputs().toMutableList()
        val row = inputs[style.channel].toMutableList().also {
            it[handle.parameter.ordinal] = handle.parameter.value(range).toString()
        }
        inputs[style.channel] = row
        val synchronized = LevelsParameter.parseRow(row) ?: range
        return copy(style = style.withLevels(rangeValues = style.ranges.toMutableList().also {
            it[style.channel] = synchronized
        }), levelsInputs = inputs)
    }

    fun resetLevels(): AdjustmentEditorDraft = copy(
        style = style.withLevels(channelValue = 0, rangeValues = List(4) { AdjustmentLevelRange() }),
        levelsInputs = null, levelsSampleMode = null)

    fun withAutoLevels(mode: LevelsAutoMode, histogram: List<List<Double>>): AdjustmentEditorDraft {
        if (commitInFlight || style.kind != AdjustmentKind.LEVELS) return this
        return copy(style = style.withLevels(channelValue = 0, rangeValues = mode.settings(histogram)),
            levelsInputs = null, levelsSampleMode = null)
    }

    fun toggleLevelsSample(mode: LevelsSampleMode): AdjustmentEditorDraft {
        if (commitInFlight || style.kind != AdjustmentKind.LEVELS) return this
        return copy(levelsSampleMode = mode.takeUnless { it == levelsSampleMode })
    }

    fun withLevelsSample(rgb: List<Double>): AdjustmentEditorDraft {
        val mode = levelsSampleMode ?: return this
        if (commitInFlight || style.kind != AdjustmentKind.LEVELS || rgb.size != 3 ||
            rgb.any { !it.isFinite() || it !in 0.0..1.0 }) return this
        return copy(style = style.withLevels(rangeValues = mode.settings(style.ranges, rgb)), levelsInputs = null)
    }

    fun withExposureInput(index: Int, text: String): AdjustmentEditorDraft {
        val parameter = ExposureParameter.entries[index]
        if (text.length > 64) return this
        val input = text
        val inputs = exposureInputs ?: ExposureParameter.entries.map { it.format(it.value(style)) }
        val value = parameter.parse(input)
        return copy(style = value?.let { parameter.assign(style, it) } ?: style,
            exposureInputs = inputs.toMutableList().also { it[index] = input })
    }

    fun withExposureSlider(index: Int, value: Double): AdjustmentEditorDraft {
        val parameter = ExposureParameter.entries[index]
        if (!value.isFinite()) return this
        val next = parameter.roundSlider(value)
        val inputs = exposureInputs ?: ExposureParameter.entries.map { it.format(it.value(style)) }
        return copy(style = parameter.assign(style, next),
            exposureInputs = inputs.toMutableList().also { it[index] = parameter.format(next) })
    }
}

internal val adjustmentEditorDraftSaver: Saver<AdjustmentEditorDraft?, Any> = Saver(
    save = { draft -> draft?.let { listOf(it.style.toMacJson().toString(), it.layerId ?: 0L, it.submissionId,
        it.exposureInputs ?: emptyList<String>(), it.levelsInputs ?: emptyList<List<String>>(),
        it.levelsSampleMode?.name ?: "", it.baseRevision) } },
    restore = { saved ->
        val fields = saved as? List<*>
        if (fields == null || fields.size !in 2..7) null else runCatching {
            AdjustmentEditorDraft(AdjustmentLayerModel.fromMacJson(JSONObject(fields[0] as String)),
                (fields[1] as Number).toLong().takeIf { it != 0L },
                if (fields.size >= 3) fields[2] as String else java.util.UUID.randomUUID().toString(),
                exposureInputs = if (fields.size >= 4) (fields[3] as List<*>).map { it as String }.takeIf { it.isNotEmpty() } else null,
                levelsInputs = if (fields.size >= 5) (fields[4] as List<*>).map { row ->
                    (row as List<*>).map { it as String }
                }.takeIf { it.isNotEmpty() } else null,
                levelsSampleMode = if (fields.size >= 6) (fields[5] as String).takeIf { it.isNotEmpty() }
                    ?.let(LevelsSampleMode::valueOf) else null,
                baseRevision = if (fields.size >= 7) (fields[6] as Number).toLong() else -1L)
        }.getOrNull()
    })

@Composable
internal fun AdjustmentLayerEditor(draft: AdjustmentEditorDraft, onDraftChanged: (AdjustmentEditorDraft) -> Unit,
    onCommit: (AdjustmentLayerModel) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier,
    levelsHistogram: LevelsHistogramState = LevelsHistogramState.Loading,
    onRetryLevelsHistogram: () -> Unit = {}, levelsSamplingBusy: Boolean = false, previewFailed: Boolean = false) {
    val ui = LocalContext.current
    val style = draft.style
    key(draft.submissionId) {
        var parametersValid by remember(style.kind, draft.layerId) { mutableStateOf(true) }
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(ui.getString(R.string.ui_adjustment_layer), style = MaterialTheme.typography.titleMedium)
            if (previewFailed) Text(ui.getString(R.string.ui_adjustment_preview_unavailable),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (draft.commitInFlight || levelsSamplingBusy) Text(ui.getString(R.string.ui_working), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AdjustmentKind.entries.forEach { kind ->
                    TextButton(enabled = !draft.commitInFlight, onClick = {
                        onDraftChanged(draft.copy(style = style.withKind(kind), levelsSampleMode = null))
                    }) {
                        Text(if (style.kind == kind) "✓ ${ui.getString(kind.label)}" else ui.getString(kind.label))
                    }
                }
            }
            when (style.kind) {
                AdjustmentKind.VIGNETTE -> VignetteEditor(style.vignette) { onDraftChanged(draft.copy(style = style.withVignette(it))) }
                AdjustmentKind.INVERT -> Text(ui.getString(R.string.ui_adjustment_invert_description))
                AdjustmentKind.EXPOSURE -> ExposureEditor(draft, onDraftChanged)
                AdjustmentKind.HUE_SATURATION -> HueSaturationEditor(style.hsv) { onDraftChanged(draft.copy(style = style.withHueSaturation(it))) }
                AdjustmentKind.GRADIENT_MAP -> GradientMapEditor(style.gradientMap) { onDraftChanged(draft.copy(style = style.withGradientMap(it))) }
                AdjustmentKind.GRAIN -> GrainEditor(style.grain, { onDraftChanged(draft.copy(style = style.withGrain(it))) }, { parametersValid = it })
                AdjustmentKind.ADD_NOISE -> {
                    AdjustmentSlider(ui.getString(R.string.ui_adjustment_noise_amount), style.noiseAmount, .1..400.0) { value ->
                        onDraftChanged(draft.copy(style = style.withNoise(amount = value)))
                    }
                    AdjustmentToggle(ui.getString(R.string.ui_adjustment_noise_gaussian), style.noiseGaussian) { value ->
                        onDraftChanged(draft.copy(style = style.withNoise(gaussian = value)))
                    }
                    AdjustmentToggle(ui.getString(R.string.ui_adjustment_noise_monochromatic), style.noiseMonochromatic) { value ->
                        onDraftChanged(draft.copy(style = style.withNoise(monochromatic = value)))
                    }
                    Text(ui.getString(R.string.ui_adjustment_noise_seed, style.noiseSeed), style = MaterialTheme.typography.bodySmall)
                }
                AdjustmentKind.CURVES -> AdjustmentCurvesEditor(style) { onDraftChanged(draft.copy(style = it)) }
                AdjustmentKind.BLACK_WHITE -> {
                    val labels = listOf(R.string.ui_adjustment_red, R.string.ui_adjustment_yellow, R.string.ui_adjustment_green,
                        R.string.ui_adjustment_cyan, R.string.ui_adjustment_blue, R.string.ui_adjustment_magenta)
                    labels.forEachIndexed { index, label ->
                        AdjustmentSlider(ui.getString(label), style.blackWhite[index], -200.0..300.0) { value ->
                            onDraftChanged(draft.copy(style = style.withBlackWhite(style.blackWhite.toMutableList().also { it[index] = value })))
                        }
                    }
                    AdjustmentToggle(ui.getString(R.string.ui_adjustment_tint), style.tint) {
                        onDraftChanged(draft.copy(style = style.withBlackWhite(tintValue = it)))
                    }
                    if (style.tint) {
                        listOf(R.string.ui_adjustment_tint_hue, R.string.ui_adjustment_tint_saturation).forEachIndexed { index, label ->
                            AdjustmentSlider(ui.getString(label), style.blackWhite[index + 6], if (index == 0) 0.0..360.0 else 0.0..100.0) { value ->
                                onDraftChanged(draft.copy(style = style.withBlackWhite(style.blackWhite.toMutableList().also { it[index + 6] = value })))
                            }
                        }
                    }
                }
                AdjustmentKind.COLOR_BALANCE -> {
                    val tones = listOf(R.string.ui_adjustment_shadows, R.string.ui_adjustment_midtones, R.string.ui_adjustment_highlights)
                    val axes = listOf(R.string.ui_adjustment_cyan_red, R.string.ui_adjustment_magenta_green, R.string.ui_adjustment_yellow_blue)
                    tones.forEachIndexed { tone, title ->
                        Text(ui.getString(title), style = MaterialTheme.typography.titleSmall)
                        axes.forEachIndexed { axis, label ->
                            val index = tone * 3 + axis
                            AdjustmentSlider(ui.getString(label), style.colorBalance[index], -100.0..100.0) { value ->
                                onDraftChanged(draft.copy(style = style.withColorBalance(style.colorBalance.toMutableList().also { it[index] = value })))
                            }
                        }
                    }
                    AdjustmentToggle(ui.getString(R.string.ui_adjustment_preserve_luminosity), style.preserveLuminosity) {
                        onDraftChanged(draft.copy(style = style.withColorBalance(preserve = it)))
                    }
                }
                AdjustmentKind.LEVELS -> LevelsEditor(draft, onDraftChanged, levelsHistogram, onRetryLevelsHistogram)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !draft.commitInFlight, onClick = onCancel) { Text(ui.getString(R.string.ui_cancel)) }
                Button(enabled = parametersValid && !levelsSamplingBusy && !draft.hasInvalidExposureInput && !draft.hasInvalidLevelsInput && !draft.commitInFlight,
                    onClick = { if (parametersValid && !levelsSamplingBusy && !draft.hasInvalidExposureInput && !draft.hasInvalidLevelsInput && !draft.commitInFlight) onCommit(style) }) { Text(ui.getString(R.string.ui_apply)) }
            }
        }
    }
}

@Composable
internal fun AdjustmentSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>,
    onValue: (Double) -> Unit) {
    var draft by remember(label) { mutableStateOf(value.toFloat()) }
    LaunchedEffect(value) { draft = value.toFloat() }
    Column {
        Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.labelMedium)
        Slider(value = draft, onValueChange = { draft = it; onValue(it.toDouble().coerceIn(range)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}

@Composable
internal fun AdjustmentToggle(label: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Checkbox, onValueChange = onValue),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = value, onCheckedChange = null)
        Text(label)
    }
}
