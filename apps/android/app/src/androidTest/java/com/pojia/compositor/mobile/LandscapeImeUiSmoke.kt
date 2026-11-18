package com.pojia.compositor.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Real IME resize, public field input and exact history in a private recovery workspace. */
internal fun landscapeImeUiSmoke(instrumentation: Instrumentation, languageTag: String): String {
    val context = instrumentation.targetContext
    val language = when (languageTag) {
        "en" -> AppLanguage.ENGLISH
        "zh-CN" -> AppLanguage.SIMPLIFIED_CHINESE
        else -> error("landscapeImeLanguage must be en or zh-CN")
    }
    val previousLanguage = AppLanguages.current(context)
    val automation = editorUiAutomation(instrumentation)
    val token = "p144-landscape-ime-${System.nanoTime()}"
    check(validatedDebugWorkspaceToken(token) == token)
    val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
    val evidence = File(context.filesDir, "$token-evidence").apply { check(mkdirs()) }
    val records = JSONArray()
    val report = JSONObject().put("selector", "landscapeImeUi").put("language", languageTag)
        .put("workspace", token).put("passed", false).put("stages", records)
        .put("input", "real touch focus, system IME visible, public ACTION_SET_TEXT, real Enter key")
        .put("ime", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        .put("imeLanguageScope", "App locale only; this selector does not change the keyboard subtype or claim Chinese composing coverage")
    var activity: MainActivity? = null
    var workspace: AndroidWorkspace? = null
    var canvas: CanvasView? = null
    var editor: NativeEditor? = null
    var primaryFailure: Throwable? = null
    var successResult: String? = null
    var windowSelection = JSONObject()
    var requireMoveField = false
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        error(message)
    }
    fun root(): AccessibilityNodeInfo? {
        automation.clearCache()
        val service = automation.serviceInfo
        if (service.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
        }
        val host = checkNotNull(activity)
        val windows = JSONArray()
        val candidates = ArrayList<AccessibilityNodeInfo>()
        for (window in automation.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()) {
            val candidate = window.root
            val packageMatches = candidate?.packageName?.toString() == context.packageName
            val hasCanvas = findEditorUiNode(candidate, host.getString(R.string.ui_canvas)) != null
            val hasX = findEditorUiNode(candidate, host.getString(R.string.ui_move_field_value, "X")) != null
            val hasEditorChrome = findEditorUiNode(candidate, host.getString(R.string.ui_file)) != null &&
                findEditorUiNode(candidate, host.getString(R.string.ui_undo)) != null
            // Text cursor handles are same-package APPLICATION windows. Only the complete
            // editor tree may receive actions, never the topmost same-package popup.
            val accepted = window.displayId == Display.DEFAULT_DISPLAY &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION && packageMatches &&
                hasCanvas && (hasX || (!requireMoveField && hasEditorChrome))
            windows.put(JSONObject().put("id", window.id).put("displayId", window.displayId)
                .put("type", window.type).put("layer", window.layer).put("title", window.title ?: JSONObject.NULL)
                .put("active", window.isActive).put("focused", window.isFocused)
                .put("bounds", Rect().also(window::getBoundsInScreen).toShortString())
                .put("package", candidate?.packageName ?: JSONObject.NULL).put("packageMatches", packageMatches)
                .put("hasCanvas", hasCanvas).put("hasMoveX", hasX).put("hasEditorChrome", hasEditorChrome)
                .put("accepted", accepted))
            if (accepted) candidates.add(checkNotNull(candidate))
        }
        windowSelection = JSONObject().put("displayId", Display.DEFAULT_DISPLAY).put("package", context.packageName)
            .put("requiresMoveX", requireMoveField).put("candidateCount", candidates.size).put("windows", windows)
        report.put("lastWindowSelection", windowSelection)
        check(candidates.size <= 1) { "Ambiguous editor Activity roots on physical display 0: $windowSelection" }
        return candidates.singleOrNull()
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun describe(node: AccessibilityNodeInfo?): Any = node?.let {
        JSONObject().put("text", it.text ?: JSONObject.NULL).put("description", it.contentDescription ?: JSONObject.NULL)
            .put("bounds", Rect().also(it::getBoundsInScreen).toShortString()).put("focused", it.isFocused)
            .put("editable", it.isEditable).put("visible", it.isVisibleToUser).put("enabled", it.isEnabled)
            .put("actions", JSONArray(it.actionList.map { action -> action.id }))
    } ?: JSONObject.NULL
    fun xField(editorRoot: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        val host = checkNotNull(activity)
        var current = findEditorUiNode(editorRoot, host.getString(R.string.ui_move_field_value, "X"))
        while (current != null && (!current.isEditable || current.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }))
            current = current.parent
        return current
    }
    fun geometry(): JSONObject {
        val result = JSONObject()
        instrumentation.runOnMainSync {
            val host = checkNotNull(activity)
            val decor = host.window.decorView
            val frame = Rect().also(decor::getWindowVisibleDisplayFrame)
            val rootBounds = Rect().also { decor.getGlobalVisibleRect(it) }
            val view = canvas
            val visible = Rect()
            val shown = view?.getGlobalVisibleRect(visible) == true
            val unobscured = Rect(visible)
            if (!unobscured.intersect(frame)) unobscured.setEmpty()
            val origin = IntArray(2).also { view?.getLocationOnScreen(it) }
            val insets = decor.rootWindowInsets
            result.put("orientation", host.resources.configuration.orientation).put("rotation", host.display?.rotation)
                .put("displayId", host.display?.displayId).put("density", host.resources.displayMetrics.density.toDouble())
                .put("fontScale", host.resources.configuration.fontScale.toDouble())
                .put("windowBounds", host.windowManager.currentWindowMetrics.bounds.toShortString())
                .put("rootBounds", rootBounds.toShortString()).put("rootWidth", decor.width).put("rootHeight", decor.height)
                .put("visibleFrame", frame.toShortString()).put("imeVisible", insets?.isVisible(WindowInsets.Type.ime()) == true)
                .put("imeInsets", insets?.getInsets(WindowInsets.Type.ime())?.toString() ?: JSONObject.NULL)
                .put("systemBarInsets", insets?.getInsets(WindowInsets.Type.systemBars())?.toString() ?: JSONObject.NULL)
                .put("windowFocus", host.hasWindowFocus()).put("viewFocus", decor.findFocus()?.javaClass?.name ?: JSONObject.NULL)
                .put("canvasVisible", shown).put("canvasWidth", view?.width ?: 0).put("canvasHeight", view?.height ?: 0)
                .put("canvasBounds", visible.toShortString()).put("canvasUnobscuredBounds", unobscured.toShortString())
                .put("canvasVisibleHeightDp", unobscured.height() / host.resources.displayMetrics.density.toDouble())
                .put("canvasVisibleWidthDp", unobscured.width() / host.resources.displayMetrics.density.toDouble())
                .put("canvasOrigin", JSONArray(origin.toList())).put("canvasLayoutRequested", view?.isLayoutRequested)
                .put("surfaceValid", view?.holder?.surface?.isValid == true)
                .put("surfaceFrame", view?.holder?.surfaceFrame?.toShortString() ?: JSONObject.NULL)
                .put("surfaceMatchesCanvas", view != null && view.holder.surfaceFrame.width() == view.width &&
                    view.holder.surfaceFrame.height() == view.height)
                .put("moveBox", view?.moveBox?.toString() ?: JSONObject.NULL)
                .put("moveInProgress", view?.moveInProgress).put("moveSettling", view?.moveSettling)
                .put("canvasKeyboardFocusActive", view?.keyboardFocusActive == true)
                .put("canvasNativeFocus", view?.hasFocus() == true).put("canvasWindowFocus", view?.hasWindowFocus() == true)
                .put("canvasKeyboardMoveEnabled", view?.keyboardMoveEnabled == true)
                .put("moveViewportVersion", view?.let { target ->
                    runCatching {
                        CanvasView::class.java.getDeclaredField("moveViewportVersion\$delegate").let { field ->
                            field.isAccessible = true; field.get(target).toString()
                        }
                    }.getOrNull()
                } ?: JSONObject.NULL)
        }
        return result
    }
    fun source(): Pair<List<Long>, String> = checkNotNull(checkNotNull(editor).captureDisplayScene()).use { scene ->
        val layer = scene.layerMetadata(0)
        check(layer.tileCount == 1)
        val buffer = ByteBuffer.allocateDirect(layer.pixelWidth * layer.pixelHeight * 4)
        scene.readTile(0, 0, layer.pixelWidth * 4, buffer)
        val bytes = ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
        scene.tileViewsMetadata(0).map { it.payloadResourceId } to moveInspectorSha(bytes)
    }
    fun capture(stage: String): JSONObject {
        val selectedRoot = root()
        val record = geometry().put("stage", stage).put("field", describe(xField(selectedRoot)))
            .put("windowSelection", windowSelection)
        editor?.let { model ->
            val state = model.state()
            val identity = source()
            record.put("state", state.toString()).put("revision", state.revision)
                .put("sourceIdentity", JSONArray(identity.first)).put("sourcePixelsSha256", identity.second)
                .put("owner", model.activeTransformCompletion()?.let(System::identityHashCode) ?: JSONObject.NULL)
        }
        var count = 0
        fun tree(node: AccessibilityNodeInfo, depth: Int): JSONObject {
            val result = describe(node) as JSONObject
            count++
            val children = JSONArray()
            for (index in 0 until node.childCount) {
                if (count >= 2048 || depth >= 64) { result.put("truncated", true); break }
                node.getChild(index)?.let { children.put(tree(it, depth + 1)) }
            }
            return result.put("children", children)
        }
        File(evidence, "$stage-tree.json").writeText(selectedRoot?.let { tree(it, 0) }?.toString(2) ?: "null")
        val bitmap = checkNotNull(automation.takeScreenshot()) { "Required screenshot unavailable: $stage" }
        val screenshot = File(evidence, "$stage.png")
        record.put("screenshotWidth", bitmap.width).put("screenshotHeight", bitmap.height).put("screenshot", screenshot.name)
        try { screenshot.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        check(screenshot.isFile && screenshot.length() > 0) { "Required screenshot is empty: $stage" }
        records.put(record)
        File(evidence, "report.json").writeText(report.toString(2))
        return record
    }
    fun stableGeometry() {
        var previous: String? = null
        var matches = 0
        waitFor("Landscape layout did not settle") {
            val value = geometry()
            val fingerprint = listOf("rootHeight", "visibleFrame", "canvasBounds", "canvasHeight", "imeVisible", "surfaceFrame")
                .joinToString("|") { value.opt(it).toString() }
            matches = if (fingerprint == previous && !value.optBoolean("canvasLayoutRequested")) matches + 1 else 0
            previous = fingerprint
            matches >= 3
        }
    }
    fun usable(record: JSONObject, ime: Boolean, focused: Boolean = false) {
        check(record.getJSONObject("windowSelection").getInt("candidateCount") == 1) {
            "Expected exactly one complete editor Activity root: ${record.getJSONObject("windowSelection")}"
        }
        check(record.getInt("orientation") == Configuration.ORIENTATION_LANDSCAPE && record.getBoolean("windowFocus")) {
            "Expected focused effective landscape configuration: $record"
        }
        check(record.getBoolean("imeVisible") == ime) { "Expected IME visible=$ime: $record" }
        check(record.getBoolean("canvasVisible") && record.getDouble("canvasVisibleHeightDp") >= 64.0 &&
            record.getDouble("canvasVisibleWidthDp") >= 64.0 && record.getBoolean("surfaceValid") &&
            record.getBoolean("surfaceMatchesCanvas")) { "Landscape Canvas must remain visible at least 64dp with a matching surface: $record" }
        if (focused) {
            val field = checkNotNull(xField()) { "Move X editable field missing during IME" }
            val bounds = Rect().also(field::getBoundsInScreen)
            val frame = Rect()
            instrumentation.runOnMainSync { checkNotNull(activity).window.decorView.getWindowVisibleDisplayFrame(frame) }
            check(field.isFocused && field.isEnabled && field.isVisibleToUser && !bounds.isEmpty && frame.contains(bounds)) {
                "Focused Move X field must stay completely on screen: bounds=$bounds frame=$frame"
            }
        }
    }
    fun click(label: String) {
        revealEditorControl(instrumentation, label)
        waitFor("Control missing: $label") { findEditorUiNode(root(), label)?.isVisibleToUser == true }
        check(clickEditorUiNode(findEditorUiNode(root(), label))) { "Public control click rejected: $label" }
        instrumentation.waitForIdleSync()
    }
    try {
        instrumentation.runOnMainSync { AppLanguages.set(context, language) }
        NativeEditor(256, false).use { seed ->
            val id = seed.importPixels(40, 20, ByteBuffer.allocateDirect(40 * 20 * 4).apply {
                put(moveInspectorColorPixels(40, 20)); rewind()
            })
            seed.setTransform(id, Transform64(10.0, 20.0, 80.0, 60.0))
            seed.selectLayer(id)
            seed.save(File(directory, "current.checkpoint"))
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
        val host = checkNotNull(activity)
        workspace = host.retainedWorkspaceForTest()
        check(checkNotNull(workspace).recoveryDirectoryForTest().canonicalFile == directory.canonicalFile &&
            host.display?.displayId == Display.DEFAULT_DISPLAY) { "Refusing to edit outside the isolated default-display workspace" }
        instrumentation.runOnMainSync { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor("Landscape editor did not load") {
            instrumentation.runOnMainSync { canvas = findCanvas(host.window.decorView) }
            editor = host.retainedEditorForTest()
            host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                !checkNotNull(workspace).snapshot().loading && editor != null && canvas != null &&
                checkNotNull(canvas).width > 0 && checkNotNull(canvas).height > 0
        }
        click(host.getString(R.string.ui_tool_shortcut, host.toolTitle(EditorTool.MOVE), "V"))
        waitFor("Move tool did not activate") { checkNotNull(canvas).toolMode == CanvasToolMode.MOVE }
        requireMoveField = true
        stableGeometry()
        val baselineGeometry = capture("baseline")
        usable(baselineGeometry, ime = false)
        val model = checkNotNull(editor)
        val before = model.state()
        val original = before.layers.single().transform
        val sourceBefore = source()
        check(original.x != 42.0)
        val field = checkNotNull(xField()) { "Move X field unavailable before input" }
        val bounds = Rect().also(field::getBoundsInScreen)
        check(field.isVisibleToUser && field.isEnabled && !bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 30)) {
            val event = MotionEvent.obtain(down, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        waitFor("Real tap did not focus Move X and show the system IME") {
            xField()?.isFocused == true && geometry().getBoolean("imeVisible")
        }
        stableGeometry()
        val imeGeometry = capture("ime-visible")
        usable(imeGeometry, ime = true, focused = true)
        check(imeGeometry.getJSONArray("canvasOrigin").toString() == baselineGeometry.getJSONArray("canvasOrigin").toString()) {
            "Showing the IME changed the Canvas screen origin"
        }
        check(checkNotNull(xField()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "42")
        })) { "Move X rejected the public text action" }
        val expected = original.copy(x = 42.0)
        waitFor("Move X native preview did not reach exactly 42") {
            checkNotNull(canvas).moveBox == expected && model.serialized {
                model.hasActiveTransformDraft && model.captureDisplayScene()?.use { it.layerMetadata(0).transform == expected } == true
            }
        }
        usable(capture("typed-preview"), ime = true, focused = true)
        check(model.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && source() == sourceBefore) {
            "Typing preview changed committed state, history, source identity or pixels"
        }
        val owner = checkNotNull(model.activeTransformCompletion())
        val time = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        instrumentation.sendKeySync(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
        waitFor("Field completion did not settle, dismiss IME, and return keyboard focus to Canvas") {
            val observed = geometry()
            model.activeTransformCompletion() == null && !checkNotNull(canvas).moveInProgress &&
                !model.hasActiveTransformDraft && !observed.getBoolean("imeVisible") &&
                observed.getBoolean("canvasKeyboardFocusActive") && observed.getBoolean("canvasWindowFocus") &&
                observed.getBoolean("canvasKeyboardMoveEnabled")
        }
        stableGeometry()
        val committedGeometry = capture("committed")
        usable(committedGeometry, ime = false)
        check(committedGeometry.getBoolean("canvasKeyboardFocusActive") && committedGeometry.getBoolean("canvasWindowFocus") &&
            committedGeometry.getBoolean("canvasKeyboardMoveEnabled")) { "Enter did not restore the Canvas keyboard target" }
        check(committedGeometry.getJSONArray("canvasOrigin").toString() == baselineGeometry.getJSONArray("canvasOrigin").toString()) {
            "Dismissing the IME changed the Canvas screen origin"
        }
        check(model.state().revision == before.revision + 1 && model.state().layers.single().transform == expected && source() == sourceBefore) {
            "Done must publish exactly X=42 in one revision without changing source pixels"
        }
        click(host.getString(R.string.ui_undo)); model.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(model.state().layers == before.layers && model.state().redo && source() == sourceBefore) { "Undo did not restore the original transform/source" }
        capture("undo")
        click(host.getString(R.string.ui_redo)); model.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(model.state().layers.single().transform == expected && source() == sourceBefore) { "Redo did not restore X=42/source" }
        usable(capture("redo"), ime = false)
        report.put("functionalPassed", true)
        successResult = "PASS landscapeImeUi language=$languageTag; real IME Canvas>=64dp, visible focused X, native preview42 without publication, Enter revision+1 and Canvas keyboard focus, exact Undo/Redo, source identity/pixels unchanged; evidence=files/${evidence.name}"
    } catch (failure: Throwable) {
        primaryFailure = failure
        report.put("failure", failure.toString())
        runCatching { if (activity != null) capture("failure") }.exceptionOrNull()?.let {
            report.put("captureFailure", it.toString())
        }
    } finally {
        val cleanupFailures = JSONArray()
        fun cleanup(name: String, action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let { failure ->
                cleanupFailures.put(JSONObject().put("stage", name).put("failure", failure.toString()))
                val prior = primaryFailure
                if (prior == null) primaryFailure = failure else prior.addSuppressed(failure)
            }
        }
        cleanup("cancel") {
            editor?.activeTransformCompletion()?.let { owner ->
                instrumentation.runOnMainSync { canvas?.cancelMove() }
                check(owner.get(10, TimeUnit.SECONDS).error == null) { "Cleanup transform termination failed" }
            }
        }
        cleanup("close") {
            instrumentation.runOnMainSync { activity?.finishAndRemoveTask() }
            instrumentation.waitForIdleSync()
            workspace?.closeAsync()?.get(15, TimeUnit.SECONDS)
        }
        cleanup("restoreLanguage") { instrumentation.runOnMainSync { AppLanguages.set(context, previousLanguage) } }
        report.put("cleanupFailures", cleanupFailures).put("cleanupPassed", cleanupFailures.length() == 0)
            .put("passed", primaryFailure == null && successResult != null)
        primaryFailure?.let { report.put("failure", it.toString()) }
        File(evidence, "report.json").writeText(report.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "P144 evidence: files/${evidence.name}/report.json\n") })
    }
    primaryFailure?.let { throw it }
    return checkNotNull(successResult)
}
