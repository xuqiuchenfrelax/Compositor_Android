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

/** Shipping Levels controls in private workspaces; accessibility text actions are not vendor IME coverage. */
internal fun levelsInputSmoke(instrumentation: Instrumentation): String {
    val source = AdjustmentLayerModel.create(AdjustmentKind.LEVELS)
        .withExposure(value = 1.234567890123, offsetValue = .0001, gammaValue = 1.125)
    val invalid = AdjustmentEditorDraft(source).withLevelsInput(0, "12.34567890123").withLevelsInput(2, "")
    check(invalid.hasInvalidLevelsInput && invalid.style.ranges[0].black == 12.34567890123)
    check(invalid.withLevelsInput(0, "0".repeat(64) + "NaN") == invalid)
    check(invalid.withLevelsSlider(0, Double.NaN) == invalid)
    val repaired = invalid.withLevelsSlider(0, 24.0)
    check(repaired.style.ranges[0].black == 24.0 && repaired.levelsInputs!![0][2] == "" && repaired.hasInvalidLevelsInput)
    val switched = invalid.copy(style = invalid.style.withLevels(channelValue = 1))
    check(switched.hasInvalidLevelsInput)
    val inactive = invalid.copy(style = invalid.style.withKind(AdjustmentKind.EXPOSURE))
    check(!inactive.hasInvalidLevelsInput && inactive.copy(style = inactive.style.withKind(AdjustmentKind.LEVELS)).hasInvalidLevelsInput)
    val reset = switched.resetLevels()
    check(reset.style.channel == 0 && reset.style.ranges.all { it == AdjustmentLevelRange() } && reset.levelsInputs == null)
    val expectedWire = source.toMacJson().apply { remove("levels") }
    val resetWire = reset.style.toMacJson().apply { remove("levels") }
    check(equalAdjustmentJson(expectedWire, resetWire)) { "Levels reset changed unrelated settings" }
    val saverScope = object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
    val mixed = invalid.withExposureInput(0, "-")
    val saved = checkNotNull(adjustmentEditorDraftSaver.run { with(saverScope) { save(mixed) } }) as List<*>
    val restored = checkNotNull(adjustmentEditorDraftSaver.restore(saved))
    check(restored.hasInvalidLevelsInput && restored.levelsInputs == mixed.levelsInputs && restored.exposureInputs == mixed.exposureInputs)
    for (count in listOf(2, 3, 4)) check(checkNotNull(adjustmentEditorDraftSaver.restore(saved.take(count))).style.ranges[0].black == 12.34567890123)
    val bounded = AdjustmentEditorDraft(source).withLevelsInput(2, "200").withLevelsSlider(0, 254.0)
    check(bounded.style.ranges[0].black == 199.0)
    check(bounded.withLevelsSlider(2, 1.0).style.ranges[0].white == 200.0)
    val context = instrumentation.targetContext
    val originalLanguage = AppLanguages.current(context)
    val evidence = File(context.cacheDir, "p136-levels-${System.nanoTime()}").apply { mkdirs() }
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
            val token = "p136-levels-${System.nanoTime()}"
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
            fun reveal(text: String, findTarget: () -> AccessibilityNodeInfo? = { node(text) }) {
                var forward = true
                waitFor("Levels control not visible: $text") {
                    val target = findTarget()
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
                waitFor("Levels action unavailable: $text") { clickEditorUiNode(node(text)) }
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
                waitFor("Levels field unavailable: ${fieldLabel(id)}") { field(id)?.isVisibleToUser == true }
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
                waitFor("Levels draft did not reattach") { main { activity.typedLayerTestAccessForTest()?.adjustment() != null } }
            }
            fun slider(id: Int): AccessibilityNodeInfo? {
                fun find(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (value == null) return null
                    if (value.contentDescription?.toString() == label(id) && value.rangeInfo != null) return value
                    for (i in 0 until value.childCount) find(value.getChild(i))?.let { return it }
                    return null
                }
                return find(editorPhysicalDisplayRoot(instrumentation))
            }
            fun progress(id: Int, value: Float) {
                reveal(label(id)) { slider(id) }
                check(checkNotNull(slider(id)).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
                instrumentation.waitForIdleSync()
            }
            try {
                waitFor("Levels fixture not ready") { activity.retainedEditorForTest()?.state()?.layers?.size == 1 &&
                    main { activity.typedLayerTestAccessForTest() != null } }
                val editor = checkNotNull(activity.retainedEditorForTest())
                val beforeCreate = editor.state(); val pixelsBefore = vignettePixels(editor)
                val channels = listOf(R.string.ui_adjustment_rgb, R.string.ui_adjustment_red,
                    R.string.ui_adjustment_green, R.string.ui_adjustment_blue)
                val fields = listOf(R.string.ui_adjustment_black, R.string.ui_adjustment_gamma,
                    R.string.ui_adjustment_white, R.string.ui_adjustment_output_black, R.string.ui_adjustment_output_white)
                revealInspectorPanel(instrumentation, InspectorPanel.ADJUSTMENTS)
                click(R.string.ui_adjustment_levels)
                waitFor("Default adjustment was not created") { main {
                    activity.typedLayerTestAccessForTest()?.adjustment()?.layerId != null &&
                        activity.typedLayerTestAccessForTest()?.busy() == false
                } }
                val baseline = editor.state()
                check(baseline.layers.size == beforeCreate.layers.size + 1 && baseline.revision == beforeCreate.revision + 1)
                val layer = baseline.layers.single { it.id == draft().layerId }
                val initialStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                check(vignettePixels(editor).contentEquals(pixelsBefore))
                val invalids = listOf(0 to "", 0 to "-", 0 to "NaN", 0 to "Infinity", 0 to "1e309",
                    0 to "-0.0001", 0 to "254.0001", 1 to "0.0999", 1 to "9.9901", 2 to "0", 2 to "255.0001",
                    3 to "-0.0001", 4 to "255.0001")
                for ((index, text) in invalids) {
                    input(fields[index], text)
                    check(draft().hasInvalidLevelsInput && !applyEnabled()) { "Accepted invalid Levels $index=$text" }
                    main { activity.typedLayerTestAccessForTest()!!.applyAdjustment() }
                    check(idle() && editor.state() == baseline)
                    input(fields[index], listOf("0", "1", "255", "0", "255")[index])
                }
                input(R.string.ui_adjustment_black, "24")
                input(R.string.ui_adjustment_white, "24.9999")
                check(draft().hasInvalidLevelsInput && draft().style.ranges[0].white == 255.0 && !applyEnabled())
                input(R.string.ui_adjustment_white, "")
                progress(R.string.ui_adjustment_black, 30f)
                check(draft().style.ranges[0].black == 30.0 && draft().hasInvalidLevelsInput)
                click(R.string.ui_adjustment_red)
                input(R.string.ui_adjustment_gamma, "1.125")
                check(draft().hasInvalidLevelsInput && !applyEnabled())
                reveal(label(R.string.ui_levels_other_channel_invalid))
                waitFor("Missing cross-channel error") { node(label(R.string.ui_levels_other_channel_invalid)) != null }
                recreate()
                check(activity.retainedEditorForTest() === editor && draft().style.channel == 1)
                check(draft().levelsInputs!![0][2] == "" && draft().hasInvalidLevelsInput && !applyEnabled())
                click(R.string.ui_levels_reset)
                check(draft().style.channel == 0 && draft().style.ranges.all { it == AdjustmentLevelRange() } && applyEnabled())
                click(R.string.ui_cancel)
                check(editor.state() == baseline && vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_edit_selected_adjustment)
                val values = listOf(
                    listOf("12.34567890123", "1.234567890123", "231.234567890123", "2.34567890123", "249.234567890123"),
                    listOf("13.125", "1.125", "230.125", "3.125", "248.125"),
                    listOf("14.25", "1.375", "229.25", "4.25", "247.25"),
                    listOf("15.5", "1.625", "228.5", "5.5", "246.5"))
                val exact = values.map { row -> AdjustmentLevelRange(row[0].toDouble(), row[1].toDouble(), row[2].toDouble(), row[3].toDouble(), row[4].toDouble()) }
                for (channel in channels.indices) {
                    if (draft().style.channel != channel) click(channels[channel])
                    check(draft().style.channel == channel)
                    fields.forEachIndexed { index, id -> input(id, values[channel][index]) }
                    check(draft().style.ranges[channel] == exact[channel])
                }
                recreate()
                check(draft().style.ranges == exact && draft().style.channel == 3 && !draft().hasInvalidLevelsInput)
                reveal(label(R.string.ui_adjustment_gamma)) { slider(R.string.ui_adjustment_gamma) }
                val gamma = checkNotNull(slider(R.string.ui_adjustment_gamma)).rangeInfo
                check(gamma.min == .1f && gamma.max == 9.99f && gamma.current == 1.625f)
                editorUiAutomation(instrumentation).takeScreenshot()?.let { image ->
                    try { File(evidence, "fields-${language.tag}.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { image.recycle() }
                }
                click(R.string.ui_apply)
                waitFor("Levels Apply did not publish") { editor.state().layers.size == 2 && idle() }
                check(editor.state().revision == baseline.revision + 1)
                fun verify(restored: NativeEditor, id: Long) {
                    val style = checkNotNull(restored.adjustmentStyleOrNull(id))
                    check(style.kind == AdjustmentKind.LEVELS && style.channel == 3 && style.ranges == exact)
                }
                verify(editor, layer.id)
                val committedStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                val afterPixels = vignettePixels(editor); check(!afterPixels.contentEquals(pixelsBefore))
                click(R.string.ui_undo)
                waitFor("Levels Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == initialStyle && idle() }
                check(vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_redo)
                waitFor("Levels Redo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == committedStyle && idle() }
                verify(editor, layer.id); check(vignettePixels(editor).contentEquals(afterPixels))
                // Reset affects all channels. Cancel first, then publish one existing-layer reset command.
                click(R.string.ui_edit_selected_adjustment)
                click(R.string.ui_levels_reset)
                check(draft().style.ranges.all { it == AdjustmentLevelRange() } && draft().style.channel == 0)
                click(R.string.ui_cancel)
                verify(editor, layer.id); check(vignettePixels(editor).contentEquals(afterPixels))
                val editRevision = editor.state().revision
                click(R.string.ui_edit_selected_adjustment)
                click(R.string.ui_levels_reset)
                click(R.string.ui_apply)
                waitFor("Levels Reset did not publish") { editor.adjustmentStyleOrNull(layer.id)?.ranges?.all { it == AdjustmentLevelRange() } == true && idle() }
                check(editor.state().layers.size == 2 && editor.state().revision == editRevision + 1)
                check(editor.adjustmentStyleOrNull(layer.id)?.channel == 0 && vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_undo)
                waitFor("Levels Reset Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.ranges == exact }
                verify(editor, layer.id); check(vignettePixels(editor).contentEquals(afterPixels))
                click(R.string.ui_redo)
                waitFor("Levels Reset Redo failed") { editor.adjustmentStyleOrNull(layer.id)?.ranges?.all { it == AdjustmentLevelRange() } == true }
                check(vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_undo)
                waitFor("Levels final Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.ranges == exact }
                verify(editor, layer.id)
                typedProjectRoundTripSmoke(context, editor, layer.id, ::verify)
                val checkpoint = File(directory, "exact.checkpoint")
                editor.save(checkpoint)
                NativeEditor.load(checkpoint).use { restored -> verify(restored, layer.id) }
                val project = File(evidence, "exact-${language.tag}.comp")
                check(!project.exists())
                editor.captureProjectSnapshot().use { snapshot -> NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                } }
                observations.put(JSONObject().put("language", language.tag).put("passed", true).put("invalidInputs", invalids.size + 1)
                    .put("channels", 4).put("parametersPerChannel", 5).put("cancelRetainsCreatedLayer", true).put("crossChannelInvalidGate", true)
                    .put("activityRecreation", true).put("parameterEditAndResetUndoRedo", true).put("checkpointAndProject", true))
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
    return "Levels exact Double UI input en/zh-CN across 4x5 fields; invalid and cross-channel input blocks UI/commit; cancel and reset; valid/invalid recreation; parameter edit/reset undo/redo; checkpoint/native project. Histogram, auto-levels, sampling, vendor IME and live preview excluded. Evidence=${evidence.path}"
}
