package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A busy command must defer production display capture without parking the GL caller. */
private fun displayCommandGateContractSmoke(): String {
    val ledger = ResourceBudgetLedger(64L * 1024 * 1024)
    val editor = NativeEditor.createWithLedgerForTest(32, ledger)
    val owner = Executors.newSingleThreadExecutor()
    val display = Executors.newSingleThreadExecutor()
    val notifications = AtomicInteger()
    val admission = DisplaySceneAdmission(ledger, { notifications.incrementAndGet() })
    var release = CountDownLatch(1)
    try {
        fun hold(): java.util.concurrent.Future<*> {
            val entered = CountDownLatch(1)
            val gate = release
            val work = owner.submit {
                editor.serialized {
                    entered.countDown()
                    check(gate.await(10, TimeUnit.SECONDS)) { "Command test release timed out" }
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            return work
        }
        val held = hold()
        // A regression that waits for the editor mutex times out independently of the owner.
        val deferred = display.submit<Boolean> {
            check(!editor.trySerialized { error("Busy command gate unexpectedly entered") })
            editor.captureDisplayScene(admission) == null
        }
        check(deferred.get(2, TimeUnit.SECONDS)) { "Production display did not defer" }
        check(admission.snapshot().waiting && admission.isDeferred && notifications.get() == 0)
        release.countDown(); held.get(5, TimeUnit.SECONDS)
        check(notifications.get() == 1 && admission.hasReadyFrame)
        checkNotNull(editor.captureDisplayScene(admission)).close()
        check(!admission.isDeferred)

        // Registration after the release must still request the retry exactly once.
        editor.awaitCommandsAvailable { notifications.incrementAndGet() }.use {
            check(notifications.get() == 2)
        }
        release = CountDownLatch(1)
        val heldAgain = hold()
        check(display.submit<Boolean> { editor.captureDisplayScene(admission) == null }.get(2, TimeUnit.SECONDS))
        admission.close()
        release.countDown(); heldAgain.get(5, TimeUnit.SECONDS)
        check(notifications.get() == 2) { "Retired admission requested a frame" }
        check(admission.snapshot().closed && !admission.snapshot().waiting)
        check(editor.trySerialized { check(editor.canvasWidth == 32) })
        return "command-contention-nonblocking; unlock-wakeup; registration-recheck; retirement-cancels"
    } finally {
        release.countDown()
        admission.close()
        owner.shutdown(); display.shutdown()
        check(owner.awaitTermination(10, TimeUnit.SECONDS))
        check(display.awaitTermination(10, TimeUnit.SECONDS))
        editor.close()
    }
}


internal fun displayCommandGateSmoke(instrumentation: Instrumentation): String {
    val contract = displayCommandGateContractSmoke()
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val editor = NativeEditor.createWithLedgerForTest(64, ResourceBudgetLedger(64L * 1024 * 1024))
    val owner = Executors.newSingleThreadExecutor()
    val watchdog = Executors.newSingleThreadScheduledExecutor()
    val release = CountDownLatch(1)
    val entered = CountDownLatch(1)
    val watchdogReleases = AtomicInteger()
    val failures = CopyOnWriteArrayList<String>()
    val evidence = JSONObject().put("contract", contract)
    val evidenceFile = File(instrumentation.targetContext.filesDir, "display-command-gate.json")
    lateinit var view: CanvasView
    lateinit var overlay: FrameLayout
    var attached = false
    var paused = false
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun gpu(): GpuStats {
        val done = CountDownLatch(1)
        var value: GpuStats? = null
        view.debugGpuStats { value = it; done.countDown() }
        check(done.await(5, TimeUnit.SECONDS)) { "GL statistics did not respond" }
        return checkNotNull(value)
    }
    fun awaitFrame(after: Long, generation: Long? = null): GpuStats {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            val value = gpu()
            if (value.currentContext && value.completedFrames > after &&
                (generation == null || value.sceneGeneration == generation)) return value
            Thread.sleep(20)
        } while (System.nanoTime() < until)
        error("Canvas did not repaint after command release")
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
        val warm = awaitFrame(0)
        val before = view.debugDisplaySceneAdmission()
        val held = owner.submit {
            editor.serialized {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Lifecycle test command timed out" }
                editor.setResolution(144.0)
            }
        }
        check(entered.await(5, TimeUnit.SECONDS))
        main { view.requestRender() }
        // Allow the real renderer to encounter contention. Old blocking code
        // reaches onPause with GL parked behind this gate; the watchdog frees it.
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
        while (view.debugDisplaySceneAdmission().deferredAttempts == before.deferredAttempts && System.nanoTime() < until)
            Thread.sleep(10)
        val guard = watchdog.schedule({ watchdogReleases.incrementAndGet(); release.countDown() }, 3, TimeUnit.SECONDS)
        val start = System.nanoTime()
        main { view.onPause(); paused = true }
        val pauseMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        guard.cancel(false)
        evidence.put("pauseMillis", pauseMillis).put("watchdogReleases", watchdogReleases.get())
            .put("deferredAttempts", view.debugDisplaySceneAdmission().deferredAttempts - before.deferredAttempts)
        check(pauseMillis < 1500 && watchdogReleases.get() == 0) { "onPause waited for the editor command: ${pauseMillis}ms" }
        check(view.debugDisplaySceneAdmission().waiting) { "Command contention did not register a display retry" }
        release.countDown(); held.get(5, TimeUnit.SECONDS)
        val generation = checkNotNull(editor.captureDisplayScene()).use { it.generation() }
        main { view.onResume(); paused = false }
        val repainted = awaitFrame(warm.completedFrames, generation)
        check(failures.isEmpty()) { "Canvas failures: $failures" }
        evidence.put("passed", true).put("repaintGeneration", repainted.sceneGeneration)
            .put("repaintFrame", repainted.completedFrames).put("wakeRequests", view.debugDisplaySceneAdmission().wakeRequests)
        return "GL pause returned in ${pauseMillis}ms under command contention; resume repainted generation $generation. Evidence=${evidenceFile.name}"
    } catch (failure: Throwable) {
        evidence.put("passed", false).put("error", failure.toString())
        throw failure
    } finally {
        release.countDown()
        watchdog.shutdownNow(); owner.shutdown()
        check(owner.awaitTermination(10, TimeUnit.SECONDS))
        if (attached) main {
            if (paused) view.onResume()
            view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        editor.close()
        main { activity.finish() }
        instrumentation.waitForIdleSync()
        evidenceFile.writeText(evidence.toString(2))
    }
}
