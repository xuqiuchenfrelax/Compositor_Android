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

/** Actual PC-generated expectations, native immutable input and production Auto buttons. */
internal fun levelsAutoSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val originalLanguage = AppLanguages.current(context)
    val evidence = File(context.cacheDir, "p137-levels-auto-${System.nanoTime()}").apply { mkdirs() }
    val oracle = JSONObject(instrumentation.context.assets.open("levels-p137-oracle.json").bufferedReader().use { it.readText() })
    var maximumUlp = 0L
    var maximumAbsolute = 0.0
    fun number(actual: Double, expected: Double, name: String, libm: Boolean = false) {
        val ulp = kotlin.math.abs(java.lang.Double.doubleToRawLongBits(actual) - java.lang.Double.doubleToRawLongBits(expected))
        val absolute = kotlin.math.abs(actual - expected)
        if (libm) { maximumUlp = maxOf(maximumUlp, ulp); maximumAbsolute = maxOf(maximumAbsolute, absolute) }
        check(actual == expected || (libm && actual.isFinite() && expected.isFinite() && ulp <= 4)) {
            "$name: actual=$actual expected=$expected ULP=$ulp"
        }
    }
    fun bins(value: JSONArray) = List(4) { c -> List(256) { i -> value.getJSONArray(c).getDouble(i) } }
    fun ranges(value: JSONArray) = List(4) { c -> value.getJSONObject(c).let {
        AdjustmentLevelRange(it.getDouble("black"), it.getDouble("gamma"), it.getDouble("white"),
            it.getDouble("outputBlack"), it.getDouble("outputWhite"))
    } }
    fun compare(actual: List<AdjustmentLevelRange>, expected: List<AdjustmentLevelRange>, name: String) {
        for (c in 0..3) {
            number(actual[c].black, expected[c].black, "$name/$c/black")
            number(actual[c].gamma, expected[c].gamma, "$name/$c/gamma", libm = true)
            number(actual[c].white, expected[c].white, "$name/$c/white")
            number(actual[c].outputBlack, expected[c].outputBlack, "$name/$c/outputBlack")
            number(actual[c].outputWhite, expected[c].outputWhite, "$name/$c/outputWhite")
        }
    }
    var modelCases = 0
    var nativeCases = 0
    val pixelCases = oracle.getJSONArray("pixelCases")
    val histogramCases = oracle.getJSONArray("histogramCases")
    val allCases = (0 until pixelCases.length()).map(pixelCases::getJSONObject) +
        (0 until histogramCases.length()).map(histogramCases::getJSONObject)
    for (case in allCases) {
        val source = bins(case.getJSONArray("bins")); val name = case.getString("name")
        for (c in 0..3) number(levelsHistogramDisplayScale(source[c]), case.getJSONArray("scales").getDouble(c), "$name/scale$c")
        for (mode in LevelsAutoMode.entries) compare(mode.settings(source),
            ranges(case.getJSONObject("modes").getJSONObject(mode.name.lowercase(java.util.Locale.ROOT)).getJSONArray("ranges")), "$name/$mode")
        ++modelCases
    }
    for (case in (0 until pixelCases.length()).map(pixelCases::getJSONObject).filter { it.isNull("coverage") }) {
        val width = case.getInt("width"); val height = case.getInt("height"); val raw = case.getJSONArray("pixels")
        val pixels = ByteBuffer.allocateDirect(raw.length())
        for (i in 0 until raw.length()) pixels.put(raw.getInt(i).toByte()); pixels.rewind()
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(maxOf(width, height), ledger, addDefaultLayers = false).use { editor ->
            editor.importPixels(width, height, pixels)
            val before = editor.state()
            val reserved = ledger.snapshot().reservedBytes
            checkNotNull(editor.captureScene()).use { scene ->
                val actual = scene.levelsHistogram()
                val expected = bins(case.getJSONArray("bins")).flatten()
                actual.forEachIndexed { i, n -> number(n, expected[i], "native/${case.getString("name")}/$i") }
                val withScene = ledger.snapshot().reservedBytes
                ledger.reserve(ledger.snapshot().availableBytes, "P137 refusal injection").use {
                    check(runCatching { scene.levelsHistogram() }.exceptionOrNull() is ResourceBudgetExceededException)
                }
                check(ledger.snapshot().reservedBytes == withScene)
                check(runCatching { scene.levelsHistogram(before.layers.last().id) }.isFailure)
            }
            check(ledger.snapshot().reservedBytes == reserved && editor.state() == before)
        }
        check(ledger.snapshot().reservedBytes == 0L)
        ++nativeCases
    }
    val uiCase = (0 until pixelCases.length()).map(pixelCases::getJSONObject).single { it.getString("name") == "ui-gradient" }
    val uiPixels = uiCase.getJSONArray("pixels")
    val uiBins = bins(uiCase.getJSONArray("bins"))
    fun expected(mode: LevelsAutoMode) = ranges(uiCase.getJSONObject("modes")
        .getJSONObject(mode.name.lowercase(java.util.Locale.ROOT)).getJSONArray("ranges"))
    val retained = AdjustmentLayerModel.create(AdjustmentKind.LEVELS).withExposure(1.25, .0001, 1.125)
    val invalid = AdjustmentEditorDraft(retained).withExposureInput(0, "-").withLevelsInput(0, "-")
    val automatic = invalid.withAutoLevels(LevelsAutoMode.COLOR, uiBins)
    check(!automatic.hasInvalidLevelsInput && automatic.levelsInputs == null && automatic.style.channel == 0)
    check(automatic.exposureInputs == invalid.exposureInputs && automatic.style.exposure == 1.25 && automatic.style.offset == .0001)
    check(invalid.copy(commitInFlight = true).let { it.withAutoLevels(LevelsAutoMode.COLOR, uiBins) == it })
    for (bad in listOf(List(3) { List(256) { 0.0 } }, uiBins.mapIndexed { i, row -> if (i == 0) row.toMutableList().also { it[0] = Double.NaN } else row },
        uiBins.mapIndexed { i, row -> if (i == 0) row.toMutableList().also { it[0] = -1.0 } else row })) {
        check(runCatching { LevelsAutoMode.COLOR.settings(bad) }.isFailure)
    }
    val flat = uiBins.flatten().toDoubleArray()
    val copied = LevelsHistogramState.ready(flat)
    flat.fill(0.0); check(copied.bins == uiBins)
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
            val token = "p137-levels-auto-${System.nanoTime()}"
            val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
            NativeEditor(32, addDefaultLayers = false).use { seed ->
                val pixels = ByteBuffer.allocateDirect(uiPixels.length())
                for (index in 0 until uiPixels.length()) pixels.put(uiPixels.getInt(index).toByte())
                pixels.rewind()
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
                waitFor("Auto Levels control not visible: $text") {
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
            fun enabled(id: Int): Boolean {
                reveal(label(id))
                var value = node(label(id))
                repeat(12) { if (value?.isClickable == true) return value!!.isEnabled; value = value?.parent }
                error("Auto button missing")
            }
            fun ready() {
                reveal(label(R.string.ui_levels_auto_contrast))
                waitFor("Histogram did not enable Auto") { enabled(R.string.ui_levels_auto_contrast) }
            }
            fun screenshot(name: String) {
                editorUiAutomation(instrumentation).takeScreenshot()?.let { image ->
                    try { File(evidence, "$name-${language.tag}.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { image.recycle() }
                }
            }
            try {
                waitFor("Auto Levels fixture not ready") { activity.retainedEditorForTest()?.state()?.layers?.size == 1 &&
                    main { activity.typedLayerTestAccessForTest() != null } }
                val editor = checkNotNull(activity.retainedEditorForTest())
                val beforeCreate = editor.state(); val pixelsBefore = vignettePixels(editor)
                revealInspectorPanel(instrumentation, InspectorPanel.ADJUSTMENTS)
                click(R.string.ui_adjustment_levels)
                waitFor("Default Levels layer was not created") { main {
                    activity.typedLayerTestAccessForTest()?.adjustment()?.layerId != null &&
                        activity.typedLayerTestAccessForTest()?.busy() == false
                } }
                val baseline = editor.state()
                check(baseline.layers.size == beforeCreate.layers.size + 1 && baseline.revision == beforeCreate.revision + 1)
                val layer = baseline.layers.single { it.id == draft().layerId }
                val initialStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                ready()
                click(R.string.ui_cancel)
                waitFor("Initial Levels editor did not close") { main { activity.typedLayerTestAccessForTest()?.adjustment() == null } }
                // Force a bounded source-capture refusal without allocating memory or changing the document.
                val blocker = editor.resourceLedger.reserve(editor.resourceLedger.snapshot().availableBytes, "P137 UI refusal injection")
                try {
                    click(R.string.ui_edit_selected_adjustment)
                    reveal(label(R.string.ui_levels_histogram_failed))
                    check(!enabled(R.string.ui_levels_auto_contrast) && !enabled(R.string.ui_levels_auto_color) && !enabled(R.string.ui_levels_auto_neutral))
                    input(R.string.ui_adjustment_gamma, "1.125")
                    check(draft().style.ranges[0].gamma == 1.125 && editor.state() == baseline)
                } finally { blocker.close() }
                click(R.string.ui_levels_histogram_retry)
                ready()
                for (mode in LevelsAutoMode.entries) {
                    click(mode.label)
                    compare(draft().style.ranges, expected(mode), "UI/${language.tag}/$mode")
                    check(draft().style.channel == 0 && !draft().hasInvalidLevelsInput && editor.state() == baseline)
                }
                // Auto also repairs invalid drafts, matching PC replacement of the complete Levels settings.
                input(R.string.ui_adjustment_black, "-")
                check(!applyEnabled())
                click(R.string.ui_levels_auto_color)
                check(applyEnabled() && draft().levelsInputs == null)
                compare(draft().style.ranges, expected(LevelsAutoMode.COLOR), "UI repaired")
                click(R.string.ui_cancel)
                check(editor.state() == baseline && vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_edit_selected_adjustment)
                ready(); click(R.string.ui_levels_auto_neutral)
                recreate(); ready()
                compare(draft().style.ranges, expected(LevelsAutoMode.NEUTRAL), "UI recreate")
                reveal(activity.getString(R.string.ui_levels_histogram_channel, label(R.string.ui_adjustment_rgb)))
                screenshot("histogram")
                click(R.string.ui_apply)
                waitFor("Auto Levels did not commit") { editor.state().layers.size == 2 && idle() }
                check(editor.state().revision == baseline.revision + 1)
                fun verify(restored: NativeEditor, id: Long) {
                    val style = checkNotNull(restored.adjustmentStyleOrNull(id))
                    check(style.kind == AdjustmentKind.LEVELS && style.channel == 0)
                    compare(style.ranges, expected(LevelsAutoMode.NEUTRAL), "project")
                }
                verify(editor, layer.id)
                val committedStyle = checkNotNull(editor.adjustmentStyleOrNull(layer.id)).toMacJson().toString()
                val afterPixels = vignettePixels(editor); check(!afterPixels.contentEquals(pixelsBefore))
                click(R.string.ui_undo); waitFor("Auto Undo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == initialStyle && idle() }
                check(vignettePixels(editor).contentEquals(pixelsBefore))
                click(R.string.ui_redo); waitFor("Auto Redo failed") { editor.adjustmentStyleOrNull(layer.id)?.toMacJson()?.toString() == committedStyle && idle() }
                verify(editor, layer.id); check(vignettePixels(editor).contentEquals(afterPixels))
                // The edited adjustment must be excluded from its own source. Repeating Auto is idempotent.
                val beforeNoOp = editor.state()
                click(R.string.ui_edit_selected_adjustment); ready(); click(R.string.ui_levels_auto_neutral)
                compare(draft().style.ranges, expected(LevelsAutoMode.NEUTRAL), "existing input prefix")
                click(R.string.ui_apply)
                waitFor("Existing Auto no-op did not finish") { idle() && main { activity.typedLayerTestAccessForTest()?.adjustment() == null } }
                verify(editor, layer.id)
                check(editor.state().revision == beforeNoOp.revision && editor.state().layers == beforeNoOp.layers)
                check(vignettePixels(editor).contentEquals(afterPixels))
                typedProjectRoundTripSmoke(context, editor, layer.id, ::verify)
                val checkpoint = File(directory, "automatic.checkpoint")
                editor.save(checkpoint); NativeEditor.load(checkpoint).use { verify(it, layer.id) }
                val project = File(evidence, "automatic-${language.tag}.comp"); check(!project.exists())
                editor.captureProjectSnapshot().use { snapshot -> NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                } }
                observations.put(JSONObject().put("language", language.tag).put("passed", true).put("modes", 3)
                    .put("sourceRefusalAndRetry", true).put("manualInputDuringFailure", true).put("autoRepairsInvalid", true)
                    .put("cancelRetainsCreatedLayer", true).put("recreation", true).put("existingAdjustmentExcluded", true)
                    .put("undoRedo", true).put("checkpointAndProject", true))
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
        File(evidence, "results.json").writeText(JSONObject().put("observations", observations).put("modelCases", modelCases)
            .put("nativePixelCases", nativeCases).put("maxGammaUlp", maximumUlp).put("maxGammaAbsoluteError", maximumAbsolute)
            .put("histogramTolerance", 0).put("gammaUlpBound", 4).toString(2))
    }
    return "Levels histogram actual PC bins zero tolerance; $modelCases model cases, $nativeCases native pixel cases; 3 Auto modes en/zh-CN; refusal/retry/manual editing; cancel/recreate; existing adjustment input prefix; undoRedo/checkpoint/project. gamma maxULP=$maximumUlp maxAbsolute=$maximumAbsolute. Vendor IME/live draft preview excluded. Evidence=${evidence.path}"
}
