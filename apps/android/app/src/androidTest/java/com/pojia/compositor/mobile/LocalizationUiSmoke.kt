package com.pojia.compositor.mobile

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Uses the shipping language controls; never changes the device's global locale or clears app data. */
internal fun localizationUiSmoke(instrumentation: Instrumentation, selectedTool: EditorTool = EditorTool.BRUSH,
    retainedNoticeKey: UiNoticeKey = UiNoticeKey.EDITOR_COMMAND_FAILED): String {
    check(selectedTool == EditorTool.BRUSH || selectedTool == EditorTool.HEALING)
    check(retainedNoticeKey in setOf(UiNoticeKey.EDITOR_COMMAND_FAILED, UiNoticeKey.DOCUMENT_RESOLUTION_INVALID))
    val validationNotice = retainedNoticeKey == UiNoticeKey.DOCUMENT_RESOLUTION_INVALID
    val automation = editorUiAutomation(instrumentation)
    val application = instrumentation.targetContext.applicationContext as Application
    val resumed = AtomicReference<MainActivity?>()
    val resumeCount = AtomicInteger()
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity) { resumed.set(activity); resumeCount.incrementAndGet() }
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    application.registerActivityLifecycleCallbacks(callbacks)
    val evidence = File(instrumentation.targetContext.cacheDir, "localization-ui").apply { check(exists() || mkdirs()) }
    val observations = JSONArray()
    val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("globalLocaleChanged", false)
    var original: AppLanguage? = null
    var originalTags: String? = null
    var restorationFailure: Throwable? = null
    var failure: Throwable? = null
    var testErrorPending = false
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    fun dump() {
        val text = StringBuilder()
        fun append(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            text.append(" ".repeat(depth)).append(node.className).append(" text=").append(node.text)
                .append(" description=").append(node.contentDescription).append(" visible=").append(node.isVisibleToUser)
                .append(" enabled=").append(node.isEnabled).append('\n')
            for (index in 0 until node.childCount) append(node.getChild(index), depth + 1)
        }
        append(automation.rootInActiveWindow, 0)
        File(evidence, "failure.txt").writeText(text.toString())
    }
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
            instrumentation.waitForIdleSync()
        }
        dump()
        error(message)
    }
    fun active(): MainActivity = checkNotNull(resumed.get()) { "No resumed editor activity" }
    fun ready(previous: MainActivity? = null, expected: AppLanguage? = null, requireRecreation: Boolean = false,
        allowErrorDialog: Boolean = false): MainActivity {
        var accepted: MainActivity? = null
        waitUntil("Localized activity/canvas did not become ready: language=$expected recreation=$requireRecreation") {
            val activity = resumed.get() ?: return@waitUntil false
            var isReady = false
            instrumentation.runOnMainSync {
                val canvas = findCanvas(activity.window.decorView)
                isReady = !activity.isDestroyed && !activity.isFinishing && (allowErrorDialog || activity.hasWindowFocus()) &&
                    activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    canvas != null && canvas.isShown && canvas.width > 0 && canvas.height > 0 &&
                    activity.retainedEditorForTest() != null &&
                    (!requireRecreation || activity !== previous) &&
                    (expected == null || AppLanguages.current(activity) == expected)
            }
            if (isReady) accepted = activity
            isReady
        }
        return checkNotNull(accepted)
    }
    fun has(label: String): Boolean {
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache() else automation.rootInActiveWindow?.refresh()
        return find(automation.rootInActiveWindow, label)?.isVisibleToUser == true
    }
    fun click(label: String) {
        var clicked = false
        waitUntil("Localized UI control missing or disabled: $label") {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache() else automation.rootInActiveWindow?.refresh()
            fun actionable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (root == null) return null
                if (root.text?.toString() == label || root.contentDescription?.toString() == label) {
                    var owner: AccessibilityNodeInfo? = root
                    while (owner != null && !owner.isClickable) owner = owner.parent
                    if (owner?.isVisibleToUser == true && owner.isEnabled) return owner
                }
                for (index in 0 until root.childCount) actionable(root.getChild(index))?.let { return it }
                return null
            }
            if (!clicked) clicked = actionable(automation.rootInActiveWindow)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    fun screenshot(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot()) { "Localization screenshot unavailable: $name" }
        try { File(evidence, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    fun catalogString(language: AppLanguage, resource: Int): String {
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language.tag))
        }
        return instrumentation.targetContext.createConfigurationContext(configuration).getString(resource)
    }
    fun openLanguage() {
        val activity = ready()
        val label = activity.getString(R.string.language_settings)
        revealEditorControl(instrumentation, label)
        click(label)
    }
    fun select(language: AppLanguage) {
        val previous = ready()
        val changed = AppLanguages.current(previous) != language
        openLanguage()
        click(previous.getString(language.label))
        ready(previous, language, changed)
    }
    try {
        report.put("typedNoticeChecks", localizationNoticeSmoke(instrumentation))
        val initial = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        resumed.compareAndSet(null, initial)
        ready()
        original = AppLanguages.current(initial)
        if (Build.VERSION.SDK_INT >= 33) originalTags = initial.getSystemService(LocaleManager::class.java)
            .applicationLocales.toLanguageTags()
        report.put("originalLanguage", checkNotNull(original).name).put("originalLocaleTags", originalTags)
        val editor = checkNotNull(initial.retainedEditorForTest())
        editor.commandDispatcher.awaitIdleForTest().get(20, TimeUnit.SECONDS)
        val baseline = editor.state()
        val toolKeys = EditorTool.entries.map { it.name to it.key }
        // Select a real tool so recreation must retain its saved enum, rather than resetting to IDLE.
        var initialErasing = false
        instrumentation.runOnMainSync { initialErasing = findCanvas(initial.window.decorView)?.erasing == true }
        click(if (initialErasing && selectedTool == EditorTool.BRUSH) initial.getString(R.string.ui_eraser_e) else
            initial.getString(R.string.ui_tool_shortcut, initial.toolTitle(selectedTool), selectedTool.key))
        if (selectedTool == EditorTool.BRUSH) click(initial.getString(R.string.ui_brush))
        val expectedMode = if (selectedTool == EditorTool.HEALING) CanvasToolMode.HEALING else CanvasToolMode.BRUSH
        fun verifyState(language: AppLanguage, name: String) {
            val activity = ready(expected = language)
            val retained = checkNotNull(activity.retainedEditorForTest())
            retained.commandDispatcher.awaitIdleForTest().get(20, TimeUnit.SECONDS)
            check(retained === editor) { "Language switch replaced the native editor" }
            check(retained.state() == baseline) { "Language switch changed document state, history or layer names" }
            check(EditorTool.entries.map { it.name to it.key } == toolKeys) { "Language switch changed tool serialization/shortcut keys" }
            var canvas: CanvasView? = null
            instrumentation.runOnMainSync { canvas = findCanvas(activity.window.decorView) }
            waitUntil("Language recreation did not retain $selectedTool tool state") {
                var brushReady = false
                instrumentation.runOnMainSync {
                    canvas = findCanvas(activity.window.decorView)
                    brushReady = canvas?.toolMode == expectedMode &&
                        (selectedTool != EditorTool.BRUSH || canvas?.erasing == false)
                }
                brushReady
            }
            if (has(activity.getString(R.string.language_settings))) click(activity.getString(R.string.ui_file))
            val headerResource = if (selectedTool == EditorTool.HEALING) R.string.ui_healing_content_aware else R.string.ui_size
            waitUntil("Localized tool header was not exposed") { has(activity.getString(headerResource)) }
            if (language == AppLanguage.SIMPLIFIED_CHINESE) {
                check(activity.getString(R.string.ui_file) == "文件")
                check(activity.getString(R.string.ui_brush) == "画笔")
                check(activity.getString(R.string.ui_size) == "大小")
                check(has("文件") && has(activity.getString(headerResource))) { "Chinese resources were not present in actual UI" }
                check(activity.getString(R.string.ui_selection_amount_range, 250) == "请输入 1 至 250 像素的整数。")
                check(activity.getString(R.string.ui_brush_summary, 40, 80) == "大小 40 像素 · 硬度 80%")
            }
            screenshot("$name-tools")
            openLanguage()
            waitUntil("Language dialog did not expose all three language choices: $language") {
                has(activity.getString(R.string.language_system)) &&
                    has(activity.getString(R.string.language_english)) &&
                    has(activity.getString(R.string.language_simplified_chinese))
            }
            screenshot("$name-language")
            click(activity.getString(R.string.ui_close))
            observations.put(JSONObject().put("language", language.name)
                .put("resourceLocale", activity.resources.configuration.locales.toLanguageTags())
                .put("activityIdentity", System.identityHashCode(activity)).put("editorIdentity", System.identityHashCode(retained))
                .put("revision", baseline.revision).put("layers", baseline.layers.size)
                .put("tool", selectedTool.name).put("shortcut", selectedTool.key)
                .put("statePreserved", true))
        }
        select(AppLanguage.ENGLISH); verifyState(AppLanguage.ENGLISH, "english")
        select(AppLanguage.SIMPLIFIED_CHINESE); verifyState(AppLanguage.SIMPLIFIED_CHINESE, "simplified-chinese")
        select(AppLanguage.SYSTEM); verifyState(AppLanguage.SYSTEM, "system")
        // Keep a real, already displayed command error open while an external per-app locale
        // change recreates the Activity. This exercises saved notice identity, not a formatter alone.
        select(AppLanguage.ENGLISH)
        val errorActivity = ready(expected = AppLanguage.ENGLISH)
        val diagnostic = "Localization retained command fault ${System.nanoTime()}"
        val englishError = errorActivity.getString(retainedNoticeKey.resource)
        if (validationNotice) {
            if (!has(errorActivity.getString(R.string.ui_document))) click(errorActivity.getString(R.string.ui_file))
            click(errorActivity.getString(R.string.ui_document))
            val label = errorActivity.getString(R.string.ui_document_resolution)
            var field: AccessibilityNodeInfo? = null
            waitUntil("Document resolution input did not open") {
                automation.clearCache()
                var node = find(automation.rootInActiveWindow, label)
                while (node != null && node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) node = node.parent
                field = node?.takeIf { it.isEditable && it.isEnabled && it.isVisibleToUser }
                field != null
            }
            check(checkNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0")
            }))
            instrumentation.waitForIdleSync()
            click(errorActivity.getString(R.string.ui_apply_resolution))
            testErrorPending = true
        } else instrumentation.runOnMainSync {
            val canvas = checkNotNull(findCanvas(errorActivity.window.decorView))
            check(canvas.command { error(diagnostic) }) { "Localization error command was not admitted" }
            testErrorPending = true
        }
        editor.commandDispatcher.awaitIdleForTest().get(20, TimeUnit.SECONDS)
        waitUntil("English command failure was not displayed") { has(englishError) }
        check(editor.state() == baseline) { "Fault-only command changed document state or history" }
        if (!validationNotice) check(editor.commandDispatcher.latestCompletion?.error == diagnostic) { "Original fault diagnostic was lost" }
        check(!has(diagnostic)) { "Technical diagnostic leaked into localized presentation" }
        screenshot("english-command-error")
        instrumentation.runOnMainSync { AppLanguages.set(errorActivity, AppLanguage.SIMPLIFIED_CHINESE) }
        val translatedActivity = ready(errorActivity, AppLanguage.SIMPLIFIED_CHINESE, requireRecreation = true,
            allowErrorDialog = true)
        val chineseError = translatedActivity.getString(retainedNoticeKey.resource)
        check(englishError != chineseError)
        waitUntil("Retained error did not switch to the new locale") { has(chineseError) && !has(englishError) }
        val translatedEditor = checkNotNull(translatedActivity.retainedEditorForTest())
        translatedEditor.commandDispatcher.awaitIdleForTest().get(20, TimeUnit.SECONDS)
        check(translatedEditor === editor) { "Error locale switch replaced the native editor" }
        check(translatedEditor.state() == baseline) { "Error locale switch changed document state or history" }
        if (!validationNotice) check(translatedEditor.commandDispatcher.latestCompletion?.error == diagnostic) {
            "Error locale switch discarded the original technical diagnostic"
        }
        check(!has(diagnostic)) { "Technical diagnostic leaked after the locale switch" }
        screenshot("simplified-chinese-command-error")
        click(translatedActivity.getString(R.string.ui_ok))
        testErrorPending = false
        ready(expected = AppLanguage.SIMPLIFIED_CHINESE)
        waitUntil("Localized error dialog did not dismiss normally") { !has(chineseError) }
        check(editor.state() == baseline)
        report.put("retainedErrorLocaleSwitch", JSONObject().put("passed", true).put("noticeKey", retainedNoticeKey.name)
            .put("diagnostic", if (validationNotice) JSONObject.NULL else diagnostic)
            .put("diagnosticPreserved", !validationNotice).put("actualInvalidResolutionControl", validationNotice).put("statePreserved", true)
            .put("editorIdentityPreserved", true).put("activityRecreated", translatedActivity !== errorActivity)
            .put("oldLanguageAbsent", true).put("dismissedByOk", true))
        select(AppLanguage.SYSTEM)
        ready(expected = AppLanguage.SYSTEM)
        report.put("passed", true)
    } catch (error: Throwable) {
        failure = error
        report.put("passed", false).put("error", error.stackTraceToString())
        runCatching { dump(); screenshot("failure") }
    } finally {
        try {
            original?.let { desired ->
                // The language dialog uses rememberSaveable, so recreation alone preserves
                // its open state. Close only the recognizable three-choice language dialog.
                runCatching {
                    val catalogs = listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)
                    val dialogLanguage = catalogs.firstOrNull { language ->
                        has(catalogString(language, R.string.language_system)) &&
                            has(catalogString(language, R.string.language_english)) &&
                            has(catalogString(language, R.string.language_simplified_chinese)) &&
                            has(catalogString(language, R.string.ui_close))
                    }
                    if (dialogLanguage != null) {
                        click(catalogString(dialogLanguage, R.string.ui_close))
                        waitUntil("Test-owned language dialog did not close during cleanup") {
                            !has(catalogString(AppLanguage.ENGLISH, R.string.language_english)) ||
                                !has(catalogString(AppLanguage.SIMPLIFIED_CHINESE, R.string.language_simplified_chinese))
                        }
                        report.put("languageDialogDismissedDuringCleanup", true)
                    }
                }.onFailure { report.put("languageDialogDismissalFailure", it.stackTraceToString()) }
                // Only dismiss the error produced by this test. Restore the preference below
                // even if this UI cleanup fails, so a test failure cannot leave a changed locale.
                if (testErrorPending) runCatching {
                    val choices = listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE).map { language ->
                        catalogString(language, retainedNoticeKey.resource) to
                            catalogString(language, R.string.ui_ok)
                    }
                    waitUntil("Test-owned localized error could not be dismissed during cleanup") {
                        val choice = choices.firstOrNull { has(it.first) && has(it.second) }
                        if (choice == null) false else { click(choice.second); true }
                    }
                    testErrorPending = false
                }.onFailure { report.put("ownedErrorDismissalFailure", it.stackTraceToString()) }
                // Cleanup must restore the persistent preference even when a failed assertion
                // leaves a dialog holding window focus. Keep the exact pre-existing API 33 tags.
                var previous: MainActivity? = null
                instrumentation.runOnMainSync {
                    val current = resumed.get()?.takeUnless { it.isDestroyed || it.isFinishing }
                    previous = current
                    val context = current ?: instrumentation.targetContext
                    val changed = if (Build.VERSION.SDK_INT >= 33) {
                        val manager = context.getSystemService(LocaleManager::class.java)
                        val target = checkNotNull(originalTags)
                        val different = manager.applicationLocales.toLanguageTags() != target
                        manager.applicationLocales = LocaleList.forLanguageTags(target)
                        different
                    } else {
                        val different = AppLanguages.current(context) != desired
                        AppLanguages.set(context, desired)
                        different
                    }
                    // A changed locale recreates the Activity through the shipping path. If it
                    // was already restored, also verify a clean replacement after dialog cleanup.
                    if (!changed) current?.recreate()
                }
                val actualTags = if (Build.VERSION.SDK_INT >= 33) instrumentation.targetContext
                    .getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else null
                check(AppLanguages.current(instrumentation.targetContext) == desired &&
                    (originalTags == null || actualTags == originalTags)) {
                    "Original app language was not restored"
                }
                report.put("originalLanguageRestored", true).put("restoredLocaleTags", actualTags)
                ready(previous = previous, expected = desired, requireRecreation = previous != null)
            }
        } catch (error: Throwable) {
            restorationFailure = error
            val actualTags = if (Build.VERSION.SDK_INT >= 33) instrumentation.targetContext
                .getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else null
            report.put("originalLanguageRestored", original?.let { desired ->
                AppLanguages.current(instrumentation.targetContext) == desired &&
                    (originalTags == null || actualTags == originalTags)
            } ?: true).put("restoredLocaleTags", actualTags).put("restorationError", error.stackTraceToString())
        } finally {
            report.put("observations", observations).put("activityResumeEvents", resumeCount.get())
            File(evidence, "result.json").writeText(report.toString(2))
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
    failure?.let { throw it }
    restorationFailure?.let { throw it }
    return "localizedUiLanguages=3 documentStatePreserved=true editorIdentityPreserved=true originalLanguageRestored=true evidence=${evidence.absolutePath}"
}
