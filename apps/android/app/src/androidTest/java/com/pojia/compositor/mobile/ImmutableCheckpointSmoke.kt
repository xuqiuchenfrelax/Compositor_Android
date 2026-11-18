package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises real JNI writes and Java publication faults; synthetic faults are not power loss. */
internal fun immutableCheckpointSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val root = File(context.cacheDir, "immutable-checkpoint-${UUID.randomUUID()}").apply { check(mkdir()) }
    val workspaces = mutableListOf<AndroidWorkspace>()
    val releases = mutableListOf<CountDownLatch>()
    val faults = AtomicReference<Pair<RecoverySaveKind, RecoveryFault>?>()
    val attempts = mutableListOf<Triple<RecoverySaveKind, RecoveryTarget, Long>>()
    val hookGate = Any()
    val blockKind = AtomicReference<RecoverySaveKind?>()
    val reached = AtomicReference<CountDownLatch?>()
    val release = AtomicReference<CountDownLatch?>()
    val afterWriteFault = AtomicReference<RecoverySaveKind?>()
    val hooks = RecoveryPublicationHooks(
        afterCheckpoint = { kind, target, _, revision ->
            synchronized(hookGate) { attempts.add(Triple(kind, target, revision)) }
            check(kind != afterWriteFault.get()) { "Synthetic Java after-checkpoint fault" }
        },
        pointerFault = { kind, _ -> faults.get()?.takeIf { it.first == kind }?.second ?: RecoveryFault.NONE },
        publicationProbe = { kind, _ -> if (kind == blockKind.get()) PublicationProbe(beforeGate = {
            reached.get()!!.countDown(); check(release.get()!!.await(10, TimeUnit.SECONDS))
        }) else null })
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    fun opened(folder: File, ownHooks: RecoveryPublicationHooks? = hooks): AndroidWorkspace {
        val workspace = AndroidWorkspace(folder, recoveryHooks = ownHooks).also(workspaces::add)
        waitFor("Immutable checkpoint workspace did not open") { !workspace.snapshot().loading }
        check(workspace.snapshot().editor != null && workspace.snapshot().recoveryError == null)
        return workspace
    }
    fun image(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(editor.canvasWidth * editor.canvasHeight * 4)
        check(editor.draw(editor.canvasWidth, editor.canvasHeight, 0.0, 0.0,
            editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun fixture(): NativeEditor = NativeEditor(8, addDefaultLayers = false).also { editor ->
        val pixels = ByteArray(256) { index -> when (index % 4) { 0 -> (index / 4).toByte(); 1 -> 96; 2 -> -64; else -> -1 } }
        editor.importPixels(8, 8, ByteBuffer.allocateDirect(256).apply { put(pixels); rewind() })
    }
    fun current(folder: File) = checkNotNull(WorkspaceRecoveryStore(folder).read())
    fun files(folder: File) = folder.listFiles()!!.filter { it.name.endsWith(".checkpoint") }.associate { it.name to it.readBytes() }
    fun preserved(folder: File, before: Map<String, ByteArray>) = before.forEach { (name, bytes) ->
        check(File(folder, name).isFile && File(folder, name).readBytes().contentEquals(bytes)) { "Published recovery file was overwritten or removed: $name" }
    }
    fun cold(folder: File, target: RecoveryTarget, expected: EditorState, pixels: ByteArray) {
        NativeEditor.load(WorkspaceRecoveryStore(folder).checkpoint(target)).use { editor ->
            check(editor.state().copy(selectedLayerActions = null) == expected.copy(undo = false, redo = false, selectedLayerActions = null) && image(editor).contentEquals(pixels))
        }
    }
    fun failed(action: () -> Unit) { check(runCatching(action).isFailure) { "Injected pointer failure was reported as durable" } }
    try {
        val folder = File(root, "ordinary")
        val workspace = opened(folder)
        check(workspace.installCandidate("fixture", DirtyDisposition.DISCARD, candidate = ::fixture)
            .result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        val generation = workspace.snapshot().generation
        val first = current(folder)
        var retained = files(folder)
        for (name in listOf("First immutable edit", "Second immutable edit")) {
            val oldPointer = File(folder, "active.json").readBytes()
            val oldTarget = current(folder)
            editor.renameLayer(editor.state().layers.single().id, name)
            check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == editor.currentRevision())
            val next = current(folder)
            check(next.fileName != oldTarget.fileName && next.generation == generation && next.documentUuid == first.documentUuid)
            check(!File(folder, "active.json").readBytes().contentEquals(oldPointer))
            preserved(folder, retained); retained = files(folder)
            cold(folder, next, editor.state(), image(editor))
        }
        editor.renameLayer(editor.state().layers.single().id, "Autosaved immutable edit")
        waitFor("Autosave did not publish its fresh recovery") { workspace.snapshot().savedRevision == editor.currentRevision() }
        preserved(folder, retained); retained = files(folder)
        check(synchronized(hookGate) { attempts.any { it.first == RecoverySaveKind.AUTOSAVE } })

        // The real JNI writer completes before this Java hook throws; this does not inject native I/O failure.
        val beforeWritePointer = File(folder, "active.json").readBytes()
        val beforeWriteTarget = current(folder)
        val beforeWriteRevision = workspace.snapshot().savedRevision
        editor.renameLayer(editor.state().layers.single().id, "Java after-checkpoint failure")
        afterWriteFault.set(RecoverySaveKind.FLUSH)
        failed { workspace.flushAsync().get(10, TimeUnit.SECONDS) }
        afterWriteFault.set(null)
        val writtenBeforeThrow = synchronized(hookGate) { attempts.last { it.first == RecoverySaveKind.FLUSH }.second }
        check(writtenBeforeThrow.fileName != beforeWriteTarget.fileName)
        check(File(folder, "active.json").readBytes().contentEquals(beforeWritePointer))
        check(workspace.snapshot().savedRevision == beforeWriteRevision && current(folder) == beforeWriteTarget)
        preserved(folder, retained)
        cold(folder, writtenBeforeThrow, editor.state(), image(editor))
        val beforeWriteRetryBytes = files(folder)
        check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == editor.currentRevision())
        check(current(folder).fileName != writtenBeforeThrow.fileName)
        preserved(folder, beforeWriteRetryBytes)
        retained = files(folder)

        for (fault in listOf(RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            val before = workspace.snapshot()
            val oldTarget = current(folder)
            val pointer = File(folder, "active.json").readBytes()
            faults.set(RecoverySaveKind.AUTOSAVE to fault)
            editor.renameLayer(editor.state().layers.single().id, "Autosave pointer $fault")
            waitFor("Faulted autosave did not finish") { workspace.snapshot().saveError != null }
            val written = synchronized(hookGate) { attempts.last { it.first == RecoverySaveKind.AUTOSAVE }.second }
            check(written.fileName != oldTarget.fileName)
            preserved(folder, retained)
            cold(folder, written, editor.state(), image(editor))
            if (fault == RecoveryFault.BEFORE_RENAME) {
                check(current(folder) == oldTarget && File(folder, "active.json").readBytes().contentEquals(pointer))
                check(workspace.snapshot().savedRevision == before.savedRevision)
            } else {
                check(current(folder) == written && workspace.snapshot().savedRevision == editor.currentRevision())
                check(workspace.snapshot().recoveryDurabilityUncertain)
            }
            faults.set(null)
            val retryFiles = files(folder)
            check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == editor.currentRevision())
            check(current(folder).fileName != written.fileName)
            check(!workspace.snapshot().recoveryDurabilityUncertain && workspace.snapshot().saveError == null)
            preserved(folder, retryFiles); retained = files(folder)
        }

        for (fault in listOf(RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            val oldTarget = current(folder)
            val oldPointer = File(folder, "active.json").readBytes()
            val oldSavedRevision = workspace.snapshot().savedRevision
            editor.renameLayer(editor.state().layers.single().id, "Pointer fault $fault")
            faults.set(RecoverySaveKind.FLUSH to fault)
            failed { workspace.flushAsync().get(10, TimeUnit.SECONDS) }
            preserved(folder, retained)
            val written = synchronized(hookGate) { attempts.last { it.first == RecoverySaveKind.FLUSH }.second }
            check(written.fileName != oldTarget.fileName && File(folder, written.fileName).exists())
            if (fault == RecoveryFault.BEFORE_RENAME) {
                check(File(folder, "active.json").readBytes().contentEquals(oldPointer) && current(folder) == oldTarget)
                check(workspace.snapshot().savedRevision == oldSavedRevision)
            } else {
                check(current(folder) == written && workspace.snapshot().savedRevision == editor.currentRevision())
                check(workspace.snapshot().recoveryDurabilityUncertain && workspace.snapshot().saveError != null)
                cold(folder, written, editor.state(), image(editor))
            }
            faults.set(null)
            val beforeRetry = current(folder)
            val beforeRetryBytes = files(folder)
            check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == editor.currentRevision())
            check(current(folder).fileName != beforeRetry.fileName)
            check(!workspace.snapshot().recoveryDurabilityUncertain && workspace.snapshot().saveError == null)
            preserved(folder, beforeRetryBytes); retained = files(folder)
        }

        for (fault in listOf(RecoveryFault.NONE, RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            editor.renameLayer(editor.state().layers.single().id, "Project save $fault")
            val before = workspace.snapshot()
            val pointer = File(folder, "active.json").readBytes()
            val oldFiles = files(folder)
            val output = File(root, "project-$fault.comp")
            val result = workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
                lease.saveProject(context, output, false, 64 * 1024 * 1024L,
                    lease.memory.totalBytes + 1024 * 1024, "source-$fault", fault)
            }, {}).get(10, TimeUnit.SECONDS)
            check(result.committed && result.durabilityConfirmed && output.isDirectory)
            preserved(folder, oldFiles)
            val after = workspace.snapshot()
            if (fault == RecoveryFault.NONE) {
                check(result.baselineUpdated && after.projectBaseline == result.capturedRevision && after.sourceIdentity == "source-$fault")
                check(!after.projectDirty && current(folder).projectBaseline == result.capturedRevision)
            } else {
                check(!result.baselineUpdated && after.projectBaseline == before.projectBaseline && after.sourceIdentity == before.sourceIdentity && after.projectDirty)
                if (fault == RecoveryFault.BEFORE_RENAME) check(File(folder, "active.json").readBytes().contentEquals(pointer))
                else {
                    check(result.metadataDurabilityUncertain && after.recoveryDurabilityUncertain)
                    check(current(folder).projectBaseline == result.capturedRevision && current(folder).sourceIdentity == "source-$fault")
                    cold(folder, current(folder), editor.state(), image(editor))
                    val uncertainTarget = current(folder)
                    val uncertainFiles = files(folder)
                    val unchangedRevision = editor.currentRevision()
                    check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == unchangedRevision)
                    val retried = workspace.snapshot()
                    val retryTarget = current(folder)
                    check(retryTarget.fileName != uncertainTarget.fileName)
                    check(retryTarget.projectBaseline == before.projectBaseline && retryTarget.sourceIdentity == before.sourceIdentity)
                    check(retried.projectBaseline == before.projectBaseline && retried.sourceIdentity == before.sourceIdentity && retried.projectDirty)
                    check(!retried.recoveryDurabilityUncertain && retried.saveError == null)
                    preserved(folder, uncertainFiles)
                    cold(folder, retryTarget, editor.state(), image(editor))
                }
            }
        }

        for (fault in listOf(RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            val closeFolder = File(root, "close-$fault")
            val closing = opened(closeFolder)
            check(closing.installCandidate("close-fixture", DirtyDisposition.DISCARD, candidate = ::fixture).result.get(10, TimeUnit.SECONDS).installed)
            val active = checkNotNull(closing.snapshot().editor)
            val oldTarget = current(closeFolder)
            val pointer = File(closeFolder, "active.json").readBytes()
            val bytes = files(closeFolder)
            active.renameLayer(active.state().layers.single().id, "Final close $fault")
            val expected = active.state(); val pixels = image(active)
            faults.set(RecoverySaveKind.CLOSE to fault)
            failed { closing.closeAsync().get(10, TimeUnit.SECONDS) }
            faults.set(null)
            check(active.state().layers.isEmpty())
            preserved(closeFolder, bytes)
            val written = synchronized(hookGate) { attempts.last { it.first == RecoverySaveKind.CLOSE }.second }
            check(written != oldTarget && File(closeFolder, written.fileName).isFile)
            cold(closeFolder, written, expected, pixels)
            if (fault == RecoveryFault.BEFORE_RENAME) check(File(closeFolder, "active.json").readBytes().contentEquals(pointer))
            else check(current(closeFolder) == written)
        }

        // Deterministic source-after-rename -> final close at the same revision, without a timing race.
        val sourceCloseFolder = File(root, "uncertain-source-close")
        val sourceClosing = opened(sourceCloseFolder)
        check(sourceClosing.installCandidate("old-source", DirtyDisposition.DISCARD, candidate = ::fixture)
            .result.get(10, TimeUnit.SECONDS).installed)
        val sourceEditor = checkNotNull(sourceClosing.snapshot().editor)
        val localMetadata = sourceClosing.snapshot()
        sourceEditor.renameLayer(sourceEditor.state().layers.single().id, "Uncertain source then close")
        val sourceState = sourceEditor.state(); val sourcePixels = image(sourceEditor)
        val sourceResult = sourceClosing.withFrozenIo(checkNotNull(sourceClosing.token()), { lease ->
            lease.saveProject(context, File(root, "uncertain-source-close.comp"), false, 64 * 1024 * 1024L,
                lease.memory.totalBytes + 1024 * 1024, "unconfirmed-new-source", RecoveryFault.AFTER_RENAME)
        }, {}).get(10, TimeUnit.SECONDS)
        check(sourceResult.committed && sourceResult.durabilityConfirmed && !sourceResult.baselineUpdated && sourceResult.metadataDurabilityUncertain)
        val uncertainSourceTarget = current(sourceCloseFolder)
        check(uncertainSourceTarget.projectBaseline == sourceState.revision && uncertainSourceTarget.sourceIdentity == "unconfirmed-new-source")
        check(sourceClosing.snapshot().savedRevision == sourceState.revision && sourceClosing.snapshot().recoveryDurabilityUncertain)
        cold(sourceCloseFolder, uncertainSourceTarget, sourceState, sourcePixels)
        val sourceFiles = files(sourceCloseFolder)
        sourceClosing.closeAsync().get(10, TimeUnit.SECONDS)
        val finalSourceTarget = current(sourceCloseFolder)
        check(finalSourceTarget.fileName != uncertainSourceTarget.fileName && finalSourceTarget.generation == uncertainSourceTarget.generation)
        check(finalSourceTarget.projectBaseline == localMetadata.projectBaseline && finalSourceTarget.sourceIdentity == localMetadata.sourceIdentity)
        check(!sourceClosing.snapshot().recoveryDurabilityUncertain && sourceClosing.snapshot().saveError == null)
        preserved(sourceCloseFolder, sourceFiles)
        cold(sourceCloseFolder, finalSourceTarget, sourceState, sourcePixels)

        val cancelFolder = File(root, "cancel-install")
        val cancellable = opened(cancelFolder)
        val oldPointer = File(cancelFolder, "active.json").readBytes()
        val oldFiles = files(cancelFolder)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1).also(releases::add)
        reached.set(entered); release.set(resume); blockKind.set(RecoverySaveKind.CANDIDATE_INSTALL)
        val operation = cancellable.installCandidate("cancelled-fixture", DirtyDisposition.DISCARD, candidate = ::fixture)
        check(entered.await(10, TimeUnit.SECONDS)); operation.cancel(); resume.countDown()
        check(!operation.result.get(10, TimeUnit.SECONDS).installed)
        blockKind.set(null)
        check(File(cancelFolder, "active.json").readBytes().contentEquals(oldPointer))
        preserved(cancelFolder, oldFiles)
        val candidate = synchronized(hookGate) { attempts.last { it.first == RecoverySaveKind.CANDIDATE_INSTALL }.second }
        check(File(cancelFolder, candidate.fileName).exists()) { "Cancelled unpublished attempt was garbage collected" }

        val finalState = editor.state(); val finalPixels = image(editor); val finalFiles = files(folder)
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        preserved(folder, finalFiles)
        val reopened = opened(folder, null)
        check(reopened.snapshot().editor!!.state().copy(selectedLayerActions = null) == finalState.copy(undo = false, redo = false, selectedLayerActions = null))
        check(image(reopened.snapshot().editor!!).contentEquals(finalPixels))
        return "Immutable recovery passed: autosave/flush/close publish fresh filenames within unchanged generation and preserve every prior file byte; before-pointer faults preserve active pointer/revision, after-pointer synthetic sync uncertainty adopts recoverable target and same-revision retry creates a fresh file; Java after-checkpoint throw retains complete real JNI attempt; project native write/baseline/source remain truthful and uncertain source retry restores local metadata while keeping dirty; cancellation retains unpublished attempts. Cold exact UUID/revision/layers/pixels with empty history. Faults are synthetic, not native filesystem I/O or power-loss proof; no GC is exercised."
    } finally {
        releases.forEach(CountDownLatch::countDown)
        faults.set(null); blockKind.set(null); afterWriteFault.set(null)
        workspaces.forEach { runCatching { it.closeAsync().get(10, TimeUnit.SECONDS) } }
        root.deleteRecursively()
    }
}
