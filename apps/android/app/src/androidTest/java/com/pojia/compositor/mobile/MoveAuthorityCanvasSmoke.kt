package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Shipping CanvasView dispatch, held native worker, UP-before-ack and retired cancellation.
 * The View is not attached to a GPU surface; this is pointer/controller correctness, not GLES/UI visual QA. */
internal fun moveAuthorityCanvasSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    val editor = NativeEditor(64,false)
    var view: CanvasView? = null
    fun waitFor(message: String, condition: () -> Boolean) {
        val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime()<end) { Thread.sleep(10); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    fun event(canvas: CanvasView, down: Long, action: Int, x: Float, y: Float, modifiers: Int = 0) {
        val value = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,modifiers)
        try { check(canvas.onTouchEvent(value)) } finally { value.recycle() }
    }
    try {
        val a = editor.importPixels(1,1,ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(32,0,0,-1)); rewind() })
        val b = editor.importPixels(1,1,ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(64,0,0,-1)); rewind() })
        editor.setTransform(a,Transform64(20.0,20.0,16.0,16.0))
        editor.setTransform(b,Transform64(20.0,20.0,16.0,16.0))
        editor.selectLayer(a)
        val before = editor.state()
        instrumentation.runOnMainSync {
            val canvas = CanvasView(context,editor,{}, { throw IllegalStateException(it) })
            view=canvas; canvas.layout(0,0,64,64); canvas.actualPixels()
            canvas.toolMode=CanvasToolMode.MOVE; canvas.layerId=a; canvas.moveAutoSelect=true; canvas.moveShowControls=false
            editor.serialized {
                val down=SystemClock.uptimeMillis()
                event(canvas,down,MotionEvent.ACTION_DOWN,28f,28f)
                event(canvas,down,MotionEvent.ACTION_MOVE,36f,32f,KeyEvent.META_CTRL_MASK)
                event(canvas,down,MotionEvent.ACTION_UP,36f,32f,KeyEvent.META_CTRL_MASK)
                check(!editor.hasActiveTransformDraft) { "Worker escaped the held editor gate" }
            }
        }
        val canvas=checkNotNull(view)
        waitFor("UP-before-ack did not commit and settle") { !canvas.moveInProgress && editor.activeTransformCompletion()==null }
        check(editor.selection().primary==b)
        check(editor.state().layers.first { it.id==a }.transform.x==20.0)
        val moved=editor.state().layers.first { it.id==b }.transform
        check(moved.x==28.0 && moved.y==24.0)
        check(editor.currentRevision()==before.revision+2) { "Expected selection publication plus one transform history entry" }
        editor.undo(false)
        check(editor.state().layers.first { it.id==b }.transform.x==20.0)
        editor.selectLayer(a)
        val content=editor.currentContentIdentity()
        instrumentation.runOnMainSync {
            editor.serialized {
                canvas.layerId=a
                val down=SystemClock.uptimeMillis()
                event(canvas,down,MotionEvent.ACTION_DOWN,28f,28f)
                event(canvas,down,MotionEvent.ACTION_MOVE,40f,40f,KeyEvent.META_CTRL_MASK)
                canvas.retire()
                check(!editor.hasActiveTransformDraft)
            }
        }
        waitFor("Retired pending press left an owner/draft") { editor.activeTransformCompletion()==null && !editor.hasActiveTransformDraft }
        check(editor.currentContentIdentity()==content)
        check(editor.state().layers.all { it.transform.x==20.0 && it.transform.y==20.0 })
        check(!editor.hasUnresolvedMovePress)
        untransformableSelectionAutoSelectSmoke(instrumentation)
        return "PASS: shipping Canvas dispatch held-worker Auto Select, latest MOVE/UP-before-ack, one history commit/undo, retired DOWN cancels actual native token"
    } finally {
        instrumentation.runOnMainSync { view?.retire(); view?.onPause() }
        editor.close()
        check(ledger.snapshot().reservedBytes==initial.reservedBytes && ledger.snapshot().reservations==initial.reservations)
    }
}

/** An absent presented transform cannot suppress a real canvas pick from empty selection or folder. */
private fun untransformableSelectionAutoSelectSmoke(instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    NativeEditor(64, false).use { editor ->
        val pixel = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(64, 0, 0, -1)); rewind()
        })
        editor.setTransform(pixel, Transform64(20.0, 20.0, 16.0, 16.0))
        val empty = editor.addGroup()
        for (primary in listOf(0L, empty)) {
            editor.selectLayer(primary)
            val selected = editor.selection()
            val before = editor.state()
            check(MoveGeometry.selection(before, selected.roots, primary, false) == null)
            var view: CanvasView? = null
            try {
                instrumentation.runOnMainSync {
                    val canvas = CanvasView(context, editor, {}, { throw IllegalStateException(it) })
                    view = canvas; canvas.layout(0, 0, 64, 64); canvas.actualPixels()
                    canvas.toolMode = CanvasToolMode.MOVE; canvas.layerId = primary
                    canvas.moveAutoSelect = true; canvas.moveShowControls = false
                    val down = SystemClock.uptimeMillis()
                    for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 28f, MotionEvent.ACTION_MOVE to 36f,
                        MotionEvent.ACTION_UP to 36f)) {
                        val modifiers = if (action == MotionEvent.ACTION_DOWN) 0 else KeyEvent.META_CTRL_MASK
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, 28f, modifiers)
                        try { check(canvas.onTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                val canvas = checkNotNull(view)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ((canvas.moveInProgress || editor.activeTransformCompletion() != null) && System.nanoTime() < deadline) {
                    Thread.sleep(10); instrumentation.waitForIdleSync()
                }
                check(!canvas.moveInProgress && editor.activeTransformCompletion() == null)
                check(editor.selection().primary == pixel && editor.selection().roots == listOf(pixel))
                check(editor.state().layers.first { it.id == pixel }.transform.x == 28.0)
                check(editor.currentRevision() == before.revision + 2 && !editor.hasUnresolvedMovePress)
                editor.undo(false)
                check(editor.state().layers.first { it.id == pixel }.transform.x == 20.0)
            } finally { instrumentation.runOnMainSync { view?.retire(); view?.onPause() } }
        }
    }
    check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
}
