package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Real Transform controls and input retain one native transaction until an explicit terminal. */
internal fun movePersistentSmoke(instrumentation: Instrumentation): String {
    val automation = instrumentation.uiAutomation
    var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val evidence = File(instrumentation.targetContext.filesDir, "p36-move-persistent-${System.nanoTime()}")
    val stages = JSONArray()
    val navigation = JSONArray()
    var successful = false
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun node(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
        for (index in 0 until root.childCount) node(root.getChild(index), label)?.let { return it }
        return null
    }
    fun click(label: String) {
        revealEditorControl(instrumentation, label)
        var action: AccessibilityNodeInfo? = null
        val numericLabels = setOf("Move X value", "Move Y value", "Move Width value", "Move Height value", "Move Rotation value")
        fun numericScroll(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isScrollable && numericLabels.any { node(root, it) != null }) return root
            for (index in 0 until root.childCount) numericScroll(root.getChild(index))?.let { return it }
            return null
        }
        fun numericFingerprint(root: AccessibilityNodeInfo): String = numericLabels.mapNotNull { name ->
            node(root, name)?.let { value ->
                val bounds = Rect().also { value.getBoundsInScreen(it) }
                "$name:${bounds.toShortString()}:${value.text}"
            }
        }.joinToString(";")
        val scrollActions = listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        var scrollDirection = 0
        var pendingScroll: String? = null
        val navigationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        waitUntil("Persistent Move control unavailable: $label") {
            automation.clearCache()
            val root = automation.rootInActiveWindow
            var candidate = node(root, label)
            while (candidate != null && !candidate.isClickable) candidate = candidate.parent
            action = candidate?.takeIf { it.isEnabled }
            if (candidate == null && label in numericLabels && System.nanoTime() < navigationDeadline) {
                val scroll = numericScroll(root)
                if (scroll != null) {
                    val before = numericFingerprint(scroll)
                    if (pendingScroll == null || pendingScroll != before) {
                        pendingScroll = null
                        if (scrollDirection < scrollActions.size) {
                            val direction = scrollActions[scrollDirection]
                            if (scroll.actionList.none { it.id == direction }) scrollDirection++ else {
                                val accepted = scroll.performAction(direction)
                                navigation.put(JSONObject().put("label", label).put("action", direction)
                                    .put("accepted", accepted).put("before", before))
                                check(accepted) { "Move numeric row rejected advertised scroll action $direction" }
                                pendingScroll = before
                            }
                        }
                    }
                }
            }
            action != null
        }
        checkNotNull(action).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync()
        automation.clearCache()
        var target = node(automation.rootInActiveWindow, label)
        while (target != null && !target.isClickable) target = target.parent
        check(target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Persistent Move click rejected: $label"
        }
        instrumentation.waitForIdleSync()
    }
    fun key(code: Int, modifiers: Int = 0) {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, modifiers))
        instrumentation.sendKeySync(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0, modifiers))
        instrumentation.waitForIdleSync()
    }
    fun setField(label: String, text: String) {
        click(label)
        var field: AccessibilityNodeInfo? = null
        waitUntil("Persistent Move field did not focus: $label") {
            automation.clearCache()
            var candidate = node(automation.rootInActiveWindow, label)
            while (candidate != null && !candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
                candidate = candidate.parent
            field = candidate?.takeIf { it.isEditable && it.isEnabled && it.isFocused }
            field != null
        }
        check(checkNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })) { "Persistent Move field rejected text: $label" }
        instrumentation.waitForIdleSync()
    }
    var initial: CanvasView? = null
    waitUntil("Persistent Move initial canvas missing") {
        instrumentation.runOnMainSync { initial = findCanvas(activity.window.decorView) }
        initial != null && activity.retainedEditorForTest() != null
    }
    val previous = initial
    val workspace = activity.retainedWorkspaceForTest()
    check(workspace.installCandidate("p36-move-persistent-${System.nanoTime()}", DirtyDisposition.DISCARD) {
        NativeEditor(256, false).also { editor ->
            val pixels = ByteBuffer.allocateDirect(16).apply {
                put(byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, 0, -1)); rewind()
            }
            val id = editor.importPixels(2, 2, pixels)
            editor.setTransform(id, Transform64(80.0, 80.0, 96.0, 96.0)); editor.selectLayer(id)
        }
    }.result.get(15, TimeUnit.SECONDS).installed)
    val editor = checkNotNull(workspace.snapshot().editor)
    var replacement: CanvasView? = null
    waitUntil("Persistent Move replacement canvas missing") {
        instrumentation.runOnMainSync {
            replacement = findCanvas(activity.window.decorView)?.takeIf {
                it !== previous && it.isAttachedToWindow && it.holder.surface.isValid && it.width > 0 && it.height > 0 &&
                    activity.retainedEditorForTest() === editor
            }
        }
        replacement != null
    }
    var canvas = checkNotNull(replacement)
    fun settled() = waitUntil("Persistent Move terminal did not settle") {
        !canvas.moveInProgress && !canvas.movePersistent && editor.activeTransformCompletion() == null && !editor.hasActiveTransformDraft
    }
    fun preview(expected: Transform64) = waitUntil("Persistent Move preview differs from $expected") {
        canvas.moveBox == expected && editor.captureDisplayScene()?.use { it.layerMetadata(0).transform == expected } == true
    }
    fun pixels(): ByteArray {
        val buffer = ByteBuffer.allocateDirect(256 * 256 * 4)
        checkNotNull(editor.captureDisplayScene()).use { it.draw(256, 256, Viewport(0.0, 0.0, 256.0, 256.0), buffer) }
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun stableBounds(): Rect {
        val latch = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        var last: Rect? = null
        var result: Rect? = null
        var frames = 0
        val frame = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val bounds = Rect()
                val ready = canvas.getGlobalVisibleRect(bounds) && canvas.isLaidOut && !canvas.isLayoutRequested &&
                    canvas.holder.surface.isValid && canvas.holder.surfaceFrame.width() == canvas.width &&
                    canvas.holder.surfaceFrame.height() == canvas.height
                frames = if (ready && bounds == last) frames + 1 else 0
                last = bounds
                if (frames >= 2) { result = bounds; latch.countDown() } else canvas.postOnAnimation(this)
            }
        }
        instrumentation.runOnMainSync { canvas.postOnAnimation(frame) }
        try { check(latch.await(15, TimeUnit.SECONDS)) { "Persistent Move canvas did not finish layout" } }
        finally { stopped.set(true); instrumentation.runOnMainSync { canvas.removeCallbacks(frame) } }
        return checkNotNull(result)
    }
    fun recreate(expected: Transform64, owner: java.util.concurrent.CompletableFuture<TransformController.Terminal>) {
        preview(expected)
        val before = editor.state()
        val beforePixels = pixels()
        val lockRatio = canvas.moveLockRatio
        val showControls = canvas.moveShowControls
        val previousActivity = activity
        val previousCanvas = canvas
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { previousActivity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        check(activity !== previousActivity && activity.retainedWorkspaceForTest() === workspace &&
            activity.retainedEditorForTest() === editor) { "Activity recreation replaced the retained document" }
        var next: CanvasView? = null
        waitUntil("Recreated Transform canvas did not attach") {
            instrumentation.runOnMainSync {
                next = findCanvas(activity.window.decorView)?.takeIf {
                    it !== previousCanvas && it.isAttachedToWindow && it.holder.surface.isValid && it.width > 0 && it.height > 0
                }
            }
            next != null
        }
        canvas = checkNotNull(next)
        check(!previousCanvas.isAttachedToWindow)
        waitUntil("Recreated canvas did not adopt the persistent Transform and settings") {
            canvas.toolMode == CanvasToolMode.MOVE && canvas.movePersistent && canvas.moveBox == expected &&
                canvas.layerId == before.document.activeLayerId && canvas.moveLockRatio == lockRatio && canvas.moveShowControls == showControls
        }
        preview(expected)
        check(editor.activeTransformCompletion() === owner && !owner.isDone && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) &&
            pixels().contentEquals(beforePixels)) { "Recreation changed Transform owner, preview, committed state or history" }
        stableBounds()
        stages.put("Actual Activity recreation retained editor/native owner/preview pixels/ratio and control settings")
    }
    fun drag(original: Transform64, dx: Double, dy: Double, cancel: Boolean = false): Transform64 {
        stableBounds()
        val scale = canvas.zoomPercent / 100
        val location = IntArray(2)
        var overlay: MoveHandleOverlay? = null
        instrumentation.runOnMainSync { canvas.getLocationOnScreen(location); overlay = canvas.moveOverlay(original) }
        val controls = checkNotNull(overlay)
        val x = location[0] + ((controls.handles[0].x + controls.handles[4].x) / 2).toFloat()
        val y = location[1] + ((controls.handles[0].y + controls.handles[4].y) / 2).toFloat()
        check(original.width * scale / 2 > 16 * activity.resources.displayMetrics.density &&
            original.height * scale / 2 > 16 * activity.resources.displayMetrics.density) { "Move fixture center overlaps handles" }
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, deltaX: Double, deltaY: Double, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action,
                x + (deltaX * scale).toFloat(), y + (deltaY * scale).toFloat(), KeyEvent.META_CTRL_ON)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        val expected = original.copy(x = kotlin.math.floor(original.x + dx + .5), y = kotlin.math.floor(original.y + dy + .5))
        pointer(MotionEvent.ACTION_DOWN, 0.0, 0.0, 0)
        pointer(MotionEvent.ACTION_MOVE, dx, dy, 30)
        preview(expected)
        // A terminal coordinate must not replace the last accepted DRAG.
        pointer(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, dx + 15, dy + 15, 60)
        return expected
    }
    fun handleDrag(original: Transform64, expected: Transform64, index: Int?, dx: Double = 0.0,
        dy: Double = 0.0, modifiers: Int = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) {
        stableBounds()
        val location = IntArray(2)
        var overlay: MoveHandleOverlay? = null
        instrumentation.runOnMainSync {
            canvas.getLocationOnScreen(location)
            overlay = canvas.moveOverlay(original)
        }
        val controls = checkNotNull(overlay)
        val scale = canvas.zoomPercent / 100
        val start = index?.let { controls.handles[it] } ?: controls.rotationHandle
        val end = if (index != null) MovePoint(start.x + dx * scale, start.y + dy * scale) else {
            // An unrotated layer's rotation handle is vertically above its center. Move that
            // radius clockwise by exactly 30 degrees; Shift must retain the 15-degree grid.
            check(original.rotation == 0.0)
            val cx = (controls.handles[0].x + controls.handles[4].x) / 2
            val cy = (controls.handles[0].y + controls.handles[4].y) / 2
            val radius = cy - start.y
            MovePoint(cx + radius / 2, cy - radius * kotlin.math.sqrt(3.0) / 2)
        }
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, point: MovePoint, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action,
                location[0] + point.x.toFloat(), location[1] + point.y.toFloat(), modifiers)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        pointer(MotionEvent.ACTION_DOWN, start, 0)
        pointer(MotionEvent.ACTION_MOVE, end, 30)
        preview(expected)
        pointer(MotionEvent.ACTION_UP, MovePoint(end.x + 15, end.y + 15), 60)
        preview(expected)
    }
    fun trimAndSpacePan(expected: Transform64, owner: java.util.concurrent.CompletableFuture<TransformController.Terminal>) {
        preview(expected)
        val before = editor.state()
        val beforePixels = pixels()
        val beforeZoom = canvas.zoomPercent
        fun unchanged() {
            preview(expected)
            check(canvas.movePersistent && editor.activeTransformCompletion() === owner && !owner.isDone &&
                editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels().contentEquals(beforePixels) && canvas.zoomPercent == beforeZoom) {
                "Memory trim or Space navigation changed the persistent owner, draft, committed state, pixels or zoom"
            }
        }
        fun drain() {
            val latch = CountDownLatch(1)
            canvas.queueEvent { latch.countDown() }
            check(latch.await(10, TimeUnit.SECONDS)) { "Memory trim texture-budget work did not finish" }
            instrumentation.waitForIdleSync()
        }
        instrumentation.runOnMainSync { activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
        drain(); unchanged()
        instrumentation.runOnMainSync { canvas.restoreTextureBudget() }
        drain(); unchanged()
        stages.put("Public Activity UI_HIDDEN memory callback preserves persistent owner/preview/history; texture budget restored")

        val bounds = stableBounds()
        fun anchor(): MovePoint {
            var result: MovePoint? = null
            instrumentation.runOnMainSync { result = canvas.moveOverlay(expected).handles[0] }
            return checkNotNull(result)
        }
        val originalAnchor = anchor()
        fun translated(dx: Double, dy: Double) = waitUntil("Space pan did not move the screen overlay by ($dx, $dy)") {
            val current = anchor()
            kotlin.math.abs(current.x - originalAnchor.x - dx) <= .001 &&
                kotlin.math.abs(current.y - originalAnchor.y - dy) <= .001
        }
        val keyDown = SystemClock.uptimeMillis()
        var spacePressed = false
        var pointerPressed = false
        val pointerDown = keyDown + 1
        fun pointer(action: Int, dx: Float, dy: Float, elapsed: Long) {
            val event = MotionEvent.obtain(pointerDown, pointerDown + elapsed, action,
                bounds.exactCenterX() + dx, bounds.exactCenterY() + dy, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        try {
            instrumentation.sendKeySync(KeyEvent(keyDown, keyDown, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0))
            spacePressed = true
            instrumentation.waitForIdleSync()
            waitUntil("Space did not enter the temporary Hand mode") { canvas.toolMode == CanvasToolMode.HAND }
            pointer(MotionEvent.ACTION_DOWN, 0f, 0f, 0); pointerPressed = true
            pointer(MotionEvent.ACTION_MOVE, 24f, 12f, 30)
            translated(24.0, 12.0); unchanged()
            instrumentation.sendKeySync(KeyEvent(keyDown, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0))
            spacePressed = false
            instrumentation.waitForIdleSync()
            waitUntil("Releasing Space did not restore Move mode") { canvas.toolMode == CanvasToolMode.MOVE }
            pointer(MotionEvent.ACTION_MOVE, 40f, 18f, 60)
            translated(40.0, 18.0); unchanged()
            pointer(MotionEvent.ACTION_UP, 100f, 100f, 90); pointerPressed = false
            translated(40.0, 18.0); unchanged()
            val finalAnchor = anchor()
            navigation.put(JSONObject().put("kind", "space-pan-captured")
                .put("startX", originalAnchor.x).put("startY", originalAnchor.y)
                .put("finalX", finalAnchor.x).put("finalY", finalAnchor.y)
                .put("expectedDx", 40).put("expectedDy", 18))
            stages.put("Public Space DOWN, pointer DOWN/MOVE, Space UP, further MOVE/UP retain pan capture: overlay +40/+18 pixels; draft/owner/history unchanged")
        } finally {
            if (pointerPressed) pointer(MotionEvent.ACTION_CANCEL, 40f, 18f, 100)
            if (spacePressed) {
                instrumentation.sendKeySync(KeyEvent(keyDown, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0))
                instrumentation.waitForIdleSync()
            }
        }
    }
    try {
        click("Move / Transform (V)")
        waitUntil("Persistent Move rail did not select Move") { canvas.toolMode == CanvasToolMode.MOVE && canvas.layerId == editor.state().layers.single().id }
        stableBounds()
        val original = editor.state()
        val originalPixels = pixels()
        val shortcutGroups = mutableListOf<KeyboardShortcutGroup>()
        instrumentation.runOnMainSync { activity.onProvideKeyboardShortcuts(shortcutGroups, null, -1) }
        val transformShortcut = shortcutGroups.flatMap { it.items }.single { it.label?.toString() == "Transform" }
        check(transformShortcut.keycode == KeyEvent.KEYCODE_T && transformShortcut.modifiers == KeyEvent.META_CTRL_ON) {
            "Android Transform shortcut advertisement must be Ctrl+T"
        }
        stages.put("Public onProvideKeyboardShortcuts callback advertises Transform Ctrl+T; system shortcut-menu UI not exercised")
        click("Start Transform")
        waitUntil("Transform UI did not open a persistent native draft") { canvas.movePersistent && editor.hasActiveTransformDraft }
        val owner = checkNotNull(editor.activeTransformCompletion())
        fun unchanged() {
            check(editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null) && canvas.movePersistent && editor.activeTransformCompletion() === owner && !owner.isDone) {
                "Persistent input replaced its owner or changed committed document/history"
            }
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
        key(KeyEvent.KEYCODE_DPAD_UP)
        preview(Transform64(90.0, 79.0, 96.0, 96.0)); unchanged()
        stages.put("UI Transform and arrow keys retain one native owner and committed state")
        setField("Move X value", "101.125")
        preview(Transform64(101.125, 79.0, 96.0, 96.0))
        key(KeyEvent.KEYCODE_ENTER)
        waitUntil("Persistent field completion did not restore canvas keyboard focus") {
            automation.clearCache(); node(automation.rootInActiveWindow, "Canvas")?.isFocused == true
        }
        unchanged()
        trimAndSpacePan(Transform64(101.125, 79.0, 96.0, 96.0), owner)
        unchanged()
        var expected = drag(Transform64(101.125, 79.0, 96.0, 96.0), 12.0, 5.0)
        preview(expected); unchanged()
        expected = drag(expected, -7.0, 3.0)
        preview(expected); unchanged()
        stages.put("Fractional field Return and two completed drags preserve draft/history/owner")
        var resized = expected.copy(width = 120.0)
        handleDrag(expected, resized, index = 3, dx = 24.0)
        unchanged()
        // Alt expands both sides around the fixed center; Shift temporarily releases ratio.
        expected = resized.copy(x = resized.x - 6.0, width = 132.0)
        handleDrag(resized, expected, index = 3, dx = 6.0,
            modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON)
        unchanged()
        resized = expected.copy(rotation = 30.0)
        handleDrag(expected, resized, index = null)
        expected = resized
        unchanged()
        stages.put("Real edge resize, Shift ratio inversion, Alt center anchor and Shift 30-degree rotation retain owner")
        click("Move Lock Ratio"); click("Move Show Controls")
        waitUntil("Transform options did not switch off") { !canvas.moveLockRatio && !canvas.moveShowControls }
        recreate(expected, owner)
        unchanged()
        click("Move Lock Ratio"); click("Move Show Controls")
        waitUntil("Transform options did not switch on") { canvas.moveLockRatio && canvas.moveShowControls }
        click("Apply Transform"); settled()
        val committed = editor.state()
        check(committed.revision == original.revision + 1 && committed.layers.single().transform == expected)
        check(owner.get(10, TimeUnit.SECONDS).let { it.committed && !it.cancelled && it.error == null })
        val committedPixels = pixels()
        check(!committedPixels.contentEquals(originalPixels)) { "Applied Move did not change the visible image" }
        click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        waitUntil("A single Undo did not restore all persistent edits") { editor.state().layers == original.layers }
        check(pixels().contentEquals(originalPixels))
        click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        waitUntil("A single Redo did not restore the entire persistent transform") { editor.state().layers == committed.layers }
        check(pixels().contentEquals(committedPixels))
        stages.put("Apply publishes once; one UI Undo/Redo restores exact placement and pixels")

        val beforeCancel = editor.state()
        key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
        waitUntil("Android Ctrl T did not start persistent Transform through system input") { canvas.movePersistent && editor.hasActiveTransformDraft }
        val cancelOwner = checkNotNull(editor.activeTransformCompletion())
        setField("Move Width value", "198")
        waitUntil("Default Transform ratio lock did not preserve dimensions") { canvas.moveBox?.width == 198.0 && canvas.moveBox?.height == 144.0 }
        key(KeyEvent.KEYCODE_ENTER)
        check(editor.state().copy(selectedLayerActions = null) == beforeCancel.copy(selectedLayerActions = null) && editor.activeTransformCompletion() === cancelOwner && canvas.movePersistent)
        recreate(expected.copy(width = 198.0, height = 144.0), cancelOwner)
        click("Cancel Transform"); settled()
        check(cancelOwner.get(10, TimeUnit.SECONDS).let { it.cancelled && !it.committed && it.error == null })
        check(editor.state().copy(selectedLayerActions = null) == beforeCancel.copy(selectedLayerActions = null) && pixels().contentEquals(committedPixels))
        stages.put("Android system-injected Ctrl T, proportional width field, explicit Cancel restore exact state and pixels")

        key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
        waitUntil("Second Android system-injected Ctrl T did not begin") { canvas.movePersistent && editor.hasActiveTransformDraft }
        val interrupted = checkNotNull(editor.activeTransformCompletion())
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val nudged = expected.copy(x = expected.x + 1)
        preview(nudged)
        drag(nudged, 15.0, -9.0, cancel = true); settled()
        check(interrupted.get(10, TimeUnit.SECONDS).let { it.cancelled && !it.committed && it.error == null })
        check(editor.state().copy(selectedLayerActions = null) == beforeCancel.copy(selectedLayerActions = null) && pixels().contentEquals(committedPixels))
        stages.put("Public ACTION_CANCEL removes the whole persistent draft including prior nudges")
        val beforeAlias = editor.state()
        val aliasPixels = pixels()
        val aliasTime = SystemClock.uptimeMillis()
        // Android reserves system META chords. This separate public Activity callback checks only
        // the compatibility alias when an event is delivered to the app, bypassing system policy.
        instrumentation.runOnMainSync {
            check(activity.dispatchKeyShortcutEvent(KeyEvent(aliasTime, aliasTime,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T, 0, KeyEvent.META_META_ON)))
            check(activity.dispatchKeyShortcutEvent(KeyEvent(aliasTime, aliasTime + 1,
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T, 0, KeyEvent.META_META_ON)))
        }
        waitUntil("Direct Activity Meta T compatibility alias did not start Transform") { canvas.movePersistent && editor.hasActiveTransformDraft }
        check(editor.state().copy(selectedLayerActions = null) == beforeAlias.copy(selectedLayerActions = null))
        click("Cancel Transform"); settled()
        check(editor.state().copy(selectedLayerActions = null) == beforeAlias.copy(selectedLayerActions = null) && pixels().contentEquals(aliasPixels))
        stages.put("Direct Activity.dispatchKeyShortcutEvent Meta T alias starts Transform and real UI Cancel restores state; no system META delivery claim")
        successful = true
        return "Real Transform/Apply/Cancel UI, Android system-injected Ctrl T and callback shortcut advertisement, direct Activity Meta T alias only, merged arrow keys, fractional field Return, UI_HIDDEN memory trim, Space pan captured through early Space release, two continuous drags, edge/center resize and 30-degree rotation under one native owner, actual Activity recreation retaining editor/owner/preview/settings with recreated Apply and Cancel, proportional field, exact committed/history isolation, single Undo/Redo pixel restoration, and full ACTION_CANCEL rollback passed. Evidence=${evidence.name}"
    } catch (error: Exception) {
        captureEditorUiFailure(instrumentation, activity, error, emptyList())
        throw error
    } finally {
        evidence.resolveSibling(evidence.name + ".json").writeText(JSONObject().put("passed", successful)
            .put("stages", stages).put("navigation", navigation).put("state", editor.state().toString()).put("activeDraft", editor.hasActiveTransformDraft)
            .put("persistent", canvas.movePersistent).put("previewBox", canvas.moveBox?.toString())
            .put("pendingTransform", editor.activeTransformCompletion() != null).toString(2))
        automation.takeScreenshot()?.let { bitmap ->
            try { evidence.resolveSibling(evidence.name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        instrumentation.runOnMainSync { canvas.cancelMove(); activity.finish() }
        instrumentation.waitForIdleSync()
        workspace.closeAsync().get(15, TimeUnit.SECONDS)
    }
}
