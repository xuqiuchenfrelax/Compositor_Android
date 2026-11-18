package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Compose accessibility actions and canvas pointer events, followed by checkpoint reopen. */
internal fun structureUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val previousLanguage = AppLanguages.current(context)
    val token = "p143-structure-${System.nanoTime()}"
    val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
    var active: MainActivity? = null
    try {
        instrumentation.runOnMainSync { AppLanguages.set(context, AppLanguage.ENGLISH) }
        NativeEditor(256, addDefaultLayers = true).use { seed ->
            seed.save(File(directory, "current.checkpoint"))
        }
        return structureUiSmokeInWorkspace(instrumentation, token) {
            active = it
            check(it.retainedWorkspaceForTest().recoveryDirectoryForTest().canonicalFile == directory.canonicalFile)
        }
    } finally {
        var workspaceClosed = active == null
        try {
            active?.let { activity ->
                val workspace = activity.retainedWorkspaceForTest()
                try { instrumentation.runOnMainSync { activity.finish() } }
                finally {
                    workspace.closeAsync().get(15, TimeUnit.SECONDS)
                    workspaceClosed = true
                }
            }
        } finally {
            try { if (workspaceClosed) check(!directory.exists() || directory.deleteRecursively()) }
            finally { instrumentation.runOnMainSync { AppLanguages.set(context, previousLanguage) } }
        }
    }
}

