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
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Real touch access to a short Inspector, with unchanged selection/search data and exact visibility history. */
internal fun shortInspectorUiSmoke(instrumentation: Instrumentation, languageTag: String): String {
    val context = instrumentation.targetContext
    val language = when (languageTag) {
        "en" -> AppLanguage.ENGLISH
        "zh-CN" -> AppLanguage.SIMPLIFIED_CHINESE
        else -> error("shortInspectorLanguage must be en or zh-CN")
    }
    val previousLanguage = AppLanguages.current(context)
    val automation = editorUiAutomation(instrumentation)
    val token = "p145-short-inspector-${System.nanoTime()}"
    check(validatedDebugWorkspaceToken(token) == token)
    val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
    val evidence = File(context.filesDir, "$token-evidence").apply { check(mkdirs()) }
    val records = JSONArray()
    val report = JSONObject().put("selector", "shortInspectorUi").put("language", languageTag)
        .put("workspace", token).put("passed", false).put("stages", records)
        .put("input", "real touch focus/actions/scrolls, system IME visible, public ACTION_SET_TEXT")
        .put("ime", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        .put("imeLanguageScope", "App locale only; this selector does not change the keyboard subtype or claim Chinese composing coverage")
    var activity: MainActivity? = null
    var workspace: AndroidWorkspace? = null
    var canvas: CanvasView? = null
    var editor: NativeEditor? = null
    var primaryFailure: Throwable? = null
    var successResult: String? = null
    var windowSelection = JSONObject()
    val actions = JSONArray().also { report.put("actions", it) }
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
                hasCanvas && hasEditorChrome
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
            .put("requiresEditorChrome", true).put("candidateCount", candidates.size).put("windows", windows)
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
    fun source(): String = checkNotNull(checkNotNull(editor).captureDisplayScene()).use { scene ->
        val values = JSONArray()
        repeat(checkNotNull(editor).state().layers.size) { index ->
            val layer = scene.layerMetadata(index)
            check(layer.tileCount == 1 && layer.pixelWidth == 4 && layer.pixelHeight == 4)
            val buffer = ByteBuffer.allocateDirect(64)
            scene.readTile(index, 0, 16, buffer)
            val bytes = ByteArray(64).also { buffer.rewind(); buffer.get(it) }
            values.put(JSONObject().put("index", index).put("pixelsSha256", moveInspectorSha(bytes))
                .put("sourceIdentity", JSONArray(scene.tileViewsMetadata(index).map { it.payloadResourceId })))
        }
        values.toString()
    }
    fun searchField(editorRoot: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? =
        findEditorEditableUiNode(editorRoot, checkNotNull(activity).getString(R.string.ui_search_layers))
    fun capture(stage: String): JSONObject {
        val selectedRoot = root()
        val record = geometry().put("stage", stage).put("field", describe(xField(selectedRoot)))
            .put("searchField", describe(searchField(selectedRoot)))
            .put("layerList", describe(findEditorUiNode(selectedRoot, checkNotNull(activity).getString(R.string.ui_layer_list))))
            .put("windowSelection", windowSelection)
        editor?.let { model ->
            val state = model.state()
            val identity = source()
            record.put("state", state.toString()).put("revision", state.revision)
                .put("sources", JSONArray(identity)).put("selection", model.selection().toString())
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
    fun usable(record: JSONObject, ime: Boolean) {
        check(record.getJSONObject("windowSelection").getInt("candidateCount") == 1) {
            "Expected one complete editor Activity root: ${record.getJSONObject("windowSelection")}"
        }
        check(record.getInt("orientation") == Configuration.ORIENTATION_LANDSCAPE && record.getBoolean("windowFocus")) {
            "Expected focused effective landscape configuration: $record"
        }
        check(record.getBoolean("imeVisible") == ime) { "Expected IME visible=$ime: $record" }
        check(record.getBoolean("canvasVisible") && record.getDouble("canvasVisibleHeightDp") >= 64.0 &&
            record.getDouble("canvasVisibleWidthDp") >= 64.0 && record.getBoolean("surfaceValid") &&
            record.getBoolean("surfaceMatchesCanvas")) { "Canvas must remain visible at least64dp with matching Surface: $record" }
        if (ime) check(record.getDouble("canvasVisibleHeightDp") <= 120.0) {
            "Fixture must exercise a genuinely short Inspector body, at most120dp: $record"
        }
    }
    fun frame(): Rect = Rect().also { value ->
        instrumentation.runOnMainSync { checkNotNull(activity).window.decorView.getWindowVisibleDisplayFrame(value) }
    }
    fun visibleBounds(node: AccessibilityNodeInfo): Rect = Rect().also { bounds ->
        node.getBoundsInScreen(bounds)
        if (!bounds.intersect(frame())) bounds.setEmpty()
        var ancestor = node.parent
        while (ancestor != null) {
            if (ancestor.isScrollable) {
                val viewport = Rect().also(ancestor::getBoundsInScreen)
                if (!bounds.intersect(viewport)) bounds.setEmpty()
            }
            ancestor = ancestor.parent
        }
    }
    fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var candidate = node
        repeat(12) {
            val value = candidate ?: return null
            if (value.isClickable || value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return value
            candidate = value.parent
        }
        return null
    }
    fun touchable(node: AccessibilityNodeInfo?): Boolean {
        val value = node ?: return false
        val bounds = visibleBounds(value)
        val density = checkNotNull(activity).resources.displayMetrics.density
        return value.isEnabled && value.isVisibleToUser && bounds.width() / density >= 48f && bounds.height() / density >= 48f
    }
    fun touch(node: AccessibilityNodeInfo, label: String) {
        check(touchable(node)) { "Touch target must expose at least48dp in both dimensions: $label ${describe(node)} visible=${visibleBounds(node)}" }
        val bounds = visibleBounds(node)
        actions.put(JSONObject().put("type", "touch").put("label", label).put("node", describe(node))
            .put("visibleIntersection", bounds.toShortString()).put("x", bounds.exactCenterX().toDouble())
            .put("y", bounds.exactCenterY().toDouble()).put("imeVisibleBefore", geometry().getBoolean("imeVisible")))
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 35)) {
            val event = MotionEvent.obtain(down, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { check(automation.injectInputEvent(event, true)) { "Inspector touch injection rejected: $label" } } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    fun click(label: String) {
        waitFor("Visible actionable control missing: $label") { touchable(clickable(findEditorUiNode(root(), label))) }
        touch(checkNotNull(clickable(findEditorUiNode(root(), label))), label)
    }
    fun listViewport(): Pair<AccessibilityNodeInfo, Rect> {
        val list = checkNotNull(findEditorUiNode(root(), checkNotNull(activity).getString(R.string.ui_layer_list))) {
            "Layer list viewport is missing while the IME is visible"
        }
        check(list.isScrollable) { "Layer list must expose its real scroll viewport" }
        val viewport = visibleBounds(list)
        val density = checkNotNull(activity).resources.displayMetrics.density
        check(viewport.width() / density >= 48 && viewport.height() / density >= 64) {
            "Layer list needs a usable scroll viewport while IME is visible: ${describe(list)} visible=$viewport"
        }
        return list to viewport
    }
    fun listFingerprint(): String {
        val list = listViewport().first
        val values = ArrayList<String>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) values.add("${node.text}|${node.contentDescription}|${Rect().also(node::getBoundsInScreen)}")
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(list)
        return values.joinToString(";")
    }
    fun swipeList(forward: Boolean, maximumDistanceDp: Float? = null): Boolean {
        val (list, viewport) = listViewport()
        val directionAction = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        check(list.actionList.any { it.id == directionAction }) {
            "Layer list reached its ${if (forward) "end" else "start"} before the target was reachable: ${describe(list)}"
        }
        val density = checkNotNull(activity).resources.displayMetrics.density
        val fullDistance = viewport.height() - 12 * density
        val distance = maximumDistanceDp?.let { minOf(fullDistance, it * density) } ?: fullDistance
        val fromY = if (forward) viewport.bottom - 6 * density else viewport.top + 6 * density
        val toY = fromY + if (forward) -distance else distance
        val x = viewport.exactCenterX()
        val beforeFingerprint = listFingerprint()
        val record = JSONObject().put("type", "layerListSwipe").put("forward", forward)
            .put("viewport", viewport.toShortString()).put("x", x.toDouble()).put("fromY", fromY.toDouble())
            .put("toY", toY.toDouble()).put("distanceDp", (distance / density).toDouble())
            .put("fineAlignment", maximumDistanceDp != null).put("imeVisibleBefore", geometry().getBoolean("imeVisible"))
        actions.put(record)
        val down = SystemClock.uptimeMillis()
        for (step in 0..8) {
            val action = if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            val event = MotionEvent.obtain(down, down + step * 35L, action, x, fromY + (toY - fromY) * step / 8, 0)
            try { check(automation.injectInputEvent(event, true)) { "Inspector swipe injection rejected" } } finally { event.recycle() }
        }
        // End with a stationary interval so a short alignment gesture does not fling past its target.
        val up = MotionEvent.obtain(down, down + 420, MotionEvent.ACTION_UP, x, toY, 0)
        try { check(automation.injectInputEvent(up, true)) { "Inspector swipe-up injection rejected" } } finally { up.recycle() }
        instrumentation.waitForIdleSync()
        Thread.sleep(80)
        check(geometry().getBoolean("imeVisible")) { "Real Inspector scrolling unexpectedly hid the IME" }
        val changed = listFingerprint() != beforeFingerprint
        record.put("visibleContentMoved", changed)
        return changed
    }
    fun revealLayerControl(label: String, forward: Boolean): AccessibilityNodeInfo {
        var stationary = 0
        repeat(40) { attempt ->
            val target = clickable(findEditorUiNode(root(), label))
            if (touchable(target)) return checkNotNull(target)
            val viewport = listViewport().second
            val bounds = target?.let { Rect().also(it::getBoundsInScreen) }
            val intersection = target?.let(::visibleBounds)
            val partlyVisible = intersection?.isEmpty == false
            val direction = when {
                bounds == null -> forward
                bounds.top < viewport.top -> false
                bounds.bottom > viewport.bottom -> true
                else -> forward
            }
            if (partlyVisible && bounds != null && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) {
                error("Inspector target is on screen but cannot expose its required48dp touch area: $label ${describe(target)}")
            }
            actions.put(JSONObject().put("type", "revealLayerTarget").put("label", label).put("attempt", attempt)
                .put("target", describe(target)).put("visibleIntersection", intersection?.toShortString() ?: JSONObject.NULL)
                .put("forward", direction).put("fineAlignment", partlyVisible))
            // A79dp viewport leaves only31dp of valid alignment for a48dp target.
            // Keep partial-target adjustments below that span; reserve large swipes for unseen content.
            val changed = swipeList(direction, if (partlyVisible) 20f else null)
            stationary = if (changed) 0 else stationary + 1
            check(stationary < 2) { "Two real Inspector swipes made no visible progress toward $label; inspect retained bounds/actions" }
        }
        error("Inspector control never became a48dp visible touch target: $label")
    }
    fun enter() {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        instrumentation.sendKeySync(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        instrumentation.waitForIdleSync()
    }
    try {
        instrumentation.runOnMainSync { AppLanguages.set(context, language) }
        NativeEditor(256, false).use { seed ->
            var selected = 0L
            repeat(12) { index ->
                selected = seed.importPixels(4, 4, ByteBuffer.allocateDirect(64).apply {
                    repeat(16) { put(byteArrayOf((30 + index * 15).toByte(), 80, 120, -1)) }; rewind()
                })
                seed.renameLayer(selected, "P145 layer ${ (index + 1).toString().padStart(2, '0') }")
                seed.setTransform(selected, Transform64(10.0 + index * 4, 20.0 + index * 3, 32.0, 32.0))
            }
            seed.selectLayer(selected)
            seed.save(File(directory, "current.checkpoint"))
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
        val host = checkNotNull(activity)
        workspace = host.retainedWorkspaceForTest()
        check(checkNotNull(workspace).recoveryDirectoryForTest().canonicalFile == directory.canonicalFile &&
            host.display?.displayId == Display.DEFAULT_DISPLAY) { "Refusing to edit outside the isolated display0 workspace" }
        instrumentation.runOnMainSync { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor("Landscape editor did not load") {
            instrumentation.runOnMainSync { canvas = findCanvas(host.window.decorView) }
            editor = host.retainedEditorForTest()
            host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                !checkNotNull(workspace).snapshot().loading && editor?.state()?.layers?.size == 12 &&
                canvas != null && checkNotNull(canvas).width > 0 && checkNotNull(canvas).height > 0
        }
        // Initial navigation is outside the short-height assertion. Every tested action below uses touch.
        revealInspectorPanel(instrumentation, InspectorPanel.LAYERS)
        revealEditorControl(instrumentation, host.getString(R.string.ui_tool_shortcut, host.toolTitle(EditorTool.MOVE), "V"))
        click(host.getString(R.string.ui_tool_shortcut, host.toolTitle(EditorTool.MOVE), "V"))
        waitFor("Move tool did not activate") { checkNotNull(canvas).toolMode == CanvasToolMode.MOVE }
        stableGeometry()
        val baselineGeometry = capture("baseline")
        usable(baselineGeometry, ime = false)
        val model = checkNotNull(editor)
        val before = model.state()
        val originalSelection = model.selection()
        val sourceBefore = source()
        fun unchangedDocument(expected: EditorState = before) {
            val actual = model.state()
            val sourceMatches = source() == sourceBefore
            check(actual.layers == expected.layers && actual.revision == expected.revision &&
                actual.undo == expected.undo && actual.redo == expected.redo && actual.bytes == expected.bytes &&
                actual.document == expected.document && sourceMatches) {
                val changedLayers = actual.layers.filter { item -> expected.layers.firstOrNull { it.id == item.id } != item }.map { it.id }
                "Inspector state differs: revision=${expected.revision}->${actual.revision}, " +
                    "active=${expected.document.activeLayerId}->${actual.document.activeLayerId}, " +
                    "undo/redo=${expected.undo}/${expected.redo}->${actual.undo}/${actual.redo}, " +
                    "bytes=${expected.bytes}->${actual.bytes}, layers=${expected.layers.size}->${actual.layers.size}, " +
                    "changedLayerIds=$changedLayers, documentMatches=${actual.document == expected.document}, sourceMatches=$sourceMatches"
            }
        }
        fun stableCanvasOrigin(record: JSONObject) {
            check(record.getJSONArray("canvasOrigin").toString() == baselineGeometry.getJSONArray("canvasOrigin").toString()) {
                "Inspector/IME layout changed the Canvas screen origin"
            }
        }
        click(host.getString(R.string.ui_search_layers))
        waitFor("Layers search editor did not open") { searchField() != null }
        touch(checkNotNull(searchField()), host.getString(R.string.ui_search_layers))
        waitFor("Real Search tap did not show the IME and preserve search focus") {
            searchField()?.isFocused == true && geometry().getBoolean("imeVisible")
        }
        stableGeometry()
        val ime = capture("search-ime-visible")
        usable(ime, ime = true); stableCanvasOrigin(ime)
        val focusedSearch = checkNotNull(searchField())
        check(focusedSearch.isFocused && touchable(focusedSearch)) {
            "Search must preserve focus and a48dp visible hit area through short-height reflow: ${describe(focusedSearch)} visible=${visibleBounds(focusedSearch)}"
        }
        report.put("searchVisibleIntersection", visibleBounds(focusedSearch).toShortString())
        val query = "P145 layer 0"
        check(focusedSearch.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        })) { "Search rejected public ACTION_SET_TEXT" }
        waitFor("Search query or focus was lost after the IME layout change") {
            searchField()?.let { it.isFocused && it.text?.toString() == query } == true
        }
        usable(capture("search-typed"), ime = true)
        unchangedDocument()
        check(model.selection() == originalSelection) { "Searching must not select a different layer" }
        val backTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(backTime, backTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(backTime, backTime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        waitFor("Back did not dismiss the IME without dropping the existing search query") {
            !geometry().getBoolean("imeVisible") && searchField()?.text?.toString() == query
        }
        stableGeometry()
        val hiddenKeyboard = capture("query-keyboard-hidden")
        usable(hiddenKeyboard, ime = false); stableCanvasOrigin(hiddenKeyboard)
        unchangedDocument()
        touch(checkNotNull(searchField()), host.getString(R.string.ui_search_layers))
        waitFor("Search query/focus did not survive showing the IME again") {
            geometry().getBoolean("imeVisible") && searchField()?.let { it.isFocused && it.text?.toString() == query } == true
        }
        stableGeometry()
        val shownAgain = capture("query-keyboard-restored")
        usable(shownAgain, ime = true); stableCanvasOrigin(shownAgain)
        check(touchable(searchField())) { "Existing search query lost its visible48dp editor after reflow" }
        unchangedDocument()
        val target = before.layers.first { it.name == "P145 layer 01" }
        val targetLabel = host.getString(R.string.ui_select_layer, target.name)
        check(!touchable(clickable(findEditorUiNode(root(), targetLabel)))) {
            "Fixture must require real scrolling to the far layer"
        }
        var filterCountObserved = false
        repeat(12) {
            val count = findEditorUiNode(root(), "9/12")
            if (count?.isVisibleToUser == true && !visibleBounds(count).isEmpty) {
                filterCountObserved = true
                return@repeat
            }
            if (!filterCountObserved) swipeList(forward = true)
        }
        check(filterCountObserved) { "Filtered layer count9/12 was not visibly reachable after entering the query" }
        usable(capture("filtered-count"), ime = true)
        val row = revealLayerControl(targetLabel, forward = true)
        val scrollCount = (0 until actions.length()).count { actions.getJSONObject(it).optString("type") == "layerListSwipe" }
        check(scrollCount > 0)
        touch(row, targetLabel)
        waitFor("Real row touch did not select the far layer") {
            model.selection().primary == target.id && model.selection().roots == listOf(target.id)
        }
        usable(capture("selected-far-layer"), ime = true)
        // Active-layer publication advances the revision without creating an Undo entry.
        // Search above must remain revision-neutral; this real selection has its own exact contract.
        check(originalSelection.primary != target.id)
        val expectedSelected = before.copy(revision = before.revision + 1,
            document = before.document.copy(activeLayerId = target.id))
        unchangedDocument(expectedSelected)
        val selectedState = model.state()
        val selectedAuthority = model.selection()
        check(selectedAuthority.revision == originalSelection.revision + 1 &&
            selectedAuthority.generation == originalSelection.generation + 1 &&
            selectedAuthority.epoch == originalSelection.epoch + 1 &&
            selectedAuthority.contentIdentity == originalSelection.contentIdentity &&
            selectedAuthority.primary == target.id && selectedAuthority.roots == listOf(target.id) && !selectedAuthority.mask) {
            "Active-layer publication must advance revision/generation/selection epoch once without changing content identity"
        }
        report.put("selectionPublication", JSONObject().put("beforeRevision", before.revision)
            .put("afterRevision", selectedState.revision).put("beforeActiveLayer", before.document.activeLayerId)
            .put("afterActiveLayer", selectedState.document.activeLayerId).put("undoUnchanged", selectedState.undo == before.undo)
            .put("redoUnchanged", selectedState.redo == before.redo).put("sourceUnchanged", source() == sourceBefore))
        val visibleLabel = host.getString(R.string.ui_layer_visibility, target.name)
        touch(revealLayerControl(visibleLabel, forward = true), visibleLabel)
        val hiddenLayers = selectedState.layers.map { if (it.id == target.id) it.copy(visible = false) else it }
        waitFor("Visibility touch did not publish exactly one layer change") { model.state().layers == hiddenLayers }
        model.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(model.state().revision == selectedState.revision + 1 && model.state().undo && !model.state().redo &&
            model.selection().let { it.primary == selectedAuthority.primary && it.roots == selectedAuthority.roots &&
                it.mask == selectedAuthority.mask } && source() == sourceBefore) {
            "Visibility must create exactly one history revision with unchanged selection/source"
        }
        usable(capture("visibility-hidden"), ime = true)
        click(host.getString(R.string.ui_undo))
        model.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(model.state().layers == selectedState.layers && model.state().revision == selectedState.revision + 2 &&
            model.state().redo && source() == sourceBefore) { "One Undo did not exactly restore the visible layer" }
        usable(capture("visibility-undo"), ime = true)
        click(host.getString(R.string.ui_redo))
        model.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(model.state().layers == hiddenLayers && model.state().revision == selectedState.revision + 3 &&
            source() == sourceBefore) { "One Redo did not exactly restore the hidden layer" }
        usable(capture("visibility-redo"), ime = true)
        click(host.getString(R.string.ui_undo))
        model.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(model.state().layers == selectedState.layers && model.state().revision == selectedState.revision + 4 &&
            source() == sourceBefore) { "Final Undo did not restore the fixture before panel navigation" }
        usable(capture("visibility-restored"), ime = true)
        val afterHistory = model.state()
        val footer = JSONArray()
        for ((stage, resource) in listOf("footer-options" to R.string.ui_layer_options,
            "footer-add" to R.string.ui_add_layer, "footer-group" to R.string.ui_group)) {
            val label = host.getString(resource)
            val control = revealLayerControl(label, forward = true)
            footer.put(JSONObject().put("label", label).put("node", describe(control))
                .put("visibleIntersection", visibleBounds(control).toShortString()))
            usable(capture(stage), ime = true)
        }
        report.put("footerTargets", footer)
        unchangedDocument(afterHistory)
        val optionsLabel = host.getString(R.string.ui_layer_options)
        touch(revealLayerControl(optionsLabel, forward = false), optionsLabel)
        fun optionsDialog(): AccessibilityNodeInfo? {
            automation.clearCache()
            val candidates = automation.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty().mapNotNull { window ->
                window.root?.takeIf { candidate -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    candidate.packageName?.toString() == context.packageName &&
                    findEditorUiNode(candidate, host.getString(R.string.ui_canvas)) == null &&
                    findEditorUiNode(candidate, target.name) != null && findEditorUiNode(candidate, host.getString(R.string.ui_done)) != null }
            }
            check(candidates.size <= 1) { "Ambiguous layer options dialog" }
            return candidates.singleOrNull()
        }
        waitFor("Real Layer options touch did not open its dialog") { optionsDialog() != null }
        val dialog = checkNotNull(optionsDialog())
        val dialogNodes = JSONArray()
        fun collectDialog(node: AccessibilityNodeInfo, depth: Int) {
            check(dialogNodes.length() < 2048 && depth < 64) { "Dialog tree exceeded evidence limit" }
            dialogNodes.put((describe(node) as JSONObject).put("depth", depth))
            for (index in 0 until node.childCount) node.getChild(index)?.let { collectDialog(it, depth + 1) }
        }
        collectDialog(dialog, 0)
        File(evidence, "layer-options-dialog-tree.json").writeText(dialogNodes.toString(2))
        capture("layer-options-dialog")
        unchangedDocument(afterHistory)
        touch(checkNotNull(clickable(findEditorUiNode(dialog, host.getString(R.string.ui_done)))), host.getString(R.string.ui_done))
        waitFor("Done did not dismiss layer options") { optionsDialog() == null }
        val closedDialog = capture("layer-options-closed")
        report.put("imeVisibleAfterOptionsDialog", closedDialog.getBoolean("imeVisible"))
        unchangedDocument(afterHistory)
        // A retained toolbar field anchors the keyboard while Search is disposed by panel switching.
        val x = checkNotNull(xField()) { "Restored visible layer must expose Move X" }
        touch(x, host.getString(R.string.ui_move_field_value, "X"))
        waitFor("Move X did not become the IME anchor") { xField()?.isFocused == true && geometry().getBoolean("imeVisible") }
        usable(capture("move-keyboard-anchor"), ime = true)
        val pickerLabel = host.getString(R.string.ui_choose_inspector_panel, host.panelTitle(InspectorPanel.LAYERS))
        click(pickerLabel)
        fun menuItem(title: String): AccessibilityNodeInfo? {
            automation.clearCache()
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (window in automation.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()) {
                val candidate = window.root ?: continue
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || candidate.packageName?.toString() != context.packageName ||
                    findEditorUiNode(candidate, host.getString(R.string.ui_canvas)) != null) continue
                    clickable(findEditorUiNode(candidate, title))?.takeIf(::touchable)?.let(matches::add)
            }
            check(matches.size <= 1) { "Ambiguous Inspector panel popup" }
            return matches.singleOrNull()
        }
        fun selectPanel(panel: InspectorPanel, reopenMenu: Boolean) {
            val title = host.panelTitle(panel)
            waitFor("${title} menu item was not touch reachable") { menuItem(title) != null }
            touch(checkNotNull(menuItem(title)), title)
            waitFor("Real panel menu touch did not select $title with the IME still visible") {
                findEditorUiNode(root(), host.getString(R.string.ui_choose_inspector_panel, title)) != null &&
                    geometry().getBoolean("imeVisible") && xField()?.isFocused == true
            }
            stableGeometry()
            val snapshot = capture("panel-${panel.name.lowercase(Locale.ROOT)}")
            usable(snapshot, ime = true)
            stableCanvasOrigin(snapshot)
            unchangedDocument(afterHistory)
            if (reopenMenu) click(host.getString(R.string.ui_choose_inspector_panel, title))
        }
        selectPanel(InspectorPanel.COLOR, reopenMenu = true)
        selectPanel(InspectorPanel.HISTORY, reopenMenu = true)
        selectPanel(InspectorPanel.NAVIGATOR, reopenMenu = false)
        enter()
        waitFor("Unchanged toolbar field completion did not return focus to Canvas") {
            val observed = geometry()
            !observed.getBoolean("imeVisible") && observed.getBoolean("canvasKeyboardFocusActive") &&
                observed.getBoolean("canvasWindowFocus") && observed.getBoolean("canvasKeyboardMoveEnabled") &&
                model.activeTransformCompletion() == null && !model.hasActiveTransformDraft
        }
        stableGeometry()
        usable(capture("done"), ime = false)
        unchangedDocument(afterHistory)
        report.put("functionalPassed", true).put("realListSwipes", scrollCount)
            .put("visibilityHistory", "one change; Undo, Redo, final Undo; exact layers and revision+1/+2/+3/+4")
        successResult = "PASS shortInspectorUi language=$languageTag; search focus/query survive real IME, Canvas>=64dp,48dp visible touch targets, real list scrolling/filter/selection, exact visibility Undo/Redo, panel switching with Move keyboard anchor; evidence=files/${evidence.name}"
    } catch (failure: Throwable) {
        primaryFailure = failure
        report.put("failure", failure.toString())
        runCatching { if (activity != null) capture("failure") }.exceptionOrNull()?.let { report.put("captureFailure", it.toString()) }
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
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "P145 evidence: files/${evidence.name}/report.json\n") })
    }
    primaryFailure?.let { throw it }
    return checkNotNull(successResult)
}
