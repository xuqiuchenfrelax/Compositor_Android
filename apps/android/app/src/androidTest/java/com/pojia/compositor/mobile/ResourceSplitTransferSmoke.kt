package com.pojia.compositor.mobile

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One admitted peak must cover both native publication owners even while other owners compete. */
internal object ResourceSplitTransferSmoke {
    fun run() {
        val ledger = ResourceBudgetLedger(100)
        val document = ledger.reserve(20, "installed")
        val peak = ledger.reserve(60, "atomic press peak")
        val draft = ledger.reserve(0, "unpublished draft")
        val other = ledger.reserve(20, "competing owner")
        val plan = ledger.prepareSplitTransfer(document, peak, draft, 60)
        val pinned = ledger.snapshot()
        check(!plan.confirm(-1, 0) && !plan.confirm(61, 0) && !plan.confirm(60, 21))
        check(!plan.confirm(1, Long.MAX_VALUE) && !plan.confirm(Long.MAX_VALUE, 1))
        check(ledger.snapshot() == pinned)
        for (claim in listOf(document, peak, draft)) {
            check(runCatching { claim.close() }.isFailure)
            check(runCatching { claim.resize(0) }.isFailure)
        }
        check(runCatching { ledger.prepareTransfer(document, other, 20) }.isFailure)
        check(runCatching { ledger.prepareSplitTransfer(other, peak, draft, 20) }.isFailure)
        check(ledger.tryReserve(1, "unreserved growth") == null)
        check(plan.confirm(35, 25))
        check(peak.isClosed && document.bytes == 35L && draft.bytes == 25L)
        check(ledger.snapshot().reservedBytes == 80L && ledger.snapshot().reservations == 3)
        check(plan.confirm(35, 25) && !plan.confirm(34, 25))
        plan.abort()
        document.resize(34); draft.resize(24)
        val reused = ledger.snapshot()
        check(plan.confirm(35, 25) && ledger.snapshot() == reused)
        check(document.bytes == 34L && draft.bytes == 24L)
        document.resize(35); draft.resize(25)
        draft.close()
        check(document.bytes == 35L && !document.isClosed && ledger.snapshot().reservedBytes == 55L)
        document.close(); other.close()
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)

        val aborted = ResourceBudgetLedger(10)
        val installed = aborted.reserve(2, "installed")
        val transient = aborted.reserve(8, "peak")
        val shell = aborted.reserve(0, "shell")
        val failed = aborted.prepareSplitTransfer(installed, transient, shell, 10)
        failed.abort(); failed.abort()
        check(!failed.confirm(2, 1))
        check(installed.bytes == 2L && transient.bytes == 8L && shell.bytes == 0L)
        transient.close(); shell.close(); installed.close()
        check(aborted.snapshot().reservedBytes == 0L)

        val exact = ResourceBudgetLedger(Long.MAX_VALUE)
        val large = exact.reserve(Long.MAX_VALUE - 1, "large document")
        val last = exact.reserve(1, "last byte")
        val empty = exact.reserve(0, "empty draft")
        val boundary = exact.prepareSplitTransfer(large, last, empty, Long.MAX_VALUE)
        check(!boundary.confirm(Long.MAX_VALUE, 1))
        check(boundary.confirm(Long.MAX_VALUE - 1, 1))
        check(exact.snapshot().reservedBytes == Long.MAX_VALUE && last.isClosed)
        empty.close(); large.close()
        check(exact.snapshot().reservedBytes == 0L)

        val validation = ResourceBudgetLedger(10)
        val one = validation.reserve(2, "one")
        val two = validation.reserve(3, "two")
        val three = validation.reserve(0, "three")
        val foreign = ResourceBudgetLedger(10).reserve(0, "foreign")
        val before = validation.snapshot()
        check(runCatching { validation.prepareSplitTransfer(one, one, three, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(one, two, one, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(one, two, two, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(one, two, foreign, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(foreign, two, three, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(one, foreign, three, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(one, two, three, 6) }.exceptionOrNull() is ResourceBudgetExceededException)
        val closed = validation.reserve(0, "closed").also { it.close() }
        check(runCatching { validation.prepareSplitTransfer(closed, two, three, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(one, closed, three, 2) }.isFailure)
        check(runCatching { validation.prepareSplitTransfer(one, two, closed, 2) }.isFailure)
        check(validation.snapshot() == before)
        // Failed preparation did not pin any owner or consume an additional owner slot.
        one.resize(1); two.close(); three.close(); one.close(); foreign.close()

        competingSettlement()
    }

    private fun competingSettlement() {
        val ledger = ResourceBudgetLedger(100)
        val document = ledger.reserve(10, "document")
        val peak = ledger.reserve(70, "peak")
        val draft = ledger.reserve(0, "draft")
        val plan = ledger.prepareSplitTransfer(document, peak, draft, 60)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val competitor = Thread {
            try {
                ledger.reserve(20, "competitor").use {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) { failures.add(error); started.countDown() }
        }.apply { start() }
        try {
            check(started.await(10, TimeUnit.SECONDS) && failures.isEmpty())
            check(ledger.snapshot().availableBytes == 0L)
            check(plan.confirm(60, 20))
            check(ledger.snapshot().reservedBytes == 100L)
        } finally {
            release.countDown(); competitor.join(10_000)
        }
        check(!competitor.isAlive && failures.isEmpty())
        draft.close(); document.close()
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
    }
}
