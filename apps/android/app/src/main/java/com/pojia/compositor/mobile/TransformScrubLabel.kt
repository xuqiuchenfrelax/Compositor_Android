package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon as AndroidPointerIcon
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Keep screen coordinates fixed even if focus or the scrolling header changes local coordinates. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun TransformScrubLabel(label: String, field: TransformScrubField, enabled: Boolean,
    begin: (TransformScrubField) -> MoveScrubSession?,
    update: (MoveScrubSession, Double) -> Unit, finish: (MoveScrubSession, Boolean) -> Unit) {
    val ui = LocalContext.current
    val available = rememberUpdatedState(enabled)
    val start = rememberUpdatedState(begin)
    val change = rememberUpdatedState(update)
    val end = rememberUpdatedState(finish)
    val density = rememberUpdatedState(LocalDensity.current.density)
    val focused = LocalWindowInfo.current.isWindowFocused
    val view = LocalView.current
    val intercept = remember { RequestDisallowInterceptTouchEvent() }
    val input = remember(field, view) { TransformScrubPointerInput(field,
        { available.value }, { density.value }, { start.value(it) },
        { session, dx -> change.value(session, dx) }, { session, cancelled -> end.value(session, cancelled) },
        { intercept(it) }, { view.requestUnbufferedDispatch(it) }) }
    DisposableEffect(input, focused) {
        if (!focused) input.cancel()
        onDispose { input.cancel() }
    }
    Text(label, fontSize = 12.sp, modifier = Modifier
        .semantics { contentDescription = ui.getString(R.string.ui_scrub_move, label); if (!enabled) disabled() }
        // A label can reach the screen edge when its parameter row scrolls.
        .then(if (enabled) Modifier.systemGestureExclusion() else Modifier)
        .pointerHoverIcon(if (enabled) PointerIcon(AndroidPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW) else PointerIcon.Default)
        .pointerInteropFilter(requestDisallowInterceptTouchEvent = intercept, onTouchEvent = input::route)
        .widthIn(min = 20.dp).heightIn(min = 40.dp).wrapContentSize(Alignment.Center))
}

/** Android cancellation is distinct from release; a canceled label must never publish an undo step. */
private class TransformScrubPointerInput(private val field: TransformScrubField,
    private val enabled: () -> Boolean, private val density: () -> Float,
    private val begin: (TransformScrubField) -> MoveScrubSession?,
    private val update: (MoveScrubSession, Double) -> Unit,
    private val finish: (MoveScrubSession, Boolean) -> Unit, private val intercept: (Boolean) -> Unit,
    private val unbuffered: (MotionEvent) -> Unit) {
    private var tracking = false
    private var rejected = false
    private var pointer = -1
    private var downX = 0f
    private var downY = 0f
    private var points = 1f
    private var session: MoveScrubSession? = null
    private var traceCount = 0

    fun cancel() {
        val captured = session
        session = null; tracking = false; rejected = false; pointer = -1
        intercept(false)
        captured?.let { finish(it, true) }
    }

    fun route(event: MotionEvent): Boolean {
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            if (!enabled() || event.pointerCount != 1 || (mouse &&
                event.buttonState != MotionEvent.BUTTON_PRIMARY)) return false
            tracking = true; pointer = event.getPointerId(0)
            traceCount = 0
            downX = event.rawX; downY = event.rawY; points = density()
            if (!points.isFinite() || points <= 0) { cancel(); return false }
            // Reserve this label's stream. Other parts of the row retain horizontal scrolling.
            intercept(true)
            // Numeric steps use measured pointer coordinates rather than frame-resampled prediction.
            unbuffered(event)
            trace(event, 0.0)
            return true
        }
        if (!tracking) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            trace(event, ((event.rawX - downX) / points).toDouble())
            cancel(); return true
        }
        if (event.pointerCount != 1 || event.getPointerId(0) != pointer ||
            (mouse && event.actionMasked != MotionEvent.ACTION_UP && event.buttonState != MotionEvent.BUTTON_PRIMARY)) {
            cancel(); return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            trace(event, ((event.rawX - downX) / points).toDouble())
            val captured = session
            session = null; tracking = false; rejected = false; pointer = -1
            intercept(false)
            captured?.let { finish(it, false) }
            return true
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE || rejected) return true
        val dx = ((event.rawX - downX) / points).toDouble()
        val dy = ((event.rawY - downY) / points).toDouble()
        if (session == null) {
            if (abs(dy) >= 1 && abs(dy) > abs(dx)) { rejected = true; return true }
            if (abs(dx) < 1) return true
            session = begin(field)
            if (session == null) { rejected = true; return true }
        }
        trace(event, dx)
        update(checkNotNull(session), dx)
        return true
    }

    private fun trace(event: MotionEvent, dx: Double) {
        if (BuildConfig.DEBUG && (traceCount++ < 16 || event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL)) android.util.Log.d("CompositorScrub",
            "field=$field action=${event.actionMasked} rawX=${event.rawX} downX=$downX density=$points dx=$dx start=${session?.numeric?.valueAtDown} history=${event.historySize}")
    }
}
