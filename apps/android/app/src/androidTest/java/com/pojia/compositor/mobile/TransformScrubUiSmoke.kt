package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public label gestures, independently observed through the retained editor and real GLES frames. */
internal fun phoneScrubTransactionAndFocus(instrumentation: Instrumentation): String {
    MoveInspectorFixtureSmoke(instrumentation, "transform-scrub-phone").use { fixture -> with(fixture) {
        check(activity.resources.configuration.screenWidthDp < 700)
        val input = TransformScrubUiDriver(fixture)
        val original = Transform64(50.0, 60.0, 80.0, 60.0)
        try {
            install(box = original)
            val before = editor.state(); val originalPixels = pixels(); val originalSource = source()
            input.down("X")
            input.up()
            editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && editor.activeTransformCompletion() == null && !canvas.moveInProgress)
            input.down("X")
            input.move(0.0, 12.0)
            input.up()
            editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && editor.activeTransformCompletion() == null && !canvas.moveInProgress) {
                "A tap or vertical label gesture began an edit"
            }
            observation("tap-and-vertical-no-edit", JSONObject().put("revision", before.revision))

            input.down("X")
            input.move(8.0)
            preview(original.copy(x = 58.0))
            val owner = checkNotNull(editor.activeTransformCompletion())
            input.move(24.0)
            val moved = original.copy(x = 74.0)
            preview(moved)
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && editor.activeTransformCompletion() === owner && !owner.isDone)
            check(canvas.moveFromFields && !canvas.movePersistent && source() == originalSource)
            input.assertLabelDidNotScroll()
            input.screenshot("held-touch-preview", "X", 74.0, moved)
            input.up(); settled()
            check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
            check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == moved)
            val movedPixels = pixels()
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == before.layers && pixels().contentEquals(originalPixels))
            click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers.single().transform == moved && pixels().contentEquals(movedPixels))
            check(source() == originalSource)
            observation("touch-total24-one-history", JSONObject().put("beforeRevision", before.revision)
                .put("afterRevisionBeforeUndo", before.revision + 1).put("expected", moved.toString()))

            val cancelBefore = editor.state(); val cancelPixels = pixels()
            input.down("X"); input.move(11.0)
            preview(moved.copy(x = 85.0))
            val cancelled = checkNotNull(editor.activeTransformCompletion())
            input.cancel(); settled()
            check(cancelled.get(10, TimeUnit.SECONDS).let { it.cancelled && !it.committed && it.error == null })
            check(editor.state().copy(selectedLayerActions = null) == cancelBefore.copy(selectedLayerActions = null) && pixels().contentEquals(cancelPixels) && source() == originalSource)
            observation("touch-cancel-restores-own-edit", JSONObject().put("revision", cancelBefore.revision))

            val handoffBefore = editor.state(); val handoffPixels = pixels()
            input.type("X", "42")
            val typed = moved.copy(x = 42.0)
            preview(typed)
            val typedOwner = checkNotNull(editor.activeTransformCompletion())
            val typedPixels = pixels()
            check(editor.state().copy(selectedLayerActions = null) == handoffBefore.copy(selectedLayerActions = null) && !typedOwner.isDone)
            val beforeHandoffBounds = stableBounds()
            val beforeHandoffOrigin = IntArray(2)
            var beforeHandoffOverlay: MoveHandleOverlay? = null
            instrumentation.runOnMainSync {
                canvas.getLocationOnScreen(beforeHandoffOrigin)
                beforeHandoffOverlay = canvas.moveOverlay(typed)
            }
            input.down("X"); input.move(5.0)
            preview(typed.copy(x = 47.0))
            val scrubOwner = checkNotNull(editor.activeTransformCompletion())
            check(scrubOwner !== typedOwner && !scrubOwner.isDone &&
                editor.state().revision == handoffBefore.revision + 1 &&
                editor.state().layers.single().transform == typed)
            instrumentation.runOnMainSync {
                val afterOrigin = IntArray(2).also(canvas::getLocationOnScreen)
                val heightChanged = canvas.height != beforeHandoffBounds.height()
                observation("typed-label-scrub-height-handoff", JSONObject().put("heightChanged", heightChanged)
                    .put("beforeHeight", beforeHandoffBounds.height()).put("afterHeight", canvas.height)
                    .put("originUnchanged", afterOrigin.contentEquals(beforeHandoffOrigin))
                    .put("ownerRetained", editor.activeTransformCompletion() === scrubOwner)
                    .put("intermediateBox", canvas.moveBox.toString()))
                if (heightChanged && canvas.width == beforeHandoffBounds.width() &&
                    afterOrigin.contentEquals(beforeHandoffOrigin)) {
                    check(canvas.moveOverlay(typed) == beforeHandoffOverlay) {
                        "IME height change moved the active label scrub's coordinate mapping"
                    }
                }
            }
            input.move(10.0)
            val afterHandoff = typed.copy(x = 52.0)
            preview(afterHandoff)
            check(editor.activeTransformCompletion() === scrubOwner && !scrubOwner.isDone)
            check(editor.state().revision == handoffBefore.revision + 1 &&
                editor.state().layers.single().transform == typed) {
                "Focused typing did not finish before the label scrub preview"
            }
            input.up(); settled()
            check(typedOwner.get(10, TimeUnit.SECONDS).let { it.committed && it.error == null })
            check(editor.state().revision == handoffBefore.revision + 2 &&
                editor.state().layers.single().transform == afterHandoff && source() == originalSource)
            val handoffAfterPixels = pixels()
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers.single().transform == typed && pixels().contentEquals(typedPixels))
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == handoffBefore.layers && pixels().contentEquals(handoffPixels))
            click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers.single().transform == afterHandoff && pixels().contentEquals(handoffAfterPixels))
            observation("focused-typing-then-scrub-two-history", JSONObject()
                .put("beforeRevision", handoffBefore.revision).put("afterRevisionBeforeUndo", handoffBefore.revision + 2)
                .put("typed", typed.toString()).put("scrubbed", afterHandoff.toString()))
            input.recordEvents()
            successful = true
            return "PASS: real touch label tap/vertical no-op, fixed total24dp preview with no publication before UP, one exact Undo/Redo segment, own-edit CANCEL, focused typing then queued scrub as two exact history segments, source identity and actual GLES screenshot."
        } catch (error: Throwable) {
            runCatching { input.recordEvents() }
            runCatching { input.recordFailureState() }
            input.releaseAfterFailure(); diagnose(error); throw error
        }
    } }
}

