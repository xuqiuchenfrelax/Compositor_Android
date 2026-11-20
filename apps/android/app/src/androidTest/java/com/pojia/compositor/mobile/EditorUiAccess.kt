package com.pojia.compositor.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.util.concurrent.TimeUnit

internal fun editorUiAutomation(instrumentation: Instrumentation) =
    instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

/** Instrumentation resources may retain the locale from before the Activity's language change. */
private fun editorUiContext(instrumentation: Instrumentation): Context {
    val context = instrumentation.targetContext
    val language = AppLanguages.current(context)
    if (language == AppLanguage.SYSTEM) return context
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(language.tag))
    })
}

/** Only the target app's topmost window on the physical default display may receive test actions. */
internal fun editorPhysicalDisplayRoot(instrumentation: Instrumentation): AccessibilityNodeInfo? {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        "Display-scoped editor UI tests require Android 11 or later"
    }
    val automation = editorUiAutomation(instrumentation)
    val service = automation.serviceInfo
    if (service.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
    }
    val targetPackage = instrumentation.targetContext.packageName
    return automation.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
        .filter { it.displayId == Display.DEFAULT_DISPLAY }
        .sortedByDescending { it.layer }
        .firstNotNullOfOrNull { window -> window.root?.takeIf { it.packageName?.toString() == targetPackage } }
}

internal fun findEditorUiNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
    if (node == null) return null
    if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
    for (index in 0 until node.childCount) findEditorUiNode(node.getChild(index), label)?.let { return it }
    return null
}

/** Match the editable owner and its label together; toolbar and panel titles can repeat field labels. */
internal fun findEditorEditableUiNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
    if (node == null) return null
    if (node.isEditable && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } &&
        (node.hintText?.toString() == label || findEditorUiNode(node, label) != null)) return node
    for (index in 0 until node.childCount) findEditorEditableUiNode(node.getChild(index), label)?.let { return it }
    return null
}

private fun clickableEditorUiNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    var current = node
    repeat(12) {
        val value = current ?: return null
        if (value.isClickable || value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            return value.takeIf { it.isEnabled && it.isVisibleToUser }
        }
        current = value.parent
    }
    return null
}

internal fun clickEditorUiNode(node: AccessibilityNodeInfo?, trace: EditorUiTrace? = null): Boolean {
    val target = clickableEditorUiNode(node) ?: return false
    trace?.beforeClick(target)
    val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    trace?.click(target, accepted)
    return accepted
}

/** The open file panel repeats the toolbar label as a non-clickable heading. */
private fun editorFilePanel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
    if (node == null) return null
    if (node.text?.toString() == label && clickableEditorUiNode(node) == null) {
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable) return parent
            parent = parent.parent
        }
    }
    for (index in 0 until node.childCount) editorFilePanel(node.getChild(index), label)?.let { return it }
    return null
}

/** The Inspector uses a fixed current-panel selector, rather than horizontally hidden tab buttons. */
internal fun revealInspectorPanel(instrumentation: Instrumentation, panel: InspectorPanel) {
    val context = editorUiContext(instrumentation)
    val title = context.panelTitle(panel)
    val selectedLabel = context.getString(R.string.ui_choose_inspector_panel, title)
    val legacyLabel = context.getString(R.string.ui_inspector_panel, title)
    var openedLayers = false
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadline) {
        editorUiAutomation(instrumentation).clearCache()
        val root = editorPhysicalDisplayRoot(instrumentation)
        if (findEditorUiNode(root, selectedLabel)?.isVisibleToUser == true) return
        val selector = InspectorPanel.entries.firstNotNullOfOrNull {
            findEditorUiNode(root, context.getString(R.string.ui_choose_inspector_panel, context.panelTitle(it)))
        }
        val target = when {
            // A popup owns its own topmost app window. Search only that root for its named item.
            selector == null && clickableEditorUiNode(findEditorUiNode(root, title)) != null -> findEditorUiNode(root, title)
            findEditorUiNode(root, legacyLabel)?.isVisibleToUser == true -> findEditorUiNode(root, legacyLabel)
            selector != null -> selector
            findEditorUiNode(root, context.getString(R.string.ui_expand_inspector_dock)) != null ->
                findEditorUiNode(root, context.getString(R.string.ui_expand_inspector_dock))
            !openedLayers -> findEditorUiNode(root, context.getString(R.string.ui_layers))?.also { openedLayers = true }
            else -> null
        }
        if (clickEditorUiNode(target)) instrumentation.waitForIdleSync()
        Thread.sleep(25)
    }
    val diagnostics = StringBuilder("requested=$title locales=${context.resources.configuration.locales.toLanguageTags()}\n")
    fun describe(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 40) return
        diagnostics.append(" ".repeat(depth)).append("text=").append(node.text)
            .append(" desc=").append(node.contentDescription).append(" visible=").append(node.isVisibleToUser)
            .append(" enabled=").append(node.isEnabled).append(" clickable=").append(node.isClickable)
            .append(" actions=").append(node.actionList.map { it.id }).append('\n')
        for (index in 0 until node.childCount) describe(node.getChild(index), depth + 1)
    }
    describe(editorPhysicalDisplayRoot(instrumentation), 0)
    File(context.cacheDir, "inspector-navigation-failure.txt").writeText(diagnostics.toString())
    error("Inspector panel unavailable on display 0: $title")
}

