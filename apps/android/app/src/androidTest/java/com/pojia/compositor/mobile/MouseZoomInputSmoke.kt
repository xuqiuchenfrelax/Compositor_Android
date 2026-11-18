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
import org.json.JSONObject
import kotlin.math.abs

/** Absolute zoom math and the real GLES image independently check a fixed DOWN anchor. */
internal fun mouseZoomInputSmoke(instrumentation: Instrumentation): String {
    val down = SystemClock.uptimeMillis(); var time = 0L
    // Public SDK omits this framework fixture setter; this selector uses --no-hidden-api-checks.
    val setActionButton = MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
    fun event(action: Int, x: Float = 100f, y: Float = 500f, modifiers: Int = KeyEvent.META_CTRL_ON,
        buttons: Int = MotionEvent.BUTTON_TERTIARY, actionButton: Int = 0): MotionEvent =
        MotionEvent.obtain(down, down + time++, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
            modifiers, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0).also {
                if (actionButton != 0) setActionButton.invoke(it, actionButton)
            }
    data class Zoom(val scale: Double, val x: Double, val y: Double)
    val updates = mutableListOf<Zoom>(); val pans = mutableListOf<Pair<Double, Double>>(); val cursors = mutableListOf<Boolean?>()
    var scale = .5; var density = 1.0
    val input = MousePanInput({ scale }, { density }, { value, x, y -> updates.add(Zoom(value, x, y)); scale = value },
        { cursors.add(it) }) { dx, dy -> pans.add(dx to dy) }
    fun route(value: MotionEvent, allowed: Boolean = true): MousePanInput.Result = try { input.route(value, allowed) } finally { value.recycle() }
    for (units in listOf(1.0, 2.625, 3.0)) for (modifier in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON)) {
        input.reset(); scale = .5; density = units
        val y = (300 * units).toFloat()
        val genericStart = modifier == KeyEvent.META_META_ON
        route(event(if (genericStart) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_DOWN,
            120f, y, modifier, actionButton = if (genericStart) MotionEvent.BUTTON_TERTIARY else 0))
        // Duplicate touch/generic start cannot replace the original mode, scale, density or anchor.
        route(event(if (genericStart) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_BUTTON_PRESS,
            900f, y + 20, 0, actionButton = if (genericStart) 0 else MotionEvent.BUTTON_TERTIARY))
        density = 4.0
        val initialCursorCount = cursors.size
        route(event(MotionEvent.ACTION_MOVE, 900f, y - (50 * units).toFloat(), 0))
        route(event(MotionEvent.ACTION_MOVE, 900f, y - (100 * units).toFloat(), 0))
        check(cursors.size == initialCursorCount) { "Same-direction motion emitted redundant cursor updates" }
        check(updates.last() == Zoom(1.0, 120.0, y.toDouble())) { "100dp up must double about the original DOWN anchor" }
        route(event(MotionEvent.ACTION_MOVE, 400f, y - (80 * units).toFloat(), KeyEvent.META_ALT_ON))
        check(cursors.last() == false) { "Reversing above the start must immediately show zoom-out" }
        val cursorCount = cursors.size
        route(event(MotionEvent.ACTION_MOVE, 999f, y - (80 * units).toFloat(), 0))
        check(cursors.size == cursorCount) { "Horizontal-only motion changed the zoom direction cursor" }
        route(event(MotionEvent.ACTION_MOVE, 400f, y + (100 * units).toFloat(), 0))
        check(updates.last() == Zoom(.25, 120.0, y.toDouble()))
        route(event(MotionEvent.ACTION_MOVE, 400f, y, 0)); check(scale == .5)
        val count = updates.size
        route(event(MotionEvent.ACTION_UP, 999f, 999f, 0, 0))
        check(updates.size == count && cursors.last() == null) { "UP applied another zoom or retained its cursor" }
    }
    check(pans.isEmpty()) { "A selected zoom gesture fell back to pan" }
    input.reset(); density = 1.0; scale = 2.0
    route(event(MotionEvent.ACTION_DOWN)); route(event(MotionEvent.ACTION_MOVE, y = -100f)); check(scale == 32.0)
    route(event(MotionEvent.ACTION_MOVE, y = 500f)); check(scale == 2.0)
    route(event(MotionEvent.ACTION_MOVE, y = 1700f)); check(scale == .001)
    route(event(MotionEvent.ACTION_MOVE, y = 400f)); check(scale == 4.0)
    val finiteCount = updates.size
    val overflow = event(MotionEvent.ACTION_MOVE, y = -199500f)
    check(overflow.x.isFinite() && overflow.y.isFinite()) { "Overflow target fixture must retain finite framework coordinates" }
    route(overflow)
    check(updates.size == finiteCount && scale == 4.0) { "Mac ignores a nonfinite exponential target" }
    route(event(MotionEvent.ACTION_MOVE, y = 500f)); check(scale == 2.0)
    route(event(MotionEvent.ACTION_MOVE, y = Float.NaN)); val invalidCount = updates.size
    route(event(MotionEvent.ACTION_MOVE, y = 400f)); check(updates.size == invalidCount)
    route(event(MotionEvent.ACTION_UP, buttons = 0))
    for (invalidDensity in listOf(0.0, Double.NaN)) {
        density = invalidDensity; route(event(MotionEvent.ACTION_DOWN)); route(event(MotionEvent.ACTION_MOVE, y = 300f))
        route(event(MotionEvent.ACTION_UP, buttons = 0)); check(updates.size == invalidCount)
    }
    density = 1.0
    route(event(MotionEvent.ACTION_DOWN), false); route(event(MotionEvent.ACTION_MOVE, y = 300f)); route(event(MotionEvent.ACTION_UP, buttons = 0))
    check(updates.size == invalidCount)
    route(event(MotionEvent.ACTION_DOWN, modifiers = 0)); route(event(MotionEvent.ACTION_MOVE, 110f, 510f))
    check(pans.last() == (10.0 to 10.0)) { "Adding Control after DOWN changed pan into zoom" }
    route(event(MotionEvent.ACTION_UP, buttons = 0))
    route(event(MotionEvent.ACTION_BUTTON_PRESS, actionButton = MotionEvent.BUTTON_TERTIARY))
    route(event(MotionEvent.ACTION_MOVE, y = 400f, buttons = 0)); val lostCount = updates.size
    route(event(MotionEvent.ACTION_MOVE, y = 300f)); check(updates.size == lostCount)
    route(event(MotionEvent.ACTION_BUTTON_RELEASE, buttons = 0, actionButton = MotionEvent.BUTTON_TERTIARY))

    // Unmodified cached Mac CanvasViewport.swift produced these independent Swift values.
    // SHA256 9d0cb3cca1fb46d9b71d57d99dd0c5c6ea9b156ed3676bacf3893f110f576b42.
    val oracle = JSONObject(instrumentation.context.assets.open("p33-swift-zoom-oracle.json").bufferedReader().use { it.readText() })
    val cases = oracle.getJSONArray("cases")
    var oracleSamples = 0
    for (index in 0 until cases.length()) {
        val scenario = cases.getJSONObject(index)
        input.reset(); scale = scenario.getDouble("initialZoom"); density = scenario.getDouble("backingScale")
        val anchor = scenario.getJSONArray("anchor")
        val x = (anchor.getDouble(0) * density).toFloat(); val y = (anchor.getDouble(1) * density).toFloat()
        route(event(MotionEvent.ACTION_DOWN, x, y))
        val samples = scenario.getJSONArray("samples")
        for (sampleIndex in 0 until samples.length()) {
            val sample = samples.getJSONObject(sampleIndex); val count = updates.size
            route(event(MotionEvent.ACTION_MOVE, x + 17, y - (sample.getDouble("risePoints") * density).toFloat()))
            check(abs(scale - sample.getDouble("actualZoom")) < 1e-12) { "Actual Swift oracle mismatch: ${sample.getString("label")}" }
            check(updates.size == count + if (sample.getBoolean("requestedFinite")) 1 else 0)
            check(updates.last().x == x.toDouble() && updates.last().y == y.toDouble())
            oracleSamples++
        }
        route(event(MotionEvent.ACTION_UP, buttons = 0))
    }
    check(oracleSamples == 28)

    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val editor = NativeEditor(128, addDefaultLayers = false)
    val failures = CopyOnWriteArrayList<String>()
    lateinit var overlay: FrameLayout; lateinit var view: CanvasView; var attached = false
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    fun drain() {
        val latch = CountDownLatch(1); view.queueEvent { latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    fun dispatch(value: MotionEvent) {
        try { instrumentation.runOnMainSync {
            val generic = value.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || value.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
            check(if (generic) view.dispatchGenericMotionEvent(value) else view.dispatchTouchEvent(value))
        } } finally { value.recycle() }
        drain()
    }
    data class Marker(val x: Double, val y: Double, val width: Int, val height: Int)
    fun marker(): Marker? {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val latch = CountDownLatch(1); var result = PixelCopy.ERROR_UNKNOWN
            instrumentation.runOnMainSync { PixelCopy.request(view, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
            check(latch.await(10, TimeUnit.SECONDS)); if (result != PixelCopy.SUCCESS) return null
            val values = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            values.forEachIndexed { index, color ->
                if ((color ushr 16 and 255) > 220 && (color ushr 8 and 255) < 25 && (color and 255) < 25) {
                    val x = index % bitmap.width; val y = index / bitmap.width
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            return if (right >= left) Marker((left + right) / 2.0, (top + bottom) / 2.0, right - left + 1, bottom - top + 1) else null
        } finally { bitmap.recycle() }
    }
    fun pixels(): ByteArray {
        val buffer = ByteBuffer.allocateDirect(128 * 128 * 4)
        check(editor.draw(128, 128, 0.0, 0.0, 128.0, 128.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun previewPixels(): ByteArray {
        val buffer = ByteBuffer.allocateDirect(128 * 128 * 4)
        checkNotNull(editor.captureDisplayScene()).use { it.draw(128, 128, Viewport(0.0, 0.0, 128.0, 128.0), buffer) }
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    try {
        val red = ByteBuffer.allocateDirect(4 * 4 * 4).apply { repeat(16) { put(byteArrayOf(-1, 0, 0, -1)) }; rewind() }
        val id = editor.importPixels(4, 4, red)
        editor.setTransform(id, Transform64(48.0, 48.0, 16.0, 16.0)); editor.selectLayer(id)
        instrumentation.runOnMainSync {
            overlay = FrameLayout(activity); view = CanvasView(activity, editor, {}, { failures.add(it) }); view.layerId = id
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); attached = true
        }
        waitUntil("Zoom canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        instrumentation.runOnMainSync { view.setZoomPercent(200.0) }; drain()
        var baseline: Marker? = null
        waitUntil("Zoom baseline marker missing") { marker()?.let { if (abs(it.width - 32) <= 1) { baseline = it; true } else false } ?: false }
        val original = editor.state(); val originalPixels = pixels(); val initialIcon = view.pointerIcon
        val anchorX = (checkNotNull(baseline).x + 40).toFloat(); val anchorY = (checkNotNull(baseline).y + 30).toFloat()
        val physicalRise = 100 * view.resources.displayMetrics.density
        for (modifier in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON)) {
            val genericStart = modifier == KeyEvent.META_META_ON
            dispatch(event(if (genericStart) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_DOWN,
                anchorX, anchorY, modifier, actionButton = if (genericStart) MotionEvent.BUTTON_TERTIARY else 0))
            check(view.pointerIcon == PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_ZOOM_IN))
            dispatch(event(if (genericStart) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_BUTTON_PRESS,
                anchorX + 100, anchorY + 100, 0, actionButton = if (genericStart) 0 else MotionEvent.BUTTON_TERTIARY))
            dispatch(event(MotionEvent.ACTION_MOVE, anchorX + 200, anchorY - physicalRise, 0))
            check(abs(view.zoomPercent - 400) < .000001)
            waitUntil("GLES fixed-anchor zoom did not double the marker extent and retain its document anchor") {
                marker()?.let { abs(it.width - checkNotNull(baseline).width * 2) <= 2 &&
                    abs(it.height - checkNotNull(baseline).height * 2) <= 2 &&
                    abs(it.x - (anchorX + (checkNotNull(baseline).x - anchorX) * 2)) <= 1 &&
                    abs(it.y - (anchorY + (checkNotNull(baseline).y - anchorY) * 2)) <= 1 } ?: false
            }
            dispatch(event(MotionEvent.ACTION_MOVE, anchorX + 200, anchorY - physicalRise * .8f, 0))
            check(view.pointerIcon == PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_ZOOM_OUT))
            dispatch(event(MotionEvent.ACTION_MOVE, anchorX - 200, anchorY - physicalRise * .8f, modifier))
            check(view.pointerIcon == PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_ZOOM_OUT))
            dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY + physicalRise, modifier))
            check(abs(view.zoomPercent - 100) < .000001)
            dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY, 0)); check(abs(view.zoomPercent - 200) < .000001)
            dispatch(event(MotionEvent.ACTION_UP, 999f, 999f, 0, 0))
            dispatch(event(MotionEvent.ACTION_BUTTON_RELEASE, buttons = 0, actionButton = MotionEvent.BUTTON_TERTIARY))
            check(view.pointerIcon === initialIcon && editor.state() == original && pixels().contentEquals(originalPixels))
        }
        for (mode in CanvasToolMode.entries) {
            instrumentation.runOnMainSync { view.toolMode = mode }; drain()
            dispatch(event(MotionEvent.ACTION_DOWN, anchorX, anchorY))
            dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY - physicalRise))
            check(abs(view.zoomPercent - 400) < .000001) { "Mouse zoom failed for $mode" }
            dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY)); dispatch(event(MotionEvent.ACTION_UP, buttons = 0))
            check(view.zoomPercent == 200.0 && editor.state() == original && pixels().contentEquals(originalPixels))
        }
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.MOVE }; drain()
        instrumentation.runOnMainSync { check(view.editMoveField(MoveField.X, 49.25)) }
        // Draft creation precedes queued numeric updates; admit the requested preview before taking a baseline.
        val requestedPreview = Transform64(49.25, 48.0, 16.0, 16.0)
        waitUntil("Zoom numeric draft not active") {
            view.moveInProgress && editor.hasActiveTransformDraft && editor.activeTransformCompletion() != null &&
                editor.captureDisplayScene()?.use { it.layerMetadata(0).transform == requestedPreview } == true
        }
        val box = view.moveBox; val owner = editor.activeTransformCompletion(); val preview = previewPixels(); val active = editor.state()
        dispatch(event(MotionEvent.ACTION_DOWN, anchorX, anchorY)); dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY - physicalRise))
        check(abs(view.zoomPercent - 400) < .000001)
        dispatch(event(MotionEvent.ACTION_CANCEL, buttons = 0)); dispatch(event(MotionEvent.ACTION_UP, buttons = 0))
        check(view.moveBox == box && view.moveInProgress && editor.activeTransformCompletion() === owner &&
            editor.state() == active && previewPixels().contentEquals(preview) && view.pointerIcon === initialIcon) {
            "Anchored viewport zoom changed or settled a pending numeric Move draft"
        }
        instrumentation.runOnMainSync { view.cancelMove() }
        waitUntil("Zoom numeric draft did not cancel") { !view.moveInProgress && editor.activeTransformCompletion() == null }
        val beforeFocusScale = view.zoomPercent
        dispatch(event(MotionEvent.ACTION_DOWN, anchorX, anchorY)); instrumentation.runOnMainSync { view.onWindowFocusChanged(false) }
        dispatch(event(MotionEvent.ACTION_MOVE, anchorX, anchorY - physicalRise)); dispatch(event(MotionEvent.ACTION_UP, buttons = 0))
        check(view.zoomPercent == beforeFocusScale && view.pointerIcon === initialIcon && failures.isEmpty() && editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null))
        instrumentation.runOnMainSync { view.retire() }
        val rejected = event(MotionEvent.ACTION_DOWN)
        try { instrumentation.runOnMainSync { check(!view.onTouchEvent(rejected)) } } finally { rejected.recycle() }
        return "Direct View mouse MotionEvents: Ctrl/Meta touch/generic start fixes mode/scale/density/anchor;100dp up doubles, down halves;28 actual Swift oracle samples at backing/density1/2, clamp recovery, overflow ignore, modifiers and no pan fallback passed. GLES marker extent/center independently prove2x fixed-anchor zoom; all tool modes preserve state/pixels. Cursor changes only on vertical direction reversal, restores on release/cancel/focus. Pending numeric Move active state/pixels/owner preserved; retired input refused. Fixture reflection uses per-run --no-hidden-api-checks; no physical mouse, OS injection, performance or full Mac parity claim."
    } finally {
        if (attached) instrumentation.runOnMainSync { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        editor.close(); instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
