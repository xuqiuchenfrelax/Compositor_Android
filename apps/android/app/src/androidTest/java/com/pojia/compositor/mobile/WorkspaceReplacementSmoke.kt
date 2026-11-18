package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.opengl.EGL14
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/** Actual JNI documents, private recovery files and paused GLSurfaceView queues exercise replacement. */
internal fun workspaceReplacementSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val root = File(context.cacheDir, "workspace-replacement-${System.nanoTime()}").apply { check(mkdirs()) }
    val workspaces = mutableListOf<AndroidWorkspace>()
    fun opened(directory: File): AndroidWorkspace {
        val workspace = AndroidWorkspace(directory)
        workspaces += workspace
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(10)
        check(!workspace.snapshot().loading)
        return workspace
    }
    fun image(editor: NativeEditor): ByteArray {
        val pixels = ByteBuffer.allocateDirect(8 * 8 * 4)
        check(editor.draw(8, 8, 0.0, 0.0, editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), pixels))
        return ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
    }
    fun install(workspace: AndroidWorkspace, fault: RecoveryFault = RecoveryFault.NONE) =
        workspace.installCandidate("internal-test", DirtyDisposition.DISCARD, fault) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS)
    try {
        val folder = File(root, "main")
        val workspace = opened(folder)
        var old = checkNotNull(workspace.snapshot().editor)
        val original = old.state()
        val pointer = File(folder, "active.json").readBytes()
        val oldTarget = checkNotNull(WorkspaceRecoveryStore(folder).read())
        val oldFile = WorkspaceRecoveryStore(folder).checkpoint(oldTarget)
        val loaded = AtomicBoolean(false)
        val rejected = workspace.installCandidate("unassociated") { loaded.set(true); NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS)
        check(!rejected.installed && !loaded.get() && workspace.snapshot().editor === old)
        check(old.state() == original && File(folder, "active.json").readBytes().contentEquals(pointer))

        val beforeUnsupported = workspace.snapshot()
        val beforeUnsupportedImage = image(old)
        val beforeUnsupportedCheckpoint = oldFile.readBytes()
        for (smoothing in listOf(true, false)) {
            val shape = File(root, "unsupported-shape-$smoothing.comp")
            copyProjectAssets(instrumentation.context.assets, "shape-smoothing-$smoothing.comp", shape)
            val failed = workspace.installProjectCandidate(context, shape, 64L * 1024 * 1024,
                32L * 1024 * 1024, "unsupported-shape-$smoothing", DirtyDisposition.DISCARD)
                .result.get(10, TimeUnit.SECONDS)
            check(!failed.installed && failed.reason == ProjectImportException(5).message)
            check(workspace.snapshot() == beforeUnsupported && workspace.snapshot().editor === old)
            check(old.state() == original && image(old).contentEquals(beforeUnsupportedImage))
            check(File(folder, "active.json").readBytes().contentEquals(pointer) &&
                oldFile.readBytes().contentEquals(beforeUnsupportedCheckpoint))
            check(WorkspaceRecoveryStore(folder).read() == oldTarget)
        }

        // A new committed edit while decoding invalidates even an explicit discard request.
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val conflict = workspace.installCandidate("conflict", DirtyDisposition.DISCARD) {
            entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)); NativeEditor(8)
        }
        check(entered.await(10, TimeUnit.SECONDS))
        old.addLayer(); val edited = old.state()
        resume.countDown()
        check(!conflict.result.get(10, TimeUnit.SECONDS).installed && workspace.snapshot().editor === old)
        check(old.state() == edited && File(folder, "active.json").readBytes().contentEquals(pointer))
        workspace.flushAsync().get(10, TimeUnit.SECONDS)

        val oldLease = checkNotNull(workspace.lease(old))
        val staleCommit = checkNotNull(old.committed)
        val oldScene = checkNotNull(old.captureScene())
        val held = ByteBuffer.allocateDirect(8 * 8 * 4)
        oldScene.draw(8, 8, Viewport(0.0, 0.0, 2048.0, 2048.0), held)
        val heldPixels = ByteArray(held.capacity()).also { held.rewind(); held.get(it) }
        val layer = old.state().layers.last().id
        val committedPixels = image(old)
        old.startStroke(layer, 400f, false)
        old.samples(doubleArrayOf(1152.0, 1152.0, 1.0, 0.0))
        check(!image(old).contentEquals(committedPixels))

        // Hold main callbacks, queue an old completion, then publish a replacement before releasing main.
        val mainEntered = CountDownLatch(1); val mainResume = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { mainEntered.countDown(); mainResume.await(20, TimeUnit.SECONDS) }
        check(mainEntered.await(10, TimeUnit.SECONDS))
        lateinit var next: WorkspaceState
        val done = AtomicBoolean(false); val ioEntered = CountDownLatch(1)
        val queuedWork = AtomicBoolean(false)
        try {
            workspace.runIo({ ioEntered.countDown(); 1 }) { done.set(true) }
            check(ioEntered.await(10, TimeUnit.SECONDS))
            val candidateEntered = CountDownLatch(1); val candidateResume = CountDownLatch(1)
            val replacement = workspace.installCandidate("new-generation", DirtyDisposition.DISCARD) {
                check(image(old).contentEquals(committedPixels)) { "Replacement did not cancel the provisional stroke" }
                candidateEntered.countDown(); check(candidateResume.await(10, TimeUnit.SECONDS)); NativeEditor(8)
            }
            check(candidateEntered.await(10, TimeUnit.SECONDS))
            workspace.runIo(checkNotNull(workspace.token(old)), { queuedWork.set(true) }) { done.set(true) }
            candidateResume.countDown()
            val installed = replacement.result.get(10, TimeUnit.SECONDS)
            check(installed.installed && !installed.durabilityUncertain) { "Replacement result: $installed" }
            next = workspace.snapshot()
            check(next.generation == oldTarget.generation + 1 && next.projectBaseline == next.currentRevision && !next.projectDirty)
            check(next.sourceIdentity == "new-generation" && next.savedRevision == next.currentRevision)
            check(!oldLease.execute { old.addLayer() } && !oldLease.isCurrent())
            staleCommit(9999)
            check(workspace.snapshot() == next) { "Retired commit callback changed replacement state" }
        } finally { mainResume.countDown() }
        instrumentation.waitForIdleSync()
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        check(!done.get() && !queuedWork.get()) { "Old IO or late completion ran against a new generation" }
        check(old.state().layers.isEmpty())
        held.rewind(); oldScene.draw(8, 8, Viewport(0.0, 0.0, 2048.0, 2048.0), held)
        val afterClose = ByteArray(held.capacity()).also { held.rewind(); held.get(it) }
        check(afterClose.contentEquals(heldPixels)); oldScene.close()
        check(oldFile.exists()) { "Retired recovery was removed while old scene references existed" }

        check(workspace.installCandidate("clean-default") { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed) { "Clean project unexpectedly required discard" }
        old = checkNotNull(workspace.snapshot().editor)
        old.addLayer()
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        check(workspace.snapshot().projectDirty && workspace.snapshot().savedRevision == old.currentRevision()) {
            "Recovery autosave cleared project dirty state"
        }
        check(!workspace.installCandidate("dirty") { NativeEditor(8) }.result.get(10, TimeUnit.SECONDS).installed)
        val beforeFailure = old.state(); val beforePointer = File(folder, "active.json").readBytes()
        val beforeFile = WorkspaceRecoveryStore(folder).checkpoint(checkNotNull(WorkspaceRecoveryStore(folder).read())).readBytes()
        check(!install(workspace, RecoveryFault.BEFORE_RENAME).installed)
        check(workspace.snapshot().editor === old && old.state() == beforeFailure)
        check(File(folder, "active.json").readBytes().contentEquals(beforePointer))
        check(WorkspaceRecoveryStore(folder).checkpoint(checkNotNull(WorkspaceRecoveryStore(folder).read())).readBytes().contentEquals(beforeFile))
        val uncertain = install(workspace, RecoveryFault.AFTER_RENAME)
        check(uncertain.installed && uncertain.durabilityUncertain && workspace.snapshot().recoveryDurabilityUncertain)
        val finalIdentity = checkNotNull(workspace.snapshot().editor).state().document.uuid
        check(old.state().layers.isEmpty() && oldFile.exists())
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        val restarted = opened(folder)
        check(checkNotNull(restarted.snapshot().editor).state().document.uuid == finalIdentity)
        check(restarted.snapshot().sourceIdentity == "internal-test" && !restarted.snapshot().projectDirty)
        restarted.closeAsync().get(10, TimeUnit.SECONDS)

        // Phase cancellation and close discard a decoder result without claiming to interrupt native decoding.
        for (close in listOf(false, true)) {
            val current = opened(File(root, "cancel-$close"))
            val before = checkNotNull(current.snapshot().editor)
            val bytes = File(root, "cancel-$close/active.json").readBytes()
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            var decoded: NativeEditor? = null
            val operation = current.installCandidate("cancel", DirtyDisposition.DISCARD) {
                started.countDown(); check(release.await(10, TimeUnit.SECONDS)); NativeEditor(8).also { decoded = it }
            }
            check(started.await(10, TimeUnit.SECONDS))
            val closing = if (close) current.closeAsync() else null
            if (!close) operation.cancel()
            release.countDown()
            check(!operation.result.get(10, TimeUnit.SECONDS).installed)
            check(checkNotNull(decoded).state().layers.isEmpty())
            check(File(root, "cancel-$close/active.json").readBytes().contentEquals(bytes))
            if (close) closing!!.get(10, TimeUnit.SECONDS) else check(current.snapshot().editor === before)
        }

        for (inside in listOf(false, true)) for (closeAtGate in listOf(false, true)) {
            val folderAtGate = File(root, "publication-$inside-$closeAtGate")
            val current = opened(folderAtGate)
            val before = checkNotNull(current.snapshot().editor)
            val lease = checkNotNull(current.lease(before))
            val beforePointer = File(folderAtGate, "active.json").readBytes()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val pause = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); Unit }
            val probe = if (inside) PublicationProbe(insideGate = pause) else PublicationProbe(beforeGate = pause)
            val operation = current.installCandidate("publication-gate", DirtyDisposition.DISCARD, probe = probe) { NativeEditor(8) }
            check(entered.await(10, TimeUnit.SECONDS))
            if (inside) {
                val requested = CountDownLatch(1); val returned = CountDownLatch(1)
                val cancelThread = Thread {
                    requested.countDown()
                    if (closeAtGate) current.closeAsync().get(10, TimeUnit.SECONDS) else operation.cancel()
                    returned.countDown()
                }.apply { start() }
                check(requested.await(10, TimeUnit.SECONDS))
                try {
                    check(!returned.await(50, TimeUnit.MILLISECONDS)) { "Cancellation crossed an in-progress pointer publication" }
                    check(lease.isCurrent()) { "Close revoked the old generation inside the publication gate" }
                } finally { release.countDown() }
                check(operation.result.get(10, TimeUnit.SECONDS).installed)
                cancelThread.join(10_000); check(returned.count == 0L)
                check(current.snapshot().editor !== before)
                if (closeAtGate) {
                    val recovered = opened(folderAtGate)
                    check(recovered.snapshot().sourceIdentity == "publication-gate")
                }
            } else {
                val closing = if (closeAtGate) current.closeAsync() else null
                if (!closeAtGate) operation.cancel()
                release.countDown()
                check(!operation.result.get(10, TimeUnit.SECONDS).installed)
                closing?.get(10, TimeUnit.SECONDS)
                check(File(folderAtGate, "active.json").readBytes().contentEquals(beforePointer))
                if (!closeAtGate) check(current.snapshot().editor === before)
            }
        }

        // Consume a real .comp through the public internal install API, then cold-recover its exact identity.
        val staging = File(root, "desktop.comp")
        fun copyAsset(path: String, file: File) {
            val children = instrumentation.context.assets.list(path).orEmpty()
            if (children.isEmpty()) instrumentation.context.assets.open(path).use { input ->
                file.parentFile!!.mkdirs(); file.outputStream().use { input.copyTo(it) }
            } else { file.mkdirs(); children.forEach { copyAsset("$path/$it", File(file, it)) } }
        }
        copyAsset("nearest-editable.comp", staging)
        val realFolder = File(root, "real")
        val real = opened(realFolder)
        val realResult = real.installProjectCandidate(context, staging, 64L * 1024 * 1024,
            32L * 1024 * 1024, "desktop-fixture", DirtyDisposition.DISCARD).result.get(10, TimeUnit.SECONDS)
        check(realResult.installed)
        val realState = checkNotNull(real.snapshot().editor).state()
        check(realState.revision == 0L && !realState.undo && !realState.redo)
        real.closeAsync().get(10, TimeUnit.SECONDS)
        val realRestart = opened(realFolder)
        check(checkNotNull(realRestart.snapshot().editor).state().copy(selectedLayerActions = null) == realState.copy(selectedLayerActions = null))
        realRestart.closeAsync().get(10, TimeUnit.SECONDS)

        val goodPointer = File(realFolder, "active.json").readText()
        for (kind in listOf("version", "crc", "escape", "identity", "limit", "baseline")) {
            val badFolder = File(root, "bad-$kind").apply { mkdirs() }
            realFolder.listFiles()!!.forEach { it.copyTo(File(badFolder, it.name)) }
            val envelope = JSONObject(goodPointer)
            val payload = JSONObject(envelope.getString("payload"))
            when (kind) {
                "version" -> envelope.put("version", 3)
                "crc" -> envelope.put("crc32", -1)
                "escape" -> payload.put("checkpoint", "../current.checkpoint")
                "identity" -> payload.put("documentUuid", UUID.randomUUID().toString())
                "limit" -> payload.put("sourceIdentity", "x".repeat(5000))
                "baseline" -> payload.put("projectBaseline", Long.MAX_VALUE)
            }
            if (kind in listOf("escape", "identity", "limit", "baseline")) {
                val text = payload.toString(); envelope.put("payload", text)
                envelope.put("crc32", CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }.value)
            }
            val file = File(badFolder, "active.json").apply { writeText(envelope.toString()) }
            val preserved = file.readBytes()
            val bad = opened(badFolder)
            check(bad.snapshot().editor == null && bad.snapshot().recoveryError != null)
            check(file.readBytes().contentEquals(preserved))
            bad.closeAsync().get(10, TimeUnit.SECONDS)
        }
        pausedReplacementSmoke(instrumentation)
        return "Workspace replacement passed: explicit dirty disposition, v14 shape smoothing true/false refusal preserves old editor/generation/pixels/recovery pointer/checkpoint, old-edit conflicts, stroke cancellation, generation leases, queued old IO/stale done/commit, retained scene after close, pre-rename preservation, post-rename durability uncertainty/cold recovery, phase cancellation/close during decode, real .comp install, pointer version/CRC/limits/path/identity rejection and paused GL plus retained command queue rejection before/after view retirement, one retained commit before replacement"
    } finally {
        workspaces.forEach { it.closeAsync().get(10, TimeUnit.SECONDS) }
        root.deleteRecursively()
    }
}

