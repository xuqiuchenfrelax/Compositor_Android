package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Actual PC sampling expectations plus physical-screen taps through the shipping draft callback. */
internal fun levelsSampleSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val originalLanguage = AppLanguages.current(context)
    val evidence = File(context.cacheDir, "p139-levels-sample-${System.nanoTime()}").apply { check(mkdirs()) }
    val oracle = JSONObject(instrumentation.context.assets.open("levels-p139-sample.json").bufferedReader().use { it.readText() })
    val cases = oracle.getJSONArray("cases")
    val sources = oracle.getJSONArray("sourceCases")
    val observations = JSONArray()
    var maximumGammaUlp = 0L
    var nativeSourceCases = 0
    fun ranges(value: JSONArray) = List(4) { index -> value.getJSONObject(index).let {
        AdjustmentLevelRange(it.getDouble("black"), it.getDouble("gamma"), it.getDouble("white"),
            it.getDouble("outputBlack"), it.getDouble("outputWhite"))
    } }
    fun rgb(value: JSONArray) = List(3) { value.getDouble(it) }
    fun compare(actual: List<AdjustmentLevelRange>, expected: List<AdjustmentLevelRange>, name: String) {
        for (channel in 0..3) for (parameter in LevelsParameter.entries) {
            val a = parameter.value(actual[channel]); val e = parameter.value(expected[channel])
            val ulp = abs(java.lang.Double.doubleToRawLongBits(a) - java.lang.Double.doubleToRawLongBits(e))
            if (parameter == LevelsParameter.GAMMA) maximumGammaUlp = maxOf(maximumGammaUlp, ulp)
            check(a == e || (parameter == LevelsParameter.GAMMA && a.isFinite() && e.isFinite() && ulp <= 4)) {
                "$name/$channel/$parameter actual=$a expected=$e ULP=$ulp"
            }
        }
    }
    fun matches(actual: List<AdjustmentLevelRange>, expected: List<AdjustmentLevelRange>): Boolean =
        (0..3).all { channel -> LevelsParameter.entries.all { parameter ->
            val a = parameter.value(actual[channel]); val e = parameter.value(expected[channel])
            a == e || (parameter == LevelsParameter.GAMMA && a.isFinite() && e.isFinite() &&
                abs(java.lang.Double.doubleToRawLongBits(a) - java.lang.Double.doubleToRawLongBits(e)) <= 4)
        } }
    for (index in 0 until cases.length()) {
        val item = cases.getJSONObject(index); val mode = LevelsSampleMode.valueOf(item.getString("mode"))
        val before = item.getJSONObject("settings"); val expected = item.getJSONObject("expected")
        val source = ranges(before.getJSONArray("ranges")); val sample = rgb(item.getJSONArray("rgb"))
        compare(mode.settings(source, sample), ranges(expected.getJSONArray("ranges")), item.getString("name"))
        val style = AdjustmentLayerModel.create(AdjustmentKind.LEVELS).withLevels(before.getInt("channel"), source)
        val draft = AdjustmentEditorDraft(style).toggleLevelsSample(mode).withLevelsSample(sample)
        check(draft.style.channel == expected.getInt("channel") && draft.levelsSampleMode == mode && draft.levelsInputs == null)
        compare(draft.style.ranges, ranges(expected.getJSONArray("ranges")), "draft/${item.getString("name")}")
    }
    for (index in 0 until sources.length()) {
        val item = sources.getJSONObject(index); val raw = item.getJSONArray("premultiplied")
        val alpha = raw.getInt(3)
        val expression = if (alpha == 0) null else List(3) { minOf(1.0, raw.getInt(it).toDouble() / alpha.toDouble()) }
        check((expression != null) == item.getBoolean("hasColor"))
        if (expression != null) check(expression == rgb(item.getJSONArray("rgb")))
        // Over-alpha supplied bytes cover only the extracted PC defensive expression.
        // Native import/scene storage requires valid premultiplied pixels.
        if ((0..2).any { raw.getInt(it) > alpha }) continue
        val ledger = ResourceBudgetLedger(32L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(1, ledger, addDefaultLayers = false).use { editor ->
            val pixels = ByteBuffer.allocateDirect(4)
            repeat(4) { pixels.put(raw.getInt(it).toByte()) }; pixels.rewind()
            editor.importPixels(1, 1, pixels)
            val before = editor.state(); val reserved = ledger.snapshot().reservedBytes
            checkNotNull(editor.captureScene()).use { scene ->
                check(scene.levelsSample(0, .25, .75) == expression) { "Native source mismatch: ${item.getString("name")}" }
                for ((x, y) in listOf(-.001 to .5, .5 to -1.0, 1.0 to .5, .5 to 1.0))
                    check(scene.levelsSample(0, x, y) == null)
                check(runCatching { scene.levelsSample(0, Double.NaN, .5) }.isFailure)
                val held = ledger.snapshot().reservedBytes
                ledger.reserve(ledger.snapshot().availableBytes, "P139 refusal injection").use {
                    check(runCatching { scene.levelsSample(0, .5, .5) }.exceptionOrNull() is ResourceBudgetExceededException)
                }
                check(ledger.snapshot().reservedBytes == held)
            }
            check(editor.state() == before && ledger.snapshot().reservedBytes == reserved)
        }
        check(ledger.snapshot().reservedBytes == 0L)
        ++nativeSourceCases
    }
    val model = AdjustmentLayerModel.create(AdjustmentKind.LEVELS).withExposure(1.25, .0001, 1.125)
    val invalid = AdjustmentEditorDraft(model).withLevelsInput(0, "-").withExposureInput(0, "-")
        .toggleLevelsSample(LevelsSampleMode.GRAY)
    val fixed = invalid.withLevelsSample(listOf(.25, .5, .75))
    check(!fixed.hasInvalidLevelsInput && fixed.levelsInputs == null && fixed.exposureInputs == invalid.exposureInputs &&
        fixed.style.exposure == model.exposure && fixed.style.offset == model.offset)
    for (bad in listOf(emptyList(), listOf(.5, .5), listOf(.5, .5, .5, .5), listOf(Double.NaN, .5, .5),
        listOf(Double.POSITIVE_INFINITY, .5, .5), listOf(-.001, .5, .5), listOf(1.001, .5, .5))) {
        check(invalid.withLevelsSample(bad) == invalid)
        check(runCatching { LevelsSampleMode.BLACK.settings(model.ranges, bad) }.isFailure)
    }
    val busy = invalid.copy(commitInFlight = true)
    check(busy.withLevelsSample(listOf(.5, .5, .5)) == busy && busy.toggleLevelsSample(LevelsSampleMode.BLACK) == busy)
    val inactive = invalid.copy(style = model.withKind(AdjustmentKind.EXPOSURE))
    check(inactive.withLevelsSample(listOf(.5, .5, .5)) == inactive && inactive.toggleLevelsSample(LevelsSampleMode.BLACK) == inactive)
    check(invalid.toggleLevelsSample(LevelsSampleMode.GRAY).levelsSampleMode == null)
    check(invalid.resetLevels().levelsSampleMode == null && invalid.withAutoLevels(LevelsAutoMode.COLOR, List(4) { List(256) { 0.0 } }).levelsSampleMode == null)
    val scope = object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
    val saved = checkNotNull(adjustmentEditorDraftSaver.run { with(scope) { save(invalid) } }) as List<*>
    val restored = checkNotNull(adjustmentEditorDraftSaver.restore(saved))
    // JSONObject uses identity equality; restoration must preserve its content.
    check(restored.style.toMacJson().toString() == invalid.style.toMacJson().toString())
    check(restored.copy(style = invalid.style) == invalid)
    for (count in 2..5) {
        val old = checkNotNull(adjustmentEditorDraftSaver.restore(saved.take(count)))
        check(old.style.toMacJson().toString() == invalid.style.toMacJson().toString())
        check(old.layerId == invalid.layerId && !old.commitInFlight && old.levelsSampleMode == null)
        check(if (count >= 3) old.submissionId == invalid.submissionId else old.submissionId.isNotBlank())
        check(old.exposureInputs == if (count >= 4) invalid.exposureInputs else null)
        check(old.levelsInputs == if (count >= 5) invalid.levelsInputs else null)
    }
    check(adjustmentEditorDraftSaver.restore(saved.take(5) + "UNKNOWN_SAMPLE") == null)
    fun <T> main(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
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
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    val uiSource = (0 until sources.length()).map(sources::getJSONObject).single { it.getString("name") == "alpha-255/02" }
    val uiRaw = uiSource.getJSONArray("premultiplied"); val uiRgb = rgb(uiSource.getJSONArray("rgb"))
    fun expected(mode: LevelsSampleMode) = ranges((0 until cases.length()).map(cases::getJSONObject)
        .single { it.getString("name") == "source/alpha-255/02/identity/${mode.name}" }
        .getJSONObject("expected").getJSONArray("ranges"))
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            main { AppLanguages.set(context, language) }
            val token = "p139-levels-sample-${System.nanoTime()}"
            val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
            NativeEditor(32, addDefaultLayers = false).use { seed ->
                val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
                repeat(32 * 32) { repeat(4) { channel -> buffer.put(uiRaw.getInt(channel).toByte()) } }; buffer.rewind()
                seed.importPixels(32, 32, buffer); seed.save(File(directory, "current.checkpoint"))
            }
            var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
            val workspace = activity.retainedWorkspaceForTest()
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            fun label(id: Int) = activity.getString(id)
            fun node(text: String) = findEditorUiNode(editorPhysicalDisplayRoot(instrumentation), text)
            fun reveal(text: String) {
                var forward = true
                waitFor("Sample control not visible: $text") {
                    val target = node(text); val rect = Rect().also { target?.getBoundsInScreen(it) }
                    if (target?.isVisibleToUser == true && !rect.isEmpty) true else {
                        val header = Rect().also { node(label(R.string.ui_collapse_inspector_dock))?.getBoundsInScreen(it) }
                        fun scroll(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                            if (value == null) return null
                            val bounds = Rect().also(value::getBoundsInScreen)
                            if (value.isScrollable && value.isVisibleToUser && value.className?.toString() == "android.widget.ScrollView" &&
                                bounds.top >= header.bottom && header.centerX() in bounds.left until bounds.right) return value
                            for (i in 0 until value.childCount) scroll(value.getChild(i))?.let { return it }
                            return null
                        }
                        if (scroll(editorPhysicalDisplayRoot(instrumentation))?.performAction(if (forward)
                                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) forward = !forward
                        false
                    }
                }
            }
            fun clickText(text: String) {
                revealEditorControl(instrumentation, text); reveal(text)
                waitFor("Sample action unavailable: $text") { clickEditorUiNode(node(text)) }
                instrumentation.waitForIdleSync()
            }
            fun click(id: Int) = clickText(label(id))
            fun access() = checkNotNull(activity.typedLayerTestAccessForTest())
            fun draft() = main { checkNotNull(access().adjustment()) }
            fun canvas() = main { checkNotNull(findCanvas(activity.window.decorView)) }
            fun sampleReady() = waitFor("Sample callback did not rebind") { main { canvas().levelsSamplePoint != null } }
            fun tap() {
                sampleReady()
                val view = canvas()
                val point = main {
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    (location[0] + view.offsetXForOverlay + 16.5f * view.scaleForOverlay) to
                        (location[1] + view.offsetYForOverlay + 16.5f * view.scaleForOverlay)
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
                        .apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    instrumentation.waitForIdleSync(); SystemClock.sleep(20)
                }
            }
            fun screenshot(name: String) {
                editorUiAutomation(instrumentation).takeScreenshot()?.let { image ->
                    try { File(evidence, "$name-${language.tag}.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { image.recycle() }
                }
            }
            try {
                waitFor("Sample isolated workspace not ready") { main { activity.retainedEditorForTest()?.state()?.layers?.size == 1 &&
                    activity.typedLayerTestAccessForTest() != null && findCanvas(activity.window.decorView)?.holder?.surface?.isValid == true } }
                check(workspace.recoveryDirectoryForTest().canonicalFile == directory.canonicalFile)
                val editor = checkNotNull(activity.retainedEditorForTest()); val beforeCreate = editor.state()
                val originalPixels = vignettePixels(editor); val foreground = main { canvas().foregroundColor }
                revealInspectorPanel(instrumentation, InspectorPanel.ADJUSTMENTS)
                click(R.string.ui_adjustment_levels)
                waitFor("Default Levels layer was not created") { main { access().adjustment()?.layerId != null && !access().busy() } }
                val baseline = editor.state()
                check(baseline.layers.size == beforeCreate.layers.size + 1 && baseline.revision == beforeCreate.revision + 1)
                val layer = baseline.layers.single { it.id == draft().layerId }
                val initialStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                check(vignettePixels(editor).contentEquals(originalPixels))
                for (mode in LevelsSampleMode.entries) {
                    if (mode != LevelsSampleMode.BLACK) click(R.string.ui_levels_reset)
                    clickText(activity.getString(R.string.ui_levels_sample_picker, label(mode.label)))
                    waitFor("Sample mode not active: $mode") { draft().levelsSampleMode == mode }
                    tap()
                    waitFor("Physical sample did not update $mode") { matches(draft().style.ranges, expected(mode)) }
                    compare(draft().style.ranges, expected(mode), "UI/${language.tag}/$mode")
                    check(draft().levelsSampleMode == mode && editor.state() == baseline &&
                        vignettePixels(editor).contentEquals(originalPixels) && main { canvas().foregroundColor } == foreground)
                    screenshot("sample-${mode.name.lowercase(java.util.Locale.ROOT)}")
                }
                val finalRanges = expected(LevelsSampleMode.WHITE)
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    main { activity.recreate() }
                    activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity)
                } finally { instrumentation.removeMonitor(monitor) }
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                waitFor("Sample mode not restored") { main { access().adjustment()?.levelsSampleMode == LevelsSampleMode.WHITE } }
                check(draft().style.ranges == finalRanges); sampleReady()
                click(R.string.ui_cancel)
                waitFor("Sample Cancel did not dismiss draft") { main { access().adjustment() == null } }
                check(editor.state() == baseline && vignettePixels(editor).contentEquals(originalPixels))
                click(R.string.ui_edit_selected_adjustment)
                clickText(activity.getString(R.string.ui_levels_sample_picker, label(LevelsSampleMode.GRAY.label)))
                tap(); waitFor("Gray sample did not update after Cancel") { matches(draft().style.ranges, expected(LevelsSampleMode.GRAY)) }
                val committedRanges = draft().style.ranges
                compare(committedRanges, expected(LevelsSampleMode.GRAY), "UI/${language.tag}/committedGray")
                click(R.string.ui_apply)
                waitFor("Sample Apply did not publish") { editor.state().layers.size == 2 && main { !access().busy() && access().adjustment() == null } }
                check(editor.state().revision == baseline.revision + 1)
                fun verify(restoredEditor: NativeEditor, id: Long) {
                    val style = checkNotNull(restoredEditor.adjustmentStyleOrNull(id))
                    check(style.kind == AdjustmentKind.LEVELS && style.channel == 0)
                    compare(style.ranges, committedRanges, "saved/${language.tag}")
                }
                verify(editor, layer.id)
                val committedStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                val afterPixels = vignettePixels(editor); check(!afterPixels.contentEquals(originalPixels))
                checkNotNull(editor.captureScene()).use { scene ->
                    check(scene.levelsSample(layer.id, 16.5, 16.5) == uiRgb) { "Existing adjustment sampled its own output" }
                    check(scene.levelsSample(0, 16.5, 16.5) != uiRgb) { "Nonidentity adjustment did not change full composite" }
                }
                click(R.string.ui_undo); waitFor("Sample Undo failed") {
                    editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == initialStyle && main { !access().busy() }
                }
                check(vignettePixels(editor).contentEquals(originalPixels))
                click(R.string.ui_redo); waitFor("Sample Redo failed") {
                    editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == committedStyle && main { !access().busy() }
                }
                verify(editor, layer.id); check(vignettePixels(editor).contentEquals(afterPixels))
                typedProjectRoundTripSmoke(context, editor, layer.id, ::verify)
                val checkpoint = File(directory, "sample.checkpoint")
                editor.save(checkpoint); NativeEditor.load(checkpoint).use { verify(it, layer.id) }
                val project = File(evidence, "sample-${language.tag}.comp"); check(!project.exists())
                editor.captureProjectSnapshot().use { snapshot -> NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                } }
                observations.put(JSONObject().put("language", language.tag).put("passed", true).put("threePhysicalTouchModes", true)
                    .put("foregroundAndDocumentUnchangedDuringDraft", true).put("persistentModeAndRecreation", true)
                    .put("cancelRetainsCreatedLayer", true).put("singleApplyUndoRedo", true).put("existingSourceExcludesSelf", true).put("checkpointAndProject", true))
            } catch (failure: Throwable) {
                File(evidence, "failure-${language.tag}.txt").writeText(failure.stackTraceToString())
                throw failure
            } finally {
                main { activity.finish() }; workspace.closeAsync().get(15, TimeUnit.SECONDS)
                check(directory.deleteRecursively())
            }
        }
    } finally {
        main { AppLanguages.set(context, originalLanguage) }
        File(evidence, "results.json").writeText(JSONObject().put("observations", observations).put("oracleCases", cases.length())
            .put("sourceExpressionCases", sources.length()).put("validPremultipliedNativeSourceCases", nativeSourceCases)
            .put("maxGammaUlp", maximumGammaUlp).put("gammaUlpBound", 4).toString(2))
    }
    return "PC ${cases.length()} settings + ${sources.length()} provided byte/alpha cases; $nativeSourceCases valid native source cases; en/zh-CN three physical tap modes, original source/foreground unchanged, cancel/recreate, single Apply undoRedo, checkpoint/project. maxGammaULP=$maximumGammaUlp. Full raster/live preview excluded. Evidence=${evidence.path}"
}
