package com.pojia.compositor.mobile

/** Publication transfers preserve admitted ownership even when competing admission changes. */
internal object ResourceTransferSmoke {
    fun run() {
        val ledger = ResourceBudgetLedger(100)
        val document = ledger.reserve(30, "document")
        val edit = ledger.reserve(40, "edit")
        val plan = ledger.prepareTransfer(document, edit, 65)
        check(runCatching { document.resize(1) }.isFailure)
        check(runCatching { edit.close() }.isFailure)
        val other = ledger.reserve(30, "competing display scene")
        check(ledger.tryReserve(0, "lower sampled headroom", 99) == null)
        // Shrinking transfer uses pinned capacity, not the later sampled ceiling.
        check(plan.confirm(60))
        check(document.bytes == 60L && edit.isClosed && ledger.snapshot().reservedBytes == 90L)
        check(plan.confirm(60) && !plan.confirm(59))
        plan.abort()
        check(document.bytes == 60L)
        other.close()

        val failedEdit = ledger.reserve(20, "failed native publication")
        val aborted = ledger.prepareTransfer(document, failedEdit, 80)
        check(!aborted.confirm(81) && !aborted.confirm(-1))
        check(document.bytes == 60L && failedEdit.bytes == 20L)
        check(runCatching { document.close() }.isFailure)
        aborted.abort(); aborted.abort()
        check(!aborted.confirm(60))
        failedEdit.close()
        check(ledger.snapshot().reservedBytes == 60L)

        // Repeated no-op/changed receipts retire transient owners instead of accumulating peaks.
        repeat(100) {
            val temporary = ledger.reserve(30, "next edit")
            val next = ledger.prepareTransfer(document, temporary, 90)
            check(next.confirm(60))
            check(temporary.isClosed && ledger.snapshot().reservations == 1)
        }
        val foreign = ResourceBudgetLedger(100).reserve(10, "foreign")
        check(runCatching { ledger.prepareTransfer(document, foreign, 70) }.isFailure)
        foreign.close()
        document.close()
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
    }
}
