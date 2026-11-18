package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Real localized layer-option buttons, including mixed selection and release of unselected siblings. */
internal fun selectedLayerActionsUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val previousLanguage = AppLanguages.current(context)
    val evidence = File(context.cacheDir, "p143-selected-layer-evidence/ui").apply { check(mkdirs()) }
    val results = JSONArray()
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            instrumentation.runOnMainSync { AppLanguages.set(context, language) }
            val token = "p143-selected-ui-${System.nanoTime()}"
            val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
            NativeEditor(32, addDefaultLayers = false).use { seed ->
                for ((index, label) in listOf("Base", "Lower", "Upper", "Top").withIndex()) {
                    val id = seed.importPixels(4, 4, ByteBuffer.allocateDirect(64).apply {
                        repeat(16) { put(byteArrayOf((40 + index * 30).toByte(), 70, 100, -1)) }; rewind()
                    })
                    seed.renameLayer(id, label)
                }
                seed.save(File(directory, "current.checkpoint"))
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
            val workspace = activity.retainedWorkspaceForTest()
            val automation = editorUiAutomation(instrumentation)
            val clickRecords = JSONArray()
            val stateCheckpoints = JSONArray()
            var currentClickLabel: String? = null
            fun describe(node: AccessibilityNodeInfo?): Any {
                if (node == null) return JSONObject.NULL
                val bounds = Rect().also(node::getBoundsInScreen)
                return JSONObject().put("windowId", node.windowId).put("class", node.className ?: JSONObject.NULL)
                    .put("package", node.packageName ?: JSONObject.NULL).put("text", node.text ?: JSONObject.NULL)
                    .put("description", node.contentDescription ?: JSONObject.NULL).put("enabled", node.isEnabled)
                    .put("visible", node.isVisibleToUser).put("clickable", node.isClickable)
                    .put("selected", node.isSelected).put("checked", node.isChecked).put("focused", node.isFocused)
                    .put("bounds", bounds.toShortString()).put("actions", JSONArray(node.actionList.map {
                        JSONObject().put("id", it.id).put("label", it.label ?: JSONObject.NULL)
                    }))
            }
            fun ancestors(node: AccessibilityNodeInfo?): JSONArray {
                val result = JSONArray()
                var current = node
                repeat(12) {
                    val value = current ?: return result
                    result.put(describe(value)); current = value.parent
                }
                return result
            }
            fun failureDiagnosis(failure: Throwable) {
                val record = JSONObject().put("error", failure.toString()).put("language", language.tag)
                    .put("currentClickLabel", currentClickLabel ?: JSONObject.NULL).put("clicks", clickRecords)
                    .put("stateCheckpoints", stateCheckpoints)
                    .put("automationMode", "FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES")
                    .put("serviceFlags", automation.serviceInfo.flags)
                fun capture(name: String, action: () -> Any) {
                    runCatching { record.put(name, action()) }.onFailure { record.put("${name}Error", it.toString()) }
                }
                capture("displayZeroTree") {
                    automation.clearCache()
                    var count = 0
                    fun tree(node: AccessibilityNodeInfo, depth: Int): JSONObject {
                        val value = describe(node) as JSONObject
                        count++
                        val children = JSONArray()
                        for (index in 0 until node.childCount) {
                            if (count >= 2048 || depth >= 64) { value.put("truncated", true); break }
                            node.getChild(index)?.let { children.put(tree(it, depth + 1)) }
                        }
                        return value.put("children", children)
                    }
                    editorPhysicalDisplayRoot(instrumentation)?.let { tree(it, 0) } ?: JSONObject.NULL
                }
                capture("windows") {
                    val displays = automation.windowsOnAllDisplays
                    JSONArray().also { values ->
                        for (index in 0 until displays.size()) for (window in displays.valueAt(index)) {
                            values.put(JSONObject().put("displayId", displays.keyAt(index)).put("id", window.id)
                                .put("type", window.type).put("layer", window.layer).put("active", window.isActive)
                                .put("focused", window.isFocused).put("root", describe(window.root)))
                        }
                    }
                }
                capture("ownState") { editorUiOwnState(instrumentation, activity) }
                capture("canvasSelectionGates") {
                    val canvases = JSONArray()
                    instrumentation.runOnMainSync {
                        fun collect(view: View) {
                            if (view is CanvasView) canvases.put(JSONObject().put("canChangeTool", view.canChangeTool)
                                .put("editingInProgress", view.editingInProgress).put("strokeInProgress", view.strokeInProgress)
                                .put("moveContextPending", view.moveContextPending).put("attached", view.isAttachedToWindow)
                                .put("keyboardFocusActive", view.keyboardFocusActive)
                                .put("nativeViewFocus", view.hasFocus())
                                .put("keyboardEnabled", view.keyboardMoveEnabled)
                                .put("clippingShortcutActions", view.clippingShortcutActions?.toString() ?: JSONObject.NULL)
                                .put("windowFocus", view.hasWindowFocus()).put("surfaceValid", view.holder.surface.isValid))
                            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                        }
                        collect(activity.window.decorView)
                    }
                    canvases
                }
                File(evidence, "failure-${language.tag}.json").writeText(record.toString(2))
            }
            fun waitFor(message: String, condition: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
                while (System.nanoTime() < deadline) {
                    editorUiAutomation(instrumentation).clearCache()
                    if (condition()) return
                    Thread.sleep(30)
                }
                error(message)
            }
            fun node(label: String) = findEditorUiNode(editorPhysicalDisplayRoot(instrumentation), label)
            fun buttonState(label: String, enabled: Boolean): Boolean {
                var current = node(label)
                repeat(12) {
                    val owner = current ?: return false
                    val hasClick = owner.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                    // A disabled Compose Button retains isClickable on its owner while
                    // withholding ACTION_CLICK; its Text child can still report enabled.
                    if (owner.isClickable || hasClick) {
                        return owner.isVisibleToUser && owner.isEnabled == enabled && hasClick == enabled
                    }
                    current = owner.parent
                }
                return false
            }
            fun revealLayerControl(label: String, scrolls: JSONArray) {
                revealInspectorPanel(instrumentation, InspectorPanel.LAYERS)
                val layerLabels = listOf("Base", "Lower", "Upper", "Top").flatMap { name ->
                    listOf(context.getString(R.string.ui_select_layer, name), context.getString(R.string.ui_include_layer, name))
                }
                fun root(): AccessibilityNodeInfo? {
                    automation.clearCache()
                    return editorPhysicalDisplayRoot(instrumentation)
                }
                fun visible(root: AccessibilityNodeInfo?): Boolean = findEditorUiNode(root, label)?.isVisibleToUser == true
                fun list(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (root == null) return null
                    if (root.isScrollable && layerLabels.any { findEditorUiNode(root, it) != null }) return root
                    for (index in 0 until root.childCount) list(root.getChild(index))?.let { return it }
                    return null
                }
                fun fingerprint(root: AccessibilityNodeInfo?): String = layerLabels.mapNotNull { name ->
                    findEditorUiNode(root, name)?.takeIf { it.isVisibleToUser }?.let {
                        "$name:${Rect().also(it::getBoundsInScreen).toShortString()}"
                    }
                }.joinToString(";")
                if (visible(root())) return
                for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    for (attempt in 0 until 16) {
                        val current = root()
                        if (visible(current)) return
                        val scrolling = list(current) ?: break
                        if (scrolling.actionList.none { it.id == action }) break
                        val before = fingerprint(current)
                        val accepted = scrolling.performAction(action)
                        scrolls.put(JSONObject().put("action", action).put("accepted", accepted)
                            .put("before", before).put("list", describe(scrolling)))
                        check(accepted) { "Selected-layer list rejected its advertised scroll action for $label" }
                        waitFor("Selected-layer list did not scroll to $label") {
                            val next = root()
                            visible(next) || fingerprint(next) != before ||
                                list(next)?.actionList?.none { it.id == action } == true
                        }
                        instrumentation.waitForIdleSync()
                        automation.waitForIdle(100, 5_000)
                    }
                }
                check(visible(root())) { "Selected-layer control missing after bounded list scrolling: $label" }
            }
            fun click(label: String, layerControl: Boolean = false) {
                currentClickLabel = label
                val trace = EditorUiTrace(label)
                val observations = JSONArray()
                val scrolls = JSONArray()
                val record = JSONObject().put("label", label).put("observations", observations)
                    .put("scrolls", scrolls).put("trace", trace.snapshot())
                clickRecords.put(record)
                revealEditorControl(instrumentation, label, trace)
                if (layerControl) revealLayerControl(label, scrolls)
                var attempts = 0
                var previous: String? = null
                waitFor("Selected-layer button unavailable: $label") {
                    val target = node(label)
                    val chain = ancestors(target)
                    val signature = chain.toString()
                    if (signature != previous && observations.length() < 32) {
                        observations.put(JSONObject().put("attempt", attempts).put("ancestors", chain))
                        previous = signature
                    }
                    attempts++
                    val accepted = clickEditorUiNode(target, trace)
                    record.put("attempts", attempts).put("accepted", accepted)
                    accepted
                }
                instrumentation.waitForIdleSync()
            }
            fun label(resource: Int) = context.getString(resource)
            fun options() = click(label(R.string.ui_layer_options))
            fun shortcut(repeat: Boolean = false) {
                val down = SystemClock.uptimeMillis()
                val modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                fun key(action: Int, count: Int) = KeyEvent(down, SystemClock.uptimeMillis(), action,
                    KeyEvent.KEYCODE_G, count, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0))
                if (repeat) instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 1))
                instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 0))
                instrumentation.waitForIdleSync()
            }
            fun capture(name: String) {
                val image = checkNotNull(editorUiAutomation(instrumentation).takeScreenshot())
                try { File(evidence, "$name-${language.tag}.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
            }
            try {
                check(workspace.recoveryDirectoryForTest().canonicalFile == directory.canonicalFile)
                waitFor("Selected-layer UI fixture did not restore") { activity.retainedEditorForTest()?.state()?.layers?.size == 4 }
                check(activity.resources.configuration.locales[0].language == language.tag.substringBefore('-'))
                val editor = checkNotNull(activity.retainedEditorForTest())
                fun awaitShortcutReady(releasesClipping: Boolean) {
                    editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
                    val expected = checkNotNull(editor.selectedLayerActions())
                    check(expected.canClipping && expected.releasesClipping == releasesClipping)
                    // Native completion precedes asynchronous presentation loading and Compose binding.
                    waitFor("Clipping shortcut did not receive the committed menu plan") {
                        var ready = false
                        instrumentation.runOnMainSync {
                            fun collect(view: View) {
                                if (view is CanvasView && view.clippingShortcutActions == expected &&
                                    (view.keyboardFocusActive || view.hasFocus()) &&
                                    view.keyboardMoveEnabled && view.hasWindowFocus() && view.samplingEnabled &&
                                    !view.editingInProgress) ready = true
                                if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                            }
                            collect(activity.window.decorView)
                        }
                        ready
                    }
                }
                val ids = editor.state().layers.map { it.id }
                val names = listOf("Base", "Lower", "Upper", "Top")
                revealInspectorPanel(instrumentation, InspectorPanel.LAYERS)
                fun select(index: Int) {
                    click(context.getString(R.string.ui_select_layer, names[index]), layerControl = true)
                    // An accepted UI click queues the native selection on a separate worker.
                    waitFor("Selected layer did not become the native primary: ${names[index]}") {
                        val selection = editor.selection()
                        selection.primary == ids[index] && selection.roots == listOf(ids[index]) && !selection.mask &&
                            editor.state().document.activeLayerId == ids[index]
                    }
                    editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
                }
                fun selectUpper() {
                    select(3)
                    for (index in 1..2) {
                        click(context.getString(R.string.ui_include_layer, names[index]), layerControl = true)
                        waitFor("Selected roots not updated") { ids[index] in editor.selection().roots }
                    }
                    check(editor.selection().roots.toSet() == ids.drop(1).toSet())
                }
                selectUpper()
                val before = editor.state()
                options()
                waitFor("Batch Hide label missing") { buttonState(label(R.string.ui_hide_layers), enabled = true) }
                click(label(R.string.ui_hide_layers))
                waitFor("Hide did not affect every selected layer") {
                    editor.state().layers.map { it.visible } == listOf(true, false, false, false)
                }
                check(editor.currentRevision() == before.revision + 1)
                click(label(R.string.ui_undo))
                waitFor("One Undo did not restore all hidden layers") { editor.state().layers == before.layers }
                click(label(R.string.ui_redo))
                waitFor("Redo did not hide all selected layers") { editor.state().layers.drop(1).all { !it.visible } }
                selectUpper()
                options()
                waitFor("Batch Show label missing") { buttonState(label(R.string.ui_show_layers), enabled = true) }
                click(label(R.string.ui_show_layers))
                waitFor("Show did not restore selected layers") { editor.state().layers.all { it.visible } }
                selectUpper()
                val clipBefore = editor.state()
                options()
                waitFor("Create Clipping Mask label missing") { buttonState(label(R.string.ui_create_clipping_mask), enabled = true) }
                capture("create")
                click(label(R.string.ui_create_clipping_mask))
                waitFor("Selected run did not share the lower base") { editor.state().layers.drop(1).all { it.maskSourceId == ids[0] } }
                check(editor.currentRevision() == clipBefore.revision + 1)
                val clipped = editor.state().layers
                click(label(R.string.ui_undo))
                waitFor("One Undo did not restore clipping run") { editor.state().layers == clipBefore.layers }
                click(label(R.string.ui_redo))
                waitFor("One Redo did not restore clipping run") { editor.state().layers == clipped }
                select(1)
                val releaseBefore = editor.state()
                options()
                waitFor("Release Clipping Mask label missing") { buttonState(label(R.string.ui_release_clipping_mask), enabled = true) }
                capture("release")
                click(label(R.string.ui_release_clipping_mask))
                waitFor("Release did not include unselected continuous siblings") { editor.state().layers.all { it.maskSourceId == 0L } }
                check(editor.currentRevision() == releaseBefore.revision + 1)
                click(label(R.string.ui_undo))
                waitFor("One Undo did not restore release cascade") { editor.state().layers == clipped }
                click(label(R.string.ui_redo))
                waitFor("One Redo did not release the cascade") { editor.state().layers.all { it.maskSourceId == 0L } }
                select(0)
                val invalidBefore = editor.state()
                fun invalidCheckpoint(stage: String, state: EditorState) = JSONObject().put("stage", stage)
                    .put("revision", state.revision).put("activeLayerId", state.document.activeLayerId)
                    .put("undo", state.undo).put("redo", state.redo).put("bytes", state.bytes)
                    .put("selection", editor.selection().toString()).put("pending", editor.pendingEditCount)
                stateCheckpoints.put(invalidCheckpoint("before-disabled-clipping-menu", invalidBefore))
                options()
                waitFor("Bottom layer clipping was not disabled") { buttonState(label(R.string.ui_create_clipping_mask), enabled = false) }
                val invalidAfter = editor.state()
                stateCheckpoints.put(invalidCheckpoint("after-disabled-clipping-menu", invalidAfter)
                    .put("layersUnchanged", invalidAfter.layers == invalidBefore.layers))
                File(evidence, "state-checkpoints-${language.tag}.json").writeText(stateCheckpoints.toString(2))
                check(invalidAfter.layers == invalidBefore.layers && invalidAfter.revision == invalidBefore.revision) {
                    "Disabled clipping menu changed state: revision=${invalidBefore.revision}->${invalidAfter.revision}, " +
                        "active=${invalidBefore.document.activeLayerId}->${invalidAfter.document.activeLayerId}, " +
                        "layersUnchanged=${invalidAfter.layers == invalidBefore.layers}"
                }
                click(label(R.string.ui_done))
                selectUpper()
                val budgetBefore = editor.state()
                val memory = editor.resourceLedger.snapshot()
                editor.resourceLedger.reserve(memory.availableBytes, "selected-layer UI competing owner").use {
                    options()
                    waitFor("Budget refusal did not disable selected-layer action") {
                        buttonState(label(R.string.ui_create_clipping_mask), enabled = false)
                    }
                    click(label(R.string.ui_done))
                }
                options()
                waitFor("Reopening options did not recover after budget release") {
                    buttonState(label(R.string.ui_create_clipping_mask), enabled = true)
                }
                click(label(R.string.ui_create_clipping_mask))
                waitFor("Recovered action did not commit") { editor.state().layers.drop(1).all { it.maskSourceId == ids[0] } }
                check(editor.currentRevision() == budgetBefore.revision + 1)
                awaitShortcutReady(releasesClipping = true)
                val shortcutBefore = editor.currentRevision()
                shortcut(repeat = true)
                waitFor("Actual Ctrl+Alt+G did not release selected clipping") { editor.state().layers.all { it.maskSourceId == 0L } }
                check(editor.currentRevision() == shortcutBefore + 1) { "Repeated shortcut generated more than one edit" }
                awaitShortcutReady(releasesClipping = false)
                shortcut()
                waitFor("Actual Ctrl+Alt+G did not create shared clipping") { editor.state().layers.drop(1).all { it.maskSourceId == ids[0] } }
                awaitShortcutReady(releasesClipping = true)
                val editingBefore = editor.state()
                options(); click(label(R.string.ui_rename))
                val nameLabel = label(R.string.ui_layer_name)
                waitFor("Rename field missing") { findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), nameLabel) != null }
                check(findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), nameLabel)!!
                    .performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                waitFor("Rename field did not acquire input focus") {
                    findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), nameLabel)?.isFocused == true
                }
                shortcut()
                editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
                check(editor.currentRevision() == editingBefore.revision && editor.state().layers == editingBefore.layers) {
                    "Clipping shortcut changed the document while a text field was editing"
                }
                click(label(R.string.ui_cancel))
                val searchLabel = label(R.string.ui_search_layers)
                click(searchLabel)
                waitFor("Layer search field missing") {
                    findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), searchLabel) != null
                }
                check(findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), searchLabel)!!
                    .performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                waitFor("Layer search field did not acquire input focus") {
                    findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), searchLabel)?.isFocused == true
                }
                shortcut()
                editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
                check(editor.currentRevision() == editingBefore.revision && editor.state().layers == editingBefore.layers) {
                    "Clipping shortcut changed the document while the layer search field was editing"
                }
                click(label(R.string.ui_close_search))
                results.put(JSONObject().put("language", language.tag).put("hideShow", true)
                    .put("clippingSharedBase", true).put("unselectedReleaseCascade", true)
                    .put("singleUndoRedo", true).put("invalidDisabled", true).put("budgetReopenRetry", true)
                    .put("actualKeyboardShortcut", true).put("repeatFence", true).put("textFocusFence", true)
                    .put("layerSearchFocusFence", true))
            } catch (failure: Throwable) {
                runCatching { failureDiagnosis(failure) }
                runCatching { capture("failure") }
                throw failure
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                workspace.closeAsync().get(20, TimeUnit.SECONDS)
                check(directory.deleteRecursively())
            }
        }
    } finally {
        instrumentation.runOnMainSync { AppLanguages.set(context, previousLanguage) }
        File(evidence, "results.json").writeText(results.toString(2))
    }
    return "Actual layer-option buttons: languages=en,zh-CN, multi-select hide/show, shared clipping base, unselected release cascade, one Undo/Redo, bottom-layer disabled gate, budget-refusal reopen/retry and injected Ctrl+Alt+G/repeat/Rename/layer-search focus fences passed."
}
