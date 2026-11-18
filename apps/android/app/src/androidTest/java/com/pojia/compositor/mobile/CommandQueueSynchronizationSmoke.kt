package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A held retained worker proves that a completed GL event does not fence an accepted ordinary command. */
internal fun commandQueueSynchronizationSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val root = File(context.cacheDir, "command-queue-${System.nanoTime()}").apply { check(mkdir()) }
    val workspace = AndroidWorkspace(File(root, "workspace"))
    val release = CountDownLatch(1)
    var view: CanvasView? = null
    var overlay: FrameLayout? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    try {
        waitUntil("Queue fixture workspace did not open") { !workspace.snapshot().loading }
        check(workspace.installCandidate("queue-fixture", DirtyDisposition.DISCARD) {
            NativeEditor(8, addDefaultLayers = false).also { it.addLayer() }
        }.result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        main {
            val container = FrameLayout(activity)
            val canvas = CanvasView(activity, editor, {}, {}, checkNotNull(workspace.lease(editor)))
            container.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            view = canvas; overlay = container
        }
        val canvas = checkNotNull(view)
        waitUntil("Queue fixture actual GL surface did not attach") {
            canvas.isAttachedToWindow && canvas.holder.surface.isValid && canvas.width > 0 && canvas.height > 0
        }
        val initial = editor.state()
        val expectedId = initial.layers.maxOf { it.id } + 1
        val entered = CountDownLatch(1)
        editor.commandDispatcher.enqueueBarrierForTest { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        check(entered.await(10, TimeUnit.SECONDS))
        val executions = AtomicInteger(); val layerId = AtomicLong()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var accepted = false
        main { accepted = canvas.command {
            executions.incrementAndGet()
            editor.addLayer(); layerId.set(editor.state().layers.last().id)
            editor.renameLayer(layerId.get(), "Queued fixture layer")
            editor.setTransform(layerId.get(), Transform64(108.0, 108.0, 4.0, 4.0))
            editor.selectLayer(layerId.get())
        } }
        check(accepted)
        val glReached = CountDownLatch(1); canvas.queueEvent { glReached.countDown() }
        check(glReached.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
        instrumentation.waitForIdleSync()
        val commandBarrier = editor.commandDispatcher.awaitIdleForTest()
        check(executions.get() == 0 && layerId.get() == 0L && editor.state() == initial)
        check(!commandBarrier.isDone && editor.pendingEditCount == 1)
        release.countDown()
        commandBarrier.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        check(executions.get() == 1 && layerId.get() == expectedId && editor.pendingEditCount == 0)
        val state = editor.state()
        check(state.layers.size == initial.layers.size + 1 && state.document.activeLayerId == expectedId)
        check(state.layers.single { it.id == expectedId }.let {
            it.name == "Queued fixture layer" && it.transform == Transform64(108.0, 108.0, 4.0, 4.0)
        })
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        val stored = WorkspaceRecoveryStore(File(root, "workspace"))
        NativeEditor.load(stored.checkpoint(checkNotNull(stored.read()))).use {
            check(it.state().copy(selectedLayerActions = null) == state.copy(undo = false, redo = false, selectedLayerActions = null))
        }
        return "Deterministic actual Canvas queue contract passed: held retained worker accepted one fixture command; GL queue and main idle completed while callback count/id remained zero and exact document was unchanged. Release plus retained completion barrier executed exactly once with exact new layer ID, metadata/selection and cold checkpoint state. One shared10s deadline fences GL and retained waits. This establishes a synchronization gap, not the cause of the earlier canonical failure."
    } finally {
        release.countDown()
        main { view?.retire(); overlay?.let { (it.parent as? ViewGroup)?.removeView(it) } }
        runCatching { workspace.closeAsync().get(10, TimeUnit.SECONDS) }
        root.deleteRecursively()
        main { activity.finish() }; instrumentation.waitForIdleSync()
    }
}
