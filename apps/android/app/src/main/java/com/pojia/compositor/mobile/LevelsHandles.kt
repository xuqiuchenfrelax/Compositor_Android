package com.pojia.compositor.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** The desktop triangle positions are input values, not the Gamma parameter itself. */
internal enum class LevelsHandle(val parameter: LevelsParameter, val label: Int) {
    BLACK(LevelsParameter.BLACK, R.string.ui_levels_handle_black),
    GAMMA(LevelsParameter.GAMMA, R.string.ui_levels_handle_gamma),
    WHITE(LevelsParameter.WHITE, R.string.ui_levels_handle_white),
    OUTPUT_BLACK(LevelsParameter.OUTPUT_BLACK, R.string.ui_levels_handle_output_black),
    OUTPUT_WHITE(LevelsParameter.OUTPUT_WHITE, R.string.ui_levels_handle_output_white);

    fun position(range: AdjustmentLevelRange): Double = when (this) {
        GAMMA -> range.black + (range.white - range.black) * .5.pow(range.gamma)
        else -> parameter.value(range)
    }

    fun moved(range: AdjustmentLevelRange, xValue: Double): AdjustmentLevelRange {
        if (!xValue.isFinite()) return range
        val x = xValue.coerceIn(0.0, 255.0)
        val whole = floor(x)
        val rounded = if (x - whole >= .5) whole + 1.0 else whole
        return when (this) {
            BLACK -> range.copy(black = minOf(range.white - 1.0, rounded).coerceIn(0.0, 254.0))
            WHITE -> range.copy(white = maxOf(range.black + 1.0, rounded).coerceAtMost(255.0))
            GAMMA -> {
                val fraction = ((x - range.black) / (range.white - range.black)).coerceIn(.001, .999)
                range.copy(gamma = (ln(fraction) / ln(.5)).coerceIn(.1, 9.99))
            }
            OUTPUT_BLACK -> range.copy(outputBlack = rounded)
            OUTPUT_WHITE -> range.copy(outputWhite = rounded)
        }
    }

    /** Accessibility exposes the actual parameter, including Gamma, rather than track position. */
    fun valueMoved(range: AdjustmentLevelRange, value: Double): AdjustmentLevelRange {
        if (!value.isFinite()) return range
        return if (this == GAMMA) range.copy(gamma = value.coerceIn(parameter.range)) else moved(range, value)
    }
}

@Composable
internal fun LevelsHandles(draft: AdjustmentEditorDraft, output: Boolean, update: (AdjustmentEditorDraft) -> Unit) {
    val ui = LocalContext.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val latestDraft by rememberUpdatedState(draft)
    val latestUpdate by rememberUpdatedState(update)
    val handles = if (output) listOf(LevelsHandle.OUTPUT_BLACK, LevelsHandle.OUTPUT_WHITE)
        else listOf(LevelsHandle.BLACK, LevelsHandle.GAMMA, LevelsHandle.WHITE)
    val range = draft.style.ranges[draft.style.channel]
    val inset = with(density) { 24.dp.toPx() }
    val trackLabel = ui.getString(if (output) R.string.ui_levels_output_handles else R.string.ui_levels_input_handles)
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = trackLabel }) {
        val span = (constraints.maxWidth.toFloat() - inset * 2f).coerceAtLeast(1f)
        fun center(handle: LevelsHandle, value: AdjustmentLevelRange): Float =
            inset + (handle.position(value) / 255.0 * span).toFloat()
        fun move(handle: LevelsHandle, x: Float) {
            if (!x.isFinite() || latestDraft.commitInFlight) return
            focus.clearFocus()
            latestUpdate(latestDraft.withLevelsHandle(handle, (x.toDouble() - inset.toDouble()) / span.toDouble() * 255.0))
        }
        Canvas(Modifier.fillMaxSize().pointerInput(draft.submissionId, draft.style.channel, output, span, inset) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (latestDraft.commitInFlight) return@awaitEachGesture
                val activeRange = latestDraft.style.ranges[latestDraft.style.channel]
                // Later triangles win coincident hit tests, as in the desktop stack.
                val selected = handles.minWithOrNull(compareBy<LevelsHandle> { abs(center(it, activeRange) - down.position.x) }
                    .thenByDescending { it.ordinal }) ?: return@awaitEachGesture
                if (abs(center(selected, activeRange) - down.position.x) > inset) return@awaitEachGesture
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (event.type == PointerEventType.Release && !change.isConsumed && (dragging ||
                            (abs(change.position.x - down.position.x) <= viewConfiguration.touchSlop &&
                                abs(change.position.y - down.position.y) <= viewConfiguration.touchSlop))) {
                            move(selected, change.position.x)
                            change.consume()
                        }
                        break
                    }
                    if (!dragging) {
                        if (change.isConsumed) break
                        val dx = abs(change.position.x - down.position.x)
                        val dy = abs(change.position.y - down.position.y)
                        if (dy > viewConfiguration.touchSlop && dy > dx) break
                        if (dx <= viewConfiguration.touchSlop) continue
                        dragging = true
                    }
                    move(selected, change.position.x)
                    change.consume()
                }
            }
        }) {
            val alpha = if (draft.commitInFlight) .38f else 1f
            if (output) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White), inset, inset + span),
                topLeft = Offset(inset, 0f), size = androidx.compose.ui.geometry.Size(span, 10.dp.toPx()), alpha = alpha)
            else drawLine(Color.Gray.copy(alpha = alpha), Offset(inset, 8.dp.toPx()), Offset(inset + span, 8.dp.toPx()), 1.dp.toPx())
            handles.forEach { handle ->
                val x = center(handle, range)
                val y = 12.dp.toPx()
                val half = 7.dp.toPx()
                val triangle = Path().apply { moveTo(x, y); lineTo(x - half, y + 12.dp.toPx()); lineTo(x + half, y + 12.dp.toPx()); close() }
                val color = when (handle) {
                    LevelsHandle.BLACK, LevelsHandle.OUTPUT_BLACK -> Color.Black
                    LevelsHandle.GAMMA -> Color.Gray
                    else -> Color.White
                }
                drawPath(triangle, color.copy(alpha = alpha))
                drawPath(triangle, Color.Gray.copy(alpha = alpha), style = Stroke(1.dp.toPx()))
            }
        }
        handles.forEach { handle ->
            val parameter = handle.parameter
            val value = parameter.value(range)
            val name = ui.getString(handle.label)
            Box(Modifier.offset { IntOffset((center(handle, range) - inset).roundToInt(), 0) }.size(48.dp)
                .clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = parameter.format(value)
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(),
                        parameter.range.start.toFloat()..parameter.range.endInclusive.toFloat())
                    if (draft.commitInFlight) disabled()
                    setProgress { requested ->
                        if (!requested.isFinite() || latestDraft.commitInFlight) false else {
                            focus.clearFocus()
                            latestUpdate(latestDraft.withLevelsHandleValue(handle, requested.toDouble()))
                            true
                        }
                    }
                })
        }
    }
}
