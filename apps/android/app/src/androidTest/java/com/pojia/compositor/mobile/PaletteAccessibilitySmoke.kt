package com.pojia.compositor.mobile

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
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
import java.util.concurrent.atomic.AtomicReference

/** Real Android Resources select each independent count, including mixed singular/plural clauses. */
internal fun pluralQuantitySmoke(instrumentation: Instrumentation): String {
    val base = instrumentation.targetContext
    val english = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags("en"))
    })
    val chinese = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags("zh-CN"))
    })
    var cases = 0
    for (total in listOf(0, 1, 2, 1000)) {
        val copied = if (total == 0) 0 else 1
        val notice = UiNotice(UiNoticeKey.COPYING_PROJECT_PROGRESS, copied, total)
        check(notice.text(english) == "Copying project: $copied / $total ${if (total == 1) "asset" else "assets"}")
        check(notice.text(chinese) == "正在复制工程：$copied / $total 个资源")
        ++cases
    }
    for (cleaned in listOf(0, 1, 2)) for (review in listOf(0, 1, 2)) for (limited in listOf(false, true)) {
        val notice = UiNotice(if (limited) UiNoticeKey.EXPORT_RECOVERY_LIMITED else UiNoticeKey.EXPORT_RECOVERY,
            cleaned, review)
        val copies = if (cleaned == 1) "copy" else "copies"
        val records = if (review == 1) "record needs" else "records need"
        val englishSuffix = if (limited) " Some records could not be read safely. External files were kept."
            else " Open Export records to review retained copies."
        val chineseSuffix = if (limited) "部分记录无法安全读取，外部文件已保留。" else "请打开导出记录检查保留的副本。"
        check(notice.text(english) == "Export recovery: $cleaned interrupted $copies cleaned up; $review $records review.$englishSuffix")
        check(notice.text(chinese) == "导出恢复：已清理 $cleaned 个中断副本，$review 条记录需要检查。$chineseSuffix")
        ++cases
    }
    return "actualAndroidPluralCases=$cases languages=en,zh-CN independentCounts=true globalLocaleChanged=false"
}

