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
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Shipping Canvas pointer/controller/renderer path; assertions use committed state and independent scenes. */
internal fun moveCanvasSmoke(instrumentation: Instrumentation): String {
    val automation = instrumentation.uiAutomation
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        if (!predicate()) {
            val error = IllegalStateException(message)
            captureEditorUiFailure(instrumentation, activity, error, emptyList())
            throw error
        }
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
        var clicked = false
        waitUntil("Move control missing: $label") {
            automation.clearCache()
            var target = node(automation.rootInActiveWindow, label)
            while (target != null && !target.isClickable) target = target.parent
            if (!clicked) clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    fun setFieldText(label: String, text: String) {
        var field: AccessibilityNodeInfo? = null
        waitUntil("Move editable control missing: $label") {
            automation.clearCache()
            var candidate = node(automation.rootInActiveWindow, label)
            // Compose can expose the description as a child of the focused EditText.
            while (candidate != null && !candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
                candidate = candidate.parent
            field = candidate?.takeIf { it.isEditable && it.isEnabled && it.isVisibleToUser && it.isFocused }
            field != null
        }
        check(checkNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }
    fun canvasKeyboardFocus() = waitUntil("Move field did not return keyboard focus to the canvas") {
        automation.clearCache()
        node(automation.rootInActiveWindow, "Canvas")?.isFocused == true
    }
    var view: CanvasView? = null
    waitUntil("Move initial canvas missing") {
        instrumentation.runOnMainSync { view = findCanvas(activity.window.decorView) }
        view != null && activity.retainedEditorForTest() != null
    }
    val previous = checkNotNull(view)
    val workspace = activity.retainedWorkspaceForTest()
    check(workspace.installCandidate("p29-move-${System.nanoTime()}", DirtyDisposition.DISCARD) {
        NativeEditor(256, false).also { editor ->
            val pixels = ByteBuffer.allocateDirect(16).apply { repeat(4) { put(byteArrayOf(-1, 0, 0, -1)) }; rewind() }
            val id = editor.importPixels(2, 2, pixels)
            editor.setTransform(id, Transform64(80.0, 80.0, 16.0, 16.0))
            editor.selectLayer(id)
        }
    }.result.get(15, TimeUnit.SECONDS).installed)
    val editor = checkNotNull(workspace.snapshot().editor)
    view = null
    waitUntil("Move replacement canvas missing") {
        instrumentation.runOnMainSync {
            val candidate = findCanvas(activity.window.decorView)
            if (candidate != null && candidate !== previous && candidate.isAttachedToWindow &&
                candidate.holder.surface.isValid && candidate.width > 0 && candidate.height > 0 &&
                activity.retainedEditorForTest() === editor) view = candidate
        }
        view != null
    }
    val canvas = checkNotNull(view)
    val id = editor.state().layers.single().id
    fun stats(): GpuStats {
        val latch = CountDownLatch(1); var result: GpuStats? = null
        canvas.debugGpuStats { result = it; latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS))
        return checkNotNull(result)
    }
    fun settled() = waitUntil("Move terminal did not acknowledge") { !canvas.moveInProgress && editor.activeTransformCompletion() == null }
    val evidence = File(instrumentation.targetContext.filesDir, "p29-move-canvas-${System.nanoTime()}")
    // Passive test diagnostics: original input and scene operands are unchanged.
    val diagnosticLock = Any()
    val receivedInputs = mutableListOf<Map<String, Any?>>()
    val injectedInputs = mutableListOf<Map<String, Any?>>()
    val previewInputs = mutableListOf<Map<String, Any?>>()
    var receivedCount = 0
    var injectedCount = 0
    var previewCount = 0
    var originalGeometry: Map<String, Any?>? = null
    fun storedField(value: Any?, name: String): Any? = value?.javaClass?.getDeclaredField(name)
        ?.apply { isAccessible = true }?.get(value)
    fun retainRecord(records: MutableList<Map<String, Any?>>, value: Map<String, Any?>, limit: Int) {
        if (records.size < limit) records += value else records[limit - 1] = value
    }
    fun diagnosticValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (key, item) -> put(key.toString(), diagnosticValue(item)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(diagnosticValue(it)) } }
        is Rect -> value.toShortString()
        is MovePoint -> JSONObject().put("x", value.x).put("y", value.y)
        is Transform64 -> JSONObject().put("x", value.x).put("y", value.y).put("width", value.width)
            .put("height", value.height).put("rotation", value.rotation).put("flipX", value.flipX).put("flipY", value.flipY)
        is MoveHandleOverlay -> JSONObject().put("handles", diagnosticValue(value.handles))
            .put("rotationHandle", diagnosticValue(value.rotationHandle)).put("screenScale", value.screenScale)
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }
    var successful = false
    try {
        click("Move / Transform (V)")
        waitUntil("Move rail did not select shipping mode") {
            automation.clearCache()
            val root = automation.rootInActiveWindow
            val controls = node(root, "Move Show Controls")
            val xField = node(root, "Move X value")
            var ready = false
            instrumentation.runOnMainSync {
                ready = canvas.toolMode == CanvasToolMode.MOVE && canvas.layerId == id &&
                    canvas.isAttachedToWindow && canvas.holder.surface.isValid && canvas.width > 0 && canvas.height > 0 &&
                    !canvas.isLayoutRequested && !activity.window.decorView.isLayoutRequested
            }
            ready && controls?.let { it.isVisibleToUser && it.isEnabled } == true &&
                xField?.let { it.isVisibleToUser && it.isEnabled } == true
        }
        waitUntil("Move fixture did not complete its GPU tile frame") {
            stats().let { it.currentContext && it.uploads > 0 && it.usesTiles && it.sceneGeneration == editor.currentSceneGeneration() }
        }
        val layoutReady = CountDownLatch(1)
        val layoutStopped = AtomicBoolean(false)
        var lastBounds: Rect? = null
        var stableFrames = 0
        val layoutFrame = object : Runnable {
            override fun run() {
                if (layoutStopped.get()) return
                val bounds = Rect()
                val ready = canvas.getGlobalVisibleRect(bounds) && canvas.isLaidOut && !canvas.isLayoutRequested &&
                    !activity.window.decorView.isLayoutRequested && canvas.holder.surface.isValid &&
                    canvas.holder.surfaceFrame.width() == canvas.width && canvas.holder.surfaceFrame.height() == canvas.height
                stableFrames = if (ready && bounds == lastBounds) stableFrames + 1 else 0
                lastBounds = bounds
                if (stableFrames >= 2) layoutReady.countDown() else canvas.postOnAnimation(this)
            }
        }
        instrumentation.runOnMainSync { canvas.postOnAnimation(layoutFrame) }
        try { check(layoutReady.await(15, TimeUnit.SECONDS)) { "Move fixture layout did not stabilize" } }
        finally { layoutStopped.set(true); instrumentation.runOnMainSync { canvas.removeCallbacks(layoutFrame) } }
        val warm = stats()
        val original = editor.state()
        data class InputGeometry(val scale: Double, val x: Float, val y: Float)
        var inputGeometry: InputGeometry? = null
        var geometryResult: Result<Unit>? = null
        instrumentation.runOnMainSync {
            geometryResult = runCatching {
                check(canvas.isAttachedToWindow && canvas.holder.surface.isValid && canvas.width > 0 && canvas.height > 0 &&
                    !canvas.isLayoutRequested && !activity.window.decorView.isLayoutRequested) { "Move body fixture layout is not ready" }
                val rect = Rect()
                check(canvas.getGlobalVisibleRect(rect))
                val location = IntArray(2).also(canvas::getLocationOnScreen)
                check(rect.width() == canvas.width && rect.height() == canvas.height &&
                    rect.left == location[0] && rect.top == location[1]) { "Move body fixture canvas is clipped" }
                val scale = canvas.zoomPercent / 100
                val box = original.layers.single().transform
                check(box == Transform64(80.0, 80.0, 16.0, 16.0))
                val overlay = canvas.moveOverlay(box)
                val center = MovePoint((overlay.handles[0].x + overlay.handles[4].x) / 2,
                    (overlay.handles[0].y + overlay.handles[4].y) / 2)
                // MotionEvent accepts Float screen coordinates. Target the displayed layer's body
                // from its actual viewport placement, then verify that the delivered Float point
                // remains strictly inside the document box and outside every resize/rotate target.
                val x = (location[0] + center.x).toFloat()
                val y = (location[1] + center.y).toFloat()
                val point = MovePoint((x - location[0]).toDouble(), (y - location[1]).toDouble())
                val documentPoint = MovePoint(box.x + (point.x - overlay.handles[0].x) / scale,
                    box.y + (point.y - overlay.handles[0].y) / scale)
                val legacyX = rect.exactCenterX() + ((88 - 128) * scale).toFloat()
                val legacyY = rect.exactCenterY() + ((88 - 128) * scale).toFloat()
                originalGeometry = mapOf("atNanos" to System.nanoTime(), "globalRect" to Rect(rect),
                    "screenX" to location[0], "screenY" to location[1], "width" to canvas.width,
                    "height" to canvas.height, "zoomPercent" to canvas.zoomPercent, "box" to box,
                    "projectionScale" to scale, "projectionX" to x, "projectionY" to y,
                    "projectionLocal" to point, "documentPoint" to documentPoint,
                    "overlayCenter" to center, "overlayHit" to overlay.hit(point),
                    "legacyProjectionLocal" to MovePoint(legacyX.toDouble() - location[0], legacyY.toDouble() - location[1]),
                    "overlay" to overlay, "stableLayoutFrames" to stableFrames)
                check(point.x >= 0 && point.x < canvas.width && point.y >= 0 && point.y < canvas.height &&
                    documentPoint.x > box.x && documentPoint.x < box.x + box.width &&
                    documentPoint.y > box.y && documentPoint.y < box.y + box.height && overlay.hit(point) == null) {
                    "Move displayed body center is clipped or overlaps a handle: ${diagnosticValue(originalGeometry)}"
                }
                inputGeometry = InputGeometry(scale, x, y)
                canvas.setOnTouchListener { _, event ->
                    // Return false even if diagnostics fail: the shipping Canvas owns every event.
                    synchronized(diagnosticLock) {
                        ++receivedCount
                        runCatching {
                            val location = IntArray(2).also(canvas::getLocationOnScreen)
                            val global = Rect().also(canvas::getGlobalVisibleRect)
                            val box = canvas.moveBox ?: original.layers.single().transform
                            val overlay = canvas.moveOverlay(box)
                            val pending = storedField(canvas, "pendingMovePress")
                            val gesture = storedField(canvas, "moveGesture")
                            // Read already stored objects; do not invoke controller or native commands.
                            val snapshot = storedField(storedField(canvas, "transforms"), "current") as? TransformController.Snapshot
                            val record = mapOf("atNanos" to System.nanoTime(), "ordinal" to receivedCount,
                                "action" to event.actionMasked, "eventTime" to event.eventTime, "downTime" to event.downTime,
                                "source" to event.source, "modifiers" to event.metaState, "pointerCount" to event.pointerCount,
                                "pointerId" to event.getPointerId(0), "localX" to event.x, "localY" to event.y,
                                "rawX" to event.rawX, "rawY" to event.rawY, "screenX" to location[0], "screenY" to location[1],
                                "globalRect" to global, "width" to canvas.width, "height" to canvas.height,
                                "zoomPercent" to canvas.zoomPercent, "attached" to canvas.isAttachedToWindow,
                                "windowFocus" to canvas.hasWindowFocus(), "moveInProgress" to canvas.moveInProgress,
                                "moveSettling" to canvas.moveSettling, "viewMoveBox" to canvas.moveBox,
                                "overlayBasis" to box, "overlay" to overlay,
                                "overlayHit" to overlay.hit(MovePoint(event.x.toDouble(), event.y.toDouble())),
                                "pendingExists" to (pending != null), "pendingDown" to storedField(pending, "down"),
                                "pendingHandle" to storedField(pending, "handle"), "pendingLastPoint" to storedField(pending, "lastPoint"),
                                "pendingModifiers" to storedField(pending, "modifiers"), "pendingEnded" to storedField(pending, "ended"),
                                "pendingViewportVersion" to storedField(pending, "viewportVersion"),
                                "gestureExists" to (gesture != null), "gestureOriginal" to storedField(gesture, "original"),
                                "gestureDown" to storedField(gesture, "down"), "gestureHandle" to storedField(gesture, "handle"),
                                "controllerOwner" to snapshot?.owner?.identity, "controllerPhase" to snapshot?.phase?.name,
                                "controllerInfoBox" to snapshot?.info?.box, "controllerAcceptedBox" to snapshot?.accepted?.box,
                                "controllerAcceptedSequence" to snapshot?.accepted?.sequence, "controllerAppliedSequence" to snapshot?.appliedSequence)
                            retainRecord(receivedInputs, record, 64)
                        }.onFailure { retainRecord(receivedInputs, mapOf("ordinal" to receivedCount, "diagnosticError" to it), 64) }
                    }
                    false
                }
            }
        }
        checkNotNull(geometryResult).getOrThrow()
        val (scale, x, y) = checkNotNull(inputGeometry)
        var down = SystemClock.uptimeMillis()
        fun pointer(action: Int, dx: Double, dy: Double, time: Long, modifiers: Int = KeyEvent.META_CTRL_ON) {
            val event = MotionEvent.obtain(down, down + time, action, x + (dx * scale).toFloat(), y + (dy * scale).toFloat(), modifiers)
            runCatching {
                ++injectedCount
                retainRecord(injectedInputs, mapOf("ordinal" to injectedCount, "atNanos" to System.nanoTime(),
                    "action" to event.actionMasked, "eventTime" to event.eventTime, "downTime" to event.downTime,
                    "source" to event.source, "modifiers" to event.metaState, "x" to event.x, "y" to event.y,
                    "constructionScale" to scale, "constructionX" to x, "constructionY" to y), 64)
            }
            instrumentation.sendPointerSync(event); event.recycle(); instrumentation.waitForIdleSync()
        }
        pointer(MotionEvent.ACTION_DOWN, 0.0, 0.0, 0)
        waitUntil("Move draft did not begin") { editor.hasActiveTransformDraft }
        val barrier = editor.commandDispatcher.barrier()
        check(!barrier.isDone && editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null))
        pointer(MotionEvent.ACTION_MOVE, 20.0, -10.0, 30)
        waitUntil("Move immutable preview did not update") {
            editor.captureDisplayScene()?.use {
                val transform = it.layerMetadata(0).transform
                runCatching {
                    ++previewCount
                    retainRecord(previewInputs, mapOf("ordinal" to previewCount, "atNanos" to System.nanoTime(),
                        "transform" to transform), 1024)
                }
                transform.x == 100.0
            } == true
        }
        check(editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null)) { "Move preview leaked into document/history" }
        // A different UP coordinate must not replace the last accepted DRAG.
        pointer(MotionEvent.ACTION_UP, 50.0, 50.0, 60)
        settled(); barrier.get(10, TimeUnit.SECONDS)
        val moved = editor.state()
        check(moved.revision == original.revision + 1 && moved.layers.single().transform == Transform64(100.0, 70.0, 16.0, 16.0))
        canvas.requestRender()
        waitUntil("GPU did not complete the committed Move scene") {
            stats().let { it.currentContext && it.usesTiles && it.completedFrames > warm.completedFrames &&
                it.sceneGeneration == editor.currentSceneGeneration() }
        }
        val afterMove = stats()
        check(afterMove.uploads == warm.uploads) { "Geometry-only Move reuploaded unchanged source tiles" }
        click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        waitUntil("Move Undo did not restore source placement") { editor.state().layers == original.layers }
        click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        waitUntil("Move Redo did not restore placement") { editor.state().layers == moved.layers }
        val beforeField = editor.state()

        // Real text semantics exercise live fractional preview and Return's one-step commit.
        click("Move X value")
        setFieldText("Move X value", "101.125")
        waitUntil("Move field did not preview its fraction") { canvas.moveBox?.x == 101.125 }
        check(editor.state().copy(selectedLayerActions = null) == beforeField.copy(selectedLayerActions = null)) { "Move text preview changed committed state before Return" }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        settled()
        check(editor.state().layers.single().transform.x == 101.125 && editor.currentRevision() == beforeField.revision + 1)
        val beforeRepeatedValue = editor.state()
        editor.captureCheckpointSnapshot().use {
            instrumentation.runOnMainSync { check(canvas.editMoveField(MoveField.X, 101.125)) }
            check(editor.state().copy(selectedLayerActions = null) == beforeRepeatedValue.copy(selectedLayerActions = null) && !editor.hasActiveTransformDraft && editor.activeTransformCompletion() == null) {
                "An unchanged Move field must not start another transform while saving"
            }
        }
        canvasKeyboardFocus()

        val beforeCancel = editor.state()
        click("Move X value")
        setFieldText("Move X value", "102.25")
        waitUntil("Move Escape fixture did not preview") { canvas.moveBox?.x == 102.25 }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
        settled(); check(editor.state().copy(selectedLayerActions = null) == beforeCancel.copy(selectedLayerActions = null))
        canvasKeyboardFocus()
        instrumentation.waitForIdleSync()
        val keyTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(keyTime, keyTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_SHIFT_ON))
        instrumentation.sendKeySync(KeyEvent(keyTime, keyTime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_SHIFT_ON))
        waitUntil("Shift arrow nudge did not commit") { editor.currentRevision() == beforeCancel.revision + 1 }
        settled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        waitUntil("Arrow nudge did not commit") { editor.currentRevision() == beforeCancel.revision + 2 }
        settled()
        check(editor.state().layers.single().transform == beforeCancel.layers.single().transform.copy(x = 111.125, y = 69.0))
        val beforeRetire = editor.state()
        // Inspector drafts survive view replacement; transient pointer drafts must cancel.
        var retireX = 0f
        var retireY = 0f
        instrumentation.runOnMainSync {
            val overlay = checkNotNull(canvas.moveOverlay(beforeRetire.layers.single().transform))
            val location = IntArray(2).also(canvas::getLocationOnScreen)
            retireX = location[0] + ((overlay.handles[0].x + overlay.handles[4].x) / 2).toFloat()
            retireY = location[1] + ((overlay.handles[0].y + overlay.handles[4].y) / 2).toFloat()
        }
        val retireDown = SystemClock.uptimeMillis()
        fun retirePointer(action: Int, dy: Double, elapsed: Long) {
            val event = MotionEvent.obtain(retireDown, retireDown + elapsed, action, retireX,
                retireY + (dy * canvas.zoomPercent / 100).toFloat(), KeyEvent.META_CTRL_ON)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        retirePointer(MotionEvent.ACTION_DOWN, 0.0, 0)
        waitUntil("Transient retirement Move did not begin") { canvas.moveInProgress && editor.hasActiveTransformDraft }
        check(!canvas.moveRetainsDraft && !canvas.moveFromFields && !canvas.movePersistent)
        retirePointer(MotionEvent.ACTION_MOVE, 200.0 - beforeRetire.layers.single().transform.y, 30)
        waitUntil("Transient retirement Move did not preview") { canvas.moveBox?.y == 200.0 }
        check(editor.state().copy(selectedLayerActions = null) == beforeRetire.copy(selectedLayerActions = null))
        instrumentation.runOnMainSync { canvas.retire() }
        retirePointer(MotionEvent.ACTION_UP, 0.0, 60)
        editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(editor.state().copy(selectedLayerActions = null) == beforeRetire.copy(selectedLayerActions = null) && !editor.hasActiveTransformDraft)
        successful = true
        return "Actual Move rail, real DOWN/DRAG/UP, committed-state isolation, terminal barrier, final DRAG semantics, GPU source tile reuse, UI Undo/Redo, real text fractional preview/Return commit, cancel, nudge and canvas retirement passed. No phone FPS/RSS/full Mac parity claim. Evidence=${evidence.name}"
    } catch (error: Exception) {
        captureEditorUiFailure(instrumentation, activity, error, emptyList())
        throw error
    } finally {
        runCatching {
            val received = synchronized(diagnosticLock) { receivedCount to receivedInputs.toList() }
            val diagnostic = mapOf("passed" to successful,
                "scope" to "Passive original-input diagnostic; no extra input, scene capture or controller command",
                "originalGeometry" to originalGeometry, "receivedCount" to received.first,
                "receivedInputs" to received.second, "injectedCount" to injectedCount, "injectedInputs" to injectedInputs,
                "previewCount" to previewCount, "previewInputs" to previewInputs,
                "eventRecordLimit" to 64, "previewRecordLimit" to 1024,
                "recordPolicy" to "First limit-1 records plus most recent record if the limit is exceeded")
            evidence.resolveSibling(evidence.name + "-input.json").writeText((diagnosticValue(diagnostic) as JSONObject).toString(2))
        }
        evidence.resolveSibling(evidence.name + ".json").writeText(JSONObject().put("passed", successful)
            .put("state", editor.state().toString()).put("activeDraft", editor.hasActiveTransformDraft)
            .put("pendingTransform", editor.activeTransformCompletion() != null).toString(2))
        automation.takeScreenshot()?.let { bitmap ->
            try { evidence.resolveSibling(evidence.name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        instrumentation.runOnMainSync { canvas.setOnTouchListener(null); canvas.cancelMove(); activity.finish() }
        instrumentation.waitForIdleSync()
        workspace.closeAsync().get(15, TimeUnit.SECONDS)
    }
}