private fun revealEditorTool(instrumentation: Instrumentation, label: String) {
    val context = editorUiContext(instrumentation)
    var forward = true
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadline) {
        editorUiAutomation(instrumentation).clearCache()
        val root = editorPhysicalDisplayRoot(instrumentation)
        if (clickableEditorUiNode(findEditorUiNode(root, label)) != null) return
        val pager = findEditorUiNode(root, context.getString(if (forward) R.string.ui_more_tools else R.string.ui_previous_tools))
        if (clickEditorUiNode(pager)) instrumentation.waitForIdleSync() else forward = !forward
        Thread.sleep(25)
    }
    error("Tool unavailable on display 0: $label")
}

/** Navigate the visible editor chrome before exercising the existing real action. */
internal fun revealEditorControl(instrumentation: Instrumentation, label: String, trace: EditorUiTrace? = null) {
    val context = editorUiContext(instrumentation)
    InspectorPanel.entries.firstOrNull {
        label == context.getString(R.string.ui_inspector_panel, context.panelTitle(it))
    }?.let { revealInspectorPanel(instrumentation, it); return }
    val toolLabels = EditorTool.entries.filter { it != EditorTool.IDLE }.map {
        context.getString(R.string.ui_tool_shortcut, context.toolTitle(it), it.key)
    } + context.getString(R.string.ui_eraser_e)
    if (label in toolLabels) { revealEditorTool(instrumentation, label); return }
    val fileLabel = context.getString(R.string.ui_file)
    val layerOptions = context.getString(R.string.ui_layer_options)
    val addLayer = context.getString(R.string.ui_add_layer)
    val initialRoot = editorPhysicalDisplayRoot(instrumentation)
    val initialLookup = findEditorUiNode(initialRoot, label)
    trace?.observe("initial", initialRoot, initialLookup, findEditorUiNode(initialRoot, fileLabel))
    if (clickableEditorUiNode(initialLookup) != null) return
    val fileActions = listOf(R.string.ui_document, R.string.ui_import_image, R.string.ui_import_image_file,
        R.string.ui_export_png, R.string.ui_export_jpeg, R.string.ui_open_project_copy, R.string.ui_save_project, R.string.ui_save_project_copy,
        R.string.ui_saved_projects, R.string.ui_export_project_copy, R.string.ui_export_records,
        R.string.language_settings).map { context.getString(it) }
    val menu = when {
        label in setOf("Brush", "Eraser", context.getString(R.string.ui_brush), context.getString(R.string.ui_eraser)) ->
            context.getString(R.string.ui_tool_shortcut, context.toolTitle(EditorTool.BRUSH), "B")
        label in fileActions || label in setOf("Document", "Import image", "Import image file", "Export PNG", "Export JPEG",
            "Open project copy", "Save project copy", "Saved projects", "Export project copy", "Export records") -> fileLabel
        label in setOf("Layer options", "Add layer", "Group", layerOptions, addLayer, context.getString(R.string.ui_group)) ||
            label.startsWith("Select ") || label.startsWith(context.getString(R.string.ui_select_layer, "").trim()) -> {
            revealInspectorPanel(instrumentation, InspectorPanel.LAYERS)
            return
        }
        else -> return
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    var opened = false
    var scroll = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    while (System.nanoTime() < deadline) {
        // Selecting a tool replaces the header semantics. Do not keep looking at
        // the accessibility snapshot from before the accepted tool action.
        editorUiAutomation(instrumentation).clearCache()
        val root = editorPhysicalDisplayRoot(instrumentation)
        trace?.observe("loop", root, findEditorUiNode(root, label), findEditorUiNode(root, menu))
        if (clickableEditorUiNode(findEditorUiNode(root, label)) != null) return
        val panel = if (menu == fileLabel) editorFilePanel(root, fileLabel) else null
        if (panel != null) {
            if (!panel.performAction(scroll)) {
                scroll = if (scroll == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else instrumentation.waitForIdleSync()
        } else if (!opened && clickEditorUiNode(findEditorUiNode(root, menu), trace)) {
            opened = true
            File(context.cacheDir, "p19-menu-navigation.txt")
                .appendText("${System.nanoTime()} display=0 request=$label menu=$menu clickAccepted=true\n")
            instrumentation.waitForIdleSync()
        }
        Thread.sleep(20)
    }
    editorUiAutomation(instrumentation).clearCache()
    val finalRoot = editorPhysicalDisplayRoot(instrumentation)
    val finalLookup = findEditorUiNode(finalRoot, label)
    trace?.observe("timeout", finalRoot, finalLookup, findEditorUiNode(finalRoot, menu))
    check(clickableEditorUiNode(finalLookup) != null) { "Editor menu unavailable on display 0: $menu for $label" }
}
