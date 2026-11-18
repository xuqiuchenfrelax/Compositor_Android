package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Scripted line origins exercise real Shift pointer metadata without querying any candidate anchor. */
internal fun shiftLineSmoke(instrumentation: Instrumentation): String {
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
    fun mask(document: NativeEditor, id: Long): ByteArray {
        val layer = document.state().layers.first { it.id == id }
        val buffer = ByteBuffer.allocateDirect(layer.maskWidth * layer.maskHeight)
        document.maskPixels(id, layer.maskWidth, buffer)
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    try {
        editor.addLayer(); reference.addLayer()
        val id = editor.state().layers.single().id; val referenceId = reference.state().layers.single().id
        instrumentation.runOnMainSync {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, { callbacks.incrementAndGet() }, { failures.add(it) })
            view.layerId = id
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attached = true
        }
        waitUntil("Shift canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        instrumentation.runOnMainSync { view.setZoomPercent(200.0) }
        drain()
        var offsetX = (view.width - 128) / 2.0; var offsetY = (view.height - 128) / 2.0
        data class Point(val x: Double, val y: Double)
        fun coords(point: Point, pressure: Float) = MotionEvent.PointerCoords().apply {
            x = (offsetX + point.x * 2).toFloat(); y = (offsetY + point.y * 2).toFloat()
            this.pressure = pressure; size = 1f
        }
        var down = SystemClock.uptimeMillis()
        fun event(action: Int, offset: Long, point: Point, pressure: Float, shift: Boolean = false): MotionEvent =
            MotionEvent.obtain(down, down + offset, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
                arrayOf(coords(point, pressure)), if (shift) KeyEvent.META_SHIFT_ON else 0,
                0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        fun dispatch(event: MotionEvent, await: Boolean = true) {
            try { instrumentation.runOnMainSync { check(view.dispatchTouchEvent(event)) } } finally { event.recycle() }
            if (await) drain()
        }
        fun tap(point: Point, settings: StrokeSettings, pressure: Float, shift: Boolean = false,
            afterDown: (() -> Unit)? = null) {
            down = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync { view.strokeSettings = settings }
            val start = event(MotionEvent.ACTION_DOWN, 0, point, pressure, shift)
            check((start.metaState and KeyEvent.META_SHIFT_ON != 0) == shift)
            dispatch(start)
            afterDown?.let { action -> instrumentation.runOnMainSync { action() } }
            // Releasing Shift before UP does not undo the line requested by DOWN.
            dispatch(event(MotionEvent.ACTION_UP, 10, point, pressure))
        }
        fun expectedTap(target: Long, settings: StrokeSettings, point: Point, pressure: Float,
            from: Point? = null, editingMask: Boolean = false) {
            reference.startStroke(target, settings, mask = editingMask)
            if (from != null) reference.samples(doubleArrayOf(from.x, from.y, pressure.toDouble(), 0.0))
            reference.samples(doubleArrayOf(point.x, point.y, pressure.toDouble(), 0.0,
                point.x, point.y, pressure.toDouble(), .01))
            reference.endStroke(false)
        }
        fun samePixels(reason: String) { check(pixels(editor).contentEquals(pixels(reference))) { reason } }
        // Explicit size dynamics preserve the pressure-sensitive oracle after the
        // ordinary Brush default changed to pressure-independent painting.
        val red = StrokeSettings(diameter = 7f, hardness = .8f, opacity = .5f, red = 1f, pressureSize = true)
        val green = StrokeSettings(diameter = 9f, hardness = .7f, opacity = .35f, green = 1f, pressureSize = true)
        val a = Point(8.5, 8.5); val b = Point(36.5, 20.5)
        tap(a, red, .8f); expectedTap(referenceId, red, a, .8f)
        samePixels("First tap failed"); val first = pixels(editor)
        val beforeLineRevision = editor.currentRevision()
        tap(b, green, .6f, shift = true, afterDown = {
            view.strokeSettings = StrokeSettings(diameter = 15f, opacity = 1f, blue = 1f, smoothing = 50f)
        })
        expectedTap(referenceId, green, b, .6f, from = a)
        samePixels("Shift DOWN failed to connect the prior endpoint using the new stroke's captured settings")
        check(callbacks.get() == 4 && failures.isEmpty() && editor.currentRevision() != beforeLineRevision)
        val line = pixels(editor)
        editor.undo(false); check(pixels(editor).contentEquals(first)) { "One undo did not remove the whole Shift line" }
        editor.undo(true); check(pixels(editor).contentEquals(line))

        // Mac's lastBrushPoint survives cancelBrush. A valid DOWN followed by CANCEL
        // rolls back pixels but the next Shift origin is the successfully appended canceled point.
        val c = Point(50.5, 50.5); val d = Point(8.5, 50.5)
        down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { view.strokeSettings = red }
        val revision = editor.currentRevision()
        dispatch(event(MotionEvent.ACTION_DOWN, 0, c, .7f))
        check(!pixels(editor).contentEquals(line))
        dispatch(event(MotionEvent.ACTION_CANCEL, 10, c, .7f))
        check(pixels(editor).contentEquals(line) && editor.currentRevision() == revision)
        tap(d, green, .9f, shift = true); expectedTap(referenceId, green, d, .9f, from = c)
        samePixels("Cancel cleared or replaced Mac's last successfully appended Shift origin")

        val e = Point(50.5, 35.5); val f = Point(8.5, 35.5)
        val beforeError = pixels(editor); val beforeErrorRevision = editor.currentRevision()
        down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { view.strokeSettings = red }
        dispatch(event(MotionEvent.ACTION_DOWN, 0, e, .65f))
        dispatch(event(MotionEvent.ACTION_MOVE, -1, Point(48.5, 35.5), .5f))
        check(failures.size == 1 && failures.single() == activity.getString(R.string.notice_editor_command_failed))
        check(pixels(editor).contentEquals(beforeError) && editor.currentRevision() == beforeErrorRevision)
        tap(f, green, .8f, shift = true); expectedTap(referenceId, green, f, .8f, from = e)
        samePixels("Error changed the prior successful origin or retained the rejected point")

        // A successful stroke on the mask overwrites the target tuple; switching back
        // to pixels starts normally rather than drawing from either old target.
        editor.createMask(id, true); reference.createMask(referenceId, true)
        val black = StrokeSettings(diameter = 6f, hardness = .75f, maskPaintWhite = false, pressureSize = true)
        val m = Point(12.5, 58.5)
        instrumentation.runOnMainSync { view.editingMask = true }
        tap(m, black, .9f, shift = true); expectedTap(referenceId, black, m, .9f, editingMask = true)
        check(mask(editor, id).contentEquals(mask(reference, referenceId))) { "Shift bridged from pixels onto a different mask target" }
        val g = Point(50.5, 8.5)
        instrumentation.runOnMainSync { view.editingMask = false }
        tap(g, red, .75f, shift = true); expectedTap(referenceId, red, g, .75f)
        samePixels("Switching from a painted mask reused a pixel origin")

        // Merely visiting another target and returning does not clear Mac's matching tuple.
        instrumentation.runOnMainSync { view.editingMask = true; view.editingMask = false }
        val h = Point(50.5, 28.5)
        tap(h, green, .6f, shift = true); expectedTap(referenceId, green, h, .6f, from = g)
        samePixels("An unpainted target round trip incorrectly cleared the Shift origin")

        editor.addLayer(); reference.addLayer()
        val other = editor.state().layers.last().id; val referenceOther = reference.state().layers.last().id
        instrumentation.runOnMainSync { view.layerId = other }
        val n = Point(32.5, 32.5)
        tap(n, red, .8f, shift = true); expectedTap(referenceOther, red, n, .8f)
        samePixels("Shift bridged across layer IDs")
        instrumentation.runOnMainSync { view.layerId = id }
        val k = Point(50.5, 40.5)
        tap(k, green, .7f, shift = true); expectedTap(referenceId, green, k, .7f)
        samePixels("A stroke on another layer did not replace the target tuple")

        // Hold the existing GL queue while two ordinary gestures arrive. The Shift
        // DOWN must use the preceding queued stroke's result, not an older UI snapshot.
        instrumentation.runOnMainSync { view.layerId = other }
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        view.queueEvent { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        check(blocked.await(10, TimeUnit.SECONDS))
        val p = Point(36.5, 58.5); val q = Point(58.5, 58.5)
        try {
            down = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync { view.strokeSettings = red }
            dispatch(event(MotionEvent.ACTION_DOWN, 0, p, .8f), await = false)
            dispatch(event(MotionEvent.ACTION_UP, 10, p, .8f), await = false)
            down = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync { view.strokeSettings = green }
            dispatch(event(MotionEvent.ACTION_DOWN, 0, q, .65f, shift = true), await = false)
            dispatch(event(MotionEvent.ACTION_UP, 10, q, .65f), await = false)
        } finally { release.countDown() }
        drain()
        expectedTap(referenceOther, red, p, .8f)
        expectedTap(referenceOther, green, q, .65f, from = p)
        samePixels("Queued Shift DOWN used a stale origin before the previous native stroke completed")

        // Current Mac source keeps the smoothed anchor, not the finish-only catch-up point.
        val smooth = green.copy(smoothing = 10f)
        val smoothStart = Point(8.5, 44.5); val smoothPointer = Point(28.5, 44.5)
        down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { view.strokeSettings = smooth }
        dispatch(event(MotionEvent.ACTION_DOWN, 0, smoothStart, .8f))
        dispatch(event(MotionEvent.ACTION_MOVE, 10, smoothPointer, .7f))
        dispatch(event(MotionEvent.ACTION_UP, 20, smoothPointer, .9f))
        reference.startStroke(referenceOther, smooth)
        reference.samples(doubleArrayOf(8.5, 44.5, .8f.toDouble(), 0.0,
            23.5, 44.5, .7f.toDouble(), .01, 28.5, 44.5, .9f.toDouble(), .02))
        reference.endStroke(false)
        samePixels("Smoothing fixture did not produce the predetermined final catch-up")
        val next = Point(50.5, 44.5)
        tap(next, red, .6f, shift = true)
        expectedTap(referenceOther, red, next, .6f, from = Point(23.5, 44.5))
        samePixels("Shift used the final raw pointer instead of Mac's last smoothed anchor")

        val previousView = view
        val beforeRecreation = pixels(editor); val recreationRevision = editor.currentRevision()
        instrumentation.runOnMainSync {
            previousView.retire(); overlay.removeView(previousView)
            view = CanvasView(activity, editor, { callbacks.incrementAndGet() }, { failures.add(it) })
            view.layerId = other
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        check(view !== previousView)
        waitUntil("Recreated Shift canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        instrumentation.runOnMainSync { view.setZoomPercent(200.0) }
        drain()
        offsetX = (view.width - 128) / 2.0; offsetY = (view.height - 128) / 2.0
        check(editor.currentRevision() == recreationRevision && pixels(editor).contentEquals(beforeRecreation))
        val recreatedEndpoint = Point(50.5, 60.5)
        tap(recreatedEndpoint, green, .7f, shift = true)
        expectedTap(referenceOther, green, recreatedEndpoint, .7f, from = next)
        samePixels("New CanvasView on the same NativeEditor identity lost the retained Shift origin")

        // The DOWN has already reached native code. Hold only its subsequent UP/commit.
        // A file-style guard must continue to refuse synchronous prepare/cancel in this window.
        val beforeHeldCommit = pixels(editor); val heldRevision = editor.currentRevision()
        val heldStart = Point(42.5, 42.5); val heldEnd = Point(55.5, 48.5)
        down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { view.strokeSettings = red }
        dispatch(event(MotionEvent.ACTION_DOWN, 0, heldStart, .8f))
        val commitBlocked = CountDownLatch(1); val commitRelease = CountDownLatch(1)
        view.queueEvent { commitBlocked.countDown(); check(commitRelease.await(10, TimeUnit.SECONDS)) }
        check(commitBlocked.await(10, TimeUnit.SECONDS))
        try {
            dispatch(event(MotionEvent.ACTION_UP, 10, heldEnd, .7f), await = false)
            var filePrepared = false
            instrumentation.runOnMainSync {
                if (!view.strokeInProgress) { filePrepared = true; editor.endStroke(true) }
            }
            check(!filePrepared) { "Released pointer allowed File prepare to cancel a queued native commit" }
            check(editor.currentRevision() == heldRevision)
        } finally { commitRelease.countDown() }
        drain()
        instrumentation.runOnMainSync { check(!view.strokeInProgress) }
        reference.startStroke(referenceOther, red)
        reference.samples(doubleArrayOf(heldStart.x, heldStart.y, .8f.toDouble(), 0.0,
            heldEnd.x, heldEnd.y, .7f.toDouble(), .01))
        reference.endStroke(false)
        samePixels("Pending native finish gate lost the released stroke")
        check(editor.currentRevision() != heldRevision)
        val heldCommitted = pixels(editor)
        editor.undo(false); check(pixels(editor).contentEquals(beforeHeldCommit))
        editor.undo(true); check(pixels(editor).contentEquals(heldCommitted))

        data class TimedPoint(val x: Double, val y: Double, val pressure: Float, val milliseconds: Long)
        data class AxisGroup(val shift: Boolean, val raw: List<TimedPoint>, val expected: List<TimedPoint>)
        fun axisCase(label: String, settings: StrokeSettings, start: Point, downShift: Boolean,
            groups: List<AxisGroup>, up: TimedPoint) {
            editor.addLayer(); reference.addLayer()
            val target = editor.state().layers.last().id; val oracleTarget = reference.state().layers.last().id
            instrumentation.runOnMainSync { view.layerId = target; view.strokeSettings = settings }
            down = SystemClock.uptimeMillis()
            dispatch(event(MotionEvent.ACTION_DOWN, 0, start, .8f, downShift))
            reference.startStroke(oracleTarget, settings)
            reference.samples(doubleArrayOf(start.x, start.y, .8f.toDouble(), 0.0))
            for (group in groups) {
                val firstPoint = group.raw.first()
                val batch = event(MotionEvent.ACTION_MOVE, firstPoint.milliseconds,
                    Point(firstPoint.x, firstPoint.y), firstPoint.pressure, group.shift)
                for (point in group.raw.drop(1)) batch.addBatch(down + point.milliseconds,
                    arrayOf(coords(Point(point.x, point.y), point.pressure)),
                    if (group.shift) KeyEvent.META_SHIFT_ON else 0)
                check(batch.historySize == group.raw.size - 1)
                group.raw.forEachIndexed { index, point ->
                    val historical = index < batch.historySize
                    val pressure = if (historical) batch.getHistoricalPressure(0, index) else batch.pressure
                    val time = if (historical) batch.getHistoricalEventTime(index) else batch.eventTime
                    check(pressure == point.pressure && time == down + point.milliseconds)
                }
                dispatch(batch)
                if (group.expected.isNotEmpty()) reference.samples(group.expected.flatMap { point ->
                    listOf(point.x, point.y, point.pressure.toDouble(), point.milliseconds / 1000.0)
                }.toDoubleArray())
                samePixels("$label: MOVE/history failed independently scripted constrained positions")
            }
            // UP has Shift metadata but must bypass the drag axis, as Mac mouseUp does.
            dispatch(event(MotionEvent.ACTION_UP, up.milliseconds, Point(up.x, up.y), up.pressure, shift = true))
            reference.samples(doubleArrayOf(up.x, up.y, up.pressure.toDouble(), up.milliseconds / 1000.0))
            reference.endStroke(false)
            samePixels("$label: Shift UP did not preserve the raw endpoint")
        }
        val axisBrush = StrokeSettings(diameter = 5f, opacity = .7f, red = .1f, green = .3f, blue = .9f, pressureSize = true)
        axisCase("Horizontal threshold and sticky axis", axisBrush, Point(8.5, 12.5), true, listOf(
            AxisGroup(true, listOf(
                TimedPoint(9.5, 14.5, .25f, 10), TimedPoint(11.5, 12.5, .6f, 20), TimedPoint(12.5, 28.5, .9f, 30)),
                listOf(TimedPoint(8.5, 12.5, .25f, 10), TimedPoint(11.5, 12.5, .6f, 20), TimedPoint(12.5, 12.5, .9f, 30)))
        ), TimedPoint(15.5, 25.5, .75f, 40))
        axisCase("Vertical history and sticky axis", axisBrush, Point(30.5, 8.5), true, listOf(
            AxisGroup(true, listOf(
                TimedPoint(32.5, 10.5, .3f, 10), TimedPoint(31.5, 13.5, .65f, 20), TimedPoint(50.5, 14.5, .9f, 30)),
                listOf(TimedPoint(30.5, 8.5, .3f, 10), TimedPoint(30.5, 13.5, .65f, 20), TimedPoint(30.5, 14.5, .9f, 30)))
        ), TimedPoint(40.5, 16.5, .8f, 40))
        axisCase("Press/release/rearm", axisBrush, Point(8.5, 30.5), false, listOf(
            AxisGroup(false, listOf(TimedPoint(14.5, 33.5, .4f, 10)), listOf(TimedPoint(14.5, 33.5, .4f, 10))),
            AxisGroup(true, listOf(TimedPoint(15.5, 34.5, .3f, 20), TimedPoint(15.5, 39.5, .7f, 30)),
                listOf(TimedPoint(14.5, 33.5, .3f, 20), TimedPoint(14.5, 39.5, .7f, 30))),
            AxisGroup(false, listOf(TimedPoint(25.5, 44.5, .55f, 40)), listOf(TimedPoint(25.5, 44.5, .55f, 40))),
            AxisGroup(true, listOf(TimedPoint(27.5, 45.5, .2f, 50), TimedPoint(32.5, 45.5, .9f, 60), TimedPoint(33.5, 58.5, .6f, 70)),
                listOf(TimedPoint(25.5, 44.5, .2f, 50), TimedPoint(32.5, 44.5, .9f, 60), TimedPoint(33.5, 44.5, .6f, 70)))
        ), TimedPoint(40.5, 54.5, .7f, 80))
        // The mid-stroke Shift origin is the last pre-smoothing pointer, not the trailing brush.
        axisCase("Shift origin before smoothing", axisBrush.copy(smoothing = 10f), Point(8.5, 8.5), false, listOf(
            AxisGroup(false, listOf(TimedPoint(18.5, 8.5, .5f, 10)), listOf(TimedPoint(13.5, 8.5, .5f, 10))),
            AxisGroup(true, listOf(TimedPoint(20.5, 8.5, .3f, 20)), emptyList()),
            AxisGroup(true, listOf(TimedPoint(21.5, 8.5, .7f, 30)), listOf(TimedPoint(16.5, 8.5, .7f, 30)))
        ), TimedPoint(21.5, 8.5, .9f, 40))

        editor.addLayer(); reference.addLayer()
        instrumentation.runOnMainSync { view.layerId = editor.state().layers.last().id; view.strokeSettings = axisBrush }
        val beforeInvalid = pixels(editor); val invalidRevision = editor.currentRevision()
        val invalidStart = Point(24.5, 24.5)
        down = SystemClock.uptimeMillis()
        dispatch(event(MotionEvent.ACTION_DOWN, 0, invalidStart, .8f, shift = true))
        dispatch(event(MotionEvent.ACTION_MOVE, 10, Point(25.5, 25.5), .4f, shift = true))
        val beforeAbortCallbacks = callbacks.get()
        val badGeometry = event(MotionEvent.ACTION_MOVE, 20, Point(Double.NaN, 25.5), .6f, shift = true)
        check(!badGeometry.x.isFinite()) { "Platform did not preserve the malformed coordinate fixture" }
        dispatch(badGeometry)
        check(failures.size == 2 && failures.last() == activity.getString(R.string.notice_editor_command_failed) && callbacks.get() == beforeAbortCallbacks + 1)
        check(pixels(editor).contentEquals(beforeInvalid) && editor.currentRevision() == invalidRevision) {
            "Below-threshold Shift projection hid raw NaN geometry or failed to roll back the whole stroke"
        }
        instrumentation.runOnMainSync { check(!view.strokeInProgress) }
        dispatch(event(MotionEvent.ACTION_UP, 30, invalidStart, .8f, shift = true))
        check(callbacks.get() == beforeAbortCallbacks + 1 && failures.size == 2)
        samePixels("Malformed Shift geometry changed the committed image")
        return "Actual stylus Shift DOWN lines and MOVE axes matched scripted native pixels: captured settings, undo/redo, cancel/error origins, target isolation, FIFO and cursor retention after new CanvasView; 3-document-pixel threshold, sticky axes, history, press/release/rearm, pre-smoothing anchor and raw Shift UP. Held native commit blocked File prepare until acknowledgement, then preserved pixels/history. Raw NaN before axis projection aborted once and restored the whole stroke. No timing test, ABI or schema change."
    } finally {
        if (attached) instrumentation.runOnMainSync { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        editor.close(); reference.close()
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
