package com.pojia.compositor.mobile

import android.app.ActivityOptions
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
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Physical display touch and Activity shortcuts, with separate recovery data and exact history checks. */
internal fun toolCanvasWorkflowSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val token = "p103-tools-${System.nanoTime()}"
    check(validatedDebugWorkspaceToken(token) == token)
    val root = File(context.filesDir, "debug-workspaces/$token").canonicalFile
    check(!root.exists())
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
    val evidence = File(context.filesDir, "$token-evidence")
    var workspace = activity.retainedWorkspaceForTest()
    var primaryFailure: Throwable? = null
    lateinit var editor: NativeEditor
    lateinit var canvas: CanvasView
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitFor(message: String, action: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            if (action()) return
            Thread.sleep(30); instrumentation.waitForIdleSync()
        }
        error(message)
    }
    fun find(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    fun drain() {
        val gl = CountDownLatch(1)
        canvas.queueEvent { gl.countDown() }
        check(gl.await(12, TimeUnit.SECONDS))
        editor.commandDispatcher.awaitIdleForTest().get(12, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }
    fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        main {
            check(activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))) {
                "Activity did not accept key $code/$meta"
            }
            activity.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0, meta))
        }
        instrumentation.waitForIdleSync()
    }
    fun keysInOneUiTurn(vararg codes: Int) {
        main {
            codes.forEach { code ->
                val now = SystemClock.uptimeMillis()
                check(activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)))
                activity.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0))
            }
        }
        instrumentation.waitForIdleSync()
    }
    fun startDistort() {
        key(KeyEvent.KEYCODE_D, KeyEvent.META_SHIFT_ON)
        waitFor("Distort Start control not available") {
            editorUiAutomation(instrumentation).clearCache()
            var node = findEditorUiNode(editorPhysicalDisplayRoot(instrumentation), context.getString(R.string.ui_start_distort))
            while (node != null && !node.isClickable) node = node.parent
            node?.let { it.isVisibleToUser && it.isEnabled &&
                it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
        waitFor("Distort session not ready") { canvas.distortSessionActive && canvas.distortSessionReady }
    }
    fun tool(code: Int, mode: CanvasToolMode) {
        key(code)
        waitFor("Tool not bound: $mode") { canvas.toolMode == mode }
    }
    fun gesture(points: List<Pair<Double, Double>>, cancel: Boolean = false, meta: Int = 0, settle: Boolean = true) {
        val positions = mutableListOf<Pair<Float, Float>>()
        main {
            val location = IntArray(2).also(canvas::getLocationOnScreen)
            points.forEach { (x, y) -> positions +=
                location[0] + canvas.offsetXForOverlay + (x * canvas.scaleForOverlay).toFloat() to
                location[1] + canvas.offsetYForOverlay + (y * canvas.scaleForOverlay).toFloat() }
        }
        val down = SystemClock.uptimeMillis()
        positions.forEachIndexed { index, (x, y) ->
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                positions.lastIndex -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, meta)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            Thread.sleep(30)
        }
        if (settle) drain()
    }
    fun pixels(): ByteArray {
        val out = ByteBuffer.allocateDirect(128 * 128 * 4)
        check(editor.draw(128, 128, 0.0, 0.0, 128.0, 128.0, out))
        return ByteArray(out.capacity()).also { out.rewind(); out.get(it) }
    }
    fun capture(name: String) {
        main { canvas.requestRender() }
        Thread.sleep(250)
        val image = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = -1
        PixelCopy.request(canvas, image, { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
        check(done.await(10, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "Canvas PixelCopy failed: $status" }
        try { File(evidence, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
    fun history(name: String, before: ByteArray) {
        val changed = pixels()
        check(!changed.contentEquals(before)) { "$name produced no pixel change" }
        capture(name)
        key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON); drain()
        check(pixels().contentEquals(before)) { "$name Undo not exact" }
        key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON); drain()
        check(pixels().contentEquals(changed)) { "$name Redo not exact" }
        key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON); drain()
        check(pixels().contentEquals(before))
        File(evidence, "steps.txt").appendText("PASS $name: touch -> changed pixels -> keyboard Undo/Redo/Undo exact; GPU surface captured\n")
    }
    try {
        check(evidence.mkdir())
        waitFor("Editor unavailable") { activity.retainedEditorForTest() != null }
        val retained = workspace
        check(retained.recoveryDirectoryForTest().canonicalFile == root) { "Refusing real user workspace" }
        check(retained.installCandidate("p103-tools", DirtyDisposition.DISCARD) {
            NativeEditor(128, addDefaultLayers = false).also { next ->
                val data = ByteBuffer.allocateDirect(128 * 128 * 4)
                repeat(128) { y -> repeat(128) { x ->
                    data.put(255.toByte()); data.put(if (x < 32 && y < 32) 0 else 255.toByte())
                    data.put(if (x < 32 && y < 32) 0 else 255.toByte()); data.put(255.toByte())
                } }
                data.rewind(); next.importPixels(128, 128, data)
            }
        }.result.get(15, TimeUnit.SECONDS).installed)
        editor = checkNotNull(retained.snapshot().editor)
        val bound = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
        waitFor("Canvas fixture not bound") {
            var ready = false
            main {
                val candidate = find(activity.window.decorView)
                if (candidate != null && candidate.isAttachedToWindow && candidate.holder.surface.isValid &&
                    candidate.width > 0 && candidate.height > 0 && bound.get(candidate) === editor &&
                    candidate.layerId == editor.state().document.activeLayerId && candidate.keyboardFocusActive) {
                    check(candidate.display.displayId == 0); canvas = candidate; ready = true
                }
            }
            ready
        }
        main { canvas.fit() }; drain()
        val original = pixels()
        tool(KeyEvent.KEYCODE_M, CanvasToolMode.MARQUEE)
        val initialMarquee = canvas.marqueePreviewShape
        main {
            val now = SystemClock.uptimeMillis()
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON))
            activity.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON))
        }
        instrumentation.waitForIdleSync()
        check(canvas.marqueePreviewShape == initialMarquee) { "Shift-Tab changed marquee mode" }
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not change the marquee shape") { canvas.marqueePreviewShape != initialMarquee }
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not restore the marquee shape") { canvas.marqueePreviewShape == initialMarquee }
        tool(KeyEvent.KEYCODE_G, CanvasToolMode.GRADIENT)
        val initialGradient = canvas.gradientShape
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not change the gradient shape") { canvas.gradientShape != initialGradient }
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not restore the gradient shape") { canvas.gradientShape == initialGradient }
        tool(KeyEvent.KEYCODE_S, CanvasToolMode.CLONE)
        val initialCloneAll = editor.cloneStamp.snapshot().sampleAllLayers
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not change clone sampling") { editor.cloneStamp.snapshot().sampleAllLayers != initialCloneAll }
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not restore clone sampling") { editor.cloneStamp.snapshot().sampleAllLayers == initialCloneAll }
        tool(KeyEvent.KEYCODE_R, CanvasToolMode.SMEAR)
        val initialSmear = canvas.smearMode
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not change the smear mode") { canvas.smearMode != initialSmear }
        key(KeyEvent.KEYCODE_TAB)
        waitFor("Tab did not restore the smear mode") { canvas.smearMode == initialSmear }
        tool(KeyEvent.KEYCODE_J, CanvasToolMode.HEALING)
        val initialHealing = canvas.healingMode
        repeat(HealingMode.entries.size) { index ->
            val expected = HealingMode.entries[(initialHealing.ordinal + index + 1) % HealingMode.entries.size]
            key(KeyEvent.KEYCODE_TAB)
            waitFor("Tab did not select healing mode $expected") { canvas.healingMode == expected }
        }
        waitFor("Tab did not cycle the healing modes") { canvas.healingMode == initialHealing }
        check(pixels().contentEquals(original)) { "Mode shortcuts changed document pixels" }
        File(evidence, "steps.txt").appendText("PASS Tab: marquee, gradient, clone sampling, smear and healing modes; document unchanged\n")
        tool(KeyEvent.KEYCODE_B, CanvasToolMode.BRUSH)
        gesture(listOf(40.0 to 55.0, 55.0 to 60.0, 75.0 to 70.0, 90.0 to 75.0))
        history("brush", original)
        tool(KeyEvent.KEYCODE_E, CanvasToolMode.BRUSH)
        waitFor("E did not select eraser") { canvas.erasing }
        gesture(listOf(40.0 to 55.0, 55.0 to 60.0, 75.0 to 70.0, 90.0 to 75.0))
        history("eraser", original)
        tool(KeyEvent.KEYCODE_U, CanvasToolMode.SHAPE)
        gesture(listOf(40.0 to 40.0, 85.0 to 85.0, 85.0 to 85.0))
        history("rectangle", original)
        tool(KeyEvent.KEYCODE_G, CanvasToolMode.GRADIENT)
        val beforeCancel = editor.currentRevision()
        gesture(listOf(20.0 to 50.0, 100.0 to 50.0, 100.0 to 50.0))
        check(canvas.gradientPending)
        key(KeyEvent.KEYCODE_ESCAPE); drain()
        check(!canvas.gradientPending && pixels().contentEquals(original) && editor.currentRevision() == beforeCancel)
        gesture(listOf(20.0 to 50.0, 100.0 to 50.0, 100.0 to 50.0))
        key(KeyEvent.KEYCODE_ENTER); drain()
        check(!canvas.gradientPending) { "Enter did not apply Gradient" }
        history("gradient-enter", original)
        tool(KeyEvent.KEYCODE_V, CanvasToolMode.MOVE)
        gesture(listOf(60.0 to 60.0, 70.0 to 65.0, 70.0 to 65.0))
        history("move", original)
        tool(KeyEvent.KEYCODE_S, CanvasToolMode.CLONE)
        gesture(listOf(16.0 to 16.0, 16.0 to 16.0), meta = KeyEvent.META_ALT_ON)
        gesture(listOf(65.0 to 65.0, 70.0 to 70.0, 75.0 to 75.0))
        history("clone-stamp", original)
        tool(KeyEvent.KEYCODE_R, CanvasToolMode.SMEAR)
        gesture(listOf(24.0 to 24.0, 35.0 to 35.0, 48.0 to 48.0, 65.0 to 65.0))
        history("smudge", original)
        tool(KeyEvent.KEYCODE_J, CanvasToolMode.HEALING)
        gesture(listOf(20.0 to 20.0, 25.0 to 25.0, 30.0 to 30.0))
        history("healing", original)
        startDistort()
        // The fixture layer occupies [0,128] on both document axes.
        gesture(listOf(0.0 to 0.0, 12.0 to 8.0, 12.0 to 8.0), settle = false)
        waitFor("Distort preview did not settle") { canvas.distortSessionReady }
        keysInOneUiTurn(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ENTER)
        waitFor("Distort did not commit") { !canvas.distortSessionActive }
        drain()
        history("distort", original)
        val revisionBeforeCancel = editor.currentRevision()
        startDistort()
        gesture(listOf(0.0 to 0.0, 10.0 to 6.0, 10.0 to 6.0), settle = false)
        waitFor("Second Distort preview did not settle") { canvas.distortSessionReady }
        keysInOneUiTurn(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE)
        waitFor("Finalizing Distort did not cancel") { !canvas.distortSessionActive }
        Thread.sleep(150); drain()
        check(editor.currentRevision() == revisionBeforeCancel && pixels().contentEquals(original)) {
            "Cancelled final-render callback committed pixels or history"
        }
        File(evidence, "steps.txt").appendText("PASS Distort repeated Enter commits once; Enter then Escape in one UI turn cancels final render with no history/pixel change.\n")
        tool(KeyEvent.KEYCODE_I, CanvasToolMode.EYEDROPPER)
        gesture(listOf(16.0 to 16.0, 16.0 to 16.0))
        waitFor("Eyedropper did not publish red foreground") { canvas.foregroundColor == 0xffff0000.toInt() && !canvas.sampleSettlementPending }
        val navRevision = editor.currentRevision()
        tool(KeyEvent.KEYCODE_H, CanvasToolMode.HAND)
        val oldX = canvas.offsetXForOverlay
        gesture(listOf(60.0 to 60.0, 70.0 to 65.0, 70.0 to 65.0))
        check(canvas.offsetXForOverlay != oldX) { "Hand did not pan" }
        tool(KeyEvent.KEYCODE_Z, CanvasToolMode.ZOOM)
        val oldScale = canvas.scaleForOverlay
        gesture(listOf(60.0 to 60.0, 60.0 to 60.0))
        check(canvas.scaleForOverlay > oldScale) { "Zoom tap did not zoom" }
        check(editor.currentRevision() == navRevision && pixels().contentEquals(original)) { "Navigation or sampling modified document" }
        capture("navigation")
        key(KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON); drain()
        tool(KeyEvent.KEYCODE_B, CanvasToolMode.BRUSH)
        gesture(listOf(60.0 to 75.0, 80.0 to 90.0, 90.0 to 95.0))
        val savedPixels = pixels()
        check(!savedPixels.contentEquals(original))
        retained.flushAsync().get(15, TimeUnit.SECONDS)
        capture("recovery-before")
        val oldEditor = editor
        main { activity.finish() }
        workspace.closeAsync().get(15, TimeUnit.SECONDS)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
        workspace = activity.retainedWorkspaceForTest()
        check(workspace.recoveryDirectoryForTest().canonicalFile == root)
        waitFor("Recovery editor did not reload") { activity.retainedEditorForTest() != null }
        editor = checkNotNull(activity.retainedEditorForTest())
        check(editor !== oldEditor && editor.canvasWidth == 128 && editor.canvasHeight == 128)
        check(pixels().contentEquals(savedPixels)) { "Activity close/relaunch changed recovery pixels" }
        waitFor("Recovered canvas did not bind") {
            var ready = false
            main {
                val candidate = find(activity.window.decorView)
                if (candidate != null && candidate.isAttachedToWindow && candidate.holder.surface.isValid &&
                    candidate.width > 0 && candidate.height > 0 && bound.get(candidate) === editor) {
                    check(candidate.display.displayId == 0); canvas = candidate; ready = true
                }
            }
            ready
        }
        main { canvas.fit() }; drain(); capture("recovery-after")
        File(evidence, "steps.txt").appendText("PASS eyedropper red, Hand pan, Zoom tap; navigation document unchanged; final brush autosave + Activity close/relaunch restores exact document pixels. No picker, IME, or process-kill assertion.\n")
        return "12 tool paths on physical display0; Brush/Eraser/Rectangle/Gradient/Move/Clone/Smudge/Healing/Distort exact Undo+Redo, Gradient Escape+Enter, Eyedropper/Hand/Zoom; autosave+Activity relaunch exact; $evidence"
    } catch (failure: Throwable) {
        primaryFailure = failure
        runCatching { File(evidence, "failure.txt").writeText(failure.stackTraceToString()) }
        runCatching { capture("failure-canvas") }
        throw failure
    } finally {
        val cleanupFailures = listOf(
            runCatching { main { if (canvas.distortSessionActive) canvas.cancelDistortSession() } }.exceptionOrNull(),
            runCatching { main { activity.finish() } }.exceptionOrNull(),
            runCatching { workspace.closeAsync().get(15, TimeUnit.SECONDS) }.exceptionOrNull()
        ).filterNotNull()
        if (cleanupFailures.isNotEmpty()) {
            val originalFailure = primaryFailure
            if (originalFailure != null) cleanupFailures.forEach(originalFailure::addSuppressed)
            else { cleanupFailures.drop(1).forEach(cleanupFailures.first()::addSuppressed); throw cleanupFailures.first() }
        }
    }
}
