package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Native terminals and held main delivery exercise the shipping context switch owner. */
private fun moveSwitchFoundationSmoke(instrumentation: Instrumentation) {
    val baseline = ImageIoAdmission.ledger.snapshot()
    val editor = NativeEditor(8, false)
    val callbacks = ConcurrentLinkedQueue<() -> Unit>()
    val control = TransformController(editor, instrumentation.targetContext, { callbacks.add(it) }, {})
    var pending = false
    val switches = MoveContextSwitch({ callbacks.add(it) }, { pending = it }, { throw it })
    var applications = 0
    fun waitTerminal() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while ((control.snapshot()?.phase != TransformController.Phase.TERMINAL_WAITING || callbacks.isEmpty()) && System.nanoTime() < deadline) Thread.sleep(5)
        check(control.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING && callbacks.isNotEmpty())
    }
    fun deliver() = instrumentation.runOnMainSync { while (true) (callbacks.poll() ?: break).invoke() }
    fun request(owner: TransformController.Owner, current: () -> Boolean = { true }) =
        switches.submit({ control.finish(owner) }, current) { applications++ }
    try {
        val id = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(-1, 0, 0, -1)); rewind() })
        val before = editor.state()
        val original = before.layers.single().transform
        val owner = editor.serialized {
            val value = checkNotNull(control.begin(id, TransformTarget.LAYER, 1, 1, 1))
            repeat(100) { check(control.update(value, original.copy(x = (it + 1) / 10.0))) }
            check(request(value))
            check(!request(value)) // The first requested context owns the one pending slot.
            value
        }
        waitTerminal()
        check(pending && applications == 0 && !owner.finished.isDone)
        check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform.x == 10.0)
        check(editor.tryRegisterPendingEdit() == null)
        instrumentation.runOnMainSync { checkNotNull(callbacks.poll()).invoke() }
        check(owner.finished.isDone && pending && applications == 0) { "Context changed before terminal main acknowledgment" }
        deliver()
        check(!pending && applications == 1 && editor.activeTransformCompletion() == null)
        editor.undo(false); check(editor.state().layers == before.layers)
        editor.undo(true); check(editor.state().layers.single().transform.x == 10.0)

        val cancelBefore = editor.state()
        val cancel = editor.serialized {
            val value = checkNotNull(control.begin(id, TransformTarget.LAYER, 1, 1, 2))
            check(control.update(value, original.copy(x = 20.0)))
            check(request(value)); control.cancel(value); value
        }
        waitTerminal(); deliver()
        check(cancel.finished.get().cancelled && editor.state().copy(selectedLayerActions = null) == cancelBefore.copy(selectedLayerActions = null) && applications == 1 && !pending)

        // A retained project snapshot has no independent claim and is rejected before draft allocation.
        editor.captureProjectSnapshot().use {
            val refused = checkNotNull(control.begin(id, TransformTarget.LAYER, 1, 1, 3))
            check(request(refused)); waitTerminal(); deliver()
            check(!refused.finished.get().committed && refused.finished.get().error != null)
        }
        check(editor.state().copy(selectedLayerActions = null) == cancelBefore.copy(selectedLayerActions = null) && applications == 1 && !pending)

        editor.committed = { error("Move switch observer fixture") }
        val confirmed = checkNotNull(control.begin(id, TransformTarget.LAYER, 1, 1, 4))
        check(control.update(confirmed, original.copy(x = 30.0)))
        check(request(confirmed)); waitTerminal(); deliver()
        val receipt = confirmed.finished.get()
        check(receipt.committed && receipt.error != null && applications == 1 && !pending)
        check(editor.state().revision == cancelBefore.revision + 1 && editor.state().layers.single().transform.x == 30.0)
        editor.committed = null
        val confirmedState = editor.state()
        check(switches.submit(null, { true }) { applications++ })
        check(applications == 2 && editor.state() == confirmedState) { "Context recovery replayed a confirmed commit" }

        val stale = checkNotNull(control.begin(id, TransformTarget.LAYER, 1, 1, 5))
        check(control.update(stale, original.copy(x = 40.0)))
        var current = true
        check(request(stale) { current }); waitTerminal(); current = false; deliver()
        check(applications == 2 && !pending && editor.activeTransformCompletion() == null)
        val retired = checkNotNull(control.begin(id, TransformTarget.LAYER, 1, 1, 6))
        check(control.update(retired, original.copy(x = 50.0)))
        check(request(retired)); waitTerminal(); switches.invalidate(); deliver()
        check(applications == 2 && !pending && editor.state().layers.single().transform.x == 50.0)
    } finally {
        editor.committed = null; switches.invalidate()
        control.snapshot()?.owner?.let { control.cancel(it); waitTerminal(); deliver() }
        control.close(); editor.close()
        check(ImageIoAdmission.ledger.snapshot().let { it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations })
    }
}

