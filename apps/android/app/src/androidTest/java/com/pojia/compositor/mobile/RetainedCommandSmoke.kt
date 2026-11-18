package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Actual Activity recreation must detach a view without discarding accepted session edits. */
internal fun retainedCommandSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val root = File(context.cacheDir, "retained-command-${System.nanoTime()}").apply { check(mkdirs()) }
    val releases = mutableListOf<CountDownLatch>()
    val ownedWorkspaces = mutableListOf<AndroidWorkspace>()
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitUntil(message: String, onFailure: (() -> Unit)? = null, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        val ready = condition()
        if (!ready) onFailure?.invoke()
        check(ready) { message }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun canvas(): CanvasView? {
        var result: CanvasView? = null
        main { result = findCanvas(activity.window.decorView) }
        return result
    }
    fun readyCanvas(): CanvasView {
        waitUntil("Retained command canvas did not attach") {
            canvas()?.let { it.isAttachedToWindow && it.holder.surface.isValid && it.width > 0 && it.height > 0 } == true
        }
        return checkNotNull(canvas())
    }
    fun fixtureFailureDiagnostic(workspace: AndroidWorkspace, previous: CanvasView) {
        runCatching {
            lateinit var record: JSONObject
            val activeWindowPackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            main {
                val snapshot = workspace.snapshot()
                val modelEditor = activity.retainedEditorForTest()
                fun editor(value: NativeEditor?): Any = value?.let {
                    val state = it.state()
                    JSONObject().put("identity", System.identityHashCode(it)).put("width", it.canvasWidth)
                        .put("height", it.canvasHeight).put("revision", state.revision)
                        .put("layerCount", state.layers.size).put("pendingEdits", it.pendingEditCount)
                        .put("strokeReservations", it.strokeReservations().size)
                } ?: JSONObject.NULL
                fun view(value: CanvasView?): Any = value?.let {
                    // Read only this app's own Canvas binding; no production test API is added.
                    val boundEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }.get(it) as NativeEditor
                    JSONObject().put("identity", System.identityHashCode(it)).put("isPrevious", it === previous)
                        .put("boundEditorIdentity", System.identityHashCode(boundEditor))
                        .put("boundEditorMatchesModel", boundEditor === modelEditor)
                        .put("boundEditorMatchesWorkspace", boundEditor === snapshot.editor)
                        .put("width", it.width).put("height", it.height).put("attached", it.isAttachedToWindow)
                        .put("surfaceValid", it.holder.surface.isValid).put("visibility", it.visibility)
                        .put("windowVisibility", it.windowVisibility).put("shown", it.isShown)
                        .put("strokeInProgress", it.strokeInProgress).put("editingInProgress", it.editingInProgress)
                        .put("windowFocus", it.hasWindowFocus())
                } ?: JSONObject.NULL
                val canvases = mutableListOf<CanvasView>()
                fun collect(value: View) {
                    if (value is CanvasView) canvases += value
                    if (value is ViewGroup) for (index in 0 until value.childCount) collect(value.getChildAt(index))
                }
                val decor = activity.window.decorView
                collect(decor)
                record = JSONObject().put("schema", 1).put("stage", "fixture-readiness-failure")
                    .put("elapsedRealtimeMillis", android.os.SystemClock.elapsedRealtime())
                    .put("activeWindowPackage", activeWindowPackage ?: JSONObject.NULL)
                    .put("scope", "Failure-time diagnosis only; original readiness predicate and 20-second timeout unchanged")
                    .put("expectedWidth", 16).put("expectedHeight", 16)
                    .put("workspaceIdentity", System.identityHashCode(workspace))
                    .put("workspaceGeneration", snapshot.generation).put("workspaceLoading", snapshot.loading)
                    .put("workspaceCurrentRevision", snapshot.currentRevision).put("workspaceSavedRevision", snapshot.savedRevision)
                    .put("workspaceFrozen", snapshot.resourceGrowthFrozen)
                    .put("workspaceError", snapshot.recoveryError ?: snapshot.saveError ?: JSONObject.NULL)
                    .put("workspaceEditor", editor(snapshot.editor)).put("modelEditor", editor(modelEditor))
                    .put("modelMatchesWorkspace", modelEditor === snapshot.editor)
                    .put("previousCanvas", view(previous)).put("firstLocatedCanvas", view(findCanvas(decor)))
                    .put("allCanvases", JSONArray(canvases.map { view(it) }))
                    .put("activity", JSONObject().put("identity", System.identityHashCode(activity))
                        .put("lifecycle", activity.lifecycle.currentState.name).put("finishing", activity.isFinishing)
                        .put("destroyed", activity.isDestroyed).put("windowFocus", activity.hasWindowFocus())
                        .put("decorAttached", decor.isAttachedToWindow).put("decorVisibility", decor.visibility)
                        .put("decorWindowVisibility", decor.windowVisibility).put("decorShown", decor.isShown))
            }
            val file = File(context.filesDir, "p20-retained-fixture-ready03-${System.nanoTime()}.json")
            file.writeText(record.toString(2))
            android.util.Log.i("CompositorRetained", "Saved failure-time readiness diagnostic ${file.name}")
        }.onFailure { android.util.Log.e("CompositorRetained", "Could not preserve fixture readiness diagnosis", it) }
    }
    fun recreateActivity(): CanvasView {
        val previous = readyCanvas()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            main { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        return readyCanvas().also { check(it !== previous && !previous.isAttachedToWindow) }
    }
    fun accessible(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return if (root.packageName?.toString() == context.packageName) find(root) else null
    }
    fun hold(editor: NativeEditor): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        releases += release
        editor.commandDispatcher.enqueueBarrierForTest {
            entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
        }
        check(entered.await(10, TimeUnit.SECONDS))
        return release
    }
    fun idle(editor: NativeEditor) {
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(16 * 16 * 4)
        check(editor.draw(16, 16, 0.0, 0.0, 16.0, 16.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun redPixels(alpha: Int): ByteArray = ByteArray(16 * 16 * 4).also { bytes ->
        for (index in bytes.indices step 4) { bytes[index] = alpha.toByte(); bytes[index + 3] = alpha.toByte() }
    }
    fun fixture(): NativeEditor = NativeEditor(16, addDefaultLayers = false).also { editor ->
        val buffer = ByteBuffer.allocateDirect(16 * 16 * 4).apply { put(redPixels(255)); rewind() }
        editor.importPixels(16, 16, buffer)
    }
    fun rejectFrozen(workspace: AndroidWorkspace, editor: NativeEditor, epoch: Long) {
        var admitted = false
        val failure = try {
            workspace.withFrozenIo(checkNotNull(workspace.token(editor)), true, { admitted = true }) {}
                .get(10, TimeUnit.SECONDS)
            null
        } catch (error: ExecutionException) { error.cause }
        check(failure?.message == PENDING_EDIT_MESSAGE && !admitted)
        check(!workspace.snapshot().resourceGrowthFrozen && checkNotNull(workspace.token(editor)).commandEpoch == epoch)
    }
    fun coldRecovery(workspace: AndroidWorkspace, directory: File, expected: EditorState, expectedPixels: ByteArray) {
        check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == expected.revision)
        val copied = File(root, directory.name + "-cold")
        check(copied.mkdirs())
        val store = WorkspaceRecoveryStore(directory)
        val target = checkNotNull(store.read())
        File(directory, "active.json").copyTo(File(copied, "active.json"))
        store.checkpoint(target).copyTo(File(copied, target.fileName))
        val recovered = AndroidWorkspace(copied)
        ownedWorkspaces += recovered
        waitUntil("Retained edits did not cold recover") { !recovered.snapshot().loading }
        val editor = checkNotNull(recovered.snapshot().editor)
        // Native checkpoint5 stores document state and starts a new empty undo/redo session.
        check(editor.state().copy(selectedLayerActions = null) == expected.copy(undo = false, redo = false, selectedLayerActions = null))
        check(editor.canvasWidth == 16 && editor.canvasHeight == 16 && pixels(editor).contentEquals(expectedPixels))
        check(recovered.snapshot().currentRevision == expected.revision && recovered.snapshot().projectDirty)
        recovered.closeAsync().get(10, TimeUnit.SECONDS)
    }
    try {
        val previousView = readyCanvas()
        val workspace = activity.retainedWorkspaceForTest()
        check(workspace.installCandidate("retained-command-fixture", DirtyDisposition.DISCARD) { fixture() }
            .result.get(10, TimeUnit.SECONDS).installed)
        waitUntil("Fixture was not published to Activity", onFailure = { fixtureFailureDiagnostic(workspace, previousView) }) {
            activity.retainedEditorForTest()?.canvasWidth == 16 &&
                canvas()?.let { it !== previousView && it.holder.surface.isValid } == true
        }
        val oldView = readyCanvas()
        val editor = checkNotNull(activity.retainedEditorForTest())
        val owner = activity.retainedProjectFlowForTest()
        val original = editor.state()
        val redId = original.layers.single().id
        check(pixels(editor).contentEquals(redPixels(255)))
        val token = checkNotNull(workspace.token(editor))
        val held = hold(editor)
        val checkpoints = CopyOnWriteArrayList<EditorState>()
        val calls = AtomicInteger()
        main {
            check(oldView.command(EditorCommand.AddLayer))
            editor.commandDispatcher.enqueueBarrierForTest { checkpoints += editor.state() }
            check(oldView.command(EditorCommand.RenameLayer(redId, "Retained ordered")))
            editor.commandDispatcher.enqueueBarrierForTest { checkpoints += editor.state() }
            check(oldView.command(EditorCommand.SetOpacity(redId, 0.5)))
            editor.commandDispatcher.enqueueBarrierForTest { checkpoints += editor.state() }
            check(editor.pendingEditCount == 3 && calls.get() == 0 && editor.state() == original)
            check(!owner.chooseNewCanvas(editor) && owner.state.error == PENDING_EDIT_MESSAGE)
            owner.clearError()
        }
        rejectFrozen(workspace, editor, token.commandEpoch)
        val decodes = AtomicInteger()
        val rejection = workspace.installCandidate("must-not-overtake", DirtyDisposition.DISCARD) {
            decodes.incrementAndGet(); fixture()
        }.result.get(10, TimeUnit.SECONDS)
        check(!rejection.installed && rejection.reason == PENDING_EDIT_MESSAGE && decodes.get() == 0)
        val newView = recreateActivity()
        check(newView !== oldView && !oldView.isAttachedToWindow)
        check(activity.retainedWorkspaceForTest() === workspace && activity.retainedEditorForTest() === editor &&
            activity.retainedProjectFlowForTest() === owner)
        check(editor.pendingEditCount == 3 && editor.state() == original && calls.get() == 0)
        rejectFrozen(workspace, editor, token.commandEpoch)
        held.countDown(); idle(editor)
        waitUntil("Recreated Activity did not receive retained commits") {
            workspace.snapshot().currentRevision == original.revision + 3 && !editor.hasPendingEdits
        }
        val edited = editor.state()
        check(checkpoints.size == 3 && editor.pendingEditCount == 0)
        check(checkpoints.map { it.revision } == listOf(original.revision + 1, original.revision + 2, original.revision + 3))
        check(checkpoints[0].layers.size == original.layers.size + 1 && checkpoints[0].layers.first().name == original.layers.first().name)
        check(checkpoints[1].layers.first().name == "Retained ordered" && checkpoints[1].layers.first().opacity == 1.0)
        check(edited.layers.first().opacity == 0.5 && edited.layers.last().assetPresent == false)
        check(pixels(editor).contentEquals(redPixels(128))) { "Retained opacity did not produce independent premultiplied RGBA128" }
        // The retired view must not accept a late command, even though the native session remains alive.
        main { check(!oldView.command { calls.incrementAndGet(); editor.addLayer() }) }
        idle(editor)
        check(calls.get() == 0 && editor.state() == edited)
        for (index in 2 downTo 0) {
            main { check(newView.command(EditorCommand.Undo(false))) }; idle(editor)
            val expected = if (index == 0) original else checkpoints[index - 1]
            check(editor.state().layers == expected.layers && editor.state().document == expected.document)
            check(pixels(editor).contentEquals(redPixels(255)))
        }
        for (index in 0..2) {
            main { check(newView.command(EditorCommand.Undo(true))) }; idle(editor)
            check(editor.state().layers == checkpoints[index].layers && editor.state().document == checkpoints[index].document)
            check(pixels(editor).contentEquals(redPixels(if (index == 2) 128 else 255)))
        }
        coldRecovery(workspace, File(context.filesDir, "workspace"), editor.state(), redPixels(128))

        // A fault accepted by the old real Activity must reach its recreated screen exactly once.
        val beforeReplay = editor.state(); val faultHeld = hold(editor)
        val faultMessage = "Expected Activity retained fault"
        val faultPresentation = activity.getString(R.string.notice_editor_command_failed)
        main { check(newView.command { error(faultMessage) }) }
        val errorView = recreateActivity()
        check(editor.pendingEditCount == 1 && editor.state() == beforeReplay)
        faultHeld.countDown(); idle(editor)
        check(editor.commandDispatcher.latestCompletion?.error == faultMessage) { "Retained diagnostic was lost during localization" }
        waitUntil("Recreated Activity lost an unread retained command error") { accessible(faultPresentation) != null }
        check(editor.pendingEditCount == 0 && editor.state() == beforeReplay && pixels(editor).contentEquals(redPixels(128)))
        val visibleErrorView = recreateActivity(); idle(editor)
        waitUntil("Activity recreation cleared an already visible retained command error") { accessible(faultPresentation) != null }
        check(visibleErrorView !== errorView && editor.state() == beforeReplay && pixels(editor).contentEquals(redPixels(128)))
        var ok = accessible(activity.getString(R.string.ui_ok))
        repeat(12) {
            val node = ok
            if (node != null && !node.isClickable && node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) ok = node.parent
        }
        check(checkNotNull(ok).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitUntil("Retained command error dialog did not clear") { accessible(faultPresentation) == null }
        val clearedView = recreateActivity(); idle(editor)
        check(clearedView !== visibleErrorView && accessible(faultPresentation) == null && editor.state() == beforeReplay)

        // A separate attached view exposes real completion callbacks: detach suppresses old deliveries,
        // while session-owned success/failure tasks continue in order and close drains accepted work.
        val folder = File(root, "callbacks")
        val isolated = AndroidWorkspace(folder)
        ownedWorkspaces += isolated
        waitUntil("Callback workspace did not open") { !isolated.snapshot().loading }
        check(isolated.installCandidate("callback-fixture", DirtyDisposition.DISCARD) { fixture() }
            .result.get(10, TimeUnit.SECONDS).installed)
        val callbackEditor = checkNotNull(isolated.snapshot().editor)
        val changed = AtomicInteger(); val failed = AtomicInteger(); val accepted = AtomicInteger()
        lateinit var view: CanvasView
        lateinit var overlay: FrameLayout
        main {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, callbackEditor, { changed.incrementAndGet() }, { failed.incrementAndGet() },
                checkNotNull(isolated.lease(callbackEditor)))
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        try {
            waitUntil("Callback canvas did not attach") { view.holder.surface.isValid && view.width > 0 }
            val beforeFault = callbackEditor.state()
            val callbackFault = "Expected live retained command fault"
            // Queue a failure between accepted successful edits; it must settle its ticket without
            // stopping later work or adding an undo step for a document that did not change.
            main {
                check(view.command { error(callbackFault) })
                check(view.command(EditorCommand.SetOpacity(beforeFault.layers.single().id, 0.75)))
            }
            idle(callbackEditor)
            waitUntil("A failed retained command did not deliver exactly one live error") { failed.get() == 1 }
            check(callbackEditor.pendingEditCount == 0 && callbackEditor.currentRevision() == beforeFault.revision + 1)
            check(callbackEditor.state().layers.single().opacity == 0.75)
            main { check(view.command(EditorCommand.Undo(false))) }; idle(callbackEditor)
            check(callbackEditor.state().layers == beforeFault.layers && pixels(callbackEditor).contentEquals(redPixels(255)))
            changed.set(0); failed.set(0)
            val baseline = callbackEditor.state()
            val closeHeld = hold(callbackEditor)
            main {
                check(view.command { accepted.incrementAndGet(); callbackEditor.renameLayer(baseline.layers.single().id, "Close retained") })
                check(view.command { accepted.incrementAndGet(); error("Expected retained command fault") })
                check(view.command { accepted.incrementAndGet(); callbackEditor.setOpacity(baseline.layers.single().id, 0.5) })
                check(callbackEditor.pendingEditCount == 3)
                view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay)
                check(callbackEditor.pendingEditCount == 3)
            }
            val closing = isolated.closeAsync()
            check(!closing.isDone && callbackEditor.pendingEditCount == 3 && callbackEditor.state() == baseline)
            closeHeld.countDown(); closing.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            check(accepted.get() == 3 && changed.get() == 0 && failed.get() == 0)
            check(callbackEditor.pendingEditCount == 0 && callbackEditor.state().layers.isEmpty())
            val target = checkNotNull(WorkspaceRecoveryStore(folder).read())
            NativeEditor.load(WorkspaceRecoveryStore(folder).checkpoint(target)).use { recovered ->
                check(recovered.state().layers.single().let { it.name == "Close retained" && it.opacity == 0.5 })
                check(recovered.currentRevision() == baseline.revision + 2 && !recovered.state().undo && !recovered.state().redo)
                check(pixels(recovered).contentEquals(redPixels(128)))
            }
        } finally { main { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) } }
        val fences = retainedStrokeFenceSmoke(instrumentation, activity, root)
        return "Actual held retained queue survived Activity recreation: three accepted typed edits executed exactly once in order, precise revisions/undo/redo and independent RGBA pixels, File/New/frozen/install rejection, retired view late-command rejection, exact checkpoint cold recovery with empty history. Unread error replayed to recreated Activity and stayed visible through another actual recreation; normal OK cleared it, and subsequent recreation did not replay the consumed error. Detached old callbacks stayed silent; mixed success/failure continued and close drained edits before recovery save/native destruction. $fences"
    } finally {
        releases.forEach { it.countDown() }
        main { activity.finish() }
        instrumentation.waitForIdleSync()
        ownedWorkspaces.forEach { it.closeAsync().get(10, TimeUnit.SECONDS) }
        root.deleteRecursively()
    }
}
