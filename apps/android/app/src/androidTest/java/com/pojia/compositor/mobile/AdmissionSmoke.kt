package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.opengl.EGL14
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutionException

/** Cooperative admission fences queued IO and rejects edits, unaccounted handles, and stale pickers. */
internal fun admissionSmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "admission-${System.nanoTime()}")
    val workspace = AndroidWorkspace(root)
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(10)
        val editor = checkNotNull(workspace.snapshot().editor)
        val stale = checkNotNull(workspace.token())
        editor.addLayer()
        val ran = AtomicBoolean(false)
        check(runCatching { workspace.withFrozenIo(stale, { ran.set(true) }, {}).get(10, TimeUnit.SECONDS) }.isFailure)
        check(!ran.get() && !workspace.snapshot().resourceGrowthFrozen)
        checkNotNull(editor.captureScene()).use {
            check(runCatching { workspace.withFrozenIo(checkNotNull(workspace.token()), {}, {}).get(10, TimeUnit.SECONDS) }.isFailure)
        }
        editor.captureProjectSnapshot().use {
            check(runCatching { workspace.withFrozenIo(checkNotNull(workspace.token()), {}, {}).get(10, TimeUnit.SECONDS) }.isFailure)
        }
        val queued = checkNotNull(workspace.token())
        workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
            check(workspace.snapshot().resourceGrowthFrozen && lease.memory.totalBytes >= lease.memory.tileBytes)
            check(runCatching { editor.addLayer() }.isFailure)
            check(runCatching { editor.captureScene() }.isFailure)
            check(runCatching { editor.captureProjectSnapshot() }.isFailure)
            check(runCatching { editor.save(File(root, "forbidden.checkpoint")) }.isFailure)
            check(runCatching { editor.draw(1, 1, 0.0, 0.0, 1.0, 1.0, java.nio.ByteBuffer.allocateDirect(4)) }.isFailure)
            check(!editor.renderWithGrowth { error("Frozen renderer allocated") })
            check(!workspace.execute(queued) { error("Queued edit ran") })
            check(runCatching { lease.installProjectCandidate(instrumentation.targetContext, File(root, "missing"),
                64L * 1024 * 1024, 0, "omitted", DirtyDisposition.DISCARD) }.isFailure)
        }, {}).get(10, TimeUnit.SECONDS)
        check(!workspace.snapshot().resourceGrowthFrozen)
        check(!workspace.execute(queued) { error("Old command epoch ran after release") })
        editor.addLayer()
        check(editor.renderWithGrowth {})
        check(runCatching { workspace.withFrozenIo(checkNotNull(workspace.token()), { error("worker failure") }, {})
            .get(10, TimeUnit.SECONDS) }.isFailure)
        check(!workspace.snapshot().resourceGrowthFrozen)
        editor.addLayer()
        pausedAdmissionSmoke(instrumentation)
        return "Admission: exact revision, held scene/snapshot rejection, editor/renderer/checkpoint growth gates, old token epoch rejection, accepted retained edit rejection with unchanged epoch, retirement preserves that edit until one commit and only then admits frozen IO, omitted reservation and failure cleanup"
    } finally { workspace.closeAsync().get(10, TimeUnit.SECONDS); root.deleteRecursively() }
}

private fun pausedAdmissionSmoke(instrumentation: Instrumentation) {
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun find(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    var canvas: CanvasView? = null
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (canvas == null && System.nanoTime() < deadline) {
        instrumentation.runOnMainSync { canvas = find(activity.window.decorView) }
        if (canvas == null) Thread.sleep(20)
    }
    val view = checkNotNull(canvas)
    val workspace = activity.retainedWorkspaceForTest()
    val editor = checkNotNull(activity.retainedEditorForTest())
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
    val calls = AtomicInteger()
    try {
        instrumentation.runOnMainSync { view.onPause() }
        view.queueEvent {
            check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT)
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }
        check(entered.await(10, TimeUnit.SECONDS))
        val commandEntered = CountDownLatch(1); val commandRelease = CountDownLatch(1)
        editor.commandDispatcher.enqueueBarrierForTest {
            commandEntered.countDown(); check(commandRelease.await(10, TimeUnit.SECONDS))
        }
        check(commandEntered.await(10, TimeUnit.SECONDS))
        try {
            view.command { calls.incrementAndGet(); editor.addLayer() }
            val revision = editor.currentRevision()
            val pendingToken = checkNotNull(workspace.token(editor))
            val admitted = AtomicBoolean(false)
            val pendingFailure = try {
                workspace.withFrozenIo(pendingToken, { admitted.set(true) }, {}).get(10, TimeUnit.SECONDS)
                null
            } catch (error: ExecutionException) { error.cause }
            check(pendingFailure?.message == PENDING_EDIT_MESSAGE && !admitted.get())
            check(editor.hasPendingEdits && calls.get() == 0 && editor.currentRevision() == revision)
            check(checkNotNull(workspace.token(editor)).commandEpoch == pendingToken.commandEpoch && !workspace.snapshot().resourceGrowthFrozen)
            // The native session retains accepted edits even when its originating view is retired.
            instrumentation.runOnMainSync { view.retire() }
            check(editor.hasPendingEdits && calls.get() == 0)
            val retiredFailure = try {
                workspace.withFrozenIo(checkNotNull(workspace.token()), { admitted.set(true) }, {}).get(10, TimeUnit.SECONDS)
                null
            } catch (error: ExecutionException) { error.cause }
            check(retiredFailure?.message == PENDING_EDIT_MESSAGE && !admitted.get())
            check(checkNotNull(workspace.token(editor)).commandEpoch == pendingToken.commandEpoch)
            commandRelease.countDown()
            editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
            check(calls.get() == 1 && editor.currentRevision() == revision + 1 && !editor.hasPendingEdits)
            workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
                check(!editor.renderWithGrowth { error("Frozen GL allocation") })
                check(lease.memory.revision == revision + 1)
            }, {}).get(10, TimeUnit.SECONDS)
            release.countDown()
            view.queueEvent { completed.countDown() }
            check(completed.await(10, TimeUnit.SECONDS))
            check(calls.get() == 1 && editor.currentRevision() == revision + 1) { "Accepted retained command was lost or duplicated" }
            instrumentation.runOnMainSync { view.onResume(); view.requestRender() }
        } finally { commandRelease.countDown() }
    } finally {
        release.countDown()
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
    }
}
