package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public actions exercise real foreground routing, read settlement and retained configuration. */
internal fun eyedropperUiSmoke(instrumentation: Instrumentation): String {
    var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun canvas(): CanvasView? {
        var result: CanvasView? = null
        instrumentation.runOnMainSync { result = findCanvas(activity.window.decorView) }
        return result
    }
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    fun drain(view: CanvasView = checkNotNull(canvas())) {
        val latch = CountDownLatch(1); view.queueEvent { latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    val actionTrace = java.io.File(instrumentation.targetContext.cacheDir,
        "p27-eyedropper-ui-actions-${System.nanoTime()}.jsonl")
    check(actionTrace.createNewFile())
    fun trace(label: String, phase: String, matched: AccessibilityNodeInfo?,
              actionable: AccessibilityNodeInfo?, returned: Boolean? = null) {
        val ancestors = JSONArray()
        var current = matched
        while (current != null) {
            val bounds = android.graphics.Rect(); current.getBoundsInScreen(bounds)
            val actions = JSONArray()
            current.actionList.forEach { actions.put(JSONObject().put("id", it.id).put("label", it.label?.toString())) }
            ancestors.put(JSONObject().put("text", current.text?.toString())
                .put("description", current.contentDescription?.toString()).put("class", current.className?.toString())
                .put("enabled", current.isEnabled).put("clickable", current.isClickable)
                .put("visible", current.isVisibleToUser).put("bounds", bounds.toShortString())
                .put("selectedActionNode", current == actionable).put("actions", actions))
            current = current.parent
        }
        val record = JSONObject().put("label", label).put("phase", phase).put("nanoTime", System.nanoTime())
            .put("ancestors", ancestors).put("returned", returned ?: JSONObject.NULL).toString()
        actionTrace.appendText("$record\n")
        android.util.Log.i("P27EyedropperUi", record)
    }
    fun click(label: String) {
        var matched: AccessibilityNodeInfo? = null
        var node: AccessibilityNodeInfo? = null
        waitUntil("Missing product action $label") {
            instrumentation.uiAutomation.clearCache()
            matched = find(instrumentation.uiAutomation.rootInActiveWindow, label)
            node = matched
            while (node != null && (node?.isClickable != true || node?.isEnabled != true)) node = node?.parent
            node?.isEnabled == true && node?.isClickable == true
        }
        trace(label, "before-show", matched, node)
        val shown = checkNotNull(node).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        trace(label, "show-returned", matched, node, shown)
        trace(label, "before-click", matched, node)
        val clicked = checkNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        trace(label, "click-returned", matched, node, clicked)
        check(clicked) { "Product action $label rejected click" }
        instrumentation.waitForIdleSync()
    }
    waitUntil("Product canvas did not attach") { canvas()?.holder?.surface?.isValid == true && activity.retainedEditorForTest() != null }
    val editor = checkNotNull(activity.retainedEditorForTest())
    val original = editor.state()
    var fixture = 0L
    try {
        var view = checkNotNull(canvas())
        fun snapshot(phase: String, event: MotionEvent? = null) {
            try { instrumentation.runOnMainSync {
                fun field(name: String): Any? = runCatching {
                    CanvasView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
                }.getOrElse { "diagnostic-unavailable: $it" }
                fun method(name: String): Any? = runCatching {
                    CanvasView::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(view)
                }.getOrElse { "diagnostic-unavailable: $it" }
                val scale = field("scale") as Double
                val offsetX = field("offsetX") as Double; val offsetY = field("offsetY") as Double
                val state = editor.state()
                val record = JSONObject().put("phase", phase).put("nanoTime", System.nanoTime())
                    .put("viewIdentity", System.identityHashCode(view)).put("editorIdentity", System.identityHashCode(editor))
                    .put("width", view.width).put("height", view.height).put("surfaceValid", view.holder.surface.isValid)
                    .put("scale", scale).put("offsetX", offsetX).put("offsetY", offsetY).put("zoomPercent", view.zoomPercent)
                    .put("current", method("current")).put("retired", field("retired"))
                    .put("nativeCommandPending", method("getNativeCommandPending"))
                    .put("tool", view.toolMode.name).put("samplingEnabled", view.samplingEnabled)
                    .put("foreground", view.foregroundColor.toUInt().toString(16)).put("strokeSettings", view.strokeSettings.toString())
                    .put("ring", view.sampleRing?.toString()).put("sampling", view.samplingInProgress)
                    .put("settlementPending", view.sampleSettlementPending).put("stats", view.eyedropperStats().toString())
                    .put("uiStats", view.eyedropperUiStats().toString()).put("documentWidth", editor.canvasWidth)
                    .put("documentHeight", editor.canvasHeight).put("revision", editor.currentRevision())
                    .put("pendingEdits", editor.hasPendingEdits).put("activeStrokes", editor.hasActiveStrokes)
                    .put("workspace", activity.retainedWorkspaceForTest().snapshot().toString())
                    .put("fixture", state.layers.firstOrNull { it.id == fixture }?.toString())
                    .put("allLayers", JSONArray(state.layers.map { it.toString() }))
                if (event != null) record.put("action", event.actionMasked).put("eventX", event.x).put("eventY", event.y)
                    .put("documentX", (event.x - offsetX) / scale).put("documentY", (event.y - offsetY) / scale)
                val encoded = record.toString()
                actionTrace.appendText("$encoded\n"); android.util.Log.i("P27EyedropperUi", encoded)
            } } catch (error: Throwable) { android.util.Log.w("P27EyedropperUi", "$phase diagnostic unavailable", error) }
        }
        check(editor.canvasWidth >= 4 && editor.canvasHeight >= 1) { "Eyedropper UI fixture requires four document columns" }
        fun documentX(x: Double) = x * editor.canvasWidth / 64.0
        fun documentY(y: Double) = y * editor.canvasHeight / 64.0
        check(view.command {
            fixture = eyedropperFixture(editor, opaqueRed = true)
            editor.setTransform(fixture, Transform64(0.0, 0.0, editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble()))
        }); drain()
        waitUntil("Fixture target not selected") { view.layerId == fixture }
        check(editor.state().layers.first { it.id == fixture }.sampling == 0)
        val documentBefore = editor.state()
        val workspaceBefore = activity.retainedWorkspaceForTest().snapshot()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_I)
        waitUntil("I did not select enabled Eyedropper") { view.toolMode == CanvasToolMode.EYEDROPPER }
        check(view.showsSampleRing)
        click("Sample Ring"); waitUntil("Ring off did not reach Canvas") { !view.showsSampleRing }
        click("Sample Ring"); waitUntil("Ring on did not reach Canvas") { view.showsSampleRing }
        snapshot("before-first-down")
        val oracleRecord = JSONObject().put("phase", "readonly-native-oracle").put("nanoTime", System.nanoTime())
        try { NativeColorSampler.capture(instrumentation.targetContext, editor).use { oracle ->
            for (x in listOf(8.0, 24.0, 56.0)) oracleRecord.put("point-$x-8", oracle.sample(documentX(x), documentY(8.0))?.toUInt()?.toString(16) ?: JSONObject.NULL)
        } } catch (error: Throwable) { oracleRecord.put("diagnosticUnavailable", error.toString()) }
        val oracleEncoded = oracleRecord.toString()
        actionTrace.appendText("$oracleEncoded\n"); android.util.Log.i("P27EyedropperUi", oracleEncoded)
        var start = SystemClock.uptimeMillis()
        fun dispatch(action: Int, x: Double, y: Double = 8.0, meta: Int = 0, await: Boolean = true) {
            var event: MotionEvent? = null
            instrumentation.runOnMainSync {
                fun geometry(name: String) = CanvasView::class.java.getDeclaredField(name).apply { isAccessible = true }.getDouble(view)
                val scale = geometry("scale"); val offsetX = geometry("offsetX"); val offsetY = geometry("offsetY")
                check(scale.isFinite() && scale > 0 && offsetX.isFinite() && offsetY.isFinite())
                event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    (offsetX + documentX(x) * scale).toFloat(), (offsetY + documentY(y) * scale).toFloat(), meta)
            }
            val mapped = checkNotNull(event)
            try {
                snapshot("before-dispatch", mapped)
                instrumentation.runOnMainSync { check(view.dispatchTouchEvent(mapped)) }
                snapshot("after-dispatch", mapped)
            } finally { mapped.recycle() }
            if (await) drain(view)
            snapshot("after-dispatch-drain")
        }
        dispatch(MotionEvent.ACTION_DOWN, 8.0)
        try {
            waitUntil("Foreground did not reach actual Brush settings") {
                view.foregroundColor == 0xffff0000.toInt() && view.strokeSettings.red == 1f && view.strokeSettings.green == 0f && view.strokeSettings.blue == 0f
            }
        } catch (error: Throwable) {
            snapshot("foreground-timeout")
            runCatching { instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { java.io.File(instrumentation.targetContext.cacheDir, "p27-eyedropper-ui-timeout-${System.nanoTime()}.png")
                    .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { screenshot.recycle() }
            } }.onFailure { android.util.Log.w("P27EyedropperUi", "Timeout screenshot unavailable", it) }
            throw error
        }
        check(view.sampleRing?.sampled == 0xffff0000.toInt())
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            try { java.io.File(instrumentation.targetContext.cacheDir, "p27-eyedropper-ring.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { screenshot.recycle() }
        }
        try { waitUntil("Sample ring accessibility overlay did not become visible") {
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, activity.getString(R.string.ui_sample_ring))?.isVisibleToUser == true
        }
        val frames = CountDownLatch(2)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback {
                frames.countDown()
                Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        check(frames.await(20, TimeUnit.SECONDS)) { "Sample ring did not receive two display frames" }
        } catch (error: Exception) {
            snapshot("ring-previsual-failure")
            runCatching { instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { java.io.File(instrumentation.targetContext.cacheDir, "p27-eyedropper-ring-previsual-failure-${System.nanoTime()}.png")
                    .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { screenshot.recycle() }
            } }.onFailure { android.util.Log.w("P27EyedropperUi", "Ring failure screenshot unavailable", it) }
            throw error
        }
        val visualPrefix = "p27-eyedropper-ring-visual-${System.nanoTime()}"
        var visualPassed = false
        var lastScreenshot: android.graphics.Bitmap? = null
        var lastVisualRecord = JSONObject()
        val visualDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        try {
            while (!visualPassed && System.nanoTime() < visualDeadline) {
                var centerX = 0f; var centerY = 0f; var density = 0f
                var originalArgb = 0; var sampledArgb = 0
                instrumentation.runOnMainSync {
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val ring = checkNotNull(view.sampleRing)
                    centerX = location[0] + ring.x; centerY = location[1] + ring.y
                    density = view.resources.displayMetrics.density
                    originalArgb = ring.original; sampledArgb = ring.sampled
                }
                lastScreenshot?.recycle(); lastScreenshot = instrumentation.uiAutomation.takeScreenshot()
                val screenshot = lastScreenshot
                val probes = JSONArray()
                fun probe(name: String, dx: Float, dy: Float, expected: Int): Boolean {
                    val x = kotlin.math.round(centerX + dx * density).toInt()
                    val y = kotlin.math.round(centerY + dy * density).toInt()
                    var matching = 0
                    if (screenshot != null && x in 1 until screenshot.width - 1 && y in 1 until screenshot.height - 1) {
                        for (py in y - 1..y + 1) for (px in x - 1..x + 1) {
                            val actual = screenshot.getPixel(px, py)
                            if (listOf(0, 8, 16, 24).all { shift ->
                                kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 3
                            }) matching++
                        }
                    }
                    probes.put(JSONObject().put("name", name).put("x", x).put("y", y)
                        .put("expected", expected.toUInt().toString(16)).put("matching3x3", matching))
                    return matching >= 8
                }
                val top = probe("sampled-top", 0f, -43f, 0xffff0000.toInt())
                val bottom = probe("original-bottom", 0f, 43f, 0xff000000.toInt())
                val left = probe("gray-left", -53f, 0f, 0xff737373.toInt())
                val right = probe("gray-right", 53f, 0f, 0xff737373.toInt())
                visualPassed = top && bottom && left && right
                lastVisualRecord = JSONObject().put("phase", "ring-visual").put("nanoTime", System.nanoTime())
                    .put("centerX", centerX).put("centerY", centerY).put("density", density).put("frames", 2)
                    .put("modelOriginal", originalArgb.toUInt().toString(16)).put("modelSampled", sampledArgb.toUInt().toString(16))
                    .put("probes", probes).put("passed", visualPassed)
                if (!visualPassed) Thread.sleep(50)
            }
            lastScreenshot?.let { screenshot -> java.io.File(instrumentation.targetContext.cacheDir, "$visualPrefix.png")
                .outputStream().use { check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            java.io.File(instrumentation.targetContext.cacheDir, "$visualPrefix.json").writeText(lastVisualRecord.toString(2))
            actionTrace.appendText("${lastVisualRecord}\n"); android.util.Log.i("P27EyedropperUi", lastVisualRecord.toString())
            check(visualPassed) { "Sample ring rendered annulus did not match red/black/gray independent probes" }
        } finally { lastScreenshot?.recycle() }
        val originalColor = checkNotNull(view.sampleRing).original
        dispatch(MotionEvent.ACTION_MOVE, 24.0, await = false)
        dispatch(MotionEvent.ACTION_UP, 56.0, await = false); drain(view)
        waitUntil("Palette dropped accepted MOVE or sampled UP") { view.foregroundColor == 0xff0000ff.toInt() && !view.sampleSettlementPending }
        check(view.sampleRing == null && originalColor != 0xff0000ff.toInt() && editor.state() == documentBefore)
        check(activity.retainedWorkspaceForTest().snapshot().currentRevision == workspaceBefore.currentRevision &&
            activity.retainedWorkspaceForTest().snapshot().projectDirty == workspaceBefore.projectDirty)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B)
        waitUntil("B did not restore Brush") { view.toolMode == CanvasToolMode.BRUSH }
        click("Eyedropper (I)"); waitUntil("Enabled tool rail did not route Eyedropper") { view.toolMode == CanvasToolMode.EYEDROPPER }
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        waitUntil("Space did not temporarily select Hand") { view.toolMode == CanvasToolMode.HAND }
        val samplesBefore = view.eyedropperStats().sampledPoints
        start = SystemClock.uptimeMillis(); dispatch(MotionEvent.ACTION_DOWN, 56.0, meta = KeyEvent.META_ALT_ON); dispatch(MotionEvent.ACTION_UP, 56.0)
        check(view.eyedropperStats().sampledPoints == samplesBefore)
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
        waitUntil("Space release did not restore Eyedropper") { view.toolMode == CanvasToolMode.EYEDROPPER }
        // A direct native edit invalidates the frozen gesture even when no product action cancelled it.
        start = SystemClock.uptimeMillis(); dispatch(MotionEvent.ACTION_DOWN, 24.0)
        editor.renameLayer(fixture, "Eyedropper stale fixture")
        dispatch(MotionEvent.ACTION_MOVE, 8.0); dispatch(MotionEvent.ACTION_UP, 8.0)
        check(view.foregroundColor == 0xff0000ff.toInt() && view.sampleRing == null)
        start = SystemClock.uptimeMillis(); dispatch(MotionEvent.ACTION_DOWN, 24.0)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        view.queueEvent { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        check(entered.await(10, TimeUnit.SECONDS))
        dispatch(MotionEvent.ACTION_MOVE, 8.0, await = false)
        try {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
            waitUntil("X did not cancel live sampler and update palette") { view.foregroundColor == 0xffffffff.toInt() && !view.samplingInProgress }
        } finally { release.countDown() }
        drain(view); check(view.foregroundColor == 0xffffffff.toInt()) { "Stale result overwrote explicit palette edit" }
        start = SystemClock.uptimeMillis(); dispatch(MotionEvent.ACTION_DOWN, 24.0)
        val oldView = view
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity.recreate() }
        activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        instrumentation.removeMonitor(monitor)
        waitUntil("Recreated workspace did not attach") { canvas()?.holder?.surface?.isValid == true && canvas() !== oldView }
        view = checkNotNull(canvas())
        waitUntil("Configuration lost tool, foreground or ring setting") {
            view.toolMode == CanvasToolMode.EYEDROPPER && view.foregroundColor == 0xff0000ff.toInt() && view.showsSampleRing
        }
        check(activity.retainedEditorForTest() === editor && oldView.sampleRing == null &&
            oldView.eyedropperStats().samplerCreates == oldView.eyedropperStats().samplerCloses)
        return "Product I/tool rail, default ring/toggle, actual visible red/black/gray annulus after accessibility and display frames, actual foreground→Brush settings, DOWN/MOVE/UP final color, readonly history/revision/dirty, Space Hand priority, document-change and palette-X stale rejection, retained configuration and retired sampler release passed. Modal picker canvas/background-target parity remains open."
    } finally {
        val closingView = canvas()
        instrumentation.runOnMainSync { closingView?.cancelSampling() }
        editor.serialized {
            editor.endStroke(true)
            if (fixture != 0L && editor.state().layers.any { it.id == fixture }) editor.deleteLayer(fixture)
            if (original.layers.any { it.id == original.document.activeLayerId }) editor.selectLayer(original.document.activeLayerId)
        }
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
