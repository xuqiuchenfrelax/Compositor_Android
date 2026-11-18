package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.TimeUnit

/** Uses only visible layer controls to align, distribute and undo in an isolated workspace. */
internal fun layerAlignmentUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val previousLanguage = AppLanguages.current(context)
    var cases = 0
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            instrumentation.runOnMainSync { AppLanguages.set(context, language) }
            val token = "p128-align-${System.nanoTime()}"
            val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
            NativeEditor(160, addDefaultLayers = false).use { seed ->
                val ids = seedAlignmentDocument(seed)
                val middle = seed.state().layers.single { it.id == ids[1] }
                seed.setTransform(ids[1], middle.transform.copy(y = 17.0))
                seed.save(File(directory, "current.checkpoint"))
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
            val workspace = activity.retainedWorkspaceForTest()
            instrumentation.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
                waitFor("Alignment control did not become clickable: $label") { clickEditorUiNode(node(label)) }
                instrumentation.waitForIdleSync()
            }
            try {
                check(workspace.recoveryDirectoryForTest().canonicalFile == directory.canonicalFile)
                waitFor("Alignment fixture did not restore") { activity.retainedEditorForTest()?.state()?.layers?.size == 3 }
                val editor = checkNotNull(activity.retainedEditorForTest())
                revealInspectorPanel(instrumentation, InspectorPanel.LAYERS)
                val ids = editor.state().layers.map { it.id }
                fun selectAll() {
                    for ((index, id) in ids.withIndex()) {
                        if (id !in editor.selection().roots) {
                            click(context.getString(R.string.ui_include_layer, "Align ${index + 1}"))
                            waitFor("Multi-selection did not publish") { id in editor.selection().roots }
                        }
                    }
                }
                selectAll()
                val before = editor.state().layers
                click(context.getString(R.string.ui_layer_options))
                click(context.getString(R.string.ui_align_layers))
                waitFor("Alignment menu did not expose all six actions") {
                    LayerAlignment.entries.all { node(context.layerAlignmentTitle(it)) != null }
                }
                click(context.layerAlignmentTitle(LayerAlignment.LEFT))
                waitFor("UI Align did not move all selected layers") { editor.state().layers.all { it.transform.x == 10.5 } }
                click(context.getString(R.string.ui_undo))
                waitFor("UI Undo did not restore alignment") { editor.state().layers == before }
                click(context.getString(R.string.ui_redo))
                waitFor("UI Redo did not restore alignment") { editor.state().layers.all { it.transform.x == 10.5 } }
                click(context.getString(R.string.ui_undo))
                waitFor("UI Undo did not restore distribution seed") { editor.state().layers == before }
                // PC Undo deliberately collapses transient multiselection to the primary layer.
                selectAll()
                click(context.getString(R.string.ui_layer_options))
                click(context.getString(R.string.ui_distribute_layers))
                waitFor("Distribution menu did not expose all four actions") {
                    LayerDistribution.entries.all { node(context.layerDistributionTitle(it)) != null }
                }
                val image = checkNotNull(editorUiAutomation(instrumentation).takeScreenshot())
                try { File(context.cacheDir, "p128-align-${language.tag}.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
                click(context.layerDistributionTitle(LayerDistribution.VERTICAL_SPACING))
                waitFor("Distribution did not close options") { node(context.getString(R.string.ui_layer_options)) != null }
                waitFor("UI distribution did not move the middle layer") {
                    editor.state().layers[1].transform.y == 20.0
                }
                check(editor.state().layers[0] == before[0] && editor.state().layers[2] == before[2])
                click(context.getString(R.string.ui_undo))
                waitFor("One UI Undo did not restore distribution") { editor.state().layers == before }
                selectAll()
                val moveTool = context.getString(R.string.ui_tool_shortcut, context.toolTitle(EditorTool.MOVE), EditorTool.MOVE.key)
                revealEditorControl(instrumentation, moveTool)
                click(moveTool)
                click(context.getString(R.string.ui_align_layers))
                click(context.layerAlignmentTitle(LayerAlignment.BOTTOM))
                waitFor("Move header alignment did not move all selected layers") {
                    editor.state().layers.all { it.transform.y == 32.0 }
                }
                click(context.getString(R.string.ui_undo))
                waitFor("One UI Undo did not restore Move header alignment") { editor.state().layers == before }
                selectAll()
                val xLabel = context.getString(R.string.ui_move_field_value, "X")
                revealEditorControl(instrumentation, xLabel)
                click(xLabel)
                waitFor("Move X field did not focus") { findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), xLabel)?.isFocused == true }
                val xField = checkNotNull(findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), xLabel))
                check(xField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "20.5")
                }))
                waitFor("Move field did not begin a pending transform") { editor.activeTransformCompletion() != null }
                click(context.getString(R.string.ui_align_layers))
                click(context.layerAlignmentTitle(LayerAlignment.LEFT))
                waitFor("Align lost the pending Move field edit") { editor.state().layers.all { it.transform.x == 20.5 } }
                click(context.getString(R.string.ui_undo))
                waitFor("Align and pending Move did not preserve separate undo steps") {
                    editor.state().layers.map { it.transform } == before.map { it.transform.copy(x = it.transform.x + 10.0) }
                }
                click(context.getString(R.string.ui_undo))
                waitFor("Second Undo did not restore the pending Move") { editor.state().layers == before }
                cases++
            } catch (failure: Throwable) {
                val image = editorUiAutomation(instrumentation).takeScreenshot()
                if (image != null) try { File(context.cacheDir, "p128-align-failure.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { image.recycle() }
                fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                    if (node == null || depth > 40) return ""
                    return "${" ".repeat(depth)}${node.text} | ${node.contentDescription} enabled=${node.isEnabled} visible=${node.isVisibleToUser}\n" +
                        (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
                }
                File(context.cacheDir, "p128-align-failure.txt").writeText("$failure\nselection=${activity.retainedEditorForTest()?.selection()}\n" +
                    describe(editorPhysicalDisplayRoot(instrumentation)))
                throw failure
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                workspace.closeAsync().get(15, TimeUnit.SECONDS)
                check(directory.deleteRecursively())
            }
        }
    } finally { instrumentation.runOnMainSync { AppLanguages.set(context, previousLanguage) } }
    return "uiCases=$cases languages=en,zh-CN isolatedWorkspace=true moveHeader=true pendingMoveThenAlign=true"
}
