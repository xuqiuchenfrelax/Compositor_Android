package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual bounded JNI snapshots and explicit caller leases; no allocator/RSS or injected JNI OOM claim. */
internal fun snapshotAdmissionSmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "snapshot-admission-${UUID.randomUUID()}")
        .apply { check(mkdir()) }
    val snapshots = mutableListOf<NativeCheckpointSnapshot>()
    val editors = mutableListOf<NativeEditor>()
    val workspaces = mutableListOf<AndroidWorkspace>()
    val writers = Executors.newFixedThreadPool(3)
    val releaseWriters = CountDownLatch(1)
    val sufficient = 8 * 1024 * 1024L
    val small = 64 * 1024L
    val pixels = ByteArray(8 * 8 * 4) { index -> when (index % 4) {
        0 -> (index / 4 * 3).toByte(); 1 -> 80; 2 -> -48; else -> -1
    } }
    fun fixture(): NativeEditor = NativeEditor(8, addDefaultLayers = false).also { editor ->
        editor.importPixels(8, 8, ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); rewind() })
        editor.renameLayer(editor.state().layers.single().id, "Bounded 快照-🎨")
    }
    fun image(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(pixels.size)
        check(editor.draw(8, 8, 0.0, 0.0, 8.0, 8.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun baseline(ledger: ResourceBudgetLedger) {
        val state = ledger.snapshot()
        check(state.reservedBytes == 0L && state.reservations == 0)
    }
    fun restored(file: File, expected: EditorState) {
        NativeEditor.load(file).use { editor ->
            check(editor.state().copy(selectedLayerActions = null) == expected.copy(undo = false, redo = false, selectedLayerActions = null))
            check(image(editor).contentEquals(pixels))
        }
    }
    fun awaitOpen(workspace: AndroidWorkspace) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(20)
        check(!workspace.snapshot().loading && workspace.snapshot().editor != null)
    }
    try {
        val editor = fixture().also(editors::add)
        val lowLedger = ResourceBudgetLedger(small)
        val nativeCalls = AtomicInteger()
        val nativeRefusal = runCatching {
            editor.captureCheckpointSnapshot(CheckpointAdmission.forTest(lowLedger, beforeCapture = {
                check(it == small && lowLedger.snapshot().reservations == 1)
                nativeCalls.incrementAndGet()
            })).close()
        }.exceptionOrNull()
        check(nativeRefusal is CheckpointException && nativeRefusal.status == 3 && nativeRefusal.cause == null)
        check(nativeCalls.get() == 1)
        baseline(lowLedger)
        editor.freezeResourceGrowth(); editor.releaseResourceGrowth()

        val zeroLedger = ResourceBudgetLedger(sufficient)
        val skippedCalls = AtomicInteger()
        val callerRefusal = runCatching {
            editor.captureCheckpointSnapshot(CheckpointAdmission.forTest(zeroLedger, headroom = { 0 },
                beforeCapture = { skippedCalls.incrementAndGet() })).close()
        }.exceptionOrNull()
        check(callerRefusal is CheckpointException && callerRefusal.status == 3 &&
            callerRefusal.cause is ResourceBudgetExceededException && skippedCalls.get() == 0)
        baseline(zeroLedger)
        val heldOwner = zeroLedger.reserve(sufficient, "competing image owner")
        try {
            val competing = runCatching {
                editor.captureCheckpointSnapshot(CheckpointAdmission.forTest(zeroLedger,
                    beforeCapture = { skippedCalls.incrementAndGet() })).close()
            }.exceptionOrNull()
            check(competing is CheckpointException && competing.cause is ResourceBudgetExceededException)
            check(skippedCalls.get() == 0 && zeroLedger.snapshot().reservations == 1)
        } finally { heldOwner.close() }
        baseline(zeroLedger)

        val hookLedger = ResourceBudgetLedger(sufficient)
        val preparationHook = runCatching {
            editor.captureCheckpointSnapshot(CheckpointAdmission.forTest(hookLedger,
                beforeCapture = { error("Caller fixture refused before JNI") })).close()
        }.exceptionOrNull()
        check(preparationHook?.message == "Caller fixture refused before JNI")
        baseline(hookLedger)
        editor.freezeResourceGrowth(); editor.releaseResourceGrowth()

        val ledger = ResourceBudgetLedger(sufficient)
        val writeCalls = AtomicInteger()
        val admission = CheckpointAdmission.forTest(ledger, beforeWrite = { writeCalls.incrementAndGet() })
        val expected = editor.state()
        val snapshot = editor.captureCheckpointSnapshot(admission).also(snapshots::add)
        check(snapshot.serializedBytes < small && snapshot.memory.sharedRetainedBytes > small)
        check(snapshot.memory.heldBytes == snapshot.memory.sharedRetainedBytes + snapshot.memory.snapshotOwnedBytes)
        check(ledger.snapshot().reservedBytes == snapshot.memory.heldBytes && ledger.snapshot().reservations == 1)
        check(ledger.snapshot().peakReservedBytes == sufficient)
        check(runCatching { editor.freezeResourceGrowth() }.isFailure)
        editor.close()
        check(ledger.snapshot().reservedBytes == snapshot.memory.heldBytes)
        val destination = File(root, "retained.checkpoint").apply { writeBytes(byteArrayOf(32, 65, 99)) }
        val original = destination.readBytes()
        ledger.reserve(ledger.snapshot().availableBytes, "image job contending with writer").use {
            val failed = runCatching { snapshot.write(destination) }.exceptionOrNull()
            check(failed is ResourceBudgetExceededException && writeCalls.get() == 0)
            check(destination.readBytes().contentEquals(original) && ledger.snapshot().reservations == 2)
        }
        check(snapshot.write(destination).successful && writeCalls.get() == 1)
        check(destination.length() == snapshot.serializedBytes)
        check(ledger.snapshot().reservedBytes == snapshot.memory.heldBytes && ledger.snapshot().reservations == 1)
        restored(destination, expected)
        snapshot.close(); snapshot.close()
        baseline(ledger)

        val writerHookLedger = ResourceBudgetLedger(sufficient)
        fixture().use { hookEditor ->
            val hooked = hookEditor.captureCheckpointSnapshot(CheckpointAdmission.forTest(writerHookLedger,
                beforeWrite = { error("Caller writer fixture refused before JNI") })).also(snapshots::add)
            val untouched = File(root, "hook-refusal.checkpoint").apply { writeBytes(original) }
            val hookFailure = runCatching { hooked.write(untouched) }.exceptionOrNull()
            check(hookFailure?.message == "Caller writer fixture refused before JNI")
            check(untouched.readBytes().contentEquals(original) && writerHookLedger.snapshot().reservations == 1 &&
                writerHookLedger.snapshot().reservedBytes == hooked.memory.heldBytes)
            hooked.close()
            baseline(writerHookLedger)
            hookEditor.freezeResourceGrowth(); hookEditor.releaseResourceGrowth()
        }

        val concurrentEditor = fixture().also(editors::add)
        val concurrentState = concurrentEditor.state()
        val concurrentLedger = ResourceBudgetLedger(sufficient)
        val entered = CountDownLatch(2)
        val concurrent = concurrentEditor.captureCheckpointSnapshot(CheckpointAdmission.forTest(concurrentLedger,
            beforeWrite = {
                entered.countDown()
                check(releaseWriters.await(10, TimeUnit.SECONDS))
            })).also(snapshots::add)
        concurrentEditor.close()
        val firstFile = File(root, "concurrent-first.checkpoint")
        val secondFile = File(root, "concurrent-second.checkpoint")
        val first = writers.submit<CheckpointWriteResult> { concurrent.write(firstFile) }
        val second = writers.submit<CheckpointWriteResult> { concurrent.write(secondFile) }
        check(entered.await(10, TimeUnit.SECONDS))
        check(concurrentLedger.snapshot().reservedBytes == concurrent.memory.heldBytes + 2 * concurrent.memory.writeScratchBytes)
        check(concurrentLedger.snapshot().reservations == 3)
        val closeStarted = CountDownLatch(1)
        val closing = writers.submit { closeStarted.countDown(); concurrent.close() }
        check(closeStarted.await(10, TimeUnit.SECONDS))
        check(runCatching { closing.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull() is TimeoutException)
        check(!firstFile.exists() && !secondFile.exists())
        releaseWriters.countDown()
        check(first.get(10, TimeUnit.SECONDS).successful && second.get(10, TimeUnit.SECONDS).successful)
        closing.get(10, TimeUnit.SECONDS)
        check(firstFile.readBytes().contentEquals(secondFile.readBytes()))
        restored(firstFile, concurrentState)
        concurrent.close()
        baseline(concurrentLedger)

        val overflowLedger = ResourceBudgetLedger(Long.MAX_VALUE)
        val overflow = CheckpointSnapshotMemory(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE, Long.MAX_VALUE, 1)
        check(runCatching {
            CheckpointAdmission.forTest(overflowLedger).withWriter(overflow) { _, _ -> error("Overflow entered writer") }
        }.exceptionOrNull() is ArithmeticException)
        baseline(overflowLedger)

        val workspaceLedger = ResourceBudgetLedger(sufficient)
        val refuseCapture = AtomicBoolean(false)
        val refuseWriter = AtomicBoolean(false)
        val workspaceAdmission = CheckpointAdmission.forTest(workspaceLedger, headroom = {
            if (refuseCapture.get() || (refuseWriter.get() && workspaceLedger.snapshot().reservations > 0)) 0
            else sufficient
        })
        val folder = File(root, "workspace")
        val workspace = AndroidWorkspace(folder, checkpointAdmission = workspaceAdmission).also(workspaces::add)
        awaitOpen(workspace)
        check(workspace.installCandidate("bounded-snapshot", DirtyDisposition.DISCARD, candidate = ::fixture)
            .result.get(10, TimeUnit.SECONDS).installed)
        baseline(workspaceLedger)
        val currentEditor = checkNotNull(workspace.snapshot().editor)
        val store = WorkspaceRecoveryStore(folder)
        val active = File(folder, "active.json")
        val oldPointer = active.readBytes()
        val oldTarget = checkNotNull(store.read())
        val oldCheckpoint = store.checkpoint(oldTarget).readBytes()
        val saved = workspace.snapshot().savedRevision
        currentEditor.renameLayer(currentEditor.state().layers.single().id, "Caller capture budget refusal")
        refuseCapture.set(true)
        val captureFailure = runCatching { workspace.flushAsync().get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        check(captureFailure?.cause is CheckpointException &&
            captureFailure.cause?.cause is CheckpointException && captureFailure.cause?.cause?.cause is ResourceBudgetExceededException)
        check(workspace.snapshot().lastCheckpointSnapshot == null && workspace.snapshot().savedRevision == saved)
        check(workspace.snapshot().lastCheckpointWrite == CheckpointWriteResult(3, 0, false, false, false))
        check(active.readBytes().contentEquals(oldPointer) && store.checkpoint(oldTarget).readBytes().contentEquals(oldCheckpoint))
        baseline(workspaceLedger)
        refuseCapture.set(false); refuseWriter.set(true)
        val writerFailure = runCatching { workspace.flushAsync().get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        check(writerFailure?.cause is ResourceBudgetExceededException)
        check(workspace.snapshot().lastCheckpointWrite == null && workspace.snapshot().lastCheckpointSnapshot != null)
        check(workspace.snapshot().savedRevision == saved && active.readBytes().contentEquals(oldPointer) &&
            store.checkpoint(oldTarget).readBytes().contentEquals(oldCheckpoint))
        baseline(workspaceLedger)
        refuseWriter.set(false)
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        check(workspace.snapshot().lastCheckpointWrite?.successful == true)
        check(workspace.snapshot().savedRevision == currentEditor.currentRevision())
        baseline(workspaceLedger)
        workspace.closeAsync().get(10, TimeUnit.SECONDS)

        val shared = ImageIoAdmission.ledger
        val sharedBefore = shared.snapshot()
        check(sharedBefore.availableBytes > small)
        fixture().use { sharedEditor ->
            val installed = shared.snapshot()
            check(installed.reservedBytes == sharedBefore.reservedBytes + sharedEditor.installedDocumentBytes() &&
                installed.reservations == sharedBefore.reservations + 1 && installed.availableBytes > small)
            // The competing job leaves 64 KiB after the editor's own document claim.
            shared.reserve(installed.availableBytes - small, "shared image job").use {
                val failure = runCatching { sharedEditor.captureCheckpointSnapshot().close() }.exceptionOrNull()
                check(failure is CheckpointException && failure.status == 3 && failure.cause == null)
                check(shared.snapshot().reservedBytes == installed.reservedBytes + it.bytes &&
                    shared.snapshot().reservations == installed.reservations + 1)
            }
            check(shared.snapshot().reservedBytes == installed.reservedBytes &&
                shared.snapshot().reservations == installed.reservations)
        }
        check(shared.snapshot().reservedBytes == sharedBefore.reservedBytes && shared.snapshot().reservations == sharedBefore.reservations)
        return "ABI10 snapshot admission passed: real JNI capture budget refusal distinct from caller admission refusal; compact serialized bytes do not substitute for conservative retained tile claims. Successful capture shrinks a preallocated shared-ledger segment to held bytes, retained through editor close. Per-writer scratch refuses before JNI/path encoding, preserves target and workspace pointer/saved revision, and retries after headroom returns. Two admitted caller writes hold separate scratch claims and both real JNI writes complete; close waits for their lifetimes and returns every lease. Native execution overlap is not measured. Caller hook/overflow cleanup is fixture evidence, not real JNI allocation/transport fault injection. Mutable/history/GPU/process memory remain outside unified accounting."
    } finally {
        releaseWriters.countDown()
        writers.shutdown()
        try { writers.awaitTermination(10, TimeUnit.SECONDS) }
        finally {
            snapshots.forEach { runCatching { it.close() } }
            workspaces.forEach { runCatching { it.closeAsync().get(10, TimeUnit.SECONDS) } }
            editors.forEach { runCatching { it.close() } }
            root.deleteRecursively()
        }
    }
}
