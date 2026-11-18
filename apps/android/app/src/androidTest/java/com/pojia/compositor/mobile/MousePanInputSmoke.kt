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
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/** Real platform mouse events exercise both touch and generic-button dispatch, then the GLES surface. */
internal fun mousePanInputSmoke(instrumentation: Instrumentation): String {
    val down = SystemClock.uptimeMillis()
    var time = 0L
    // Framework fixture construction only: run this selector with am instrument --no-hidden-api-checks.
    val setActionButton = MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
    fun event(action: Int, x: Float = 100f, y: Float = 100f, buttons: Int = MotionEvent.BUTTON_TERTIARY,
        actionButton: Int = 0, modifiers: Int = 0, stylus: Boolean = false): MotionEvent =
        MotionEvent.obtain(down, down + time++, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
            modifiers, buttons, 1f, 1f, 0, 0, if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_MOUSE, 0)
            .also { if (actionButton != 0) setActionButton.invoke(it, actionButton) }
    val deltas = mutableListOf<Pair<Double, Double>>()
    val input = MousePanInput { dx, dy -> deltas.add(dx to dy) }
    fun route(value: MotionEvent, allowed: Boolean = true): MousePanInput.Result = try { input.route(value, allowed) } finally { value.recycle() }
    check(route(event(MotionEvent.ACTION_DOWN)).consumed)
    check(route(event(MotionEvent.ACTION_BUTTON_PRESS, actionButton = MotionEvent.BUTTON_TERTIARY)).consumed)
    route(event(MotionEvent.ACTION_MOVE, 123f, 91f))
    route(event(MotionEvent.ACTION_UP, 900f, 900f, 0))
    check(deltas == listOf(23.0 to -9.0)) { "Middle release recomputed the last accepted drag" }
    route(event(MotionEvent.ACTION_BUTTON_RELEASE, buttons = 0, actionButton = MotionEvent.BUTTON_TERTIARY))
    route(event(MotionEvent.ACTION_MOVE, 500f, 500f))
    check(deltas.size == 1) { "Orphan middle movement navigated" }
    route(event(MotionEvent.ACTION_BUTTON_PRESS, actionButton = MotionEvent.BUTTON_TERTIARY))
    route(event(MotionEvent.ACTION_MOVE, 105f, 110f))
    route(event(MotionEvent.ACTION_BUTTON_RELEASE, buttons = 0, actionButton = MotionEvent.BUTTON_TERTIARY))
    check(deltas.last() == (5.0 to 10.0))
    for (modifier in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON)) {
        route(event(MotionEvent.ACTION_DOWN, modifiers = modifier)); route(event(MotionEvent.ACTION_MOVE, 200f, 200f))
        route(event(MotionEvent.ACTION_UP, buttons = 0))
    }
    check(deltas.size == 2) { "Modifier-middle zoom leaked into pan" }
    route(event(MotionEvent.ACTION_DOWN), false); route(event(MotionEvent.ACTION_MOVE, 200f, 200f)); route(event(MotionEvent.ACTION_UP, buttons = 0))
    check(deltas.size == 2) { "A rejected middle sequence restarted later" }
    route(event(MotionEvent.ACTION_DOWN)); route(event(MotionEvent.ACTION_MOVE, 200f, 200f, 0))
    route(event(MotionEvent.ACTION_MOVE, 300f, 300f)); route(event(MotionEvent.ACTION_UP, buttons = 0))
    check(deltas.size == 2) { "Lost button resumed a stale drag" }
    route(event(MotionEvent.ACTION_DOWN)); route(event(MotionEvent.ACTION_MOVE, Float.NaN, 200f))
    route(event(MotionEvent.ACTION_MOVE, 300f, 300f)); route(event(MotionEvent.ACTION_UP, buttons = 0))
    check(deltas.size == 2) { "Nonfinite coordinates produced a viewport delta" }
    route(event(MotionEvent.ACTION_DOWN)); route(event(MotionEvent.ACTION_CANCEL, buttons = 0))
    route(event(MotionEvent.ACTION_MOVE, 300f, 300f)); input.reset()
    check(deltas.size == 2)
    route(event(MotionEvent.ACTION_DOWN))
    val twoPointers = MotionEvent.obtain(down, down + time++, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
        Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_MOUSE } },
        Array(2) { index -> MotionEvent.PointerCoords().apply { x = 100f + index; y = 100f; pressure = 1f } },
        0, MotionEvent.BUTTON_TERTIARY, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    check(route(twoPointers).consumed)
    route(event(MotionEvent.ACTION_MOVE, 300f, 300f)); check(deltas.size == 2)
    input.reset()
    check(!route(event(MotionEvent.ACTION_DOWN, stylus = true)).consumed) { "Stylus barrel flags were treated as a mouse" }
    check(!route(event(MotionEvent.ACTION_DOWN, buttons = MotionEvent.BUTTON_PRIMARY)).consumed)
    route(event(MotionEvent.ACTION_BUTTON_PRESS, buttons = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_TERTIARY,
        actionButton = MotionEvent.BUTTON_TERTIARY))
    check(!route(event(MotionEvent.ACTION_MOVE, 120f, 120f, MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_TERTIARY)).consumed)
    check(route(event(MotionEvent.ACTION_BUTTON_RELEASE, actionButton = MotionEvent.BUTTON_PRIMARY)).finishPrimary)
    check(route(event(MotionEvent.ACTION_UP, buttons = 0)).consumed)
    check(deltas.size == 2)
    check(!route(event(MotionEvent.ACTION_DOWN, buttons = MotionEvent.BUTTON_PRIMARY)).consumed)
    check(!route(event(MotionEvent.ACTION_DOWN, stylus = true)).consumed)
    check(route(event(MotionEvent.ACTION_UP, buttons = 0)).consumed)
    check(!route(event(MotionEvent.ACTION_BUTTON_RELEASE, buttons = 0, actionButton = MotionEvent.BUTTON_PRIMARY)).finishPrimary)
    check(route(event(MotionEvent.ACTION_CANCEL, buttons = 0)).consumed)

    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val editor = NativeEditor(64, addDefaultLayers = false)
    val failures = CopyOnWriteArrayList<String>()
    lateinit var overlay: FrameLayout
    lateinit var view: CanvasView
    var attached = false
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    fun drain() {
        val latch = CountDownLatch(1); view.queueEvent { latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    fun stats(): GpuStats {
        val latch = CountDownLatch(1); var result: GpuStats? = null
        view.debugGpuStats { result = it; latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)); return checkNotNull(result)
    }
    fun dispatch(value: MotionEvent) {
        try { instrumentation.runOnMainSync {
            val generic = value.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || value.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
            check(if (generic) view.dispatchGenericMotionEvent(value) else view.dispatchTouchEvent(value))
        } } finally { value.recycle() }
        drain()
    }
    fun pixels(): ByteArray {
        val buffer = ByteBuffer.allocateDirect(64 * 64 * 4)
        check(editor.draw(64, 64, 0.0, 0.0, 64.0, 64.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun marker(): Pair<Double, Double>? {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val latch = CountDownLatch(1); var result = PixelCopy.ERROR_UNKNOWN
            instrumentation.runOnMainSync { PixelCopy.request(view, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
            check(latch.await(10, TimeUnit.SECONDS)); if (result != PixelCopy.SUCCESS) return null
            val values = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var sx = 0L; var sy = 0L; var count = 0
            values.forEachIndexed { index, color ->
                if ((color ushr 16 and 255) > 220 && (color ushr 8 and 255) < 25 && (color and 255) < 25) {
                    sx += index % bitmap.width; sy += index / bitmap.width; count++
                }
            }
            return if (count > 0) sx.toDouble() / count to sy.toDouble() / count else null
        } finally { bitmap.recycle() }
    }
    try {
        val red = ByteBuffer.allocateDirect(8 * 8 * 4).apply { repeat(64) { put(byteArrayOf(-1, 0, 0, -1)) }; rewind() }
        val id = editor.importPixels(8, 8, red)
        editor.setTransform(id, Transform64(24.0, 24.0, 16.0, 16.0)); editor.selectLayer(id)
        instrumentation.runOnMainSync {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, {}, { failures.add(it) })
            view.layerId = id
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attached = true
        }
        waitUntil("Mouse canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        drain()
        val original = editor.state(); val originalPixels = pixels()
        var baseline: Pair<Double, Double>? = null
        waitUntil("GLES marker was not presented") { marker()?.let { baseline = it; true } ?: false }
        dispatch(event(MotionEvent.ACTION_DOWN))
        dispatch(event(MotionEvent.ACTION_BUTTON_PRESS, actionButton = MotionEvent.BUTTON_TERTIARY))
        dispatch(event(MotionEvent.ACTION_MOVE, 124f, 112f))
        dispatch(event(MotionEvent.ACTION_UP, 900f, 900f, 0))
        dispatch(event(MotionEvent.ACTION_BUTTON_RELEASE, buttons = 0, actionButton = MotionEvent.BUTTON_TERTIARY))
        waitUntil("Middle pan did not translate the actual GLES surface by its view delta") {
            marker()?.let { abs(it.first - checkNotNull(baseline).first - 24) <= 1 && abs(it.second - checkNotNull(baseline).second - 12) <= 1 } ?: false
        }
        for (tool in CanvasToolMode.entries) {
            instrumentation.runOnMainSync { view.toolMode = tool }; drain()
            val beforeToolEditing = view.editingInProgress
            val beforeToolCrop = view.cropRect
            dispatch(event(MotionEvent.ACTION_BUTTON_PRESS, actionButton = MotionEvent.BUTTON_TERTIARY,
                modifiers = KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON))
            dispatch(event(MotionEvent.ACTION_MOVE, 108f, 104f)); dispatch(event(MotionEvent.ACTION_BUTTON_RELEASE,
                buttons = 0, actionButton = MotionEvent.BUTTON_TERTIARY))
            // Entering Crop intentionally opens a draft; navigation must preserve it.
            check(editor.state() == original && pixels().contentEquals(originalPixels) &&
                view.editingInProgress == beforeToolEditing && view.cropRect == beforeToolCrop && !view.samplingInProgress)
        }
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.CROP; view.fit() }; drain()
        val cropBaseline = checkNotNull(view.cropRect)
        val cropScale = min(view.width / 64.0, view.height / 64.0) * .92
        val cropLeft = ((view.width - 64 * cropScale) / 2).toFloat()
        val cropTop = ((view.height - 64 * cropScale) / 2).toFloat()
        fun cropEvent(action: Int, coordinate: Double, buttons: Int = MotionEvent.BUTTON_PRIMARY,
            actionButton: Int = 0, stylus: Boolean = false) = event(action,
            cropLeft + (coordinate * cropScale).toFloat(), cropTop + (coordinate * cropScale).toFloat(),
            buttons, actionButton, stylus = stylus)
        // A non-mouse orphan reaches the tool directly and must not invent a crop anchor.
        dispatch(cropEvent(MotionEvent.ACTION_MOVE, 12.0, 0, stylus = true))
        check(view.cropRect == cropBaseline) { "Orphan Crop MOVE changed the draft" }
        dispatch(cropEvent(MotionEvent.ACTION_DOWN, 0.0))
        dispatch(cropEvent(MotionEvent.ACTION_MOVE, 8.0))
        val resizedCrop = checkNotNull(view.cropRect)
        check(resizedCrop != cropBaseline) { "Primary Crop resize did not update the draft" }
        dispatch(cropEvent(MotionEvent.ACTION_BUTTON_PRESS, 8.0,
            MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_TERTIARY))
        dispatch(cropEvent(MotionEvent.ACTION_BUTTON_RELEASE, 8.0,
            MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_PRIMARY))
        dispatch(cropEvent(MotionEvent.ACTION_MOVE, 16.0, 0, stylus = true))
        check(view.cropRect == resizedCrop) { "Generic primary release left a Crop gesture active" }
        dispatch(cropEvent(MotionEvent.ACTION_DOWN, 8.0, stylus = true))
        instrumentation.runOnMainSync {
            view.cancelCrop(); view.toolMode = CanvasToolMode.BRUSH; view.toolMode = CanvasToolMode.CROP
        }; drain()
        val restartedCrop = checkNotNull(view.cropRect)
        dispatch(cropEvent(MotionEvent.ACTION_MOVE, 24.0, 0, stylus = true))
        dispatch(cropEvent(MotionEvent.ACTION_UP, 24.0, 0, stylus = true))
        check(view.cropRect == restartedCrop && editor.state() == original && pixels().contentEquals(originalPixels)) {
            "Canceled Crop gesture altered a new draft or committed document"
        }
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.MOVE }; drain()
        instrumentation.runOnMainSync { check(view.editMoveField(MoveField.X, 25.25)) }
        waitUntil("Numeric Move preview did not begin") { view.moveInProgress && editor.hasActiveTransformDraft }
        val box = view.moveBox; val pending = editor.activeTransformCompletion()
        dispatch(event(MotionEvent.ACTION_DOWN)); dispatch(event(MotionEvent.ACTION_MOVE, 107f, 103f))
        dispatch(event(MotionEvent.ACTION_CANCEL, buttons = 0)); dispatch(event(MotionEvent.ACTION_UP, buttons = 0))
        check(view.moveInProgress && view.moveBox == box && editor.activeTransformCompletion() === pending && editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null)) {
            "Viewport pan settled or canceled a pending numeric Move draft"
        }
        instrumentation.runOnMainSync { view.cancelMove() }
        waitUntil("Numeric Move cancel did not settle") { !view.moveInProgress && editor.activeTransformCompletion() == null }
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.BRUSH; view.fit() }; drain()
        waitUntil("Marker did not return to fit after numeric Move cancellation") {
            marker()?.let { abs(it.first - checkNotNull(baseline).first) <= 1 && abs(it.second - checkNotNull(baseline).second) <= 1 } ?: false
        }
        val beforeFocus = checkNotNull(marker())
        dispatch(event(MotionEvent.ACTION_DOWN))
        instrumentation.runOnMainSync { view.onWindowFocusChanged(false) }
        dispatch(event(MotionEvent.ACTION_MOVE, 200f, 200f)); dispatch(event(MotionEvent.ACTION_UP, buttons = 0))
        // Force one verification frame: a broken reset must not hide behind an older presented frame.
        val beforeFrame = stats().completedFrames
        view.requestRender()
        waitUntil("Focus cleanup verification frame did not finish") { stats().completedFrames > beforeFrame }
        val afterFocus = checkNotNull(marker())
        check(abs(afterFocus.first - beforeFocus.first) <= 1 && abs(afterFocus.second - beforeFocus.second) <= 1) {
            "Lost focus retained the middle drag's viewport anchor"
        }
        check(editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null))
        val scale = min(view.width / 64.0, view.height / 64.0) * .92
        val x = ((view.width - 64 * scale) / 2 + 30 * scale).toFloat()
        val y = ((view.height - 64 * scale) / 2 + 30 * scale).toFloat()
        dispatch(event(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY))
        check(view.strokeInProgress)
        dispatch(event(MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_TERTIARY))
        dispatch(event(MotionEvent.ACTION_MOVE, x + 8, y, MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_TERTIARY))
        dispatch(event(MotionEvent.ACTION_BUTTON_RELEASE, x + 8, y, MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_PRIMARY))
        waitUntil("Generic primary release did not settle the ordinary stroke") { !view.strokeInProgress }
        val painted = editor.state(); val paintedPixels = pixels()
        check(painted.revision == original.revision + 1 && !paintedPixels.contentEquals(originalPixels))
        dispatch(event(MotionEvent.ACTION_MOVE, x + 40, y + 40))
        dispatch(event(MotionEvent.ACTION_UP, x + 40, y + 40, 0))
        check(editor.state() == painted && pixels().contentEquals(paintedPixels)) { "Middle remainder leaked into ordinary brush input" }
        dispatch(event(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY))
        dispatch(event(MotionEvent.ACTION_DOWN, x + 20, y + 20, stylus = true))
        check(view.strokeInProgress)
        val stylusPreview = pixels()
        val stylusState = editor.state()
        check(stylusState.layers == painted.layers && stylusState.undo == painted.undo &&
            stylusState.redo == painted.redo && stylusState.revision == painted.revision && stylusState.document == painted.document) {
            "Source transition changed committed layers, history, revision, or document metadata"
        }
        dispatch(event(MotionEvent.ACTION_UP, x + 50, y + 50, 0))
        dispatch(event(MotionEvent.ACTION_BUTTON_RELEASE, x + 50, y + 50, 0, MotionEvent.BUTTON_PRIMARY))
        dispatch(event(MotionEvent.ACTION_CANCEL, x + 50, y + 50, 0))
        val afterOrphans = editor.state()
        val orphanPixelsUnchanged = pixels().contentEquals(stylusPreview)
        check(view.strokeInProgress && orphanPixelsUnchanged && afterOrphans == stylusState) {
            "Orphan mouse diagnostic: strokeActive=${view.strokeInProgress}; pixelsUnchanged=$orphanPixelsUnchanged; " +
                "activeStateUnchanged=${afterOrphans == stylusState}; paintedStateUnchanged=${afterOrphans == painted}; " +
                "painted=$painted; beforeOrphans=$stylusState; afterOrphans=$afterOrphans"
        }
        dispatch(event(MotionEvent.ACTION_UP, x + 20, y + 20, buttons = 0, stylus = true))
        waitUntil("Stylus terminal did not settle after source transition") { !view.strokeInProgress }
        val afterStylus = editor.state()
        check(afterStylus.revision == painted.revision + 1)
        check(editor.state() == afterStylus && failures.isEmpty())
        instrumentation.runOnMainSync { view.retire() }
        val rejected = event(MotionEvent.ACTION_DOWN)
        try { instrumentation.runOnMainSync { check(!view.onTouchEvent(rejected)) } } finally { rejected.recycle() }
        check(editor.state() == afterStylus)
        return "Constructed physical-mouse-source MotionEvents dispatched directly through View.dispatchTouchEvent/dispatchGenericMotionEvent pass exact view deltas and actual GLES marker displacement; all tools preserve committed pixels/state/history and existing Crop drafts. Crop orphan MOVE, primary resize, generic primary release and cancel/restart isolation passed. Generic actionButton fixtures use framework reflection with per-run --no-hidden-api-checks. Direct editMoveField/controller numeric Move remains owned (no focused-field UI claim); ordinary primary+middle overlap commits once at primary release; orphan terminals cannot finish a subsequent stylus stroke. Pan routing retained; lost-button/nonfinite/multipointer/cancel/source isolation passed. Direct focus callback cleanup preserves marker placement after a forced verification frame; retired view rejects input. No OS-injected/physical mouse, full Mac parity, FPS or RSS claim."
    } finally {
        if (attached) instrumentation.runOnMainSync { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        editor.close()
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
