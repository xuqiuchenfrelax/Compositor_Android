package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Real GL frames must wait for a display-scene claim without failing an accepted edit. */
internal fun displaySceneAdmissionSmoke(instrumentation: Instrumentation): String {
    val ledgerProof = resourceAvailabilitySmoke()
    val context = instrumentation.targetContext
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val ledger = ResourceBudgetLedger(64L * 1024 * 1024)
    val editor = NativeEditor.createWithLedgerForTest(256, ledger)
    val failures = CopyOnWriteArrayList<String>()
    val evidence = JSONObject().put("ledgerProof", ledgerProof).put("stages", JSONArray())
        .put("deferredObservations", JSONArray())
    val evidenceFile = File(context.filesDir, "display-scene-admission-${System.nanoTime()}.json")
    lateinit var view: CanvasView
    lateinit var overlay: FrameLayout
    var attached = false
    var stage = "attach"
    var blockedGl: CountDownLatch? = null
    val held = mutableListOf<ResourceBudgetLedger.Reservation>()

    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { "$label timed out" }
    }
    fun gpu(): GpuStats {
        val latch = CountDownLatch(1)
        var result: GpuStats? = null
        view.debugGpuStats { result = it; latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "Display-scene GPU fence timed out" }
        return checkNotNull(result)
    }
    fun holdGpu(): GpuStats {
        check(blockedGl == null)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        blockedGl = unblock
        var result: GpuStats? = null
        view.debugGpuStats {
            result = it
            entered.countDown()
            check(unblock.await(30, TimeUnit.SECONDS)) { "Display-scene GL barrier timed out" }
        }
        check(entered.await(10, TimeUnit.SECONDS)) { "Display-scene GL barrier did not enter" }
        return checkNotNull(result)
    }
    fun releaseGpu() {
        checkNotNull(blockedGl).countDown()
        blockedGl = null
    }
    fun admission() = view.debugDisplaySceneAdmission()
    fun observe(label: String, stats: GpuStats? = null) {
        val state = admission()
        val budget = ledger.snapshot()
        (evidence.getJSONArray("stages")).put(JSONObject().put("stage", label)
            .put("revision", editor.currentRevision())
            .put("resolution", editor.state().document.resolution)
            .put("availableBytes", budget.availableBytes)
            .put("reservedBytes", budget.reservedBytes)
            .put("reservations", budget.reservations)
            .put("attempts", state.attempts)
            .put("capturedScenes", state.capturedScenes)
            .put("deferredAttempts", state.deferredAttempts)
            .put("wakeRequests", state.wakeRequests)
            .put("waiting", state.waiting)
            .put("deferred", state.deferred)
            .put("closed", state.closed)
            .put("requestedBytes", state.requestedBytes)
            .put("completedFrames", stats?.completedFrames)
            .put("completedSceneGeneration", stats?.sceneGeneration)
            .put("failures", JSONArray(failures.toList())))
    }
    fun commandResolution(value: Double) {
        main { view.command { editor.setResolution(value) } }
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        check(editor.state().document.resolution == value && !editor.hasPendingEdits)
        check(editor.commandDispatcher.latestCompletion?.error == null)
        check(failures.isEmpty()) { "A committed command failed: $failures" }
    }
    fun generation(): Long = checkNotNull(editor.captureDisplayScene()).use { it.generation() }
    fun awaitFrame(target: Long, after: Long): GpuStats {
        var current: GpuStats? = null
        await("Display-scene frame $target") {
            val sampled = gpu()
            if (sampled.currentContext && sampled.completedFrames > after && sampled.sceneGeneration == target)
                current = sampled
            current != null
        }
        return checkNotNull(current)
    }
    fun exhaustBytes(label: String): ResourceBudgetLedger.Reservation {
        val available = ledger.snapshot().availableBytes
        check(available > 0)
        return ledger.reserve(available, label).also(held::add)
    }
    fun release(lease: ResourceBudgetLedger.Reservation) {
        lease.close()
        held.remove(lease)
    }
    fun awaitDeferred(after: Long, baseline: GpuStats): DisplaySceneAdmission.State {
        var current: DisplaySceneAdmission.State? = null
        await("Display-scene deferred capture") {
            val sampled = admission()
            if (sampled.deferred && sampled.waiting && sampled.deferredAttempts > after) current = sampled
            current != null
        }
        val blocked = gpu()
        evidence.getJSONArray("deferredObservations").put(JSONObject().put("stage", stage)
            .put("baselineCompletedFrames", baseline.completedFrames)
            .put("baselineSceneGeneration", baseline.sceneGeneration)
            .put("blockedCompletedFrames", blocked.completedFrames)
            .put("blockedSceneGeneration", blocked.sceneGeneration))
        check(blocked.completedFrames == baseline.completedFrames &&
            blocked.sceneGeneration == baseline.sceneGeneration) {
            "Deferred scene counted a completed frame: $baseline -> $blocked"
        }
        check(failures.isEmpty()) { "Deferred scene reported a failure: $failures" }
        return checkNotNull(current)
    }
    fun permanentInsufficiency() {
        val probeLedger = ResourceBudgetLedger(64L * 1024 * 1024)
        val required = NativeEditor.createWithLedgerForTest(256, probeLedger).use { probe ->
            val installed = probe.installedDocumentBytes()
            probeLedger.reserve(probeLedger.snapshot().availableBytes, "scene size probe").use {
                val error = runCatching { probe.captureScene()?.close() }.exceptionOrNull()
                check(error is ResourceBudgetExceededException && error.requestedBytes > 0) {
                    "Scene size probe did not report a reservation need: $error"
                }
                installed to error.requestedBytes
            }
        }
        val limit = Math.addExact(required.first, required.second) - 1
        val tightLedger = ResourceBudgetLedger(limit)
        check(required.first <= limit && Math.addExact(required.first, required.second) > limit)
        val error = runCatching {
            NativeEditor.createWithLedgerForTest(256, tightLedger).close()
        }.exceptionOrNull()
        check(error is TransformDraftException && error.status == 3) {
            "Permanently insufficient future display scene must reject construction: $error"
        }
        val state = tightLedger.snapshot()
        check(state.reservedBytes == 0L && state.reservations == 0) {
            "Refused constructor leaked resource claims: $state"
        }
        evidence.put("permanentLimitBytes", limit)
            .put("permanentInstalledBytes", required.first)
            .put("permanentRequestedBytes", required.second)
            .put("permanentErrorType", error.javaClass.simpleName)
            .put("permanentNativeStatus", error.status)
            .put("permanentConstructorRejected", true)
            .put("permanentClaimsAfterRefusal", state.reservations)
            .put("permanentReservedBytesAfterRefusal", state.reservedBytes)
            .put("permanentCoordinatorCreated", false)
    }

    try {
        main {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, {}, { failures.add(it) })
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            attached = true
        }
        await("Display-scene canvas attachment") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        stage = "committed-288"
        commandResolution(288.0)
        val committed = generation()
        main { view.requestRender() }
        val warm = awaitFrame(committed, 0)
        observe(stage, warm)

        stage = "byte-pressure-deferred"
        val beforeFrame = holdGpu()
        val byteLease = exhaustBytes("display scene byte-pressure fixture")
        val beforeByte = admission()
        main { view.requestRender() }
        releaseGpu()
        val deferredByte = awaitDeferred(beforeByte.deferredAttempts, beforeFrame)
        check(deferredByte.requestedBytes > 0 && ledger.snapshot().availableBytes == 0L)
        val strictError = runCatching { editor.captureScene()?.close() }.exceptionOrNull()
        check(strictError is ResourceBudgetExceededException && strictError.requestedBytes > 0 &&
            strictError.availableBytes == 0L) { "Strict capture must report the exhausted budget: $strictError" }
        evidence.put("strictCaptureErrorType", strictError.javaClass.simpleName)
            .put("strictCaptureRequestedBytes", strictError.requestedBytes)
            .put("strictCaptureAvailableBytes", strictError.availableBytes)
        observe(stage, beforeFrame)

        stage = "byte-pressure-auto-wake"
        release(byteLease)
        val woken = awaitFrame(committed, beforeFrame.completedFrames)
        check(admission().wakeRequests > deferredByte.wakeRequests)
        observe(stage, woken)

        stage = "permanent-insufficiency"
        permanentInsufficiency()
        observe(stage, gpu())

        stage = "newer-targets-waiting"
        val targetBaseline = holdGpu()
        val targetLease = exhaustBytes("display scene newer-target fixture")
        val beforeTarget = admission()
        main { view.requestRender() }
        releaseGpu()
        val deferredTarget = awaitDeferred(beforeTarget.deferredAttempts, targetBaseline)
        observe(stage, targetBaseline)

        stage = "edits-while-display-waits"
        val editBaseline = holdGpu()
        release(targetLease)
        commandResolution(289.0)
        commandResolution(290.0)
        val targetRevision = editor.currentRevision()
        val replacementLease = exhaustBytes("display scene replacement-target fixture")
        main { view.requestRender() }
        releaseGpu()
        val deferredReplacement = awaitDeferred(deferredTarget.deferredAttempts, editBaseline)
        check(editor.currentRevision() == targetRevision && editor.state().document.resolution == 290.0)
        check(ledger.snapshot().availableBytes == 0L)
        observe(stage, editBaseline)

        stage = "latest-target-auto-wake"
        release(replacementLease)
        val finalGeneration = generation()
        evidence.put("latestTargetSceneGeneration", finalGeneration)
        val finalFrame = awaitFrame(finalGeneration, editBaseline.completedFrames)
        check(admission().wakeRequests > deferredReplacement.wakeRequests)
        check(editor.state().document.resolution == 290.0 && failures.isEmpty())
        observe(stage, finalFrame)

        stage = "reservation-slot-pressure"
        val slotFrame = holdGpu()
        val slots = mutableListOf<ResourceBudgetLedger.Reservation>()
        while (true) {
            val slot = ledger.tryReserve(0, "display scene slot-pressure fixture") ?: break
            slots += slot; held += slot
            check(slots.size <= 64) { "Unbounded reservation slots" }
        }
        check(slots.isNotEmpty() && ledger.snapshot().availableBytes > 0)
        val beforeSlots = admission()
        main { view.requestRender() }
        releaseGpu()
        val deferredSlots = awaitDeferred(beforeSlots.deferredAttempts, slotFrame)
        observe(stage, slotFrame)
        stage = "reservation-slot-auto-wake"
        release(slots.removeLast())
        val afterSlots = awaitFrame(finalGeneration, slotFrame.completedFrames)
        check(admission().wakeRequests > deferredSlots.wakeRequests)
        observe(stage, afterSlots)
        slots.forEach(::release)

        stage = "retire-cancels-waiter"
        val retireFrame = holdGpu()
        val retireLease = exhaustBytes("display scene retire fixture")
        val beforeRetire = admission()
        main { view.requestRender() }
        releaseGpu()
        awaitDeferred(beforeRetire.deferredAttempts, retireFrame)
        val wakeBeforeRetire = admission().wakeRequests
        main { view.retire() }
        check(admission().closed && !admission().waiting && !admission().deferred)
        evidence.put("retireWakeRequestsBeforeRelease", wakeBeforeRetire)
            .put("retireClosedBeforeRelease", admission().closed)
        release(retireLease)
        check(admission().wakeRequests == wakeBeforeRetire)
        evidence.put("retireWakeRequestsAfterRelease", admission().wakeRequests)
        check(failures.isEmpty())
        observe(stage)

        evidence.put("passed", true)
        evidenceFile.writeText(evidence.toString(2))
        return "Display scene admission: temporary byte and slot pressure deferred without failure or false frame; strict capture reported pressure; permanent future-scene insufficiency rejected construction with zero claims; metadata edits committed while waiting; release woke final committed target automatically; retirement cancelled waiter. Ledger=$ledgerProof Evidence=${evidenceFile.name}"
    } catch (error: Throwable) {
        evidence.put("passed", false).put("failedStage", stage).put("error", error.toString())
        runCatching { observe("failure-$stage") }
        throw error
    } finally {
        blockedGl?.countDown()
        held.asReversed().forEach { it.close() }
        if (attached) main { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
        editor.close()
        main { activity.finish() }
        instrumentation.waitForIdleSync()
        evidenceFile.writeText(evidence.toString(2))
    }
}