internal fun wideMouseScrubPersistentCancel(instrumentation: Instrumentation): String {
    MoveInspectorFixtureSmoke(instrumentation, "transform-scrub-wide").use { fixture -> with(fixture) {
        check(activity.resources.configuration.screenWidthDp >= 700)
        val input = TransformScrubUiDriver(fixture, mouse = true)
        val original = Transform64(20.0, 30.0, 80.0, 60.0, flipX = true)
        try {
            install(box = original)
            val before = editor.state(); val originalPixels = pixels(); val originalSource = source()
            fun persistentTyped() {
                key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
                waitUntil("Mouse scrub Ctrl+T did not start") { canvas.movePersistent && editor.hasActiveTransformDraft }
                input.type("X", "35")
                preview(original.copy(x = 35.0))
                key(KeyEvent.KEYCODE_ENTER); canvasFocused()
            }
            persistentTyped()
            val owner = checkNotNull(editor.activeTransformCompletion())
            val priorDraft = original.copy(x = 35.0)
            val priorPixels = pixels()
            input.down("Width"); input.move(8.0)
            preview(priorDraft.copy(width = 88.0, height = 66.0))
            input.move(16.0)
            val resized = priorDraft.copy(width = 96.0, height = 72.0)
            preview(resized)
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && editor.activeTransformCompletion() === owner && !owner.isDone)
            input.assertLabelDidNotScroll()
            input.cancel()
            preview(priorDraft)
            check(canvas.movePersistent && editor.activeTransformCompletion() === owner && !owner.isDone &&
                editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels().contentEquals(priorPixels) && source() == originalSource) {
                "Mouse scrub CANCEL discarded the earlier persistent draft or its owner"
            }
            observation("mouse-cancel-keeps-earlier-draft", JSONObject().put("restored", priorDraft.toString())
                .put("ownerRetained", true).put("revision", before.revision))

            input.revealNumericFieldBeforeGesture("Width")
            input.down("Width"); input.move(8.0)
            val intermediate = priorDraft.copy(width = 88.0, height = 66.0)
            preview(intermediate)
            check(canvas.movePersistent && editor.activeTransformCompletion() === owner && !owner.isDone &&
                editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && source() == originalSource) {
                "Second mouse scrub intermediate preview changed its persistent owner or committed state"
            }
            observation("mouse-second-total8-retains-persistent", JSONObject().put("expected", intermediate.toString())
                .put("revision", before.revision).put("ownerRetained", true))
            input.move(16.0)
            preview(resized)
            input.assertLabelDidNotScroll()
            input.screenshot("held-mouse-persistent-preview", "Width", 96.0, resized)
            input.up()
            preview(resized)
            check(canvas.movePersistent && editor.activeTransformCompletion() === owner && !owner.isDone && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
            click("Cancel Transform"); settled()
            check(owner.get(10, TimeUnit.SECONDS).let { it.cancelled && !it.committed && it.error == null })
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels().contentEquals(originalPixels) && source() == originalSource)
            observation("mouse-up-retains-then-transform-cancel", JSONObject().put("revision", before.revision))

            persistentTyped()
            val appliedOwner = checkNotNull(editor.activeTransformCompletion())
            input.down("Width"); input.move(8.0)
            preview(intermediate)
            check(canvas.movePersistent && editor.activeTransformCompletion() === appliedOwner && !appliedOwner.isDone &&
                editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && source() == originalSource) {
                "Third mouse scrub intermediate preview changed its persistent owner or committed state"
            }
            observation("mouse-third-total8-retains-persistent", JSONObject().put("expected", intermediate.toString())
                .put("revision", before.revision).put("ownerRetained", true))
            input.move(16.0); input.up()
            preview(resized)
            check(editor.activeTransformCompletion() === appliedOwner && !appliedOwner.isDone && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
            click("Apply Transform"); settled()
            check(appliedOwner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
            check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == resized)
            val afterPixels = pixels()
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == before.layers && pixels().contentEquals(originalPixels))
            click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers.single().transform == resized && pixels().contentEquals(afterPixels) && source() == originalSource)
            observation("mouse-total16-persistent-apply-one-history", JSONObject().put("beforeRevision", before.revision)
                .put("afterRevisionBeforeUndo", before.revision + 1).put("expected", resized.toString()))
            input.recordEvents()
            successful = true
            return "PASS: actual primary mouse label8dp intermediate preview in all three gestures and cumulative16dp proportional resize, CANCEL restores the full earlier Ctrl+T draft and owner, UP retains persistent preview, Transform Cancel restores original, Apply publishes one exact Undo/Redo segment; source identity and actual GLES screenshot."
        } catch (error: Throwable) {
            runCatching { input.recordEvents() }
            runCatching { input.recordFailureState() }
            input.releaseAfterFailure(); diagnose(error); throw error
        }
    } }
}