private fun structureUiSmokeInWorkspace(instrumentation: Instrumentation, token: String,
    activityCreated: (MainActivity) -> Unit): String {
    var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
    activityCreated(activity)
    val originalOrientation = activity.requestedOrientation
    val timeout = TimeUnit.SECONDS.toNanos(10)
    val strokeObservations = JSONArray()
    val strokeEvidence = File(instrumentation.targetContext.cacheDir, "$token-strokes.json")
    fun freshRoot(): AccessibilityNodeInfo? {
        val automation = editorUiAutomation(instrumentation)
        automation.clearCache()
        return automation.rootInActiveWindow
    }
    fun failureEvidence(message: String) {
        val evidence = StringBuilder(message).append("\nworkspace=").append(token).append('\n')
        fun dump(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null) return
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            evidence.append("  ".repeat(depth)).append("text=").append(node.text)
                .append(" description=").append(node.contentDescription)
                .append(" enabled=").append(node.isEnabled).append(" clickable=").append(node.isClickable)
                .append(" visible=").append(node.isVisibleToUser).append(" bounds=").append(bounds)
                .append(" actions=").append(node.actionList.map { it.id }).append('\n')
            for (index in 0 until node.childCount) dump(node.getChild(index), depth + 1)
        }
        dump(freshRoot())
        instrumentation.runOnMainSync {
            val state = activity.retainedWorkspaceForTest().snapshot()
            evidence.append("owner loading=").append(state.loading).append(" frozen=").append(state.resourceGrowthFrozen)
                .append(" generation=").append(state.generation).append(" revision=").append(state.currentRevision)
                .append(" editor=").append(System.identityHashCode(state.editor))
                .append(" projectBusy=").append(activity.retainedProjectFlowForTest().state.busy).append('\n')
            fun canvases(view: View) {
                if (view is CanvasView) evidence.append("canvas ").append(view.width).append('x').append(view.height)
                    .append(" attached=").append(view.isAttachedToWindow).append(" surface=").append(view.holder.surface.isValid)
                    .append(" stroke=").append(view.strokeInProgress).append(" tool=").append(view.toolMode)
                    .append(" layer=").append(view.layerId).append(" mask=").append(view.editingMask)
                    .append(" editing=").append(view.editingInProgress).append(" canChangeTool=").append(view.canChangeTool)
                    .append(" settings=").append(view.strokeSettings).append('\n')
                if (view is ViewGroup) for (index in 0 until view.childCount) canvases(view.getChildAt(index))
            }
            canvases(activity.window.decorView)
        }
        val name = "$token-failure-${System.nanoTime()}"
        File(instrumentation.targetContext.cacheDir, "$name.txt").writeText(evidence.toString())
        strokeEvidence.writeText(JSONObject().put("token", token).put("observations", strokeObservations)
            .put("failure", message).toString(2))
        editorUiAutomation(instrumentation).takeScreenshot()?.let { screenshot ->
            try { File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { screenshot.recycle() }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "Structure failure evidence: cache/$name.txt, cache/$name.png, cache/${strokeEvidence.name}")
        })
    }
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeout
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        val passed = predicate()
        if (!passed) runCatching { failureEvidence(message) }
        check(passed) { message }
    }
    waitUntil("UI did not open recovered editor") { activity.retainedEditorForTest() != null }
    val editor = checkNotNull(activity.retainedEditorForTest())
    val original = editor.state().layers
    val scrollTrace = File(instrumentation.targetContext.cacheDir, "structure-scroll-actions.txt").apply { writeText("") }
    fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), label)?.let { return it }
        return null
    }
    fun node(label: String): AccessibilityNodeInfo {
        revealEditorControl(instrumentation, label)
        fun layerList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            fun hasLayer(node: AccessibilityNodeInfo): Boolean {
                if (node.contentDescription?.toString()?.startsWith("Select ") == true) return true
                return (0 until node.childCount).any { node.getChild(it)?.let(::hasLayer) == true }
            }
            if (root.isScrollable && hasLayer(root)) {
                val bounds = Rect(); root.getBoundsInScreen(bounds)
                check(bounds.width() > 0 && bounds.height() > 0) { "Layer list has no visible scroll area" }
                return root
            }
            for (index in 0 until root.childCount) layerList(root.getChild(index))?.let { return it }
            return null
        }
        fun visibleLayers(root: AccessibilityNodeInfo?): String {
            if (root == null) return ""
            val bounds = Rect(); root.getBoundsInScreen(bounds)
            val label = root.contentDescription?.toString().orEmpty()
            return (if (label.startsWith("Select ")) "$label@$bounds;" else "") +
                (0 until root.childCount).joinToString("") { visibleLayers(root.getChild(it)) }
        }
        // Compose excludes offscreen layer semantics. Exercise the list's real scroll actions.
        if (label.startsWith("Select ")) {
            waitUntil("Layer list not ready for: $label") {
                val root = freshRoot()
                findNode(root, label) != null || layerList(root) != null
            }
            for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                for (attempt in 0 until 32) {
                    val root = freshRoot()
                    findNode(root, label)?.let { return it }
                    val list = layerList(root) ?: break
                    if (list.actionList.none { it.id == action }) break
                    val before = visibleLayers(list)
                    val bounds = Rect(); list.getBoundsInScreen(bounds)
                    val started = SystemClock.uptimeMillis()
                    val accepted = list.performAction(action)
                    scrollTrace.appendText("label=$label bounds=$bounds action=$action accepted=$accepted before=$before\n")
                    check(accepted) { "Layer list rejected advertised scroll action $action for $label" }
                    waitUntil("Layer list scroll did not move for: $label") {
                        val current = freshRoot()
                        findNode(current, label) != null || visibleLayers(layerList(current)) != before
                    }
                    instrumentation.waitForIdleSync()
                    editorUiAutomation(instrumentation).waitForIdle(500, 5_000)
                    scrollTrace.appendText("stableAfterMs=${SystemClock.uptimeMillis() - started} after=${visibleLayers(layerList(freshRoot()))}\n")
                }
            }
        }
        var found: AccessibilityNodeInfo? = null
        waitUntil("UI control missing: $label") { found = findNode(editorUiAutomation(instrumentation).rootInActiveWindow, label); found != null }
        return checkNotNull(found)
    }
    fun click(label: String) {
        try { revealEditorControl(instrumentation, label) }
        catch (failure: Exception) {
            runCatching { failureEvidence("Navigation to $label: ${failure.message}") }
            throw failure
        }
        node(label).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync()
        var clicked = false
        waitUntil("UI control not clickable: $label") {
            if (!clicked) {
                editorUiAutomation(instrumentation).clearCache()
                val targets = mutableListOf<AccessibilityNodeInfo>()
                fun collect(root: AccessibilityNodeInfo?) {
                    if (root == null) return
                    if (root.text?.toString() == label || root.contentDescription?.toString() == label) {
                        var owner: AccessibilityNodeInfo? = root
                        while (owner != null && !owner.isClickable) owner = owner.parent
                        owner?.takeIf { it.isVisibleToUser && it.isEnabled && it.actionList.any {
                            action -> action.id == AccessibilityNodeInfo.ACTION_CLICK
                        } }?.let { if (it !in targets) targets += it }
                    }
                    for (index in 0 until root.childCount) collect(root.getChild(index))
                }
                // Tool headers can repeat a button label as a non-interactive title.
                // Select the unique real action owner instead of stopping at that title.
                collect(editorPhysicalDisplayRoot(instrumentation))
                check(targets.size <= 1) { "Multiple actionable UI controls match: $label" }
                clicked = targets.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    fun options(action: String) {
        click("Layer options")
        val automation = editorUiAutomation(instrumentation)
        fun root(): AccessibilityNodeInfo? {
            automation.clearCache()
            return editorPhysicalDisplayRoot(instrumentation)
        }
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (index in 0 until node.childCount) scrollable(node.getChild(index))?.let { return it }
            return null
        }
        fun fingerprint(node: AccessibilityNodeInfo?): String {
            if (node == null) return ""
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            val own = if (node.isVisibleToUser && text != null)
                "$text:${Rect().also(node::getBoundsInScreen).toShortString()};" else ""
            return own + (0 until node.childCount).joinToString("") { fingerprint(node.getChild(it)) }
        }
        fun visible(node: AccessibilityNodeInfo?): Boolean = findNode(node, action)?.isVisibleToUser == true
        fun revealAction() {
            waitUntil("Layer options dialog did not open for: $action") { findNode(root(), "Done") != null }
            if (visible(root())) return
            for (scrollAction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                for (attempt in 0 until 16) {
                    val current = root()
                    if (visible(current)) return
                    val list = scrollable(current) ?: break
                    if (list.actionList.none { it.id == scrollAction }) break
                    val before = fingerprint(list)
                    val accepted = list.performAction(scrollAction)
                    scrollTrace.appendText("dialog=$action action=$scrollAction accepted=$accepted before=$before\n")
                    check(accepted) { "Layer options rejected its advertised scroll action for $action" }
                    waitUntil("Layer options did not scroll to: $action") {
                        val next = root()
                        visible(next) || fingerprint(scrollable(next)) != before ||
                            scrollable(next)?.actionList?.none { it.id == scrollAction } == true
                    }
                    instrumentation.waitForIdleSync()
                    automation.waitForIdle(100, 5_000)
                }
            }
            waitUntil("Layer options action missing after bounded scrolling: $action") { visible(root()) }
        }
        revealAction()
        click(action)
    }
    fun rename(value: String) {
        options("Rename")
        node("Layer name")
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return node
            for (index in 0 until node.childCount) editable(node.getChild(index))?.let { return it }
            return null
        }
        var field: AccessibilityNodeInfo? = null
        waitUntil("Rename did not expose an editable text field") {
            field = editable(editorUiAutomation(instrumentation).rootInActiveWindow); field != null
        }
        field?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val argument = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        check(field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argument) == true) { "Rename field rejected text" }
        click("Save name")
        waitUntil("Rename did not commit") { editor.state().layers.any { it.name == value } }
    }
    fun select(value: String) { click("Select $value") }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun stroke(stage: String, expectedLayer: Long, mask: Boolean = false, erase: Boolean = false, white: Boolean = false) {
        var canvas: CanvasView? = null
        instrumentation.runOnMainSync { canvas = findCanvas(activity.window.decorView) }
        val view = checkNotNull(canvas)
        fun observe(action: String, event: MotionEvent? = null) {
            val state = editor.state()
            val detail = JSONObject().put("stage", stage).put("action", action)
                .put("revision", state.revision).put("selection", editor.selection().toString())
                .put("nativePending", editor.hasPendingEdits).put("nativeActiveStrokes", editor.hasActiveStrokes)
                .put("eventTime", event?.eventTime ?: JSONObject.NULL)
                .put("screenX", event?.rawX ?: JSONObject.NULL).put("screenY", event?.rawY ?: JSONObject.NULL)
            instrumentation.runOnMainSync {
                detail.put("layer", view.layerId).put("mask", view.editingMask).put("tool", view.toolMode.name)
                    .put("erasing", view.erasing).put("settings", view.strokeSettings.toString())
                    .put("strokeInProgress", view.strokeInProgress).put("editingInProgress", view.editingInProgress)
                    .put("canChangeTool", view.canChangeTool).put("windowFocus", view.hasWindowFocus())
                    .put("bounds", Rect().also(view::getGlobalVisibleRect).toShortString()).put("zoom", view.zoomPercent)
            }
            state.layers.firstOrNull { it.id == expectedLayer }?.takeIf { it.hasMask }?.let { layer ->
                val buffer = ByteBuffer.allocateDirect(layer.maskWidth * layer.maskHeight)
                editor.maskPixels(layer.id, layer.maskWidth, buffer)
                val bytes = ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
                detail.put("maskSHA256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    .put("maskNonzero", bytes.count { it != 0.toByte() })
                    .put("maskNonwhite", bytes.count { it != (-1).toByte() })
            }
            strokeObservations.put(detail)
            strokeEvidence.writeText(JSONObject().put("token", token).put("observations", strokeObservations).toString(2))
        }
        observe("before-readiness")
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        val layoutReady = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        var frames = 0
        var previous = Rect()
        var bounds: Rect? = null
        val frame = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val selected = editor.selection()
                val currentBounds = Rect()
                val ready = selected.primary == expectedLayer && selected.mask == mask &&
                    view.layerId == expectedLayer && view.editingMask == mask && view.toolMode == CanvasToolMode.BRUSH &&
                    view.erasing == erase && view.strokeSettings.maskPaintWhite == white && view.samplingEnabled &&
                    !view.editingInProgress && view.canChangeTool && !editor.hasPendingEdits && !editor.hasActiveStrokes &&
                    view.hasWindowFocus() && view.getGlobalVisibleRect(currentBounds) && !view.isLayoutRequested &&
                    view.holder.surface.isValid && view.holder.surfaceFrame.width() == view.width &&
                    view.holder.surfaceFrame.height() == view.height
                frames = if (ready && currentBounds == previous) frames + 1 else 0
                previous = currentBounds
                if (frames >= 2) { bounds = currentBounds; layoutReady.countDown() } else view.postOnAnimation(this)
            }
        }
        instrumentation.runOnMainSync { view.postOnAnimation(frame) }
        try { check(layoutReady.await(10, TimeUnit.SECONDS)) { "Stroke target/layout did not bind: $stage" } }
        finally { stopped.set(true); instrumentation.runOnMainSync { view.removeCallbacks(frame) } }
        val readyBounds = checkNotNull(bounds)
        observe("ready-before-DOWN")
        val beforeRevision = editor.currentRevision()
        val x = readyBounds.exactCenterX(); val y = readyBounds.exactCenterY()
        val start = SystemClock.uptimeMillis()
        for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 20L to MotionEvent.ACTION_MOVE, 40L to MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, start + offset, action, x + offset / 4, y, 0)
            try { instrumentation.sendPointerSync(event); observe(MotionEvent.actionToString(action), event) }
            finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
        val latch = CountDownLatch(1)
        view.queueEvent { latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS))
        waitUntil("Stroke did not settle: $stage") {
            !view.strokeInProgress && !editor.hasActiveStrokes && !editor.hasPendingEdits
        }
        observe("after-settlement")
        check(editor.currentRevision() == beforeRevision + 1) { "One real stroke did not publish exactly once: $stage" }
    }
    fun maskBytes(layer: LayerState): ByteArray {
        val bytes = ByteBuffer.allocateDirect(layer.maskWidth * layer.maskHeight)
        editor.maskPixels(layer.id, layer.maskWidth, bytes)
        return ByteArray(bytes.capacity()).also { bytes.rewind(); bytes.get(it) }
    }
    val checkpoint = File(instrumentation.targetContext.cacheDir, "structure-ui-${System.nanoTime()}.checkpoint")
    try {
        click("Group")
        waitUntil("Group button did not create group") { editor.state().layers.size == original.size + 1 }
        rename("Folder 🎨")
        val group = editor.state().layers.last()
        check(group.isGroup)
        options("White mask")
        waitUntil("Group white mask was not created") { editor.state().layers.first { it.id == group.id }.hasMask }

        click("Add layer")
        waitUntil("First child did not appear") { editor.state().layers.size == original.size + 2 }
        rename("Paint A")
        options("Move into group"); click("Folder 🎨")
        val firstId = editor.state().layers.last().id
        waitUntil("First child parent was not assigned") { editor.state().layers.first { it.id == firstId }.parentId == group.id }
        val paintRevision = editor.currentRevision()
        click("Brush (B)")
        stroke("pixel-brush", firstId)
        check(editor.currentRevision() > paintRevision) { "Real canvas pixel brush did not commit" }

        click("Add layer")
        waitUntil("Second child did not appear") { editor.state().layers.size == original.size + 3 }
        rename("Paint B 🖌")
        options("Move into group"); click("Folder 🎨")
        val secondId = editor.state().layers.last().id
        waitUntil("Second child parent was not assigned") { editor.state().layers.first { it.id == secondId }.parentId == group.id }
        options("Black mask")
        waitUntil("Black mask did not appear") { editor.state().layers.first { it.id == secondId }.hasMask }
        val black = maskBytes(editor.state().layers.first { it.id == secondId })
        check(black.all { it == 0.toByte() })
        options("Remove mask")
        waitUntil("Remove mask did not commit") { !editor.state().layers.first { it.id == secondId }.hasMask }
        options("White mask")
        waitUntil("White mask did not appear") { editor.state().layers.first { it.id == secondId }.hasMask }
        options("Edit mask")
        val before = maskBytes(editor.state().layers.first { it.id == secondId })
        stroke("mask-hide-brush", secondId, mask = true)
        val after = maskBytes(editor.state().layers.first { it.id == secondId })
        check(!after.contentEquals(before)) { "Real canvas mask brush did not change coverage" }
        click("Undo")
        waitUntil("One mask stroke undo did not restore coverage") { maskBytes(editor.state().layers.first { it.id == secondId }).contentEquals(before) }
        click("Redo")
        waitUntil("Mask redo did not restore coverage") { maskBytes(editor.state().layers.first { it.id == secondId }).contentEquals(after) }
        click("White · Reveal"); click("Eraser")
        stroke("mask-reveal-eraser", secondId, mask = true, erase = true, white = true)
        check(!maskBytes(editor.state().layers.first { it.id == secondId }).contentEquals(after)) { "Independent white mask target did not reveal pixels in Eraser mode" }
        click("Brush"); click("Black · Hide")
        options("Enable mask")
        waitUntil("Mask disable did not commit") { !editor.state().layers.first { it.id == secondId }.maskEnabled }
        options("Enable mask")
        waitUntil("Mask enable did not commit") { editor.state().layers.first { it.id == secondId }.maskEnabled }
        options(instrumentation.targetContext.getString(R.string.ui_create_clipping_mask))
        waitUntil("Sibling clip was not assigned") { editor.state().layers.first { it.id == secondId }.maskSourceId == firstId }
        options(instrumentation.targetContext.getString(R.string.ui_release_clipping_mask))
        waitUntil("Clip release did not commit") { editor.state().layers.first { it.id == secondId }.maskSourceId == 0L }
        options(instrumentation.targetContext.getString(R.string.ui_create_clipping_mask))
        waitUntil("Clip restore did not commit") { editor.state().layers.first { it.id == secondId }.maskSourceId == firstId }
        options("Move down")
        waitUntil("Move down did not change ordering") { editor.state().layers.indexOfFirst { it.id == secondId } < editor.state().layers.indexOfFirst { it.id == firstId } }
        options("Move up")
        waitUntil("Move up did not restore ordering") { editor.state().layers.indexOfFirst { it.id == secondId } > editor.state().layers.indexOfFirst { it.id == firstId } }

        click("Add layer")
        waitUntil("Third clipping layer did not appear") { editor.state().layers.size == original.size + 4 }
        rename("Paint C")
        options("Move into group"); click("Folder 🎨")
        val thirdId = editor.state().layers.last().id
        waitUntil("Third child parent was not assigned") { editor.state().layers.first { it.id == thirdId }.parentId == group.id }
        options(instrumentation.targetContext.getString(R.string.ui_create_clipping_mask))
        waitUntil("Stack clipping did not choose shared base") { editor.state().layers.first { it.id == thirdId }.maskSourceId == firstId }
        check(editor.state().layers.first { it.id == thirdId }.maskSourceId != secondId) { "UI clipping created a recursive chain" }
        select("Paint B 🖌")

        val saved = editor.state()
        val expectedMask = maskBytes(saved.layers.first { it.id == secondId })
        editor.save(checkpoint)
        NativeEditor.load(checkpoint).use { restored ->
            check(restored.state().layers == saved.layers) { "Close/reopen lost names or group/mask/clip relationships" }
            val bytes = ByteBuffer.allocateDirect(expectedMask.size)
            restored.maskPixels(secondId, saved.layers.first { it.id == secondId }.maskWidth, bytes)
            val actual = ByteArray(bytes.capacity()).also { bytes.rewind(); bytes.get(it) }
            check(actual.contentEquals(expectedMask)) { "Close/reopen lost mask brush pixels" }
            check(!restored.state().undo && !restored.state().redo)
        }
        fun screenshot(name: String) {
            editorUiAutomation(instrumentation).waitForIdle(500, 5_000)
            // Activity idle does not wait for WindowManager's display rotation animation.
            Thread.sleep(500)
            val image = checkNotNull(editorUiAutomation(instrumentation).takeScreenshot())
            try {
                File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
        }
        screenshot("structure-phone.png")
        instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitUntil("Landscape fixture did not exercise wide layout") { activity.resources.configuration.screenWidthDp >= 700 }
        // Production handles size changes directly; also exercise explicit recreation in that size.
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity.recreate() }
        val wide = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
        instrumentation.removeMonitor(monitor)
        activity = checkNotNull(wide) { "Landscape UI did not recreate" }
        activityCreated(activity)
        instrumentation.waitForIdleSync()
        check(activity.resources.configuration.screenWidthDp >= 700) { "Landscape fixture did not exercise wide layout" }
        check(activity.retainedEditorForTest() === editor) { "Landscape recreation lost native document" }
        waitUntil("Wide editor window did not gain focus") { activity.window.decorView.hasWindowFocus() }
        editorUiAutomation(instrumentation).waitForIdle(500, 5_000)
        select("Paint B 🖌")
        screenshot("structure-wide.png")
        options("Move to root")
        waitUntil("Root reparent did not commit") { editor.state().layers.first { it.id == secondId }.parentId == 0L }
        select("Folder 🎨")
        options("Delete"); click("Delete layer")
        waitUntil("Group delete did not delete descendants") { editor.state().layers.none { it.id == group.id || it.id == firstId || it.id == thirdId } }
        check(editor.state().layers.first { it.id == secondId }.maskSourceId == 0L) { "Deleted clip source left dangling relationship" }
        click("Undo")
        waitUntil("Group-delete undo lost subtree") { editor.state().layers.any { it.id == group.id } && editor.state().layers.any { it.id == firstId } }
        return "Structure UI passed: actual phone+wide group/rename/reparent/up-down/white-black-mask/enable/brush-eraser/one-stroke-undo/clip-release/delete; checkpoint reopen exact names/relationships/mask pixels"
    } catch (error: Throwable) {
        runCatching { failureEvidence(error.stackTraceToString()) }
        throw error
    } finally {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Structure stroke evidence: cache/${strokeEvidence.name}") })
        checkpoint.delete()
        instrumentation.runOnMainSync { activity.requestedOrientation = originalOrientation }
    }
}
