package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** PC geometry oracle and touch events on production handle tracks in isolated workspaces. */
internal fun levelsHandlesSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val originalLanguage = AppLanguages.current(context)
    val evidence = File(context.cacheDir, "p138-levels-handles-${System.nanoTime()}").apply { mkdirs() }
    val oracle = JSONObject(instrumentation.context.assets.open("levels-p138-handles.json").bufferedReader().use { it.readText() })
    var maxGammaUlp = 0L
    var maxPositionUlp = 0L
    var maxTouchErrorPixels = 0.0
    fun range(json: JSONObject) = AdjustmentLevelRange(json.getDouble("black"), json.getDouble("gamma"), json.getDouble("white"),
        json.getDouble("outputBlack"), json.getDouble("outputWhite"))
    fun number(actual: Double, expected: Double, name: String, mathValue: Boolean = false, position: Boolean = false) {
        val ulp = abs(java.lang.Double.doubleToRawLongBits(actual) - java.lang.Double.doubleToRawLongBits(expected))
        if (position) maxPositionUlp = maxOf(maxPositionUlp, ulp)
        else if (mathValue) maxGammaUlp = maxOf(maxGammaUlp, ulp)
        check(actual == expected || (mathValue && actual.isFinite() && expected.isFinite() && ulp <= 4)) {
            "$name actual=$actual expected=$expected ULP=$ulp"
        }
    }
    val cases = oracle.getJSONArray("cases")
    for (index in 0 until cases.length()) {
        val case = cases.getJSONObject(index)
        val handle = LevelsHandle.valueOf(case.getString("handle"))
        val before = range(case.getJSONObject("range")); val expected = range(case.getJSONObject("expected"))
        number(handle.position(before), case.getDouble("position"), "${case.getString("name")}/position", mathValue = true, position = true)
        val actual = handle.moved(before, case.getDouble("xValue"))
        for (p in LevelsParameter.entries) number(p.value(actual), p.value(expected), "${case.getString("name")}/$p", mathValue = p == LevelsParameter.GAMMA)
        actual.validate()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            check(handle.moved(before, bad) == before && handle.valueMoved(before, bad) == before)
        }
    }
    val source = AdjustmentLayerModel.create(AdjustmentKind.LEVELS).withExposure(1.25, .0001, 1.125)
    val whiteRaw = AdjustmentEditorDraft(source).withLevelsInput(0, "24").withLevelsInput(2, "24.5")
    check(whiteRaw.hasInvalidLevelsInput && whiteRaw.style.ranges[0].white == 255.0)
    val syncWhite = whiteRaw.withLevelsHandle(LevelsHandle.BLACK, 20.0)
    check(!syncWhite.hasInvalidLevelsInput && syncWhite.style.ranges[0].white == 24.5 && syncWhite.levelsInputs!![0][2] == "24.5")
    val blackRaw = AdjustmentEditorDraft(source).withLevelsInput(2, "200").withLevelsInput(0, "250")
    val syncBlack = blackRaw.withLevelsHandle(LevelsHandle.WHITE, 255.0)
    check(!syncBlack.hasInvalidLevelsInput && syncBlack.style.ranges[0].black == 250.0 && syncBlack.levelsInputs!![0][0] == "250")
    val invalid = AdjustmentEditorDraft(source).withLevelsInput(2, "").withLevelsHandle(LevelsHandle.BLACK, 24.0)
    check(invalid.hasInvalidLevelsInput && invalid.style.ranges[0].black == 24.0 && invalid.levelsInputs!![0][2] == "")
    check(invalid.style.exposure == source.exposure && invalid.style.offset == source.offset)
    val held = invalid.copy(commitInFlight = true)
    check(held.withLevelsHandle(LevelsHandle.BLACK, 50.0) == held && held.withLevelsHandleValue(LevelsHandle.GAMMA, 2.0) == held)
    val inactive = invalid.copy(style = invalid.style.withKind(AdjustmentKind.EXPOSURE))
    check(inactive.withLevelsHandle(LevelsHandle.BLACK, 50.0) == inactive)
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
            val token = "p138-levels-handles-${System.nanoTime()}"
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
            fun trackLabel(output: Boolean) = label(if (output) R.string.ui_levels_output_handles else R.string.ui_levels_input_handles)
            fun track(output: Boolean): AccessibilityNodeInfo? {
                val result = node(trackLabel(output)) ?: return null
                val rect = android.graphics.Rect().also(result::getBoundsInScreen)
                val height = 48f * activity.resources.displayMetrics.density
                return result.takeIf { it.isVisibleToUser && rect.height() >= height.toInt() - 1 && rect.width() > height }
            }
            fun revealTrack(output: Boolean) {
                reveal(trackLabel(output)) { track(output) }
            }
            fun handleNode(handle: LevelsHandle) = node(label(handle.label))
            fun progress(handle: LevelsHandle, value: Float) {
                revealTrack(handle == LevelsHandle.OUTPUT_BLACK || handle == LevelsHandle.OUTPUT_WHITE)
                val target = checkNotNull(handleNode(handle))
                check(target.rangeInfo != null && target.isVisibleToUser)
                check(target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
                }))
                instrumentation.waitForIdleSync()
            }
            val touchObservations = JSONArray()
            fun drag(handle: LevelsHandle, destination: Double, cancel: Boolean = false) {
                val output = handle == LevelsHandle.OUTPUT_BLACK || handle == LevelsHandle.OUTPUT_WHITE
                revealTrack(output)
                val bounds = android.graphics.Rect().also(checkNotNull(track(output))::getBoundsInScreen)
                val density = activity.resources.displayMetrics.density
                val inset = 24f * density
                val span = bounds.width().toFloat() - 2f * inset
                val old = draft().style.ranges[draft().style.channel]
                val startX = bounds.left + inset + (handle.position(old) / 255.0 * span).toFloat()
                val endX = bounds.left + inset + (destination / 255.0 * span).toFloat()
                val y = bounds.exactCenterY()
                val down = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float) {
                    val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                    try { instrumentation.sendPointerSync(e) } finally { e.recycle() }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(20)
                }
                event(MotionEvent.ACTION_DOWN, startX)
                for (fraction in listOf(.4f, .6f, .75f)) event(MotionEvent.ACTION_MOVE, startX + (endX - startX) * fraction)
                // Deliberately release at a different coordinate from the last MOVE.
                event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endX)
                val actual = draft().style.ranges[draft().style.channel]
                val finalScreenX = if (cancel) startX + (endX - startX) * .75f else endX
                val requested = ((finalScreenX - bounds.left).toDouble() - inset.toDouble()) / span.toDouble() * 255.0
                val errorPixels = abs(handle.position(actual) - requested) / 255.0 * span
                maxTouchErrorPixels = maxOf(maxTouchErrorPixels, errorPixels)
                check(errorPixels <= 1.0) { "Touch $handle final position mismatch: errorPx=$errorPixels actual=${handle.position(actual)} requested=$requested cancel=$cancel" }
                actual.validate()
                touchObservations.put(JSONObject().put("handle", handle.name).put("cancel", cancel)
                    .put("downX", startX).put("lastMoveX", startX + (endX - startX) * .75f).put("upOrCancelX", endX)
                    .put("spanPixels", span).put("parameter", handle.parameter.value(actual)).put("coordinateErrorPixels", errorPixels))
            }
            fun screenshot(name: String) {
                editorUiAutomation(instrumentation).takeScreenshot()?.let { image ->
                    try { File(evidence, "$name-${language.tag}.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { image.recycle() }
                }
            }
            try {
                waitFor("Levels handles fixture not ready") { activity.retainedEditorForTest()?.state()?.layers?.size == 1 &&
                    main { activity.typedLayerTestAccessForTest() != null } }
                val editor = checkNotNull(activity.retainedEditorForTest())
                val beforeCreate = editor.state(); val pixelsBefore = vignettePixels(editor)
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
                input(R.string.ui_adjustment_white, "")
                progress(LevelsHandle.BLACK, 24f)
                check(draft().hasInvalidLevelsInput && draft().style.ranges[0].black == 24.0 && !applyEnabled())
                input(R.string.ui_adjustment_white, "24.5")
                progress(LevelsHandle.BLACK, 20f)
                check(!draft().hasInvalidLevelsInput && draft().style.ranges[0].white == 24.5 && applyEnabled())
                click(R.string.ui_levels_reset)
                drag(LevelsHandle.BLACK, 60.0, cancel = true)
                check(editor.state() == baseline)
                click(R.string.ui_cancel)
                check(editor.state() == baseline && vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_edit_selected_adjustment)
                drag(LevelsHandle.BLACK, 33.0)
                drag(LevelsHandle.WHITE, 222.0)
                drag(LevelsHandle.GAMMA, 96.0)
                revealTrack(false); screenshot("input-handles")
                drag(LevelsHandle.OUTPUT_BLACK, 204.0)
                drag(LevelsHandle.OUTPUT_WHITE, 51.0)
                val rgb = draft().style.ranges[0]
                check(rgb.black == 33.0 && rgb.white == 222.0 && rgb.outputBlack == 204.0 && rgb.outputWhite == 51.0)
                check(editor.state() == baseline && vignettePixels(editor).contentEquals(pixelsBefore))
                revealTrack(true); screenshot("output-handles")
                click(R.string.ui_adjustment_red)
                progress(LevelsHandle.BLACK, 18f); progress(LevelsHandle.WHITE, 220f); progress(LevelsHandle.GAMMA, 1.125f)
                progress(LevelsHandle.OUTPUT_BLACK, 8f); progress(LevelsHandle.OUTPUT_WHITE, 240f)
                check(draft().style.ranges[1] == AdjustmentLevelRange(18.0, 1.125, 220.0, 8.0, 240.0))
                revealTrack(false)
                val gamma = checkNotNull(handleNode(LevelsHandle.GAMMA))
                check(gamma.rangeInfo.min == .1f && gamma.rangeInfo.max == 9.99f && gamma.rangeInfo.current == 1.125f)
                val beforeNaN = draft()
                check(!gamma.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN)
                }))
                check(draft() == beforeNaN)
                click(R.string.ui_adjustment_green); input(R.string.ui_adjustment_gamma, "-")
                click(R.string.ui_adjustment_blue); progress(LevelsHandle.GAMMA, 2f)
                check(draft().hasInvalidLevelsInput && !applyEnabled())
                click(R.string.ui_adjustment_green); input(R.string.ui_adjustment_gamma, "1")
                click(R.string.ui_adjustment_rgb)
                var expected = draft().style.ranges
                check(!draft().hasInvalidLevelsInput && expected[0] == rgb && expected[2] == AdjustmentLevelRange() && expected[3].gamma == 2.0)
                recreate()
                check(draft().style.ranges == expected && draft().style.channel == 0)
                click(R.string.ui_apply)
                waitFor("Handles Apply did not publish") { editor.state().layers.size == 2 && idle() }
                check(editor.state().revision == baseline.revision + 1)
                fun verify(restored: NativeEditor, id: Long) {
                    val style = checkNotNull(restored.adjustmentStyleOrNull(id))
                    check(style.kind == AdjustmentKind.LEVELS && style.channel == 0 && style.ranges == expected)
                }
                verify(editor, layer.id)
                val committedStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                val afterPixels = vignettePixels(editor); check(!afterPixels.contentEquals(pixelsBefore))
                click(R.string.ui_undo); waitFor("Handles Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == initialStyle && idle() }
                check(vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_redo); waitFor("Handles Redo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == committedStyle && idle() }
                verify(editor, layer.id); check(vignettePixels(editor).contentEquals(afterPixels))
                val oldExpected = expected; val beforeEditRevision = editor.state().revision
                click(R.string.ui_edit_selected_adjustment); progress(LevelsHandle.BLACK, 44f); click(R.string.ui_apply)
                waitFor("Existing handles edit did not publish") { editor.adjustmentStyleOrNull(layer.id)?.ranges?.get(0)?.black == 44.0 && idle() }
                expected = oldExpected.toMutableList().also { it[0] = it[0].copy(black = 44.0) }
                check(editor.state().revision == beforeEditRevision + 1 && editor.state().layers.size == 2)
                verify(editor, layer.id)
                click(R.string.ui_undo); waitFor("Existing handles edit Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.ranges == oldExpected }
                check(vignettePixels(editor).contentEquals(afterPixels))
                click(R.string.ui_redo); waitFor("Existing handles edit Redo failed") { editor.adjustmentStyleOrNull(layer.id)?.ranges == expected }
                verify(editor, layer.id)
                typedProjectRoundTripSmoke(context, editor, layer.id, ::verify)
                val checkpoint = File(directory, "handles.checkpoint")
                editor.save(checkpoint); NativeEditor.load(checkpoint).use { verify(it, layer.id) }
                val project = File(evidence, "handles-${language.tag}.comp"); check(!project.exists())
                editor.captureProjectSnapshot().use { snapshot -> NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                } }
                observations.put(JSONObject().put("language", language.tag).put("passed", true).put("touches", touchObservations)
                    .put("rawRevalidationSync", true).put("cancelRetainsCreatedLayer", true).put("activityRecreation", true)
                    .put("crossChannelInvalidGate", true).put("actualGammaAccessibility", true).put("parameterEditUndoRedo", true).put("checkpointAndProject", true))
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
        File(evidence, "results.json").writeText(JSONObject().put("observations", observations).put("oracleCases", cases.length())
            .put("maxGammaUlp", maxGammaUlp).put("maxPositionUlp", maxPositionUlp).put("maxTouchCoordinateErrorPixels", maxTouchErrorPixels)
            .put("gammaAndPositionUlpBound", 4).put("touchCoordinateBoundPixels", 1).toString(2))
    }
    return "PC ${cases.length()} handle cases; en/zh-CN five actual touch handles incl distinct final UP, ACTION_CANCEL; actual gamma accessibility; raw revalidation; cancel/recreate; separate create and parameter edit undoRedo; checkpoint/project. maxGammaULP=$maxGammaUlp maxPositionULP=$maxPositionUlp touchErrorPixels=$maxTouchErrorPixels. Live draft preview excluded. Evidence=${evidence.path}"
}
