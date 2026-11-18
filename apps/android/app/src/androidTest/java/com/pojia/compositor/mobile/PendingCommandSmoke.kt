package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Hold the actual retained command queue while file actions try to overtake an accepted edit. */
internal fun pendingCommandSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val root = File(context.cacheDir, "pending-command-${System.nanoTime()}").apply { check(mkdirs()) }
    val workspace = AndroidWorkspace(File(root, "workspace"))
    val failures = CopyOnWriteArrayList<String>()
    val createdCopies = mutableListOf<File>()
    lateinit var owner: ProjectFlowController
    lateinit var view: CanvasView
    lateinit var overlay: FrameLayout
    var ownerCreated = false
    var attached = false
    var release: CountDownLatch? = null
    var decoderRelease: CountDownLatch? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    fun holdQueue(): CountDownLatch {
        val blocked = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        release = unblock
        checkNotNull(workspace.snapshot().editor).commandDispatcher.enqueueBarrierForTest {
            blocked.countDown(); check(unblock.await(20, TimeUnit.SECONDS))
        }
        check(blocked.await(10, TimeUnit.SECONDS))
        return unblock
    }
    fun drain() {
        checkNotNull(workspace.snapshot().editor).commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
        check(editor.draw(32, 32, 0.0, 0.0, editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    try {
        waitUntil("Pending command workspace did not open") { !workspace.snapshot().loading }
        val editor = checkNotNull(workspace.snapshot().editor)
        val lease = checkNotNull(workspace.lease(editor))
        main {
            owner = ProjectFlowController(context, workspace); ownerCreated = true
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, {}, { failures.add(it) }, lease)
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attached = true
        }
        waitUntil("Pending command canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        drain()
        val before = editor.state()
        val beforeToken = checkNotNull(workspace.token(editor))
        val libraryBefore = LocalProjectLibrary.projects(context).toSet()
        val held = holdQueue()
        main {
            view.command {
                editor.addLayer()
                editor.renameLayer(editor.state().layers.last().id, "Queued layer")
                editor.setResolution(144.0)
            }
            check(editor.pendingEditCount == 1 && editor.hasPendingEdits)
            check(!view.strokeInProgress) { "A non-stroke command unexpectedly used the brush gate" }
            owner.saveCopy(editor)
            check(owner.state.error == PENDING_EDIT_MESSAGE && !owner.state.busy)
            owner.clearError()
            check(!owner.chooseNewCanvas(editor))
            check(owner.state.error == PENDING_EDIT_MESSAGE && owner.state.newCanvas == null && !owner.state.busy)
        }
        check(editor.state() == before && LocalProjectLibrary.projects(context).toSet() == libraryBefore)

        // The worker must also refuse a token captured before a command was registered.
        var admitted = false
        val frozen = workspace.withFrozenIo(beforeToken, true, { admitted = true }) {}
        val failure = try { frozen.get(10, TimeUnit.SECONDS); null }
            catch (error: ExecutionException) { error.cause }
        check(failure?.message == PENDING_EDIT_MESSAGE && !admitted)
        val afterReject = checkNotNull(workspace.token(editor))
        check(afterReject.commandEpoch == beforeToken.commandEpoch && afterReject.revision == beforeToken.revision)
        check(!workspace.snapshot().resourceGrowthFrozen && lease.isCurrent() && editor.state() == before)
        val earlyDecodes = AtomicInteger()
        val earlyInstall = workspace.installCandidate("pending-edit", DirtyDisposition.DISCARD) {
            earlyDecodes.incrementAndGet(); NativeEditor(8)
        }.result.get(10, TimeUnit.SECONDS)
        check(!earlyInstall.installed && earlyInstall.reason == PENDING_EDIT_MESSAGE && earlyDecodes.get() == 0)
        check(workspace.snapshot().editor === editor && editor.state() == before && editor.pendingEditCount == 1)
        check(checkNotNull(workspace.token(editor)).commandEpoch == beforeToken.commandEpoch)

        held.countDown(); release = null; drain()
        check(!editor.hasPendingEdits && editor.pendingEditCount == 0 && failures.isEmpty())
        val edited = editor.state()
        check(edited.layers.size == before.layers.size + 1 && edited.layers.last().name == "Queued layer")
        check(edited.document.resolution == 144.0 && edited.revision != before.revision)
        main { owner.clearError(); owner.saveCopy(editor) }
        waitUntil("Save after queued edit did not finish") { owner.state.phase == ProjectFlowPhase.IDLE }
        createdCopies += LocalProjectLibrary.projects(context).toSet() - libraryBefore
        check(owner.state.error == null && createdCopies.size == 1 && !workspace.snapshot().projectDirty)
        NativeEditor.loadProjectCandidate(context, createdCopies.single(), 256L * 1024 * 1024, 0).use { saved ->
            check(saved.state().layers == edited.layers && saved.state().document == edited.document) {
                "The saved project omitted the edit that was accepted before File"
            }
        }

        // The first installation check is insufficient: an edit can be accepted while decoding a candidate.
        val beforeLate = workspace.snapshot()
        val lateState = editor.state()
        val latePixels = pixels(editor)
        val pointer = File(root, "workspace/active.json")
        val latePointer = pointer.readBytes()
        val lateToken = checkNotNull(workspace.token(editor))
        val lateHeld = holdQueue()
        val decoding = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        decoderRelease = finishDecode
        val lateDecodes = AtomicInteger()
        val lateInstall = workspace.installCandidate("late-pending-edit", DirtyDisposition.DISCARD) {
            lateDecodes.incrementAndGet(); decoding.countDown()
            check(finishDecode.await(10, TimeUnit.SECONDS))
            NativeEditor(8)
        }
        check(decoding.await(10, TimeUnit.SECONDS))
        main { view.command { editor.setResolution(288.0) }; check(editor.pendingEditCount == 1) }
        finishDecode.countDown(); decoderRelease = null
        val lateResult = lateInstall.result.get(10, TimeUnit.SECONDS)
        check(!lateResult.installed && lateResult.reason == PENDING_EDIT_MESSAGE && lateDecodes.get() == 1)
        val afterLate = workspace.snapshot()
        check(afterLate.editor === editor && afterLate.generation == beforeLate.generation &&
            afterLate.projectBaseline == beforeLate.projectBaseline && afterLate.sourceIdentity == beforeLate.sourceIdentity)
        check(!afterLate.resourceGrowthFrozen && lease.isCurrent() && editor.pendingEditCount == 1)
        check(editor.state() == lateState && pixels(editor).contentEquals(latePixels) && pointer.readBytes().contentEquals(latePointer))
        check(checkNotNull(workspace.token(editor)).commandEpoch == lateToken.commandEpoch)
        lateHeld.countDown(); release = null; drain()
        check(editor.state().document.resolution == 288.0 && !editor.hasPendingEdits && failures.isEmpty()) {
            val diagnostic = org.json.JSONObject().put("stage", "late-command-after-drain")
                .put("resolution", editor.state().document.resolution).put("pendingEdits", editor.pendingEditCount)
                .put("failures", org.json.JSONArray(failures.toList()))
                .put("latestCompletion", editor.commandDispatcher.latestCompletion?.toString())
                .put("workspace", workspace.snapshot().toString())
            runCatching {
                File(context.filesDir, "pending-command-failure-${System.nanoTime()}.json").writeText(diagnostic.toString(2))
            }
            "Late accepted command did not settle: $diagnostic"
        }

        // Retirement only detaches the view: an already accepted session command must still commit once.
        val retiredState = editor.state()
        val retireHeld = holdQueue()
        val retiredCalls = AtomicInteger()
        main {
            view.command { retiredCalls.incrementAndGet(); editor.addLayer() }
            check(editor.hasPendingEdits)
            view.retire()
            check(editor.hasPendingEdits && editor.pendingEditCount == 1 && retiredCalls.get() == 0)
        }
        retireHeld.countDown(); release = null; drain()
        check(editor.state().layers.size == retiredState.layers.size + 1 &&
            editor.currentRevision() == retiredState.revision + 1 && retiredCalls.get() == 1 &&
            !editor.hasPendingEdits && failures.isEmpty())
        main { view.command { retiredCalls.incrementAndGet(); editor.addLayer() } }
        drain()
        check(retiredCalls.get() == 1 && editor.currentRevision() == retiredState.revision + 1)
        NativeEditor(8, addDefaultLayers = false).use { closed ->
            val ticket = checkNotNull(closed.tryRegisterPendingEdit())
            closed.close(); ticket.settle(); ticket.settle()
            check(!closed.hasPendingEdits && closed.tryRegisterPendingEdit() == null)
        }
        return "Held retained edit rejected Save/New, frozen IO and direct installation without decoding or changing epoch; after release it survived in the saved .comp. An edit accepted during candidate decode rejected publication while preserving document, pixels, pointer and ticket until release. Retirement preserved the accepted edit, which committed exactly once; late retired-view submission was rejected and close settled tickets safely."
    } finally {
        decoderRelease?.countDown()
        release?.countDown()
        if (attached) main { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        if (ownerCreated) main { owner.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        createdCopies.forEach { it.deleteRecursively() }
        root.deleteRecursively()
        main { activity.finish() }
        instrumentation.waitForIdleSync()
    }
}
