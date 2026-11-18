package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual JNI queries and render admission; HQ guards deliberately remain closed. */
internal fun samplingScratchSmoke(instrumentation: Instrumentation): String {
    val allowance = 520L * 1024
    samplingScratchLeaseChecks(allowance)
    val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
    val initial = ledger.snapshot()
    val globalBefore = ImageIoAdmission.ledger.snapshot()
    NativeEditor.createWithLedgerForTest(8, ledger, false).use { editor ->
        val source = ByteBuffer.allocateDirect(8 * 8 * 4)
        repeat(64) { source.put(byteArrayOf(120, 40, 20, -1)) }; source.rewind()
        val id = editor.importPixels(8, 8, source)
        val view = Viewport(0.0, 0.0, 8.0, 8.0)
        fun sentinel() = ByteBuffer.allocateDirect(4).apply { putInt(0, 0x12345678) }
        fun unchanged(output: ByteBuffer) = check(output.getInt(0) == 0x12345678) { "Rejected render wrote output" }
        fun sameClaims(before: ResourceBudgetSnapshot) = check(ledger.snapshot().let {
            it.reservedBytes == before.reservedBytes && it.reservations == before.reservations
        }) { "Sampling scratch lease leaked" }
        checkNotNull(editor.captureScene()).use { scene ->
            check(scene.resourceLedger === ledger)
            check(scene.samplingScratchBytes(1, 1, view) == 0L)
            ledger.reserve(ledger.snapshot().availableBytes, "nearest byte pressure").use {
                val before = ledger.snapshot()
                val output = sentinel()
                scene.draw(1, 1, view, output)
                check(output.get(0) == 120.toByte() && output.get(3) == (-1).toByte())
                check(editor.draw(1, 1, 0.0, 0.0, 8.0, 8.0, output))
                check(ledger.snapshot() == before) { "Nearest acquired a sampling lease" }
            }
        }
        editor.setSampling(id, 2)
        checkNotNull(editor.captureScene()).use { scene ->
            check(scene.resourceLedger === ledger)
            check(scene.samplingScratchBytes(1, 1, view) == allowance)
            check(scene.samplingScratchBytes(4, 4, view) == 0L) { "Half scale allocated an unnecessary reduction cache" }
            ledger.reserve(ledger.snapshot().availableBytes - allowance + 1, "HQ competing owner").use {
                val before = ledger.snapshot()
                val sceneOutput = sentinel()
                val sceneFailure = runCatching { scene.draw(1, 1, view, sceneOutput) }.exceptionOrNull()
                check(sceneFailure is ResourceBudgetExceededException && sceneFailure.requestedBytes == allowance)
                unchanged(sceneOutput); check(ledger.snapshot() == before)
                val documentOutput = sentinel()
                val documentFailure = runCatching {
                    editor.draw(1, 1, 0.0, 0.0, 8.0, 8.0, documentOutput)
                }.exceptionOrNull()
                check(documentFailure is ResourceBudgetExceededException && documentFailure.requestedBytes == allowance)
                unchanged(documentOutput); check(ledger.snapshot() == before)
            }
            for (document in listOf(false, true)) {
                val before = ledger.snapshot()
                val output = sentinel()
                val failure = runCatching {
                    if (document) editor.draw(1, 1, 0.0, 0.0, 8.0, 8.0, output)
                    else scene.draw(1, 1, view, output)
                }.exceptionOrNull()
                check(failure is NativeCommandException && failure.status == 5) { "HQ render guard opened: $failure" }
                unchanged(output); sameClaims(before)
            }
            val before = ledger.snapshot()
            val output = sentinel()
            val failure = runCatching { scene.draw(1, 1, view.copy(width = Double.NaN), output) }.exceptionOrNull()
            check(failure is NativeCommandException && failure.status == 1)
            unchanged(output); check(ledger.snapshot() == before)
        }
        editor.setSampling(id, 0)
        editor.beginTransformDraft(instrumentation.targetContext, id).use { draft ->
            draft.update(editor.state().layers.single().transform.copy(x = 1.0))
            draft.captureScene().use { scene ->
                check(scene.resourceLedger === ledger) { "Transform preview escaped its editor ledger" }
                check(scene.samplingScratchBytes(1, 1, view) == 0L)
                scene.draw(1, 1, view, sentinel())
            }
        }
        editor.beginDistortSession(id).use { worker ->
            check(worker.submitQuad(doubleArrayOf(0.0, 0.0, 8.0, 0.0, 8.0, 8.0, 0.0, 8.0)) == 0)
            check(worker.wait(10_000) == NativeDistortSessionBridge.WAIT_READY)
            check(worker.publish() == 0)
            worker.snapshot().use { scene ->
                check(scene.resourceLedger === ledger) { "Distort preview escaped its editor ledger" }
                check(scene.samplingScratchBytes(1, 1, view) == 0L)
                scene.draw(1, 1, view, sentinel())
            }
        }
        checkNotNull(editor.captureDisplayScene()).use { scene -> check(scene.resourceLedger === ledger) }
    }
    check(ledger.snapshot().let { it.reservedBytes == initial.reservedBytes && it.reservations == initial.reservations })
    check(ImageIoAdmission.ledger.snapshot() == globalBefore) { "Custom editor used the global ledger" }
    return "Sampling scratch passed: actual scene/document JNI admission, 520 KiB reduction and zero Nearest/half-scale, " +
        "unchanged output on pressure/invalid/HQ guard rejection, exception cleanup, competing owners, " +
        "and custom ledger inheritance through document/display/transform/Distort scenes; HQ remains unsupported"
}

