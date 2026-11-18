package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.TimeUnit

/** Exercises the crop header, custom ratio dialog, validation, and localized labels. */
internal fun cropRatioUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val previous = AppLanguages.current(context)
    val observations = mutableListOf<String>()
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            instrumentation.runOnMainSync { AppLanguages.set(context, language) }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
            try {
                fun root() = editorPhysicalDisplayRoot(instrumentation)
                fun waitFor(message: String, condition: () -> Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (System.nanoTime() < deadline) {
                        editorUiAutomation(instrumentation).clearCache()
                        if (condition()) return
                        Thread.sleep(30)
                    }
                    error(message)
                }
                fun node(label: String) = findEditorUiNode(root(), label)
                fun click(label: String) {
                    waitFor("Missing crop control: $label") { node(label) != null }
                    check(clickEditorUiNode(node(label))) { "Could not click $label" }
                    instrumentation.waitForIdleSync()
                }
                val cropTool = context.getString(R.string.ui_tool_shortcut, context.toolTitle(EditorTool.CROP), EditorTool.CROP.key)
                revealEditorControl(instrumentation, cropTool)
                click(cropTool)
                waitFor("Crop header did not load") { node(context.getString(R.string.ui_custom_ratio)) != null }
                click(context.getString(R.string.ui_custom_ratio))
                val ratioLabel = context.getString(R.string.ui_width_height_ratio)
                waitFor("Custom ratio dialog did not open") { findEditorEditableUiNode(root(), ratioLabel) != null }
                val field = checkNotNull(findEditorEditableUiNode(root(), ratioLabel))
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0:5")
                }))
                click(context.getString(R.string.ui_apply))
                waitFor("Invalid custom ratio was not rejected") { node(context.getString(R.string.ui_invalid_crop_ratio)) != null }
                check(findEditorEditableUiNode(root(), ratioLabel) != null)
                val valid = checkNotNull(findEditorEditableUiNode(root(), ratioLabel))
                check(valid.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "4:5")
                }))
                click(context.getString(R.string.ui_apply))
                waitFor("Valid custom ratio dialog did not close") { node(context.getString(R.string.ui_invalid_crop_ratio)) == null }
                observations += language.tag
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    } finally {
        instrumentation.runOnMainSync { AppLanguages.set(context, previous) }
    }
    return "uiCases=${observations.size} languages=${observations.joinToString(",")} validation=invalid-rejected valid-applied"
}