/** Real rail/layer/target accessibility actions settle shipping Canvas pointer and field input. */
internal fun moveSwitchSmoke(instrumentation: Instrumentation): String {
    moveSwitchFoundationSmoke(instrumentation)
    val automation = editorUiAutomation(instrumentation)
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun waitFor(message: String, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(15), condition: () -> Boolean) {
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }
    fun node(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
        for (i in 0 until root.childCount) node(root.getChild(i), label)?.let { return it }
        return null
    }
    var lastClick: JSONObject? = null
    fun actionAvailable(label: String): Boolean {
        automation.clearCache()
        var target = node(automation.rootInActiveWindow, label)
        while (target != null && !target.isClickable) target = target.parent
        return target?.isVisibleToUser == true && target.isEnabled &&
            target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }
    fun countDescription(root: AccessibilityNodeInfo?, label: String): Int {
        if (root == null) return 0
        return (if (root.contentDescription?.toString() == label) 1 else 0) +
            (0 until root.childCount).sumOf { countDescription(root.getChild(it), label) }
    }
    data class LayerBody(val owner: Rect, val list: Rect, val include: Rect, val visibility: Rect)
    val layerBodyReadiness = org.json.JSONArray()
    fun layerBody(label: String): LayerBody {
        val name = label.removePrefix("Select ")
        var accepted: LayerBody? = null
        var quietCandidate: LayerBody? = null
        var stableObservations = 0
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val observations = org.json.JSONArray()
        val quietWaits = org.json.JSONArray()
        layerBodyReadiness.put(JSONObject().put("label", label).put("observations", observations).put("quietWaits", quietWaits))
        var previousObservation: String? = null
        fun scrollQuiet(reason: String): Boolean {
            val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
            if (remaining <= 100) return false
            val observation = JSONObject().put("reason", reason).put("startedAt", SystemClock.uptimeMillis())
                .put("idleMillis", 100).put("timeoutMillis", remaining)
            quietWaits.put(observation)
            automation.waitForIdle(100, remaining)
            instrumentation.waitForIdleSync()
            observation.put("finishedAt", SystemClock.uptimeMillis()).put("idle", true)
            return true
        }
        waitFor("Move switch layer body unavailable: $label", deadline) {
            if (accepted != null) true else if (System.nanoTime() >= deadline) false else {
                automation.clearCache()
                val root = automation.rootInActiveWindow
                check(countDescription(root, label) <= 1) { "Move switch layer description is ambiguous: $label" }
                val description = node(root, label)
                val list = node(root, "Layer list")
                val canonical = Rect().also { description?.getBoundsInScreen(it) }
                val listBounds = Rect().also { list?.getBoundsInScreen(it) }
                val intersection = Rect(canonical)
                var owner = if (!canonical.isEmpty && intersection.intersect(listBounds)) description else null
                while (owner != null && owner.contentDescription?.toString() != "Layer list" &&
                    !(countDescription(owner, label) == 1 &&
                        countDescription(owner, "Include $name in selection") == 1 &&
                        countDescription(owner, "Visibility $name") == 1)) owner = owner.parent
                val row = owner?.takeIf { it.contentDescription?.toString() != "Layer list" }
                val bounds = Rect().also { row?.getBoundsInScreen(it) }
                val include = Rect().also { node(row, "Include $name in selection")?.getBoundsInScreen(it) }
                val visibility = Rect().also { node(row, "Visibility $name")?.getBoundsInScreen(it) }
                val x = bounds.centerX(); val y = bounds.centerY()
                val ready = description?.isVisibleToUser == true && row?.isVisibleToUser == true && row.isEnabled &&
                    row.isClickable && row.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                    !bounds.isEmpty && bounds.contains(canonical) && listBounds.contains(x, y) &&
                    !include.isEmpty && !visibility.isEmpty && !include.contains(x, y) && !visibility.contains(x, y)
                val observation = JSONObject().put("rowBounds", bounds.toShortString()).put("listBounds", listBounds.toShortString())
                    .put("includeBounds", include.toShortString()).put("visibilityBounds", visibility.toShortString())
                    .put("ready", ready).put("clickOwnerEnabled", row?.isEnabled == true)
                    .put("clickOwnerHasAction", row?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true)
                    .put("stableObservations", stableObservations)
                if (observation.toString() != previousObservation && observations.length() < 64) {
                    observations.put(observation); previousObservation = observation.toString()
                }
                if (ready) {
                    check(countDescription(row, "Select Switch first") + countDescription(row, "Select Switch second") == 1) {
                        "Move switch layer body spans multiple rows: $label"
                    }
                    val current = LayerBody(bounds, listBounds, include, visibility)
                    if (current == quietCandidate) {
                        stableObservations++
                        if (stableObservations >= 2 && System.nanoTime() <= deadline) accepted = current
                    } else {
                        stableObservations = 0
                        // A first visible row may still be moving. A DOWN during scroll
                        // animation stops the scroll and does not represent a row click.
                        quietCandidate = if (scrollQuiet("visible-row-before-single-tap")) current else null
                    }
                } else if (list != null && System.nanoTime() < deadline) {
                    quietCandidate = null; stableObservations = 0
                    if (!bounds.isEmpty && !listBounds.isEmpty) direction =
                        if (bounds.centerY() < listBounds.centerY()) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    if (list.actionList.none { it.id == direction }) direction =
                        if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    if (list.actionList.any { it.id == direction }) {
                        val scrolled = list.performAction(direction)
                        observation.put("scrollAction", direction).put("scrollAccepted", scrolled)
                        if (scrolled) scrollQuiet("accepted-list-scroll")
                    }
                }
                accepted != null
            }
        }
        return checkNotNull(accepted)
    }
    fun click(label: String) {
        revealEditorControl(instrumentation, label)
        if (label.startsWith("Select ")) {
            val body = layerBody(label)
            val x = body.owner.exactCenterX(); val y = body.owner.exactCenterY()
            val down = SystemClock.uptimeMillis()
            lastClick = JSONObject().put("label", label).put("route", "layer-body-pointer")
                .put("bounds", body.owner.toShortString()).put("listBounds", body.list.toShortString())
                .put("includeBounds", body.include.toShortString()).put("visibilityBounds", body.visibility.toShortString())
                .put("screenX", x).put("screenY", y).put("modifiers", 0).put("downSent", false).put("upSent", false)
            for ((action, elapsed) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 30L)) {
                val event = MotionEvent.obtain(down, down + elapsed, action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                lastClick?.put(if (action == MotionEvent.ACTION_DOWN) "downSent" else "upSent", true)
            }
            instrumentation.waitForIdleSync()
            return
        }
        automation.clearCache()
        var target = node(automation.rootInActiveWindow, label)
        while (target != null && !target.isClickable) target = target.parent
        val enabled = target?.isEnabled == true
        val visible = target?.isVisibleToUser
        val clickable = target?.isClickable
        val windowId = target?.windowId
        val bounds = Rect().also { target?.getBoundsInScreen(it) }
        val result = if (enabled) target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) else null
        lastClick = JSONObject().put("label", label).put("foundClickableAncestor", target != null)
            .put("enabled", enabled).put("visible", visible).put("clickable", clickable)
            .put("windowId", windowId).put("bounds", bounds.toShortString())
            .put("actionInvoked", enabled && target != null).put("actionResult", result ?: JSONObject.NULL)
        check(enabled && result == true) { "Move switch action unavailable: $label" }
        instrumentation.waitForIdleSync()
    }
    var canvas: CanvasView? = null
    waitFor("Move switch initial canvas missing") {
        instrumentation.runOnMainSync { canvas = findCanvas(activity.window.decorView) }
        canvas != null && activity.retainedEditorForTest() != null
    }
    val previous = canvas
    val workspace = activity.retainedWorkspaceForTest()
    check(workspace.installCandidate("p30-move-switch-${System.nanoTime()}", DirtyDisposition.DISCARD) {
        NativeEditor(256, false).also { editor ->
            fun layer(name: String, x: Double): Long {
                val id = editor.importPixels(2, 2, ByteBuffer.allocateDirect(16).apply { repeat(4) { put(byteArrayOf(-1, 0, 0, -1)) }; rewind() })
                editor.renameLayer(id, name); editor.setTransform(id, Transform64(x, 80.0, 16.0, 16.0)); return id
            }
            val first = layer("Switch first", 80.0)
            layer("Switch second", 120.0)
            editor.createMask(first, true); editor.linkMask(first, false); editor.selectLayer(first)
        }
    }.result.get(15, TimeUnit.SECONDS).installed)
    val editor = checkNotNull(workspace.snapshot().editor)
    waitFor("Move switch replacement canvas missing") {
        instrumentation.runOnMainSync { canvas = findCanvas(activity.window.decorView)?.takeIf { it !== previous && it.holder.surface.isValid && it.width > 0 } }
        canvas != null && activity.retainedEditorForTest() === editor
    }
    val view = checkNotNull(canvas)
    val first = editor.state().layers.first { it.name == "Switch first" }.id
    val second = editor.state().layers.first { it.name == "Switch second" }.id
    val selectionPublications = org.json.JSONArray()
    val layoutPublications = org.json.JSONArray()
    fun selectionPublished(id: Long, name: String) {
        val context = instrumentation.targetContext
        val rowLabel = context.getString(R.string.ui_select_layer, name)
        val targetState = context.getString(R.string.layer_panel_editing_target_state)
        val header = context.getString(R.string.layer_panel_current_target, name,
            context.getString(R.string.layer_panel_target_content))
        val observations = org.json.JSONArray()
        val summaryObservations = org.json.JSONArray()
        val summaryScrolls = org.json.JSONArray()
        val publication = JSONObject().put("expectedId", id).put("name", name).put("observations", observations)
            .put("rowVerified", false).put("summaryVerified", false)
            .put("summaryObservations", summaryObservations).put("summaryScrolls", summaryScrolls)
        selectionPublications.put(publication)
        val publicationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var previous: String? = null
        waitFor("Layer selection did not publish its editing target: $name", publicationDeadline) {
            automation.clearCache()
            val root = editorPhysicalDisplayRoot(instrumentation)
            val description = node(root, rowLabel)
            val includeLabel = context.getString(R.string.ui_include_layer, name)
            val visibilityLabel = context.getString(R.string.ui_layer_visibility, name)
            val listLabel = context.getString(R.string.ui_layer_list)
            var owner = description
            val ancestors = org.json.JSONArray()
            while (owner != null && owner.contentDescription?.toString() != listLabel) {
                val candidate = owner
                val isRow = countDescription(candidate, rowLabel) == 1 &&
                    countDescription(candidate, includeLabel) == 1 && countDescription(candidate, visibilityLabel) == 1
                ancestors.put(JSONObject().put("description", candidate.contentDescription ?: JSONObject.NULL)
                    .put("state", candidate.stateDescription ?: JSONObject.NULL).put("isRowOwner", isRow)
                    .put("visible", candidate.isVisibleToUser).put("enabled", candidate.isEnabled)
                    .put("clickable", candidate.isClickable)
                    .put("hasClick", candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    .put("bounds", Rect().also(candidate::getBoundsInScreen).toShortString()))
                if (isRow) break
                owner = candidate.parent
                if (ancestors.length() >= 12) { owner = null; break }
            }
            val row = owner?.takeIf { it.contentDescription?.toString() != listLabel }
            val title = node(root, header)
            val active = editor.state().document.activeLayerId
            val canvasId = view.layerId
            val state = JSONObject().put("nativeActive", active).put("canvasActive", canvasId)
                .put("rowVisible", row?.isVisibleToUser == true).put("rowEnabled", row?.isEnabled == true)
                .put("rowState", row?.stateDescription ?: JSONObject.NULL)
                .put("labelVisible", description?.isVisibleToUser == true)
                .put("labelState", description?.stateDescription ?: JSONObject.NULL)
                .put("rowAncestors", ancestors)
                .put("headerVisible", title?.isVisibleToUser == true)
            if (state.toString() != previous && observations.length() < 32) {
                observations.put(state); previous = state.toString()
            }
            active == id && canvasId == id && description?.isVisibleToUser == true && row?.isVisibleToUser == true && row.isEnabled &&
                row.isClickable && row.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                row.stateDescription?.toString() == targetState
        }
        publication.put("rowVerified", true)
        val publishedState = editor.state()
        val publishedSelection = editor.selection()
        check(publishedState.document.activeLayerId == id && publishedSelection.primary == id && view.layerId == id)
        fun unchangedWhileRevealingSummary() {
            check(editor.state() == publishedState && editor.selection() == publishedSelection && view.layerId == id) {
                "Revealing the current-target summary changed document/selection/Canvas authority: $name"
            }
        }
        previous = null
        // Compact layouts place the summary above the rows in the same scroll area.
        // Verify the visible row first, then reveal and verify the actual summary text.
        waitFor("Current-target summary did not become visible: $name", publicationDeadline) {
            unchangedWhileRevealingSummary()
            automation.clearCache()
            val root = editorPhysicalDisplayRoot(instrumentation)
            val title = node(root, header)
            val list = node(root, context.getString(R.string.ui_layer_list))
            val titleBounds = Rect().also { title?.getBoundsInScreen(it) }
            val listBounds = Rect().also { list?.getBoundsInScreen(it) }
            val visible = title?.isVisibleToUser == true && !titleBounds.isEmpty
            val observation = JSONObject().put("header", header).put("headerVisible", visible)
                .put("headerBounds", titleBounds.toShortString()).put("listBounds", listBounds.toShortString())
                .put("nativeActive", editor.state().document.activeLayerId).put("canvasActive", view.layerId)
                .put("canScrollBackward", list?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } == true)
            if (observation.toString() != previous && summaryObservations.length() < 32) {
                summaryObservations.put(observation); previous = observation.toString()
            }
            if (!visible && list?.isVisibleToUser == true && list.isEnabled &&
                list.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                val remaining = TimeUnit.NANOSECONDS.toMillis((publicationDeadline - System.nanoTime()).coerceAtLeast(0))
                if (remaining > 100) {
                    check(summaryScrolls.length() < 12) { "Current-target summary exceeded bounded scrolling: $name" }
                    val scroll = JSONObject().put("action", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        .put("beforeListBounds", listBounds.toShortString()).put("beforeHeaderBounds", titleBounds.toShortString())
                        .put("startedAt", SystemClock.uptimeMillis())
                    summaryScrolls.put(scroll)
                    val accepted = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    scroll.put("accepted", accepted)
                    check(accepted) { "Layer list rejected its advertised summary scroll: $name" }
                    automation.waitForIdle(100, remaining)
                    instrumentation.waitForIdleSync()
                    automation.clearCache()
                    val afterRoot = editorPhysicalDisplayRoot(instrumentation)
                    val afterTitle = node(afterRoot, header)
                    val afterList = node(afterRoot, context.getString(R.string.ui_layer_list))
                    scroll.put("finishedAt", SystemClock.uptimeMillis())
                        .put("afterListBounds", Rect().also { afterList?.getBoundsInScreen(it) }.toShortString())
                        .put("afterHeaderBounds", Rect().also { afterTitle?.getBoundsInScreen(it) }.toShortString())
                        .put("afterHeaderVisible", afterTitle?.isVisibleToUser == true)
                    unchangedWhileRevealingSummary()
                }
            }
            visible
        }
        unchangedWhileRevealingSummary()
        publication.put("summaryVerified", true).put("documentAndSelectionUnchanged", true)
    }
    fun settled() = waitFor("Move context did not settle") { !view.moveInProgress && !view.moveContextPending && editor.activeTransformCompletion() == null && !editor.hasPendingEdits }
    fun canvasLayout(message: String, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(15), expected: (Rect) -> Boolean = { true }): Rect {
        val complete = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        var previous: Rect? = null
        var result: Rect? = null
        var stableFrames = 0
        val observations = org.json.JSONArray()
        val record = JSONObject().put("condition", message).put("completed", false).put("observations", observations)
        layoutPublications.put(record)
        var lastObservation: String? = null
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> stableFrames = 0 }
        val frame = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val bounds = Rect()
                val visible = view.getGlobalVisibleRect(bounds)
                val matches = expected(bounds)
                val ready = visible && view.isLaidOut && !view.isLayoutRequested &&
                    view.holder.surface.isValid && view.holder.surfaceFrame.width() == view.width &&
                    view.holder.surfaceFrame.height() == view.height && matches
                stableFrames = if (ready && bounds == previous) stableFrames + 1 else 0
                previous = bounds
                val state = JSONObject().put("bounds", bounds.toShortString()).put("matchesExpectedState", matches)
                    .put("width", view.width).put("height", view.height)
                    .put("surfaceWidth", view.holder.surfaceFrame.width()).put("surfaceHeight", view.holder.surfaceFrame.height())
                    .put("imeVisible", view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                    .put("keyboardFocus", view.keyboardFocusActive).put("layoutRequested", view.isLayoutRequested)
                    .put("ready", ready).put("stableFrames", stableFrames)
                if (state.toString() != lastObservation && observations.length() < 32) {
                    observations.put(state); lastObservation = state.toString()
                }
                if (stableFrames >= 2) {
                    result = bounds; record.put("completed", true).put("result", bounds.toShortString()); complete.countDown()
                }
                else view.postOnAnimation(this)
            }
        }
        instrumentation.runOnMainSync { view.addOnLayoutChangeListener(listener); view.postOnAnimation(frame) }
        try { check(complete.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) { message } }
        finally {
            stopped.set(true)
            instrumentation.runOnMainSync { view.removeOnLayoutChangeListener(listener); view.removeCallbacks(frame) }
        }
        return checkNotNull(result)
    }
    fun setX(value: String) {
        val focusDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        // Tool state can precede the new parameter row's accessibility nodes.
        waitFor("Move X field did not become actionable", focusDeadline) { actionAvailable("Move X value") }
        click("Move X value")
        canvasLayout("Move field focus layout did not complete", focusDeadline)
        automation.clearCache()
        var field = node(automation.rootInActiveWindow, "Move X value")
        while (field != null && !field.isEditable) field = field.parent
        check(field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }) == true)
        waitFor("Move switch field did not preview") { view.moveBox?.x == value.toDouble() }
    }
    val initialPointer = JSONObject()
    try {
        click("Move / Transform (V)")
        waitFor("Move switch tool missing") {
            view.toolMode == CanvasToolMode.MOVE && view.layerId == first && actionAvailable("Move Show Controls")
        }
        val original = editor.state()
        click("Move Show Controls")
        waitFor("Ordinary body Move fixture did not hide transform controls") { !view.moveShowControls }
        check(editor.state() == original && !view.moveInProgress && !view.movePersistent &&
            !editor.hasActiveTransformDraft && editor.activeTransformCompletion() == null) {
            "Hiding controls must not edit the document or begin a transform"
        }
        val rect = canvasLayout("Ordinary body Move fixture layout did not complete")
        val scale = view.zoomPercent / 100
        val location = IntArray(2)
        val box = original.layers.first { it.id == first }.transform
        var overlay: MoveHandleOverlay? = null
        instrumentation.runOnMainSync { view.getLocationOnScreen(location); overlay = view.moveOverlay(box) }
        val controls = checkNotNull(overlay)
        val x = location[0] + ((controls.handles[0].x + controls.handles[4].x) / 2).toFloat()
        val y = location[1] + ((controls.handles[0].y + controls.handles[4].y) / 2).toFloat()
        initialPointer.put("canvasBounds", rect.toShortString()).put("canvasOriginX", location[0])
            .put("canvasOriginY", location[1]).put("scale", scale).put("screenX", x).put("screenY", y)
            .put("box", box.toString()).put("showControls", view.moveShowControls).put("persistent", view.movePersistent)
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, delta: Double, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action, x + (delta * scale).toFloat(), y, android.view.KeyEvent.META_CTRL_ON)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        pointer(MotionEvent.ACTION_DOWN, 0.0, 0)
        pointer(MotionEvent.ACTION_MOVE, 12.0, 30)
        waitFor("Move switch DRAG missing") { view.moveBox?.x == 92.0 }
        // Compose omits ACTION_CLICK for a selected tool; V exercises the real same-tool entry.
        val sameToolTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(sameToolTime, sameToolTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0))
        instrumentation.sendKeySync(KeyEvent(sameToolTime, sameToolTime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0))
        instrumentation.waitForIdleSync()
        check(view.moveInProgress && editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null))
        click("Brush (B)")
        settled(); waitFor("Brush did not apply after Move") { view.toolMode == CanvasToolMode.BRUSH }
        pointer(MotionEvent.ACTION_UP, 80.0, 60)
        check(editor.state().revision == original.revision + 1 && editor.state().layers.first { it.id == first }.transform.x == 92.0)
        click("Undo"); settled(); waitFor("Move switch Undo failed") {
            val state = editor.state()
            automation.clearCache()
            var redo = node(automation.rootInActiveWindow, "Redo")
            while (redo != null && !redo.isClickable) redo = redo.parent
            state.layers == original.layers && state.redo && redo?.isVisibleToUser == true &&
                redo.isEnabled && redo.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        }
        click("Redo"); settled(); waitFor("Move switch Redo failed") { editor.state().layers.first { it.id == first }.transform.x == 92.0 }

        click("Move / Transform (V)")
        // Compare panel geometry with the same keyboard state. Focusing X opens
        // the IME; clicking Layers dismisses it while opening the panel.
        val closedBounds = canvasLayout("Closed Layers layout without IME did not complete") {
            view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false
        }
        setX("92.5")
        val beforeOpen = editor.state()
        check(view.moveInProgress)
        click("Layers")
        settled()
        val openBounds = canvasLayout("Layers did not finish opening") {
            it.height() < closedBounds.height() && view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false &&
                view.keyboardFocusActive
        }
        check(editor.state().revision == beforeOpen.revision + 1 && editor.state().layers.first { it.id == first }.transform.x == 92.5)
        setX("92.75")
        val beforeClose = editor.state()
        check(view.moveInProgress)
        click("Layers")
        settled()
        canvasLayout("Layers did not finish closing") {
            it.height() == closedBounds.height() && view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false &&
                view.keyboardFocusActive
        }
        check(editor.state().revision == beforeClose.revision + 1 && editor.state().layers.first { it.id == first }.transform.x == 92.75)
        val beforeReopen = editor.state()
        click("Layers")
        canvasLayout("Layers did not finish reopening") {
            it == openBounds && view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false &&
                view.keyboardFocusActive
        }
        check(editor.state() == beforeReopen)
        automation.clearCache()
        val canvasNode = checkNotNull(node(automation.rootInActiveWindow, "Canvas"))
        check(canvasNode.isEnabled && canvasNode.isVisibleToUser)
        if (!canvasNode.isFocused) check(canvasNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        fun focusedEditable(root: AccessibilityNodeInfo?): Boolean {
            if (root == null) return false
            if (root.isEditable && root.isFocused) return true
            return (0 until root.childCount).any { focusedEditable(root.getChild(it)) }
        }
        waitFor("Canvas input focus did not settle after Layers") {
            var focused = false
            instrumentation.runOnMainSync { focused = view.keyboardFocusActive }
            automation.clearCache()
            focused && node(automation.rootInActiveWindow, "Canvas")?.isFocused == true &&
                !focusedEditable(automation.rootInActiveWindow)
        }
        canvasLayout("Layers input focus changed the open panel bounds") { it == openBounds }
        check(editor.state() == beforeReopen)
        instrumentation.runOnMainSync { check(view.editMoveField(MoveField.X, 93.125)) }
        waitFor("Layer switch draft missing") { view.moveBox?.x == 93.125 }
        val beforeLayer = editor.state()
        click("Select Switch first") // No field focus is lost: same-layer selection is a no-op.
        check(view.moveInProgress && editor.state() == beforeLayer)
        click("Select Switch second")
        settled(); waitFor("Layer selection did not apply after Move") { editor.state().document.activeLayerId == second }
        selectionPublished(second, "Switch second")
        // One transform publication plus one selection metadata revision.
        check(editor.state().layers.first { it.id == first }.transform.x == 93.125 && editor.state().revision == beforeLayer.revision + 2)
        click("Select Switch first")
        settled()
        selectionPublished(first, "Switch first")
        waitFor("First layer did not select") { view.layerId == first }
        instrumentation.runOnMainSync { check(view.editMoveField(MoveField.X, 94.25)) }
        waitFor("Target switch draft missing") { view.moveBox?.x == 94.25 }
        val beforeTarget = editor.state()
        click("Pixels") // Same target must not manufacture a commit.
        check(view.moveInProgress && editor.state() == beforeTarget)
        click("Mask")
        settled(); waitFor("Mask target did not apply after Move") { view.editingMask }
        val afterTarget = editor.state()
        check(afterTarget.revision == beforeTarget.revision + 1 && afterTarget.layers.first { it.id == first }.transform.x == 94.25)
        check(afterTarget.layers.first { it.id == first }.maskPlacement == beforeTarget.layers.first { it.id == first }.maskPlacement)
        setX("81.5")
        val beforePixels = editor.state()
        click("Pixels")
        settled(); waitFor("Pixels target did not apply after mask Move") { !view.editingMask }
        check(editor.state().revision == beforePixels.revision + 1)
        check(editor.state().layers.first { it.id == first }.transform == beforePixels.layers.first { it.id == first }.transform)
        check(editor.state().layers.first { it.id == first }.maskPlacement?.x == 81.5)
        val beforeRetire = editor.state()
        var retiredAction = false
        instrumentation.runOnMainSync { editor.serialized {
            check(view.editMoveField(MoveField.X, 95.0))
            check(view.switchMoveContext { retiredAction = true })
            view.retire()
        } }
        waitFor("Retired Move context did not release the document") { editor.activeTransformCompletion() == null }
        check(!retiredAction && !view.moveContextPending && editor.state().copy(selectedLayerActions = null) == beforeRetire.copy(selectedLayerActions = null))
        return "Ordinary single-layer body Move with real UI-hidden controls settles last accepted DRAG/field before real tool/layer/pixels-mask switches; same values preserve the draft, Undo/Redo exact. Held native terminal/main acknowledgment, one pending request, cancel, retained-project admission refusal before draft allocation, stale/invalidated owner and confirmed observer-error recovery preserve state without replay. Persistent Transform and handles have a separate UI/lifecycle validation selector; group transforms remain unsupported."
    } catch (error: Throwable) {
        runCatching {
            val diagnostic = JSONObject().put("schema", 1).put("error", error.toString()).put("initialPointer", initialPointer)
                .put("lastClick", lastClick ?: JSONObject.NULL)
                .put("selectionPublications", selectionPublications)
                .put("layoutPublications", layoutPublications)
                .put("layerBodyReadiness", layerBodyReadiness)
                .put("scope", "Passive failure-time body Move diagnosis; no fallback, retry or changed assertion")
            instrumentation.runOnMainSync {
                val location = IntArray(2); view.getLocationOnScreen(location)
                val bounds = Rect(); view.getGlobalVisibleRect(bounds)
                diagnostic.put("current", JSONObject().put("moveBox", view.moveBox?.toString())
                    .put("moveInProgress", view.moveInProgress).put("persistent", view.movePersistent)
                    .put("showControls", view.moveShowControls).put("lockRatio", view.moveLockRatio)
                    .put("settling", view.moveSettling).put("contextPending", view.moveContextPending)
                    .put("activeNativeDraft", editor.hasActiveTransformDraft)
                    .put("pendingTransform", editor.activeTransformCompletion() != null)
                    .put("scale", view.zoomPercent / 100).put("canvasOriginX", location[0])
                    .put("canvasOriginY", location[1]).put("canvasBounds", bounds.toShortString())
                    .put("toolMode", view.toolMode.name).put("layerId", view.layerId).put("editingMask", view.editingMask)
                    .put("windowFocus", view.hasWindowFocus()).put("keyboardFocus", view.keyboardFocusActive)
                    .put("imeVisible", view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                    .put("state", editor.state().toString()))
            }
            File(instrumentation.targetContext.filesDir, "p36-move-switch-body-test09-${System.nanoTime()}.json")
                .writeText(diagnostic.toString(2))
        }.onFailure { android.util.Log.e("CompositorMove", "Could not preserve body Move failure diagnosis", it) }
        captureEditorUiFailure(instrumentation, activity, error as? Exception ?: Exception(error), emptyList())
        throw error
    } finally {
        instrumentation.runOnMainSync { view.retire(); activity.finish() }
        instrumentation.waitForIdleSync()
    }
}
