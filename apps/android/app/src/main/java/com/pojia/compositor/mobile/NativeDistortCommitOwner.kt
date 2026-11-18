package com.pojia.compositor.mobile

import java.nio.ByteBuffer

/** Calls are synchronous: an exception from Apply must not leave publication running elsewhere.
 * Query NOT_FOUND then proves that this exclusive operation was never admitted. */
internal interface DistortCommitTransport {
    fun prepare(document: Long, draft: ByteBuffer, ticket: ByteBuffer, output: ByteBuffer): Int
    fun apply(document: Long, operation: Long, plan: ByteBuffer, output: ByteBuffer): Int
    fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int
    fun retained(document: Long, output: ByteBuffer): Int
    fun setRetainedLimit(document: Long, bytes: Long): Int
    fun acknowledge(document: Long, instance: Long, operation: Long): Int
}

internal object DirectDistortCommitTransport : DistortCommitTransport {
    override fun prepare(document: Long, draft: ByteBuffer, ticket: ByteBuffer, output: ByteBuffer) =
        NativeDistortBridge.prepare(document, draft, ticket, output)
    override fun apply(document: Long, operation: Long, plan: ByteBuffer, output: ByteBuffer) =
        NativeDistortBridge.apply(document, operation, plan, output)
    override fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer) =
        NativeDistortBridge.query(document, instance, operation, output)
    override fun retained(document: Long, output: ByteBuffer) = NativeDistortBridge.retained(document, output)
    override fun setRetainedLimit(document: Long, bytes: Long) = NativeDistortBridge.setRetainedLimit(document, bytes)
    override fun acknowledge(document: Long, instance: Long, operation: Long) =
        NativeDistortBridge.acknowledge(document, instance, operation)
}

/** Owns one commit's accounting and receipt, not its native document or resource arena.
 * The caller must freeze ALL document mutation, limit changes, and destruction from construction
 * until isResolved. Keep this owner reachable across exceptions and call recover under that gate.
 * An unresolved owner is deliberately not closeable: releasing live claims would undercount memory.
 *
 * arenaReservation covers the entire native Control limit, including its resident baseline. This
 * conservatively counts that baseline twice. Keep this whole claim until native authority is sealed,
 * workers joined, and every image/history/scene/document owner using the Control has retired.
 * This class never closes that claim or infers its retirement from commit/ack/draft cancellation.
 * nativeEnvelopeLimitBytes must be the real immutable Control limit supplied by its trusted owner;
 * it is not a limit guessed from image dimensions or a metadata allowance.
 * UI/controller lifetime integration is the caller's responsibility. */
