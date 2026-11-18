package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
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

/** Shipping localized header and real pointer transactions without replacing the user's document. */
internal fun healingUiSmoke(instrumentation: Instrumentation): String {
    val automation = instrumentation.uiAutomation
    val context = instrumentation.targetContext
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val evidence = File(context.filesDir, "p85-healing-ui-${System.nanoTime()}").apply { check(mkdir()) }
    lateinit var editor: NativeEditor
    var editorReady = false
    lateinit var canvas: CanvasView
    var accessibility: AutoCloseable? = null
    var originalSelection: NativeSelection? = null
    var originalLayers: List<LayerState>? = null
    val ownedStrokes = mutableSetOf<StrokeReservation>()
    var fixtureId = 0L
    var originX = 0.0; var originY = 0.0
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun bindCanvas(previous: CanvasView? = null) {
        waitUntil("Healing canvas did not bind to retained editor") {
            var ready = false
            main {
                findCanvas(activity.window.decorView)?.takeIf { it !== previous && it.isAttachedToWindow &&
                    it.width > 0 && it.height > 0 && activity.retainedEditorForTest() === editor }?.let {
                    canvas = it; ready = it.layerId == editor.state().document.activeLayerId
                }
            }
            ready
        }
    }
    fun bindAccessibility(): AutoCloseable {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        lateinit var delegate: Any
        lateinit var setter: java.lang.reflect.Method
        var previous = false
        main {
            val root = checkNotNull(find(activity.window.decorView))
            delegate = checkNotNull(root.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }.get(root))
            val getter = delegate.javaClass.methods.single { it.name.startsWith("getAccessibilityForceEnabledForTesting") && it.parameterCount == 0 }
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
    fun headerLabels() = listOf(R.string.ui_healing_content_aware, R.string.ui_healing_create_texture, R.string.ui_healing_proximity_match, R.string.ui_size,
        R.string.ui_hardness, R.string.ui_opacity).map(activity::getString)
    fun revealHeader(label: String) {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var previous = ""
        var lastAction = 0L
        fun scroll(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isScrollable && headerLabels().any { node(root, it)?.isVisibleToUser == true }) return root
            for (index in 0 until root.childCount) scroll(root.getChild(index))?.let { return it }
            return null
        }
        waitUntil("Localized Healing header control unavailable: $label") {
            automation.clearCache()
            val root = automation.rootInActiveWindow
            if (node(root, label)?.isVisibleToUser == true) true else {
                scroll(root)?.let { row ->
                    val fingerprint = headerLabels().mapNotNull { text -> node(row, text)?.takeIf { it.isVisibleToUser }?.let {
                        "$text:${Rect().also(it::getBoundsInScreen).toShortString()}"
                    } }.joinToString(";")
                    val now = SystemClock.uptimeMillis()
                    if (row.actionList.none { it.id == direction }) {
                        direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        previous = ""
                    }
                    if (now - lastAction >= 200 && (fingerprint != previous || previous.isEmpty())) {
                        check(row.actionList.any { it.id == direction } && row.performAction(direction))
                        lastAction = now; previous = fingerprint
                    }
                }
                false
            }
        }
    }
    fun click(label: String, header: Boolean = false) {
        if (header) revealHeader(label)
        var clicked = false
        waitUntil("Localized Healing UI control unavailable: $label") {
            automation.clearCache()
            if (!clicked) {
                var item = node(automation.rootInActiveWindow, label)
                if (item != null && !item.isVisibleToUser)
                    item.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                while (item != null && !item.isClickable) item = item.parent
                if (item?.isVisibleToUser == true && item.isEnabled) clicked = item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    fun drain(includeRetained: Boolean) {
        val complete = CountDownLatch(1); canvas.queueEvent { complete.countDown() }
        check(complete.await(10, TimeUnit.SECONDS)) { "Healing GL queue failed to drain" }
        instrumentation.waitForIdleSync()
        // A live pointer owns the retained barrier. Await it only after UP/CANCEL.
        if (includeRetained) {
            editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
            waitUntil("Healing stroke did not retire its reservation") {
                !editor.hasActiveStrokes && editor.pendingEditCount == 0 && editor.strokeReservations().isEmpty()
            }
            instrumentation.waitForIdleSync()
        }
    }
    fun command(action: () -> Unit) {
        var accepted = false
        main { accepted = canvas.command(action) }
        check(accepted) { "Healing fixture command rejected" }
        drain(true)
    }
    fun setNumber(resource: Int, value: String, accepted: () -> Boolean) {
        val label = activity.getString(R.string.ui_field_value, activity.getString(resource))
        click(label, header = true)
        var editable: AccessibilityNodeInfo? = null
        waitUntil("Healing numeric field did not become editable: $label") {
            automation.clearCache()
            var field = node(automation.rootInActiveWindow, label)
            while (field != null && field.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) field = field.parent
            editable = field?.takeIf { it.isEditable && it.isFocused && it.isEnabled && it.isVisibleToUser }
            editable != null
        }
        check(checkNotNull(editable).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        instrumentation.waitForIdleSync()
        waitUntil("Healing numeric field failed to commit $label=$value", accepted)
    }
    fun pixels(): ByteArray {
        val output = ByteBuffer.allocateDirect(64 * 64 * 4)
        check(editor.draw(64, 64, originX, originY, 64.0, 64.0, output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun gesture(cancel: Boolean) {
        waitUntil("Healing target, layout and window focus are not ready") {
            var ready = false
            main { ready = activity.hasWindowFocus() && canvas.hasWindowFocus() && canvas.isShown &&
                !canvas.isLayoutRequested && canvas.toolMode == CanvasToolMode.HEALING && canvas.layerId == fixtureId }
            ready
        }
        val frame = CountDownLatch(1)
        main { canvas.postOnAnimation { frame.countDown() } }
        check(frame.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
        main { canvas.fit() }
        drain(false)
        val down = SystemClock.uptimeMillis()
        for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 16.0, MotionEvent.ACTION_MOVE to 48.0,
            (if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP) to 48.0)) {
            var sx = 0f; var sy = 0f
            main {
                val location = IntArray(2); canvas.getLocationOnScreen(location)
                sx = location[0] + canvas.offsetXForOverlay + ((originX + x) * canvas.scaleForOverlay).toFloat()
                sy = location[1] + canvas.offsetYForOverlay + ((originY + 32) * canvas.scaleForOverlay).toFloat()
                val visible = Rect()
                check(canvas.getGlobalVisibleRect(visible) && visible.contains(sx.toInt(), sy.toInt())) {
                    "Healing fixture pointer outside real canvas: $sx,$sy $visible"
                }
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, sx, sy, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) }
            finally { event.recycle(); ownedStrokes.addAll(editor.strokeReservations()) }
            drain(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            ownedStrokes.addAll(editor.strokeReservations())
        }
    }
    fun screenshot(name: String) {
        val image = checkNotNull(automation.takeScreenshot())
        try { File(evidence, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
    try {
        waitUntil("Healing Activity did not open") { activity.retainedEditorForTest() != null }
        editor = checkNotNull(activity.retainedEditorForTest())
        editorReady = true
        bindCanvas(); accessibility = bindAccessibility()
        check(!editor.hasActiveStrokes && editor.pendingEditCount == 0 && editor.strokeReservations().isEmpty()) {
            "Healing UI fixture requires an idle document; no existing edit was canceled"
        }
        check(!editor.pixelSelection().active) {
            "Healing UI fixture requires no active user pixel selection; no user selection was cleared"
        }
        originalSelection = editor.selection()
        originalLayers = editor.state().layers
        check(editor.canvasWidth >= 64 && editor.canvasHeight >= 64) { "Healing fixture needs a canvas of at least 64px" }
        originX = (editor.canvasWidth - 64) / 2.0; originY = (editor.canvasHeight - 64) / 2.0
        command {
            val input = ByteBuffer.allocateDirect(64 * 64 * 4)
            repeat(64 * 64) { p ->
                val x = p % 64
                val y = p / 64
                val damaged = x in 26..37 && y in 26..37
                input.put((if (damaged) 255 else x * 3).toByte())
                input.put((if (damaged) 255 else y * 3).toByte())
                input.put((if (damaged) 255 else (x + y) * 2).toByte()); input.put(255.toByte())
            }
            input.rewind(); fixtureId = editor.importPixels(64, 64, input)
            editor.renameLayer(fixtureId, "Healing UI fixture ${System.nanoTime()}")
            editor.setTransform(fixtureId, Transform64(originX, originY, 64.0, 64.0))
            editor.selectLayer(fixtureId)
        }
        waitUntil("Healing fixture selection did not publish") { canvas.layerId == fixtureId }
        click(activity.getString(R.string.ui_tool_shortcut, activity.toolTitle(EditorTool.HEALING), EditorTool.HEALING.key))
        waitUntil("Healing tool did not select") { canvas.toolMode == CanvasToolMode.HEALING }
        setNumber(R.string.ui_size, "8") { canvas.strokeSettings.diameter == 8f }
        setNumber(R.string.ui_hardness, "100") { canvas.strokeSettings.hardness == 1f }
        setNumber(R.string.ui_opacity, "100") { canvas.strokeSettings.opacity == 1f }
        for (mode in HealingMode.entries) {
            if (canvas.healingMode != mode) click(activity.getString(mode.labelResource), header = true)
            waitUntil("Localized mode failed to select: $mode") { canvas.healingMode == mode }
            val before = pixels(); val initial = editor.currentRevision()
            gesture(cancel = true)
            check(editor.currentRevision() == initial && pixels().contentEquals(before)) { "$mode pointer Cancel changed pixels" }
            gesture(cancel = false)
            val after = pixels()
            val index = (32 * 64 + 32) * 4
            check((0..2).any { after[index + it] != before[index + it] }) {
                "$mode real pointer left the damaged center unchanged"
            }
            repeat(64) { x -> repeat(4) { channel ->
                val untouched = (2 * 64 + x) * 4 + channel
                check(after[untouched] == before[untouched]) { "$mode changed pixels outside pointer coverage" }
            } }
            check(editor.currentRevision() == initial + 1 && !after.contentEquals(before))
            click(activity.getString(R.string.ui_undo)); drain(true)
            check(pixels().contentEquals(before)) { "$mode UI Undo did not restore full fixture" }
            click(activity.getString(R.string.ui_redo)); drain(true)
            check(pixels().contentEquals(after)) { "$mode UI Redo changed full fixture" }
            click(activity.getString(R.string.ui_undo)); drain(true)
            check(pixels().contentEquals(before))
        }
        setNumber(R.string.ui_opacity, "65") { canvas.strokeSettings.opacity == .65f }
        setNumber(R.string.ui_size, "12") { canvas.strokeSettings.diameter == 12f }
        val retainedPixels = pixels(); val retainedRevision = editor.currentRevision(); val previous = canvas
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            accessibility?.close(); accessibility = null
            main { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        bindCanvas(previous); accessibility = bindAccessibility()
        waitUntil("Healing mode/settings did not survive Activity recreation") {
            canvas.toolMode == CanvasToolMode.HEALING && canvas.healingMode == HealingMode.PROXIMITY_MATCH &&
                canvas.strokeSettings.diameter == 12f && canvas.strokeSettings.opacity == .65f && canvas.strokeSettings.hardness == 1f
        }
        check(editor.currentRevision() == retainedRevision && pixels().contentEquals(retainedPixels))
        screenshot("healing-localized-header.png")
        File(evidence, "result.json").writeText(org.json.JSONObject().put("passed", true)
            .put("locale", activity.resources.configuration.locales.toLanguageTags())
            .put("checks", "localized tool/modes and numeric size/hardness/opacity, real pointer Cancel/commit, damaged-center and untouched-row witnesses, UI Undo/Redo, Activity retention")
            .put("fixtureLayer", fixtureId).put("replacedUserDocument", false).toString(2))
        return "Healing UI passed real localized controls, three-mode healing pointer pixels, Cancel/Undo/Redo and Activity-retained mode/tip. Evidence: ${evidence.absolutePath}"
    } catch (failure: Throwable) {
        runCatching { screenshot("failure.png") }
        File(evidence, "failure.txt").writeText(failure.stackTraceToString())
        throw failure
    } finally {
        accessibility?.close()
        if (editorReady && fixtureId != 0L) {
            // Release only reservations observed during this test. This also
            // handles failure between DOWN and UP, without waiting on the live
            // pointer's own retained barrier or canceling another view's work.
            editor.cancelReservations(ownedStrokes)
            editor.commandDispatcher.awaitIdleForTest().get(15, TimeUnit.SECONDS)
            editor.serialized {
                if (fixtureId != 0L && editor.state().layers.any { it.id == fixtureId }) editor.deleteLayer(fixtureId)
                originalSelection?.let { selection ->
                    val live = editor.state().layers.map { it.id }.toSet()
                    if (selection.roots.all { it in live } && (selection.primary == 0L || selection.primary in live))
                        editor.selectLayers(selection.roots, selection.primary, selection.mask)
                }
            }
            check(editor.state().layers.none { it.id == fixtureId })
            originalLayers?.let { check(editor.state().layers == it) { "Healing UI cleanup changed original user layer metadata" } }
            activity.retainedWorkspaceForTest().flushAsync().get(15, TimeUnit.SECONDS)
            val cleaned = editor.currentRevision()
            waitUntil("Healing fixture cleanup did not reach recovery") {
                activity.retainedWorkspaceForTest().snapshot().savedRevision == cleaned
            }
            File(evidence, "cleanup.json").writeText(org.json.JSONObject().put("fixtureAbsent", true)
                .put("savedRevision", cleaned).put("preservedUserDocument", true).toString(2))
        }
        main { activity.finish() }
        instrumentation.waitForIdleSync()
    }
}
