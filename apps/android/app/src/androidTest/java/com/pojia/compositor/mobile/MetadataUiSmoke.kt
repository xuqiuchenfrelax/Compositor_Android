package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.TimeUnit

/** Records the actual settings UI; numerical correctness is exercised through the native ABI. */
internal fun metadataUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val documentLabel = context.getString(R.string.ui_document)
    val settingsLabel = context.getString(R.string.ui_document_settings)
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (activity.retainedEditorForTest() == null && System.nanoTime() < deadline) Thread.sleep(20)
        check(activity.retainedEditorForTest() != null)
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        instrumentation.waitForIdleSync()
        revealEditorControl(instrumentation, documentLabel)
        var button: AccessibilityNodeInfo? = null
        while (button == null && System.nanoTime() < deadline) {
            button = find(instrumentation.uiAutomation.rootInActiveWindow, documentLabel)
            if (button == null) Thread.sleep(20)
        }
        var actionable = checkNotNull(button) { "$settingsLabel button is missing" }
        while (!actionable.isClickable) actionable = checkNotNull(actionable.parent)
        check(actionable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        val dialogDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var dialog = find(instrumentation.uiAutomation.rootInActiveWindow, settingsLabel)
        while (dialog == null && System.nanoTime() < dialogDeadline) {
            Thread.sleep(20)
            dialog = find(instrumentation.uiAutomation.rootInActiveWindow, settingsLabel)
        }
        check(dialog != null) { "$settingsLabel dialog did not become accessible" }
        Thread.sleep(500)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(instrumentation.targetContext.cacheDir, "precision-document-phone.png")
        try { file.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return "Metadata UI opened actual Document settings; screenshot cache/${file.name}; full guide overlay/snapping not covered"
    } finally { instrumentation.runOnMainSync { activity.finish() } }
}