internal class NativeDistortCommitOwner(
    private val document: Long,
    private val draft: DistortDraftIdentity,
    private val ticket: DistortWorkIdentity,
    private val operation: ULong,
    private val ledger: ResourceBudgetLedger,
    private val documentReservation: ResourceBudgetLedger.Reservation,
    private val arenaReservation: ResourceBudgetLedger.Reservation,
    private val growthBytes: Long,
    private val nativeEnvelopeLimitBytes: Long,
    private val transport: DistortCommitTransport = DirectDistortCommitTransport,
) {
    enum class Phase { READY, PINNED, UNCERTAIN, TERMINAL, SETTLED, RESET_ABORT, COMPLETE, DESTROYED }

    // Allocate every wire carrier before reserving or invoking a publishing operation.
    private val draftWire = NativeDistortBridge.record(NativeDistortBridge.DRAFT_BYTES)
    private val ticketWire = NativeDistortBridge.record(NativeDistortBridge.TICKET_BYTES)
    private val planWire = NativeDistortBridge.record(NativeDistortBridge.PLAN_BYTES)
    private val receiptWire = NativeDistortBridge.record(NativeDistortBridge.RECEIPT_BYTES)
    private val retainedWire = NativeDistortBridge.record(NativeDistortBridge.RETAINED_BYTES)
    private var peak: ResourceBudgetLedger.Reservation? = null
    private var transfer: ResourceBudgetLedger.SplitTransferPlan? = null
    private var originalDocumentBytes = 0L
    private var arenaBytes = 0L
    private var upperBytes = 0L
    private var settlementBytes = -1L
    private var outcome = 4
    private var applyStatus = 4
    @Volatile var phase = Phase.READY
        private set
    val isResolved: Boolean get() = phase == Phase.COMPLETE || phase == Phase.DESTROYED
    internal val resultStatus: Int get() = outcome

    init {
        require(document != 0L && operation != 0uL && growthBytes >= 0 && nativeEnvelopeLimitBytes >= 0)
        require(draft.document == ticket.document && draft.owner == ticket.owner &&
            draft.revision == ticket.revision && draft.source == ticket.source)
        require(documentReservation !== arenaReservation)
        NativeDistortBridge.putDraft(draftWire, draft)
        NativeDistortBridge.putTicket(ticketWire, ticket)
    }

    /** Status 4 with !isResolved means recovery is required, not a failed commit safe to retry.
     * Once dispatch has happened, this method only recovers; it never submits a second Apply. */
    @Synchronized fun commit(): Int {
        if (phase != Phase.READY) return recover()
        val prepared = transport.prepare(document, draftWire, ticketWire, planWire)
        if (prepared != 0) {
            outcome = prepared
            phase = Phase.COMPLETE
            return outcome
        }
        val plan = NativeDistortBridge.plan(planWire)
        check(plan.draft == draft && plan.ticket == ticket)
        check(plan.currentRetainedBytes <= Long.MAX_VALUE.toULong() &&
            plan.metadataAllowanceBytes <= Long.MAX_VALUE.toULong())
        check(!documentReservation.isClosed && !arenaReservation.isClosed)
        originalDocumentBytes = documentReservation.bytes
        arenaBytes = arenaReservation.bytes
        check(plan.currentRetainedBytes.toLong() <= originalDocumentBytes)
        check(arenaBytes >= nativeEnvelopeLimitBytes)
        upperBytes = Math.addExact(originalDocumentBytes, growthBytes)
        val claim = ledger.reserve(growthBytes, "Distort document growth")
        peak = claim
        try {
            transfer = ledger.prepareSplitTransfer(documentReservation, claim, arenaReservation, upperBytes)
        } catch (error: Throwable) {
            claim.close()
            peak = null
            throw error
        }
        // A setter can succeed natively and lose its return. Mark reset required BEFORE calling it.
        phase = Phase.RESET_ABORT
        val limitStatus = transport.setRetainedLimit(document, upperBytes)
        if (limitStatus != 0) {
            outcome = limitStatus
            return recover()
        }
        phase = Phase.PINNED
        clear(receiptWire)
        phase = Phase.UNCERTAIN
        applyStatus = transport.apply(document, operation.toLong(), planWire, receiptWire)
        if (receiptWire.getInt(124) == applyStatus) acceptReceipt()
        return recover()
    }

    /** Query the same identity after lost delivery; never release claims for pending/bad records. */
    @Synchronized fun recover(): Int {
        when (phase) {
            Phase.READY -> return commit()
            Phase.COMPLETE -> return outcome
            Phase.DESTROYED -> return 4
            Phase.PINNED -> { outcome = 4; phase = Phase.RESET_ABORT }
            Phase.UNCERTAIN -> {
                clear(receiptWire)
                val status = transport.query(document, draft.document.toLong(), operation.toLong(), receiptWire)
                if (status == 0) {
                    if (!acceptReceipt()) return 4
                } else if (status == 2 && isZero(receiptWire)) {
                    // Synchronous exclusive transport: no accepted or outstanding native operation.
                    outcome = if (applyStatus in 1..9) applyStatus else 4
                    phase = Phase.RESET_ABORT
                } else return 4
            }
            else -> Unit
        }
        if (phase == Phase.RESET_ABORT) {
            if (transport.setRetainedLimit(document, originalDocumentBytes) != 0) return 4
            transfer!!.abort()
            // Set completion first: close can invoke an availability callback that throws Error.
            phase = Phase.COMPLETE
            peak!!.close()
            return outcome
        }
        if (phase == Phase.TERMINAL) {
            if (settlementBytes < 0) {
                clear(retainedWire)
                if (transport.retained(document, retainedWire) != 0) return 4
                val current = retainedWire.getLong(16)
                if (current < 0 || current > upperBytes) return 4
                // The external document freeze spans dispatch and recovery. A terminal record
                // cannot describe another revision or inventory under that contract. If delivery
                // was malformed, query it afresh rather than repeatedly trusting the bad record.
                if (retainedWire.getLong(0) != receiptWire.getLong(128) ||
                    retainedWire.getLong(8) != receiptWire.getLong(136) ||
                    current != receiptWire.getLong(144)) {
                    phase = Phase.UNCERTAIN
                    return 4
                }
                settlementBytes = current
            }
            // confirm may throw from a scheduling callback AFTER primitive settlement. Retrying
            // these exact values is idempotent, and never falls back to aborting a confirmed plan.
            if (!transfer!!.confirm(settlementBytes, arenaBytes)) return 4
            phase = Phase.SETTLED
        }
        if (phase == Phase.SETTLED) {
            if (transport.setRetainedLimit(document, settlementBytes) != 0) return 4
            // Acknowledgement is last, after budget settlement and restoring the installed ceiling.
            // A lost ack response is retried idempotently, with no Apply or inventory replay.
            if (transport.acknowledge(document, draft.document.toLong(), operation.toLong()) != 0) return 4
            phase = Phase.COMPLETE
            return outcome
        }
        return 4
    }

    private fun acceptReceipt(): Boolean {
        if (receiptWire.getLong(0) != operation.toLong()) return false
        var offset = 0
        while (offset < NativeDistortBridge.PLAN_BYTES) {
            if (receiptWire.getLong(8 + offset) != planWire.getLong(offset)) return false
            offset += 8
        }
        val receiptPhase = receiptWire.getInt(120)
        if (receiptPhase !in 1..2 || receiptWire.getInt(156) != 0 ||
            receiptWire.getInt(124) !in 0..9 || receiptWire.getInt(152) !in 0..1 ||
            receiptWire.getLong(144) < 0) return false
        if (receiptPhase == 1) return false
        val changed = receiptWire.getInt(152) != 0
        val revision = receiptWire.getLong(128).toULong()
        val scene = receiptWire.getLong(136).toULong()
        val baseScene = planWire.getLong(88).toULong()
        if (receiptWire.getInt(124) != 0) {
            if (changed || revision != draft.revision || scene != baseScene ||
                receiptWire.getLong(144) != planWire.getLong(96)) return false
        } else {
            if (baseScene == ULong.MAX_VALUE || scene != baseScene + 1uL) return false
            if (changed) {
                if (draft.revision == ULong.MAX_VALUE || revision != draft.revision + 1uL) return false
            } else if (revision != draft.revision || receiptWire.getLong(144) != planWire.getLong(96)) return false
        }
        outcome = receiptWire.getInt(124)
        phase = Phase.TERMINAL
        return true
    }

    /** Caller attests that the native document AND all work/resource owners are destroyed.
     * This releases only this transaction's pins/growth; caller still owns the D/E claims. */
    @Synchronized fun releasePinsAfterNativeDestruction() {
        if (phase == Phase.DESTROYED) return
        transfer?.abort()
        phase = Phase.DESTROYED
        peak?.close()
    }

    private fun clear(record: ByteBuffer) {
        var offset = 0
        while (offset < record.capacity()) { record.putLong(offset, 0); offset += 8 }
    }
    private fun isZero(record: ByteBuffer): Boolean {
        var offset = 0
        while (offset < record.capacity()) {
            if (record.getLong(offset) != 0L) return false
            offset += 8
        }
        return true
    }
}
