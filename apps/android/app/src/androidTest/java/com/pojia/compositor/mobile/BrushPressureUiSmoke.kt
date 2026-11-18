package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** OS-injected synthetic stylus input exercises actual pressure controls and native pixels. */
internal fun brushPressureUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    val evidence = File(context.filesDir, "p76-pressure-ui-${System.nanoTime()}").apply { check(mkdir()) }
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
    val pressureLabels = setOf("Pressure for size", "Pressure for opacity")
    val headerLabels = pressureLabels + setOf("Size value", "Hardness value", "Opacity value", "Smoothing value", "Brush foreground color")
    fun revealPressureControl(label: String) {
        fun headerScroll(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isScrollable && headerLabels.any { node(root, it)?.isVisibleToUser == true }) return root
            for (index in 0 until root.childCount) headerScroll(root.getChild(index))?.let { return it }
            return null
        }
        fun fingerprint(root: AccessibilityNodeInfo) = headerLabels.mapNotNull { name ->
            node(root, name)?.takeIf { it.isVisibleToUser }?.let { field ->
                val bounds = Rect().also(field::getBoundsInScreen)
                "$name:${bounds.toShortString()}"
            }
        }.joinToString(";")
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var pending: String? = null
        var lastAction = 0L
        waitUntil("Pressure control did not become visible through header scrolling: $label") {
            automation.clearCache()
            val root = automation.rootInActiveWindow
            val control = node(root, label)
            if (control?.isVisibleToUser == true) true else {
                val scroll = headerScroll(root)
                if (scroll != null) {
                    val now = SystemClock.uptimeMillis()
                    val current = fingerprint(scroll)
                    // Compose omits fully clipped semantics nodes. Navigate the real
                    // horizontal row; SHOW_ON_SCREEN cannot reveal a missing node.
                    if (scroll.actionList.none { it.id == direction }) {
                        direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        pending = null
                    }
                    if (now - lastAction >= 200 && (pending == null || pending != current)) {
                        check(scroll.actionList.any { it.id == direction } && scroll.performAction(direction)) {
                            "Pressure header rejected its advertised horizontal scroll action"
                        }
                        pending = current
                        lastAction = now
                    }
                }
                false
            }
        }
    }
    fun click(label: String) {
        if (label in pressureLabels) revealPressureControl(label)
        var clicked = false
        waitUntil("Pressure control unavailable: $label") {
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
    fun penEvent(action: Int, x: Double, y: Double, pressure: Float, down: Long, time: Long) {
        val location = IntArray(2)
        var sx = 0f; var sy = 0f
        main {
            canvas.getLocationOnScreen(location)
            sx = location[0] + canvas.offsetXForOverlay + (x * canvas.scaleForOverlay).toFloat()
            sy = location[1] + canvas.offsetYForOverlay + (y * canvas.scaleForOverlay).toFloat()
        }
        val event = MotionEvent.obtain(down, time, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = sx; this.y = sy; this.pressure = pressure; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { check(automation.injectInputEvent(event, true)) { "Native stylus injection failed" } }
        finally { event.recycle() }
    }
    fun penTap(x: Double, y: Double, pressure: Float) {
        val down = SystemClock.uptimeMillis()
        penEvent(MotionEvent.ACTION_DOWN, x, y, pressure, down, down)
        penEvent(MotionEvent.ACTION_UP, x, y, pressure, down, down + 20)
        drain()
    }
    fun pixels(): ByteArray {
        val output = ByteBuffer.allocateDirect(80 * 80 * 4)
        check(editor.draw(80, 80, 0.0, 0.0, 80.0, 80.0, output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun alpha(bytes: ByteArray, x: Int, y: Int) = bytes[(y * 80 + x) * 4 + 3].toInt() and 255
    fun pressureControls(size: Boolean, opacity: Boolean) {
        // A phone cannot show both header controls at once. Verify each real
        // visible toggle separately, then check their combined Canvas settings.
        for ((label, expected) in listOf("Pressure for size" to size, "Pressure for opacity" to opacity)) {
            revealPressureControl(label)
            waitUntil("Pressure control did not expose its checked state: $label=$expected") {
                automation.clearCache()
                val control = node(automation.rootInActiveWindow, label)
                control?.isVisibleToUser == true && control.isCheckable && control.isChecked == expected
            }
        }
        waitUntil("Pressure controls did not bind the combined Canvas settings") {
            canvas.strokeSettings.pressureSize == size && canvas.strokeSettings.pressureOpacity == opacity
        }
    }
    fun setPressure(size: Boolean, opacity: Boolean) {
        if (canvas.strokeSettings.pressureSize != size) click("Pressure for size")
        if (canvas.strokeSettings.pressureOpacity != opacity) click("Pressure for opacity")
        pressureControls(size, opacity)
    }
    fun bindCanvas(previous: CanvasView? = null) {
        var next: CanvasView? = null
        waitUntil("Pressure canvas did not bind") {
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
    try {
        waitUntil("Pressure Activity did not open") { activity.retainedEditorForTest() != null }
        val retained = activity.retainedWorkspaceForTest()
        workspace = retained
        check(retained.installCandidate("pressure-ui", DirtyDisposition.DISCARD) {
            NativeEditor(80, addDefaultLayers = false).also { next ->
                next.importPixels(80, 80, ByteBuffer.allocateDirect(80 * 80 * 4))
            }
        }.result.get(12, TimeUnit.SECONDS).installed)
        editor = checkNotNull(retained.snapshot().editor)
        bindCanvas()
        accessibility = bindAccessibility()
        click("Brush (B)")
        waitUntil("Brush did not select its default tip") { canvas.toolMode == CanvasToolMode.BRUSH &&
            canvas.strokeSettings.diameter == 40f && canvas.strokeSettings.hardness == 1f && canvas.strokeSettings.opacity == 1f }
        check(!canvas.strokeSettings.pressureSize && !canvas.strokeSettings.pressureOpacity)
        val seenBefore = PenInput.seen
        if (!seenBefore) {
            automation.clearCache()
            check(node(automation.rootInActiveWindow, "Pressure for size") == null &&
                node(automation.rootInActiveWindow, "Pressure for opacity") == null) { "Pressure controls appeared before pen discovery" }
        }
        val original = pixels()
        val initialRevision = editor.currentRevision()
        val hoverTime = SystemClock.uptimeMillis()
        penEvent(MotionEvent.ACTION_HOVER_ENTER, 40.5, 40.5, .25f, hoverTime, hoverTime)
        penEvent(MotionEvent.ACTION_HOVER_MOVE, 40.5, 40.5, .25f, hoverTime, hoverTime + 10)
        penEvent(MotionEvent.ACTION_HOVER_EXIT, 40.5, 40.5, .25f, hoverTime, hoverTime + 20)
        drain()
        waitUntil("Synthetic stylus hover did not discover the pen") { PenInput.seen }
        pressureControls(false, false)
        check(editor.currentRevision() == initialRevision && pixels().contentEquals(original)) { "Pen hover painted pixels" }
        val observations = JSONArray()
        for ((size, opacity) in listOf(false to false, true to false, false to true, true to true)) {
            setPressure(size, opacity)
            val revision = editor.currentRevision()
            penTap(40.5, 40.5, .25f)
            val painted = pixels()
            val expectedAlpha = if (opacity) 64 else 255
            check(alpha(painted, 40, 40) == expectedAlpha) {
                "Stylus opacity mismatch for size=$size opacity=$opacity: ${alpha(painted, 40, 40)}"
            }
            check(alpha(painted, 48, 40) == if (size) 0 else expectedAlpha) {
                "Stylus size toggle did not control the native footprint"
            }
            check(editor.currentRevision() == revision + 1)
            observations.put(JSONObject().put("pressureSize", size).put("pressureOpacity", opacity)
                .put("samplePressure", .25).put("centerAlpha", alpha(painted, 40, 40))
                .put("outsideSmallTipAlpha", alpha(painted, 48, 40)))
            click("Undo"); drain(); check(pixels().contentEquals(original))
            click("Redo"); drain(); check(pixels().contentEquals(painted))
            click("Undo"); drain(); check(pixels().contentEquals(original))
        }
        val zeroRevision = editor.currentRevision()
        penTap(40.5, 40.5, 0f)
        check(editor.currentRevision() == zeroRevision && pixels().contentEquals(original)) { "Zero stylus pressure created an invisible edit" }
        screenshot("brush-pressure-options.png")

        val previous = canvas
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            accessibility?.close(); accessibility = null
            main { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        bindCanvas(previous)
        accessibility = bindAccessibility()
        waitUntil("Brush did not survive Activity recreation") { canvas.toolMode == CanvasToolMode.BRUSH && PenInput.seen }
        pressureControls(true, true)
        check(pixels().contentEquals(original) && editor.currentRevision() == zeroRevision)
        penTap(20.5, 40.5, 1f)
        val cloneOriginal = pixels()
        check(alpha(cloneOriginal, 20, 40) == 255 && alpha(cloneOriginal, 60, 40) == 0)
        click("Clone Stamp (S)")
        waitUntil("Clone did not isolate its pressure settings") { canvas.toolMode == CanvasToolMode.CLONE &&
            !canvas.strokeSettings.pressureSize && !canvas.strokeSettings.pressureOpacity }
        automation.clearCache()
        check(node(automation.rootInActiveWindow, "Pressure for size") == null &&
            node(automation.rootInActiveWindow, "Pressure for opacity") == null) { "Brush pressure controls leaked into Clone" }
        val beforeSource = editor.currentRevision()
        click("Clone Set Source")
        penTap(20.5, 40.5, .25f)
        check(editor.currentRevision() == beforeSource && pixels().contentEquals(cloneOriginal))
        var cloneReference: ByteArray? = null
        for (pressure in listOf(0f, .25f, 1f)) {
            penTap(60.5, 40.5, pressure)
            val cloned = pixels()
            check(alpha(cloned, 60, 40) == 255) { "Clone inherited Brush pressure opacity" }
            val reference = cloneReference
            if (reference == null) cloneReference = cloned else check(cloned.contentEquals(reference)) {
                "Clone synthetic stylus pressure changed output"
            }
            click("Undo"); drain(); check(pixels().contentEquals(cloneOriginal))
        }
        click("Brush (B)")
        waitUntil("Brush did not restore") { canvas.toolMode == CanvasToolMode.BRUSH }
        pressureControls(true, true)
        screenshot("pressure-after-recreation-and-clone.png")
        File(evidence, "result.json").writeText(JSONObject().put("passed", true).put("penSeenBeforeFixture", seenBefore)
            .put("input", "OS-injected synthetic native stylus hover and touch; no physical stylus claim")
            .put("cases", observations).put("checks", "real pressure toggle checked state, exact native center/footprint, zero-strength no edit, Undo/Redo, Activity recreation, Clone pressure0/0.25/1 exact equality, Brush settings retained")
            .toString(2))
        return "Pressure UI passed OS-injected synthetic stylus discovery, four real toggle combinations and exact native pixels/history; zero pressure no-op, Activity recreation and Clone isolation passed. PNG: ${File(evidence, "pressure-after-recreation-and-clone.png").absolutePath}. No physical stylus claim."
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
