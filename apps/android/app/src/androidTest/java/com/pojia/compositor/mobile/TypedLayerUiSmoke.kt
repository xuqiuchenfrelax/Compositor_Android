package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Actual Inspector ACTION_CLICK/SET_TEXT/SET_PROGRESS on physical display 0, in a unique debug recovery workspace.
 * NativeEditor is read only for postcondition inspection. Text input can use the focused view's InputConnection;
 * this does not exercise an OEM keyboard candidate UI, real fingers/stylus,
 * Mac glyph/pixel parity, the shared saved-project library, SAF export, or a process-death/recreation boundary.
 */
internal fun typedLayerUiSmoke(instrumentation: Instrumentation, noiseOnly: Boolean = false, textBoxOnly: Boolean = false,
    textInputOnly: Boolean = false): String {
    val context = instrumentation.targetContext
    val workspaceToken = "p103-typed-ui-${System.nanoTime()}"
    check(validatedDebugWorkspaceToken(workspaceToken) == workspaceToken) { "Typed UI requires a debug-only isolated workspace" }
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, workspaceToken)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
    val workspace = activity.retainedWorkspaceForTest()
    val expectedDirectory = File(context.filesDir, "debug-workspaces/$workspaceToken").canonicalFile
    try {
        check(activity.display?.displayId == Display.DEFAULT_DISPLAY) { "Typed UI Activity did not launch on display 0" }
        check(workspace.recoveryDirectoryForTest().canonicalFile == expectedDirectory) {
            "Refusing to edit: debug workspace token was not applied"
        }
    } catch (failure: Throwable) {
        instrumentation.runOnMainSync { activity.finish() }
        runCatching { workspace.closeAsync().get(12, TimeUnit.SECONDS) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    val evidenceDirectory = try {
        File(expectedDirectory, "ui-evidence").apply { check(isDirectory || mkdirs()) }
    } catch (failure: Throwable) {
        instrumentation.runOnMainSync { activity.finish() }
        runCatching { workspace.closeAsync().get(12, TimeUnit.SECONDS) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "Typed UI isolated workspace: $workspaceToken; display=0; evidence=$evidenceDirectory\n")
    })
    val steps = JSONArray()
    var activeStage = "initializing"
    fun checkpoint(stage: String, status: String, details: JSONObject? = null) {
        activeStage = stage
        steps.put(JSONObject().put("stage", stage).put("status", status)
            .put("atUptimeMillis", SystemClock.uptimeMillis()).apply { if (details != null) put("details", details) })
        File(evidenceDirectory, "steps.json").writeText(JSONObject().put("workspace", workspaceToken)
            .put("display", Display.DEFAULT_DISPLAY)
            .put("input", if (textInputOnly) "accessibilityAndFocusedViewInputConnection" else "accessibilityActionsNotIME")
            .put("steps", steps).toString(2))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "Typed UI stage: $stage; status=$status; workspace=$workspaceToken\n")
        })
    }
    fun preserveFailure(message: String) {
        val prefix = "p97-typed-ui-failure-${SystemClock.uptimeMillis()}"
        var remaining = 2000
        fun tree(node: AccessibilityNodeInfo?, depth: Int): JSONObject? {
            if (node == null || depth > 40 || remaining-- <= 0) return null
            return JSONObject().put("text", node.text?.toString()?.take(256))
                .put("description", node.contentDescription?.toString()?.take(256))
                .put("class", node.className?.toString()).put("visible", node.isVisibleToUser)
                .put("enabled", node.isEnabled).put("selected", node.isSelected)
                .put("editable", node.isEditable).put("hint", node.hintText?.toString()?.take(256))
                .put("clickable", node.isClickable).put("focused", node.isFocused)
                .put("actions", JSONArray(node.actionList.map { it.id }))
                .put("bounds", Rect().also(node::getBoundsInScreen).toShortString())
                .put("children", JSONArray().also { children ->
                    for (index in 0 until node.childCount) tree(node.getChild(index), depth + 1)?.let(children::put)
                })
        }
        runCatching {
            File(evidenceDirectory, "$prefix.json").writeText(JSONObject().put("error", message)
                .put("stage", activeStage).put("steps", steps)
                .put("tree", tree(editorPhysicalDisplayRoot(instrumentation), 0)).toString(2))
            editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
                try { File(evidenceDirectory, "$prefix.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Typed UI failure evidence: $prefix\n") })
        }
    }
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            editorUiAutomation(instrumentation).clearCache()
            if (condition()) return
            Thread.sleep(25); instrumentation.waitForIdleSync()
        }
        preserveFailure(message)
        error(message)
    }
    fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    fun node(label: String): AccessibilityNodeInfo? = find(editorPhysicalDisplayRoot(instrumentation), label)
    fun scrollInspector(preferredDirection: Int, target: AccessibilityNodeInfo? = null): Int {
        val root = editorPhysicalDisplayRoot(instrumentation) ?: return preferredDirection
        val minimumWidth = (96 * context.resources.displayMetrics.density).toInt()
        val header = node(context.getString(R.string.ui_collapse_inspector_dock)) ?: return preferredDirection
        val headerBounds = Rect().also(header::getBoundsInScreen)
        fun isInspectorScroller(value: AccessibilityNodeInfo): Boolean {
            val bounds = Rect().also(value::getBoundsInScreen)
            return value.isScrollable && value.isVisibleToUser && bounds.width() >= minimumWidth &&
                bounds.top >= headerBounds.bottom &&
                headerBounds.centerX() in bounds.left until bounds.right &&
                value.className?.toString() == "android.widget.ScrollView"
        }
        fun scroller(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (value == null) return null
            // Anchor to the dock header: an expanded File menu is also a wide ScrollView.
            if (isInspectorScroller(value)) return value
            for (index in 0 until value.childCount) scroller(value.getChild(index))?.let { return it }
            return null
        }
        fun targetScroller(): AccessibilityNodeInfo? {
            var current = target
            repeat(20) {
                val value = current ?: return null
                if (isInspectorScroller(value)) return value
                current = value.parent
            }
            return null
        }
        val owner = targetScroller() ?: scroller(root) ?: return preferredDirection
        val direction = if (owner.actionList.any { it.id == preferredDirection }) preferredDirection
            else if (preferredDirection == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if (owner.actionList.any { it.id == direction }) owner.performAction(direction)
        return direction
    }
    fun findExposureSlider(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val range = node.rangeInfo
        if (range != null && kotlin.math.abs(range.min + 20f) < .001f && kotlin.math.abs(range.max - 20f) < .001f &&
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }) return node
        for (index in 0 until node.childCount) findExposureSlider(node.getChild(index))?.let { return it }
        return null
    }
    fun progress(label: String, value: Float) {
        fun findSlider(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label && node.rangeInfo != null && node.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }) return node
            for (i in 0 until node.childCount) findSlider(node.getChild(i))?.let { return it }
            return null
        }
        var slider: AccessibilityNodeInfo? = null
        var lastReveal = 0L
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        waitFor("Missing adjustment slider: $label") {
            slider = findSlider(editorPhysicalDisplayRoot(instrumentation))
            if (slider?.isVisibleToUser == true) true else {
                if (SystemClock.uptimeMillis() - lastReveal > 150) {
                    if (slider?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) != true)
                        direction = scrollInspector(direction, slider)
                    lastReveal = SystemClock.uptimeMillis()
                }
                false
            }
        }
        check(checkNotNull(slider).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }))
        instrumentation.waitForIdleSync()
    }
    fun actionable(label: String): AccessibilityNodeInfo? {
        var current = node(label)
        repeat(12) {
            val value = current ?: return null
            val clickable = value.isClickable || value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            val bounds = Rect().also(value::getBoundsInScreen)
            if (clickable && value.isVisibleToUser && !bounds.isEmpty) return value
            current = value.parent
        }
        return null
    }
    fun click(label: String) {
        InspectorPanel.entries.firstOrNull {
            label == context.getString(R.string.ui_inspector_panel, context.panelTitle(it))
        }?.let { revealInspectorPanel(instrumentation, it); return }
        val toolLabels = EditorTool.entries.filter { it != EditorTool.IDLE }.map {
            context.getString(R.string.ui_tool_shortcut, context.toolTitle(it), it.key)
        }
        if (label in toolLabels) revealEditorControl(instrumentation, label)
        var lastReveal = 0L
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        waitFor("Missing clickable Inspector action: $label") {
            if (actionable(label)?.isEnabled == true) true else {
                if (SystemClock.uptimeMillis() - lastReveal > 150) {
                    val target = node(label)
                    if (target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) != true)
                        direction = scrollInspector(direction, target)
                    lastReveal = SystemClock.uptimeMillis()
                }
                false
            }
        }
        val target = checkNotNull(actionable(label))
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $label" }
        instrumentation.waitForIdleSync()
    }
    fun editableField(label: String): AccessibilityNodeInfo {
        var editable: AccessibilityNodeInfo? = null
        var lastReveal = 0L
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        waitFor("Missing editable field: $label") {
            editable = findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), label)
            val target = editable
            val bounds = target?.let { Rect().also(it::getBoundsInScreen) }
            if (target?.isVisibleToUser == true && bounds?.isEmpty == false) true else {
                if (SystemClock.uptimeMillis() - lastReveal > 150) {
                    if (target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) != true)
                        direction = scrollInspector(direction, target)
                    lastReveal = SystemClock.uptimeMillis()
                }
                false
            }
        }
        // waitFor refreshes the accessibility cache. Keep only the node from its successful predicate.
        return checkNotNull(editable)
    }
    fun field(label: String, value: String) {
        check(editableField(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "ACTION_SET_TEXT rejected $label" }
        instrumentation.waitForIdleSync()
    }
    fun focusField(label: String) {
        val target = editableField(label)
        check(target.isFocused || target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            "Could not focus editable field: $label"
        }
        instrumentation.waitForIdleSync()
        waitFor("Field did not gain input focus: $label") {
            findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), label)?.isFocused == true
        }
    }
    fun revealButton(label: String): AccessibilityNodeInfo {
        instrumentation.runOnMainSync {
            val input = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        var button: AccessibilityNodeInfo? = null
        var lastReveal = 0L
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        waitFor("Missing visible button: $label") {
            button = null
            var current = node(label)
            repeat(12) {
                val candidate = current
                if (candidate != null && candidate.className?.toString() != "android.widget.TextView" &&
                    (candidate.isClickable || !candidate.isEnabled || candidate.className?.toString() == "android.widget.Button" ||
                        candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) button = candidate
                else current = candidate?.parent
            }
            if (button?.isVisibleToUser == true) true else {
                if (SystemClock.uptimeMillis() - lastReveal > 150) {
                    if (node(label)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) != true)
                        direction = scrollInspector(direction, button ?: node(label))
                    lastReveal = SystemClock.uptimeMillis()
                }
                false
            }
        }
        return checkNotNull(button)
    }
    fun editor(): NativeEditor {
        waitFor("Editor did not attach") { activity.retainedEditorForTest() != null }
        return checkNotNull(activity.retainedEditorForTest())
    }
    fun awaitLayer(predicate: (NativeEditor) -> Boolean) = waitFor("Expected typed layer state was not committed") {
        runCatching { predicate(editor()) }.getOrDefault(false)
    }

    var originalFailure: Throwable? = null
    try {
        val editor = editor()
        if (textInputOnly) {
            checkpoint("text-input-connection-validation-selection-history", "started")
            fun <T> main(action: () -> T): T {
                var result: Result<T>? = null
                instrumentation.runOnMainSync { result = runCatching(action) }
                return checkNotNull(result).getOrThrow()
            }
            fun draft(): TextEditorDraft = main { checkNotNull(activity.typedLayerTestAccessForTest()?.text?.invoke()) }
            fun editField(label: String, value: String) {
                focusField(label)
                field(label, value)
            }
            val apply = context.getString(R.string.ui_apply)
            val contentLabel = context.getString(R.string.ui_text_content)
            val colorLabel = context.getString(R.string.ui_text_color_hex)
            val fontLabel = context.getString(R.string.ui_font_face)
            click(context.getString(R.string.ui_tool_shortcut, context.getString(R.string.ui_tool_type), "T"))
            editField(context.getString(R.string.ui_font_size_px), "24")
            focusField(contentLabel)
            fun settleInputFrame() {
                val ready = java.util.concurrent.CountDownLatch(1)
                main {
                    val view = activity.window.decorView
                    view.postOnAnimation { view.postOnAnimation { ready.countDown() } }
                }
                check(ready.await(3, TimeUnit.SECONDS)) { "Text input frame did not settle" }
                instrumentation.waitForIdleSync()
            }
            fun connect(): android.view.inputmethod.InputConnection {
                // Queue idleness alone does not finish Compose's frame-driven
                // input restart. Bind after it, before sending the next edit.
                settleInputFrame()
                val info = android.view.inputmethod.EditorInfo()
                val connection = main { checkNotNull(activity.window.decorView.findFocus()?.onCreateInputConnection(info)) {
                    "Focused text field did not expose a real InputConnection"
                } }
                check(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                check(main { connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString() }
                    == draft().style.content) { "New input connection does not match the production draft" }
                return connection
            }
            var connection = connect()
            val inputTrace = JSONArray()
            fun input(action: (android.view.inputmethod.InputConnection) -> Boolean) {
                check(main { action(connection) }) { "InputConnection rejected a text edit" }
                instrumentation.waitForIdleSync()
                settleInputFrame()
                val current = draft()
                inputTrace.put(JSONObject().put("text", current.style.content)
                    .put("connectionIdentity", System.identityHashCode(connection))
                    .put("selectionStart", current.selectionStart).put("selectionEnd", current.selectionEnd)
                    .put("composingStart", current.composingStart).put("composingEnd", current.composingEnd)
                    .put("connectionTextBeforeCursor", main { connection.getTextBeforeCursor(100, 0)?.toString() }))
                File(evidenceDirectory, "input-connection.json").writeText(inputTrace.toString(2))
            }
            val unchanged = editor.state()
            input { it.beginBatchEdit(); val accepted = it.setComposingText("zhong", 1); it.endBatchEdit(); accepted }
            waitFor("Pinyin composition did not reach the production draft") { draft().let {
                it.style.content == "zhong" && it.hasComposition && it.selectionEnd == 5
            } }
            input { it.setComposingText("中文🚀", 1) }
            waitFor("Chinese candidate did not replace the composing range") { draft().let {
                it.style.content == "中文🚀" && it.composingStart == 0 && it.composingEnd == 4 && it.selectionEnd == 4
            } }
            // The OEM keyboard can clip Apply out of the accessibility tree.
            // Check the production commit guard without dismissing composition.
            main { checkNotNull(activity.typedLayerTestAccessForTest()).applyText() }
            instrumentation.waitForIdleSync()
            check(draft().hasComposition) { "Submission ended composition" }
            check(editor.state() == unchanged) { "Composition mutated the native document" }
            input { it.finishComposingText() }
            waitFor("finishComposingText did not release Apply") { !draft().hasComposition }
            input { it.commitText("\n第二段", 1) }
            val content = "中文🚀\n第二段"
            waitFor("Committed multiline text missing") { draft().style.content == content }
            input { it.setSelection(4, 4) }
            input { it.deleteSurroundingTextInCodePoints(1, 0) }
            waitFor("Codepoint deletion split or retained the emoji") { draft().style.content == "中文\n第二段" }
            // Rebind after the platform's edit/reset boundary, as an IME does.
            // An expired RecordingInputConnection may acknowledge edits without applying them.
            connection = connect()
            input { it.commitText("🚀", 1) }
            waitFor("Emoji insertion did not restore the text") { draft().style.content == content }
            input { it.setSelection(0, 2) }
            waitFor("InputConnection UTF16 selection was lost") { draft().let { it.selectionStart == 0 && it.selectionEnd == 2 } }
            main { connection.closeConnection() }
            // End the input phase before navigating controls that OEM adjustPan
            // moves above the physical display while its keyboard is visible.
            revealButton(apply)
            editField(colorLabel, "#336699")
            editField(fontLabel, "serif")
            val styled = draft().style
            File(evidenceDirectory, "selected-text-formatting.json").writeText(JSONObject()
                .put("style", styled.toMacJson()).put("selectionStart", draft().selectionStart)
                .put("selectionEnd", draft().selectionEnd).toString(2))
            check(styled.content == content && styled.colorRuns == listOf(TextColorRun(0, 2, .2, .4, .6)))
            check(styled.fontRuns == listOf(TextFontRun(0, 2, "serif")))
            for (invalid in listOf("", "#+00001", "#-00001", "#GGGGGG", "#1234567", "#336699garbage")) {
                editField(colorLabel, invalid)
                check(!revealButton(apply).isEnabled) { "Invalid color $invalid allowed Apply" }
                check(draft().style == styled && editor.state() == unchanged) { "Invalid color changed draft or document" }
            }
            focusField(context.getString(R.string.ui_font_size_px))
            check(!revealButton(apply).isEnabled) { "Blur silently discarded the invalid color" }
            check(editableField(colorLabel).text?.toString() == "#336699GARBAGE")
            editField(fontLabel, "")
            check(!revealButton(apply).isEnabled) { "Empty font allowed Apply" }
            editField(colorLabel, "336699")
            check(!revealButton(apply).isEnabled) { "Repairing color bypassed the invalid font" }
            editField(fontLabel, "x".repeat(201))
            val invalidFontApply = revealButton(apply).isEnabled
            val actualDraft = draft()
            File(evidenceDirectory, "text-style-validation.json").writeText(JSONObject()
                .put("expected", styled.toMacJson()).put("actual", actualDraft.style.toMacJson())
                .put("selectionStart", actualDraft.selectionStart).put("selectionEnd", actualDraft.selectionEnd)
                .put("invalidFontApplyEnabled", invalidFontApply).toString(2))
            check(!invalidFontApply && actualDraft.style == styled) { "Overlong font or repaired color changed the selected style; evidence=text-style-validation.json" }
            // Preserve actual invalid field edits through SavedState, not only a model-created draft.
            val sizeLabel = context.getString(R.string.ui_font_size_px)
            editField(colorLabel, "#GGGGGG")
            editField(sizeLabel, "NaN")
            check(!revealButton(apply).isEnabled)
            val previousActivity = activity
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                main { previousActivity.recreate() }
                activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity)
            } finally { instrumentation.removeMonitor(monitor) }
            waitFor("Recreated text draft did not attach") {
                main { activity.typedLayerTestAccessForTest()?.text?.invoke()?.style == styled }
            }
            check(activity !== previousActivity && main { activity.retainedWorkspaceForTest() === workspace })
            val recoveredFont = editableField(fontLabel).text?.toString()
            val recoveredColor = editableField(colorLabel).text?.toString()
            val recoveredSize = editableField(sizeLabel).text?.toString()
            val recoveredApply = revealButton(apply).isEnabled
            File(evidenceDirectory, "invalid-style-recreation.json").writeText(JSONObject()
                .put("font", recoveredFont).put("color", recoveredColor).put("size", recoveredSize)
                .put("applyEnabled", recoveredApply).put("sameWorkspace", true)
                .put("styleUnchanged", draft().style == styled).toString(2))
            check(recoveredFont == "x".repeat(201) && recoveredColor == "#GGGGGG" && recoveredSize == "NaN" && !recoveredApply) {
                "Activity recreation discarded invalid text fields or released Apply; evidence=invalid-style-recreation.json"
            }
            main { checkNotNull(activity.typedLayerTestAccessForTest()).applyText() }
            instrumentation.waitForIdleSync()
            check(editor.state() == unchanged && draft().style == styled) { "Invalid restored input reached the production commit" }
            editField(fontLabel, "serif")
            check(!revealButton(apply).isEnabled) { "Repairing font bypassed other restored errors" }
            editField(colorLabel, "336699")
            check(!revealButton(apply).isEnabled) { "Repairing color bypassed restored numeric error" }
            editField(sizeLabel, "24")
            check(revealButton(apply).isEnabled) { "Valid repaired text fields did not release Apply" }
            click(apply)
            awaitLayer { current -> current.state().layers.any { current.textStyleOrNull(it.id) == styled } }
            val id = editor.state().layers.single { editor.textStyleOrNull(it.id) == styled }.id
            click(context.getString(R.string.ui_undo))
            awaitLayer { it.state().layers.none { layer -> layer.id == id } }
            click(context.getString(R.string.ui_redo))
            awaitLayer { it.textStyleOrNull(id) == styled }
            click(context.getString(R.string.ui_edit_selected_text))
            editField(colorLabel, "#NOPE")
            check(!revealButton(apply).isEnabled)
            click(context.getString(R.string.ui_cancel))
            check(editor.textStyleOrNull(id) == styled)
            typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId ->
                check(restored.textStyleOrNull(restoredId) == styled)
            }
            editor.captureProjectSnapshot().use { snapshot ->
                NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, File(evidenceDirectory, "text-input.comp"), false, control).successful)
                }
            }
            checkpoint("text-input-connection-validation-selection-history", "passed", JSONObject()
                .put("input", "focusedViewInputConnection").put("oemKeyboardCandidateUI", false)
                .put("style", styled.toMacJson()))
            return "Text InputConnection composingPinyin->ChineseEmoji compositionCommitGuard=productionCallback finishCommit multiline codepointDelete UTF16SelectionFontColor; invalidColorAndFontGateAcrossBlur repair cancel undoRedo projectReopen=exact; OEMKeyboardCandidateNotExercised; workspace=$workspaceToken; evidence=$evidenceDirectory"
        }
        if (textBoxOnly) {
            checkpoint("text-box-create-resize-validation-history", "started")
            val apply = context.getString(R.string.ui_apply)
            click(context.getString(R.string.ui_tool_shortcut, context.getString(R.string.ui_tool_type), "T"))
            waitFor("Type draft missing") { node(context.getString(R.string.ui_text_layer)) != null }
            field(context.getString(R.string.ui_text_content), "中文段落🚀 自动换行\n第二段 editable")
            field(context.getString(R.string.ui_font_size_px), "24")
            click(context.getString(R.string.ui_text_box))
            val widthLabel = context.getString(R.string.ui_text_box_width)
            val heightLabel = context.getString(R.string.ui_text_box_height)
            field(widthLabel, "160")
            field(heightLabel, "100")
            click(context.getString(R.string.ui_align_center))
            val before = editor.state()
            field(widthLabel, "0")
            check(!revealButton(apply).isEnabled) { "Invalid text width allowed Apply" }
            check(editor.state() == before) { "Invalid draft mutated document" }
            field(widthLabel, "30000")
            field(heightLabel, "30000")
            check(!revealButton(apply).isEnabled) { "Oversized text area allowed Apply" }
            field(widthLabel, "160")
            field(heightLabel, "100")
            click(apply)
            awaitLayer { current -> current.state().layers.any { layer -> current.textStyleOrNull(layer.id)?.let {
                it.boxWidth == 160.0 && it.boxHeight == 100.0 && it.alignment == TextParagraphAlignment.CENTER
            } == true } }
            val id = editor.state().layers.single { editor.textStyleOrNull(it.id)?.boxWidth == 160.0 }.id
            val created = checkNotNull(editor.textStyleOrNull(id))
            val original = editor.state().layers.single { it.id == id }
            check(original.pixelWidth == 160 && original.pixelHeight == 100)
            click(context.getString(R.string.ui_edit_selected_text))
            field(widthLabel, "240")
            field(heightLabel, "144")
            click(apply)
            awaitLayer { it.textStyleOrNull(id)?.boxWidth == 240.0 }
            val edited = checkNotNull(editor.textStyleOrNull(id))
            val resized = editor.state().layers.single { it.id == id }
            check(resized.pixelWidth == 240 && resized.pixelHeight == 144)
            check(resized.transform.width == 240.0 && resized.transform.height == 144.0 &&
                resized.transform.x == original.transform.x && resized.transform.y == original.transform.y)
            click(context.getString(R.string.ui_undo))
            awaitLayer { it.textStyleOrNull(id) == created }
            click(context.getString(R.string.ui_redo))
            awaitLayer { it.textStyleOrNull(id) == edited }
            click(context.getString(R.string.ui_edit_selected_text))
            click(context.getString(R.string.ui_text_point))
            field(context.getString(R.string.ui_font_size_px), "")
            check(!revealButton(apply).isEnabled) { "Empty size allowed Apply" }
            click(context.getString(R.string.ui_cancel))
            check(editor.textStyleOrNull(id) == edited) { "Cancel changed committed paragraph" }
            typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId ->
                check(restored.textStyleOrNull(restoredId) == edited)
                check(restored.state().layers.single { it.id == restoredId }.transform == resized.transform)
            }
            editor.captureProjectSnapshot().use { snapshot ->
                NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, File(evidenceDirectory, "paragraph.comp"), false, control).successful)
                }
            }
            checkpoint("text-box-create-resize-validation-history", "passed", JSONObject()
                .put("created", created.toMacJson()).put("edited", edited.toMacJson()))
            return "ParagraphText Inspector Chinese+emoji create 160x100 -> resize240x144; center; invalidSide+invalidArea+emptySize Apply gates; Cancel+UndoRedo+projectReopen exact; input=accessibilityActionsNotIME; workspace=$workspaceToken; evidence=$evidenceDirectory"
        }
        if (noiseOnly) {
            checkpoint("add-noise-create-edit-history", "started")
            val apply = context.getString(R.string.ui_apply)
            click(context.getString(R.string.ui_inspector_panel, context.getString(R.string.ui_adjustments)))
            click(context.getString(R.string.ui_adjustment_add_noise))
            progress(context.getString(R.string.ui_adjustment_noise_amount), 77f)
            click(context.getString(R.string.ui_adjustment_noise_gaussian))
            click(context.getString(R.string.ui_adjustment_noise_monochromatic))
            click(apply)
            awaitLayer { current -> current.state().layers.any { layer -> current.adjustmentStyleOrNull(layer.id)?.let {
                it.kind == AdjustmentKind.ADD_NOISE && it.noiseAmount == 77.0 && it.noiseGaussian && it.noiseMonochromatic
            } == true } }
            val id = editor.state().layers.single { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.ADD_NOISE }.id
            val created = checkNotNull(editor.adjustmentStyleOrNull(id))
            click(context.getString(R.string.ui_edit_selected_adjustment))
            click(context.getString(R.string.ui_adjustment_noise_monochromatic))
            click(apply)
            awaitLayer { it.adjustmentStyleOrNull(id)?.noiseMonochromatic == false }
            val edited = checkNotNull(editor.adjustmentStyleOrNull(id))
            check(edited.noiseSeed == created.noiseSeed)
            click(context.getString(R.string.ui_undo))
            awaitLayer { it.adjustmentStyleOrNull(id)?.let { value -> equalAdjustmentJson(value.toMacJson(), created.toMacJson()) } == true }
            click(context.getString(R.string.ui_redo))
            awaitLayer { it.adjustmentStyleOrNull(id)?.let { value -> equalAdjustmentJson(value.toMacJson(), edited.toMacJson()) } == true }
            editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
                try { File(evidenceDirectory, "add-noise-committed.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
            }
            checkpoint("add-noise-create-edit-history", "passed", JSONObject().put("layerId", id)
                .put("created", created.toMacJson()).put("edited", edited.toMacJson()))
            return "AddNoise Inspector create+amount77+Gaussian+Monochromatic+immediateEdit+stableSeed+UndoRedo PASS; input=accessibilityActions; workspace=$workspaceToken; evidence=$evidenceDirectory"
        }
        checkpoint("text-create-edit-history", "started")
        val initialIds = editor.state().layers.map { it.id }.toSet()
        val initialHueLayers = editor.state().layers.count { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.HUE_SATURATION }
        click(context.getString(R.string.ui_tool_shortcut, context.getString(R.string.ui_tool_type), "T"))
        // Opening the phone Inspector shrinks the tool rail and can scroll the Type
        // button out of its accessibility viewport. Verify the resulting editor draft.
        waitFor("Type tool did not open the new text draft") { node(context.getString(R.string.ui_text_layer)) != null }
        val content = "中文🚀 composited"
        field(context.getString(R.string.ui_text_content), content)
        val apply = context.getString(R.string.ui_apply)
        click(apply)
        awaitLayer { current -> current.state().layers.any { it.id !in initialIds && current.textStyleOrNull(it.id)?.content == content } }
        var textLayer = editor.state().layers.first { it.id !in initialIds && editor.textStyleOrNull(it.id)?.content == content }
        val committedText = checkNotNull(editor.textStyleOrNull(textLayer.id))
        click(context.getString(R.string.ui_inspector_panel, context.getString(R.string.ui_tool_type)))
        click(context.getString(R.string.ui_edit_selected_text))
        field(context.getString(R.string.ui_text_content), "中文🚀 edited")
        click(apply)
        awaitLayer { editor.textStyleOrNull(textLayer.id)?.content == "中文🚀 edited" }
        click(context.getString(R.string.ui_undo))
        awaitLayer { it.textStyleOrNull(textLayer.id) == committedText }
        click(context.getString(R.string.ui_redo))
        awaitLayer { it.textStyleOrNull(textLayer.id)?.content == "中文🚀 edited" }
        checkpoint("text-create-edit-history", "passed")

        checkpoint("invert-exposure-edit-history", "started")
        click(context.getString(R.string.ui_inspector_panel, context.getString(R.string.ui_adjustments)))
        click(context.getString(R.string.ui_adjustment_invert))
        waitFor("Invert was not added from Inspector") {
            editor.state().layers.any { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.INVERT }
        }
        click(context.getString(R.string.ui_adjustment_exposure_kind))
        click(apply)
        waitFor("Exposure was not added from Inspector") {
            editor.state().layers.any { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.EXPOSURE }
        }
        val adjustmentLayer = editor.state().layers.last { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.EXPOSURE }
        click(context.getString(R.string.ui_edit_selected_adjustment))
        val exposureLabel = context.getString(R.string.ui_field_value, context.getString(R.string.ui_adjustment_exposure))
        waitFor("Adjustment editor did not switch to Exposure") { node(exposureLabel) != null }
        var exposureSlider: AccessibilityNodeInfo? = null
        waitFor("Exposure slider with -20..20 range was not exposed") {
            exposureSlider = findExposureSlider(editorPhysicalDisplayRoot(instrumentation))
            exposureSlider != null
        }
        checkNotNull(exposureSlider).let { progress ->
            check(progress.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1.25f)
            })) { "Exposure slider rejected progress" }
        }
        click(apply)
        waitFor("Exposure edit was not committed") {
            editor.adjustmentStyleOrNull(adjustmentLayer.id)?.let { it.kind == AdjustmentKind.EXPOSURE && it.exposure > 0.0 } == true
        }
        val editedAdjustment = checkNotNull(editor.adjustmentStyleOrNull(adjustmentLayer.id))
        click(context.getString(R.string.ui_undo))
        awaitLayer { it.adjustmentStyleOrNull(adjustmentLayer.id)?.let { value ->
            value.kind == AdjustmentKind.EXPOSURE && value.exposure == 0.0 } == true }
        click(context.getString(R.string.ui_redo))
        awaitLayer { current -> current.adjustmentStyleOrNull(adjustmentLayer.id)?.let {
            it.kind == editedAdjustment.kind && equalAdjustmentJson(it.toMacJson(), editedAdjustment.toMacJson())
        } == true }
        checkpoint("invert-exposure-edit-history", "passed")
        checkpoint("curves-point", "started")
        click(context.getString(R.string.ui_adjustment_curves))
        click(context.getString(R.string.ui_adjustment_curve_add))
        click(apply)
        waitFor("Curve point edit was not committed") { editor.state().layers.any { layer ->
            editor.adjustmentStyleOrNull(layer.id)?.let { it.kind == AdjustmentKind.CURVES && it.curves[0].size == 3 } == true
        } }
        checkpoint("curves-point", "passed")
        checkpoint("black-white-tint", "started")
        click(context.getString(R.string.ui_adjustment_black_white))
        click(context.getString(R.string.ui_adjustment_tint))
        click(apply)
        waitFor("B&W tint edit was not committed") { editor.state().layers.any { layer ->
            editor.adjustmentStyleOrNull(layer.id)?.let { it.kind == AdjustmentKind.BLACK_WHITE && it.tint } == true
        } }
        checkpoint("black-white-tint", "passed")
        checkpoint("color-balance-luminosity", "started")
        click(context.getString(R.string.ui_adjustment_color_balance))
        click(context.getString(R.string.ui_adjustment_preserve_luminosity))
        click(apply)
        waitFor("Color balance preserve luminosity edit was not committed") { editor.state().layers.any { layer ->
            editor.adjustmentStyleOrNull(layer.id)?.let { it.kind == AdjustmentKind.COLOR_BALANCE && !it.preserveLuminosity } == true
        } }
        checkpoint("color-balance-luminosity", "passed")
        checkpoint("hue-range-repeat-apply-immediate-colorize-edit", "started")
        click(context.getString(R.string.ui_adjustment_hue_saturation))
        click(context.getString(R.string.ui_hue_range_reds))
        editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
            try { File(evidenceDirectory, "p99-hsv-editor.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        click(context.getString(R.string.ui_adjustment_invert_range))
        revealButton(apply).let { button ->
            check(button.isEnabled) { "Apply was not ready for the hue edit" }
            check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
        waitFor("Hue/Saturation range edit was not committed") { editor.state().layers.any { layer ->
            editor.adjustmentStyleOrNull(layer.id)?.let { it.kind == AdjustmentKind.HUE_SATURATION &&
                it.hsv.range == HueRange.REDS && it.hsv.invertRange } == true
        } }
        click(context.getString(R.string.ui_edit_selected_adjustment))
        check(editor.state().layers.count { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.HUE_SATURATION } == initialHueLayers + 1) {
            "Repeated Apply created duplicate hue adjustment layers"
        }
        click(context.getString(R.string.ui_adjustment_colorize))
        click(apply)
        waitFor("Hue/Saturation colorize reset was not committed") { editor.state().layers.any { layer ->
            editor.adjustmentStyleOrNull(layer.id)?.let { it.kind == AdjustmentKind.HUE_SATURATION &&
                it.hsv.colorize && it.hsv.range == HueRange.MASTER && it.hsv.current.saturation == 25.0 } == true
        } }
        checkpoint("hue-range-repeat-apply-immediate-colorize-edit", "passed")
        checkpoint("gradient-map-endpoints-immediate-edit-history", "started")
        click(context.getString(R.string.ui_adjustment_gradient_map))
        click(context.getString(R.string.ui_adjustment_shadow_color))
        field(context.getString(R.string.ui_hex_color), "19334D")
        click(context.getString(R.string.ui_ok))
        click(context.getString(R.string.ui_adjustment_highlight_color))
        field(context.getString(R.string.ui_hex_color), "E6CC99")
        click(context.getString(R.string.ui_ok))
        click(context.getString(R.string.ui_adjustment_reverse))
        editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
            try { File(evidenceDirectory, "p100-gradient-editor.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        click(apply)
        awaitLayer { current -> current.state().layers.any { layer ->
            current.adjustmentStyleOrNull(layer.id)?.let { style ->
                style.kind == AdjustmentKind.GRADIENT_MAP && style.gradientMap.reversed &&
                    listOf(style.gradientMap.shadows.red, style.gradientMap.shadows.green, style.gradientMap.shadows.blue,
                        style.gradientMap.highlights.red, style.gradientMap.highlights.green, style.gradientMap.highlights.blue)
                        .zip(listOf(25, 51, 77, 230, 204, 153)).all { (actual, expected) -> kotlin.math.abs(actual - expected / 255.0) < .0001 }
            } == true
        } }
        val gradientLayer = editor.state().layers.first { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.GRADIENT_MAP }
        click(context.getString(R.string.ui_edit_selected_adjustment))
        click(context.getString(R.string.ui_adjustment_reverse))
        click(apply)
        awaitLayer { editor.adjustmentStyleOrNull(gradientLayer.id)?.gradientMap?.reversed == false }
        click(context.getString(R.string.ui_undo))
        awaitLayer { it.adjustmentStyleOrNull(gradientLayer.id)?.gradientMap?.reversed == true }
        click(context.getString(R.string.ui_redo))
        awaitLayer { it.adjustmentStyleOrNull(gradientLayer.id)?.gradientMap?.reversed == false }
        checkpoint("gradient-map-endpoints-immediate-edit-history", "passed")
        checkpoint("grain-sliders-numeric-validation-history", "started")
        click(context.getString(R.string.ui_adjustment_grain))
        progress(context.getString(R.string.ui_grain_amount), 61.4f)
        progress(context.getString(R.string.ui_grain_size), kotlin.math.ln(2.76).toFloat())
        progress(context.getString(R.string.ui_grain_roughness), 67.8f)
        click(apply)
        awaitLayer { current -> current.state().layers.any { layer -> current.adjustmentStyleOrNull(layer.id)?.let {
            it.kind == AdjustmentKind.GRAIN && it.grain.amount == 61.0 && it.grain.size == 2.8 && it.grain.roughness == 68.0
        } == true } }
        val grainLayer = editor.state().layers.first { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.GRAIN }
        val originalGrain = checkNotNull(editor.adjustmentStyleOrNull(grainLayer.id)).grain
        click(context.getString(R.string.ui_edit_selected_adjustment))
        val amountField = context.getString(R.string.ui_field_value, context.getString(R.string.ui_grain_amount))
        focusField(amountField)
        field(amountField, "101")
        check(!revealButton(apply).isEnabled) { "Invalid grain input did not disable Apply" }
        focusField(context.getString(R.string.ui_field_value, context.getString(R.string.ui_grain_size)))
        check(!revealButton(apply).isEnabled) { "Invalid grain input was discarded when focus moved" }
        editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
            try { File(evidenceDirectory, "p101-grain-editor-invalid.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        focusField(amountField)
        field(amountField, "31.125")
        check(revealButton(apply).isEnabled) { "Valid grain input did not enable Apply" }
        editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
            try { File(evidenceDirectory, "p101-grain-editor-valid.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        click(apply)
        awaitLayer { editor.adjustmentStyleOrNull(grainLayer.id)?.grain == originalGrain.copy(amount = 31.125) }
        click(context.getString(R.string.ui_undo))
        awaitLayer { it.adjustmentStyleOrNull(grainLayer.id)?.grain == originalGrain }
        click(context.getString(R.string.ui_redo))
        awaitLayer { it.adjustmentStyleOrNull(grainLayer.id)?.grain == originalGrain.copy(amount = 31.125) }
        checkpoint("grain-sliders-numeric-validation-history", "passed")
        checkpoint("add-noise-controls", "started")
        click(context.getString(R.string.ui_adjustment_add_noise))
        progress(context.getString(R.string.ui_adjustment_noise_amount), 77f)
        click(context.getString(R.string.ui_adjustment_noise_gaussian))
        click(context.getString(R.string.ui_adjustment_noise_monochromatic))
        click(apply)
        awaitLayer { current -> current.state().layers.any { layer -> current.adjustmentStyleOrNull(layer.id)?.let {
            it.kind == AdjustmentKind.ADD_NOISE && it.noiseAmount > 70.0 && it.noiseGaussian && it.noiseMonochromatic
        } == true } }
        checkpoint("add-noise-controls", "passed")
        checkpoint("levels-rgb-red-edit-history", "started")
        val previousLevelIds = editor.state().layers.map { it.id }.toSet()
        click(context.getString(R.string.ui_adjustment_levels))
        progress(context.getString(R.string.ui_adjustment_black), 24f)
        progress(context.getString(R.string.ui_adjustment_white), 231f)
        progress(context.getString(R.string.ui_adjustment_gamma), 1.25f)
        progress(context.getString(R.string.ui_adjustment_output_black), 12f)
        progress(context.getString(R.string.ui_adjustment_output_white), 244f)
        val expectedRgb = AdjustmentLevelRange(24.0, 1.25, 231.0, 12.0, 244.0)
        click(apply)
        awaitLayer { current -> current.state().layers.any { layer ->
            layer.id !in previousLevelIds && current.adjustmentStyleOrNull(layer.id)?.let {
                it.kind == AdjustmentKind.LEVELS && it.channel == 0 && it.ranges[0] == expectedRgb &&
                    it.ranges.drop(1).all { range -> range == AdjustmentLevelRange() }
            } == true
        } }
        val levelsLayer = editor.state().layers.first { it.id !in previousLevelIds &&
            editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.LEVELS }
        val originalLevels = checkNotNull(editor.adjustmentStyleOrNull(levelsLayer.id))
        click(context.getString(R.string.ui_edit_selected_adjustment))
        click(context.getString(R.string.ui_adjustment_red))
        progress(context.getString(R.string.ui_adjustment_black), 18f)
        progress(context.getString(R.string.ui_adjustment_white), 220f)
        progress(context.getString(R.string.ui_adjustment_gamma), 1.75f)
        progress(context.getString(R.string.ui_adjustment_output_black), 8f)
        progress(context.getString(R.string.ui_adjustment_output_white), 240f)
        val expectedRed = AdjustmentLevelRange(18.0, 1.75, 220.0, 8.0, 240.0)
        click(apply)
        awaitLayer { current -> current.adjustmentStyleOrNull(levelsLayer.id)?.let {
            it.kind == AdjustmentKind.LEVELS && it.channel == 1 && it.ranges[0] == expectedRgb &&
                it.ranges[1] == expectedRed && it.ranges.drop(2).all { range -> range == AdjustmentLevelRange() }
        } == true }
        val editedLevels = checkNotNull(editor.adjustmentStyleOrNull(levelsLayer.id))
        click(context.getString(R.string.ui_undo))
        awaitLayer { current -> current.adjustmentStyleOrNull(levelsLayer.id)?.let {
            equalAdjustmentJson(it.toMacJson(), originalLevels.toMacJson())
        } == true }
        click(context.getString(R.string.ui_redo))
        awaitLayer { current -> current.adjustmentStyleOrNull(levelsLayer.id)?.let {
            equalAdjustmentJson(it.toMacJson(), editedLevels.toMacJson())
        } == true }
        checkpoint("levels-rgb-red-edit-history", "passed", JSONObject().put("layerId", levelsLayer.id)
            .put("original", originalLevels.toMacJson()).put("edited", editedLevels.toMacJson()))
        checkpoint("all-typed-ui-stages", "passed")
        return "display=0;workspace=$workspaceToken;input=accessibilityActionsNotIME;steps=${File(evidenceDirectory, "steps.json")};typedLayerUi=InspectorNewText+ChineseEmojiACTION_SET_TEXT+commit+edit+undoRedo verified; adjustmentUi=Invert+ExposureEdit+undoRedo+CurvesPoint+BlackWhiteTint+ColorBalanceLuminosity+HueRangeInvert+RepeatedApplySingleCommit+ImmediateColorizeEdit+GradientEndpointHex+Reverse+ImmediateEdit+UndoRedo+GrainLogSize+RoundedSliders+InvalidInputGateAcrossFocus+ExactNumeric+StableSeed+LevelsRgbFiveParameters+ImmediateEditRedFiveParameters+ExactUndoRedo verified"
    } catch (failure: Throwable) {
        originalFailure = failure
        runCatching { checkpoint(activeStage, "failed", JSONObject().put("error", failure.toString())) }
        preserveFailure(failure.toString())
        throw failure
    } finally {
        try {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            workspace.closeAsync().get(12, TimeUnit.SECONDS)
        } catch (cleanup: Throwable) {
            val failure = originalFailure
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}
