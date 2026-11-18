package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The production screen loader must yield main while a retained command owns the document. */
internal fun editorInitializationSmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "editor-initialization-${System.nanoTime()}").apply { check(mkdir()) }
    val workspace = AndroidWorkspace(File(root, "workspace"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val release = CountDownLatch(1)
    val publishedAfterCancel = AtomicBoolean(false)
    val result = CompletableFuture<EditorScreenInitial?>()
    val refreshed = CompletableFuture<EditorScreenPresentation?>()
    val cancelledFinished = CountDownLatch(1)
    var primaryFailure: Throwable? = null
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(10)
        check(!workspace.snapshot().loading)
        check(workspace.installCandidate("initialization-fixture", DirtyDisposition.DISCARD) {
            NativeEditor(8, addDefaultLayers = false).also { it.addLayer() }
        }.result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        val initial = editor.state()
        val lease = checkNotNull(workspace.lease(editor))
        val entered = CountDownLatch(1)
        check(editor.commandDispatcher.submitAction(null, null) {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Initialization fixture release timed out" }
            editor.renameLayer(initial.document.activeLayerId, "Retained initialization edit")
        })
        check(entered.await(5, TimeUnit.SECONDS))
        val loadersStarted = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            scope.launch {
                try { result.complete(loadEditorScreenInitial(editor, workspace)) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            scope.launch {
                try { refreshed.complete(loadEditorScreenPresentation(lease)) }
                catch (failure: Throwable) { refreshed.completeExceptionally(failure) }
            }
            val cancelled = scope.launch {
                loadEditorScreenInitial(editor, workspace)
                publishedAfterCancel.set(true)
            }
            cancelled.invokeOnCompletion { cancelledFinished.countDown() }
            cancelled.cancel()
            loadersStarted.countDown()
        }
        check(loadersStarted.await(3, TimeUnit.SECONDS)) { "Starting the editor loader blocked main" }
        val heartbeat = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
        check(heartbeat.await(3, TimeUnit.SECONDS)) { "Editor initialization blocked main behind a retained command" }
        check(!result.isDone && !refreshed.isDone) { "Editor published a presentation while its command was still held" }
        check(cancelledFinished.await(3, TimeUnit.SECONDS) && !publishedAfterCancel.get())
        release.countDown()
        val loaded = checkNotNull(result.get(10, TimeUnit.SECONDS))
        val updated = checkNotNull(refreshed.get(10, TimeUnit.SECONDS))
        check(updated.state == editor.state() && updated.selection == editor.presentationSelection())
        check(loaded.lease.token.editor === editor && loaded.lease.isCurrent())
        check(loaded.state == editor.state() && loaded.selection == editor.presentationSelection())
        check(loaded.state.layers.single().name == "Retained initialization edit")
        check(!publishedAfterCancel.get())
        return "Async editor initialization and presentation refresh: main heartbeat succeeds while a retained command holds the document; canceled screen loader never publishes; resumed loader returns the exact committed state and current lease."
    } catch (failure: Throwable) {
        val recorded = AssertionError("Editor initialization failed; fixture retained at ${root.absolutePath}", failure)
        primaryFailure = recorded
        throw recorded
    } finally {
        release.countDown()
        scope.cancel()
        val closeFailure = runCatching { workspace.closeAsync().get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        if (closeFailure != null) {
            val recorded = AssertionError("Workspace cleanup failed; fixture retained at ${root.absolutePath}", closeFailure)
            primaryFailure?.addSuppressed(recorded) ?: throw recorded
        } else if (primaryFailure == null) check(root.deleteRecursively()) { "Could not remove successful fixture ${root.absolutePath}" }
    }
}
