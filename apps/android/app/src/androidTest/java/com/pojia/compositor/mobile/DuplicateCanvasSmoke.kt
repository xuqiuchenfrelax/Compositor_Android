package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONObject

internal fun duplicateCanvasSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    val editor = NativeEditor(64, false)
    val fixtureFile = File(context.filesDir, "p41-duplicate-canvas-fixture.json")
    var view: CanvasView? = null
    fun awaitIdle() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < end && (view?.moveInProgress == true || editor.activeTransformCompletion() != null)) {
            Thread.sleep(10); instrumentation.waitForIdleSync()
        }
        check(view?.moveInProgress == false && editor.activeTransformCompletion() == null)
        check(!editor.hasUnresolvedMovePress && !editor.hasUnresolvedDuplicate && !editor.hasUnresolvedDuplicateCancel)
    }
    fun input(canvas: CanvasView, down: Long, action: Int, x: Float, flags: Int = 0, y: Float = 28f) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, flags)
        try { check(canvas.onTouchEvent(event)) } finally { event.recycle() }
    }
    try {
        val source = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(80, 0, 0, -1)); rewind()
        })
        editor.setTransform(source, Transform64(20.0, 20.0, 16.0, 16.0))
        editor.selectLayer(source)
        instrumentation.runOnMainSync {
            view = CanvasView(context, editor, {}, { throw IllegalStateException(it) }).also {
                it.layout(0, 0, 64, 64); it.actualPixels(); it.toolMode = CanvasToolMode.MOVE
                it.layerId = source; it.moveShowControls = false
            }
        }
        val canvas = checkNotNull(view)
        val baseline = editor.state().layers.size

        instrumentation.runOnMainSync {
            canvas.moveDuplicateArmed = true
            val down = SystemClock.uptimeMillis()
            input(canvas, down, MotionEvent.ACTION_DOWN, 28f)
            input(canvas, down, MotionEvent.ACTION_UP, 28f)
        }
        awaitIdle()
        check(editor.state().layers.size == baseline) { "A duplicate-armed click copied a layer" }

        instrumentation.runOnMainSync {
            editor.serialized {
                val down = SystemClock.uptimeMillis()
                input(canvas, down, MotionEvent.ACTION_DOWN, 28f)
                input(canvas, down, MotionEvent.ACTION_MOVE, 28f)
                input(canvas, down, MotionEvent.ACTION_UP, 28f)
                check(editor.state().layers.size == baseline) { "Queued pointer work escaped the held DOWN gate" }
            }
        }
        awaitIdle()
        check(editor.state().layers.size == baseline + 1) { "Same-coordinate first MOVE did not copy" }
        check(editor.state().layers.first { it.id == source }.transform.x == 20.0)

        editor.selectLayer(source)
        instrumentation.runOnMainSync {
            canvas.layerId = source; canvas.moveDuplicateArmed = false
            val down = SystemClock.uptimeMillis()
            input(canvas, down, MotionEvent.ACTION_DOWN, 28f)
            input(canvas, down, MotionEvent.ACTION_MOVE, 32f, KeyEvent.META_ALT_MASK)
            input(canvas, down, MotionEvent.ACTION_UP, 32f)
        }
        awaitIdle()
        check(editor.state().layers.size == baseline + 1) { "Late Alt copied an ordinary drag" }

        editor.selectLayer(source)
        instrumentation.runOnMainSync {
            canvas.layerId = source
            val down = SystemClock.uptimeMillis()
            input(canvas, down, MotionEvent.ACTION_DOWN, 28f, KeyEvent.META_ALT_MASK)
            input(canvas, down, MotionEvent.ACTION_MOVE, 30f)
            input(canvas, down, MotionEvent.ACTION_UP, 30f)
        }
        awaitIdle()
        check(editor.state().layers.size == baseline + 2) { "Releasing Alt after DOWN lost duplicate intent" }

        editor.selectLayer(source)
        instrumentation.runOnMainSync {
            canvas.layerId = source; canvas.moveDuplicateArmed = true
            val down = SystemClock.uptimeMillis()
            input(canvas, down, MotionEvent.ACTION_DOWN, 28f)
            input(canvas, down, MotionEvent.ACTION_CANCEL, 28f)
        }
        awaitIdle()
        check(editor.state().layers.size == baseline + 2)

        val group = editor.addGroup()
        val child = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(0, 80, 0, -1)); rewind()
        })
        editor.setTransform(child, Transform64(8.0, 4.0, 48.0, 48.0))
        editor.reparentLayer(child, group)
        editor.selectLayer(group)
        var frozen: Transform64? = null
        instrumentation.runOnMainSync {
            canvas.layout(0, 0, 256, 256); canvas.setZoomPercent(400.0)
            canvas.layerId = group; canvas.moveDuplicateArmed = false; canvas.moveAutoSelect = true
            check(canvas.startPersistentMove())
            check(canvas.editMoveField(MoveField.WIDTH, 50.0))
            check(canvas.editMoveField(MoveField.ROTATION, 30.0))
            frozen = canvas.moveBox
        }
        val downBox = checkNotNull(frozen)
        check(downBox.rotation == 30.0 && downBox.width == 50.0)
        val downScreen = MovePoint(132.0, 116.0)
        val moveScreen = MovePoint(148.0, 116.0)
        val origin = canvas.moveOverlay(Transform64(0.0, 0.0, 1.0, 1.0)).handles[0]
        val scale = canvas.zoomPercent / 100.0
        val downDocument = MovePoint((downScreen.x - origin.x) / scale, (downScreen.y - origin.y) / scale)
        val moveDocument = MovePoint((moveScreen.x - origin.x) / scale, (moveScreen.y - origin.y) / scale)
        val hit = canvas.moveOverlay(downBox).hit(downScreen)
        val fixture = JSONObject().put("density", context.resources.displayMetrics.density)
            .put("viewWidth", canvas.width).put("viewHeight", canvas.height).put("zoomPercent", canvas.zoomPercent)
            .put("downBox", downBox.toString()).put("origin", origin.toString())
            .put("downScreen", downScreen.toString()).put("moveScreen", moveScreen.toString())
            .put("downDocument", downDocument.toString()).put("moveDocument", moveDocument.toString())
            .put("handle", hit?.toString() ?: JSONObject.NULL)
        fixtureFile.writeText(fixture.toString(2))
        check(canvas.zoomPercent == 400.0)
        check(abs(downDocument.x - 33.0) < 1e-9 && abs(downDocument.y - 29.0) < 1e-9 &&
            abs(moveDocument.x - 37.0) < 1e-9 && abs(moveDocument.y - 29.0) < 1e-9)
        check(hit == null) {
            "Persistent duplicate fixture DOWN hit a transform handle"
        }
        val groupBaseline = editor.state().layers.size
        val down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            input(canvas, down, MotionEvent.ACTION_DOWN, 132f, KeyEvent.META_ALT_MASK, 116f)
            input(canvas, down, MotionEvent.ACTION_MOVE, 148f,
                KeyEvent.META_CTRL_MASK or KeyEvent.META_SHIFT_MASK, 116f)
        }
        val expected = MoveGeometry.drag(downBox, MovePoint(33.0, 29.0), MovePoint(37.0, 29.0),
            shift = true, snap = false, targets = MoveSnapTargets(emptyList(), emptyList()),
            pointsPerPixel = 4.0 / context.resources.displayMetrics.density).box
        val previewDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < previewDeadline &&
            (editor.duplicateLayerPresentation() == null || canvas.moveBox != expected)) {
            Thread.sleep(10); instrumentation.waitForIdleSync()
        }
        val actualBox = canvas.moveBox
        fixture.put("expectedBox", expected.toString())
            .put("actualBox", actualBox?.toString() ?: JSONObject.NULL)
            .put("presentation", editor.duplicateLayerPresentation()?.toString() ?: JSONObject.NULL)
            .put("selection", editor.selection().toString())
            .put("transformSnapshot", editor.retainedTransformController(context).snapshot()?.toString() ?: JSONObject.NULL)
        fixtureFile.writeText(fixture.toString(2))
        check(actualBox == expected) { "Persistent group drag lost frozen DOWN geometry" }
        val presented = checkNotNull(editor.duplicateLayerPresentation())
        check(presented.mapping.any { it.sourceId == group && it.root }) {
            "Persistent replay changed selected roots under Auto Select"
        }
        check(editor.selection().roots == listOf(group))
        check(MoveGeometry.selectionBox(editor.state(), listOf(group)) != downBox) {
            "Fixture did not distinguish committed group bounds from frozen DOWN box"
        }
        instrumentation.runOnMainSync { input(canvas, down, MotionEvent.ACTION_UP, 148f, y = 116f) }
        awaitIdle()
        check(editor.state().layers.size == groupBaseline + 2)
        return "Canvas duplicate: touch arm, Alt latch, first MOVE, persistent rotated group geometry/selection, queued DOWN/UP and cancel."
    } finally {
        instrumentation.runOnMainSync { view?.retire(); view?.onPause() }
        editor.close()
        val final = ledger.snapshot()
        check(final.reservedBytes == initial.reservedBytes && final.reservations == initial.reservations)
    }
}
