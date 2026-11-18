package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exact native serialization size and immutable ownership; no quota, GC, or process memory acceptance. */
internal fun checkpointSnapshotSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val root = File(context.cacheDir, "checkpoint-snapshot-${UUID.randomUUID()}").apply { check(mkdir()) }
    val snapshots = mutableListOf<NativeCheckpointSnapshot>()
    val editors = mutableListOf<NativeEditor>()
    val workspaces = mutableListOf<AndroidWorkspace>()
    fun image(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(editor.canvasWidth * editor.canvasHeight * 4)
        check(editor.draw(editor.canvasWidth, editor.canvasHeight, 0.0, 0.0,
            editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun cold(file: File, state: EditorState, pixels: ByteArray) {
        NativeEditor.load(file).use { check(it.state().copy(selectedLayerActions = null) == state.copy(undo = false, redo = false, selectedLayerActions = null) && image(it).contentEquals(pixels)) }
    }
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    fun fixture(): NativeEditor = NativeEditor(32, addDefaultLayers = false).also { editor ->
        val bytes = ByteArray(17 * 9 * 4) { index -> when (index % 4) { 0 -> (index / 4).toByte(); 1 -> 80; 2 -> -48; else -> -1 } }
        val id = editor.importPixels(17, 9, ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() })
        editor.renameLayer(id, "快照-🎨")
        editor.createMask(id, true)
        editor.setOpacity(id, .7)
        editor.putGuide(GuideState(UUID.randomUUID().toString(), true, 4.25))
    }
    try {
        NativeEditor(8, addDefaultLayers = false).use { empty ->
            val beforeFiles = root.list()!!.toSet()
            val snapshot = empty.captureCheckpointSnapshot().also(snapshots::add)
            check(snapshot.capturedRevision == 0L && snapshot.serializedBytes >= 32L)
            check(root.list()!!.toSet() == beforeFiles) { "Capture created an IO artifact" }
            val file = File(root, "zero.checkpoint")
            val written = snapshot.write(file)
            check(written.successful && written.revisionCaptured && written.capturedRevision == 0L)
            check(file.length() == snapshot.serializedBytes)
            cold(file, empty.state(), image(empty))
            snapshot.close(); snapshot.close()
            check(runCatching { snapshot.write(File(root, "closed-zero.checkpoint")) }.isFailure)
            check(!File(root, "closed-zero.checkpoint").exists())
            empty.freezeResourceGrowth(); empty.releaseResourceGrowth()
        }

        val editor = fixture().also(editors::add)
        val layer = editor.state().layers.single().id
        editor.startStroke(layer, StrokeSettings(diameter = 4f))
        editor.samples(doubleArrayOf(4.0, 4.0, 1.0, 0.0))
        val activeError = runCatching { editor.captureCheckpointSnapshot() }.exceptionOrNull()
        check(activeError is CheckpointException && activeError.status == 4)
        editor.endStroke(true)
        val original = editor.state(); val pixels = image(editor)
        val snapshot = editor.captureCheckpointSnapshot().also(snapshots::add)
        val capturedInfo = snapshot.info
        check(capturedInfo.capturedRevision == original.revision)
        check(runCatching { editor.freezeResourceGrowth() }.isFailure) { "Held checkpoint snapshot was omitted from retained ownership" }
        val blocked = File(root, "incompatible.checkpoint").apply { check(mkdir()) }
        val sentinel = File(blocked, "sentinel").apply { writeBytes(byteArrayOf(32, 65, 99)) }
        val refused = snapshot.write(blocked)
        check(refused.status == 7 && refused.revisionCaptured && refused.capturedRevision == original.revision &&
            !refused.destinationPublished && !refused.durabilityConfirmed)
        check(sentinel.readBytes().contentEquals(byteArrayOf(32, 65, 99)))
        val invalid = snapshot.write(File(root, "invalid\u0000path"))
        check(invalid.status == 1 && !invalid.revisionCaptured && !invalid.destinationPublished && !invalid.durabilityConfirmed)
        editor.renameLayer(layer, "A later much longer name changes serialization metadata")
        editor.startStroke(layer, StrokeSettings(diameter = 6f, red = 1f, green = 0f, blue = 0f))
        editor.samples(doubleArrayOf(8.0, 4.0, 1.0, 0.0)); editor.endStroke(false)
        check(editor.currentRevision() > capturedInfo.capturedRevision && !image(editor).contentEquals(pixels))
        editor.close()
        check(runCatching { editor.captureCheckpointSnapshot() }.isFailure)
        val file = File(root, "immutable-after-editor-close.checkpoint")
        check(snapshot.write(file).let { it.successful && it.capturedRevision == capturedInfo.capturedRevision })
        check(file.length() == capturedInfo.serializedBytes && snapshot.info == capturedInfo)
        cold(file, original, pixels)
        val second = File(root, "same-snapshot-again.checkpoint")
        check(snapshot.write(second).successful && second.length() == capturedInfo.serializedBytes)
        check(file.readBytes().contentEquals(second.readBytes()))
        snapshot.close(); snapshot.close()
        check(runCatching { snapshot.write(File(root, "closed.checkpoint")) }.isFailure)
        check(!File(root, "closed.checkpoint").exists())

        fixture().use { frozen ->
            frozen.freezeResourceGrowth()
            try {
                check(runCatching { frozen.captureCheckpointSnapshot() }.isFailure)
                val frozenSnapshot = frozen.captureFrozenCheckpointSnapshot().also(snapshots::add)
                val destination = File(root, "frozen-owner.checkpoint")
                check(frozenSnapshot.write(destination).successful && destination.length() == frozenSnapshot.serializedBytes)
                frozenSnapshot.close(); frozenSnapshot.close()
            } finally { frozen.releaseResourceGrowth() }
            frozen.freezeResourceGrowth(); frozen.releaseResourceGrowth()
        }

        val folder = File(root, "workspace")
        val faultKind = AtomicReference<RecoverySaveKind?>()
        val captureRefusalEditor = AtomicReference<NativeEditor?>()
        val workspace = AndroidWorkspace(folder, recoveryHooks = RecoveryPublicationHooks(beforeCheckpoint = { kind, _, destination ->
            if (kind == faultKind.get()) check(destination.mkdir())
            if (kind == RecoverySaveKind.FLUSH) captureRefusalEditor.get()?.let {
                it.startStroke(it.state().layers.single().id, StrokeSettings(diameter = 4f))
                it.samples(doubleArrayOf(4.0, 4.0, 1.0, 0.0))
            }
        })).also(workspaces::add)
        waitFor("Snapshot workspace did not open") { !workspace.snapshot().loading }
        check(workspace.installCandidate("snapshot-fixture", DirtyDisposition.DISCARD, candidate = ::fixture)
            .result.get(10, TimeUnit.SECONDS).installed)
        val currentEditor = checkNotNull(workspace.snapshot().editor)
        val store = WorkspaceRecoveryStore(folder)
        fun exactLastFile() {
            val info = checkNotNull(workspace.snapshot().lastCheckpointSnapshot)
            val written = checkNotNull(workspace.snapshot().lastCheckpointWrite)
            check(written.successful && info.capturedRevision == written.capturedRevision)
            check(store.checkpoint(checkNotNull(store.read())).length() == info.serializedBytes)
        }
        exactLastFile()
        currentEditor.renameLayer(currentEditor.state().layers.single().id, "Captured for workspace flush")
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        exactLastFile()
        val activePointer = File(folder, "active.json").readBytes()
        val oldTarget = checkNotNull(store.read()); val oldBytes = store.checkpoint(oldTarget).readBytes()
        val beforeFailure = workspace.snapshot().savedRevision
        faultKind.set(RecoverySaveKind.FLUSH)
        currentEditor.renameLayer(currentEditor.state().layers.single().id, "Refused immutable snapshot write")
        val error = runCatching { workspace.flushAsync().get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        check(error?.cause is CheckpointException)
        val failed = workspace.snapshot()
        check(failed.savedRevision == beforeFailure && failed.lastCheckpointSnapshot?.capturedRevision == currentEditor.currentRevision())
        check(checkNotNull(failed.lastCheckpointWrite).let { it.status == 7 && it.revisionCaptured && !it.destinationPublished && !it.durabilityConfirmed })
        check(File(folder, "active.json").readBytes().contentEquals(activePointer) && store.checkpoint(oldTarget).readBytes().contentEquals(oldBytes))
        faultKind.set(null)
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        exactLastFile()
        val pointerBeforeCaptureRefusal = File(folder, "active.json").readBytes()
        val targetBeforeCaptureRefusal = checkNotNull(store.read())
        val bytesBeforeCaptureRefusal = store.checkpoint(targetBeforeCaptureRefusal).readBytes()
        val savedBeforeCaptureRefusal = workspace.snapshot().savedRevision
        captureRefusalEditor.set(currentEditor)
        currentEditor.renameLayer(currentEditor.state().layers.single().id, "Native capture refuses active stroke")
        val captureError = runCatching { workspace.flushAsync().get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        check(captureError?.cause is CheckpointException && (captureError.cause as CheckpointException).status == 4)
        val captureRefusal = workspace.snapshot()
        // This is synthesized from a real native capture status, not a native writer result or injected transport fault.
        check(captureRefusal.lastCheckpointSnapshot == null && captureRefusal.savedRevision == savedBeforeCaptureRefusal)
        check(captureRefusal.lastCheckpointWrite == CheckpointWriteResult(4, 0, false, false, false))
        check(File(folder, "active.json").readBytes().contentEquals(pointerBeforeCaptureRefusal) &&
            store.checkpoint(targetBeforeCaptureRefusal).readBytes().contentEquals(bytesBeforeCaptureRefusal))
        captureRefusalEditor.set(null)
        currentEditor.endStroke(true)
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        exactLastFile()
        val finalState = currentEditor.state(); val finalPixels = image(currentEditor)
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        cold(store.checkpoint(checkNotNull(store.read())), finalState, finalPixels)
        return "ABI9 immutable checkpoint snapshot passed: exact serialized size equals real JNI file length including header; captured revision zero, UTF-8 names/patterned pixels/mask/guide state, same-snapshot repeated bytes, and cold exact history-free restore. Later edits and editor close preserve captured state. Double close and post-close write are safe; active-stroke capture and wrong frozen owner reject, held snapshot blocks growth admission and close releases it. Workspace uses one captured snapshot for size/write and preserves old pointer/bytes on actual pre-publication filesystem refusal. Native capture status4 yields a synthesized preparation refusal with no writer, no exposed revision/size, and no stale prior size. Size is not a quota reservation, GC, allocator peak, or global resident-memory guarantee; Android transport/allocation and post-publication syscall faults are not injected."
    } finally {
        snapshots.forEach { runCatching { it.close() } }
        editors.forEach { runCatching { it.close() } }
        workspaces.forEach { runCatching { it.closeAsync().get(10, TimeUnit.SECONDS) } }
        root.deleteRecursively()
    }
}
