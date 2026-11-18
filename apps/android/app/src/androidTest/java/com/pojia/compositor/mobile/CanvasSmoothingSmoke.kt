package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real history batches are compared with predetermined string positions, without using StrokeInput. */
internal fun canvasSmoothingSmoke(instrumentation: Instrumentation): String {
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val editor = NativeEditor(64, addDefaultLayers = false)
    val reference = NativeEditor(64, addDefaultLayers = false)
    val callbacks = AtomicInteger()
    val failures = CopyOnWriteArrayList<String>()
    lateinit var overlay: FrameLayout
    lateinit var view: CanvasView
    var attached = false
    fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
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
    try {
        editor.addLayer(); reference.addLayer()
        val id = editor.state().layers.single().id; val referenceId = reference.state().layers.single().id
        // Enable size dynamics explicitly so historical pressure loss changes pixels
        // even though ordinary Brush now defaults both pressure options off.
        val settings = StrokeSettings(diameter = 7f, hardness = .65f, opacity = .6f,
            red = .9f, green = .15f, blue = .05f, smoothing = 10f, pressureSize = true)
        instrumentation.runOnMainSync {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, { callbacks.incrementAndGet() }, { failures.add(it) })
            view.layerId = id; view.strokeSettings = settings
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attached = true
        }
        waitUntil("Smoothing canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        instrumentation.runOnMainSync { view.setZoomPercent(200.0) }
        drain(); check(view.zoomPercent == 200.0)
        val original = pixels(editor); val originalRevision = editor.currentRevision()
        val offsetX = (view.width - 128) / 2.0; val offsetY = (view.height - 128) / 2.0
        data class Point(val x: Double, val y: Double, val pressure: Float)
        fun coords(point: Point) = MotionEvent.PointerCoords().apply {
            x = (offsetX + point.x * 2).toFloat(); y = (offsetY + point.y * 2).toFloat()
            pressure = point.pressure; size = 1f
        }
        var down = SystemClock.uptimeMillis()
        fun event(action: Int, offset: Long, point: Point): MotionEvent = MotionEvent.obtain(down, down + offset, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
            arrayOf(coords(point)), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        fun dispatch(event: MotionEvent) {
            try { instrumentation.runOnMainSync { check(view.dispatchTouchEvent(event)) } } finally { event.recycle() }
            drain()
        }
        fun assertPlatformSample(event: MotionEvent, index: Int, point: Point, milliseconds: Long) {
            val historical = index < event.historySize
            val x = if (historical) event.getHistoricalX(0, index) else event.x
            val y = if (historical) event.getHistoricalY(0, index) else event.y
            val pressure = if (historical) event.getHistoricalPressure(0, index) else event.pressure
            val time = if (historical) event.getHistoricalEventTime(index) else event.eventTime
            check(abs((x - offsetX) / 2 - point.x) < .000001 && abs((y - offsetY) / 2 - point.y) < .000001)
            check(pressure == point.pressure && time == down + milliseconds &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) { "Platform changed the intended stylus history fixture" }
        }
        val start = Point(8.5, 8.5, .75f)
        val slack = Point(11.5, 8.5, .25f)
        val firstPull = Point(18.5, 8.5, .65f)
        val secondPull = Point(28.5, 8.5, .35f)
        val downEvent = event(MotionEvent.ACTION_DOWN, 0, start)
        assertPlatformSample(downEvent, 0, start, 0); dispatch(downEvent)
        reference.startStroke(referenceId, settings)
        reference.samples(doubleArrayOf(8.5, 8.5, .75f.toDouble(), 0.0))
        check(pixels(editor).contentEquals(pixels(reference)) && callbacks.get() == 1)
        val move = event(MotionEvent.ACTION_MOVE, 10, slack)
        move.addBatch(down + 20, arrayOf(coords(firstPull)), 0)
        move.addBatch(down + 30, arrayOf(coords(secondPull)), 0)
        check(move.historySize == 2)
        assertPlatformSample(move, 0, slack, 10); assertPlatformSample(move, 1, firstPull, 20)
        assertPlatformSample(move, 2, secondPull, 30)
        dispatch(move)
        // Ten screen pixels at 200% is a five-document-pixel string. The slack point
        // adds no dab; the two pulls end at 13.5 and 23.5, retaining their own pressure/time.
        reference.samples(doubleArrayOf(13.5, 8.5, .65f.toDouble(), .02, 23.5, 8.5, .35f.toDouble(), .03))
        check(pixels(editor).contentEquals(pixels(reference))) { "Real smoothing history lost string positions or historical pressure" }
        check(!pixels(editor).contentEquals(original) && editor.currentRevision() == originalRevision && callbacks.get() == 1)
        val beforeUp = pixels(editor)
        val endpoint = Point(33.5, 8.5, .9f)
        val up = event(MotionEvent.ACTION_UP, 40, endpoint)
        assertPlatformSample(up, 0, endpoint, 40); dispatch(up)
        // Mouse-up first pulls the anchor to 28.5, then catches up the remaining string.
        reference.samples(doubleArrayOf(28.5, 8.5, .9f.toDouble(), .04, 33.5, 8.5, .9f.toDouble(), .04))
        reference.endStroke(false)
        val committed = pixels(editor); val committedRevision = editor.currentRevision()
        check(committed.contentEquals(pixels(reference)) && !committed.contentEquals(beforeUp)) { "Mouse-up omitted or changed the final catch-up segment" }
        check(callbacks.get() == 2 && failures.isEmpty() && committedRevision != originalRevision)
        instrumentation.runOnMainSync { check(!view.strokeInProgress) }
        editor.undo(false); check(pixels(editor).contentEquals(original))
        editor.undo(true); check(pixels(editor).contentEquals(committed))
        val afterHistoryRevision = editor.currentRevision()

        down = SystemClock.uptimeMillis()
        dispatch(event(MotionEvent.ACTION_DOWN, 0, Point(8.5, 40.5, .6f)))
        val cancelMove = event(MotionEvent.ACTION_MOVE, 10, Point(11.5, 40.5, .3f))
        cancelMove.addBatch(down + 20, arrayOf(coords(Point(20.5, 40.5, .8f))), 0)
        dispatch(cancelMove)
        check(!pixels(editor).contentEquals(committed) && callbacks.get() == 3)
        dispatch(event(MotionEvent.ACTION_CANCEL, 30, Point(20.5, 40.5, .8f)))
        check(pixels(editor).contentEquals(committed) && editor.currentRevision() == afterHistoryRevision && callbacks.get() == 4)
        instrumentation.runOnMainSync { check(!view.strokeInProgress) }

        // The latest raw sample is slack at t=30 ms. Backdating to t=25 ms must abort
        // even though the last painted anchor was at t=10 ms; smoothing cannot hide it.
        down = SystemClock.uptimeMillis()
        dispatch(event(MotionEvent.ACTION_DOWN, 0, Point(8.5, 50.5, .7f)))
        val timedMove = event(MotionEvent.ACTION_MOVE, 10, Point(20.5, 50.5, .8f))
        timedMove.addBatch(down + 30, arrayOf(coords(Point(16.5, 50.5, .4f))), 0)
        dispatch(timedMove)
        check(callbacks.get() == 5 && failures.isEmpty())
        dispatch(event(MotionEvent.ACTION_MOVE, 25, Point(17.5, 50.5, .5f)))
        check(callbacks.get() == 6 && failures.size == 1 && failures.single() == activity.getString(R.string.notice_editor_command_failed))
        check(pixels(editor).contentEquals(committed) && editor.currentRevision() == afterHistoryRevision) { "Backdated slack sample failed to roll back the whole stroke" }
        dispatch(event(MotionEvent.ACTION_UP, 40, Point(20.5, 50.5, .7f)))
        check(callbacks.get() == 6 && failures.size == 1 && pixels(editor).contentEquals(committed))
        return "Real CanvasView stylus history at 200% matched predetermined slack/pull positions and pressure-dependent native pixels; UP matched the independent final endpoint, one undo/redo restored pixels, CANCEL restored the whole stroke, and a backdated slack timestamp delivered one failure and rollback. No timing test or test-only input seam."
    } finally {
        if (attached) instrumentation.runOnMainSync { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        editor.close(); reference.close()
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