private fun samplingScratchLeaseChecks(allowance: Long) {
    val ledger = ResourceBudgetLedger(allowance, maximumReservations = 1)
    ledger.reserve(allowance, "all bytes and owner slots used").use {
        val before = ledger.snapshot()
        check(NativeScene.withSamplingScratch(ledger, 0) { 42 } == 42)
        check(ledger.snapshot() == before) { "Zero scratch consumed a bounded owner slot" }
        var rendered = false
        check(runCatching { NativeScene.withSamplingScratch(ledger, allowance) { rendered = true } }
            .exceptionOrNull() is ResourceBudgetExceededException)
        check(!rendered && ledger.snapshot() == before)
    }
    val failed = runCatching {
        NativeScene.withSamplingScratch(ledger, allowance) {
            check(ledger.snapshot().let { it.reservedBytes == allowance && it.reservations == 1 })
            error("Injected native render failure")
        }
    }.exceptionOrNull()
    check(failed?.message == "Injected native render failure" && ledger.snapshot().reservedBytes == 0L &&
        ledger.snapshot().reservations == 0)
    check(runCatching { NativeScene.withSamplingScratch(ledger, -1) { error("must not render") } }
        .exceptionOrNull() is IllegalStateException)
    val start = CountDownLatch(1)
    val attempted = CountDownLatch(2)
    val release = CountDownLatch(1)
    val accepted = AtomicInteger()
    val rejected = AtomicInteger()
    val failures = ConcurrentLinkedQueue<Throwable>()
    val owners = List(2) { index -> Thread({
        try {
            check(start.await(10, TimeUnit.SECONDS))
            NativeScene.withSamplingScratch(ledger, allowance) {
                accepted.incrementAndGet(); attempted.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        } catch (failure: ResourceBudgetExceededException) { rejected.incrementAndGet(); attempted.countDown() }
        catch (failure: Throwable) { failures.add(failure); attempted.countDown() }
    }, "sampling-scratch-$index").apply { start() } }
    try {
        start.countDown()
        check(attempted.await(10, TimeUnit.SECONDS))
        check(failures.isEmpty() && accepted.get() == 1 && rejected.get() == 1)
        check(ledger.snapshot().let { it.reservedBytes == allowance && it.reservations == 1 })
    } finally {
        start.countDown(); release.countDown()
        owners.forEach { it.join(10_000); check(!it.isAlive) }
    }
    check(failures.isEmpty() && ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
}
