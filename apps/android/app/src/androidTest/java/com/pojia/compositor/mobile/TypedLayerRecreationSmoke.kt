package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.app.ActivityOptions
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Actual Activity/SavedState/Canvas command integration; no global accessibility service or real user workspace. */
internal fun typedLayerRecreationSmoke(instrumentation: Instrumentation): String {
    check(BuildConfig.DEBUG)
    val context = instrumentation.targetContext
    val token = "p103-typed-recreation-${System.nanoTime()}"
    val expectedRoot = File(context.filesDir, "debug-workspaces/$token").canonicalFile
    check(validatedDebugWorkspaceToken(token) == token && !expectedRoot.exists())
    val events = JSONArray()
    val evidence = JSONObject().put("token", token).put("workspace", expectedRoot.path)
        .put("passed", false).put("uiAutomationUsed", false).put("realImeUsed", false).put("events", events)
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
    var workspace: AndroidWorkspace? = null
    val releases = mutableListOf<CountDownLatch>()
    val watchdog = Executors.newSingleThreadScheduledExecutor()
    val watchdogFired = AtomicBoolean(false)
    var failure: Throwable? = null
    fun <T> main(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        error(message)
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    val boundEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
    fun canvas(): CanvasView? = main { findCanvas(activity.window.decorView) }
    fun access(): TypedLayerTestAccess = checkNotNull(main { activity.typedLayerTestAccessForTest() })
    fun ready(editor: NativeEditor? = null) {
        waitFor("Isolated Activity did not attach its typed draft bridge and Canvas") {
            main { activity.typedLayerTestAccessForTest() != null && activity.display?.displayId == 0 } && canvas()?.let { view ->
                main {
                    evidence.put("lastCanvasReady", JSONObject().put("attached", view.isAttachedToWindow)
                        .put("width", view.width).put("height", view.height).put("display", view.display?.displayId)
                        .put("surfaceValid", view.holder.surface.isValid).put("windowFocus", view.hasWindowFocus())
                        .put("keyboardFocus", view.keyboardFocusActive).put("expectedEditorBound", editor == null || boundEditor.get(view) === editor))
                    view.isAttachedToWindow && view.width > 0 && view.height > 0 &&
                    view.display?.displayId == 0 && view.holder.surface.isValid && view.hasWindowFocus() &&
                    view.keyboardFocusActive && (editor == null || boundEditor.get(view) === editor)
                }
            } == true
        }
    }
    fun event(name: String) {
        events.put(JSONObject().put("name", name).put("atNanos", System.nanoTime())
            .put("activityIdentity", System.identityHashCode(activity)))
        if (expectedRoot.isDirectory) File(expectedRoot, "typed-recreation-evidence.json").writeText(evidence.toString(2))
    }
    fun recreate(editor: NativeEditor) {
        val previous = activity
        val retainedWorkspace = workspace
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            main { previous.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        ready(editor)
        check(activity !== previous)
        check(main { activity.retainedWorkspaceForTest() } === retainedWorkspace)
        check(main { activity.retainedEditorForTest() } === editor)
        check(main { activity.intent.getStringExtra(DEBUG_WORKSPACE_TOKEN_EXTRA) } == token)
        check(checkNotNull(workspace).recoveryDirectoryForTest().canonicalFile == expectedRoot)
        event("actual-activity-recreated")
    }
    fun blockQueue(editor: NativeEditor): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases.add(it) }
        watchdog.schedule({ if (release.count > 0) { watchdogFired.set(true); release.countDown() } }, 25, TimeUnit.SECONDS)
        // The barrier holds the retained queue without holding the editor gate or the main looper.
        editor.commandDispatcher.enqueueBarrierForTest {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS))
        }
        check(entered.await(3, TimeUnit.SECONDS))
        return release
    }
    fun awaitIdle(editor: NativeEditor) {
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        waitFor("Canvas did not finish its retained command") { main { canvas()?.editingInProgress != true } }
    }
    fun undoRedo(editor: NativeEditor, removed: () -> Boolean, restored: () -> Boolean, expectRemoval: Boolean = true) {
        val presented = editor.state()
        event("undo-requested-${presented.document.activeLayerId}")
        check(main { checkNotNull(canvas()).command(EditorCommand.Undo(false)) })
        awaitIdle(editor)
        check(removed()) { "One undo did not reverse exactly one typed submission" }
        val retiredId = presented.document.activeLayerId
        if (expectRemoval) {
            check(editor.state().layers.none { it.id == retiredId })
            check(editor.textStyleOrNull(retiredId) == null && editor.adjustmentStyleOrNull(retiredId) == null)
            val staleStyles = editor.typedLayerStylesForPresentation(presented)
            check(staleStyles.text == null && staleStyles.adjustment == null)
            event("undo-removed-stale-id-queries-safe-$retiredId")
        } else check(editor.state().layers.any { it.id == retiredId })
        // Keep the real screen alive for several frames before redo; the previous crash
        // occurred here when recomposition asked JNI for the already removed layer ID.
        val frames = CountDownLatch(1)
        main {
            var remaining = 3
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) frames.countDown()
                    else android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(callback)
        }
        check(frames.await(5, TimeUnit.SECONDS)) { "Screen did not survive frames after typed undo" }
        check(main { activity.typedLayerTestAccessForTest() != null })
        event("undo-screen-survived-three-frames-$retiredId")
        check(main { checkNotNull(canvas()).command(EditorCommand.Undo(true)) })
        awaitIdle(editor)
        check(restored())
    }
    try {
        ready()
        val retainedWorkspace = main { activity.retainedWorkspaceForTest() }
        check(retainedWorkspace.recoveryDirectoryForTest().canonicalFile == expectedRoot) {
            "Refusing lifecycle fixture outside its unique debug workspace"
        }
        workspace = retainedWorkspace
        check(expectedRoot != File(context.filesDir, "workspace").canonicalFile)
        check(retainedWorkspace.installCandidate("typed-recreation", DirtyDisposition.DISCARD) {
            NativeEditor(128, addDefaultLayers = false)
        }.result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(retainedWorkspace.snapshot().editor)
        ready(editor)
        val owner = typedLayerSubmissionOwner(editor)
        event("isolated-editor-installed")

        main { access().beginText() }
        val initial = main { checkNotNull(access().text()) }
        val content = "中文🚀 actual Activity"
        val draft = initial.copy(style = initial.style.replacingContent(content), selectionStart = 2,
            selectionEnd = 4, composingStart = 0, composingEnd = 2)
        main { access().updateText(draft) }
        event("unsubmitted-text-draft-with-composition")
        recreate(editor)
        val restored = main { checkNotNull(access().text()) }
        check(restored.submissionId == draft.submissionId && restored.style == draft.style &&
            restored.selectionStart == 2 && restored.selectionEnd == 4 &&
            restored.composingStart == 0 && restored.composingEnd == 2)
        main { access().applyText() }
        check(!main { access().busy() } && editor.state().layers.isEmpty()) { "Composing text was submitted" }
        main { access().updateText(restored.copy(composingStart = -1, composingEnd = -1)) }

        val invalidDraft = main { checkNotNull(access().text()) }
            .editStyleField(TextStyleField.FONT, "x".repeat(201))
            .editStyleField(TextStyleField.COLOR, "#GGGGGG").editStyleField(TextStyleField.SIZE, "NaN")
        main { access().updateText(invalidDraft) }
        recreate(editor)
        val invalidRestored = main { checkNotNull(access().text()) }
        check(invalidRestored == invalidDraft && invalidRestored.hasInvalidStyleInput)
        main { access().applyText(); access().applyText() }
        check(!main { access().busy() } && editor.state().layers.isEmpty()) { "Invalid restored fields reached the text submission queue" }
        main { access().updateText(invalidRestored.editStyleField(TextStyleField.FONT, "Helvetica")
            .editStyleField(TextStyleField.COLOR, "#000000").editStyleField(TextStyleField.SIZE, draft.style.fontSize.toString())) }
        event("invalid-fields-restored-and-production-submit-rejected")

        val textRelease = blockQueue(editor)
        main { access().applyText(); access().applyText() }
        check(main { access().busy() } && owner.pendingId == draft.submissionId)
        check(editor.state().layers.isEmpty())
        event("text-accepted-through-canvas-command-pending")
        recreate(editor)
        check(main { access().busy() && access().text()?.submissionId == draft.submissionId })
        main {
            val current = checkNotNull(access().text())
            access().updateText(current.copy(style = current.style.replacingContent("must not replace pending input")))
            access().cancelText(); access().beginText(); access().applyText()
        }
        check(main { access().text()?.submissionId == draft.submissionId && access().text()?.style?.content == content })
        textRelease.countDown()
        event("text-pending-command-released")
        awaitIdle(editor)
        waitFor("Published text draft did not clear after recreation") { main { access().text() == null && !access().busy() } }
        val textLayer = editor.state().layers.single()
        check(editor.textStyleOrNull(textLayer.id)?.content == content)
        check(!owner.begin(draft.submissionId, TypedLayerSubmissionKind.TEXT))
        undoRedo(editor, { editor.state().layers.isEmpty() }, { editor.state().layers.size == 1 })
        event("text-single-publication-and-undo-redo")

        main { access().beginAdjustment(AdjustmentKind.EXPOSURE) }
        waitFor("Created Exposure editor did not attach") { main { access().adjustment()?.layerId != null && !access().busy() } }
        val createdExposure = main { checkNotNull(access().adjustment()) }
        val adjustment = main { checkNotNull(access().adjustment()) }.let {
            it.copy(style = it.style.withExposure(1.25, .1, 1.2))
        }
        main { access().updateAdjustment(adjustment) }
        val adjustmentRelease = blockQueue(editor)
        main { access().applyAdjustment(); access().applyAdjustment() }
        check(main { access().busy() } && owner.pendingId == adjustment.submissionId)
        recreate(editor)
        check(main { access().busy() && access().adjustment()?.submissionId == adjustment.submissionId })
        check(equalAdjustmentJson(main { checkNotNull(access().adjustment()).style.toMacJson() }, adjustment.style.toMacJson()))
        main { access().cancelAdjustment(); access().applyAdjustment(); access().beginText() }
        check(main { access().adjustment()?.submissionId == adjustment.submissionId && access().text() == null })
        adjustmentRelease.countDown()
        event("adjustment-pending-command-released")
        awaitIdle(editor)
        waitFor("Published adjustment draft did not clear after recreation") { main { access().adjustment() == null && !access().busy() } }
        val adjustmentLayer = editor.state().layers.single { it.id != textLayer.id }
        check(equalAdjustmentJson(checkNotNull(editor.adjustmentStyleOrNull(adjustmentLayer.id)).toMacJson(), adjustment.style.toMacJson()))
        undoRedo(editor, {
            editor.state().layers.size == 2 && editor.adjustmentStyleOrNull(adjustmentLayer.id)?.let {
                equalAdjustmentJson(it.toMacJson(), createdExposure.style.toMacJson())
            } == true
        }, {
            editor.state().layers.size == 2 && editor.adjustmentStyleOrNull(adjustmentLayer.id)?.let {
                equalAdjustmentJson(it.toMacJson(), adjustment.style.toMacJson())
            } == true
        }, expectRemoval = false)
        event("adjustment-settings-publication-and-undo-redo-keep-created-layer")

        // Keep a real handler from the Exposure presentation, then publish Hue without
        // yielding main to the receipt loader. Neither that handler nor a new draft may
        // reopen Exposure during the native-publication -> composed-presentation gap.
        main { access().beginAdjustment(AdjustmentKind.HUE_SATURATION) }
        waitFor("Created Hue editor did not attach") { main { access().adjustment()?.layerId != null && !access().busy() } }
        val hueDraft = main { checkNotNull(access().adjustment()) }
        val staleAdjustmentAccess = main { access() }
        val hueRelease = blockQueue(editor)
        main {
            staleAdjustmentAccess.applyAdjustment()
            hueRelease.countDown()
            editor.commandDispatcher.awaitIdleForTest().get(5, TimeUnit.SECONDS)
            check(owner.pendingId == null && owner.presentationPendingId == hueDraft.submissionId)
            check(staleAdjustmentAccess.busy())
            staleAdjustmentAccess.editAdjustment()
            staleAdjustmentAccess.cancelAdjustment()
            staleAdjustmentAccess.beginAdjustment(AdjustmentKind.COLOR_BALANCE)
            check(staleAdjustmentAccess.adjustment()?.submissionId == hueDraft.submissionId)
            check(staleAdjustmentAccess.adjustment()?.style?.kind == AdjustmentKind.HUE_SATURATION)
            event("published-hue-keeps-edit-gate-before-ui-consumption")
        }
        waitFor("Hue presentation was not composed and released") {
            main { access().adjustment() == null && !access().busy() }
        }
        val hueLayer = editor.state().layers.single { it.id != textLayer.id && it.id != adjustmentLayer.id }
        main { staleAdjustmentAccess.editAdjustment() }
        check(main { access().adjustment()?.layerId == hueLayer.id &&
            access().adjustment()?.style?.kind == AdjustmentKind.HUE_SATURATION }) {
            "An old edit handler opened the previous adjustment after current presentation"
        }
        main { access().cancelAdjustment() }
        event("old-edit-handler-uses-new-hue-presentation")

        // Force publication before the screen can consume its receipt: keep this one main turn
        // until the native queue finishes, then recreate before returning to Compose effects.
        main { access().beginText() }
        val receiptDraft = main { checkNotNull(access().text()) }.let {
            it.copy(style = it.style.replacingContent("published before UI receipt"))
        }
        main { access().updateText(receiptDraft) }
        val receiptRelease = blockQueue(editor)
        val previous = activity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            main {
                access().applyText()
                receiptRelease.countDown()
                editor.commandDispatcher.awaitIdleForTest().get(5, TimeUnit.SECONDS)
                check(owner.outcomeFor(receiptDraft.submissionId)?.published == true)
                check(access().text()?.submissionId == receiptDraft.submissionId)
                check(access().busy() && owner.presentationPendingId == receiptDraft.submissionId)
                access().editText()
                check(access().text()?.submissionId == receiptDraft.submissionId)
                event("published-before-ui-receipt-recreate-requested")
                previous.recreate()
            }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        ready(editor)
        check(activity !== previous && main { activity.retainedWorkspaceForTest() } === retainedWorkspace)
        waitFor("Restored already-published draft was not consumed") { main { access().text() == null && !access().busy() } }
        check(editor.state().layers.size == 4)
        check(editor.state().layers.count { editor.textStyleOrNull(it.id)?.content == receiptDraft.style.content } == 1)
        check(!owner.begin(receiptDraft.submissionId, TypedLayerSubmissionKind.TEXT))
        undoRedo(editor, { editor.state().layers.size == 3 }, { editor.state().layers.size == 4 })
        event("publication-before-ui-delivery-recreated-without-replay")
        check(!watchdogFired.get())
        evidence.put("passed", true).put("textSubmissionId", draft.submissionId)
            .put("adjustmentSubmissionId", adjustment.submissionId).put("receiptSubmissionId", receiptDraft.submissionId)
            .put("finalLayers", editor.state().layers.size)
        return "Actual isolated Activity recreation: unsubmitted composing text, pending typed commands and published-before-UI receipt; one undo/redo, safe stale IDs across 3 UI frames. Publication keeps typed edit gate until composition; a retained old Exposure handler opens current Hue after receipt. Bridge-driven; real IME/physical tap not exercised. Evidence: ${File(expectedRoot, "typed-recreation-evidence.json")}"
    } catch (error: Throwable) {
        failure = error
        evidence.put("error", error.toString())
        throw error
    } finally {
        releases.forEach { it.countDown() }
        watchdog.shutdownNow()
        val finishFailure = runCatching { main { activity.finishAndRemoveTask() } }.exceptionOrNull()
        val closeFailure = runCatching { workspace?.closeAsync()?.get(12, TimeUnit.SECONDS) }.exceptionOrNull()
        evidence.put("activityFinished", finishFailure == null).put("workspaceClosed", closeFailure == null)
            .put("watchdogFired", watchdogFired.get())
        if (finishFailure != null || closeFailure != null) evidence.put("passed", false)
        if (expectedRoot.isDirectory) File(expectedRoot, "typed-recreation-evidence.json").writeText(evidence.toString(2))
        val cleanupFailures = listOfNotNull(finishFailure, closeFailure)
        if (failure != null) cleanupFailures.forEach { failure!!.addSuppressed(it) }
        else if (cleanupFailures.isNotEmpty()) {
            val cleanup = cleanupFailures.first()
            cleanupFailures.drop(1).forEach { cleanup.addSuppressed(it) }
            throw cleanup
        }
    }
}
