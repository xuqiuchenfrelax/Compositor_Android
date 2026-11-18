package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
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

/** Real fields, tool keys and pointer input verify the product UI reaches native pixels. */
internal fun workspaceUiSmoke(instrumentation: Instrumentation): String {
    var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val focusTrace = StringBuilder()
    fun focusSnapshot(label: String) {
        val snapshot = StringBuilder("stage=$label uptime=${SystemClock.uptimeMillis()}\n")
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val focused = decor.findFocus()
            snapshot.append("activity=").append(System.identityHashCode(activity))
                .append(" windowFocus=").append(decor.hasWindowFocus())
                .append(" currentFocus=").append(activity.currentFocus?.javaClass?.name)
                .append(" actualFocusedView=").append(focused?.javaClass?.name)
                .append(" focusedId=").append(focused?.id).append(" focusedTag=").append(focused?.tag)
                .append(" focusedAttached=").append(focused?.isAttachedToWindow).append('\n')
            val retained = activity.retainedEditorForTest()
            val owner = activity.retainedWorkspaceForTest().snapshot()
            snapshot.append("editor=").append(retained?.let { System.identityHashCode(it) })
                .append(" editorPendingCount=").append(retained?.pendingEditCount)
                .append(" workspaceLoading=").append(owner.loading)
                .append(" workspaceFrozen=").append(owner.resourceGrowthFrozen)
                .append(" projectBusy=").append(activity.retainedProjectFlowForTest().state.busy).append('\n')
            fun describe(view: View) {
                if (view is CanvasView) snapshot.append("canvas=").append(System.identityHashCode(view))
                    .append(" tool=").append(view.toolMode).append(" settings=").append(view.strokeSettings)
                    .append(" editing=").append(view.editingInProgress).append(" stroke=").append(view.strokeInProgress)
                    .append(" layer=").append(view.layerId).append(" mask=").append(view.editingMask)
                    .append(" attached=").append(view.isAttachedToWindow)
                    .append(" surface=").append(view.holder.surface.isValid).append('\n')
                if (view.isFocused) snapshot.append("focusedView=").append(view.javaClass.name)
                    .append(" id=").append(view.id).append(" tag=").append(view.tag).append('\n')
                if (view is ViewGroup) for (index in 0 until view.childCount) describe(view.getChildAt(index))
            }
            describe(decor)
        }
        focusTrace.append(snapshot)
        android.util.Log.i("CompositorWorkspaceFocus", snapshot.toString())
    }
    fun failureEvidence(message: String) {
        focusSnapshot("failure: $message")
        val evidence = StringBuilder(focusTrace).append("accessibility failure tree\n")
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null) { evidence.append("null root\n"); return }
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            evidence.append("  ".repeat(depth)).append(node.className)
                .append(" text=").append(node.text).append(" description=").append(node.contentDescription)
                .append(" focused=").append(node.isFocused).append(" axFocused=").append(node.isAccessibilityFocused)
                .append(" editable=").append(node.isEditable).append(" enabled=").append(node.isEnabled)
                .append(" visible=").append(node.isVisibleToUser).append(" error=").append(node.error)
                .append(" bounds=").append(bounds).append(" actions=").append(node.actionList.map { it.id }).append('\n')
            if (depth < 30) for (index in 0 until node.childCount) describe(node.getChild(index), depth + 1)
        }
        val automation = instrumentation.uiAutomation
        automation.clearCache()
        describe(automation.rootInActiveWindow)
        for (window in automation.windows) {
            evidence.append("window=").append(window.id).append(" active=").append(window.isActive)
                .append(" focused=").append(window.isFocused).append('\n')
            describe(window.root)
        }
        val file = File(instrumentation.targetContext.cacheDir, "p19-workspace-focus-diagnostic.txt")
        file.writeText(evidence.toString())
        android.util.Log.e("CompositorWorkspaceFocus", "Diagnostic ${file.name}\n$evidence")
        automation.takeScreenshot()?.let { screenshot ->
            try { File(instrumentation.targetContext.cacheDir, "p19-workspace-focus-diagnostic.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
        }
    }
    fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) {
            runCatching { failureEvidence(message) }.onFailure {
                android.util.Log.e("CompositorWorkspaceFocus", "Could not preserve failure diagnostic", it)
            }
            message
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
    fun drain() {
        val latch = CountDownLatch(1)
        checkNotNull(canvas()).queueEvent { latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    waitUntil("Workspace did not attach") { activity.retainedEditorForTest() != null && canvas()?.holder?.surface?.isValid == true }
    val editor = checkNotNull(activity.retainedEditorForTest())
    val original = editor.state()
    var id = 0L
    try {
        val view = checkNotNull(canvas())
        check(view.strokeSettings == StrokeSettings()) { "UI did not use Mac brush defaults" }
        check(view.toolMode == CanvasToolMode.IDLE) { "Unavailable Move was presented as an executable tool" }
        focusSnapshot("before B")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B)
        focusSnapshot("after B")
        waitUntil("B did not select Brush") { view.toolMode == CanvasToolMode.BRUSH }
        var field: AccessibilityNodeInfo? = null
        waitUntil("Size value field missing") { field = find(instrumentation.uiAutomation.rootInActiveWindow, "Size value"); field != null }
        focusSnapshot("before Size click")
        checkNotNull(field).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        focusSnapshot("after Size click")
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "12") }
        check(checkNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "Size field rejected text" }
        focusSnapshot("after Size SET_TEXT / before Enter")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        focusSnapshot("after Enter")
        waitUntil("Size field did not update the stroke settings") { view.strokeSettings.diameter == 12f }
        // Numeric input must release focus, so the following keys operate on the canvas.
        focusSnapshot("size assertion passed / before 3")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_3)
        focusSnapshot("after 3 / before X")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
        focusSnapshot("after X")
        waitUntil("Opacity key or palette swap failed") {
            view.strokeSettings.opacity == .3f && view.strokeSettings.red == 1f &&
                view.strokeSettings.green == 1f && view.strokeSettings.blue == 1f
        }
        check(editor.currentRevision() == original.revision) { "Tool settings entered document history" }
        view.command { editor.addLayer(); id = editor.state().layers.last().id; editor.selectLayer(id) }
        drain(); waitUntil("Temporary pixel target not attached") { view.layerId == id }
        val rect = Rect(); instrumentation.runOnMainSync { check(view.getGlobalVisibleRect(rect)) }
        val start = SystemClock.uptimeMillis()
        for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 20L to MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, start + offset, action, rect.exactCenterX(), rect.exactCenterY(), 0)
            instrumentation.sendPointerSync(event); event.recycle()
        }
        drain()
        fun paintedTiles(): List<ByteArray> = checkNotNull(editor.captureScene()).use { scene ->
            val index = editor.state().layers.indexOfFirst { it.id == id }
            scene.tileViewsMetadata(index).indices.map { tile ->
                val info = scene.tileViewMetadata(index, tile)
                val buffer = ByteBuffer.allocateDirect(info.width * info.height * 4)
                scene.readTile(index, tile, info.width * 4, buffer)
                ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
            }
        }
        val painted = paintedTiles()
        var maximumAlpha = 0
        for (tile in painted) for (offset in tile.indices step 4) {
            val alpha = tile[offset + 3].toInt() and 255
            maximumAlpha = maxOf(maximumAlpha, alpha)
            check((0..2).all { (tile[offset + it].toInt() and 255) == alpha }) { "UI foreground color did not reach stored white pixels" }
        }
        check(abs(maximumAlpha - 77) <= 1) { "UI opacity did not reach source pixels: $maximumAlpha" }
        view.command { editor.undo(false) }; drain()
        check(paintedTiles().all { tile -> tile.all { it == 0.toByte() } }) { "One undo did not remove the configured stroke" }
        view.command { editor.undo(true) }; drain()
        val redone = paintedTiles()
        check(redone.size == painted.size && redone.indices.all { redone[it].contentEquals(painted[it]) })
        val revision = editor.currentRevision()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_H)
        waitUntil("H did not select Hand") { view.toolMode == CanvasToolMode.HAND }
        val keyTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(keyTime, keyTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 0, KeyEvent.META_CTRL_ON))
        instrumentation.sendKeySync(KeyEvent(keyTime, keyTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, 0, KeyEvent.META_CTRL_ON))
        waitUntil("Ctrl-1 did not select actual pixels") { view.zoomPercent == 100.0 }
        check(editor.currentRevision() == revision) { "Navigation entered document history" }
        val expected = view.strokeSettings
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity.recreate() }
        activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        instrumentation.removeMonitor(monitor)
        waitUntil("Recreated workspace did not attach") { canvas()?.holder?.surface?.isValid == true }
        check(activity.retainedEditorForTest() === editor)
        waitUntil("Recreation lost tool, settings or foreground color") {
            canvas()?.strokeSettings == expected && canvas()?.toolMode == CanvasToolMode.HAND
        }
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(instrumentation.targetContext.cacheDir, "p19-workspace-phone.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { screenshot.recycle() }
        return "Real Size field, B/H/3/X and Ctrl-1 bindings, white premultiplied pixels at 30% opacity, one undo/redo, settings outside document history and recreation preserving editor/tool/parameters/colors passed. Screenshot p19-workspace-phone.png; full Mac UI parity remains incomplete."
    } finally {
        editor.serialized {
            editor.endStroke(true)
            if (id != 0L && editor.state().layers.any { it.id == id }) editor.deleteLayer(id)
            if (original.layers.any { it.id == original.document.activeLayerId }) editor.selectLayer(original.document.activeLayerId)
        }
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
