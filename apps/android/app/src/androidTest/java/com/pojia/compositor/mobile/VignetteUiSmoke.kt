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

internal fun vignetteUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val languageBefore = AppLanguages.current(context)
    var count = 0
    try {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            instrumentation.runOnMainSync { AppLanguages.set(context, language) }
            val token = "p129-vignette-${System.nanoTime()}"
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
                waitFor("Vignette control not visible: $label") {
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
                waitFor("Vignette control unavailable: $label") { clickEditorUiNode(node(label)) }
                instrumentation.waitForIdleSync()
            }
            fun progress(resource: Int, value: Float) {
                val label = context.getString(resource)
                revealEditorControl(instrumentation, label)
                reveal(label)
                waitFor("Vignette slider unavailable: $label") { node(label)?.actionList?.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id } == true }
                check(node(label)!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
                }))
                instrumentation.waitForIdleSync()
            }
            try {
                waitFor("Vignette UI seed did not restore") { activity.retainedEditorForTest()?.state()?.layers?.size == 1 }
                val editor = checkNotNull(activity.retainedEditorForTest())
                val before = editor.state().layers; val beforePixels = vignettePixels(editor)
                revealInspectorPanel(instrumentation, InspectorPanel.ADJUSTMENTS)
                click(context.getString(R.string.ui_adjustment_vignette))
                progress(R.string.ui_vignette_amount, 75f)
                progress(R.string.ui_vignette_midpoint, 20f)
                progress(R.string.ui_vignette_roundness, -50f)
                progress(R.string.ui_vignette_feather, 30f)
                progress(R.string.ui_vignette_highlights, 80f)
                click(context.getString(R.string.ui_vignette_color))
                val hex = context.getString(R.string.ui_hex_color)
                revealEditorControl(instrumentation, hex)
                reveal(hex)
                waitFor("Color hex field unavailable") { findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), hex) != null }
                check(findEditorEditableUiNode(editorPhysicalDisplayRoot(instrumentation), hex)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "336699")
                }))
                click(context.getString(R.string.ui_ok))
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    instrumentation.runOnMainSync { activity.recreate() }
                    activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity)
                    instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                } finally { instrumentation.removeMonitor(monitor) }
                click(context.getString(R.string.ui_apply))
                waitFor("Vignette UI Apply did not create a layer") { editor.state().layers.size == 2 }
                val id = editor.state().layers.single { it.id !in before.map { it.id } }.id
                val committed = checkNotNull(editor.adjustmentStyleOrNull(id)).vignette
                check(committed.amount == 75.0 && committed.midpoint == 20.0 && committed.roundness == -50.0 &&
                    committed.feather == 30.0 && committed.highlights == 80.0)
                check(kotlin.math.abs(committed.color.red - .2) < 1e-5 && kotlin.math.abs(committed.color.green - .4) < 1e-5 && kotlin.math.abs(committed.color.blue - .6) < 1e-5)
                val changedPixels = vignettePixels(editor); check(!changedPixels.contentEquals(beforePixels))
                val beforeSampling = editor.state()
                NativeColorSampler.capture(context, editor).use { sampler ->
                    for (y in listOf(0, 7, 16, 31)) for (x in listOf(0, 9, 16, 31)) {
                        val offset = (y * 32 + x) * 4
                        check(changedPixels[offset + 3].toInt() and 255 == 255)
                        val expected = (255 shl 24) or ((changedPixels[offset].toInt() and 255) shl 16) or
                            ((changedPixels[offset + 1].toInt() and 255) shl 8) or (changedPixels[offset + 2].toInt() and 255)
                        check(sampler.sample(x.toDouble(), y.toDouble()) == expected) { "Vignette sampler lost full canvas coordinates: $x,$y" }
                    }
                }
                check(editor.state() == beforeSampling)
                click(context.getString(R.string.ui_undo)); waitFor("UI Undo did not restore vignette base") { editor.state().layers == before }
                click(context.getString(R.string.ui_redo)); waitFor("UI Redo did not restore vignette") { editor.state().layers.size == 2 }
                check(vignettePixels(editor).contentEquals(changedPixels))
                val committedLayers = editor.state().layers
                click(context.getString(R.string.ui_edit_selected_adjustment))
                progress(R.string.ui_vignette_amount, 10f)
                click(context.getString(R.string.ui_cancel))
                check(editor.state().layers == committedLayers && vignettePixels(editor).contentEquals(changedPixels))
                click(context.getString(R.string.ui_edit_selected_adjustment))
                revealEditorControl(instrumentation, context.getString(R.string.ui_vignette_midpoint))
                val image = checkNotNull(editorUiAutomation(instrumentation).takeScreenshot())
                try { File(context.cacheDir, "p129-vignette-${language.tag}.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
                click(context.getString(R.string.ui_cancel))
                typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId ->
                    check(restored.adjustmentStyleOrNull(restoredId)!!.vignette == committed)
                }
                val target = File(context.cacheDir, "p129-vignette-projects/ui-${language.tag}.comp")
                if (target.exists()) check(target.deleteRecursively())
                editor.captureProjectSnapshot().use { snapshot -> NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                    check(snapshot.write(context, target, false, control).successful)
                } }
                count++
            } catch (failure: Throwable) {
                val image = editorUiAutomation(instrumentation).takeScreenshot()
                if (image != null) try { File(context.cacheDir, "p129-ui-failure.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { image.recycle() }
                fun tree(value: AccessibilityNodeInfo?, depth: Int = 0): String {
                    if (value == null || depth > 40) return ""
                    return "${" ".repeat(depth)}${value.text} | ${value.contentDescription} visible=${value.isVisibleToUser} enabled=${value.isEnabled} scroll=${value.isScrollable} class=${value.className} actions=${value.actionList.map { it.id }}\n" +
                        (0 until value.childCount).joinToString("") { tree(value.getChild(it), depth + 1) }
                }
                File(context.cacheDir, "p129-ui-failure.txt").writeText("$failure\n" + tree(editorPhysicalDisplayRoot(instrumentation)))
                throw failure
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                workspace.closeAsync().get(15, TimeUnit.SECONDS)
                check(directory.deleteRecursively())
            }
        }
    } finally { instrumentation.runOnMainSync { AppLanguages.set(context, languageBefore) } }
    return "uiCases=$count languages=en,zh-CN actualSlidersAndColor=true canvasSampler=true draftRecreation=true undoRedo=true cancel=true isolatedWorkspace=true"
}
