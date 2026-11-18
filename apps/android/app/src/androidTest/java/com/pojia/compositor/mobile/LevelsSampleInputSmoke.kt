package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Public Canvas/Activity dispatch in an isolated workspace; observes real sample dispatch without replacing Compose callbacks. */
internal fun levelsSampleInputFenceSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val token = "p139-levels-input-${System.nanoTime()}"
    val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
    val evidence = File(context.cacheDir, "p139-levels-input-${System.nanoTime()}").apply { check(mkdirs()) }
    val observations = JSONArray()
    var passed = false
    var activity: MainActivity? = null
    var workspace: AndroidWorkspace? = null
    fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        }
        error(message)
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    try {
        NativeEditor(32, addDefaultLayers = false).use { seed ->
            val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
            repeat(32 * 32) { buffer.put(byteArrayOf(60, 90, 110, -1)) }; buffer.rewind()
            seed.importPixels(32, 32, buffer)
            seed.save(File(directory, "current.checkpoint"))
        }
        val screen = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
        activity = screen
        val retained = screen.retainedWorkspaceForTest()
        workspace = retained
        main { screen.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        waitFor("Levels input isolated editor did not attach") {
            main { findCanvas(screen.window.decorView)?.holder?.surface?.isValid == true &&
                screen.retainedEditorForTest()?.state()?.layers?.size == 1 && screen.typedLayerTestAccessForTest() != null }
        }
        val editor = checkNotNull(screen.retainedEditorForTest())
        val canvas = main { checkNotNull(findCanvas(screen.window.decorView)) }
        check(retained.recoveryDirectoryForTest().canonicalFile == directory.canonicalFile)
        fun access() = checkNotNull(screen.typedLayerTestAccessForTest())
        fun draft() = main { checkNotNull(access().adjustment()) }
        fun pixels(): ByteArray {
            val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
            check(editor.draw(32, 32, 0.0, 0.0, 32.0, 32.0, buffer))
            return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
        }
        var baseline = editor.state()
        val originalPixels = pixels()
        val foreground = main { canvas.foregroundColor }
        fun drain() {
            val latch = CountDownLatch(1)
            canvas.queueEvent { latch.countDown() }
            check(latch.await(10, TimeUnit.SECONDS)) { "Levels input GL queue did not drain" }
            editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
        }
        fun unchangedDocument(name: String) {
            drain()
            check(editor.state() == baseline) { "$name changed document/history" }
            check(pixels().contentEquals(originalPixels)) { "$name changed source pixels" }
            check(!editor.hasActiveStrokes && !editor.hasPendingEdits && !editor.hasActiveTransformDraft) {
                "$name left a native editing owner"
            }
            check(main { canvas.foregroundColor } == foreground) { "$name changed foreground palette" }
        }
        fun update(value: AdjustmentEditorDraft) {
            main { access().updateAdjustment(value) }
            waitFor("Levels sample context did not follow draft") { main { canvas.levelsSampleContext == value } }
        }
        fun sampleReady() {
            waitFor("Levels sample callback did not settle/rebind") { main { canvas.levelsSamplePoint != null } }
        }
        var downTime = SystemClock.uptimeMillis()
        var eventTime = downTime
        fun event(action: Int, positions: List<Pair<Float, Float>>, mouse: Boolean = false,
            buttons: Int = 0, actionButton: Int = 0): MotionEvent {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            eventTime = maxOf(SystemClock.uptimeMillis(), eventTime + 1)
            return MotionEvent.obtain(downTime, eventTime, action, positions.size,
                positions.indices.map { index -> MotionEvent.PointerProperties().apply {
                    id = index; toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray(), positions.map { point -> MotionEvent.PointerCoords().apply {
                    x = point.first; y = point.second; pressure = 1f; size = 1f
                } }.toTypedArray(), 0, buttons, 1f, 1f, 0, 0,
                if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN, 0).also { value ->
                if (actionButton != 0) {
                    // SDK fixture construction only; this selector needs am instrument --no-hidden-api-checks.
                    MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
                        .invoke(value, actionButton)
                }
            }
        }
        fun dispatch(value: MotionEvent) {
            try { main {
                val generic = value.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || value.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
                check(if (generic) canvas.dispatchGenericMotionEvent(value) else canvas.dispatchTouchEvent(value)) {
                    "Canvas rejected Levels event ${value.actionMasked}"
                }
            } } finally { value.recycle() }
            instrumentation.waitForIdleSync()
        }
        fun center() = main { canvas.width / 2f to canvas.height / 2f }
        fun touch(action: Int, position: Pair<Float, Float> = center()) = dispatch(event(action, listOf(position)))
        // Preview completion can recompose and replace the production callback between
        // DOWN and UP. Read the Canvas dispatch counter instead of overwriting that callback.
        fun callbacks() = main { canvas.levelsSampleDeliveriesForTest() }
        fun observeRealCallback() = sampleReady()
        fun reject(name: String, gesture: () -> Unit) {
            val before = draft()
            val beforeCallbacks = callbacks()
            observeRealCallback()
            gesture()
            drain()
            // The fixed 32x32 source has no long native work; allow a posted request to become visible.
            SystemClock.sleep(100)
            sampleReady()
            check(draft() == before) { "$name calibrated the draft" }
            check(callbacks() == beforeCallbacks) { "$name invoked a sample callback" }
            unchangedDocument(name)
            observations.put(JSONObject().put("case", name).put("sampleCallbacks", 0).put("documentUnchanged", true))
        }
        fun key(code: Int, modifiers: Int = 0) {
            val now = SystemClock.uptimeMillis()
            main {
                screen.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, modifiers))
                screen.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0, modifiers))
            }
            drain()
        }
        // Start with Move selected so the fence proves that its arrow/Transform handlers are disabled.
        key(KeyEvent.KEYCODE_V)
        waitFor("Move tool was not selected before Levels") { main { canvas.toolMode == CanvasToolMode.MOVE } }
        main { access().beginAdjustment(AdjustmentKind.LEVELS) }
        waitFor("Levels edit fence was not bound") { main { canvas.levelsEditing && canvas.toolMode == CanvasToolMode.IDLE &&
            access().adjustment()?.layerId != null && !access().busy() } }
        val created = editor.state()
        check(created.layers.size == baseline.layers.size + 1 && created.revision == baseline.revision + 1)
        check(created.layers.any { it.id == draft().layerId })
        baseline = created
        check(draft().levelsSampleMode == null && main { canvas.levelsSamplePoint == null })
        val noMode = draft()
        touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
        drain(); check(draft() == noMode)
        unchangedDocument("no-mode tap")
        observations.put(JSONObject().put("case", "no-mode tap").put("documentUnchanged", true))
        update(draft().toggleLevelsSample(LevelsSampleMode.BLACK))
        reject("ACTION_CANCEL") { touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_CANCEL) }
        reject("drag beyond touch slop") {
            val start = center(); val end = start.first + 100f to start.second
            touch(MotionEvent.ACTION_DOWN, start); touch(MotionEvent.ACTION_MOVE, end); touch(MotionEvent.ACTION_UP, end)
        }
        val contextCases = listOf("mode change", "draft value change", "viewport change", "focus callback loss")
        for (name in contextCases) {
            val beforeCallbacks = callbacks()
            observeRealCallback()
            val point = center()
            touch(MotionEvent.ACTION_DOWN, point)
            when (name) {
                "mode change" -> update(draft().toggleLevelsSample(LevelsSampleMode.GRAY))
                "draft value change" -> update(draft().withLevelsInput(LevelsParameter.BLACK.ordinal, "12.5"))
                "viewport change" -> main { canvas.zoomBy(.75) }
                else -> main { canvas.onWindowFocusChanged(false); canvas.onWindowFocusChanged(true) }
            }
            val expected = draft()
            touch(MotionEvent.ACTION_UP, point)
            drain(); SystemClock.sleep(100); sampleReady()
            check(draft() == expected && callbacks() == beforeCallbacks) { "$name did not discard an old tap" }
            unchangedDocument(name)
            observations.put(JSONObject().put("case", name).put("sampleCallbacks", 0).put("documentUnchanged", true))
        }
        val oldSubmission = draft().submissionId
        val replacementCallbacks = callbacks()
        observeRealCallback()
        val oldPoint = center()
        touch(MotionEvent.ACTION_DOWN, oldPoint)
        main { access().cancelAdjustment(); access().editAdjustment() }
        waitFor("Replacement Levels draft did not attach") {
            main { access().adjustment()?.submissionId != oldSubmission && access().adjustment() != null }
        }
        update(draft().toggleLevelsSample(LevelsSampleMode.BLACK))
        val replacement = draft()
        touch(MotionEvent.ACTION_UP, oldPoint)
        drain(); SystemClock.sleep(100); sampleReady()
        check(draft() == replacement && callbacks() == replacementCallbacks) { "Old tap reached replacement editor" }
        unchangedDocument("editor replacement")
        observations.put(JSONObject().put("case", "editor replacement").put("sampleCallbacks", 0).put("documentUnchanged", true))
        update(draft().resetLevels().toggleLevelsSample(LevelsSampleMode.BLACK))
        reject("two-finger navigation") {
            val middle = center(); val scale = main { canvas.scaleForOverlay }
            val left = middle.first - 40f to middle.second
            val right = middle.first + 40f to middle.second
            touch(MotionEvent.ACTION_DOWN, left)
            dispatch(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(left, right)))
            val movedLeft = middle.first - 30f to middle.second
            val movedRight = middle.first + 30f to middle.second
            dispatch(event(MotionEvent.ACTION_MOVE, listOf(movedLeft, movedRight)))
            dispatch(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(movedLeft, movedRight)))
            touch(MotionEvent.ACTION_UP, movedLeft)
            check(main { canvas.scaleForOverlay } != scale) { "Two-finger navigation did not change viewport" }
        }
        reject("middle-button pan") {
            val point = center(); val offset = main { canvas.offsetXForOverlay to canvas.offsetYForOverlay }
            dispatch(event(MotionEvent.ACTION_DOWN, listOf(point), mouse = true, buttons = MotionEvent.BUTTON_TERTIARY))
            val moved = point.first + 30f to point.second + 18f
            dispatch(event(MotionEvent.ACTION_MOVE, listOf(moved), mouse = true, buttons = MotionEvent.BUTTON_TERTIARY))
            dispatch(event(MotionEvent.ACTION_UP, listOf(moved), mouse = true))
            check(main { canvas.offsetXForOverlay to canvas.offsetYForOverlay } != offset) { "Middle-button pan did not change viewport" }
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
        main {
            val now = SystemClock.uptimeMillis()
            screen.dispatchKeyShortcutEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T, 0, KeyEvent.META_META_ON))
            screen.dispatchKeyShortcutEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T, 0, KeyEvent.META_META_ON))
        }
        drain()
        check(main { !canvas.keyboardMoveEnabled && !canvas.moveInProgress && !canvas.distortSessionActive })
        unchangedDocument("arrow and Ctrl/Meta-T fence")
        observations.put(JSONObject().put("case", "Activity arrow/Ctrl-T and direct Meta-T shortcut callbacks()").put("documentUnchanged", true))
        // A real primary DOWN, another held button, and generic primary release must sample exactly once.
        main { canvas.fit() }; drain(); sampleReady()
        val beforeSample = draft()
        val count = callbacks()
        observeRealCallback()
        val point = center()
        dispatch(event(MotionEvent.ACTION_DOWN, listOf(point), mouse = true, buttons = MotionEvent.BUTTON_PRIMARY))
        dispatch(event(MotionEvent.ACTION_BUTTON_PRESS, listOf(point), mouse = true,
            buttons = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_TERTIARY, actionButton = MotionEvent.BUTTON_TERTIARY))
        dispatch(event(MotionEvent.ACTION_BUTTON_RELEASE, listOf(point), mouse = true,
            buttons = MotionEvent.BUTTON_TERTIARY, actionButton = MotionEvent.BUTTON_PRIMARY))
        waitFor("Generic primary release did not calibrate through production sample closure") { draft().style.ranges != beforeSample.style.ranges }
        sampleReady()
        check(callbacks() == count + 1 && draft().levelsSampleMode == LevelsSampleMode.BLACK)
        check(draft().style.ranges[1].black > 0 && draft().style.ranges[2].black > 0 && draft().style.ranges[3].black > 0)
        dispatch(event(MotionEvent.ACTION_BUTTON_RELEASE, listOf(point), mouse = true, actionButton = MotionEvent.BUTTON_TERTIARY))
        unchangedDocument("generic primary release sample")
        observations.put(JSONObject().put("case", "mouse generic primary UP").put("sampleCallbacks", 1)
            .put("calibratedThreeChannels", true).put("documentUnchanged", true))
        // Desktop active Levels eyedroppers precede selected Hand/Zoom tools; Space remains temporary pan.
        for ((code, mode) in listOf(KeyEvent.KEYCODE_H to LevelsSampleMode.GRAY, KeyEvent.KEYCODE_Z to LevelsSampleMode.WHITE)) {
            update(draft().resetLevels().toggleLevelsSample(mode))
            key(code)
            val selectedTool = if (code == KeyEvent.KEYCODE_H) CanvasToolMode.HAND else CanvasToolMode.ZOOM
            waitFor("Selected $selectedTool and active $mode did not bind before sampling") {
                main { canvas.toolMode == selectedTool && canvas.levelsSampleContext == access().adjustment() &&
                    canvas.levelsSampleContext?.levelsSampleMode == mode && canvas.levelsSamplePoint != null &&
                    !canvas.levelsTemporaryHand && !canvas.moveContextPending }
            }
            val before = draft(); val previousCount = callbacks()
            observeRealCallback()
            val samplePoint = center()
            fun inputState() = main {
                JSONObject().put("tool", canvas.toolMode.name).put("mode", canvas.levelsSampleContext?.levelsSampleMode?.name)
                    .put("submission", canvas.levelsSampleContext?.submissionId).put("callbackReady", canvas.levelsSamplePoint != null)
                    .put("deliveries", canvas.levelsSampleDeliveriesForTest()).put("temporaryHand", canvas.levelsTemporaryHand)
                    .put("width", canvas.width).put("height", canvas.height).put("scale", canvas.scaleForOverlay)
                    .put("offsetX", canvas.offsetXForOverlay).put("offsetY", canvas.offsetYForOverlay)
                    .put("sampleViewX", samplePoint.first).put("sampleViewY", samplePoint.second)
                    .put("sampleDocumentX", (samplePoint.first - canvas.offsetXForOverlay) / canvas.scaleForOverlay)
                    .put("sampleDocumentY", (samplePoint.second - canvas.offsetYForOverlay) / canvas.scaleForOverlay)
            }
            val sampleRecord = JSONObject().put("case", "active $mode before $selectedTool")
                .put("beforeDown", inputState()).put("beforeRanges", before.style.ranges.toString())
            observations.put(sampleRecord)
            touch(MotionEvent.ACTION_DOWN, samplePoint)
            sampleRecord.put("afterDown", inputState())
            touch(MotionEvent.ACTION_UP, samplePoint)
            sampleRecord.put("afterUp", inputState())
            waitFor("Active $mode did not precede Hand/Zoom") { draft().style.ranges != before.style.ranges }
            sampleReady(); check(callbacks() == previousCount + 1) { "Sample $mode deliveries: before=$previousCount after=${callbacks()}" }
            sampleRecord.put("calibratedRanges", draft().style.ranges.toString()).put("sampleCallbacks", 1)
            unchangedDocument("active sample before Hand/Zoom")
        }
        val beforeSpace = draft(); val spaceCount = callbacks()
        observeRealCallback()
        main { screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)) }
        waitFor("Space did not enter temporary Hand") { main { canvas.levelsTemporaryHand && canvas.toolMode == CanvasToolMode.HAND } }
        val spacePoint = center(); val spaceOffset = main { canvas.offsetXForOverlay }
        touch(MotionEvent.ACTION_DOWN, spacePoint)
        touch(MotionEvent.ACTION_MOVE, spacePoint.first + 20f to spacePoint.second)
        touch(MotionEvent.ACTION_UP, spacePoint.first + 20f to spacePoint.second)
        main { screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)) }
        drain()
        check(draft() == beforeSpace && callbacks() == spaceCount && main { canvas.offsetXForOverlay } != spaceOffset)
        unchangedDocument("Space temporary Hand")
        observations.put(JSONObject().put("case", "active sample precedes Hand/Zoom; Space pans").put("documentUnchanged", true))
        main { access().cancelAdjustment() }
        waitFor("Cancel did not release Levels input context") { main { !canvas.levelsEditing } }
        unchangedDocument("editor Cancel")
        passed = true
    } catch (failure: Throwable) {
        File(evidence, "failure.txt").writeText(failure.stackTraceToString())
        throw failure
    } finally {
        File(evidence, "results.json").writeText(JSONObject().put("passed", passed).put("observations", observations)
            .put("dispatchScope", "Public Canvas touch/generic MotionEvent and Activity key callbacks(); focus callback simulated, not system focus loss or physical mouse hardware")
            .toString(2))
        activity?.let { main { it.finish() } }
        workspace?.closeAsync()?.get(15, TimeUnit.SECONDS)
        check(directory.deleteRecursively())
    }
    return "Levels input fence: no-mode/cancel/drag/context/viewport/focus discard; two-finger and middle navigation; generic primary release samples once through production closure; arrow/Ctrl-T/Meta-T blocked; exact document/history/pixels and foreground unchanged. Evidence=${evidence.path}"
}
