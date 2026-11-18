package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real JNI success and filesystem refusals; post-publication syscall faults belong to the host campaign. */
internal fun classifiedCheckpointSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val root = File(context.cacheDir, "classified-checkpoint-${UUID.randomUUID()}").apply { check(mkdir()) }
    val workspaces = mutableListOf<AndroidWorkspace>()
    val blockedKind = AtomicReference<RecoverySaveKind?>()
    val blockedTarget = AtomicReference<RecoveryTarget?>()
    val pointerCalls = AtomicInteger()
    val hooks = RecoveryPublicationHooks(
        beforeCheckpoint = { kind, target, file ->
            if (kind == blockedKind.get()) {
                check(file.mkdir()) { "Could not create fixture's incompatible checkpoint destination" }
                File(file, "sentinel").writeBytes(byteArrayOf(19, 83, 42))
                blockedTarget.set(target)
            }
        },
        pointerFault = { _, _ -> pointerCalls.incrementAndGet(); RecoveryFault.NONE })
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    fun opened(folder: File): AndroidWorkspace = AndroidWorkspace(folder, recoveryHooks = hooks).also { workspace ->
        workspaces.add(workspace)
        waitFor("Classified workspace did not open") { !workspace.snapshot().loading }
        check(workspace.snapshot().editor != null && workspace.snapshot().recoveryError == null)
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(editor.canvasWidth * editor.canvasHeight * 4)
        check(editor.draw(editor.canvasWidth, editor.canvasHeight, 0.0, 0.0,
            editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun fixture(): NativeEditor = NativeEditor(8, addDefaultLayers = false).also {
        val bytes = ByteArray(256) { index -> when (index % 4) { 0 -> (index / 4).toByte(); 1 -> 72; 2 -> -32; else -> -1 } }
        it.importPixels(8, 8, ByteBuffer.allocateDirect(256).apply { put(bytes); rewind() })
    }
    fun cold(file: File, expected: EditorState, expectedPixels: ByteArray) {
        NativeEditor.load(file).use { check(it.state().copy(selectedLayerActions = null) == expected.copy(undo = false, redo = false, selectedLayerActions = null) && pixels(it).contentEquals(expectedPixels)) }
    }
    fun target(folder: File) = checkNotNull(WorkspaceRecoveryStore(folder).read())
    fun nativeRefusal(result: CheckpointWriteResult) {
        check(result.status == 7 && result.revisionCaptured && !result.destinationPublished && !result.durabilityConfirmed && !result.successful)
    }
    fun retainedBlock(folder: File) {
        val blocked = checkNotNull(blockedTarget.get())
        check(File(folder, blocked.fileName).isDirectory)
        check(File(folder, "${blocked.fileName}/sentinel").readBytes().contentEquals(byteArrayOf(19, 83, 42)))
    }
    fun install(workspace: AndroidWorkspace) {
        check(workspace.installCandidate("fixture-source", DirtyDisposition.DISCARD, candidate = ::fixture)
            .result.get(10, TimeUnit.SECONDS).installed)
    }
    try {
        // Decoder checks are synthetic ABI payload checks, not native syscall fault evidence.
        val zeroRevision = CheckpointWriteResult.fromNative(longArrayOf(0, 0, 1, 1, 1))
        check(zeroRevision.successful && zeroRevision.revisionCaptured && zeroRevision.capturedRevision == 0L)
        val uncertain = CheckpointWriteResult.fromNative(longArrayOf(7, 0, 1, 1, 0))
        check(!uncertain.successful && uncertain.revisionCaptured && uncertain.destinationPublished && !uncertain.durabilityConfirmed)
        check(CheckpointException(7, uncertain).write == uncertain)
        for (invalid in listOf(longArrayOf(0, 0), longArrayOf(0, 0, 0, 0, 0),
            longArrayOf(7, 0, 0, 1, 0), longArrayOf(7, 0, 1, 0, 1), longArrayOf(7, 0, 2, 0, 0)))
            check(runCatching { CheckpointWriteResult.fromNative(invalid) }.isFailure)

        NativeEditor(8, addDefaultLayers = false).use { empty ->
            check(empty.currentRevision() == 0L)
            val zeroFile = File(root, "revision-zero.checkpoint")
            val result = empty.saveCheckpointResult(zeroFile)
            check(result == zeroRevision)
            cold(zeroFile, empty.state(), pixels(empty))
            val malformed = empty.saveCheckpointResult(File(root, "invalid\u0000path"))
            check(malformed.status == 1 && !malformed.revisionCaptured && malformed.capturedRevision == 0L &&
                !malformed.destinationPublished && !malformed.durabilityConfirmed)
            val missing = empty.saveCheckpointResult(File(root, "missing-parent/attempt.checkpoint"))
            nativeRefusal(missing)
            check(missing.capturedRevision == 0L && !File(root, "missing-parent").exists())
        }
        fixture().use { editor ->
            val file = File(root, "preserved.checkpoint")
            val revision = editor.save(file)
            val oldBytes = file.readBytes()
            val oldState = editor.state(); val oldPixels = pixels(editor)
            val blocked = File(root, "directory-destination.checkpoint").apply { check(mkdir()) }
            val sentinel = File(blocked, "sentinel").apply { writeBytes(oldBytes) }
            val refused = editor.saveCheckpointResult(blocked)
            nativeRefusal(refused); check(refused.capturedRevision == revision)
            val exception = runCatching { editor.save(blocked) }.exceptionOrNull()
            check(exception is CheckpointException && exception.write == refused && exception.status == 7)
            check(sentinel.readBytes().contentEquals(oldBytes) && file.readBytes().contentEquals(oldBytes))
            editor.startStroke(editor.state().layers.single().id, StrokeSettings(diameter = 2f))
            editor.samples(doubleArrayOf(3.0, 3.0, 1.0, 0.0))
            val active = editor.saveCheckpointResult(file)
            check(active.status == 4 && !active.revisionCaptured && active.capturedRevision == 0L &&
                !active.destinationPublished && !active.durabilityConfirmed)
            editor.endStroke(true)
            check(file.readBytes().contentEquals(oldBytes))
            cold(file, oldState, oldPixels)
        }

        val folder = File(root, "workspace")
        val workspace = opened(folder); install(workspace)
        val editor = checkNotNull(workspace.snapshot().editor)
        check(checkNotNull(workspace.snapshot().lastCheckpointWrite).successful)
        for (kind in listOf(RecoverySaveKind.FLUSH, RecoverySaveKind.AUTOSAVE)) {
            val oldTarget = target(folder)
            val oldPointer = File(folder, "active.json").readBytes()
            val oldBytes = WorkspaceRecoveryStore(folder).checkpoint(oldTarget).readBytes()
            val oldSaved = workspace.snapshot().savedRevision
            val commits = pointerCalls.get()
            blockedKind.set(kind)
            editor.renameLayer(editor.state().layers.single().id, "Real filesystem refusal $kind")
            if (kind == RecoverySaveKind.FLUSH) {
                val exception = runCatching { workspace.flushAsync().get(10, TimeUnit.SECONDS) }.exceptionOrNull()
                check(exception?.cause is CheckpointException)
            } else waitFor("Autosave did not report classified failure") { workspace.snapshot().saveError != null }
            val failed = workspace.snapshot()
            nativeRefusal(checkNotNull(failed.lastCheckpointWrite))
            check(failed.lastCheckpointWrite.capturedRevision == editor.currentRevision())
            check(failed.savedRevision == oldSaved && pointerCalls.get() == commits && target(folder) == oldTarget)
            check(File(folder, "active.json").readBytes().contentEquals(oldPointer))
            check(WorkspaceRecoveryStore(folder).checkpoint(oldTarget).readBytes().contentEquals(oldBytes))
            retainedBlock(folder)
            blockedKind.set(null)
            check(workspace.flushAsync().get(10, TimeUnit.SECONDS) == editor.currentRevision())
            check(checkNotNull(workspace.snapshot().lastCheckpointWrite).successful && workspace.snapshot().saveError == null)
            check(target(folder).fileName != oldTarget.fileName)
            retainedBlock(folder)
            cold(WorkspaceRecoveryStore(folder).checkpoint(target(folder)), editor.state(), pixels(editor))
        }

        val beforeInstall = workspace.snapshot(); val installPointer = File(folder, "active.json").readBytes()
        val beforeInstallState = editor.state()
        val installRecoveryFile = WorkspaceRecoveryStore(folder).checkpoint(target(folder))
        val installRecoveryBytes = installRecoveryFile.readBytes()
        val installCommits = pointerCalls.get()
        blockedKind.set(RecoverySaveKind.CANDIDATE_INSTALL)
        val installation = workspace.installCandidate("refused-candidate", DirtyDisposition.DISCARD, candidate = ::fixture)
            .result.get(10, TimeUnit.SECONDS)
        check(!installation.installed && workspace.snapshot().editor === editor && workspace.snapshot().generation == beforeInstall.generation)
        check(editor.state() == beforeInstallState && workspace.snapshot().savedRevision == beforeInstall.savedRevision)
        nativeRefusal(checkNotNull(workspace.snapshot().lastCheckpointWrite))
        check(pointerCalls.get() == installCommits && File(folder, "active.json").readBytes().contentEquals(installPointer))
        check(installRecoveryFile.readBytes().contentEquals(installRecoveryBytes))
        retainedBlock(folder); blockedKind.set(null)

        editor.renameLayer(editor.state().layers.single().id, "Source file success, recovery failure")
        blockedKind.set(RecoverySaveKind.PROJECT_SAVE)
        val project = File(root, "committed-source.comp")
        val sourceResult = workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
            val before = workspace.snapshot(); val pointer = File(folder, "active.json").readBytes()
            val recoveryFile = WorkspaceRecoveryStore(folder).checkpoint(target(folder)); val recoveryBytes = recoveryFile.readBytes()
            val commits = pointerCalls.get()
            val result = lease.saveProject(context, project, false, 64 * 1024 * 1024L,
                lease.memory.totalBytes + 1024 * 1024, "new-source")
            check(result.committed && result.durabilityConfirmed && !result.baselineUpdated && !result.metadataDurabilityUncertain)
            nativeRefusal(checkNotNull(result.recoveryWrite))
            check(result.recoveryWrite == workspace.snapshot().lastCheckpointWrite)
            check(workspace.snapshot().projectBaseline == before.projectBaseline && workspace.snapshot().sourceIdentity == before.sourceIdentity && workspace.snapshot().projectDirty)
            check(pointerCalls.get() == commits && File(folder, "active.json").readBytes().contentEquals(pointer))
            check(recoveryFile.readBytes().contentEquals(recoveryBytes))
            retainedBlock(folder)
            result
        }, {}).get(10, TimeUnit.SECONDS)
        check(sourceResult.committed && project.isDirectory)
        blockedKind.set(null)
        NativeEditor.loadProjectCandidate(context, project, 64 * 1024 * 1024L, 0).use { saved ->
            check(saved.state().document.uuid == editor.state().document.uuid && saved.state().layers == editor.state().layers)
            check(pixels(saved).contentEquals(pixels(editor)))
        }

        blockedKind.set(RecoverySaveKind.OUTGOING_NEW)
        val outgoing = workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
            val before = workspace.snapshot(); val pointer = File(folder, "active.json").readBytes()
            val recoveryFile = WorkspaceRecoveryStore(folder).checkpoint(target(folder)); val recoveryBytes = recoveryFile.readBytes()
            val commits = pointerCalls.get()
            val result = lease.installNewProjectCandidate(context, project, 64 * 1024 * 1024L,
                lease.memory.totalBytes + 1024 * 1024, DirtyDisposition.DISCARD)
            check(!result.installed && workspace.snapshot().editor === editor && workspace.snapshot().generation == before.generation &&
                workspace.snapshot().savedRevision == before.savedRevision)
            nativeRefusal(checkNotNull(workspace.snapshot().lastCheckpointWrite))
            check(pointerCalls.get() == commits && File(folder, "active.json").readBytes().contentEquals(pointer))
            check(recoveryFile.readBytes().contentEquals(recoveryBytes))
            retainedBlock(folder)
            result
        }, {}).get(10, TimeUnit.SECONDS)
        check(!outgoing.installed); blockedKind.set(null)
        workspace.flushAsync().get(10, TimeUnit.SECONDS)

        val closeFolder = File(root, "close")
        val closing = opened(closeFolder); install(closing)
        val closeEditor = checkNotNull(closing.snapshot().editor)
        val closePointer = File(closeFolder, "active.json").readBytes()
        val closeTarget = target(closeFolder)
        val closeBytes = WorkspaceRecoveryStore(closeFolder).checkpoint(closeTarget).readBytes()
        closeEditor.renameLayer(closeEditor.state().layers.single().id, "Close refusal")
        val closeSaved = closing.snapshot().savedRevision; val closeCommits = pointerCalls.get()
        blockedKind.set(RecoverySaveKind.CLOSE)
        check(runCatching { closing.closeAsync().get(10, TimeUnit.SECONDS) }.exceptionOrNull()?.cause is CheckpointException)
        nativeRefusal(checkNotNull(closing.snapshot().lastCheckpointWrite))
        check(closing.snapshot().editor == null && closing.snapshot().savedRevision == closeSaved)
        check(pointerCalls.get() == closeCommits && File(closeFolder, "active.json").readBytes().contentEquals(closePointer))
        check(WorkspaceRecoveryStore(closeFolder).checkpoint(closeTarget).readBytes().contentEquals(closeBytes))
        retainedBlock(closeFolder); blockedKind.set(null)

        val bootstrapFolder = File(root, "bootstrap")
        blockedKind.set(RecoverySaveKind.BOOTSTRAP)
        val bootstrapCommits = pointerCalls.get()
        val bootstrap = AndroidWorkspace(bootstrapFolder, recoveryHooks = hooks).also(workspaces::add)
        waitFor("Failed bootstrap did not finish") { !bootstrap.snapshot().loading }
        check(bootstrap.snapshot().editor == null && bootstrap.snapshot().recoveryError != null && !File(bootstrapFolder, "active.json").exists())
        check(pointerCalls.get() == bootstrapCommits)
        nativeRefusal(checkNotNull(bootstrap.snapshot().lastCheckpointWrite))
        retainedBlock(bootstrapFolder); blockedKind.set(null)
        return "ABI9 classified checkpoint passed: real JNI revision-zero success and exact cold recovery; invalid NUL path, missing parent, incompatible directory destination and active stroke distinguish uncaptured/pre-publication failures and preserve existing bytes. Actual filesystem refusals at bootstrap/autosave/flush/close/source-save/outgoing-New/candidate retain attempts and old recovery pointer; source .comp native commit/durability stay truthful. Synthetic decoder payload checks preserve known-zero and published-but-unconfirmed flags. No Android post-publication syscall fault, disk-full or power-loss claim; host fault campaign is separate."
    } finally {
        blockedKind.set(null)
        workspaces.forEach { runCatching { it.closeAsync().get(10, TimeUnit.SECONDS) } }
        root.deleteRecursively()
    }
}
