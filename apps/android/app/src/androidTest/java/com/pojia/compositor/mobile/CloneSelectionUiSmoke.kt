package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Actual marquee/source gestures clip clone writes while retaining selection through recreation/history. */
internal fun cloneSelectionUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    val evidence = File(context.filesDir, "p75-clone-selection-ui-${System.nanoTime()}").apply { check(mkdir()) }
    val automation = instrumentation.uiAutomation
    var accessibility: AutoCloseable? = null
    var workspace: AndroidWorkspace? = null
    var primaryFailure: Throwable? = null
    lateinit var editor: NativeEditor
    lateinit var canvas: CanvasView
    val boundEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun bindAccessibility(): AutoCloseable {
        lateinit var delegate: Any
        lateinit var getter: java.lang.reflect.Method
        lateinit var setter: java.lang.reflect.Method
        var previous = false
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        main {
            val root = checkNotNull(find(activity.window.decorView))
            delegate = checkNotNull(root.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }.get(root))
            getter = delegate.javaClass.methods.single { it.name.startsWith("getAccessibilityForceEnabledForTesting") && it.parameterCount == 0 }
            setter = delegate.javaClass.methods.single { it.name.startsWith("setAccessibilityForceEnabledForTesting") && it.parameterCount == 1 }
            previous = getter.invoke(delegate) as Boolean
            setter.invoke(delegate, true)
        }
        return AutoCloseable { main { setter.invoke(delegate, previous) } }
    }
    fun node(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
        for (index in 0 until root.childCount) node(root.getChild(index), label)?.let { return it }
        return null
    }
    fun click(label: String) {
        var clicked = false
        waitUntil("Clone control unavailable: $label") {
            if (!clicked) {
                automation.clearCache()
                var item = node(automation.rootInActiveWindow, label)
                if (item != null && !item.isVisibleToUser) item.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                while (item != null && !item.isClickable) item = item.parent
                if (item?.isVisibleToUser == true && item.isEnabled) clicked = item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    fun drain() {
        val complete = CountDownLatch(1)
        canvas.queueEvent { complete.countDown() }
        check(complete.await(10, TimeUnit.SECONDS))
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }
    fun gesture(points: List<Pair<Double, Double>>) {
        val location = IntArray(2)
        val screen = points.map { point ->
            var sx = 0f; var sy = 0f
            main {
                canvas.getLocationOnScreen(location)
                sx = location[0] + canvas.offsetXForOverlay + (point.first * canvas.scaleForOverlay).toFloat()
                sy = location[1] + canvas.offsetYForOverlay + (point.second * canvas.scaleForOverlay).toFloat()
            }
            sx to sy
        }
        val down = SystemClock.uptimeMillis()
        screen.forEachIndexed { index, point ->
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                screen.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(down, down + index * 40L, action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        drain()
    }
    fun tap(x: Double, y: Double) = gesture(listOf(x to y, x to y))
    fun key(code: Int, modifiers: Int = 0) {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, modifiers))
        instrumentation.sendKeySync(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0, modifiers))
        instrumentation.waitForIdleSync()
    }
    fun pixels(): ByteArray {
        val buffer = ByteBuffer.allocateDirect(80 * 80 * 4)
        check(editor.draw(80, 80, 0.0, 0.0, 80.0, 80.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun color(x: Int, y: Int): List<Int> = pixels().let { bytes -> (0..3).map { bytes[(y * 80 + x) * 4 + it].toInt() and 255 } }
    fun bindCanvas(previous: CanvasView? = null) {
        var next: CanvasView? = null
        waitUntil("Clone canvas did not bind") {
            main { next = findCanvas(activity.window.decorView)?.takeIf { it !== previous && it.isAttachedToWindow && it.width > 0 &&
                it.height > 0 && activity.retainedEditorForTest() === editor && boundEditor.get(it) === editor } }
            val ready = next?.takeIf { it.layerId == editor.state().document.activeLayerId }
            if (ready != null) { canvas = ready; true } else false
        }
    }
    fun screenshot(name: String) {
        val image = checkNotNull(automation.takeScreenshot())
        try { File(evidence, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
    fun selectionOverlayVisible() {
        waitUntil("Clone selection outline is not exposed on screen") {
            automation.clearCache()
            node(automation.rootInActiveWindow, "Clone selection")?.isVisibleToUser == true
        }
    }
    try {
        waitUntil("Clone Activity did not open") { activity.retainedEditorForTest() != null }
        val retained = activity.retainedWorkspaceForTest()
        workspace = retained
        check(retained.installCandidate("clone-selection-ui", DirtyDisposition.DISCARD) {
            NativeEditor(80, addDefaultLayers = false).also { next ->
                val rgba = ByteBuffer.allocateDirect(80 * 80 * 4)
                repeat(80) { y -> repeat(80) { x ->
                    val green = x in 10..19 && y in 15..24
                    rgba.put(if (!green && x < 40) 255.toByte() else 0.toByte())
                    rgba.put(if (green) 255.toByte() else 0.toByte())
                    rgba.put(if (x >= 40) 255.toByte() else 0.toByte())
                    rgba.put(255.toByte())
                } }
                rgba.rewind(); next.importPixels(80, 80, rgba)
            }
        }.result.get(12, TimeUnit.SECONDS).installed)
        editor = checkNotNull(retained.snapshot().editor)
        bindCanvas()
        accessibility = bindAccessibility()
        val original = pixels()
        click("Marquee (M)")
        waitUntil("Marquee tool was not selected") { canvas.toolMode == CanvasToolMode.MARQUEE }
        gesture(listOf(60.0 to 10.0, 70.0 to 30.0, 70.0 to 30.0))
        val selected = editor.pixelSelection()
        check(selected.active && selected.shape == PixelSelectionShape.RECTANGLE &&
            abs(selected.x - 60) < .01 && abs(selected.y - 10) < .01 &&
            abs(selected.width - 10) < .01 && abs(selected.height - 20) < .01) { "Real marquee did not create target selection" }
        val regions = editor.pixelSelectionRegions().regions
        check(regions.size == 1 && canvas.pixelSelectionRegions == regions)
        check(pixels().contentEquals(original))
        click("Clone Stamp (S)")
        waitUntil("Clone tool was not selected") { canvas.toolMode == CanvasToolMode.CLONE }
        repeat(8) { key(KeyEvent.KEYCODE_LEFT_BRACKET) }
        repeat(4) { key(KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.META_SHIFT_ON) }
        waitUntil("Clone tip keys did not apply") { canvas.strokeSettings.diameter == 9f && canvas.strokeSettings.hardness == 1f }
        val beforeSource = editor.currentRevision()
        click("Clone Set Source")
        check(canvas.cloneSourceArmed)
        tap(15.5, 20.5)
        check(!canvas.cloneSourceArmed && pixels().contentEquals(original) && editor.currentRevision() == beforeSource)
        val source = checkNotNull(editor.cloneStamp.snapshot().source)
        check(abs(source.x - 15.5) < .01 && abs(source.y - 20.5) < .01)
        val previous = canvas
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            accessibility?.close(); accessibility = null
            main { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        bindCanvas(previous)
        accessibility = bindAccessibility()
        waitUntil("Clone selection/tip did not survive recreation") { canvas.toolMode == CanvasToolMode.CLONE &&
            canvas.strokeSettings.diameter == 9f && canvas.strokeSettings.hardness == 1f &&
            canvas.pixelSelection?.active == true && canvas.pixelSelectionRegions == regions }
        check(editor.pixelSelection() == selected && editor.cloneStamp.snapshot().source == source)
        selectionOverlayVisible()
        screenshot("selection-before-stroke.png")
        tap(60.5, 20.5)
        check(color(60, 20) == listOf(0, 255, 0, 255) && color(61, 20) == listOf(0, 255, 0, 255)) {
            "Selected destination did not copy from the source outside the selection"
        }
        check(color(59, 20) == listOf(0, 0, 255, 255) && color(58, 20) == listOf(0, 0, 255, 255)) {
            "Clone crossed the selection boundary"
        }
        val painted = pixels()
        for (y in 0 until 80) for (x in 0 until 80) if (x !in 60..69 || y !in 10..29) {
            val offset = (y * 80 + x) * 4
            check((0..3).all { painted[offset + it] == original[offset + it] }) { "Unselected pixel changed at $x,$y" }
        }
        fun retainedSelection() {
            val current = editor.pixelSelection()
            check(current.active && current.shape == selected.shape && current.x == selected.x && current.y == selected.y &&
                current.width == selected.width && current.height == selected.height)
            check(editor.pixelSelectionRegions().regions == regions)
            check(canvas.pixelSelection?.active == true && canvas.pixelSelectionRegions == regions)
            selectionOverlayVisible()
        }
        retainedSelection()
        click("Undo"); drain(); check(pixels().contentEquals(original)); retainedSelection()
        click("Redo"); drain(); check(pixels().contentEquals(painted)); retainedSelection()
        screenshot("clone-selection-boundary.png")
        File(evidence, "result.json").writeText(org.json.JSONObject()
            .put("passed", true)
            .put("checks", "real rectangle gesture, source outside selection, Activity recreation before stroke, exact green/blue boundary pixels, all unselected RGBA unchanged, overlay retained, exact Undo/Redo")
            .put("screenshot", "clone-selection-boundary.png").toString(2))
        return "Clone selection UI passed; PNG: ${File(evidence, "clone-selection-boundary.png").absolutePath}"
    } catch (failure: Throwable) {
        runCatching { screenshot("failure.png") }
        File(evidence, "failure.txt").writeText(failure.stackTraceToString())
        primaryFailure = failure
        throw failure
    } finally {
        accessibility?.close()
        val failure = runCatching {
            workspace?.flushAsync()?.get(12, TimeUnit.SECONDS)
            main { activity.finish() }
            workspace?.closeAsync()?.get(12, TimeUnit.SECONDS)
        }.exceptionOrNull()
        if (failure != null) primaryFailure?.addSuppressed(failure) ?: throw failure
    }
}
