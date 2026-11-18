package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Real menu clicks and controls; the debug bridge only observes drafts and the submission gate. */
internal fun adjustmentWorkflowSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val oracle = JSONObject(instrumentation.context.assets.open("adjustment-workflow/pc-menu.json").bufferedReader().use { it.readText() })
    for ((name, kinds) in listOf("imageAdjustments" to AdjustmentKind.imageAdjustments, "filters" to AdjustmentKind.filters)) {
        val expected = oracle.getJSONObject("groups").getJSONArray(name)
        check(kinds.map { it.wireName } == List(expected.length()) { expected.getString(it) })
    }
    check(AdjustmentKind.entries.filter { !it.isEditable } == listOf(AdjustmentKind.INVERT))
    val languageBefore = AppLanguages.current(context)
    val evidence = File(context.cacheDir, "p130-adjustment-workflow").apply { mkdirs() }
    var completed = 0
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            instrumentation.runOnMainSync { AppLanguages.set(context, language) }
            val token = "p130-adjustment-${System.nanoTime()}"
            val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
            NativeEditor(32, addDefaultLayers = false).use { seed ->
                val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
                repeat(32 * 32) { pixels.put(byteArrayOf(60, 90, 110, -1)) }; pixels.rewind()
                seed.importPixels(32, 32, pixels); seed.save(File(directory, "current.checkpoint"))
            }
            var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
            val workspace = activity.retainedWorkspaceForTest()
            instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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
            fun reveal(label: String) {
                var forward = true
                waitFor("Adjustment workflow control not visible: $label") {
                    val target = node(label)
                    val bounds = Rect().also { target?.getBoundsInScreen(it) }
                    if (target?.isVisibleToUser == true && bounds.width() > 0 && bounds.height() > 0) true else {
                        fun scroller(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                            if (value == null) return null
                            val header = node(context.getString(R.string.ui_collapse_inspector_dock)) ?: return null
                            val headerBounds = Rect().also(header::getBoundsInScreen)
                            val bounds = Rect().also(value::getBoundsInScreen)
                            if (value.isScrollable && value.isVisibleToUser &&
                                value.className?.toString() == "android.widget.ScrollView" &&
                                bounds.width() >= (96 * context.resources.displayMetrics.density).toInt() &&
                                bounds.top >= headerBounds.bottom &&
                                headerBounds.centerX() in bounds.left until bounds.right) return value
                            for (i in 0 until value.childCount) scroller(value.getChild(i))?.let { return it }
                            return null
                        }
                        val owner = scroller(editorPhysicalDisplayRoot(instrumentation))
                        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        if (owner?.performAction(action) != true) forward = !forward
                        false
                    }
                }
            }
            fun click(label: String) {
                revealEditorControl(instrumentation, label)
                reveal(label)
                waitFor("Adjustment workflow control unavailable: $label") { clickEditorUiNode(node(label)) }
                instrumentation.waitForIdleSync()
            }
            fun progress(resource: Int, value: Float) {
                val label = context.getString(resource)
                revealEditorControl(instrumentation, label)
                reveal(label)
                waitFor("Adjustment workflow slider unavailable: $label") { node(label)?.actionList?.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id } == true }
                check(node(label)!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
                }))
                instrumentation.waitForIdleSync()
            }

            fun <T> main(action: () -> T): T {
                var result: T? = null
                instrumentation.runOnMainSync { result = action() }
                @Suppress("UNCHECKED_CAST") return result as T
            }
            fun access() = checkNotNull(activity.typedLayerTestAccessForTest())
            fun draft(): AdjustmentEditorDraft {
                waitFor("Created adjustment editor did not attach") {
                    main { access().adjustment()?.layerId != null && !access().busy() }
                }
                return main { checkNotNull(access().adjustment()) }
            }
            fun settled() = main { !access().busy() }
            try {
                waitFor("Workflow UI did not restore") { activity.retainedEditorForTest()?.state()?.layers?.size == 1 && main { activity.typedLayerTestAccessForTest() != null } }
                val editor = checkNotNull(activity.retainedEditorForTest())
                val before = editor.state(); val beforePixels = vignettePixels(editor)
                revealInspectorPanel(instrumentation, InspectorPanel.ADJUSTMENTS)
                reveal(context.getString(R.string.ui_adjustment_image_group))
                check(node(context.getString(R.string.ui_adjustment_image_group))?.isVisibleToUser == true)
                // Invert creates directly through the same retained command and presentation gate.
                click(context.getString(R.string.ui_adjustment_invert))
                waitFor("Invert did not create directly") { editor.state().layers.size == 2 && settled() }
                check(main { access().adjustment() == null })
                check(editor.state().revision == before.revision + 1)
                val invert = editor.state().layers.single { it.id !in before.layers.map { it.id } }
                check(editor.adjustmentStyleOrNull(invert.id)!!.kind == AdjustmentKind.INVERT)
                check(node(context.getString(R.string.ui_edit_selected_adjustment)) == null)
                val invertedPixels = vignettePixels(editor)
                for (offset in invertedPixels.indices step 4) {
                    check((invertedPixels[offset].toInt() and 255) == 195)
                    check((invertedPixels[offset + 1].toInt() and 255) == 165)
                    check((invertedPixels[offset + 2].toInt() and 255) == 145)
                    check(invertedPixels[offset + 3].toInt() and 255 == 255)
                }
                click(context.getString(R.string.ui_undo))
                waitFor("Invert Undo failed") { editor.state().layers == before.layers }
                check(vignettePixels(editor).contentEquals(beforePixels))
                click(context.getString(R.string.ui_redo))
                waitFor("Invert Redo failed") { editor.state().layers.size == 2 }
                check(vignettePixels(editor).contentEquals(invertedPixels))
                reveal(context.getString(R.string.ui_adjustment_filter_group))
                check(node(context.getString(R.string.ui_adjustment_filter_group))?.isVisibleToUser == true)
                click(context.getString(R.string.ui_adjustment_add_noise))
                val firstSeed = draft().style.noiseSeed
                check(firstSeed in 0L..4_294_967_295L)
                progress(R.string.ui_adjustment_noise_amount, 35f)
                check(draft().style.noiseSeed == firstSeed)
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    instrumentation.runOnMainSync { activity.recreate() }
                    activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity)
                    instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                } finally { instrumentation.removeMonitor(monitor) }
                waitFor("Noise draft did not recover") { main { activity.typedLayerTestAccessForTest()?.adjustment()?.style?.noiseSeed == firstSeed } }
                check(draft().style.noiseAmount == 35.0)
                click(context.getString(R.string.ui_apply))
                waitFor("First noise Apply failed") { editor.state().layers.size == 3 && settled() }
                val firstNoise = editor.state().layers.single { editor.adjustmentStyleOrNull(it.id)?.kind == AdjustmentKind.ADD_NOISE }
                check(editor.adjustmentStyleOrNull(firstNoise.id)!!.noiseSeed == firstSeed)
                val firstPixels = vignettePixels(editor)
                click(context.getString(R.string.ui_edit_selected_adjustment))
                check(draft().style.noiseSeed == firstSeed)
                progress(R.string.ui_adjustment_noise_amount, 75f)
                check(draft().style.noiseSeed == firstSeed)
                click(context.getString(R.string.ui_apply))
                waitFor("Noise edit Apply failed") { editor.adjustmentStyleOrNull(firstNoise.id)?.noiseAmount == 75.0 && settled() }
                val editedPixels = vignettePixels(editor); check(!editedPixels.contentEquals(firstPixels))
                click(context.getString(R.string.ui_undo))
                waitFor("Noise edit Undo failed") { editor.adjustmentStyleOrNull(firstNoise.id)?.noiseAmount == 35.0 }
                check(editor.adjustmentStyleOrNull(firstNoise.id)!!.noiseSeed == firstSeed && vignettePixels(editor).contentEquals(firstPixels))
                click(context.getString(R.string.ui_redo))
                waitFor("Noise edit Redo failed") { editor.adjustmentStyleOrNull(firstNoise.id)?.noiseAmount == 75.0 }
                check(vignettePixels(editor).contentEquals(editedPixels))
                click(context.getString(R.string.ui_adjustment_add_noise))
                val secondSeed = draft().style.noiseSeed
                check(secondSeed != firstSeed) { "New noise layers reused the same pattern seed" }
                click(context.getString(R.string.ui_apply))
                waitFor("Second noise Apply failed") { editor.state().layers.size == 4 && settled() }
                val beforeCancelCreation = editor.state()
                click(context.getString(R.string.ui_adjustment_add_noise))
                val cancelDraft = draft()
                val committed = editor.state(); val committedPixels = vignettePixels(editor)
                check(committed.layers.size == beforeCancelCreation.layers.size + 1 &&
                    committed.revision == beforeCancelCreation.revision + 1 && cancelDraft.layerId != null)
                progress(R.string.ui_adjustment_noise_amount, 90f)
                click(context.getString(R.string.ui_cancel))
                waitFor("Cancel did not close the settings editor") { main { access().adjustment() == null } }
                check(editor.state() == committed && vignettePixels(editor).contentEquals(committedPixels))
                check(equalAdjustmentJson(checkNotNull(editor.adjustmentStyleOrNull(checkNotNull(cancelDraft.layerId))).toMacJson(),
                    cancelDraft.style.toMacJson())) { "Cancel must preserve the newly created default adjustment" }
                typedProjectRoundTripSmoke(context, editor, firstNoise.id) { restored, restoredId ->
                    check(restored.adjustmentStyleOrNull(restoredId)!!.noiseSeed == firstSeed)
                    check(restored.state().layers.count { restored.adjustmentStyleOrNull(it.id)?.noiseSeed == secondSeed } == 1)
                }
                val project = File(evidence, "workflow-${language.tag}.comp")
                if (project.exists()) check(project.deleteRecursively())
                editor.captureProjectSnapshot().use { snapshot -> NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                } }
                reveal(context.getString(R.string.ui_adjustment_image_group))
                val image = checkNotNull(editorUiAutomation(instrumentation).takeScreenshot())
                try { File(evidence, "menu-${language.tag}.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { image.recycle() }
                File(evidence, "result-${language.tag}.json").writeText(JSONObject().put("firstSeed", firstSeed).put("secondSeed", secondSeed)
                    .put("invertImmediate", true).put("creationAndSettingsSeparateHistory", true).put("cancelKeepsCreatedLayer", true).put("draftRecreation", true).put("cancel", true).toString(2))
                completed++
            } catch (failure: Throwable) {
                editorUiAutomation(instrumentation).takeScreenshot()?.let { image ->
                    try { File(evidence, "failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { image.recycle() }
                }
                fun tree(value: AccessibilityNodeInfo?, depth: Int = 0): String {
                    if (value == null || depth > 40) return ""
                    return "${" ".repeat(depth)}${value.text} | ${value.contentDescription} visible=${value.isVisibleToUser} enabled=${value.isEnabled} scroll=${value.isScrollable}\n" +
                        (0 until value.childCount).joinToString("") { tree(value.getChild(it), depth + 1) }
                }
                File(evidence, "failure.txt").writeText("$failure\n" + tree(editorPhysicalDisplayRoot(instrumentation)))
                throw failure
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                workspace.closeAsync().get(15, TimeUnit.SECONDS)
                check(directory.deleteRecursively())
            }
        }
    } finally { instrumentation.runOnMainSync { AppLanguages.set(context, languageBefore) } }
    return "uiCases=$completed languages=en,zh-CN actualPcMenuGroups=true invertImmediate=true noInvertEditor=true randomNoisePerLayer=true stableNoiseThroughEditingAndRecreation=true undoRedo=true nativeComp=true cancel=true isolatedWorkspace=true"
}
