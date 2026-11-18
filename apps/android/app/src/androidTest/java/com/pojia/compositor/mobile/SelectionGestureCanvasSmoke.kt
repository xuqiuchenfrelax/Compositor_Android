package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real display-0 touch dispatch; Activity-local keys avoid another display's keyboard focus. */
internal fun selectionGestureCanvasSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val workspaceToken = "p103-selection-${System.nanoTime()}"
    val isolatedRoot = File(context.filesDir, "debug-workspaces/$workspaceToken").canonicalFile
    check(BuildConfig.DEBUG && validatedDebugWorkspaceToken(workspaceToken) == workspaceToken)
    check(!isolatedRoot.exists()) { "Selection workspace token was reused" }
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, workspaceToken)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
    val evidence = File(context.filesDir, "p103-selection-gesture-${System.nanoTime()}").apply { check(mkdir()) }
    var workspace: AndroidWorkspace? = null
    var failure: Throwable? = null
    lateinit var editor: NativeEditor
    lateinit var canvas: CanvasView
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun drain() {
        val done = CountDownLatch(1)
        canvas.queueEvent { done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "Selection GL queue did not drain" }
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }
    fun key(code: Int) {
        val time = SystemClock.uptimeMillis()
        main {
            check(activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))) {
                "Activity did not handle selection/crop key $code"
            }
            activity.dispatchKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0))
        }
        instrumentation.waitForIdleSync()
    }
    fun tool(code: Int, expected: CanvasToolMode) {
        key(code)
        waitUntil("Selection tool $expected was not bound") { canvas.toolMode == expected }
    }
    fun point(x: Double, y: Double): Pair<Float, Float> {
        var result = 0f to 0f
        main {
            val location = IntArray(2)
            canvas.getLocationOnScreen(location)
            result = location[0] + canvas.offsetXForOverlay + (x * canvas.scaleForOverlay).toFloat() to
                location[1] + canvas.offsetYForOverlay + (y * canvas.scaleForOverlay).toFloat()
        }
        return result
    }
    fun pointer(action: Int, point: Pair<Float, Float>, down: Long, time: Long, modifiers: Int = 0) {
        // MotionEvent.obtain creates a default-display (0) touchscreen event. No active-window lookup is used.
        val event = MotionEvent.obtain(down, time, action, point.first, point.second, modifiers)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
    fun gesture(points: List<Pair<Double, Double>>, downModifiers: Int = 0, laterModifiers: Int = 0) {
        val screen = points.map { point(it.first, it.second) }
        val down = SystemClock.uptimeMillis()
        screen.forEachIndexed { index, position ->
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; screen.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            pointer(action, position, down, down + index * 40, if (index == 0) downModifiers else laterModifiers)
        }
        drain()
    }
    fun base(rect: PixelSelectionRect) {
        main {
            canvas.pixelSelection = editor.setPixelSelection(rect)
            canvas.pixelSelectionRegions = editor.pixelSelectionRegions().regions
        }
    }
    fun image(width: Int = editor.canvasWidth, height: Int = editor.canvasHeight): ByteArray {
        val output = ByteBuffer.allocateDirect(width * height * 4)
        check(editor.draw(width, height, 0.0, 0.0, width.toDouble(), height.toDouble(), output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun alpha(bytes: ByteArray, x: Int, y: Int) = bytes[(y * 128 + x) * 4 + 3].toInt() and 255
    try {
        waitUntil("Selection Activity editor unavailable") { activity.retainedEditorForTest() != null }
        val retained = activity.retainedWorkspaceForTest()
        workspace = retained
        check(retained.recoveryDirectoryForTest().canonicalFile == isolatedRoot) {
            "Selection test refused to modify a non-isolated workspace"
        }
        check(retained.installCandidate("p103-selection-gesture", DirtyDisposition.DISCARD) {
            NativeEditor(128, addDefaultLayers = false).also { next ->
                val pixels = ByteBuffer.allocateDirect(128 * 128 * 4)
                repeat(128) { y -> repeat(128) { x ->
                    pixels.put(if (y < 64 && x < 64) (-1).toByte() else 0.toByte())
                    pixels.put(if (y >= 64) (-1).toByte() else 0.toByte())
                    pixels.put(if (y < 64 && x >= 64) (-1).toByte() else 0.toByte())
                    pixels.put((-1).toByte())
                } }
                pixels.rewind(); next.importPixels(128, 128, pixels)
            }
        }.result.get(12, TimeUnit.SECONDS).installed)
        editor = checkNotNull(retained.snapshot().editor)
        val boundEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
        waitUntil("Selection fixture Canvas did not bind on display 0") {
            var ready = false
            main {
                val candidate = findCanvas(activity.window.decorView)
                if (candidate != null && candidate.isAttachedToWindow && candidate.width > 0 && candidate.height > 0 &&
                    candidate.holder.surface.isValid && boundEditor.get(candidate) === editor &&
                    candidate.layerId == editor.state().document.activeLayerId && candidate.keyboardFocusActive) {
                    check(candidate.display.displayId == 0) { "Selection fixture launched on another display" }
                    canvas = candidate; ready = true
                }
            }
            ready
        }
        main { canvas.fit() }
        drain()
        val original = image()
        val initialRevision = editor.currentRevision()

        tool(KeyEvent.KEYCODE_M, CanvasToolMode.MARQUEE)
        val down = SystemClock.uptimeMillis()
        val start = point(16.0, 16.0); val end = point(48.0, 48.0)
        pointer(MotionEvent.ACTION_DOWN, start, down, down)
        pointer(MotionEvent.ACTION_MOVE, end, down, down + 40)
        check(canvas.marqueePreview != null)
        key(KeyEvent.KEYCODE_ESCAPE)
        drain()
        check(canvas.marqueePreview == null) { "Escape retained the pending Marquee outline" }
        pointer(MotionEvent.ACTION_UP, end, down, down + 80)
        drain()
        check(!editor.pixelSelection().active && canvas.marqueePreview == null) { "Marquee selection resurrected after Escape then UP" }

        base(PixelSelectionRect(8.0, 8.0, 16.0, 16.0))
        gesture(listOf(40.0 to 8.0, 56.0 to 24.0, 56.0 to 24.0), KeyEvent.META_SHIFT_ON)
        var regions = editor.pixelSelectionRegions().regions
        check(regions.size == 2 && regions.last().operation == PixelSelectionOperation.ADD &&
            regions.all { it.shape == PixelSelectionShape.RECTANGLE }) { "Shift Marquee must add and retain Rectangle shape: $regions" }
        gesture(listOf(12.0 to 12.0, 20.0 to 20.0, 20.0 to 20.0), KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON)
        check(editor.pixelSelectionRegions().regions.last().operation == PixelSelectionOperation.SUBTRACT) {
            "Option must take precedence over Shift for Marquee"
        }

        tool(KeyEvent.KEYCODE_L, CanvasToolMode.LASSO)
        base(PixelSelectionRect(8.0, 8.0, 16.0, 16.0))
        gesture(listOf(40.0 to 8.0, 56.0 to 8.0, 56.0 to 24.0, 40.0 to 24.0, 40.0 to 8.0), KeyEvent.META_SHIFT_ON)
        regions = editor.pixelSelectionRegions().regions
        check(regions.size == 2 && regions.last().shape == PixelSelectionShape.POLYGON &&
            regions.last().operation == PixelSelectionOperation.ADD) { "Shift Lasso did not add: $regions" }
        gesture(listOf(12.0 to 12.0, 20.0 to 12.0, 20.0 to 20.0, 12.0 to 20.0, 12.0 to 12.0),
            KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON)
        check(editor.pixelSelectionRegions().regions.last().operation == PixelSelectionOperation.SUBTRACT) {
            "Option must take precedence over Shift for Lasso"
        }

        tool(KeyEvent.KEYCODE_W, CanvasToolMode.MAGIC)
        base(PixelSelectionRect(0.0, 0.0, 128.0, 128.0))
        gesture(listOf(30.0 to 30.0, 30.0 to 30.0), KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON)
        check(editor.pixelSelectionRegions().regions.last().operation == PixelSelectionOperation.SUBTRACT) {
            "Magic must retain the DOWN subtraction mode after modifiers release on UP"
        }
        main { check(canvas.cutPixelSelection()) }
        drain()
        val cut = image()
        check(alpha(cut, 30, 30) == 255 && alpha(cut, 90, 30) == 0 && alpha(cut, 30, 90) == 0) {
            "Magic subtraction cut the excluded red quadrant or retained selected blue/green pixels"
        }
        main { check(canvas.command(EditorCommand.Undo(false))) }
        drain()
        check(image().contentEquals(original)) { "Magic Cut Undo did not restore exact pixels" }

        base(PixelSelectionRect(16.25, 24.5, 39.5, 31.0))
        tool(KeyEvent.KEYCODE_C, CanvasToolMode.CROP)
        val expectedRect = CropRect(16.0, 24.0, 56.0, 56.0)
        check(canvas.cropRect == expectedRect) { "Crop did not initialize from integral selection bounds: ${canvas.cropRect}" }
        key(KeyEvent.KEYCODE_ENTER)
        drain()
        check(editor.canvasWidth == 40 && editor.canvasHeight == 32 && canvas.cropRect == null)
        val cropped = image()
        val expectedCrop = ByteArray(40 * 32 * 4)
        repeat(32) { row -> original.copyInto(expectedCrop, row * 40 * 4, ((row + 24) * 128 + 16) * 4,
            ((row + 24) * 128 + 56) * 4) }
        check(cropped.contentEquals(expectedCrop)) { "Selection Crop changed retained source pixel mapping" }
        main { check(canvas.command(EditorCommand.Undo(false))) }
        drain()
        check(editor.canvasWidth == 128 && editor.canvasHeight == 128 && image().contentEquals(original)) {
            "Crop Undo left managed canvas dimensions stale or changed source pixels"
        }
        main { check(canvas.command(EditorCommand.Undo(true))) }
        drain()
        check(editor.canvasWidth == 40 && editor.canvasHeight == 32 && image().contentEquals(cropped)) {
            "Crop Redo did not restore dimensions and exact cropped pixels"
        }
        val cropCheckpoint = File(evidence, "crop-roundtrip.checkpoint")
        editor.save(cropCheckpoint)
        NativeEditor.load(cropCheckpoint).use { reopened ->
            check(reopened.canvasWidth == 40 && reopened.canvasHeight == 32) {
                "Crop checkpoint did not retain canvas dimensions"
            }
            val reopenedPixels = ByteBuffer.allocateDirect(40 * 32 * 4)
            check(reopened.draw(40, 32, 0.0, 0.0, 40.0, 32.0, reopenedPixels))
            val reopenedBytes = ByteArray(reopenedPixels.capacity()).also { reopenedPixels.rewind(); reopenedPixels.get(it) }
            check(reopenedBytes.contentEquals(cropped)) {
                "Crop checkpoint changed exact pixels after save/reopen"
            }
        }
        val revisionAfterRedo = editor.currentRevision()
        check(runCatching { editor.undo(true) }.isFailure) { "Empty Redo unexpectedly succeeded" }
        check(editor.canvasWidth == 40 && editor.canvasHeight == 32 &&
            editor.currentRevision() == revisionAfterRedo && image().contentEquals(cropped)) {
            "Rejected Redo changed canvas dimensions, revision or pixels"
        }
        check(editor.currentRevision() == initialRevision + 5) { "Selection-only gestures entered document history or Crop/Cut replayed" }
        File(evidence, "result.txt").writeText("PASS: display0 real pointer dispatch; Escape mid-Marquee; captured Shift/Option precedence for Marquee/Lasso/Magic; exact Magic Cut+Undo; selection-integral Crop+Undo+Redo pixels and managed dimensions. Keys dispatched only through this Activity; no UiAutomation connection. Independent recovery store: ${isolatedRoot.absolutePath}\n")
        return "Selection gestures and Crop history passed; evidence ${evidence.absolutePath}"
    } catch (error: Throwable) {
        failure = error
        File(evidence, "failure.txt").writeText(error.stackTraceToString())
        throw error
    } finally {
        val cleanup = runCatching {
            main { activity.finish() }
            workspace?.closeAsync()?.get(12, TimeUnit.SECONDS)
        }.exceptionOrNull()
        if (cleanup != null) failure?.addSuppressed(cleanup) ?: throw cleanup
    }
}
