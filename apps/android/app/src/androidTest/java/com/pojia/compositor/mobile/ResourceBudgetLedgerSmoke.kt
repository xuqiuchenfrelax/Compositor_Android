package com.pojia.compositor.mobile

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Pure JVM checks: competing owners must not each consume the same remaining capacity. */
internal object ResourceBudgetLedgerSmoke {
    fun run(): String {
        val ledger = ResourceBudgetLedger(100, 20)
        val lease = ledger.reserve(30, "image import")
        lease.resize(50)
        check(ledger.snapshot().reservedBytes == 70L)
        val before = ledger.snapshot()
        check(runCatching { lease.resize(81) }.exceptionOrNull() is ResourceBudgetExceededException)
        check(ledger.snapshot() == before && lease.bytes == 50L)
        check(runCatching { ledger.updateResident(51) }.exceptionOrNull() is ResourceBudgetExceededException)
        check(ledger.snapshot() == before)
        ledger.updateResident(40)
        lease.resize(10)
        check(ledger.snapshot().reservedBytes == 50L)
        lease.close(); lease.close()
        check(lease.isClosed && lease.bytes == 0L && ledger.snapshot().reservedBytes == 40L)
        check(runCatching { lease.resize(1) }.exceptionOrNull() is IllegalStateException)
        val failure = runCatching {
            ledger.reserve(60, "PNG export").use { error("Injected codec failure") }
        }.exceptionOrNull()
        check(failure?.message == "Injected codec failure" && ledger.snapshot().reservedBytes == 40L)

        val bounded = ResourceBudgetLedger(1, maximumReservations = 2)
        val first = bounded.reserve(0, "zero-byte owner")
        val second = bounded.reserve(0, "second owner")
        check(bounded.tryReserve(0, "third owner") == null)
        first.close()
        bounded.reserve(1, "replacement owner").close()
        second.close()
        check(bounded.snapshot().reservations == 0)

        val exact = ResourceBudgetLedger(Long.MAX_VALUE, Long.MAX_VALUE - 7)
        exact.reserve(7, "exact capacity").use {
            check(exact.snapshot().reservedBytes == Long.MAX_VALUE)
            check(exact.tryReserve(1, "overflow owner") == null)
            check(runCatching { it.resize(Long.MAX_VALUE) }.exceptionOrNull() is ResourceBudgetExceededException)
            check(runCatching { exact.updateResident(Long.MAX_VALUE) }.exceptionOrNull() is ResourceBudgetExceededException)
            check(exact.snapshot().reservedBytes == Long.MAX_VALUE)
        }
        check(exact.snapshot().reservedBytes == Long.MAX_VALUE - 7)
        check(runCatching { ResourceBudgetLedger(-1) }.isFailure)
        check(runCatching { ResourceBudgetLedger(1, 2) }.isFailure)
        check(runCatching { ResourceBudgetLedger(1, maximumReservations = 0) }.isFailure)
        check(runCatching { ledger.reserve(-1, "invalid") }.isFailure)
        check(runCatching { ledger.tryReserve(1, "") }.isFailure)
        check(runCatching { ledger.updateResident(-1) }.isFailure)

        admissionCeilings()
        competingReservations()
        competingReservations(limit = 512, resident = 0, bytes = 80, ceiling = 100, ownerCount = 2, expected = 1)
        competingClose()
        ResourceTransferSmoke.run()
        ResourceSplitTransferSmoke.run()
        return "Resource ledger: atomic competing admission with external ceilings, bounded owners, exception/idempotent close, " +
            "transactional resize/resident update and Long.MAX_VALUE boundaries passed"
    }

    private fun admissionCeilings() {
        val ledger = ResourceBudgetLedger(200, 20)
        ledger.reserve(60, "existing owner", 100).use { lease ->
            val before = ledger.snapshot()
            val rejected = runCatching { ledger.reserve(30, "ceiling rejection", 100) }.exceptionOrNull()
            check(rejected is ResourceBudgetExceededException && rejected.availableBytes == 20L)
            check(ledger.tryReserve(0, "lower ceiling", 79) == null)
            val resize = runCatching { lease.resize(81, 100) }.exceptionOrNull()
            check(resize is ResourceBudgetExceededException && resize.availableBytes == 80L)
            check(ledger.snapshot() == before && lease.bytes == 60L)
            val belowResident = runCatching { lease.resize(0, 19) }.exceptionOrNull()
            check(belowResident is ResourceBudgetExceededException && belowResident.availableBytes == 0L)
            check(ledger.snapshot() == before)
            lease.resize(40, 60)
            check(ledger.snapshot().reservedBytes == 60L)
        }
        check(runCatching { ledger.reserve(0, "negative ceiling", -1) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { ledger.tryReserve(0, "negative ceiling", -1) }.exceptionOrNull() is IllegalArgumentException)
        ledger.reserve(180, "static limit", Long.MAX_VALUE).use { lease ->
            check(ledger.tryReserve(1, "cannot raise limit", Long.MAX_VALUE) == null)
            check(runCatching { lease.resize(181, Long.MAX_VALUE) }.exceptionOrNull() is ResourceBudgetExceededException)
            check(runCatching { lease.resize(0, -1) }.exceptionOrNull() is IllegalArgumentException)
        }
        ResourceBudgetLedger(1).reserve(0, "zero ceiling", 0).close()
    }

    private fun competingReservations(limit: Long = 100, resident: Long = 20, bytes: Long = 40,
        ceiling: Long = 100, ownerCount: Int = 16, expected: Int = 2) {
        val ledger = ResourceBudgetLedger(limit, resident)
        val ready = CountDownLatch(ownerCount)
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(ownerCount)
        val release = CountDownLatch(1)
        val accepted = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val owners = List(ownerCount) { index -> Thread({
            var lease: ResourceBudgetLedger.Reservation? = null
            try {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                lease = ledger.tryReserve(bytes, "competing owner $index", ceiling)
                if (lease != null) accepted.incrementAndGet()
                attempted.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            } catch (error: Throwable) { failures.add(error); attempted.countDown() }
            finally { lease?.close() }
        }, "resource-owner-$index").apply { start() } }
        try {
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            check(attempted.await(10, TimeUnit.SECONDS))
            check(failures.isEmpty()) { failures.toString() }
            check(accepted.get() == expected)
            val snapshot = ledger.snapshot()
            check(snapshot.reservedBytes == resident + expected * bytes && snapshot.reservations == expected)
            check(snapshot.reservedBytes <= minOf(limit, ceiling))
        } finally {
            start.countDown(); release.countDown()
            owners.forEach { it.join(10_000); check(!it.isAlive) }
        }
        check(failures.isEmpty()) { failures.toString() }
        val snapshot = ledger.snapshot()
        check(snapshot.reservedBytes == resident && snapshot.peakReservedBytes == resident + expected * bytes &&
            snapshot.reservations == 0)
    }

    private fun competingClose() {
        val ledger = ResourceBudgetLedger(64)
        val lease = ledger.reserve(64, "shared cleanup")
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val owners = List(16) { Thread({
            try { check(start.await(10, TimeUnit.SECONDS)); lease.close() }
            catch (error: Throwable) { failures.add(error) }
        }).apply { start() } }
        try { start.countDown() }
        finally { owners.forEach { it.join(10_000); check(!it.isAlive) } }
        check(failures.isEmpty()) { failures.toString() }
        check(lease.isClosed && ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
    }
}
