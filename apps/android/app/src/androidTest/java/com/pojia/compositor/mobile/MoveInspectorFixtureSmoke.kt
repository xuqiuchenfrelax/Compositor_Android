package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** New Inspector selectors share public UI actions and exact native observations, never edit entry points. */
internal class MoveInspectorFixtureSmoke(val instrumentation: Instrumentation, val label: String) : AutoCloseable {
    private val automation = editorUiAutomation(instrumentation)
    var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        private set
    val workspace: AndroidWorkspace
    lateinit var editor: NativeEditor
        private set
    lateinit var canvas: CanvasView
        private set
    private val stages = JSONArray()
    private val evidence = File(instrumentation.targetContext.filesDir, "p37-$label-${System.nanoTime()}.json")
    var successful = false

    init {
        waitUntil("Inspector host did not open") {
            activity.retainedEditorForTest() != null && findCanvas() != null &&
                !activity.retainedWorkspaceForTest().snapshot().loading
        }
        workspace = activity.retainedWorkspaceForTest()
    }

    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }

    private fun findCanvas(): CanvasView? {
        fun find(view: View): CanvasView? {
            if (view is CanvasView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var result: CanvasView? = null
        instrumentation.runOnMainSync { result = find(activity.window.decorView) }
        return result
    }

    fun node(root: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isVisibleToUser && (root.text?.toString() == name || root.contentDescription?.toString() == name)) return root
        for (index in 0 until root.childCount) node(root.getChild(index), name)?.let { return it }
        return null
    }

    private val numericLabels = setOf("Move X value", "Move Y value", "Move Width value", "Move Height value",
        "Move Rotation value", "Move Scale value", "Flip Horizontal", "Flip Vertical")

    private fun reveal(name: String) {
        revealEditorControl(instrumentation, name)
        var direction = 0
        var pending: String? = null
        val actions = listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        fun numericScroll(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isScrollable && numericLabels.any { node(root, it) != null }) return root
            for (index in 0 until root.childCount) numericScroll(root.getChild(index))?.let { return it }
            return null
        }
        fun fingerprint(root: AccessibilityNodeInfo): String = numericLabels.mapNotNull { label ->
            node(root, label)?.let { field ->
                val bounds = Rect().also(field::getBoundsInScreen)
                "$label:${bounds.toShortString()}:${field.text}"
            }
        }.joinToString(";")
        waitUntil("Inspector control unavailable: $name") {
            automation.clearCache()
            val root = editorPhysicalDisplayRoot(instrumentation)
            if (node(root, name) != null) true else {
                val scroll = if (name in numericLabels) numericScroll(root) else null
                if (scroll != null && direction < actions.size) {
                    val current = fingerprint(scroll)
                    if (pending == null || pending != current) {
                        val action = actions[direction]
                        if (scroll.actionList.none { it.id == action }) { direction++; pending = null } else {
                            check(scroll.performAction(action)) { "Inspector parameter row rejected advertised scroll action" }
                            pending = current
                        }
                    }
                }
                false
            }
        }
    }

    fun click(name: String) {
        reveal(name)
        var actionable: AccessibilityNodeInfo? = null
        waitUntil("Inspector control disabled: $name") {
            automation.clearCache()
            var current = node(editorPhysicalDisplayRoot(instrumentation), name)
            while (current != null && !current.isClickable) current = current.parent
            actionable = current?.takeIf { it.isVisibleToUser && it.isEnabled }
            actionable != null
        }
        check(checkNotNull(actionable).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Inspector click rejected: $name" }
        instrumentation.waitForIdleSync()
    }

    private fun editable(name: String): AccessibilityNodeInfo? {
        automation.clearCache()
        var field = node(editorPhysicalDisplayRoot(instrumentation), name)
        while (field != null && field.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) field = field.parent
        return field?.takeIf { it.isEditable && it.isVisibleToUser && it.isEnabled && it.isFocused }
    }

    fun focusScale(): String {
        click("Move Scale value")
        var field: AccessibilityNodeInfo? = null
        waitUntil("Scale field did not focus") { field = editable("Move Scale value"); field != null }
        return checkNotNull(field).text.toString()
    }

    fun scaleText(text: String, focus: Boolean = true) {
        if (focus) focusScale()
        val field = checkNotNull(editable("Move Scale value"))
        check(field.text?.toString() != text) { "Fixture requires an actual text change, not identical SET_TEXT" }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })) { "Scale field rejected public text action" }
        instrumentation.waitForIdleSync()
    }

    fun key(code: Int, modifiers: Int = 0) {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, modifiers))
        instrumentation.sendKeySync(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0, modifiers))
        instrumentation.waitForIdleSync()
    }

    fun canvasFocused() = waitUntil("Inspector completion did not focus Canvas") {
        automation.clearCache(); node(editorPhysicalDisplayRoot(instrumentation), "Canvas")?.isFocused == true
    }

    fun bodyDrag(box: Transform64, dx: Double, dy: Double) {
        val initialBounds = stableBounds()
        val location = IntArray(2)
        var overlay: MoveHandleOverlay? = null
        instrumentation.runOnMainSync { canvas.getLocationOnScreen(location); overlay = canvas.moveOverlay(box) }
        val controls = checkNotNull(overlay)
        val scale = canvas.zoomPercent / 100
        check(box.width * scale / 2 > 16 * activity.resources.displayMetrics.density &&
            box.height * scale / 2 > 16 * activity.resources.displayMetrics.density) { "Inspector handoff fixture center overlaps handles" }
        val x = location[0] + ((controls.handles[0].x + controls.handles[4].x) / 2).toFloat()
        val y = location[1] + ((controls.handles[0].y + controls.handles[4].y) / 2).toFloat()
        val down = SystemClock.uptimeMillis()
        fun record(action: String, screenX: Float, screenY: Float, source: Int? = null, toolType: Int? = null) {
            val details = JSONObject().put("action", action).put("screenX", screenX.toDouble()).put("screenY", screenY.toDouble())
                .put("initialBounds", initialBounds.toShortString()).put("initialScale", scale)
                .put("initialLocationX", location[0]).put("initialLocationY", location[1])
                .put("requestedBox", box.toString()).put("requestedDeltaX", dx).put("requestedDeltaY", dy)
                .put("source", source ?: JSONObject.NULL).put("toolType", toolType ?: JSONObject.NULL)
            instrumentation.runOnMainSync {
                val currentLocation = IntArray(2).also(canvas::getLocationOnScreen)
                val bounds = Rect().also { canvas.getGlobalVisibleRect(it) }
                val currentControls = canvas.moveOverlay(box)
                details.put("bounds", bounds.toShortString()).put("locationX", currentLocation[0]).put("locationY", currentLocation[1])
                    .put("width", canvas.width).put("height", canvas.height).put("layoutRequested", canvas.isLayoutRequested)
                    .put("scale", canvas.zoomPercent / 100).put("overlayFirst", currentControls.handles[0].toString())
                    .put("overlayOpposite", currentControls.handles[4].toString())
                    .put("moveBox", canvas.moveBox?.toString() ?: JSONObject.NULL)
                    .put("moveInProgress", canvas.moveInProgress).put("moveFromFields", canvas.moveFromFields)
                    .put("moveSettling", canvas.moveSettling).put("moveContextPending", canvas.moveContextPending)
                    .put("acceptedSequence", canvas.moveAcceptedSequence)
            }
            val state = editor.state()
            details.put("revision", state.revision).put("committedTransform", state.layers.single().transform.toString())
                .put("activeDraft", editor.hasActiveTransformDraft)
                .put("completionDone", editor.activeTransformCompletion()?.isDone ?: JSONObject.NULL)
            observation("body-drag-input", details)
        }
        record("before-DOWN", x, y)
        fun pointer(action: Int, deltaX: Double, deltaY: Double, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action,
                x + (deltaX * scale).toFloat(), y + (deltaY * scale).toFloat(), KeyEvent.META_CTRL_ON)
            try {
                instrumentation.sendPointerSync(event)
                instrumentation.waitForIdleSync()
                record(MotionEvent.actionToString(action), event.x, event.y, event.source, event.getToolType(0))
            } finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, 0.0, 0.0, 0)
        instrumentation.runOnMainSync {
            val afterDownLocation = IntArray(2).also(canvas::getLocationOnScreen)
            val heightChanged = canvas.height != initialBounds.height()
            observation("body-drag-height-handoff", JSONObject().put("heightChanged", heightChanged)
                .put("beforeHeight", initialBounds.height()).put("afterHeight", canvas.height)
                .put("gestureOwned", canvas.moveInProgress || canvas.moveContextPending)
                .put("originUnchanged", afterDownLocation.contentEquals(location)))
            if (heightChanged && canvas.width == initialBounds.width() && afterDownLocation.contentEquals(location)) {
                check(canvas.zoomPercent / 100 == scale && canvas.moveOverlay(box) == controls &&
                    (canvas.moveInProgress || canvas.moveContextPending)) {
                    "IME height change moved the Inspector drag's coordinate mapping or lost its owner"
                }
            }
        }
        pointer(MotionEvent.ACTION_MOVE, dx, dy, 30)
        // Actual UP ends the accepted MOVE; its different coordinates must never create another sample.
        pointer(MotionEvent.ACTION_UP, dx + 15, dy + 15, 60)
    }

    fun install(width: Int = 40, height: Int = 20,
        box: Transform64 = Transform64(10.0, 20.0, 80.0, 60.0, 37.0, true, false)) {
        val previous = findCanvas()
        check(workspace.installCandidate("p37-$label-${System.nanoTime()}", DirtyDisposition.DISCARD) {
            NativeEditor(256, false).also { candidate ->
                val id = candidate.importPixels(width, height, ByteBuffer.allocateDirect(width * height * 4).apply {
                    put(moveInspectorColorPixels(width, height)); rewind()
                })
                candidate.setTransform(id, box); candidate.selectLayer(id)
                candidate.renameLayer(id, "Inspector redo witness"); candidate.undo(false)
            }
        }.result.get(15, TimeUnit.SECONDS).installed)
        editor = checkNotNull(workspace.snapshot().editor)
        waitUntil("Inspector fixture canvas did not bind") {
            val next = findCanvas()
            if (next != null && next !== previous && next.isAttachedToWindow && next.holder.surface.isValid &&
                next.width > 0 && next.height > 0 && activity.retainedEditorForTest() === editor) canvas = next
            ::canvas.isInitialized && canvas !== previous && canvas.layerId == editor.state().layers.single().id
        }
        check(editor.state().redo)
        click("Move / Transform (V)")
        waitUntil("Inspector fixture did not enter Move") { canvas.toolMode == CanvasToolMode.MOVE }
        stableBounds()
        stage("installed", box)
    }

    /** Installs through the real retained workspace and waits for its attached GLES canvas. */
    fun installAuthority(candidate: () -> NativeEditor) {
        val previous = findCanvas()
        val operation = workspace.installCandidate("$label-${System.nanoTime()}", DirtyDisposition.DISCARD,
            candidate = candidate)
        val result = operation.result.get(15, TimeUnit.SECONDS)
        val after = workspace.snapshot()
        val afterPending = runCatching { operation.token.editor.pendingEditCount }.getOrNull()
        val afterOwner = runCatching { operation.token.editor.activeTransformCompletion() }.getOrNull()
        val afterDone = afterOwner?.isDone
        observation("authority-install-result", JSONObject()
            .put("capturedGeneration", operation.token.generation)
            .put("capturedRevision", operation.token.revision)
            .put("capturedCommandEpoch", operation.token.commandEpoch)
            .put("preInstallLockReadsOmitted", true)
            .put("installed", result.installed)
            .put("resultGeneration", result.generation)
            .put("durabilityUncertain", result.durabilityUncertain)
            .put("reason", result.reason ?: JSONObject.NULL)
            .put("afterGeneration", after.generation)
            .put("afterRevision", after.currentRevision)
            .put("afterPendingEdits", afterPending ?: JSONObject.NULL)
            .put("afterActiveCompletion", afterOwner != null)
            .put("afterCompletionDone", afterDone ?: JSONObject.NULL))
        check(result.installed) { "Authority fixture install rejected: ${result.reason}" }
        editor = checkNotNull(workspace.snapshot().editor)
        waitUntil("Authority fixture canvas did not bind") {
            val next = findCanvas()
            if (next != null && next !== previous && next.isAttachedToWindow && next.holder.surface.isValid &&
                next.width > 0 && next.height > 0 && activity.retainedEditorForTest() === editor) canvas = next
            ::canvas.isInitialized && canvas !== previous && canvas.layerId == editor.selection().primary
        }
        click("Move / Transform (V)")
        waitUntil("Authority fixture did not enter Move") { canvas.toolMode == CanvasToolMode.MOVE }
        stableBounds()
    }

    fun settled() = waitUntil("Inspector terminal did not acknowledge") {
        !canvas.moveInProgress && !canvas.movePersistent && editor.activeTransformCompletion() == null && !editor.hasActiveTransformDraft
    }

    fun preview(expected: Transform64) = waitUntil("Inspector local/native preview differs from $expected") {
        canvas.moveBox == expected && editor.serialized {
            editor.hasActiveTransformDraft && editor.captureDisplayScene()?.use {
                val layer = it.layerMetadata(0)
                layer.id == editor.state().layers.single().id && layer.transform == expected
            } == true
        }
    }

    fun pixels(): ByteArray {
        val buffer = ByteBuffer.allocateDirect(256 * 256 * 4)
        checkNotNull(editor.captureDisplayScene()).use { it.draw(256, 256, Viewport(0.0, 0.0, 256.0, 256.0), buffer) }
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }

    fun source(): Pair<List<Long>, String> = checkNotNull(editor.captureDisplayScene()).use { scene ->
        val layer = scene.layerMetadata(0)
        check(layer.id == editor.state().layers.single().id && layer.tileCount == 1)
        val buffer = ByteBuffer.allocateDirect(layer.pixelWidth * layer.pixelHeight * 4)
        scene.readTile(0, 0, layer.pixelWidth * 4, buffer)
        val bytes = ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
        scene.tileViewsMetadata(0).map { it.payloadResourceId } to moveInspectorSha(bytes)
    }

    fun stableBounds(): Rect {
        val latch = CountDownLatch(1); val stopped = AtomicBoolean(false)
        var previous: Rect? = null; var result: Rect? = null; var frames = 0
        val frame = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val bounds = Rect()
                val ready = canvas.getGlobalVisibleRect(bounds) && canvas.isLaidOut && !canvas.isLayoutRequested &&
                    canvas.holder.surface.isValid && canvas.holder.surfaceFrame.width() == canvas.width &&
                    canvas.holder.surfaceFrame.height() == canvas.height
                frames = if (ready && bounds == previous) frames + 1 else 0
                previous = bounds
                if (frames >= 2) { result = bounds; latch.countDown() } else canvas.postOnAnimation(this)
            }
        }
        instrumentation.runOnMainSync { canvas.postOnAnimation(frame) }
        try { check(latch.await(15, TimeUnit.SECONDS)) { "Inspector canvas layout did not stabilize" } }
        finally { stopped.set(true); instrumentation.runOnMainSync { canvas.removeCallbacks(frame) } }
        return checkNotNull(result)
    }

    fun recreate(expected: Transform64, owner: CompletableFuture<TransformController.Terminal>) {
        preview(expected)
        val before = editor.state(); val beforePixels = pixels(); val sourceBefore = source()
        val oldActivity = activity; val oldCanvas = canvas
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { oldActivity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        check(activity !== oldActivity && activity.retainedWorkspaceForTest() === workspace && activity.retainedEditorForTest() === editor)
        waitUntil("Inspector recreation did not bind new canvas") {
            val next = findCanvas()
            if (next != null && next !== oldCanvas && next.isAttachedToWindow && next.holder.surface.isValid) canvas = next
            canvas !== oldCanvas && canvas.toolMode == CanvasToolMode.MOVE && canvas.moveBox == expected
        }
        check(!oldCanvas.isAttachedToWindow)
        preview(expected); stableBounds()
        check(editor.activeTransformCompletion() === owner && !owner.isDone && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) &&
            pixels().contentEquals(beforePixels) && source() == sourceBefore)
        stage("actual-recreate", expected)
    }

    /** Group previews keep the same transaction while a real Activity replaces its Canvas. */
    fun recreateGroup(expected: Transform64, owner: CompletableFuture<TransformController.Terminal>) {
        waitUntil("Group preview is not ready before recreation") { canvas.moveBox == expected }
        val before = editor.state(); val beforePixels = pixels()
        val oldActivity = activity; val oldCanvas = canvas
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { oldActivity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        check(activity !== oldActivity && activity.retainedWorkspaceForTest() === workspace && activity.retainedEditorForTest() === editor)
        waitUntil("Group recreation did not bind the retained draft") {
            val next = findCanvas()
            if (next != null && next !== oldCanvas && next.isAttachedToWindow && next.holder.surface.isValid) canvas = next
            canvas !== oldCanvas && canvas.toolMode == CanvasToolMode.MOVE && canvas.moveBox == expected
        }
        check(!oldCanvas.isAttachedToWindow)
        stableBounds()
        check(editor.activeTransformCompletion() === owner && !owner.isDone && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) &&
            pixels().contentEquals(beforePixels))
        stage("actual-group-recreate", expected)
    }

    fun stage(name: String, expected: Transform64) {
        stages.put(JSONObject().put("stage", name).put("expected", expected.toString())
            .put("acceptedBox", if (::canvas.isInitialized) canvas.moveBox?.toString() ?: JSONObject.NULL else JSONObject.NULL)
            .put("committed", if (::editor.isInitialized) editor.state().toString() else JSONObject.NULL))
    }

    fun observation(name: String, value: JSONObject) { stages.put(value.put("stage", name)) }

    fun diagnose(error: Throwable) {
        // Failure capture must keep the same connection flags as the real control actions.
        runCatching {
            automation.clearCache()
            var count = 0
            fun tree(node: AccessibilityNodeInfo, depth: Int): JSONObject {
                val value = JSONObject().put("text", node.text ?: JSONObject.NULL)
                    .put("description", node.contentDescription ?: JSONObject.NULL)
                    .put("enabled", node.isEnabled).put("visible", node.isVisibleToUser)
                    .put("clickable", node.isClickable).put("focused", node.isFocused)
                    .put("bounds", Rect().also(node::getBoundsInScreen).toShortString())
                    .put("actions", JSONArray(node.actionList.map { it.id }))
                count++
                val children = JSONArray()
                for (index in 0 until node.childCount) {
                    if (count >= 2048 || depth >= 64) { value.put("truncated", true); break }
                    node.getChild(index)?.let { children.put(tree(it, depth + 1)) }
                }
                return value.put("children", children)
            }
            val details = JSONObject().put("automationMode", "FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES")
                .put("tree", editorPhysicalDisplayRoot(instrumentation)?.let { tree(it, 0) } ?: JSONObject.NULL)
                .put("ownState", editorUiOwnState(instrumentation, activity))
            automation.takeScreenshot()?.let { bitmap ->
                val screenshot = File(evidence.parentFile, "${evidence.nameWithoutExtension}-failure.png")
                try { screenshot.outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
                details.put("screenshot", screenshot.name)
            }
            observation("failure-diagnostic", details)
        }.onFailure { stages.put(JSONObject().put("diagnosticError", it.toString())) }
        stages.put(JSONObject().put("failure", error.toString()))
    }

    override fun close() {
        try {
            if (::editor.isInitialized && ::canvas.isInitialized) {
                val owner = editor.activeTransformCompletion()
                if (owner != null) {
                    instrumentation.runOnMainSync { canvas.cancelMove() }
                    owner.get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            try {
                instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
                instrumentation.waitForIdleSync()
                workspace.closeAsync().get(15, TimeUnit.SECONDS)
            } finally {
                evidence.writeText(JSONObject().put("selector", label).put("passed", successful).put("stages", stages).toString(2))
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Inspector evidence: files/${evidence.name}\n") })
            }
        }
    }
}

internal fun moveInspectorSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Frozen six-color ordering, independent of the production geometry and renderer. */
internal fun moveInspectorColorPixels(width: Int, height: Int): ByteArray {
    val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(),
        0xffffff00.toInt(), 0xffff00ff.toInt(), 0xff00ffff.toInt())
    return ByteArray(width * height * 4).also { bytes ->
        for (y in 0 until height) for (x in 0 until width) {
            val color = colors[minOf(1, y * 2 / height) * 3 + minOf(2, x * 3 / width)]
            val index = (y * width + x) * 4
            bytes[index] = (color ushr 16).toByte(); bytes[index + 1] = (color ushr 8).toByte()
            bytes[index + 2] = color.toByte(); bytes[index + 3] = (-1).toByte()
        }
    }
}
