package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Shipping fields and commands in private workspaces. Accessibility input is not a vendor IME test. */
internal fun exposureInputSmoke(instrumentation: Instrumentation): String {
    check(ExposureParameter.EXPOSURE.roundSlider(Math.nextDown(.005)) == 0.0)
    check(ExposureParameter.EXPOSURE.roundSlider(.005) == .01)
    check(ExposureParameter.EXPOSURE.roundSlider(-.005) == -.01)
    check(ExposureParameter.EXPOSURE.roundSlider(-Math.nextDown(.005)) == 0.0)
    val sourceStyle = AdjustmentLayerModel.create(AdjustmentKind.EXPOSURE).withExposure(value = 1.234567890123)
    val draftModel = AdjustmentEditorDraft(sourceStyle).withExposureInput(1, "0.0001").withExposureInput(2, "0")
    check(draftModel.hasInvalidExposureInput && draftModel.style.offset == 0.0001)
    check(draftModel.withExposureInput(0, "0".repeat(64) + "NaN") == draftModel)
    val switched = draftModel.copy(style = draftModel.style.withKind(AdjustmentKind.LEVELS))
    check(!switched.hasInvalidExposureInput && switched.copy(style = switched.style.withKind(AdjustmentKind.EXPOSURE)).hasInvalidExposureInput)
    val saverScope = object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
    val saved = checkNotNull(adjustmentEditorDraftSaver.run { with(saverScope) { save(draftModel) } }) as List<*>
    val restored = checkNotNull(adjustmentEditorDraftSaver.restore(saved))
    check(restored.hasInvalidExposureInput && restored.exposureInputs == draftModel.exposureInputs && restored.style.offset == 0.0001)
    for (count in listOf(2, 3)) check(checkNotNull(adjustmentEditorDraftSaver.restore(saved.take(count))).style.offset == 0.0001)
    val context = instrumentation.targetContext
    val originalLanguage = AppLanguages.current(context)
    val evidence = File(context.cacheDir, "p135-exposure-${System.nanoTime()}").apply { mkdirs() }
    val observations = JSONArray()
    fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
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
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            main { AppLanguages.set(context, language) }
            val token = "p135-exposure-${System.nanoTime()}"
            val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
            NativeEditor(32, addDefaultLayers = false).use { seed ->
                val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
                repeat(32 * 32) { pixels.put(byteArrayOf(60, 90, 110, -1)) }; pixels.rewind()
                seed.importPixels(32, 32, pixels); seed.save(File(directory, "current.checkpoint"))
            }
            var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
            val workspace = activity.retainedWorkspaceForTest()
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            fun label(id: Int) = activity.getString(id)
            fun node(text: String) = findEditorUiNode(editorPhysicalDisplayRoot(instrumentation), text)
            fun reveal(text: String) {
                var forward = true
                waitFor("Exposure control not visible: $text") {
                    val target = node(text)
                    val bounds = android.graphics.Rect().also { target?.getBoundsInScreen(it) }
                    if (target?.isVisibleToUser == true && !bounds.isEmpty) true else {
                        val header = node(label(R.string.ui_collapse_inspector_dock))
                        val headerBounds = android.graphics.Rect().also { header?.getBoundsInScreen(it) }
                        fun scroller(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                            if (value == null) return null
                            val rect = android.graphics.Rect().also(value::getBoundsInScreen)
                            if (value.isScrollable && value.isVisibleToUser && value.className?.toString() == "android.widget.ScrollView" &&
                                rect.top >= headerBounds.bottom && headerBounds.centerX() in rect.left until rect.right) return value
                            for (i in 0 until value.childCount) scroller(value.getChild(i))?.let { return it }
                            return null
                        }
                        val owner = scroller(editorPhysicalDisplayRoot(instrumentation))
                        if (owner?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true)
                            forward = !forward
                        false
                    }
                }
            }
            fun click(id: Int) {
                val text = label(id)
                revealEditorControl(instrumentation, text)
                reveal(text)
                waitFor("Exposure action unavailable: $text") { clickEditorUiNode(node(text)) }
                instrumentation.waitForIdleSync()
            }
            fun fieldLabel(id: Int) = activity.getString(R.string.ui_field_value, label(id))
            fun field(id: Int): AccessibilityNodeInfo? {
                val wrapper = node(fieldLabel(id))
                fun editable(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (value == null) return null
                    if (value.isEditable && value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return value
                    for (i in 0 until value.childCount) editable(value.getChild(i))?.let { return it }
                    return null
                }
                return editable(wrapper) ?: findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), fieldLabel(id))
            }
            fun input(id: Int, value: String) {
                revealEditorControl(instrumentation, fieldLabel(id))
                reveal(fieldLabel(id))
                waitFor("Exposure field unavailable: ${fieldLabel(id)}") { field(id)?.isVisibleToUser == true }
                check(field(id)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                instrumentation.waitForIdleSync()
            }
            fun draft() = main { checkNotNull(activity.typedLayerTestAccessForTest()?.adjustment()) }
            fun idle() = main { activity.typedLayerTestAccessForTest()?.busy() == false }
            fun applyEnabled(): Boolean {
                reveal(label(R.string.ui_apply))
                var value = node(label(R.string.ui_apply))
                repeat(12) { if (value?.isClickable == true) return value!!.isEnabled; value = value?.parent }
                error("Apply button not exposed")
            }
            fun recreate() {
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    main { activity.recreate() }
                    activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity)
                } finally { instrumentation.removeMonitor(monitor) }
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                waitFor("Exposure draft did not reattach") { main { activity.typedLayerTestAccessForTest()?.adjustment() != null } }
            }
            try {
                waitFor("Exposure fixture not ready") { activity.retainedEditorForTest()?.state()?.layers?.size == 1 &&
                    main { activity.typedLayerTestAccessForTest() != null } }
                val editor = checkNotNull(activity.retainedEditorForTest())
                val beforeCreate = editor.state(); val pixelsBefore = vignettePixels(editor)
                revealInspectorPanel(instrumentation, InspectorPanel.ADJUSTMENTS)
                click(R.string.ui_adjustment_exposure)
                waitFor("Default adjustment was not created") { main {
                    activity.typedLayerTestAccessForTest()?.adjustment()?.layerId != null &&
                        activity.typedLayerTestAccessForTest()?.busy() == false
                } }
                val baseline = editor.state()
                check(baseline.layers.size == beforeCreate.layers.size + 1 && baseline.revision == beforeCreate.revision + 1)
                val layer = baseline.layers.single { it.id == draft().layerId }
                val initialStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                check(vignettePixels(editor).contentEquals(pixelsBefore))
                input(R.string.ui_adjustment_exposure, "1.234567890123")
                input(R.string.ui_adjustment_offset, "0.0001")
                input(R.string.ui_adjustment_gamma, "1.125")
                check(draft().style.exposure == 1.234567890123 && draft().style.offset == 0.0001 && draft().style.exposureGamma == 1.125)
                for (invalid in listOf("", "-", "NaN", "Infinity", "-Infinity", "1e309", "20.0001", "-20.0001")) {
                    input(R.string.ui_adjustment_exposure, invalid)
                    check(draft().hasInvalidExposureInput && !applyEnabled()) { "Accepted invalid exposure: $invalid" }
                    main { activity.typedLayerTestAccessForTest()!!.applyAdjustment() }
                    check(idle() && editor.state() == baseline && draft().style.exposure == 1.234567890123)
                }
                input(R.string.ui_adjustment_exposure, "1.234567890123")
                input(R.string.ui_adjustment_offset, "-0.5001")
                input(R.string.ui_adjustment_gamma, "0")
                check(!applyEnabled())
                recreate()
                check(activity.retainedEditorForTest() === editor)
                check(draft().exposureInputs == listOf("1.234567890123", "-0.5001", "0"))
                check(!applyEnabled() && editor.state() == baseline)
                // Gamma exposes actual values to accessibility despite a logarithmic touch track.
                fun slider(value: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
                    if (value == null) return null
                    if (value.contentDescription?.toString() == name && value.rangeInfo != null) return value
                    for (i in 0 until value.childCount) slider(value.getChild(i), name)?.let { return it }
                    return null
                }
                fun gammaSlider() = slider(editorPhysicalDisplayRoot(instrumentation), label(R.string.ui_adjustment_gamma))
                val gammaNode = checkNotNull(gammaSlider())
                check(gammaNode.rangeInfo.min == .01f && gammaNode.rangeInfo.max == 9.99f)
                check(gammaNode.rangeInfo.current == 1.125f)
                check(gammaNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2f)
                }))
                instrumentation.waitForIdleSync()
                check(draft().style.exposureGamma == 2.0 && draft().hasInvalidExposureInput && !applyEnabled())
                // Fixing one field must not enable Apply while another remains invalid.
                input(R.string.ui_adjustment_gamma, "1.125")
                check(!applyEnabled())
                input(R.string.ui_adjustment_offset, "0.0001")
                check(applyEnabled())
                click(R.string.ui_cancel)
                check(editor.state() == baseline && vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_edit_selected_adjustment)
                input(R.string.ui_adjustment_exposure, "1.234567890123")
                input(R.string.ui_adjustment_offset, "0.0001")
                input(R.string.ui_adjustment_gamma, "1.125")
                recreate()
                check(draft().style.exposure == 1.234567890123 && draft().style.offset == 0.0001 && draft().style.exposureGamma == 1.125)
                editorUiAutomation(instrumentation).takeScreenshot()?.let { image ->
                    try { File(evidence, "fields-${language.tag}.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { image.recycle() }
                }
                click(R.string.ui_apply)
                waitFor("Exposure Apply did not publish") { editor.state().layers.size == 2 && idle() }
                check(editor.state().revision == baseline.revision + 1)
                var expectedOffset = 0.0001
                fun verify(restored: NativeEditor, id: Long) {
                    val style = checkNotNull(restored.adjustmentStyleOrNull(id))
                    check(style.exposure == 1.234567890123 && style.offset == expectedOffset && style.exposureGamma == 1.125)
                }
                verify(editor, layer.id)
                val committedStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                val afterPixels = vignettePixels(editor); check(!afterPixels.contentEquals(pixelsBefore))
                click(R.string.ui_undo)
                waitFor("Exposure Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == initialStyle && idle() }
                check(vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_redo)
                waitFor("Exposure Redo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == committedStyle && idle() }
                verify(editor, layer.id); check(vignettePixels(editor).contentEquals(afterPixels))
                typedProjectRoundTripSmoke(context, editor, layer.id, ::verify)
                // Editing an existing adjustment preserves its identity and adds exactly one history item.
                val beforeEditRevision = editor.state().revision
                click(R.string.ui_edit_selected_adjustment)
                input(R.string.ui_adjustment_offset, "-0.0001")
                click(R.string.ui_apply)
                waitFor("Existing exposure edit did not publish") { editor.adjustmentStyleOrNull(layer.id)?.offset == -0.0001 && idle() }
                check(editor.state().layers.size == 2 && editor.state().revision == beforeEditRevision + 1)
                expectedOffset = -0.0001
                verify(editor, layer.id)
                click(R.string.ui_undo)
                waitFor("Existing exposure edit Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.offset == 0.0001 }
                check(vignettePixels(editor).contentEquals(afterPixels))
                click(R.string.ui_redo)
                waitFor("Existing exposure edit Redo failed") { editor.adjustmentStyleOrNull(layer.id)?.offset == -0.0001 }
                verify(editor, layer.id)
                typedProjectRoundTripSmoke(context, editor, layer.id, ::verify)
                val checkpoint = File(directory, "exact.checkpoint")
                editor.save(checkpoint)
                NativeEditor.load(checkpoint).use { restored -> verify(restored, layer.id) }
                val project = File(evidence, "exact-${language.tag}.comp")
                check(!project.exists()) { "Evidence path already occupied" }
                editor.captureProjectSnapshot().use { snapshot -> NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                } }
                observations.put(JSONObject().put("language", language.tag).put("passed", true).put("invalidInputs", 10)
                    .put("exactExposure", 1.234567890123).put("exactOffset", expectedOffset).put("exactGamma", 1.125)
                    .put("cancelRetainsCreatedLayer", true).put("activityRecreation", true).put("oneUndo", true).put("checkpointAndProject", true))
            } catch (failure: Throwable) {
                File(evidence, "failure-${language.tag}.txt").writeText(failure.stackTraceToString())
                throw failure
            } finally {
                main { activity.finish() }
                workspace.closeAsync().get(15, TimeUnit.SECONDS)
                check(directory.deleteRecursively())
            }
        }
    } finally {
        main { AppLanguages.set(context, originalLanguage) }
        File(evidence, "results.json").writeText(observations.toString(2))
    }
    return "Exposure exact Double UI input en/zh-CN; invalid text blocks UI and commit closure; Cancel retains created layer; valid/invalid recreation; single undo/redo; checkpoint/native project roundtrip. Vendor IME and live canvas preview excluded. Evidence=${evidence.path}"
}
