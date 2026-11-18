package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.PointerIcon
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** Android wheel-unit policy, existing middle ownership, and real GLES viewport geometry. */
internal fun mouseWheelInputSmoke(instrumentation: Instrumentation): String {
    val down = SystemClock.uptimeMillis(); var time = 0L
    fun event(action: Int = MotionEvent.ACTION_SCROLL, x: Float = 100f, y: Float = 500f,
        horizontal: Float = 0f, vertical: Float = 0f, modifiers: Int = 0, buttons: Int = 0,
        source: Int = InputDevice.SOURCE_MOUSE, tool: Int = MotionEvent.TOOL_TYPE_MOUSE, count: Int = 1): MotionEvent =
        MotionEvent.obtain(down, down + time++, action, count,
            Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = tool } },
            Array(count) { MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = 1f
                setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal); setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
            } }, modifiers, buttons, 1f, 1f, 0, 0, source, 0)
    data class Zoom(val scale: Double, val x: Double, val y: Double)
    var scale = 2.0; var density = 1.0
    val zooms = mutableListOf<Zoom>(); val pans = mutableListOf<Pair<Double, Double>>()
    val wheel = MouseWheelInput({ scale }, { density }, { value, x, y -> scale = value; zooms.add(Zoom(value, x, y)) }) { dx, dy -> pans.add(dx to dy) }
    fun route(value: MotionEvent, allowed: Boolean = true): Boolean = try { wheel.route(value, allowed) } finally { value.recycle() }
    for (units in listOf(1.0, 2.625, 3.0)) {
        density = units
        for (modifier in listOf(0, KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON)) {
            check(route(event(horizontal = .25f, vertical = -.5f, modifiers = modifier)))
            check(pans.last() == (3 * units to -6 * units)) { "Raw fractional axes must map to 12dp without a Shift swap" }
        }
    }
    check(zooms.isEmpty()) { "Control alone incorrectly selected wheel zoom" }
    for (modifier in listOf(KeyEvent.META_META_ON, KeyEvent.META_ALT_ON, KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON)) {
        scale = 2.0
        check(route(event(x = 123f, y = 267f, horizontal = 99f, vertical = 1f, modifiers = modifier)))
        check(zooms.last() == Zoom(2 * exp(-.015), 123.0, 267.0)) { "Positive V must decrease zoom at the current event anchor" }
        check(route(event(x = 321f, y = 456f, vertical = -2f, modifiers = modifier)))
        check(zooms.last() == Zoom(2 * exp(-.015) * exp(.03), 321.0, 456.0))
        val count = zooms.size
        check(route(event(horizontal = 3f, modifiers = modifier))); check(zooms.size == count)
    }
    val zeroZooms = zooms.size; val zeroPans = pans.size
    check(route(event())); check(route(event(vertical = 2f), false))
    for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
        check(route(event(vertical = value))); check(route(event(horizontal = value, modifiers = KeyEvent.META_ALT_ON)))
        check(route(event(x = value, vertical = 1f)))
    }
    density = Double.NaN; check(route(event(vertical = 1f))); density = 0.0; check(route(event(vertical = 1f)))
    scale = Double.NaN; check(route(event(vertical = 1f, modifiers = KeyEvent.META_ALT_ON)))
    check(zooms.size == zeroZooms && pans.size == zeroPans)
    density = 1.0; scale = 31.0
    route(event(vertical = -1000f, modifiers = KeyEvent.META_ALT_ON)); check(scale == 32.0)
    route(event(vertical = 1f, modifiers = KeyEvent.META_ALT_ON)); check(scale == 32 * exp(-.015)) { "Wheel clamp reversal must use the current clamped scale" }
    val beforeOverflow = scale; val beforeCount = zooms.size
    val overflow = event(vertical = -100000f, modifiers = KeyEvent.META_ALT_ON)
    check(overflow.x.isFinite() && overflow.y.isFinite() && overflow.getAxisValue(MotionEvent.AXIS_VSCROLL).isFinite())
    route(overflow); check(scale == beforeOverflow && zooms.size == beforeCount)
    route(event(vertical = 1f, modifiers = KeyEvent.META_ALT_ON)); check(scale == beforeOverflow * exp(-.015))
    val underflow = event(vertical = 100000f, modifiers = KeyEvent.META_META_ON)
    check(underflow.x.isFinite() && underflow.y.isFinite() && underflow.getAxisValue(MotionEvent.AXIS_VSCROLL).isFinite())
    route(underflow); check(scale == .001)
    route(event(vertical = -1f, modifiers = KeyEvent.META_META_ON)); check(scale == .001 * exp(.015))
    for (value in listOf(event(action = MotionEvent.ACTION_HOVER_MOVE), event(source = InputDevice.SOURCE_STYLUS, tool = MotionEvent.TOOL_TYPE_STYLUS),
        event(source = InputDevice.SOURCE_ROTARY_ENCODER, tool = MotionEvent.TOOL_TYPE_UNKNOWN), event(count = 2))) check(!route(value))

    val cursors = mutableListOf<Boolean?>()
    val middle = MousePanInput({ scale }, { density }, { value, x, y -> scale = value; zooms.add(Zoom(value, x, y)) }, { cursors.add(it) }) { dx, dy -> pans.add(dx to dy) }
    fun middleEvent(action: Int, x: Float = 100f, y: Float = 500f, modifiers: Int = KeyEvent.META_CTRL_ON) {
        val value = event(action, x, y, modifiers = modifiers, buttons = if (action == MotionEvent.ACTION_UP) 0 else MotionEvent.BUTTON_TERTIARY)
        try { check(middle.route(value, true).consumed) } finally { value.recycle() }
    }
    scale = 1.0; density = 1.0
    middleEvent(MotionEvent.ACTION_DOWN); middleEvent(MotionEvent.ACTION_MOVE, y = 400f); check(scale == 2.0)
    val cursorCount = cursors.size
    route(event(x = 20f, y = 30f, vertical = -20f, modifiers = KeyEvent.META_ALT_ON))
    check(scale == 2 * exp(.3) && cursors.size == cursorCount)
    middleEvent(MotionEvent.ACTION_MOVE, y = 420f)
    check(zooms.last() == Zoom(2.0.pow(.8), 100.0, 500.0) && cursors.last() == false)
    middleEvent(MotionEvent.ACTION_UP); check(cursors.last() == null)
    middleEvent(MotionEvent.ACTION_DOWN, modifiers = 0); middleEvent(MotionEvent.ACTION_MOVE, 110f, 510f, 0)
    route(event(horizontal = .25f, vertical = -.5f))
    middleEvent(MotionEvent.ACTION_MOVE, 120f, 520f, 0); check(pans.last() == (10.0 to 10.0))
    middleEvent(MotionEvent.ACTION_UP)

    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val editor = NativeEditor(128, addDefaultLayers = false); val failures = CopyOnWriteArrayList<String>()
    lateinit var view: CanvasView; lateinit var overlay: FrameLayout; var attached = false; var publications = 0
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    fun drain() { val latch = CountDownLatch(1); view.queueEvent { latch.countDown() }; check(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync() }
    fun dispatch(value: MotionEvent) {
        try { instrumentation.runOnMainSync { check(if (value.actionMasked == MotionEvent.ACTION_SCROLL) view.dispatchGenericMotionEvent(value) else view.dispatchTouchEvent(value)) } }
        finally { value.recycle() }
        drain()
    }
    data class Marker(val x: Double, val y: Double, val width: Int, val height: Int)
    fun marker(): Marker? {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val latch = CountDownLatch(1); var result = PixelCopy.ERROR_UNKNOWN
            instrumentation.runOnMainSync { PixelCopy.request(view, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
            check(latch.await(10, TimeUnit.SECONDS)); if (result != PixelCopy.SUCCESS) return null
            val values = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            values.forEachIndexed { index, color -> if ((color ushr 16 and 255) > 220 && (color ushr 8 and 255) < 25 && (color and 255) < 25) {
                val x = index % bitmap.width; val y = index / bitmap.width; left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            } }
            return if (right >= left) Marker((left + right) / 2.0, (top + bottom) / 2.0, right - left + 1, bottom - top + 1) else null
        } finally { bitmap.recycle() }
    }
    fun pixels(): ByteArray { val buffer = ByteBuffer.allocateDirect(128 * 128 * 4); check(editor.draw(128, 128, 0.0, 0.0, 128.0, 128.0, buffer)); return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) } }
    fun previewPixels(): ByteArray { val buffer = ByteBuffer.allocateDirect(128 * 128 * 4); checkNotNull(editor.captureDisplayScene()).use { it.draw(128, 128, Viewport(0.0, 0.0, 128.0, 128.0), buffer) }; return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) } }
    try {
        val red = ByteBuffer.allocateDirect(64).apply { repeat(16) { put(byteArrayOf(-1, 0, 0, -1)) }; rewind() }
        val id = editor.importPixels(4, 4, red); editor.setTransform(id, Transform64(48.0, 48.0, 16.0, 16.0)); editor.selectLayer(id)
        instrumentation.runOnMainSync {
            overlay = FrameLayout(activity); view = CanvasView(activity, editor, {}, { failures.add(it) }); view.layerId = id
            view.viewportChanged = { publications++ }
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); attached = true
        }
        waitUntil("Wheel canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        instrumentation.runOnMainSync { view.setZoomPercent(200.0) }; drain()
        var baseline: Marker? = null
        waitUntil("Wheel marker missing") { marker()?.let { if (abs(it.width - 32) <= 1) { baseline = it; true } else false } ?: false }
        val initial = checkNotNull(baseline); val original = editor.state(); val originalPixels = pixels(); val initialIcon = view.pointerIcon
        val units = view.resources.displayMetrics.density.toDouble(); val anchorX = (initial.x + 40).toFloat(); val anchorY = (initial.y + 30).toFloat()
        val zeroPublications = publications
        dispatch(event(x = anchorX, y = anchorY)); dispatch(event(x = anchorX, y = anchorY, horizontal = 2f, modifiers = KeyEvent.META_ALT_ON))
        check(publications == zeroPublications && view.zoomPercent == 200.0)
        dispatch(event(x = anchorX, y = anchorY, horizontal = .5f, vertical = -1f, modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        waitUntil("GLES wheel pan did not preserve native axis direction and density mapping") { marker()?.let { abs(it.x - initial.x - 6 * units) <= 1 && abs(it.y - initial.y + 12 * units) <= 1 && it.width == initial.width && it.height == initial.height } ?: false }
        dispatch(event(x = anchorX, y = anchorY, horizontal = -.5f, vertical = 1f)); check(view.zoomPercent == 200.0)
        for (modifier in listOf(KeyEvent.META_META_ON, KeyEvent.META_ALT_ON)) {
            dispatch(event(x = anchorX, y = anchorY, horizontal = 10f, vertical = -20f, modifiers = modifier))
            val factor = exp(.3); check(abs(view.zoomPercent - 200 * factor) < 1e-9)
            waitUntil("GLES wheel current-anchor zoom geometry mismatch") { marker()?.let { abs(it.width - initial.width * factor) <= 2 && abs(it.height - initial.height * factor) <= 2 && abs(it.x - (anchorX + (initial.x - anchorX) * factor)) <= 1.5 && abs(it.y - (anchorY + (initial.y - anchorY) * factor)) <= 1.5 } ?: false }
            dispatch(event(x = anchorX, y = anchorY, vertical = 20f, modifiers = modifier)); check(abs(view.zoomPercent - 200) < 1e-9)
        }
        check(view.pointerIcon === initialIcon && editor.state() == original && pixels().contentEquals(originalPixels))
        instrumentation.runOnMainSync { view.setZoomPercent(3200.0) }; drain()
        val clampedPublications = publications
        dispatch(event(x = anchorX, y = anchorY, vertical = -1000f, modifiers = KeyEvent.META_ALT_ON))
        check(view.zoomPercent == 3200.0 && publications == clampedPublications) { "An unchanged clamped wheel zoom republished its viewport" }
        instrumentation.runOnMainSync { view.setZoomPercent(200.0) }; drain()
        // Wheel must leave the middle zoom's original DOWN scale/anchor and cursor ownership intact.
        dispatch(event(MotionEvent.ACTION_DOWN, anchorX, anchorY, modifiers = KeyEvent.META_CTRL_ON, buttons = MotionEvent.BUTTON_TERTIARY))
        val middleIcon = view.pointerIcon; check(middleIcon == PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_ZOOM_IN))
        dispatch(event(x = anchorX + 70, y = anchorY + 90, vertical = -20f, modifiers = KeyEvent.META_ALT_ON))
        check(view.pointerIcon === middleIcon)
        dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY - (100 * units).toFloat(), buttons = MotionEvent.BUTTON_TERTIARY))
        check(abs(view.zoomPercent - 400) < 1e-9)
        dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY, buttons = MotionEvent.BUTTON_TERTIARY))
        dispatch(event(MotionEvent.ACTION_UP, anchorX, anchorY)); check(view.pointerIcon === initialIcon && abs(view.zoomPercent - 200) < 1e-9)
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.MOVE }; drain()
        instrumentation.runOnMainSync { check(view.editMoveField(MoveField.X, 49.25)) }
        // Draft creation precedes queued numeric updates; admit the requested preview before taking a baseline.
        val requestedPreview = Transform64(49.25, 48.0, 16.0, 16.0)
        waitUntil("Wheel draft missing") {
            view.moveInProgress && editor.hasActiveTransformDraft && editor.activeTransformCompletion() != null &&
                editor.captureDisplayScene()?.use { it.layerMetadata(0).transform == requestedPreview } == true
        }
        val preview = previewPixels(); val active = editor.state(); val owner = editor.activeTransformCompletion(); val box = view.moveBox
        dispatch(event(x = anchorX, y = anchorY, vertical = -20f, modifiers = KeyEvent.META_ALT_ON))
        dispatch(event(x = anchorX, y = anchorY, horizontal = .5f, vertical = -1f))
        check(editor.state() == active && view.moveInProgress && view.moveBox == box && editor.activeTransformCompletion() === owner && previewPixels().contentEquals(preview))
        instrumentation.runOnMainSync { view.cancelMove() }; waitUntil("Wheel draft did not cancel") { !view.moveInProgress && editor.activeTransformCompletion() == null }
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.BRUSH; view.setZoomPercent(200.0) }; drain()
        val stylus = event(MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, source = InputDevice.SOURCE_STYLUS, tool = MotionEvent.TOOL_TYPE_STYLUS)
        dispatch(stylus); check(view.strokeInProgress)
        val strokePixels = pixels(); val strokeState = editor.state(); val strokeZoom = view.zoomPercent; val strokePublications = publications
        dispatch(event(x = anchorX, y = anchorY, vertical = -20f, modifiers = KeyEvent.META_ALT_ON)); dispatch(event(horizontal = 2f, vertical = 2f))
        check(view.strokeInProgress && view.zoomPercent == strokeZoom && publications == strokePublications && editor.state() == strokeState && pixels().contentEquals(strokePixels)) { "Blocked wheel altered an active ordinary stroke" }
        dispatch(event(MotionEvent.ACTION_CANCEL, source = InputDevice.SOURCE_STYLUS, tool = MotionEvent.TOOL_TYPE_STYLUS))
        waitUntil("Wheel fixture stroke did not cancel") { !view.strokeInProgress }
        check(failures.isEmpty())
        instrumentation.runOnMainSync { view.retire() }
        val retired = event(vertical = 1f)
        try { instrumentation.runOnMainSync { check(!view.onGenericMotionEvent(retired)) } } finally { retired.recycle() }
        return "Direct View wheel MotionEvents: raw fractional H/V units map to12dp with identity signs; Meta/Alt incremental exp(-V*.015) at current anchor, Ctrl pan, no Shift swap. Exact density/zero/nonfinite/clamp reversal/finite overflow and underflow tests pass. GLES pan/extent/center verify live geometry; wheel preserves middle cursor/absolute start and numeric Move full active state/pixels/owner. Active stylus state/pixels/zoom/publications remain exact when wheel refused; retired input rejected. No Mac precise-device equivalence, OS injection, physical wheel/trackpad, Layers middle scroll, performance or full parity claim."
    } finally {
        if (attached) instrumentation.runOnMainSync { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        editor.close(); instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
