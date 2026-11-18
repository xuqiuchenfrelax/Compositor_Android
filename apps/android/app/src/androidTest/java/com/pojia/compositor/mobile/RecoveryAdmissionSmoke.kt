package com.pojia.compositor.mobile

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A compact checkpoint still requires full native tiles; file size is not its loading budget. */
internal fun recoveryAdmissionSmoke(context: Context): String {
    val root = File(context.cacheDir, "recovery-admission-${UUID.randomUUID()}").apply { check(mkdir()) }
    val checkpoint = File(root, "small.checkpoint")
    val pixels = ByteArray(8 * 8 * 4) { index -> when (index % 4) {
        0 -> (index / 4 * 3).toByte()
        1 -> (index / 4 * 2).toByte()
        2 -> 96
        else -> -1
    } }
    val smallBudget = 64 * 1024L
    val sufficientBudget = 2 * 1024 * 1024L
    val workspaces = mutableListOf<AndroidWorkspace>()
    val workers = Executors.newSingleThreadExecutor()
    val resume = CountDownLatch(1)
    val returnedCandidate = AtomicReference<NativeEditor>()
    val abandoning = AtomicBoolean(false)
    fun image(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(pixels.size)
        check(editor.draw(8, 8, 0f, 0f, 8f, 8f, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun expectNativeBudget(action: () -> Unit) {
        try { action(); error("Undersized recovery budget was accepted") }
        catch (error: CheckpointException) { check(error.status == 3) { "Wrong recovery refusal: ${error.status}" } }
    }
    fun awaitOpen(workspace: AndroidWorkspace): WorkspaceState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(20)
        return workspace.snapshot().also { check(!it.loading) { "Recovery admission never settled" } }
    }
    try {
        val saved = NativeEditor(8, addDefaultLayers = false).use { editor ->
            editor.importPixels(8, 8, ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); rewind() })
            editor.renameLayer(editor.state().layers.single().id, "Bounded recovery fixture")
            check(editor.retainedMemory().tileBytes > smallBudget)
            editor.save(checkpoint)
            editor.state()
        }
        check(checkpoint.length() < smallBudget) { "Compact recovery fixture no longer distinguishes serialized and decoded sizes" }
        val checkpointBytes = checkpoint.readBytes()
        val lowLedger = ResourceBudgetLedger(smallBudget)
        val lowBefore = lowLedger.snapshot()
        val nativeCalls = AtomicInteger()
        expectNativeBudget {
            RecoveryAdmission.forTest(lowLedger, beforeNative = { budget ->
                check(budget.limitBytes == smallBudget && budget.residentBytes == 0L)
                check(lowLedger.snapshot().reservations == 1)
                nativeCalls.incrementAndGet()
            }).load(checkpoint).close()
        }
        check(nativeCalls.get() == 1 && lowLedger.snapshot().reservedBytes == lowBefore.reservedBytes &&
            lowLedger.snapshot().reservations == lowBefore.reservations && checkpoint.readBytes().contentEquals(checkpointBytes))
        val residentLedger = ResourceBudgetLedger(sufficientBudget)
        expectNativeBudget {
            RecoveryAdmission.forTest(residentLedger, resident = { sufficientBudget - smallBudget })
                .load(checkpoint).close()
        }
        check(residentLedger.snapshot().reservedBytes == 0L && residentLedger.snapshot().reservations == 0)

        val successfulLedger = ResourceBudgetLedger(sufficientBudget)
        val entered = CountDownLatch(1)
        val success = workers.submit<NativeEditor> {
            RecoveryAdmission.forTest(successfulLedger, beforeNative = { budget ->
                check(budget.limitBytes == sufficientBudget)
                entered.countDown(); check(resume.await(10, TimeUnit.SECONDS))
            }).load(checkpoint).also { candidate ->
                returnedCandidate.set(candidate)
                if (abandoning.get()) returnedCandidate.getAndSet(null)?.close()
            }
        }
        check(entered.await(10, TimeUnit.SECONDS))
        check(successfulLedger.snapshot().reservedBytes == sufficientBudget && successfulLedger.snapshot().reservations == 1)
        check(successfulLedger.tryReserve(1, "image job while recovery holds its segment") == null)
        resume.countDown()
        success.get(10, TimeUnit.SECONDS).use { editor ->
            check(editor.state().document.uuid == saved.document.uuid && editor.state().layers == saved.layers)
            check(editor.currentRevision() == saved.revision && !editor.state().undo && !editor.state().redo)
            check(image(editor).contentEquals(pixels))
            // The exact admitted lease survives decoding as this document's persistent owner.
            check(editor.resourceLedger === successfulLedger)
            check(successfulLedger.snapshot().reservedBytes == editor.installedDocumentBytes() &&
                successfulLedger.snapshot().reservedBytes > 0 && successfulLedger.snapshot().reservations == 1)
            val id = editor.state().layers.single().id
            editor.setOpacity(id, .5)
            editor.undo(false)
            check(image(editor).contentEquals(pixels))
            check(successfulLedger.snapshot().reservedBytes == editor.installedDocumentBytes() &&
                successfulLedger.snapshot().reservations == 1)
        }
        check(successfulLedger.snapshot().reservedBytes == 0L && successfulLedger.snapshot().reservations == 0)

        val tightLedger = ResourceBudgetLedger(sufficientBudget + 7)
        tightLedger.reserve(7, "existing recovery competitor").use {
            RecoveryAdmission.forTest(tightLedger, beforeNative = { budget ->
                check(budget.limitBytes == sufficientBudget)
                check(tightLedger.snapshot().availableBytes == 0L)
                check(tightLedger.tryReserve(1, "racing owner before document handoff") == null)
            }).load(checkpoint).use { recovered ->
                check(recovered.resourceLedger === tightLedger)
                check(tightLedger.snapshot().reservedBytes == recovered.installedDocumentBytes() + 7)
                check(tightLedger.snapshot().reservations == 2)
                check(image(recovered).contentEquals(pixels))
                tightLedger.reserve(tightLedger.snapshot().availableBytes, "racing owner after handoff").use {
                    check(tightLedger.snapshot().availableBytes == 0L)
                    check(recovered.state().document.uuid == saved.document.uuid)
                }
            }
            check(tightLedger.snapshot().reservedBytes == 7L && tightLedger.snapshot().reservations == 1)
        }
        check(tightLedger.snapshot().reservedBytes == 0L && tightLedger.snapshot().reservations == 0)

        val injectedLedger = ResourceBudgetLedger(sufficientBudget)
        val injected = runCatching {
            RecoveryAdmission.forTest(injectedLedger, beforeNative = { error("recovery pre-native fixture") })
                .load(checkpoint).close()
        }.exceptionOrNull()
        check(injected?.message == "recovery pre-native fixture")
        check(injectedLedger.snapshot().reservedBytes == 0L && injectedLedger.snapshot().reservations == 0)

        val zeroCalls = AtomicInteger()
        try {
            RecoveryAdmission.forTest(ResourceBudgetLedger(sufficientBudget), headroom = { 0 },
                beforeNative = { zeroCalls.incrementAndGet() }).load(checkpoint).close()
            error("Zero observed headroom accepted recovery")
        } catch (error: IllegalStateException) { check(error.message!!.contains("Not enough memory")) }
        check(zeroCalls.get() == 0)

        val shared = ImageIoAdmission.ledger
        val sharedBefore = shared.snapshot()
        check(sharedBefore.availableBytes > smallBudget)
        shared.reserve(sharedBefore.availableBytes - smallBudget, "held image import job").use {
            val held = shared.snapshot()
            expectNativeBudget { RecoveryAdmission().load(checkpoint).close() }
            check(shared.snapshot().reservedBytes == held.reservedBytes && shared.snapshot().reservations == held.reservations)
        }
        check(shared.snapshot().reservedBytes == sharedBefore.reservedBytes && shared.snapshot().reservations == sharedBefore.reservations)

        for (legacy in listOf(false, true)) {
            val folder = File(root, if (legacy) "legacy" else "pointer").apply { check(mkdir()) }
            val store = WorkspaceRecoveryStore(folder)
            if (legacy) checkpoint.copyTo(store.legacy)
            else NativeEditor.load(checkpoint).use { editor ->
                val target = store.target(7, editor, null, null)
                checkpoint.copyTo(store.checkpoint(target))
                check(store.commit(target).committed)
            }
            val before = folder.listFiles()!!.associate { it.name to it.readBytes() }
            val calls = AtomicInteger()
            val workspace = AndroidWorkspace(folder, RecoveryAdmission.forTest(ResourceBudgetLedger(smallBudget),
                beforeNative = { calls.incrementAndGet() })).also(workspaces::add)
            val failed = awaitOpen(workspace)
            check(failed.editor == null && failed.recoveryError!!.contains("memory budget") &&
                failed.recoveryError.contains("existing saved document has been kept"))
            check(calls.get() == 1 && folder.listFiles()!!.map { it.name }.toSet() == before.keys)
            before.forEach { (name, bytes) -> check(File(folder, name).readBytes().contentEquals(bytes)) }
            workspace.closeAsync().get(10, TimeUnit.SECONDS)
            val recovered = AndroidWorkspace(folder, RecoveryAdmission.forTest(ResourceBudgetLedger(sufficientBudget)))
                .also(workspaces::add)
            val opened = awaitOpen(recovered)
            check(opened.recovered && opened.editor != null && opened.recoveryError == null)
            check(opened.editor.state().document.uuid == saved.document.uuid && opened.editor.state().layers == saved.layers)
            check(opened.currentRevision == saved.revision && !opened.editor.state().undo && !opened.editor.state().redo)
            check(image(opened.editor).contentEquals(pixels))
            recovered.closeAsync().get(10, TimeUnit.SECONDS)
        }
        RecoveryAdmission.forContext(context).load(checkpoint).use { check(image(it).contentEquals(pixels)) }
        check(checkpoint.readBytes().contentEquals(checkpointBytes))
        return "Recovery admission passed: compact serialized checkpoint rejects64KiB at the budgeted native entry; " +
            "exact pixels/UUID/revision/layers with empty recovered history; the preadmitted lease shrinks into persistent document ownership " +
            "without release/reacquire under tight capacity and competing claims; custom-ledger edits/undo retain the same owner; " +
            "pre-native and native failures release claims; zero headroom never enters native; pointer/legacy failures preserve every file byte; " +
            "document destruction releases persistent accounting. Conservative ownership is not an RSS ceiling."
    } finally {
        resume.countDown()
        abandoning.set(true)
        try { workspaces.forEach { it.closeAsync().get(10, TimeUnit.SECONDS) } }
        finally {
            workers.shutdown()
            try { workers.awaitTermination(10, TimeUnit.SECONDS) }
            finally { returnedCandidate.getAndSet(null)?.close(); root.deleteRecursively() }
        }
    }
}
