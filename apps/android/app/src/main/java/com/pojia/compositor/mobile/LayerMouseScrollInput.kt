package com.pojia.compositor.mobile

import android.view.PointerIcon as AndroidPointerIcon
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalWindowInfo

/** Row navigation owns a middle-button sequence without changing the editing context. */
internal class LayerMouseScrollInput(private val scrollBy: (Float) -> Unit) {
    internal enum class Action { PRESS, MOVE, RELEASE, OTHER }
    internal data class Sample(val action: Action, val pointerId: Long, val mouse: Boolean,
        val pointerCount: Int, val x: Float, val y: Float, val pressed: Boolean,
        val wasPressed: Boolean, val middle: Boolean, val otherButton: Boolean,
        val anyPressed: Boolean = pressed, val consumed: Boolean = false)

    var active by mutableStateOf(false)
        private set
    private var swallowing = false
    private var pointerId = 0L
    private var lastY = 0f

    fun cancel() {
        swallowing = swallowing || active
        active = false
    }

    fun route(sample: Sample, focused: Boolean = true): Boolean {
        // A fresh single-pointer press begins a new stream even if cancellation hid the old UP.
        // Held movement, another pointer and button chords still belong to the swallowed stream.
        if (swallowing && sample.action == Action.PRESS && sample.pointerCount == 1 &&
            sample.pressed && !sample.wasPressed) swallowing = false
        if (!active && !swallowing) {
            val middleDown = sample.action == Action.PRESS && sample.pressed && !sample.wasPressed &&
                sample.mouse && sample.pointerCount == 1 && sample.middle && !sample.otherButton && !sample.consumed
            if (!middleDown) return false
            pointerId = sample.pointerId
            lastY = sample.y
            active = focused && sample.x.isFinite() && sample.y.isFinite()
            swallowing = !active
            return true
        }

        // The remaining events of a canceled gesture stay consumed, including its final UP.
        if (!sample.anyPressed) {
            active = false
            swallowing = false
            return true
        }
        if (!focused || !sample.mouse || sample.pointerCount != 1 || sample.pointerId != pointerId ||
            !sample.pressed || !sample.middle || sample.otherButton || sample.consumed ||
            !sample.x.isFinite() || !sample.y.isFinite()) cancel()
        // A captured held pointer can cross a viewport edge with an unclassified event.
        if (active && (sample.action == Action.MOVE || sample.action == Action.OTHER)) {
            val delta = lastY - sample.y
            lastY = sample.y
            if (delta.isFinite()) {
                if (delta != 0f) scrollBy(delta)
            } else cancel()
        }
        return true
    }
}

@Composable
internal fun Modifier.layerMouseScrolling(scrollState: ScrollState): Modifier {
    val input = remember(scrollState) { LayerMouseScrollInput { scrollState.dispatchRawDelta(it) } }
    val focused = LocalWindowInfo.current.isWindowFocused
    val grabbing = remember { PointerIcon(AndroidPointerIcon.TYPE_GRABBING) }
    val pointer = pointerInput(input, focused) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // Ordinary wheel scrolling remains with verticalScroll's native handler.
                    if (event.type == PointerEventType.Scroll) continue
                    val first = event.changes.firstOrNull()
                    val buttons = event.buttons
                    val sample = LayerMouseScrollInput.Sample(
                        action = when (event.type) {
                            PointerEventType.Press -> LayerMouseScrollInput.Action.PRESS
                            // Compose reports Enter/Exit when a captured drag crosses the viewport edge.
                            PointerEventType.Move, PointerEventType.Enter, PointerEventType.Exit -> LayerMouseScrollInput.Action.MOVE
                            PointerEventType.Release -> LayerMouseScrollInput.Action.RELEASE
                            else -> LayerMouseScrollInput.Action.OTHER
                        },
                        pointerId = first?.id?.value ?: 0L,
                        mouse = first != null && event.changes.all { it.type == PointerType.Mouse },
                        pointerCount = event.changes.size,
                        x = first?.position?.x ?: Float.NaN,
                        y = first?.position?.y ?: Float.NaN,
                        pressed = first?.pressed == true,
                        wasPressed = first?.previousPressed == true,
                        middle = buttons.isTertiaryPressed,
                        otherButton = buttons.isPrimaryPressed || buttons.isSecondaryPressed ||
                            buttons.isBackPressed || buttons.isForwardPressed,
                        anyPressed = event.changes.any { it.pressed },
                        consumed = event.changes.any { it.isConsumed },
                    )
                    if (input.route(sample, focused)) event.changes.forEach { it.consume() }
                }
            }
        } finally {
            input.cancel()
        }
    }
    return if (input.active) pointer.pointerHoverIcon(grabbing, overrideDescendants = true) else pointer
}