private fun pausedReplacementSmoke(instrumentation: Instrumentation) {
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
    val old = checkNotNull(activity.retainedEditorForTest())
    val block = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
    val commandBlock = CountDownLatch(1); val commandRelease = CountDownLatch(1)
    try {
        instrumentation.runOnMainSync { view.onPause() }
        view.queueEvent {
            check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT)
            block.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }
        check(block.await(10, TimeUnit.SECONDS))
        old.commandDispatcher.enqueueBarrierForTest {
            commandBlock.countDown(); check(commandRelease.await(10, TimeUnit.SECONDS))
        }
        check(commandBlock.await(10, TimeUnit.SECONDS))
        // Submission updates Compose state, so the fixture uses the UI thread and checks acceptance once.
        instrumentation.runOnMainSync { check(view.command { calls.incrementAndGet(); old.addLayer() }) }
        val before = checkNotNull(workspace.token(old))
        val rejectedLoad = AtomicInteger()
        val rejected = workspace.installCandidate("pending-paused-gl", DirtyDisposition.DISCARD) {
            rejectedLoad.incrementAndGet(); NativeEditor(8)
        }.result.get(10, TimeUnit.SECONDS)
        check(!rejected.installed && rejected.reason == PENDING_EDIT_MESSAGE && rejectedLoad.get() == 0)
        check(old.hasPendingEdits && calls.get() == 0 && old.currentRevision() == before.revision)
        check(checkNotNull(workspace.token(old)).commandEpoch == before.commandEpoch && !workspace.snapshot().resourceGrowthFrozen)
        // Replacement cannot silently discard an accepted edit, including after view retirement.
        instrumentation.runOnMainSync { view.retire() }
        check(old.hasPendingEdits && calls.get() == 0)
        val afterRetire = workspace.installCandidate("retired-pending", DirtyDisposition.DISCARD) {
            rejectedLoad.incrementAndGet(); NativeEditor(8)
        }.result.get(10, TimeUnit.SECONDS)
        check(!afterRetire.installed && afterRetire.reason == PENDING_EDIT_MESSAGE && rejectedLoad.get() == 0)
        commandRelease.countDown()
        old.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        check(calls.get() == 1 && old.currentRevision() == before.revision + 1 && !old.hasPendingEdits)
        check(workspace.installCandidate("paused-gl", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed) { "Installation waited for paused GL commands" }
        check(calls.get() == 1 && old.state().layers.isEmpty())
    } finally {
        commandRelease.countDown()
        release.countDown()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
    }
    check(calls.get() == 1) { "Accepted retained command was lost or duplicated" }
}
