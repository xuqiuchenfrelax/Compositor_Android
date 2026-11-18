package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Unlike a queue barrier, this fixture holds the editor gate across real Activity recreation. */
internal fun editorRecreationHeldLockSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val release = CountDownLatch(1)
    val watchdogFired = AtomicBoolean(false)
    val watchdog = Executors.newSingleThreadScheduledExecutor()
    var workspace: AndroidWorkspace? = null
    var primaryFailure: Throwable? = null
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun canvas(): CanvasView? {
        var value: CanvasView? = null
        instrumentation.runOnMainSync { value = findCanvas(activity.window.decorView) }
        return value
    }
    val boundEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
    fun readyCanvas(expected: NativeEditor? = null): CanvasView {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            canvas()?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 &&
                (expected == null || boundEditor.get(it) === expected) }?.let { return it }
            Thread.sleep(20)
        }
        error("Recreated editor canvas did not become ready")
    }
    try {
        readyCanvas()
        val retainedWorkspace = activity.retainedWorkspaceForTest()
        workspace = retainedWorkspace
        check(retainedWorkspace.installCandidate("held-lock-recreation", DirtyDisposition.DISCARD) {
            NativeEditor(8, addDefaultLayers = false).also { it.addLayer() }
        }.result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(retainedWorkspace.snapshot().editor)
        val initial = editor.state()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (activity.retainedEditorForTest() !== editor && System.nanoTime() < deadline) Thread.sleep(20)
        check(activity.retainedEditorForTest() === editor)
        val previous = readyCanvas(editor)
        val entered = CountDownLatch(1)
        val executions = AtomicInteger()
        watchdog.schedule({
            if (release.count > 0) { watchdogFired.set(true); release.countDown() }
        }, 12, TimeUnit.SECONDS)
        var accepted = false
        instrumentation.runOnMainSync {
            accepted = previous.command {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Held editor command timed out" }
                editor.renameLayer(initial.document.activeLayerId, "Recreated retained edit")
                executions.incrementAndGet()
            }
        }
        check(accepted && entered.await(3, TimeUnit.SECONDS))
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val previousActivity = activity
        try {
            Handler(Looper.getMainLooper()).post { previousActivity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? MainActivity) {
                "Activity recreation waited for the held editor gate"
            }
        } finally { instrumentation.removeMonitor(monitor) }
        val heartbeat = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
        check(heartbeat.await(3, TimeUnit.SECONDS)) { "Recreated Activity blocked main during editor initialization" }
        check(!watchdogFired.get() && executions.get() == 0 && release.count == 1L)
        check(activity !== previousActivity && activity.retainedWorkspaceForTest() === retainedWorkspace)
        check(activity.retainedEditorForTest() === editor)
        check(canvas() == null) { "A Canvas was initialized while its editor gate was held" }
        release.countDown()
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        val next = readyCanvas(editor)
        check(next !== previous && !previous.isAttachedToWindow)
        check(executions.get() == 1 && editor.state().layers.single().name == "Recreated retained edit")
        check(!watchdogFired.get())
        return "Actual Activity recreated and main heartbeat completed while a retained command held the editor gate; loading screen deferred Canvas creation until release; retained edit executed exactly once and a new Canvas attached to the same editor."
    } catch (failure: Throwable) {
        val recorded = AssertionError("Held-lock Activity recreation failed; workspace evidence remains in ${context.filesDir}/workspace", failure)
        primaryFailure = recorded
        throw recorded
    } finally {
        release.countDown()
        watchdog.shutdownNow()
        val closeFailure = runCatching { workspace?.closeAsync()?.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        instrumentation.runOnMainSync { activity.finish() }
        if (closeFailure != null) {
            val recorded = AssertionError("Held-lock recreation workspace close failed; recovery evidence retained", closeFailure)
            primaryFailure?.addSuppressed(recorded) ?: throw recorded
        }
    }
}