private class TransformScrubUiDriver(private val fixture: MoveInspectorFixtureSmoke, private val mouse: Boolean = false) {
    private val instrumentation = fixture.instrumentation
    private val automation = editorUiAutomation(instrumentation)
    private var downTime = 0L
    private var originX = 0f
    private var originY = 0f
    private var deltaX = 0f
    private var deltaY = 0f
    private var active = false
    private var label = ""
    private var initialBounds = Rect()
    private val events = JSONArray()

    private fun bounds(name: String): Rect {
        var found: Rect? = null
        fixture.waitUntil("Scrub public label unavailable: $name") {
            automation.clearCache()
            fixture.node(automation.rootInActiveWindow, name)?.takeIf { it.isEnabled }?.let { node ->
                Rect().also(node::getBoundsInScreen).takeIf { it.width() > 0 && it.height() > 0 }?.let { found = it }
            }
            found != null
        }
        return checkNotNull(found)
    }

    fun down(name: String) {
        check(!active)
        fixture.stableBounds()
        label = name; initialBounds = bounds("Scrub Move $name")
        originX = initialBounds.exactCenterX(); originY = initialBounds.exactCenterY()
        deltaX = 0f; deltaY = 0f; downTime = SystemClock.uptimeMillis(); active = true
        inject(MotionEvent.ACTION_DOWN)
    }

