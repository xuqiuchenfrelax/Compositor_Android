package com.pojia.compositor.mobile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic pressure and ownership boundaries for display-admission notifications. */
internal fun resourceAvailabilitySmoke(): String {
    val ledger = ResourceBudgetLedger(100)
    val owner = ledger.reserve(100, "competing job")
    val notifications = AtomicInteger()
    val waiter = ledger.awaitAvailable(20) { notifications.incrementAndGet() }
    check(notifications.get() == 0 && ledger.tryReserve(20, "display") == null)
    owner.resize(90)
    check(notifications.get() == 0)
    owner.resize(80)
    check(notifications.get() == 1)
    owner.resize(70); owner.close(); waiter.close(); waiter.close()
    check(notifications.get() == 1 && ledger.snapshot().reservedBytes == 0L)

    val cancelledOwner = ledger.reserve(100, "cancel pressure")
    var cancelledNotifications = 0
    ledger.awaitAvailable(1) { cancelledNotifications++ }.close()
    cancelledOwner.close()
    check(cancelledNotifications == 0)
    // Releasing between failed admission and registration must not lose the final redraw.
    val released = ledger.reserve(100, "released before registration")
    check(ledger.tryReserve(1, "display") == null)
    released.close()
    ledger.awaitAvailable(1) { notifications.incrementAndGet() }.close()
    check(notifications.get() == 2)

    val slots = ResourceBudgetLedger(100, maximumReservations = 1)
    val slot = slots.reserve(0, "occupied zero-byte slot")
    var slotReady = false
    slots.awaitAvailable(0) { slotReady = true }.use {
        check(!slotReady && slots.tryReserve(0, "display") == null)
        slot.close()
        check(slotReady)
    }
    val resident = ResourceBudgetLedger(100, residentBytes = 100)
    var residentReady = false
    resident.awaitAvailable(10) { residentReady = true }.use {
        resident.updateResident(95); check(!residentReady)
        resident.updateResident(90); check(residentReady)
    }

    val installed = ledger.reserve(40, "document")
    val edit = ledger.reserve(60, "edit")
    val transfer = ledger.prepareTransfer(installed, edit, 100)
    var transferReady = false
    ledger.awaitAvailable(20) { transferReady = true }.use {
        check(!transferReady && transfer.confirm(80) && transferReady)
        check(edit.isClosed && installed.bytes == 80L && transfer.confirm(80))
    }
    installed.close()
    val splitDocument = ledger.reserve(40, "split document")
    val peak = ledger.reserve(50, "split peak")
    val draft = ledger.reserve(10, "split draft")
    val split = ledger.prepareSplitTransfer(splitDocument, peak, draft, 100)
    var splitReady = false
    ledger.awaitAvailable(30) { splitReady = true }.use {
        check(!splitReady && split.confirm(60, 10) && splitReady)
        check(peak.isClosed && splitDocument.bytes == 60L && draft.bytes == 10L)
    }
    splitDocument.close(); draft.close()

    // A callback may be scheduled synchronously, but it must never hold the ledger gate.
    val done = CountDownLatch(1)
    var callbackOutsideGate = false
    ledger.awaitAvailable(1) {
        Thread {
            ledger.reserve(1, "callback lock witness").close()
            done.countDown()
        }.start()
        callbackOutsideGate = done.await(5, TimeUnit.SECONDS)
    }.close()
    check(callbackOutsideGate) { "Availability callback held the ledger gate" }
    check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
    val schedulingFailure = ledger.reserve(100, "notification failure witness")
    ledger.awaitAvailable(1) { throw IllegalStateException("Expected scheduling failure witness") }
    schedulingFailure.close()
    check(schedulingFailure.isClosed && ledger.snapshot().reservedBytes == 0L)
    check(runCatching { ledger.awaitAvailable(101) {} }.exceptionOrNull() is IllegalArgumentException)
    check(notifications.get() == 2 && ledger.snapshot().peakReservedBytes == 100L)
    val coordinatedPressure = ledger.reserve(100, "display coordination")
    var frameRequests = 0
    val admission = DisplaySceneAdmission(ledger, requestFrame = { frameRequests++ })
    check(admission.beginAttempt())
    admission.defer(20)
    check(admission.snapshot().let { it.waiting && it.deferred && it.attempts == 1L && it.capturedScenes == 0L })
    coordinatedPressure.close()
    check(frameRequests == 1 && admission.hasReadyFrame)
    check(admission.beginAttempt() && !admission.hasReadyFrame)
    admission.captured()
    check(admission.snapshot().let { !it.waiting && !it.deferred && it.capturedScenes == 1L && it.wakeRequests == 1L })
    // A ready callback may run during registration; the returned waiter must not remain pending.
    admission.defer(1)
    check(frameRequests == 2 && admission.hasReadyFrame && !admission.snapshot().waiting)
    check(admission.beginAttempt())
    val retiringPressure = ledger.reserve(100, "retiring display")
    admission.defer(1)
    admission.close(); admission.close()
    retiringPressure.close()
    check(frameRequests == 2 && !admission.hasReadyFrame && !admission.beginAttempt())
    check(admission.snapshot().let { it.closed && !it.waiting && !it.deferred })
    return "PASS: byte/zero-byte slot pressure, shrink/release/resident/transfer/split wake, cancellation, immediate recheck, callback outside ledger gate and display coordinator synchronous-wake/retirement; no budget expansion."
}
