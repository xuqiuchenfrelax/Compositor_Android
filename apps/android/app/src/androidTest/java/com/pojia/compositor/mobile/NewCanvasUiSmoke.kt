package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Run only on a disposable emulator workspace; exercises the actual New button, fields, dialogs and recreation. */
internal fun newCanvasUiSmoke(instrumentation: Instrumentation): String {
    var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    fun available(label: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != instrumentation.targetContext.packageName) return null
        return find(root, label)
    }
    fun actionableButton(label: String): AccessibilityNodeInfo? {
        var node = available(label)
        repeat(12) {
            val current = node ?: return null
            val hasClick = current.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
            // Compose Text children often have neither the button's action nor its disabled semantics.
            // A disabled button may omit ACTION_CLICK but still exposes its Button role/class.
            if (current.isClickable || hasClick || current.className?.toString() == "android.widget.Button") {
                val bounds = Rect().also { current.getBoundsInScreen(it) }
                return current.takeIf { current.isVisibleToUser && !bounds.isEmpty }
            }
            node = current.parent
        }
        return null
    }
    fun click(label: String) {
        waitFor("Missing enabled action $label") {
            actionableButton(label)?.let { node -> node.isEnabled &&
                node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } } == true
        }
        // Obtain a fresh root and actionable ancestor after readiness, rather than clicking a prior modal's node.
        val node = checkNotNull(actionableButton(label))
        check(node.isEnabled && node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Unavailable action $label" }
        instrumentation.waitForIdleSync()
    }
    var fieldDiagnosticCaptured = false
    fun fieldDiagnostic(stage: String, label: String, value: String, matched: AccessibilityNodeInfo) {
        runCatching {
            fun properties(node: AccessibilityNodeInfo): JSONObject {
                val screen = Rect().also { node.getBoundsInScreen(it) }
                val parent = Rect().also { node.getBoundsInParent(it) }
                return JSONObject().put("text", node.text?.toString() ?: JSONObject.NULL)
                    .put("description", node.contentDescription?.toString() ?: JSONObject.NULL)
                    .put("class", node.className?.toString() ?: JSONObject.NULL)
                    .put("package", node.packageName?.toString() ?: JSONObject.NULL)
                    .put("viewId", node.viewIdResourceName ?: JSONObject.NULL).put("windowId", node.windowId)
                    .put("editable", node.isEditable).put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
                    .put("focused", node.isFocused).put("focusable", node.isFocusable)
                    .put("accessibilityFocused", node.isAccessibilityFocused).put("clickable", node.isClickable)
                    .put("selected", node.isSelected).put("childCount", node.childCount)
                    .put("boundsScreen", screen.toShortString()).put("boundsParent", parent.toShortString())
                    .put("actions", JSONArray(node.actionList.map {
                        JSONObject().put("id", it.id).put("label", it.label?.toString() ?: JSONObject.NULL)
                    }))
            }
            fun tree(node: AccessibilityNodeInfo?): Any {
                if (node == null) return JSONObject.NULL
                return properties(node).put("children", JSONArray((0 until node.childCount).map { tree(node.getChild(it)) }))
            }
            val ancestors = JSONArray()
            var ancestor = matched.parent
            while (ancestor != null) {
                ancestors.put(properties(ancestor))
                ancestor = ancestor.parent
            }
            val automation = instrumentation.uiAutomation
            val record = JSONObject().put("stage", stage).put("label", label).put("requestedValue", value)
                .put("matchedNode", tree(matched)).put("parentChain", ancestors)
                .put("activeRoot", tree(automation.rootInActiveWindow))
                .put("windows", JSONArray(automation.windows.map { window ->
                    val bounds = Rect().also { window.getBoundsInScreen(it) }
                    JSONObject().put("id", window.id).put("type", window.type).put("layer", window.layer)
                        .put("title", window.title?.toString() ?: JSONObject.NULL)
                        .put("active", window.isActive).put("focused", window.isFocused)
                        .put("bounds", bounds.toShortString()).put("root", tree(window.root))
                }))
            instrumentation.runOnMainSync {
                val workspace = activity.retainedWorkspaceForTest().snapshot()
                val editor = activity.retainedEditorForTest()
                val focus = activity.window.currentFocus
                record.put("flowState", activity.retainedProjectFlowForTest().state.toString())
                    .put("workspaceState", workspace.toString()).put("nativeState", editor?.state()?.toString() ?: JSONObject.NULL)
                    .put("canvasWidth", editor?.canvasWidth ?: JSONObject.NULL).put("canvasHeight", editor?.canvasHeight ?: JSONObject.NULL)
                    .put("activityWindowFocused", activity.hasWindowFocus()).put("decorVisibility", activity.window.decorView.visibility)
                    .put("focusView", focus?.javaClass?.name ?: JSONObject.NULL)
                    .put("focusViewId", focus?.id ?: JSONObject.NULL).put("focusViewEnabled", focus?.isEnabled ?: JSONObject.NULL)
            }
            val prefix = "p19-new-ui-field-diagnostic01-$stage-${System.nanoTime()}"
            val directory = instrumentation.targetContext.filesDir
            runCatching {
                automation.takeScreenshot()?.let { screenshot ->
                    try {
                        val file = File(directory, "$prefix.png")
                        file.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        record.put("screenshot", file.name)
                    } finally { screenshot.recycle() }
                }
            }.onFailure { record.put("screenshotError", it.toString()) }
            val file = File(directory, "$prefix.json")
            file.writeText(record.toString(2))
            android.util.Log.i("CompositorNewUiField", "Saved diagnostic ${file.name}")
        }.onFailure { android.util.Log.e("CompositorNewUiField", "Could not preserve $stage diagnostic", it) }
    }
    fun actionableField(label: String): AccessibilityNodeInfo? {
        var node = available(label)
        repeat(12) {
            val current = node ?: return null
            val hasSetText = current.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            // Compose field descriptions can be children of the editable node.
            if (current.isEditable && hasSetText) {
                val bounds = Rect().also { current.getBoundsInScreen(it) }
                return current.takeIf { current.isVisibleToUser && current.isEnabled && !bounds.isEmpty }
            }
            node = current.parent
        }
        return null
    }
    fun setField(label: String, value: String) {
        waitFor("Missing editable field $label") { actionableField(label) != null }
        val node = checkNotNull(actionableField(label))
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val diagnose = !fieldDiagnosticCaptured
        if (diagnose) { fieldDiagnosticCaptured = true; fieldDiagnostic("before-action", label, value, node) }
        try {
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "New field did not accept text: $label" }
        } catch (error: IllegalStateException) {
            if (diagnose) fieldDiagnostic("failed-action", label, value, node)
            throw error
        }
        instrumentation.waitForIdleSync()
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }
    fun canvas(): CanvasView? {
        var value: CanvasView? = null
        instrumentation.runOnMainSync { value = findCanvas(activity.window.decorView) }
        return value
    }
    waitFor("Workspace did not attach") { activity.retainedEditorForTest() != null && canvas()?.holder?.surface?.isValid == true }
    val workspace = activity.retainedWorkspaceForTest()
    val originalEditor = checkNotNull(activity.retainedEditorForTest())
    val original = originalEditor.state()
    val originalLease = checkNotNull(workspace.lease(originalEditor))
    try {
        click("New")
        waitFor("New dialog missing") { activity.retainedProjectFlowForTest().state.newCanvas != null }
        check(activity.retainedProjectFlowForTest().state.newCanvas?.draft == NewCanvasPreferences.load(activity))
        setField("New canvas width", "0")
        waitFor("Invalid size did not disable Create") { actionableButton("Create canvas")?.isEnabled == false }
        setField("New canvas width", "23")
        setField("New canvas height", "13")
        setField("New canvas resolution", "300")
        val retained = activity.retainedProjectFlowForTest()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity.recreate() }
        activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        instrumentation.removeMonitor(monitor)
        waitFor("New dialog draft lost on recreation") { activity.retainedProjectFlowForTest().state.newCanvas?.draft?.spec()?.width == 23 }
        check(activity.retainedProjectFlowForTest() === retained && activity.retainedEditorForTest() === originalEditor)
        click("Cancel")
        check(originalEditor.state() == original && originalLease.isCurrent())

        // Force the fixture into dirty state so the real explicit-choice dialog must appear.
        instrumentation.runOnMainSync { originalEditor.addLayer() }
        val dirty = originalEditor.state()
        click("New")
        setField("New canvas width", "23"); setField("New canvas height", "13")
        setField("New canvas resolution", "300")
        click("Create canvas")
        waitFor("Dirty choice dialog missing") { available("Save and create") != null && available("Discard changes and create") != null }
        click("Cancel")
        check(activity.retainedEditorForTest() === originalEditor && originalEditor.state() == dirty && originalLease.isCurrent())

        click("New")
        setField("New canvas width", "23"); setField("New canvas height", "13")
        setField("New canvas resolution", "300")
        click("Create canvas"); click("Save and create")
        if (activity.retainedProjectFlowForTest().state.projectCopy != null)
            click(instrumentation.targetContext.getString(R.string.ui_save_project_copy_confirm))
        waitFor("New canvas was not published to actual Activity") {
            activity.retainedEditorForTest()?.let { it !== originalEditor && it.canvasWidth == 23 && it.canvasHeight == 13 } == true &&
                canvas()?.holder?.surface?.isValid == true && activity.retainedProjectFlowForTest().state.phase == ProjectFlowPhase.IDLE
        }
        val created = checkNotNull(activity.retainedEditorForTest())
        check(created.state().document.resolution == 300.0 && created.state().layers.single().name == "Layer 1")
        check(!originalLease.isCurrent())
        check(workspace.snapshot().projectDirty && workspace.snapshot().projectBaseline == null && workspace.snapshot().sourceIdentity == null)
        check(available("Canvas dimensions") != null)
        return "Actual New UI: default dialog, invalid Create disabled, real numeric fields, retained draft through Activity recreation, cancel and dirty Cancel preserve old document, Named Save and create publishes rectangular DPI canvas, old Canvas lease expires and new document remains unsaved"
    } finally { instrumentation.runOnMainSync { activity.finish() } }
}
