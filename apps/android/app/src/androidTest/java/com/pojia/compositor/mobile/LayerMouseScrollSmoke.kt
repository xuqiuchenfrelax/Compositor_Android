package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.app.ActivityManager
import android.os.ParcelFileDescriptor
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** UiAutomation suppresses services; enable Compose's own pinned test accessibility path. */
private fun <T> withComposeAccessibilityForSmoke(instrumentation: Instrumentation, activity: MainActivity,
    block: () -> T): T {
    fun find(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    lateinit var delegate: Any
    lateinit var setter: java.lang.reflect.Method
    lateinit var getter: java.lang.reflect.Method
    var previous = false
    instrumentation.runOnMainSync {
        val root = checkNotNull(find(activity.window.decorView)) { "Compose root missing" }
        val field = root.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }
        delegate = checkNotNull(field.get(root))
        getter = delegate.javaClass.methods.single { it.name.startsWith("getAccessibilityForceEnabledForTesting") && it.parameterCount == 0 }
        setter = delegate.javaClass.methods.single { it.name.startsWith("setAccessibilityForceEnabledForTesting") && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
        previous = getter.invoke(delegate) as Boolean
        setter.invoke(delegate, true)
        check(getter.invoke(delegate) == true)
    }
    try { return block() } finally {
        instrumentation.runOnMainSync {
            setter.invoke(delegate, previous)
            check(getter.invoke(delegate) == previous)
        }
    }
}

/** Optional dedicated-emulator isolation; task lock is always released before closing the activity. */
private fun layerSmokeTaskLock(instrumentation: Instrumentation, activity: MainActivity, enabled: Boolean): () -> Unit {
    if (!enabled) return {}
    val manager = activity.getSystemService(ActivityManager::class.java)
    check(manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) { "Existing task lock must be preserved" }
    fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    fun awaitState(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (manager.lockTaskModeState != expected && System.nanoTime() < deadline) Thread.sleep(20)
        check(manager.lockTaskModeState == expected) { "Unexpected task lock state ${manager.lockTaskModeState}" }
    }
    var released = false
    val release = {
        if (!released) {
            shell("am task lock stop")
            awaitState(ActivityManager.LOCK_TASK_MODE_NONE)
            released = true
        }
        Unit
    }
    try {
        shell("am task lock ${activity.taskId}")
        awaitState(ActivityManager.LOCK_TASK_MODE_PINNED)
    } catch (failure: Throwable) { release(); throw failure }
    return release
}

/** Native Compose row navigation, with independent accessibility offsets and document snapshots. */
internal fun layerMouseScrollSmoke(instrumentation: Instrumentation, wide: Boolean, lockOwnTask: Boolean = false): String {
    fun helperChecks() {
        var offset = 0f; var calls = 0
        val input = LayerMouseScrollInput { delta -> offset = (offset + delta).coerceIn(0f, 500f); calls++ }
        val down = LayerMouseScrollInput.Sample(LayerMouseScrollInput.Action.PRESS, 7, true, 1,
            10f, 300f, true, false, true, false)
        fun move(y: Float, x: Float = 10f) = down.copy(action = LayerMouseScrollInput.Action.MOVE,
            y = y, x = x, wasPressed = true)
        val up = down.copy(action = LayerMouseScrollInput.Action.RELEASE, pressed = false,
            wasPressed = true, middle = false, anyPressed = false, y = 999f)
        check(!input.route(move(200f)) && !input.route(down.copy(mouse = false)))
        check(!input.route(down.copy(middle = false, otherButton = true)))
        check(input.route(down) && input.active)
        input.route(move(200f)); check(offset == 100f)
        input.route(move(200f, 900f)); check(offset == 100f && calls == 1)
        input.route(move(-100_000f)); check(offset == 500f)
        input.route(move(-99_960f)); check(offset == 460f)
        input.route(move(100_000f)); check(offset == 0f)
        input.route(move(99_960f)); check(offset == 40f)
        input.route(up); check(!input.active && offset == 40f)
        check(!input.route(move(0f)))
        input.route(down)
        input.route(move(260f).copy(action = LayerMouseScrollInput.Action.OTHER))
        check(offset == 80f && input.active)
        input.route(up); check(offset == 80f && !input.active)
        check(!input.route(move(0f).copy(action = LayerMouseScrollInput.Action.OTHER)))
        for (bad in listOf(move(100f).copy(x = Float.NaN), move(100f).copy(y = Float.POSITIVE_INFINITY),
            move(100f).copy(pointerId = 8), move(100f).copy(pointerCount = 2),
            move(100f).copy(mouse = false), move(100f).copy(otherButton = true),
            move(100f).copy(middle = false), move(100f).copy(consumed = true))) {
            input.route(down); val before = offset
            check(input.route(bad) && !input.active)
            check(input.route(move(0f)) && offset == before)
            check(input.route(up)); check(!input.route(move(0f)))
        }
        input.route(down); input.cancel(); val before = offset
        check(input.route(move(0f)) && !input.active && offset == before); input.route(up)
        input.route(down); input.route(move(0f), false)
        check(!input.active && offset == before); input.route(up)
        check(input.route(down, false) && !input.active); input.route(move(0f)); input.route(up)
        check(offset == before)

        var recoveredOffset = 0f
        val recovery = LayerMouseScrollInput { recoveredOffset += it }
        val primary = down.copy(middle = false, otherButton = true)
        check(recovery.route(down)); recovery.cancel()
        check(!recovery.route(primary) && !recovery.active && recoveredOffset == 0f) {
            "A fresh primary press must escape the canceled middle stream"
        }
        check(!recovery.route(up) && recoveredOffset == 0f)
        check(recovery.route(down)); recovery.cancel()
        check(recovery.route(down) && recovery.active && recoveredOffset == 0f) {
            "A fresh middle press must begin its own grab after cancellation"
        }
        check(recovery.route(move(260f)) && recoveredOffset == 40f)
        check(recovery.route(up) && !recovery.active)
        check(recovery.route(down)); recovery.cancel()
        val heldOffset = recoveredOffset
        check(recovery.route(move(200f)) && !recovery.active && recoveredOffset == heldOffset)
        check(recovery.route(primary.copy(pointerCount = 2)) && !recovery.active && recoveredOffset == heldOffset)
        check(recovery.route(move(180f)) && !recovery.active && recoveredOffset == heldOffset) {
            "A second pointer must not release the canceled stream"
        }
        check(recovery.route(primary.copy(wasPressed = true)) && !recovery.active && recoveredOffset == heldOffset)
        check(recovery.route(move(160f)) && !recovery.active && recoveredOffset == heldOffset) {
            "A held button chord must not release the canceled stream"
        }
        check(!recovery.route(primary) && !recovery.active && recoveredOffset == heldOffset)
    }
    helperChecks()
    val automation = instrumentation.uiAutomation
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val releaseTask = layerSmokeTaskLock(instrumentation, activity, lockOwnTask)
    try {
    return withComposeAccessibilityForSmoke(instrumentation, activity) {
    val scroll = AtomicInteger(0); val maximum = AtomicInteger(0); val scrollEvents = AtomicInteger(0)
    automation.setOnAccessibilityEventListener { event ->
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.source?.contentDescription?.toString() == "Layer list") {
            scroll.set(event.scrollY); maximum.set(event.maxScrollY); scrollEvents.incrementAndGet()
        }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }
    fun canvas(): CanvasView? {
        var result: CanvasView? = null
        instrumentation.runOnMainSync { result = findCanvas(activity.window.decorView) }
        return result
    }
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        if (!predicate()) {
            val error = IllegalStateException("$message (scroll=${scroll.get()}, max=${maximum.get()}, events=${scrollEvents.get()})")
            captureEditorUiFailure(instrumentation, activity, error, emptyList())
            throw error
        }
    }
    fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    fun node(label: String): AccessibilityNodeInfo? {
        automation.clearCache()
        return find(automation.rootInActiveWindow, label)
    }
    fun bounds(label: String): Rect? = node(label)?.let { Rect().also(it::getBoundsInScreen) }
    fun click(label: String) {
        var clicked = false
        waitUntil("Missing clickable $label") {
            var target = node(label)
            while (target != null && !target.isClickable) target = target.parent
            if (!clicked) clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    waitUntil("Layer navigation workspace missing") {
        activity.window.decorView.hasWindowFocus() && canvas()?.let { it.holder.surface.isValid && it.width > 0 && it.height > 0 } == true
    }
    check((activity.resources.configuration.screenWidthDp >= 700) == wide) { "Wrong requested layout" }
    val workspace = activity.retainedWorkspaceForTest()
    var current: NativeEditor? = null
    fun install(count: Int): Pair<NativeEditor, CanvasView> {
        val old = canvas()
        check(workspace.installCandidate("p35-layers-$count-${System.nanoTime()}", DirtyDisposition.DISCARD) {
            NativeEditor(64, false).also { editor ->
                repeat(count - 1) { index ->
                    editor.addLayer(); editor.renameLayer(editor.state().layers.last().id, "P35 Row %03d".format(index + 1))
                }
                val red = ByteBuffer.allocateDirect(64).apply { repeat(16) { put(byteArrayOf(-1, 0, 0, -1)) }; rewind() }
                val id = editor.importPixels(4, 4, red)
                editor.renameLayer(id, "P35 Row %03d".format(count))
                editor.setTransform(id, Transform64(24.0, 24.0, 16.0, 16.0)); editor.selectLayer(id)
            }
        }.result.get(15, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor); current = editor
        waitUntil("Replacement Layers canvas missing") { canvas()?.let { it !== old && it.holder.surface.isValid &&
            it.width > 0 && it.layerId == editor.state().document.activeLayerId &&
            activity.window.decorView.hasWindowFocus() && activity.retainedEditorForTest() === editor } == true }
        if (!wide) {
            waitUntil("Narrow Layers button missing") { node("Layers") != null }
            if (bounds("Layer list") == null) click("Layers")
        }
        waitUntil("Layer row viewport missing") { bounds("Layer list")?.let { it.width() > 0 && it.height() > 0 } == true }
        scroll.set(0); maximum.set(0); scrollEvents.set(0)
        return editor to checkNotNull(canvas())
    }
    var downTime = 0L; var eventTime = 0L
    fun pointer(action: Int, x: Float, y: Float, buttons: Int = MotionEvent.BUTTON_TERTIARY, modifiers: Int = 0) {
        eventTime = maxOf(SystemClock.uptimeMillis(), eventTime + 1)
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
        val event = MotionEvent.obtain(downTime, eventTime, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
            modifiers, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try {
            instrumentation.runOnMainSync {
                check(activity.window.decorView.hasWindowFocus() && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { "Compositor lost foreground before mouse dispatch" }
                val origin = IntArray(2); activity.window.decorView.getLocationOnScreen(origin)
                event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                // A real mouse moves to its next target before pressing. Compose keeps mouse
                // hit paths across UP; a teleported DOWN alone can retain the previous child.
                if (action == MotionEvent.ACTION_DOWN) {
                    val hover = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, 1,
                        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
                        arrayOf(MotionEvent.PointerCoords().apply { this.x = event.x; this.y = event.y }),
                        modifiers, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                    try {
                        activity.window.decorView.dispatchGenericMotionEvent(hover)
                    } finally { hover.recycle() }
                }
                activity.window.decorView.dispatchTouchEvent(event)
            }
        } finally { event.recycle() }
        instrumentation.waitForIdleSync()
    }
    fun drain(view: CanvasView) {
        val latch = CountDownLatch(1); view.queueEvent { latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val bytes = ByteBuffer.allocateDirect(64 * 64 * 4)
        checkNotNull(editor.captureDisplayScene()).use { it.draw(64, 64, Viewport(0.0, 0.0, 64.0, 64.0), bytes) }
        return ByteArray(bytes.capacity()).also { bytes.rewind(); bytes.get(it) }
    }
    try {
        if (wide) {
            val (editor, view) = install(3); drain(view)
            val before = checkNotNull(bounds("Select P35 Row 003"))
            val list = checkNotNull(bounds("Layer list")); check(list.height() >= before.height() * 3)
            val originalPixels = pixels(editor); val state = editor.state()
            val x = before.exactCenterX(); val y = before.exactCenterY()
            pointer(MotionEvent.ACTION_DOWN, x, y); pointer(MotionEvent.ACTION_MOVE, x, y - 100)
            pointer(MotionEvent.ACTION_UP, x, y - 200, 0)
            check(bounds("Select P35 Row 003") == before && editor.state() == state && pixels(editor).contentEquals(originalPixels))
        }
        val (editor, view) = install(30); drain(view)
        var viewportPublications = 0
        val previousViewport = view.viewportChanged
        instrumentation.runOnMainSync { view.viewportChanged = { viewportPublications++; previousViewport?.invoke(it) } }
        val originalPixels = pixels(editor); val original = editor.state(); val zoom = view.zoomPercent
        val top = checkNotNull(bounds("Select P35 Row 030")); val list = checkNotNull(bounds("Layer list"))
        val x = (list.left + 12).toFloat(); val y = top.exactCenterY()
        pointer(MotionEvent.ACTION_DOWN, x, y)
        pointer(MotionEvent.ACTION_MOVE, x, y - 100)
        waitUntil("Middle row drag did not scroll100 actual pixels") { scrollEvents.get() > 0 && scroll.get() == 100 && maximum.get() > 140 }
        // Crossing back into the viewport must apply the reverse delta on the same grab.
        pointer(MotionEvent.ACTION_MOVE, x, y)
        waitUntil("Reentering the list did not reverse the captured drag") { scroll.get() == 0 }
        pointer(MotionEvent.ACTION_MOVE, x, y - 100)
        waitUntil("Leaving the list again lost the captured drag") { scroll.get() == 100 }
        // Use a fully visible shared row in the wide layout as an independent displacement witness.
        if (wide) {
            val witness = checkNotNull(bounds("Select P35 Row 024"))
            pointer(MotionEvent.ACTION_MOVE, x + 100, y - 140)
            waitUntil("Displayed row did not move with scroll offset") {
                scrollEvents.get() > 1 && scroll.get() == 140 && bounds("Select P35 Row 024")?.top == witness.top - 40
            }
        }
        pointer(MotionEvent.ACTION_MOVE, x, -100_000f)
        waitUntil("Middle drag did not clamp to bottom") { scroll.get() == maximum.get() && maximum.get() > 140 }
        val end = maximum.get()
        pointer(MotionEvent.ACTION_MOVE, x, -99_960f)
        waitUntil("Bottom overshoot accumulated a reversal deadzone") { scroll.get() == end - 40 }
        pointer(MotionEvent.ACTION_MOVE, x, 100_000f)
        waitUntil("Middle drag did not clamp to top") { scroll.get() == 0 }
        pointer(MotionEvent.ACTION_MOVE, x, 99_960f)
        waitUntil("Top overshoot accumulated a reversal deadzone") { scroll.get() == 40 }
        pointer(MotionEvent.ACTION_UP, x, 99_000f, 0)
        check(editor.state() == original && pixels(editor).contentEquals(originalPixels) &&
            view.zoomPercent == zoom && viewportPublications == 0)

        // A middle gesture starting on the real checkbox must not toggle it or select its row.
        val checkbox = checkNotNull(bounds("Visibility P35 Row 029"))
        val cx = checkbox.exactCenterX(); val cy = checkbox.exactCenterY()
        pointer(MotionEvent.ACTION_DOWN, cx, cy)
        pointer(MotionEvent.ACTION_MOVE, cx, cy - 40)
        waitUntil("Middle checkbox drag was not intercepted") { scroll.get() == 80 }
        pointer(MotionEvent.ACTION_UP, cx, cy - 40, 0)
        check(editor.state() == original && pixels(editor).contentEquals(originalPixels))
        pointer(MotionEvent.ACTION_DOWN, cx, cy); pointer(MotionEvent.ACTION_MOVE, cx, cy + 80)
        waitUntil("List did not return to top") { scroll.get() == 0 }
        pointer(MotionEvent.ACTION_CANCEL, cx, cy + 80, 0)

        // Ordinary primary clicking still executes the existing row and visibility commands.
        val row = checkNotNull(bounds("Select P35 Row 029"))
        val rx = (row.left + 16).toFloat(); val ry = row.exactCenterY()
        pointer(MotionEvent.ACTION_DOWN, rx, ry, MotionEvent.BUTTON_PRIMARY)
        pointer(MotionEvent.ACTION_UP, rx, ry, 0)
        val selectedId = original.layers.first { it.name == "P35 Row 029" }.id
        waitUntil("Primary row selection was swallowed") {
            var acknowledged = false
            instrumentation.runOnMainSync { acknowledged = view.layerId == selectedId && view.canChangeTool }
            editor.state().document.activeLayerId == selectedId && !editor.hasPendingEdits && acknowledged
        }
        val visible = checkNotNull(bounds("Visibility P35 Row 030"))
        pointer(MotionEvent.ACTION_DOWN, visible.exactCenterX(), visible.exactCenterY(), MotionEvent.BUTTON_PRIMARY)
        pointer(MotionEvent.ACTION_UP, visible.exactCenterX(), visible.exactCenterY(), 0)
        val marker = original.layers.last()
        waitUntil("Primary visibility click was swallowed") { editor.state().layers.last().visible != marker.visible }
        view.command { editor.undo(false); editor.selectLayer(marker.id) }
        waitUntil("Primary fixture did not restore visibility and target") { editor.state().layers == original.layers &&
            editor.state().document.activeLayerId == marker.id && view.layerId == marker.id }

        // P41 readiness clock begin
        val moveReadyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // P41 readiness clock end
        click("Move / Transform (V)")
        waitUntil("Move mode missing") { view.toolMode == CanvasToolMode.MOVE }
        // P41 layout readiness begin
        while (bounds("Start Transform") == null && System.nanoTime() < moveReadyDeadline) {
            Thread.sleep(20); instrumentation.waitForIdleSync()
        }
        check(bounds("Start Transform") != null) { "Move controls missing" }
        val moveFrame = CountDownLatch(1)
        instrumentation.runOnMainSync { view.postOnAnimation { moveFrame.countDown() } }
        check(moveFrame.await(maxOf(1, moveReadyDeadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
            "Move layout frame missing"
        }
        instrumentation.waitForIdleSync()
        var moveLayoutReady = false
        while (!moveLayoutReady && System.nanoTime() < moveReadyDeadline) {
            val rect = bounds("Canvas")
            instrumentation.runOnMainSync {
                moveLayoutReady = rect != null && view.isLaidOut && !view.isLayoutRequested &&
                    view.width > 0 && view.height > 0 && view.width == rect.width() && view.height == rect.height()
            }
            if (!moveLayoutReady) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        }
        check(moveLayoutReady) { "Move canvas layout did not settle" }
        instrumentation.waitForIdleSync()
        // P41 layout readiness end
        instrumentation.runOnMainSync { check(view.editMoveField(MoveField.X, 25.25)) }
        val expected = marker.transform.copy(x = 25.25)
        waitUntil("Numeric Move preview not applied") {
            view.moveInProgress && editor.hasActiveTransformDraft && editor.activeTransformCompletion() != null &&
                editor.captureDisplayScene()?.use { it.layerMetadata(29).transform == expected } == true
        }
        val preview = pixels(editor); val activeState = editor.state(); val owner = editor.activeTransformCompletion()
        val box = view.moveBox; val beforeZoom = view.zoomPercent; viewportPublications = 0
        val disabledCheckbox = checkNotNull(bounds("Visibility P35 Row 030"))
        pointer(MotionEvent.ACTION_DOWN, disabledCheckbox.exactCenterX(), disabledCheckbox.exactCenterY())
        pointer(MotionEvent.ACTION_MOVE, disabledCheckbox.exactCenterX(), disabledCheckbox.exactCenterY() - 40)
        waitUntil("Read-only navigation was disabled by numeric Move") { scroll.get() == 40 }
        pointer(MotionEvent.ACTION_UP, disabledCheckbox.exactCenterX(), disabledCheckbox.exactCenterY() - 40, 0)
        // Sample the original seven operands once; format and write evidence only on failure.
        val invariantStartedAt = System.nanoTime()
        val actualState = editor.state()
        val actualPixels = pixels(editor)
        val actualMoveInProgress = view.moveInProgress
        val actualOwner = editor.activeTransformCompletion()
        val actualBox = view.moveBox
        val actualZoom = view.zoomPercent
        val actualViewportPublications = viewportPublications
        val invariantEndedAt = System.nanoTime()
        val stateMatches = actualState == activeState
        val pixelsMatch = actualPixels.contentEquals(preview)
        val moveInProgressMatches = actualMoveInProgress
        val ownerMatches = actualOwner === owner
        val boxMatches = actualBox == box
        val zoomMatches = actualZoom == beforeZoom
        val viewportPublicationsMatch = actualViewportPublications == 0
        try {
            check(stateMatches && pixelsMatch && moveInProgressMatches && ownerMatches &&
                boxMatches && zoomMatches && viewportPublicationsMatch)
        } catch (error: Throwable) {
            runCatching {
                val directory = java.io.File(instrumentation.targetContext.filesDir,
                    "p40-layer-${if (wide) "wide" else "narrow"}-failure-${System.nanoTime()}")
                    .apply { check(mkdir()) }
                java.io.File(directory, "expected-preview.rgba").writeBytes(preview)
                java.io.File(directory, "actual-preview.rgba").writeBytes(actualPixels)
                fun ownerIdentity(value: Any?): Any = value?.let {
                    org.json.JSONObject().put("identityHash", System.identityHashCode(it)).put("class", it.javaClass.name)
                } ?: org.json.JSONObject.NULL
                fun pixelSHA256(value: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
                val evidence = org.json.JSONObject().put("schema", 1).put("wide", wide)
                    .put("scope", "New execution only; original runtime04 operands remain unknown")
                    .put("pixelWidth", 64).put("pixelHeight", 64).put("pixelFormat", "RGBA8")
                    .put("startedAtNanoTime", invariantStartedAt).put("endedAtNanoTime", invariantEndedAt)
                    .put("evidenceStartedAtNanoTime", System.nanoTime())
                    .put("failure", android.util.Log.getStackTraceString(error))
                    .put("clauses", org.json.JSONObject().put("state", stateMatches).put("pixels", pixelsMatch)
                        .put("moveInProgress", moveInProgressMatches).put("owner", ownerMatches).put("box", boxMatches)
                        .put("zoom", zoomMatches).put("viewportPublications", viewportPublicationsMatch))
                    .put("expected", org.json.JSONObject().put("state", activeState.toString())
                        .put("pixelsBytes", preview.size).put("pixelsSHA256", pixelSHA256(preview))
                        .put("pixelsFile", "expected-preview.rgba").put("moveInProgress", true)
                        .put("owner", ownerIdentity(owner)).put("box", box?.toString() ?: org.json.JSONObject.NULL)
                        .put("zoom", beforeZoom).put("zoomBits", java.lang.Double.doubleToRawLongBits(beforeZoom).toString())
                        .put("viewportPublications", 0))
                    .put("actual", org.json.JSONObject().put("state", actualState.toString())
                        .put("pixelsBytes", actualPixels.size).put("pixelsSHA256", pixelSHA256(actualPixels))
                        .put("pixelsFile", "actual-preview.rgba").put("moveInProgress", actualMoveInProgress)
                        .put("owner", ownerIdentity(actualOwner)).put("box", actualBox?.toString() ?: org.json.JSONObject.NULL)
                        .put("zoom", actualZoom).put("zoomBits", java.lang.Double.doubleToRawLongBits(actualZoom).toString())
                        .put("viewportPublications", actualViewportPublications))
                java.io.File(directory, "diagnostic.json").writeText(evidence.toString(2))
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "Layer invariant failure evidence: files/${directory.name}/diagnostic.json\n")
                })
            }.onFailure { if (it !== error) error.addSuppressed(it) }
            throw error
        }
        instrumentation.runOnMainSync { view.cancelMove() }
        waitUntil("Layers numeric Move did not cancel") { !view.moveInProgress && editor.activeTransformCompletion() == null }
        return@withComposeAccessibilityForSmoke "Real Compose ${if (wide) "wide3/30" else "narrow30"} Layers: public mouse DOWN/MOVE/UP through decorView, row/checkbox interception, standard accessibility scroll100/clamps/reverse40 and wide displayed-row displacement; primary row+visibility commands remain executable. Exact document/history/pixels/selection and Canvas viewport preserved; disabled-edit numeric Move owner/full preview preserved. Helper loss/source/type/pointer/nonfinite/focus/cancel checks pass. Direct UI delivery, not OS injection, physical mouse, device-ID, large-list virtualization or performance proof."
    } finally {
        automation.setOnAccessibilityEventListener(null)
        canvas()?.let { view -> instrumentation.runOnMainSync { view.cancelMove() } }
        current?.activeTransformCompletion()?.get(10, TimeUnit.SECONDS)
        releaseTask()
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
        workspace.closeAsync().get(15, TimeUnit.SECONDS)
    }
    }
    } finally {
        releaseTask()
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }
}
