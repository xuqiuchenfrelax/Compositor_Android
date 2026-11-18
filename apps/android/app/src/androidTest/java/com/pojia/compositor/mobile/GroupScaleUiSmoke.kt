package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual public Scale text, Return/Apply/Cancel and Activity recreation on both layouts. */
internal fun groupScaleUiSmoke(instrumentation: Instrumentation, wide: Boolean): String {
    val label = if (wide) "group-scale-wide" else "group-scale-phone"
    MoveInspectorFixtureSmoke(instrumentation, label).use { fixture -> with(fixture) {
        check((activity.resources.configuration.screenWidthDp >= 700) == wide)
        var ids: GroupScaleFixture? = null
        fun install() {
            installAuthority {
                NativeEditor(256, false).also { ids = installGroupScaleFixture(it, grouped = !wide) }
            }
        }
        val original = Transform64(20.0, 20.0, 56.0, 36.0)
        val half = Transform64(34.0, 29.0, 28.0, 18.0)
        val a = Transform64(20.0, 20.0, 16.0, 16.0)
        val b = Transform64(60.0, 40.0, 16.0, 16.0, flipX = true)
        val halfA = Transform64(34.0, 29.0, 8.0, 8.0)
        val halfB = Transform64(54.0, 39.0, 8.0, 8.0, flipX = true)
        fun preview(box: Transform64, first: Transform64, second: Transform64) = waitUntil("Group Scale preview differs: $box") {
            canvas.moveBox == box && groupScalePreview(editor, checkNotNull(ids), first, second)
        }
        fun displayedFields(): JSONObject {
            val automation = editorUiAutomation(instrumentation)
            val names = listOf("X", "Y", "Width", "Height", "Scale", "Rotation")
            fun root(): AccessibilityNodeInfo? {
                automation.clearCache()
                return editorPhysicalDisplayRoot(instrumentation)
            }
            fun field(root: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
                var value = node(root, "Move $name value")
                // Compose can expose the label as a child of the editable text node.
                while (value != null && (!value.isEditable || value.actionList.none {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                })) value = value.parent
                return value?.takeIf { it.isVisibleToUser && it.isEnabled }
            }
            fun row(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (root == null) return null
                if (root.isScrollable && names.any { node(root, "Move $it value") != null }) return root
                for (index in 0 until root.childCount) row(root.getChild(index))?.let { return it }
                return null
            }
            fun fingerprint(root: AccessibilityNodeInfo?): String = names.mapNotNull { name ->
                node(root, "Move $name value")?.let { "$name:${Rect().also(it::getBoundsInScreen).toShortString()}" }
            }.joinToString(";")
            fun visibleField(name: String): AccessibilityNodeInfo {
                field(root(), name)?.let { return it }
                for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    for (attempt in 0 until 12) {
                        val current = root()
                        field(current, name)?.let { return it }
                        val scroll = row(current) ?: break
                        if (scroll.actionList.none { it.id == action }) break
                        val before = fingerprint(current)
                        check(scroll.performAction(action)) { "Group Scale numeric row rejected its advertised scroll action" }
                        waitUntil("Group Scale numeric row did not scroll to $name") {
                            val next = root()
                            field(next, name) != null || fingerprint(next) != before ||
                                row(next)?.actionList?.none { it.id == action } == true
                        }
                        instrumentation.waitForIdleSync()
                        automation.waitForIdle(100, 5_000)
                    }
                }
                return checkNotNull(field(root(), name)) { "Group Scale public field missing after scrolling: $name" }
            }
            val before = editor.state()
            val owner = editor.activeTransformCompletion()
            val box = canvas.moveBox
            val fields = JSONObject()
            // A wide window can still need horizontal scrolling. Read every asserted
            // public field without focusing or committing it, ending with Scale visible.
            for (name in if (wide) listOf("X", "Y", "Width", "Height", "Scale") else listOf("Scale")) {
                fields.put(name, visibleField(name).text?.toString())
            }
            check(editor.state() == before && editor.activeTransformCompletion() === owner && canvas.moveBox == box) {
                "Reading the public fields through horizontal scrolling changed the retained Move"
            }
            return fields
        }
        fun fieldsMatch(fields: JSONObject): Boolean = fields.optString("Scale").toDoubleOrNull() == 50.0 &&
            (!wide || listOf("X" to 34.0, "Y" to 29.0, "Width" to 28.0, "Height" to 18.0).all {
                fields.optString(it.first).toDoubleOrNull() == it.second
            })
        fun gpuStats(): GpuStats {
            val latch = CountDownLatch(1); var result: GpuStats? = null
            canvas.debugGpuStats { result = it; latch.countDown() }
            check(latch.await(10, TimeUnit.SECONDS)) { "Group Scale GPU stats did not fence" }
            return checkNotNull(result)
        }
        fun screenshot(stage: String) {
            // Model readiness alone does not establish Compose or Surface presentation readiness.
            waitUntil("Group Scale public fields did not display the half-size preview") { fieldsMatch(displayedFields()) }
            preview(half, halfA, halfB)
            val generation = editor.serialized { checkNotNull(editor.captureDisplayScene()).use { it.generation() } }
            val beforeFrame = gpuStats()
            canvas.requestRender()
            var rendered: GpuStats? = null
            waitUntil("Group Scale target GLES generation did not complete") {
                val current = gpuStats()
                if (current.currentContext && current.usesTiles && current.sceneGeneration == generation &&
                    current.completedFrames > beforeFrame.completedFrames) rendered = current
                rendered != null
            }
            // At least two stable UI frame intervals follow the completed GLES draw.
            stableBounds()
            instrumentation.waitForIdleSync()
            editorUiAutomation(instrumentation).waitForIdle(100, 5_000)
            val fields = displayedFields()
            check(fieldsMatch(fields)) { "Group Scale public fields changed before screenshot: $fields" }
            preview(half, halfA, halfB)
            val file = File(instrumentation.targetContext.filesDir, "p42-$label-$stage-${System.nanoTime()}.png")
            val bitmap = checkNotNull(editorUiAutomation(instrumentation).takeScreenshot())
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            observation(stage, JSONObject().put("screenshot", file.name).put("scalePercent", canvas.moveScalePercent)
                .put("grouped", !wide).put("box", canvas.moveBox?.toString()).put("displayedFields", fields)
                .put("fieldReadPolicy", "Visible public controls read through horizontal scrolling; Scale visible at capture")
                .put("targetSceneGeneration", generation).put("gpuBefore", beforeFrame.toString())
                .put("gpuRendered", checkNotNull(rendered).toString()).put("stableUiFrameIntervals", 2))
        }
        try {
            install()
            var before = editor.state()
            val originalPixels = pixels()
            var source = groupScaleSource(editor, checkNotNull(ids))
            check(focusScale().toDouble() == 100.0)
            key(KeyEvent.KEYCODE_ENTER); canvasFocused(); settled()
            check(editor.state() == before && pixels().contentEquals(originalPixels)) {
                "Untouched group100 field changed the document"
            }
            scaleText("50")
            preview(half, halfA, halfB)
            val owner = checkNotNull(editor.activeTransformCompletion())
            check(canvas.moveFromFields && !canvas.movePersistent && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
            check(groupScaleSource(editor, checkNotNull(ids)) == source)
            // The retained basis survives a real Activity replacement, not merely a numeric helper call.
            recreateGroup(half, owner)
            check(canvas.moveScalePercent == 50.0 && focusScale().toDouble() == 50.0)
            scaleText("100", focus = false)
            preview(original, a, b)
            check(editor.activeTransformCompletion() === owner && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && !owner.isDone)
            scaleText("50", focus = false)
            preview(half, halfA, halfB)
            screenshot("ordinary-half-after-recreate")
            key(KeyEvent.KEYCODE_ENTER); canvasFocused(); settled()
            check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
            val after = editor.state(); val halfPixels = pixels()
            check(after.revision == before.revision + 1 && groupScaleSource(editor, checkNotNull(ids)) == source)
            check(focusScale().toDouble() == 100.0) { "Committed group retained the previous edit's50% basis" }
            key(KeyEvent.KEYCODE_ENTER); canvasFocused(); settled()
            check(editor.state() == after)
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == before.layers && pixels().contentEquals(originalPixels))
            click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == after.layers && pixels().contentEquals(halfPixels))
            observation("ordinary-one-history-and-next100", JSONObject().put("beforeRevision", before.revision)
                .put("afterRevision", after.revision).put("sourcePayloadsStable", true))

            install(); before = editor.state(); source = groupScaleSource(editor, checkNotNull(ids))
            key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
            waitUntil("Group Ctrl+T did not start persistent editing") { canvas.movePersistent && canvas.moveBox == original }
            val persistent = checkNotNull(editor.activeTransformCompletion())
            scaleText("50"); preview(half, halfA, halfB)
            key(KeyEvent.KEYCODE_ENTER)
            check(canvas.movePersistent && !persistent.isDone && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null)) {
                "Return from a persistent group's numeric field committed it"
            }
            screenshot("persistent-half")
            click("Cancel Transform"); settled()
            check(persistent.get(10, TimeUnit.SECONDS).cancelled && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
            check(groupScaleSource(editor, checkNotNull(ids)) == source)
            key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
            waitUntil("Second group Ctrl+T did not start") { canvas.movePersistent }
            scaleText("50"); preview(half, halfA, halfB)
            click("Apply Transform"); settled()
            check(editor.state().revision == before.revision + 1)
            check(groupScaleSource(editor, checkNotNull(ids)) == source)
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == before.layers)
            successful = true
            return "PASS: $label actual group/multiselect Scale control, untouched100 no-op,50→100 original basis across Activity recreation, ordinary Return one undo/redo segment and new100 reset; Ctrl+T field Return retains, Cancel/Apply and source payload identity passed."
        } catch (error: Throwable) { diagnose(error); throw error }
    } }
}
