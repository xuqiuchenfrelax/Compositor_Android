package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Passive failure-only evidence. Recording failures must never replace the original test exception. */
internal class EditorUiTrace(val label: String, private val ownState: (() -> JSONObject)? = null) {
    private val samples = JSONArray()
    private val clicks = JSONArray()
    fun beforeClick(ancestor: AccessibilityNodeInfo?) {
        runCatching { clicks.put(JSONObject().put("stage", "beforeAction").put("nanoTime", System.nanoTime())
            .put("ancestor", editorDiagnosticNode(ancestor)).put("mainOwnState", ownState?.invoke() ?: JSONObject.NULL)) }
    }
    fun observe(stage: String, root: AccessibilityNodeInfo?, target: AccessibilityNodeInfo?, menu: AccessibilityNodeInfo?) {
        runCatching {
            samples.put(JSONObject().put("stage", stage).put("nanoTime", System.nanoTime())
                .put("root", editorDiagnosticNode(root)).put("lookup", editorDiagnosticNode(target))
                .put("menuLookup", editorDiagnosticNode(menu)))
        }
    }
    fun click(ancestor: AccessibilityNodeInfo?, result: Boolean) {
        runCatching { clicks.put(JSONObject().put("stage", "afterAction").put("nanoTime", System.nanoTime())
            .put("ancestor", editorDiagnosticNode(ancestor)).put("actionResult", result)
            .put("mainOwnStateAtAction", ownState?.invoke() ?: JSONObject.NULL)) }
    }
    fun snapshot(): JSONObject = JSONObject().put("label", label).put("samples", samples).put("actualClicks", clicks)
}

private fun editorDiagnosticNode(node: AccessibilityNodeInfo?): Any {
    if (node == null) return JSONObject.NULL
    val bounds = Rect().also(node::getBoundsInScreen)
    return JSONObject().put("windowId", node.windowId).put("package", node.packageName ?: JSONObject.NULL)
        .put("class", node.className ?: JSONObject.NULL).put("resourceId", node.viewIdResourceName ?: JSONObject.NULL)
        .put("text", node.text ?: JSONObject.NULL).put("description", node.contentDescription ?: JSONObject.NULL)
        .put("enabled", node.isEnabled).put("clickable", node.isClickable).put("visible", node.isVisibleToUser)
        .put("focused", node.isFocused).put("selected", node.isSelected)
        .put("checkable", node.isCheckable).put("checked", node.isChecked).put("bounds", bounds.toShortString())
        .put("supportsClick", node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
}

internal fun captureEditorUiFailure(instrumentation: Instrumentation, activity: MainActivity,
    error: Exception, traces: List<EditorUiTrace>) {
    runCatching {
        val prefix = "p22-menu-failure02-${System.nanoTime()}"
        val record = JSONObject().put("schema", 1).put("error", error.toString())
            .put("elapsedRealtimeMillis", android.os.SystemClock.elapsedRealtime())
            .put("scope", "Failure-only passive evidence before finally; original lookup/actions/assertions/20s timeout retained")
            .put("traces", JSONArray(traces.map { it.snapshot() }))
        fun capture(name: String, action: () -> Any) {
            runCatching { record.put(name, action()) }.onFailure { record.put("${name}Error", it.toString()) }
        }
        capture("freshActiveWindow") {
            var count = 0
            fun tree(node: AccessibilityNodeInfo, depth: Int): JSONObject {
                val value = editorDiagnosticNode(node) as JSONObject
                count++
                val children = JSONArray()
                for (index in 0 until node.childCount) {
                    if (count >= 8192 || depth >= 128) { value.put("truncated", true); break }
                    node.getChild(index)?.let { children.put(tree(it, depth + 1)) }
                }
                return value.put("children", children)
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let { tree(it, 0) } ?: JSONObject.NULL
        }
        capture("windows") { JSONArray(instrumentation.uiAutomation.windows.map {
            JSONObject().put("id", it.id).put("type", it.type).put("layer", it.layer)
                .put("active", it.isActive).put("focused", it.isFocused).put("root", editorDiagnosticNode(it.root))
        }) }
        capture("ownState") { editorUiOwnState(instrumentation, activity) }
        capture("screenshot") {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(instrumentation.targetContext.filesDir, "$prefix.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
            "$prefix.png"
        }
        File(instrumentation.targetContext.filesDir, "$prefix.json").writeText(record.toString(2))
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Editor menu failure-time diagnostic: files/$prefix.json") })
    }.onFailure { android.util.Log.e("CompositorEditorUi", "Could not preserve menu diagnosis", it) }
}

internal fun editorUiOwnState(instrumentation: Instrumentation, activity: MainActivity): JSONObject {
    lateinit var value: JSONObject
    instrumentation.runOnMainSync {
        val workspace = activity.retainedWorkspaceForTest()
        val state = workspace.snapshot()
        val model = activity.retainedEditorForTest()
        val project = activity.retainedProjectFlowForTest().state
        val canvases = JSONArray()
        fun collect(view: View) {
            if (view is CanvasView) {
                val bound = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
                    .get(view) as NativeEditor
                canvases.put(JSONObject().put("identity", System.identityHashCode(view))
                    .put("boundEditorIdentity", System.identityHashCode(bound)).put("matchesModel", bound === model)
                    .put("matchesWorkspace", bound === state.editor).put("attached", view.isAttachedToWindow)
                    .put("surfaceValid", view.holder.surface.isValid).put("width", view.width).put("height", view.height)
                    .put("shown", view.isShown).put("windowFocus", view.hasWindowFocus())
                    .put("editing", view.editingInProgress).put("stroke", view.strokeInProgress)
                    .put("toolMode", view.toolMode.name).put("editingMask", view.editingMask).put("layerId", view.layerId))
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(activity.window.decorView)
        fun editor(editor: NativeEditor?): Any = editor?.let {
            JSONObject().put("identity", System.identityHashCode(it)).put("state", it.state().toString())
                .put("selection", runCatching { it.selection().toString() }.getOrElse { error -> error.toString() })
                .put("pending", it.pendingEditCount).put("strokeReservations", it.strokeReservations().size)
        } ?: JSONObject.NULL
        value = JSONObject().put("activityIdentity", System.identityHashCode(activity))
            .put("lifecycle", activity.lifecycle.currentState.name).put("finishing", activity.isFinishing)
            .put("destroyed", activity.isDestroyed).put("windowFocus", activity.hasWindowFocus())
            .put("generation", state.generation).put("loading", state.loading).put("frozen", state.resourceGrowthFrozen)
            .put("workspaceEditor", editor(state.editor)).put("modelEditor", editor(model))
            .put("modelMatchesWorkspace", model === state.editor).put("canvases", canvases)
            .put("workspaceError", state.recoveryError ?: state.saveError ?: JSONObject.NULL)
            .put("projectBusy", project.busy).put("projectPhase", project.phase.name)
            .put("projectError", project.error ?: JSONObject.NULL)
            .put("imageState", activity.retainedImageRequestsForTest().state.toString())
    }
    return value
}