    fun move(totalDpX: Double, totalDpY: Double = 0.0) {
        check(active)
        val density = fixture.activity.resources.displayMetrics.density
        deltaX = (totalDpX * density).toFloat(); deltaY = (totalDpY * density).toFloat()
        inject(MotionEvent.ACTION_MOVE)
    }

    fun up() { check(active); inject(MotionEvent.ACTION_UP); active = false }
    fun cancel() { check(active); inject(MotionEvent.ACTION_CANCEL); active = false }
    fun releaseAfterFailure() { if (active) runCatching { cancel() } }
    fun assertLabelDidNotScroll() {
        check(bounds("Scrub Move $label") == initialBounds) { "The parameter row scrolled during a recognized label scrub" }
    }

    fun revealNumericFieldBeforeGesture(name: String) {
        check(!active) { "The parameter row must not scroll during a held scrub" }
        val before = fixture.editor.state()
        val owner = fixture.editor.activeTransformCompletion()
        val box = fixture.canvas.moveBox
        val labelBounds = bounds("Scrub Move $name")
        automation.clearCache()
        var row = fixture.node(automation.rootInActiveWindow, "Scrub Move $name")
        while (row != null && !row.isScrollable) row = row.parent
        val rowBounds = Rect().also(checkNotNull(row)::getBoundsInScreen)
        fun visibleField(): Boolean {
            automation.clearCache()
            var field = fixture.node(automation.rootInActiveWindow, "Move $name value")
            while (field != null && !field.isEditable) field = field.parent
            val fieldBounds = field?.let { Rect().also(it::getBoundsInScreen) }
            return field?.isEnabled == true && field.isVisibleToUser && fieldBounds != null &&
                rowBounds.contains(fieldBounds) && bounds("Scrub Move $name").let(rowBounds::contains)
        }
        if (!visibleField()) {
            // The first gesture above still exercises the original right-edge coordinates.
            // Scroll the row's top padding only after CANCEL, before the held-preview gesture.
            val y = rowBounds.top + 1f
            check(y < labelBounds.top && rowBounds.width() > 0)
            val startX = rowBounds.left + rowBounds.width() * .75f
            val endX = rowBounds.left + rowBounds.width() * .40f
            val down = SystemClock.uptimeMillis()
            val scrollEvents = JSONArray()
            fun pointer(action: Int, x: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    check(automation.injectInputEvent(event, true)) { "Public parameter-row scroll injection rejected" }
                    scrollEvents.put(JSONObject().put("action", action).put("screenX", x).put("screenY", y)
                        .put("eventTime", event.eventTime))
                } finally { event.recycle() }
            }
            pointer(MotionEvent.ACTION_DOWN, startX)
            try {
                for (step in 1..8) {
                    Thread.sleep(20)
                    pointer(MotionEvent.ACTION_MOVE, startX + (endX - startX) * step / 8)
                }
                // A stationary tail avoids a fling changing the next label's measured position.
                Thread.sleep(120)
                pointer(MotionEvent.ACTION_MOVE, endX)
                pointer(MotionEvent.ACTION_UP, endX)
            } catch (error: Throwable) {
                runCatching { pointer(MotionEvent.ACTION_CANCEL, endX) }
                throw error
            }
            instrumentation.waitForIdleSync()
            automation.waitForIdle(100, 5_000)
            fixture.waitUntil("Scrub label and public numeric field did not become visible together") { visibleField() }
            fixture.observation("public-parameter-row-scroll-between-gestures", JSONObject()
                .put("field", name).put("rowBounds", rowBounds.flattenToString())
                .put("labelBefore", labelBounds.flattenToString())
                .put("labelAfter", bounds("Scrub Move $name").flattenToString()).put("events", scrollEvents))
        }
        check(fixture.editor.state() == before && fixture.editor.activeTransformCompletion() === owner &&
            fixture.canvas.moveBox == box && owner?.isDone == false) {
            "Revealing the public numeric field changed the persistent draft, owner, or document"
        }
    }

    private fun inject(action: Int) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0; toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = originX + deltaX; y = originY + deltaY
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
            size = 1f
        }
        val buttons = if (mouse && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)
            MotionEvent.BUTTON_PRIMARY else 0
        val source = if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(properties), arrayOf(coordinates), 0, buttons, 1f, 1f, 0, 0, source, 0)
        try {
            check(automation.injectInputEvent(event, true)) { "Public scrub pointer injection rejected" }
            events.put(JSONObject().put("label", label).put("action", action).put("source", source)
                .put("toolType", properties.toolType).put("buttons", buttons)
                .put("totalDxPx", deltaX).put("totalDyPx", deltaY)
                .put("density", fixture.activity.resources.displayMetrics.density)
                .put("originScreenX", originX).put("originScreenY", originY)
                .put("screenX", event.rawX).put("screenY", event.rawY)
                .put("downTime", event.downTime).put("eventTime", event.eventTime)
                .put("labelBounds", initialBounds.flattenToString()))
        }
        finally { event.recycle() }
        instrumentation.waitForIdleSync()
        recordAfterEvent(action)
    }

    private fun recordAfterEvent(action: Int) {
        automation.clearCache()
        val root = automation.rootInActiveWindow
        val currentLabel = fixture.node(root, "Scrub Move $label")
        val currentBounds = currentLabel?.let { Rect().also(it::getBoundsInScreen) }
        val windowBounds = root?.let { Rect().also(it::getBoundsInScreen) }
        val details = JSONObject().put("action", action).put("label", label)
            .put("screenX", originX + deltaX).put("screenY", originY + deltaY)
            .put("initialLabelBounds", initialBounds.flattenToString())
            .put("currentLabelBounds", currentBounds?.flattenToString() ?: JSONObject.NULL)
            .put("labelEnabled", currentLabel?.isEnabled ?: JSONObject.NULL)
            .put("labelVisible", currentLabel?.isVisibleToUser ?: JSONObject.NULL)
            .put("windowBounds", windowBounds?.flattenToString() ?: JSONObject.NULL)
        instrumentation.runOnMainSync {
            val location = IntArray(2).also(fixture.canvas::getLocationOnScreen)
            details.put("windowFocused", fixture.activity.window.decorView.hasWindowFocus())
                .put("canvasLocationX", location[0]).put("canvasLocationY", location[1])
                .put("canvasWidth", fixture.canvas.width).put("canvasHeight", fixture.canvas.height)
                .put("moveBox", fixture.canvas.moveBox?.toString() ?: JSONObject.NULL)
                .put("moveInProgress", fixture.canvas.moveInProgress)
                .put("movePersistent", fixture.canvas.movePersistent)
                .put("moveSettling", fixture.canvas.moveSettling)
                .put("acceptedSequence", fixture.canvas.moveAcceptedSequence)
        }
        val owner = fixture.editor.activeTransformCompletion()
        details.put("revision", fixture.editor.state().revision)
            .put("ownerIdentity", owner?.let(System::identityHashCode) ?: JSONObject.NULL)
            .put("ownerDone", owner?.isDone ?: JSONObject.NULL)
        fixture.observation("public-label-event-state", details)
    }

    fun type(name: String, text: String) {
        fixture.click("Move $name value")
        var editable: AccessibilityNodeInfo? = null
        fixture.waitUntil("Scrub handoff field did not focus") {
            automation.clearCache()
            var current = fixture.node(automation.rootInActiveWindow, "Move $name value")
            while (current != null && (!current.isEditable || current.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }))
                current = current.parent
            editable = current?.takeIf { it.isFocused && it.isEnabled && it.isVisibleToUser }
            editable != null
        }
        check(checkNotNull(editable).text?.toString() != text)
        check(checkNotNull(editable).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        instrumentation.waitForIdleSync()
    }

    private fun displayed(name: String): Double? {
        automation.clearCache()
        var current = fixture.node(automation.rootInActiveWindow, "Move $name value")
        while (current != null && !current.isEditable) current = current.parent
        return current?.takeIf { it.isVisibleToUser && it.isEnabled }?.text?.toString()?.toDoubleOrNull()
    }

    private fun gpu(): GpuStats {
        val latch = CountDownLatch(1); var result: GpuStats? = null
        fixture.canvas.debugGpuStats { result = it; latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "Scrub screenshot GPU fence timed out" }
        return checkNotNull(result)
    }

    fun screenshot(stage: String, field: String, expectedValue: Double, expected: Transform64) {
        fixture.waitUntil("Scrub public numeric field did not update") { displayed(field) == expectedValue }
        fixture.preview(expected)
        val generation = fixture.editor.serialized {
            checkNotNull(fixture.editor.captureDisplayScene()).use { it.generation() }
        }
        val before = gpu(); fixture.canvas.requestRender()
        var rendered: GpuStats? = null
        fixture.waitUntil("Scrub target GLES generation did not complete") {
            val stats = gpu()
            if (stats.currentContext && stats.usesTiles && stats.sceneGeneration == generation &&
                stats.completedFrames > before.completedFrames) rendered = stats
            rendered != null
        }
        fixture.stableBounds(); instrumentation.waitForIdleSync(); automation.waitForIdle(100, 5_000)
        check(displayed(field) == expectedValue)
        fixture.preview(expected)
        val file = File(instrumentation.targetContext.filesDir, "transform-scrub-${fixture.label}-$stage-${System.nanoTime()}.png")
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        fixture.observation(stage, JSONObject().put("screenshot", file.name).put("field", field)
            .put("displayedValue", displayed(field)).put("box", expected.toString())
            .put("targetSceneGeneration", generation).put("gpuBefore", before.toString())
            .put("gpuRendered", checkNotNull(rendered).toString()).put("stableUiFrameIntervals", 2)
            .put("inputSource", if (mouse) "primary-mouse" else "touch"))
    }

    fun recordEvents() = fixture.observation("injected-public-label-events",
        JSONObject().put("events", JSONArray(events.toString())))

    fun recordFailureState() {
        val committed = fixture.editor.state()
        val snapshot = JSONObject().put("committedState", committed.toString()).put("revision", committed.revision)
        instrumentation.runOnMainSync {
            snapshot.put("moveBox", fixture.canvas.moveBox?.toString() ?: JSONObject.NULL)
                .put("moveInProgress", fixture.canvas.moveInProgress)
                .put("movePersistent", fixture.canvas.movePersistent)
                .put("moveSettling", fixture.canvas.moveSettling)
                .put("activeCompletionIsDone", fixture.editor.activeTransformCompletion()?.isDone ?: JSONObject.NULL)
        }
        fixture.observation("failure-before-pointer-cleanup", snapshot)
    }
}