/** Operates the shipping picker through Android accessibility; cancels every edit to preserve the palette/document. */
internal fun paletteAccessibilitySmoke(instrumentation: Instrumentation): String {
    val automation = instrumentation.uiAutomation
    val application = instrumentation.targetContext.applicationContext as Application
    val resumed = AtomicReference<MainActivity?>()
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) resumed.set(activity) }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    val evidence = File(instrumentation.targetContext.cacheDir, "palette-accessibility-${System.nanoTime()}")
        .apply { check(mkdir()) }
    val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("globalLocaleChanged", false)
    val observations = JSONArray()
    var original: AppLanguage? = null
    var originalTags: String? = null
    var originalForeground: Int? = null
    var pickerOpen = false
    var failure: Throwable? = null
    var restorationFailure: Throwable? = null
    application.registerActivityLifecycleCallbacks(callbacks)
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache() else automation.rootInActiveWindow?.refresh()
        return automation.rootInActiveWindow
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
                .append(" description=").append(node.contentDescription).append(" state=").append(node.stateDescription)
                .append(" actions=").append(node.actionList.joinToString { "${it.id}:${it.label}" }).append('\n')
            for (index in 0 until node.childCount) append(node.getChild(index), depth + 1)
        }
        append(root(), 0); File(evidence, "failure.txt").writeText(text.toString())
    }
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(25); instrumentation.waitForIdleSync()
        }
        dump(); error(message)
    }
    fun canvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) canvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun ready(expected: AppLanguage? = null, previous: MainActivity? = null, requireRecreation: Boolean = false): MainActivity {
        var accepted: MainActivity? = null
        waitUntil("Palette activity/canvas not ready for $expected") {
            val activity = resumed.get() ?: return@waitUntil false
            var valid = false
            main { valid = !activity.isDestroyed && !activity.isFinishing &&
                activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                activity.retainedEditorForTest() != null && canvas(activity.window.decorView)?.isShown == true &&
                (expected == null || AppLanguages.current(activity) == expected) &&
                (!requireRecreation || activity !== previous) }
            if (valid) accepted = activity
            valid
        }
        return checkNotNull(accepted)
    }
    fun foreground(activity: MainActivity): Int {
        var value = 0
        main { value = checkNotNull(canvas(activity.window.decorView)).foregroundColor }
        return value
    }
    fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) editable(node.getChild(index))?.let { return it }
        return null
    }
    fun reveal(label: String) {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var lastScroll = 0L
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && node.className?.toString() != "android.widget.HorizontalScrollView" &&
                node.actionList.any { it.id == direction }) return node
            for (index in 0 until node.childCount) scrollable(node.getChild(index))?.let { return it }
            return null
        }
        waitUntil("Picker control is clipped: $label") {
            val tree = root()
            val node = find(tree, label)
            if (node?.isVisibleToUser == true) true else {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastScroll >= 150) {
                    val revealed = node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true
                    if (!revealed) {
                        var scroller = scrollable(tree)
                        if (scroller == null) {
                            direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            scroller = scrollable(tree)
                        }
                        scroller?.performAction(direction)
                    }
                    lastScroll = now
                }
                false
            }
        }
    }
    fun click(label: String) {
        // The phone tool rail scrolls; controls below the fold may be absent from its AX tree.
        reveal(label)
        var clicked = false
        waitUntil("Palette control unavailable: $label") {
            if (!clicked) {
                var node = find(root(), label)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isVisibleToUser == true && node.isEnabled)
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    fun hex(activity: MainActivity): String {
        reveal(activity.getString(R.string.ui_hex_color))
        var value: String? = null
        waitUntil("Picker HEX field unavailable") {
            val node = find(root(), activity.getString(R.string.ui_hex_color))
            val field = editable(node) ?: editable(node?.parent)
            value = field?.text?.toString()?.removePrefix("#")?.uppercase(java.util.Locale.ROOT)
                ?.takeIf { it.matches(Regex("[0-9A-F]{6}")) }
            value != null
        }
        return checkNotNull(value)
    }
    fun expectColor(activity: MainActivity, hsv: FloatArray, stage: String) {
        val expected = String.format(java.util.Locale.ROOT, "%06X", Color.HSVToColor(hsv) and 0xffffff)
        waitUntil("Accessibility HSV did not reach actual HEX: $stage expected=$expected") { hex(activity) == expected }
        reveal(activity.getString(R.string.ui_color_hue))
        waitUntil("Hue state/range does not match actual action: $stage") {
            val node = find(root(), activity.getString(R.string.ui_color_hue))
            node != null && node.stateDescription?.toString() == activity.getString(R.string.ui_color_hue_value, hsv[0]) &&
                node.rangeInfo?.let { kotlin.math.abs(it.current - hsv[0]) < .0001f } == true
        }
        observations.put(JSONObject().put("language", AppLanguages.current(activity).name)
            .put("stage", stage).put("hue", hsv[0].toDouble()).put("saturation", hsv[1].toDouble())
            .put("brightness", hsv[2].toDouble()).put("actualHex", expected))
    }
    fun custom(activity: MainActivity, nodeResource: Int, actionResource: Int, expectedChanged: Boolean) {
        reveal(activity.getString(nodeResource))
        waitUntil("Localized picker action missing: ${activity.getString(actionResource)}") {
            val node = find(root(), activity.getString(nodeResource)) ?: return@waitUntil false
            val action = node.actionList.firstOrNull { it.label?.toString() == activity.getString(actionResource) }
                ?: return@waitUntil false
            val accepted = node.performAction(action.id)
            check(accepted == expectedChanged) { "Wrong boundary result for ${activity.getString(actionResource)}" }
            true
        }
        instrumentation.waitForIdleSync()
    }
    try {
        report.put("plurals", pluralQuantitySmoke(instrumentation))
        val initial = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        resumed.compareAndSet(null, initial); ready()
        original = AppLanguages.current(initial)
        if (Build.VERSION.SDK_INT >= 33) originalTags = initial.getSystemService(LocaleManager::class.java)
            .applicationLocales.toLanguageTags()
        originalForeground = foreground(initial)
        val editor = checkNotNull(initial.retainedEditorForTest())
        editor.commandDispatcher.awaitIdleForTest().get(20, TimeUnit.SECONDS)
        val baseline = editor.state()
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            val previous = ready()
            val changed = AppLanguages.current(previous) != language
            main { AppLanguages.set(previous, language) }
            val activity = ready(language, previous, changed)
            check(activity.retainedEditorForTest() === editor && foreground(activity) == originalForeground)
            click(activity.getString(R.string.ui_foreground_color)); pickerOpen = true
            val initialHex = hex(activity)
            val hsv = FloatArray(3)
            Color.RGBToHSV(initialHex.substring(0, 2).toInt(16), initialHex.substring(2, 4).toInt(16),
                initialHex.substring(4, 6).toInt(16), hsv)
            val hueLabel = activity.getString(R.string.ui_color_hue)
            var hueNode: AccessibilityNodeInfo? = null
            reveal(hueLabel)
            waitUntil("Picker hue lacks range/SetProgress") {
                hueNode = find(root(), hueLabel)
                hueNode?.rangeInfo?.let { it.min == 0f && it.max == 360f } == true &&
                    hueNode?.actionList?.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id } == true
            }
            val invalid = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN) }
            check(!checkNotNull(hueNode).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, invalid))
            check(hex(activity) == initialHex)
            expectColor(activity, hsv, "reject-nonfinite-progress")
            val clamped = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 500f) }
            check(checkNotNull(find(root(), hueLabel)).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, clamped) == (hsv[0] != 360f))
            hsv[0] = 360f; expectColor(activity, hsv, "clamp-hue-360")
            custom(activity, R.string.ui_color_hue, R.string.ui_increase_hue, false)
            expectColor(activity, hsv, "reject-increase-at-360")
            val lower = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -500f) }
            check(checkNotNull(find(root(), hueLabel)).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, lower))
            hsv[0] = 0f; expectColor(activity, hsv, "clamp-hue-0")
            custom(activity, R.string.ui_color_hue, R.string.ui_decrease_hue, false)
            expectColor(activity, hsv, "reject-decrease-at-0")
            val progress = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 120f) }
            reveal(activity.getString(R.string.ui_color_hue))
            check(checkNotNull(find(root(), hueLabel)).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, progress) == (hsv[0] != 120f))
            hsv[0] = 120f; expectColor(activity, hsv, "set-hue-120")
            for ((resource, delta) in listOf(R.string.ui_increase_hue to 1f, R.string.ui_decrease_hue to -1f)) {
                custom(activity, R.string.ui_color_hue, resource, true)
                hsv[0] += delta; expectColor(activity, hsv, "hue-$delta")
            }
            for ((resource, axis, delta) in listOf(Triple(R.string.ui_increase_saturation, 1, .01f),
                Triple(R.string.ui_decrease_saturation, 1, -.01f), Triple(R.string.ui_increase_brightness, 2, .01f),
                Triple(R.string.ui_decrease_brightness, 2, -.01f))) {
                val next = (hsv[axis] + delta).coerceIn(0f, 1f)
                custom(activity, R.string.ui_color_saturation_and_brightness, resource, next != hsv[axis])
                hsv[axis] = next; expectColor(activity, hsv, "axis-$axis-delta-$delta")
                reveal(activity.getString(R.string.ui_color_saturation_and_brightness))
                val node = checkNotNull(find(root(), activity.getString(R.string.ui_color_saturation_and_brightness)))
                check(node.stateDescription?.toString() == activity.getString(R.string.ui_color_saturation_brightness_value,
                    hsv[1] * 100, hsv[2] * 100))
            }
            click(activity.getString(R.string.ui_cancel)); pickerOpen = false
            check(foreground(activity) == originalForeground) { "Canceled picker changed foreground" }
            editor.commandDispatcher.awaitIdleForTest().get(20, TimeUnit.SECONDS)
            check(editor.state() == baseline) { "Accessible palette changed document/history" }
        }
        report.put("passed", true)
    } catch (error: Throwable) {
        failure = error; report.put("passed", false).put("error", error.stackTraceToString()); runCatching { dump() }
    } finally {
        try {
            if (pickerOpen) { click(ready().getString(R.string.ui_cancel)); pickerOpen = false }
            check(originalForeground == null || foreground(ready()) == originalForeground) { "Foreground restoration failed" }
            report.put("foregroundRestored", true)
        } catch (error: Throwable) { restorationFailure = error; report.put("paletteRestorationError", error.stackTraceToString()) }
        try {
            original?.let { language ->
                val activity = ready()
                val changed = if (Build.VERSION.SDK_INT >= 33) activity.getSystemService(LocaleManager::class.java)
                    .applicationLocales.toLanguageTags() != originalTags else AppLanguages.current(activity) != language
                main {
                    if (Build.VERSION.SDK_INT >= 33) activity.getSystemService(LocaleManager::class.java).applicationLocales =
                        LocaleList.forLanguageTags(checkNotNull(originalTags))
                    else AppLanguages.set(activity, language)
                }
                ready(language, activity, changed)
                if (Build.VERSION.SDK_INT >= 33) check(ready().getSystemService(LocaleManager::class.java)
                    .applicationLocales.toLanguageTags() == originalTags)
                report.put("originalLanguageRestored", true)
            }
        } catch (error: Throwable) {
            restorationFailure?.addSuppressed(error) ?: run { restorationFailure = error }
            report.put("localeRestorationError", error.stackTraceToString())
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            if (restorationFailure != null) report.put("passed", false)
            report.put("observations", observations)
            File(evidence, "result.json").writeText(report.toString(2))
        }
    }
    failure?.let { restorationFailure?.let(it::addSuppressed); throw it }
    restorationFailure?.let { throw it }
    return "Actual localized Android HSV range/SetProgress/NaN refusal and six custom actions reached independent HSVToColor HEX values in en/zh-CN; canceled picker preserves exact foreground, editor and history. ${report.getString("plurals")} Evidence=${evidence.absolutePath}"
}
