package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual line controls, pointer preview/commit, exact pixels and retained Activity options. */
internal fun lineShapeUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    val evidence = File(context.filesDir, "p77-line-ui-${System.nanoTime()}").apply { check(mkdir()) }
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
    val headerLabels = setOf("Line", "Rectangle", "Ellipse", "Width value", "Width slider", "Width scrub", "Line style", "Line cap")
    fun revealHeaderControl(label: String) {
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
        waitUntil("Line control did not become visible through header scrolling: $label") {
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
                            "Line header rejected its advertised horizontal scroll action"
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
        if (label in headerLabels) revealHeaderControl(label)
        var clicked = false
        waitUntil("Line control unavailable: $label") {
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
    fun stateDiagnostic(): String {
        val state = editor.state()
        var view = ""
        main { view = "windowFocus=${activity.hasWindowFocus()}/${canvas.hasWindowFocus()}, keyboardFocus=${canvas.keyboardFocusActive}, " +
            "tool=${canvas.toolMode}, width=${canvas.shapeLineWidth}, style=${canvas.shapeLineStyle}, cap=${canvas.shapeLineCap}, draft=${canvas.shapeDraft}" }
        return "revision=${editor.currentRevision()}, layers=${state.layers.size}, active=${state.document.activeLayerId}, " +
            "metadata=${editor.lineShape(state.document.activeLayerId)}, $view"
    }
    fun trace(message: String) {
        File(evidence, "pointer-events.txt").appendText("${SystemClock.uptimeMillis()} $message\n")
    }
    fun pointer(action: Int, x: Double, y: Double, down: Long, time: Long) {
        if (action == MotionEvent.ACTION_DOWN) {
            waitUntil("Canvas window focus did not return before pointer input") {
                var focused = false
                main { focused = activity.hasWindowFocus() && canvas.hasWindowFocus() }
                focused
            }
        }
        val location = IntArray(2)
        val visible = Rect()
        var sx = 0f; var sy = 0f
        var hasVisibleBounds = false
        main {
            canvas.getLocationOnScreen(location)
            hasVisibleBounds = canvas.getGlobalVisibleRect(visible)
            sx = location[0] + canvas.offsetXForOverlay + (x * canvas.scaleForOverlay).toFloat()
            sy = location[1] + canvas.offsetYForOverlay + (y * canvas.scaleForOverlay).toFloat()
        }
        val detail = "action=$action document=($x,$y) screen=($sx,$sy) visible=${visible.toShortString()} ${stateDiagnostic()}"
        trace(detail)
        check(hasVisibleBounds && sx >= visible.left && sx < visible.right && sy >= visible.top && sy < visible.bottom) {
            "Line pointer is outside the visible Canvas: $detail"
        }
        val eventTime = maxOf(time, SystemClock.uptimeMillis())
        val event = MotionEvent.obtain(down, eventTime, action, sx, sy, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        if (action == MotionEvent.ACTION_MOVE) {
            instrumentation.waitForIdleSync()
            trace("post-MOVE eventTime=$eventTime ${stateDiagnostic()}")
        }
        if (action == MotionEvent.ACTION_DOWN) {
            waitUntil("Line pointer DOWN did not create a draft; $detail") {
                var accepted = false
                main { accepted = canvas.shapeDraft?.let { it.anchorX == shapePixel(x) && it.anchorY == shapePixel(y) } == true }
                accepted
            }
        }
    }
    fun awaitCommit(revision: Long, layerCount: Int, activeId: Long) {
        val expected = "revision=${revision + 1}, layers=${layerCount + 1}, active!=$activeId"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (editor.currentRevision() == revision && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            Thread.sleep(20)
        }
        drain()
        val state = editor.state()
        val actual = stateDiagnostic()
        trace("commit expected $expected; actual $actual")
        check(editor.currentRevision() == revision + 1 && state.layers.size == layerCount + 1 && state.document.activeLayerId != activeId) {
            "Line pointer UP did not commit exactly one shape; expected $expected; actual $actual"
        }
    }
    fun drag(ax: Double, ay: Double, bx: Double, by: Double,
        releaseX: Double = bx, releaseY: Double = by, preview: (() -> Unit)? = null) {
        val before = editor.state()
        val revision = editor.currentRevision()
        val time = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, ax, ay, time, time)
        pointer(MotionEvent.ACTION_MOVE, bx, by, time, time + 20)
        waitUntil("Line pointer MOVE did not update its draft endpoints; ${stateDiagnostic()}") {
            var moved = false
            main { moved = canvas.shapeDraft?.let { it.endX == shapePixel(bx) && it.endY == shapePixel(by) } == true }
            moved
        }
        preview?.invoke()
        pointer(MotionEvent.ACTION_UP, releaseX, releaseY, time, maxOf(time + 40, SystemClock.uptimeMillis()))
        awaitCommit(revision, before.layers.size, before.document.activeLayerId)
    }
    fun setWidth(value: String) {
        revealHeaderControl("Width value")
        click("Width value")
        var editable: AccessibilityNodeInfo? = null
        waitUntil("Line width field did not expose its focused editable action") {
            automation.clearCache()
            var field = node(automation.rootInActiveWindow, "Width value")
            // Compose's description can live below the editable text-field semantics.
            while (field != null && field.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) field = field.parent
            editable = field?.takeIf { it.isEditable && it.isFocused && it.isEnabled && it.isVisibleToUser }
            editable != null
        }
        check(checkNotNull(editable).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Line width text field rejected input" }
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        waitUntil("Typed line width did not commit: $value") { canvas.shapeLineWidth == value.toDouble() }
    }
    fun pixels(): ByteArray {
        val output = ByteBuffer.allocateDirect(80 * 80 * 4)
        check(editor.draw(80, 80, 0.0, 0.0, 80.0, 80.0, output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun alpha(bytes: ByteArray, x: Int, y: Int) = bytes[(y * 80 + x) * 4 + 3].toInt() and 255
    fun bindCanvas(previous: CanvasView? = null) {
        var next: CanvasView? = null
        waitUntil("Line canvas did not bind") {
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
    var report: JSONObject? = null
    try {
        waitUntil("Line Activity did not open") { activity.retainedEditorForTest() != null }
        val retained = activity.retainedWorkspaceForTest()
        workspace = retained
        check(retained.installCandidate("line-ui", DirtyDisposition.DISCARD) {
            NativeEditor(80, addDefaultLayers = false).also { next ->
                next.importPixels(80, 80, ByteBuffer.allocateDirect(80 * 80 * 4))
            }
        }.result.get(12, TimeUnit.SECONDS).installed)
        editor = checkNotNull(retained.snapshot().editor)
        bindCanvas()
        accessibility = bindAccessibility()
        click("Shape (U)"); click("Line")
        waitUntil("Line did not select its default options") { canvas.toolMode == CanvasToolMode.SHAPE &&
            canvas.shapeKind == ShapeKind.LINE && canvas.shapeLineWidth == 4.0 &&
            canvas.shapeLineStyle == ShapeLineStyle.SOLID && canvas.shapeLineCap == ShapeLineCap.SQUARE }
        revealHeaderControl("Width slider")
        automation.clearCache()
        val slider = checkNotNull(node(automation.rootInActiveWindow, "Width slider")?.rangeInfo)
        check(slider.min == 1f && slider.max == 50f) { "Line slider must cover 1 through 50" }
        setWidth("80")
        revealHeaderControl("Width slider")
        automation.clearCache()
        check(node(automation.rootInActiveWindow, "Width slider")?.rangeInfo?.current == 50f)
        check(canvas.shapeLineWidth == 80.0) { "Slider clipped the typed width to its everyday range" }
        setWidth("1")
        click("Line style")
        screenshot("line-style-menu.png")
        click("Dotted")
        click("Line cap"); click("Round")
        waitUntil("Real line menus did not bind") { canvas.shapeLineStyle == ShapeLineStyle.DOTTED && canvas.shapeLineCap == ShapeLineCap.ROUND }
        val oldCanvas = canvas
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            accessibility?.close(); accessibility = null
            main { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        bindCanvas(oldCanvas)
        accessibility = bindAccessibility()
        waitUntil("Line options did not survive Activity recreation") { canvas.toolMode == CanvasToolMode.SHAPE &&
            canvas.shapeKind == ShapeKind.LINE && canvas.shapeLineWidth == 1.0 &&
            canvas.shapeLineStyle == ShapeLineStyle.DOTTED && canvas.shapeLineCap == ShapeLineCap.ROUND }
        click("Line cap"); click("Square")
        waitUntil("Square cap did not bind before the crisp one-pixel check") { canvas.shapeLineCap == ShapeLineCap.SQUARE }
        val original = pixels(); val before = editor.currentRevision()
        drag(10.0, 20.0, 30.0, 20.0) {
            waitUntil("Line preview did not appear") {
                automation.clearCache()
                node(automation.rootInActiveWindow, "Shape preview")?.isVisibleToUser == true
            }
            check(editor.currentRevision() == before && pixels().contentEquals(original)) { "Preview committed before pointer up" }
            val draft = checkNotNull(canvas.shapeDraft)
            check(draft.lineStyle == ShapeLineStyle.DOTTED && draft.lineCap == ShapeLineCap.SQUARE)
            screenshot("dotted-line-preview.png")
        }
        check(editor.currentRevision() == before + 1 && editor.state().layers.size == 2)
        val firstId = editor.state().document.activeLayerId
        val first = checkNotNull(editor.lineShape(firstId))
        check(first.lineWidth == 1.0 && first.style == ShapeLineStyle.DOTTED.wireValue && first.cap == ShapeLineCap.SQUARE.wireValue)
        check(first.startX < first.endX && first.startY == first.endY)
        val dotted = pixels()
        for (x in 10..30) {
            val expected = if ((x - 10) % 2 == 0) 255 else 0
            check(alpha(dotted, x, 20) == expected) { "Dotted line pixel $x,20 alpha ${alpha(dotted, x, 20)} != $expected" }
            check(alpha(dotted, x, 19) == 0 && alpha(dotted, x, 21) == 0) { "Odd-width line straddled two rows" }
        }
        click("Undo"); drain(); check(editor.state().layers.size == 1 && pixels().contentEquals(original))
        click("Redo"); drain(); check(pixels().contentEquals(dotted) && editor.lineShape(firstId) == first)
        setWidth("5000")
        val beforeOversized = editor.currentRevision()
        val beforeOversizedLayers = editor.state().layers.size
        val oversizedTime = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, 55.0, 60.0, oversizedTime, oversizedTime)
        pointer(MotionEvent.ACTION_UP, 55.0, 60.0, oversizedTime, oversizedTime + 20)
        drain()
        val oversizedMessage = "That shape is too large. A shape can cover up to 16 megapixels."
        waitUntil("Oversized line did not explain its refusal") {
            automation.clearCache()
            node(automation.rootInActiveWindow, oversizedMessage)?.isVisibleToUser == true
        }
        check(editor.currentRevision() == beforeOversized && editor.state().layers.size == beforeOversizedLayers &&
            pixels().contentEquals(dotted)) { "Oversized line changed the document" }
        screenshot("oversized-line-refusal.png")
        click("OK")
        waitUntil("Editor focus did not return after oversized line refusal") {
            var focused = false
            main { focused = activity.hasWindowFocus() && canvas.hasWindowFocus() && canvas.keyboardFocusActive }
            automation.clearCache()
            focused && node(automation.rootInActiveWindow, oversizedMessage) == null
        }
        setWidth("3")
        click("Line style"); click("Dashed")
        click("Line cap"); click("Round")
        waitUntil("Round dashed options did not bind") { canvas.shapeLineStyle == ShapeLineStyle.DASHED && canvas.shapeLineCap == ShapeLineCap.ROUND }
        drag(60.0, 50.0, 40.0, 30.0)
        val diagonal = checkNotNull(editor.lineShape(editor.state().document.activeLayerId))
        check(diagonal.lineWidth == 3.0 && diagonal.style == ShapeLineStyle.DASHED.wireValue && diagonal.cap == ShapeLineCap.ROUND.wireValue) {
            "Reverse line metadata expected width=3/style=Dashed/cap=Round; actual $diagonal; ${stateDiagnostic()}"
        }
        check(diagonal.startX > diagonal.endX && diagonal.startY > diagonal.endY) { "Reverse line endpoints were flipped" }
        click("Line style"); click("Solid")
        click("Line cap"); click("Square")
        waitUntil("Solid square style did not bind") { canvas.shapeLineStyle == ShapeLineStyle.SOLID && canvas.shapeLineCap == ShapeLineCap.SQUARE }
        // A real release may advance beyond the last delivered MOVE sample.
        drag(20.0, 65.0, 25.0, 65.0, releaseX = 35.0, releaseY = 65.0)
        val releasedLayer = editor.state().layers.first { it.id == editor.state().document.activeLayerId }
        val released = checkNotNull(editor.lineShape(releasedLayer.id))
        check(releasedLayer.pixelWidth == 18 && releasedLayer.pixelHeight == 3 &&
            releasedLayer.transform.x == 19.0 && releasedLayer.transform.y == 64.0 &&
            released.startX == 1.5 / 18.0 && released.endX == 16.5 / 18.0 &&
            released.startY == 0.5 && released.endY == 0.5 &&
            alpha(pixels(), 35, 65) == 255 && alpha(pixels(), 37, 65) == 0) {
            "Line release must commit the UP endpoint (35,65), not MOVE (25,65); layer=$releasedLayer metadata=$released"
        }
        val beforePoint = editor.state().layers.size
        val beforePointRevision = editor.currentRevision()
        val beforePointId = editor.state().document.activeLayerId
        val time = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, 55.0, 60.0, time, time)
        pointer(MotionEvent.ACTION_UP, 55.0, 60.0, time, SystemClock.uptimeMillis())
        awaitCommit(beforePointRevision, beforePoint, beforePointId)
        val pointLayer = editor.state().layers.first { it.id == editor.state().document.activeLayerId }
        val point = checkNotNull(editor.lineShape(pointLayer.id))
        check(editor.state().layers.size == beforePoint + 1 && pointLayer.pixelWidth == 3 && pointLayer.pixelHeight == 3)
        check(point.startX == point.endX && point.startY == point.endY && alpha(pixels(), 55, 60) == 255)
        revealHeaderControl("Line cap")
        screenshot("line-controls-and-committed-pixels.png")
        report = JSONObject().put("passed", true).put("checks", "real header scrolling, slider1..50/typed80/typed5000 refusal, style/cap menus, Activity options retention, preview before commit, exact1px dotted row, Undo/Redo metadata/pixels, reverse endpoints, final UP endpoint and click point")
        return "Line UI passed; PNG: ${File(evidence, "line-controls-and-committed-pixels.png").absolutePath}"
    } catch (failure: Throwable) {
        runCatching { screenshot("failure.png") }
        File(evidence, "failure.txt").writeText(failure.stackTraceToString())
        primaryFailure = failure
        throw failure
    } finally {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let { failure ->
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        cleanup { accessibility?.close() }
        cleanup { workspace?.flushAsync()?.get(12, TimeUnit.SECONDS) }
        cleanup { main { activity.finish() } }
        cleanup { workspace?.closeAsync()?.get(12, TimeUnit.SECONDS) }
        val failed = cleanupFailure
        if (failed != null) {
            File(evidence, "cleanup-failure.txt").writeText(failed.stackTraceToString())
            primaryFailure?.addSuppressed(failed) ?: throw failed
        } else if (primaryFailure == null) report?.let { File(evidence, "result.json").writeText(it.toString(2)) }
    }
}
