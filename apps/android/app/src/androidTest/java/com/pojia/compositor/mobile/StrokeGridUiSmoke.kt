package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/** Real pointer input uses CanvasView's shipping device-headroom admission and bounded JNI begin. */
internal fun strokeGridUiSmoke(instrumentation: Instrumentation): String {
    // Connect before Compose attaches so accessibility semantics are enabled for its initial tree.
    val automation = instrumentation.uiAutomation
    val context = instrumentation.targetContext
    val workspaceToken = "p113-stroke-ui-${System.nanoTime()}"
    check(validatedDebugWorkspaceToken(workspaceToken) == workspaceToken)
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, workspaceToken)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
    val workspace = activity.retainedWorkspaceForTest()
    try {
        check(activity.display?.displayId == Display.DEFAULT_DISPLAY && workspace.recoveryDirectoryForTest().canonicalFile ==
            File(context.filesDir, "debug-workspaces/$workspaceToken").canonicalFile) {
            "Refusing stroke UI edits outside its isolated physical-display workspace"
        }
    } catch (failure: Throwable) {
        instrumentation.runOnMainSync { activity.finish() }
        runCatching { workspace.closeAsync().get(12, TimeUnit.SECONDS) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    instrumentation.sendStatus(0, android.os.Bundle().apply {
        putString("stream", "Stroke UI isolated workspace: $workspaceToken; display=0")
    })
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) {
            val description = StringBuilder()
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null) { description.append("null root\n"); return }
                description.append(" ".repeat(depth)).append(node.className).append(" text=").append(node.text)
                    .append(" description=").append(node.contentDescription).append(" clickable=").append(node.isClickable)
                    .append(" enabled=").append(node.isEnabled).append('\n')
                if (depth < 30) for (index in 0 until node.childCount) describe(node.getChild(index), depth + 1)
            }
            description.append("flags=").append(automation.serviceInfo.flags).append('\n')
            describe(automation.rootInActiveWindow)
            for (window in automation.windows) {
                description.append("window=").append(window.id).append(" active=").append(window.isActive)
                    .append(" focused=").append(window.isFocused).append('\n')
                describe(window.root)
            }
            val stem = "stroke-grid-ui-failure-${System.nanoTime()}"
            File(instrumentation.targetContext.cacheDir, "$stem.txt").writeText(description.toString())
            automation.takeScreenshot()?.let { screenshot ->
                try { File(instrumentation.targetContext.cacheDir, "$stem.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { screenshot.recycle() }
            }
            "$message\n$description"
        }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), label)?.let { return it }
        return null
    }
    fun click(requested: String) {
        val label = when (requested) {
            "Brush" -> context.getString(R.string.ui_brush)
            "Eraser" -> context.getString(R.string.ui_eraser)
            "Undo" -> context.getString(R.string.ui_undo)
            "Redo" -> context.getString(R.string.ui_redo)
            else -> requested
        }
        revealEditorControl(instrumentation, label)
        var clicked = false
        waitUntil("Stroke UI control missing: $label") {
            automation.clearCache()
            var node = findNode(automation.rootInActiveWindow, label)
            while (node != null && !node.isClickable) node = node.parent
            if (!clicked) clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    var canvas: CanvasView? = null
    waitUntil("Stroke UI did not attach actual editor/canvas") {
        instrumentation.runOnMainSync { canvas = findCanvas(activity.window.decorView) }
        canvas != null && activity.retainedEditorForTest() != null
    }
    waitUntil("Stroke UI Activity did not gain focus") {
        var focused = false
        instrumentation.runOnMainSync { focused = activity.window.decorView.hasWindowFocus() }
        focused
    }
    val editor = checkNotNull(activity.retainedEditorForTest()); val view = checkNotNull(canvas)
    fun drain(deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)) {
        val latch = CountDownLatch(1); view.queueEvent { latch.countDown() }
        check(latch.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
            "Stroke UI GL queue did not drain before deadline"
        }
        instrumentation.waitForIdleSync()
    }
    var commandAccepted: Boolean? = null
    val commandEntered = AtomicBoolean(); val commandCompleted = AtomicBoolean()
    fun command(action: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        commandEntered.set(false); commandCompleted.set(false)
        instrumentation.runOnMainSync { commandAccepted = view.command {
            commandEntered.set(true)
            try { action() } finally { commandCompleted.set(true) }
        } }
        check(commandAccepted == true) { "Stroke fixture command was rejected" }
        // Ordinary edits belong to the retained worker, independently of the GL event queue.
        editor.commandDispatcher.awaitIdleForTest().get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        drain(deadline)
    }
    val name = "Stroke grid ${System.nanoTime()}"
    var id = 0L
    val file = File(instrumentation.targetContext.cacheDir, "stroke-grid-ui-${System.nanoTime()}.checkpoint")
    try {
        command {
            editor.addLayer(); id = editor.state().layers.last().id
            editor.renameLayer(id, name)
            editor.setTransform(id, Transform64(editor.canvasWidth + 100.0, editor.canvasHeight + 100.0, 4.0, 4.0))
            editor.selectLayer(id)
        }
        check(id != 0L) { "Stroke fixture did not create a target layer" }
        waitUntil("Actual canvas did not select new brush target") { view.layerId == id }
        val original = editor.state().layers.first { it.id == id }
        val expectedWidth = editor.canvasWidth + 104; val expectedHeight = editor.canvasHeight + 104
        var x = 0f; var y = 0f; var down = 0L
        fun toolReady(erasing: Boolean) {
            waitUntil("Stroke UI tool/focus/layout not ready: erasing=$erasing target=$id") {
                var ready = false
                instrumentation.runOnMainSync {
                    ready = activity.window.decorView.hasWindowFocus() && view.hasWindowFocus() &&
                        view.isShown && !view.isLayoutRequested && view.width > 0 && view.height > 0 &&
                        view.layerId == id && view.toolMode == CanvasToolMode.BRUSH && view.erasing == erasing
                }
                ready
            }
        }
        fun preparePointer(erasing: Boolean) {
            toolReady(erasing)
            // Tool controls can reflow Compose and resize the canvas. Wait for the next
            // layout frame, then fit and sample the actual screen location for this gesture.
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync { view.postOnAnimation { frame.countDown() } }
            check(frame.await(10, TimeUnit.SECONDS)) { "Stroke UI layout frame did not arrive" }
            instrumentation.waitForIdleSync()
            toolReady(erasing)
            instrumentation.runOnMainSync {
                view.fit()
                val location = IntArray(2); view.getLocationOnScreen(location)
                x = location[0] + view.width / 2f; y = location[1] + view.height / 2f
                val bounds = Rect()
                check(view.getGlobalVisibleRect(bounds) && bounds.contains(x.toInt(), y.toInt())) {
                    "Stroke canvas center is not visible: center=$x,$y bounds=$bounds size=${view.width}x${view.height}"
                }
            }
            drain()
            down = SystemClock.uptimeMillis()
        }
        fun pointer(action: Int, offset: Long) {
            val wait = down + offset - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            // Stroke actions run through the GL queue and post state back to the UI.
            // The retained barrier includes active stroke reservations, so it must only
            // be awaited after UP/CANCEL; waiting after DOWN would block its own gesture.
            instrumentation.waitForIdleSync()
            drain()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
                drain()
                waitUntil("Stroke UI did not settle action=$action: revision=${editor.currentRevision()}") {
                    !editor.hasActiveStrokes && editor.pendingEditCount == 0 && editor.strokeReservations().isEmpty()
                }
            }
        }
        // Reveal the real rail tool before using its localized header choices.
        val brushLabel = if (view.erasing) context.getString(R.string.ui_eraser_e) else
            context.getString(R.string.ui_tool_shortcut, context.toolTitle(EditorTool.BRUSH), "B")
        click(brushLabel)
        // The selected FilterChip has no click action. Switch away and back through real controls.
        click("Eraser"); toolReady(true)
        click("Brush"); preparePointer(false)
        val revision = editor.currentRevision()
        pointer(MotionEvent.ACTION_DOWN, 0)
        check(editor.currentRevision() == revision) { "Brush preview unexpectedly committed revision ${editor.currentRevision()} (before=$revision)" }
        checkNotNull(editor.captureScene()) { "Brush preview did not expose a display scene" }.use { scene ->
            val index = editor.state().layers.indexOfFirst { it.id == id }
            val previewLayer = scene.layerMetadata(index)
            check(previewLayer.pixelWidth == expectedWidth && previewLayer.pixelHeight == expectedHeight) {
                "Brush working preview expected ${expectedWidth}x$expectedHeight, actual=$previewLayer target=$id index=$index"
            }
        }
        pointer(MotionEvent.ACTION_CANCEL, 20)
        check(editor.state().layers.first { it.id == id } == original && editor.currentRevision() == revision) {
            "Brush cancel changed layer or revision: original=$original actual=${editor.state().layers.first { it.id == id }} revision=${editor.currentRevision()} expected=$revision"
        }
        click("Eraser"); preparePointer(true)
        // The pointer is the fitted canvas center; allocated patches are anchored at working coordinate zero.
        val radius = view.brushSize / 2.0 + 2
        val cropX = maxOf(0, floor((editor.canvasWidth / 2.0 - radius) / 256).toInt() * 256)
        val cropY = maxOf(0, floor((editor.canvasHeight / 2.0 - radius) / 256).toInt() * 256)
        val cropRight = minOf(expectedWidth, ceil((editor.canvasWidth / 2.0 + radius) / 256).toInt() * 256)
        val cropBottom = minOf(expectedHeight, ceil((editor.canvasHeight / 2.0 + radius) / 256).toInt() * 256)
        pointer(MotionEvent.ACTION_DOWN, 0); pointer(MotionEvent.ACTION_UP, 20)
        val committed = editor.state().layers.first { it.id == id }
        check(committed.assetPresent && committed.pixelWidth == cropRight - cropX && committed.pixelHeight == cropBottom - cropY) {
            "Eraser commit expected asset with ${cropRight - cropX}x${cropBottom - cropY} pixels, actual=$committed; pointer=$x,$y revision=${editor.currentRevision()} before=$revision active=${editor.hasActiveStrokes} pending=${editor.pendingEditCount} latest=${editor.commandDispatcher.latestCompletion}"
        }
        check(committed.transform == Transform64(cropX.toDouble(), cropY.toDouble(),
            (cropRight - cropX).toDouble(), (cropBottom - cropY).toDouble())) {
            "Eraser commit transform differs from tile crop ($cropX,$cropY)-($cropRight,$cropBottom): ${committed.transform}"
        }
        click("Undo"); editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS); drain()
        check(editor.state().layers.first { it.id == id } == original) { "Stroke Undo did not restore original layer: ${editor.state().layers.first { it.id == id }}" }
        click("Redo"); editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS); drain()
        check(editor.state().layers.first { it.id == id } == committed) { "Stroke Redo did not restore committed layer: ${editor.state().layers.first { it.id == id }}" }
        editor.save(file)
        NativeEditor.load(file).use {
            check(it.state().layers.first { layer -> layer.id == id } == committed) { "Checkpoint reopen changed the committed stroke layer" }
        }
        return "Actual CanvasView pointer begin/working preview/cancel/transparent eraser commit/Undo/Redo/checkpoint passed using shipping headroom policy and bounded JNI API; localized controls; workspace=$workspaceToken; physical display 0."
    } catch (error: Exception) {
        runCatching {
            val prefix = "p24-stroke-command-failure02-${System.nanoTime()}"
            val record = JSONObject().put("error", error.toString()).put("idAtFailure", id)
                .put("commandAccepted", commandAccepted ?: JSONObject.NULL).put("commandEntered", commandEntered.get())
                .put("commandCompleted", commandCompleted.get()).put("scope", "Failure-time passive capture before finally")
            record.put("ownState", editorUiOwnState(instrumentation, activity))
                .put("latestCompletion", editor.commandDispatcher.latestCompletion?.toString() ?: JSONObject.NULL)
                .put("pendingEdits", editor.pendingEditCount).put("strokeReservations", editor.strokeReservations().size)
            for (field in listOf("editor", "lease", "retired")) {
                val value = CanvasView::class.java.getDeclaredField(field).apply { isAccessible = true }.get(view)
                record.put("capturedView-$field", if (field == "editor") System.identityHashCode(value) else value?.toString() ?: JSONObject.NULL)
            }
            File(instrumentation.targetContext.filesDir, "$prefix.json").writeText(record.toString(2))
            automation.takeScreenshot()?.let { screenshot ->
                try { File(instrumentation.targetContext.filesDir, "$prefix.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { screenshot.recycle() }
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Stroke command failure-time diagnostic: files/$prefix.json") })
        }.onFailure { android.util.Log.e("CompositorStrokeUi", "Could not preserve command diagnosis", it) }
        throw error
    } finally {
        // Cleanup must survive UI retirement and must not leave a temporary layer in recovery.
        editor.serialized {
            editor.endStroke(true)
            if (id != 0L && editor.state().layers.any { it.id == id }) editor.deleteLayer(id)
        }
        check(editor.state().layers.none { it.id == id })
        val cleanupRevision = editor.currentRevision()
        waitUntil("Stroke UI cleanup did not reach recovery") {
            activity.retainedWorkspaceForTest().snapshot().savedRevision == cleanupRevision
        }
        file.delete()
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        workspace.closeAsync().get(12, TimeUnit.SECONDS)
    }
}
