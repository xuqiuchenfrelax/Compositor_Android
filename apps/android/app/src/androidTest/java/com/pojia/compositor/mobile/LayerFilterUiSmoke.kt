package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Real panel navigation and accessibility actions in a seeded, isolated recovery workspace. */
internal fun layerFilterUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val previousLanguage = AppLanguages.current(context)
    val results = JSONArray()
    val evidence = File(context.cacheDir, "p145-filter-ui-${System.nanoTime()}-evidence").apply { check(mkdirs()) }
    val workspaceRecords = JSONArray()
    val cleanupFailures = JSONArray()
    val report = JSONObject().put("passed", false).put("cleanupPassed", false).put("results", results)
        .put("cases", JSONArray()).put("workspaces", workspaceRecords).put("cleanupFailures", cleanupFailures)
    var primaryFailure: Throwable? = null
    fun save() { File(evidence, "report.json").writeText(report.toString(2)) }
    fun describe(node: AccessibilityNodeInfo): JSONObject = JSONObject()
        .put("text", node.text ?: JSONObject.NULL).put("description", node.contentDescription ?: JSONObject.NULL)
        .put("bounds", Rect().also(node::getBoundsInScreen).toShortString())
        .put("visible", node.isVisibleToUser).put("scrollable", node.isScrollable)
        .put("actions", JSONArray(node.actionList.map { it.id }))
    fun capture(name: String) {
        val automation = editorUiAutomation(instrumentation)
        automation.clearCache()
        var count = 0
        fun tree(node: AccessibilityNodeInfo, depth: Int): JSONObject {
            check(++count <= 4096 && depth < 64) { "Filter evidence tree exceeded its bound" }
            val children = JSONArray()
            for (index in 0 until node.childCount) node.getChild(index)?.let { children.put(tree(it, depth + 1)) }
            return describe(node).put("children", children)
        }
        val windows = JSONArray()
        for (window in automation.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()) {
            val root = window.root
            if (root?.packageName?.toString() == context.packageName) windows.put(JSONObject()
                .put("id", window.id).put("type", window.type).put("layer", window.layer)
                .put("focused", window.isFocused).put("tree", tree(root, 0)))
        }
        File(evidence, "$name-tree.json").writeText(windows.toString(2))
        val image = checkNotNull(automation.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { image.recycle() }
        save()
    }
    save()
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            instrumentation.runOnMainSync { AppLanguages.set(context, language) }
            val token = "p126-filter-ui-${System.nanoTime()}"
            val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
            NativeEditor(96).use { editor -> seedLayerFilterDocument(editor); editor.save(File(directory, "current.checkpoint")) }
            val workspaceRecord = JSONObject().put("token", token).put("started", false).put("closed", false)
            workspaceRecords.put(workspaceRecord)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
            workspaceRecord.put("started", true)
            val workspace = activity.retainedWorkspaceForTest()
            var workspaceFailure: Throwable? = null
            fun waitFor(message: String, condition: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (System.nanoTime() < deadline) {
                    editorUiAutomation(instrumentation).clearCache()
                    if (condition()) return
                    Thread.sleep(30)
                }
                error(message)
            }
            fun node(label: String) = findEditorUiNode(editorPhysicalDisplayRoot(instrumentation), label)
            fun click(label: String) {
                waitFor("Missing filter control: $label") { node(label) != null }
                check(clickEditorUiNode(node(label))) { "Could not click $label" }
                instrumentation.waitForIdleSync()
            }
            fun editorRoot(): AccessibilityNodeInfo? {
                // Configure window retrieval, then exclude same-package menus/cursor popups.
                editorPhysicalDisplayRoot(instrumentation)
                val roots = editorUiAutomation(instrumentation).windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.displayId == Display.DEFAULT_DISPLAY }
                    .mapNotNull { it.root }.filter { root -> root.packageName?.toString() == context.packageName &&
                        findEditorUiNode(root, context.getString(R.string.ui_canvas)) != null &&
                        findEditorUiNode(root, context.getString(R.string.ui_layer_list)) != null }
                check(roots.size <= 1) { "Ambiguous filter editor roots on physical display 0" }
                return roots.singleOrNull()
            }
            val marker = "\uE000"
            val rowFormat = context.getString(R.string.ui_select_layer, marker)
            check(rowFormat.indexOf(marker) == rowFormat.lastIndexOf(marker) && marker in rowFormat)
            val prefix = rowFormat.substringBefore(marker)
            val suffix = rowFormat.substringAfter(marker)
            data class ListSample(val node: AccessibilityNodeInfo, val rows: Set<String>, val fingerprint: String, val record: JSONObject)
            fun sample(): ListSample {
                val automation = editorUiAutomation(instrumentation)
                automation.clearCache()
                val root = checkNotNull(editorRoot()) { "Filter editor/list root missing" }
                val list = checkNotNull(findEditorUiNode(root, context.getString(R.string.ui_layer_list)))
                val viewport = Rect().also(list::getBoundsInScreen)
                check(list.isVisibleToUser && viewport.width() > 0 && viewport.height() > 0) { "Filter list has no visible viewport" }
                val rows = LinkedHashSet<String>()
                val visible = JSONArray()
                var count = 0
                fun visit(current: AccessibilityNodeInfo, depth: Int) {
                    check(++count <= 2048 && depth < 64)
                    val bounds = Rect().also(current::getBoundsInScreen)
                    if (current.isVisibleToUser && Rect.intersects(viewport, bounds)) {
                        visible.put(describe(current))
                        val description = current.contentDescription?.toString()
                        if (description != null && description.startsWith(prefix) && description.endsWith(suffix)) {
                            val title = description.substring(prefix.length, description.length - suffix.length)
                            check(title.isNotEmpty()) { "Empty accessible layer row label" }
                            rows.add(title)
                        }
                    }
                    for (index in 0 until current.childCount) current.getChild(index)?.let { visit(it, depth + 1) }
                }
                visit(list, 0)
                return ListSample(list, rows, visible.toString(), JSONObject().put("list", describe(list))
                    .put("rows", JSONArray(rows.toList())).put("visibleNodes", visible))
            }
            fun assertRows(case: JSONObject, expected: Set<String>) {
                val trace = JSONArray().also { case.put("scrolls", it) }
                val seen = LinkedHashSet<String>()
                instrumentation.waitForIdleSync()
                editorUiAutomation(instrumentation).waitForIdle(100, 5_000)
                waitFor("Filter editor did not return after the menu closed") { editorRoot() != null }
                fun boundary(action: Int, collect: Boolean, phase: String) {
                    repeat(32) { step ->
                        val before = sample()
                        if (collect) seen.addAll(before.rows)
                        val record = before.record.put("phase", phase).put("step", step).put("action", action)
                        trace.put(record)
                        if (before.node.actionList.none { it.id == action }) {
                            record.put("boundary", "action-not-advertised")
                            save()
                            return
                        }
                        val accepted = before.node.performAction(action)
                        record.put("accepted", accepted)
                        instrumentation.waitForIdleSync()
                        editorUiAutomation(instrumentation).waitForIdle(100, 5_000)
                        if (!accepted) {
                            val after = sample()
                            check(after.fingerprint == before.fingerprint) { "Rejected filter scroll changed the viewport" }
                            if (collect) seen.addAll(after.rows)
                            record.put("boundary", "action-rejected-stable-viewport").put("after", after.record)
                            save()
                            return
                        }
                        waitFor("Accepted filter scroll made no visible progress in $phase step $step") {
                            val after = sample()
                            after.fingerprint != before.fingerprint || after.node.actionList.none { it.id == action }
                        }
                        record.put("after", sample().record)
                        save()
                    }
                    error("Filter list did not reach the $phase boundary within 32 scrolls")
                }
                // Collect every exposed row from top through bottom, including unexpected names.
                // A single small viewport must not stand in for the complete filtered list.
                boundary(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, false, "rewind")
                boundary(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, true, "collect")
                case.put("actualRows", JSONArray(seen.toList())).put("expectedRows", JSONArray(expected.toList()))
                check(seen == expected) { "Complete filtered rows $seen != $expected" }
                boundary(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, false, "restore-top")
                case.put("passed", true)
                save()
            }
            val filterButton = context.getString(R.string.ui_filter_layers)
            fun selectFilter(resource: Int) {
                click(filterButton)
                click(context.getString(R.string.ui_filter_all_layers))
                click(filterButton)
                click(context.getString(resource))
                check(editorUiAutomation(instrumentation).performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                instrumentation.waitForIdleSync()
            }
            try {
                check(workspace.recoveryDirectoryForTest().canonicalFile == directory.canonicalFile)
                waitFor("Seeded filter document did not restore") { activity.retainedEditorForTest()?.state()?.layers?.size == 6 }
                check(activity.resources.configuration.locales[0].language == language.tag.substringBefore('-'))
                revealInspectorPanel(instrumentation, InspectorPanel.LAYERS)
                val cases = listOf(R.string.ui_filter_images to setOf("Image", "Blank"),
                    R.string.ui_filter_shapes to setOf("Line"), R.string.ui_filter_text to setOf("中文 Text"),
                    R.string.ui_filter_folders to setOf("Folder", "中文 Text", "Line"),
                    R.string.ui_filter_adjustments to setOf("Tone"), R.string.ui_filter_selected to setOf("中文 Text"))
                for ((resource, expected) in cases) {
                    val case = JSONObject().put("language", language.tag).put("filter", context.getString(resource))
                    report.getJSONArray("cases").put(case)
                    selectFilter(resource)
                    assertRows(case, expected)
                    capture("${language.tag}-$resource")
                    results.put(JSONObject().put("language", language.tag).put("filter", context.getString(resource)).put("rows", JSONArray(expected.toList())))
                }
                click(filterButton); click(context.getString(R.string.ui_filter_all_layers))
                click(context.getString(R.string.ui_search_layers))
                val search = context.getString(R.string.ui_search_layers)
                waitFor("Search editor missing") { findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), search) != null }
                check(findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), search)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "中文")
                }))
                val searchCase = JSONObject().put("language", language.tag).put("query", "中文")
                report.getJSONArray("cases").put(searchCase)
                assertRows(searchCase, setOf("中文 Text"))
                capture("${language.tag}-search")
                results.put(JSONObject().put("language", language.tag).put("query", "中文").put("rows", JSONArray(listOf("中文 Text"))))
            } catch (failure: Throwable) {
                workspaceFailure = failure
                report.put("failure", failure.toString()).put("failureLanguage", language.tag)
                runCatching { capture("${language.tag}-failure") }.exceptionOrNull()?.let {
                    report.put("failureCaptureError", it.toString())
                }
                throw failure
            } finally {
                val failures = ArrayList<Throwable>()
                val finishFailure = runCatching { instrumentation.runOnMainSync { activity.finish() } }.exceptionOrNull()
                workspaceRecord.put("activityFinished", finishFailure == null)
                finishFailure?.let { failures.add(it); cleanupFailures.put(JSONObject().put("token", token)
                    .put("action", "finishActivity").put("failure", it.toString())) }
                val closeFailure = runCatching { workspace.closeAsync().get(15, TimeUnit.SECONDS) }.exceptionOrNull()
                workspaceRecord.put("closed", closeFailure == null)
                closeFailure?.let { failures.add(it); cleanupFailures.put(JSONObject().put("token", token)
                    .put("action", "closeWorkspace").put("failure", it.toString())) }
                save()
                if (failures.isNotEmpty()) {
                    val original = workspaceFailure
                    if (original != null) failures.forEach(original::addSuppressed)
                    else throw failures.first().also { first -> failures.drop(1).forEach(first::addSuppressed) }
                }
            }
        }
        report.put("passed", true)
    } catch (failure: Throwable) {
        primaryFailure = failure
        report.put("passed", false).put("failure", failure.toString())
        throw failure
    } finally {
        val languageFailure = runCatching {
            instrumentation.runOnMainSync { AppLanguages.set(context, previousLanguage) }
        }.exceptionOrNull()
        report.put("languageRestored", languageFailure == null)
        languageFailure?.let { cleanupFailures.put(JSONObject().put("action", "restoreLanguage").put("failure", it.toString())) }
        val closed = (0 until workspaceRecords.length()).all { index ->
            val record = workspaceRecords.getJSONObject(index)
            !record.getBoolean("started") || record.getBoolean("closed")
        }
        val cleanupPassed = closed && languageFailure == null && cleanupFailures.length() == 0
        report.put("cleanupPassed", cleanupPassed)
        if (!cleanupPassed) report.put("passed", false)
        save()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "P145 layer-filter evidence: cache/${evidence.name}/report.json\n")
        })
        languageFailure?.let { failure ->
            val original = primaryFailure
            if (original != null) original.addSuppressed(failure) else throw failure
        }
    }
    return "uiCases=${results.length()} languages=en,zh-CN isolatedWorkspace=true completeScrollUnion=true evidence=cache/${evidence.name}"
}
