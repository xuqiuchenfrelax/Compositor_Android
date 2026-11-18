package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Ordinary CanvasView callbacks and real MotionEvent history exercise the on-demand SurfaceView. */
internal fun previewInputSmoke(instrumentation: Instrumentation): String {
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val editor = NativeEditor(64, addDefaultLayers = false)
    val reference = NativeEditor(64, addDefaultLayers = false)
    val callbacks = AtomicInteger()
    val states = CopyOnWriteArrayList<EditorState>()
    val failures = CopyOnWriteArrayList<String>()
    val settings = StrokeSettings(diameter = 8f, hardness = .8f, red = .18f, green = .45f, blue = .95f,
        pressureSize = true)
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
    fun pixels(document: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(64 * 64 * 4)
        check(document.draw(64, 64, 0.0, 0.0, 64.0, 64.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun visiblePixels(): IntArray? {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val latch = CountDownLatch(1); var result = PixelCopy.ERROR_UNKNOWN
            instrumentation.runOnMainSync { PixelCopy.request(view, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
            check(latch.await(10, TimeUnit.SECONDS))
            if (result != PixelCopy.SUCCESS) return null
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally { bitmap.recycle() }
    }
    try {
        editor.addLayer(); reference.addLayer()
        val id = editor.state().layers.single().id; val referenceId = reference.state().layers.single().id
        instrumentation.runOnMainSync {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, { callbacks.incrementAndGet(); states.add(it) }, { failures.add(it) })
            view.layerId = id; view.strokeSettings = settings
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attached = true
        }
        waitUntil("Isolated pointer canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        drain()
        var baselineDisplay: IntArray? = null
        waitUntil("Actual on-demand surface did not present baseline") { visiblePixels()?.let { baselineDisplay = it; true } ?: false }
        val original = pixels(editor); val originalRevision = editor.currentRevision()
        val scale = min(view.width / 64.0, view.height / 64.0) * 0.92
        val offsetX = (view.width - 64 * scale) / 2; val offsetY = (view.height - 64 * scale) / 2
        data class Point(val x: Float, val y: Float, val pressure: Float)
        fun point(x: Double, y: Double, pressure: Float) = Point((offsetX + x * scale).toFloat(), (offsetY + y * scale).toFloat(), pressure)
        fun coords(point: Point) = MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = point.pressure; size = 1f }
        var down = SystemClock.uptimeMillis()
        fun event(action: Int, offset: Long, point: Point): MotionEvent = MotionEvent.obtain(down, down + offset, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
            arrayOf(coords(point)), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        fun dispatch(event: MotionEvent) {
            try { instrumentation.runOnMainSync { check(view.dispatchTouchEvent(event)) } } finally { event.recycle() }
            drain()
        }
        // MotionEvent may quantize constructed coordinates. The oracle reads actual platform fields,
        // independently of CanvasView, and checks exact pixels without a rounding tolerance.
        fun samples(event: MotionEvent): DoubleArray = (0..event.historySize).flatMap { index ->
            val historical = index < event.historySize
            val x = if (historical) event.getHistoricalX(0, index) else event.x
            val y = if (historical) event.getHistoricalY(0, index) else event.y
            val pressure = if (historical) event.getHistoricalPressure(0, index) else event.pressure
            val time = if (historical) event.getHistoricalEventTime(index) else event.eventTime
            listOf((x.toDouble() - offsetX) / scale, (y.toDouble() - offsetY) / scale,
                pressure.coerceIn(0.01f, 1f).toDouble(), (time - down) / 1000.0)
        }.toDoubleArray()
        val start = point(10.0, 10.0, 0.8f)
        val route = listOf(point(48.0, 10.0, 0.35f), point(48.0, 48.0, 0.65f), point(10.0, 48.0, 0.95f))
        val downEvent = event(MotionEvent.ACTION_DOWN, 0, start)
        val downSamples = samples(downEvent)
        check(downEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && downSamples[2] == start.pressure.toDouble() && downSamples[3] == 0.0)
        dispatch(downEvent)
        check(callbacks.get() == 1 && failures.isEmpty()) { "Begin must publish once; successful first sample must not publish metadata" }
        reference.startStroke(referenceId, settings); reference.samples(downSamples)
        val move = event(MotionEvent.ACTION_MOVE, 10, route[0])
        move.addBatch(down + 20, arrayOf(coords(route[1])), 0)
        move.addBatch(down + 30, arrayOf(coords(route[2])), 0)
        check(move.historySize == 2 && move.getHistoricalEventTime(0) == down + 10 && move.getHistoricalEventTime(1) == down + 20)
        val moveSamples = samples(move)
        check(move.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            route.indices.all { moveSamples[it * 4 + 2] == route[it].pressure.toDouble() && moveSamples[it * 4 + 3] == (it + 1) / 100.0 })
        dispatch(move)
        reference.samples(moveSamples)
        check(callbacks.get() == 1 && failures.isEmpty()) { "MOVE history batch published full editor state" }
        check(pixels(editor).contentEquals(pixels(reference))) { "CanvasView lost/reordered historical coordinates, pressure, or timestamps" }
        check(!pixels(editor).contentEquals(original) && editor.currentRevision() == originalRevision)
        var previewDisplay: IntArray? = null
        waitUntil("Preview did not reach actual SurfaceView before commit without extra render request") {
            visiblePixels()?.let { candidate ->
                if (!candidate.contentEquals(checkNotNull(baselineDisplay))) { previewDisplay = candidate; true } else false
            } ?: false
        }
        check(previewDisplay != null && callbacks.get() == 1)
        val up = event(MotionEvent.ACTION_UP, 40, route.last())
        val upSamples = samples(up)
        check(up.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && upSamples[2] == route.last().pressure.toDouble() && upSamples[3] == .04)
        dispatch(up)
        reference.samples(upSamples); reference.endStroke(false)
        check(callbacks.get() == 2 && failures.isEmpty() && editor.currentRevision() != originalRevision)
        check(pixels(editor).contentEquals(pixels(reference)) && states.last() == editor.state())
        val committedPixels = pixels(editor); val committedRevision = editor.currentRevision()
        down = SystemClock.uptimeMillis()
        dispatch(event(MotionEvent.ACTION_DOWN, 0, point(32.0, 32.0, 1f)))
        check(callbacks.get() == 3)
        dispatch(event(MotionEvent.ACTION_MOVE, 10, point(40.0, 32.0, 1f)))
        check(callbacks.get() == 3 && !pixels(editor).contentEquals(committedPixels))
        dispatch(event(MotionEvent.ACTION_CANCEL, 20, point(40.0, 32.0, 1f)))
        check(callbacks.get() == 4 && failures.isEmpty() && pixels(editor).contentEquals(committedPixels))
        check(editor.currentRevision() == committedRevision && states.last() == editor.state())
        down = SystemClock.uptimeMillis()
        dispatch(event(MotionEvent.ACTION_DOWN, 0, point(32.0, 32.0, 1f)))
        check(callbacks.get() == 5)
        // A malformed input batch reaches shipping timestamp validation and samples() aborts it.
        val malformed = event(MotionEvent.ACTION_MOVE, -1, point(40.0, 32.0, 1f))
        check(malformed.eventTime < malformed.downTime)
        dispatch(malformed)
        check(callbacks.get() == 6 && failures.size == 1 && failures.single() == activity.getString(R.string.notice_editor_command_failed))
        check(pixels(editor).contentEquals(committedPixels) && editor.currentRevision() == committedRevision)
        check(states.last() == editor.state()) { "Aborted preview did not refresh full state" }
        dispatch(event(MotionEvent.ACTION_UP, 10, point(40.0, 32.0, 1f)))
        // Malformed input now ends the UI stroke immediately; the later UP is ignored.
        check(callbacks.get() == 6 && failures.size == 1 && editor.currentRevision() == committedRevision)
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        view.queueEvent { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        check(blocked.await(10, TimeUnit.SECONDS))
        try {
            down = SystemClock.uptimeMillis()
            val queued = event(MotionEvent.ACTION_DOWN, 0, point(24.0, 24.0, 1f))
            try { instrumentation.runOnMainSync { check(view.dispatchTouchEvent(queued)); view.retire() } } finally { queued.recycle() }
        } finally { release.countDown() }
        drain()
        check(callbacks.get() == 6 && failures.size == 1 && pixels(editor).contentEquals(committedPixels))
        check(editor.currentRevision() == committedRevision)
        return "Actual CanvasView stylus MotionEvent with two historical samples matches ordered coordinates/pressure/timestamps; PixelCopy observed on-demand preview before commit. Full-state callbacks begin1/MOVE0/commit1/cancel1/abort1; original error delivered once and subsequent UP ignored, queued retirement rejected, metadata/revision/committed pixels preserved. Input dispatched through ordinary View; no manual render call, native test seam, or display override."
    } finally {
        if (attached) instrumentation.runOnMainSync { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        editor.close(); reference.close()
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
