package com.pojia.compositor.mobile

internal class ResourceBudgetExceededException(val requestedBytes: Long, val availableBytes: Long,
    label: String) : IllegalStateException("Not enough reserved memory for $label")

internal data class ResourceBudgetSnapshot(val limitBytes: Long, val residentBytes: Long,
    val reservedBytes: Long, val availableBytes: Long, val peakReservedBytes: Long, val reservations: Int)

/** Accounts only for resources whose owners explicitly acquire a lease. This is not an RSS ceiling. */
internal class ResourceBudgetLedger(val limitBytes: Long, residentBytes: Long = 0,
    private val maximumReservations: Int = 64) {
    private val gate = Any()
    private var resident = residentBytes
    private var reserved = residentBytes
    private var peak = residentBytes
    private class Entry(val lease: Reservation, var bytes: Long, var transfer: Any? = null)
    // Primitive fields and a pre-sized array avoid allocating map entries/boxed byte counts at publication.
    private val leases = ArrayList<Entry>(maximumReservations)
    private val availabilityWaiters = ArrayList<AvailabilityWaiter>()

    init {
        require(limitBytes >= 0 && residentBytes in 0..limitBytes)
        require(maximumReservations > 0)
    }

    fun snapshot(): ResourceBudgetSnapshot = synchronized(gate) {
        ResourceBudgetSnapshot(limitBytes, resident, reserved, limitBytes - reserved, peak, leases.size)
    }

    fun reserve(bytes: Long, label: String, admissionCeilingBytes: Long = limitBytes): Reservation = synchronized(gate) {
        validate(bytes, label, admissionCeilingBytes)
        acquire(bytes, label, admissionCeilingBytes) ?: throw ResourceBudgetExceededException(
            bytes, available(reserved, admissionCeilingBytes), label)
    }

    fun tryReserve(bytes: Long, label: String, admissionCeilingBytes: Long = limitBytes): Reservation? = synchronized(gate) {
        validate(bytes, label, admissionCeilingBytes)
        acquire(bytes, label, admissionCeilingBytes)
    }

    /** One notification when a claim could fit, not a promise that it has been reserved.
     * Registration rechecks under the admission gate, including reservation-slot pressure.
     * Callbacks run outside this gate and must only schedule work, never acquire editor locks. */
    fun awaitAvailable(bytes: Long, onAvailable: () -> Unit): AvailabilityWaiter {
        require(bytes in 0..limitBytes)
        val waiter = synchronized(gate) {
            AvailabilityWaiter(this, bytes, onAvailable).also {
                if (canAcquire(bytes)) it.ready = true else availabilityWaiters.add(it)
            }
        }
        waiter.signal()
        return waiter
    }

    private fun canAcquire(bytes: Long): Boolean =
        leases.size < maximumReservations && reserved <= limitBytes && bytes <= limitBytes - reserved

    private fun notifyAvailable() {
        // Publication settlement must not allocate a second collection after native commit.
        // A callback can register another waiter, so bound this pass by the original count.
        var remaining = synchronized(gate) { availabilityWaiters.size }
        while (remaining-- > 0) {
            val waiter = synchronized(gate) {
                var index = 0
                while (index < availabilityWaiters.size && !canAcquire(availabilityWaiters[index].requestedBytes)) index++
                if (index == availabilityWaiters.size) return
                availabilityWaiters.removeAt(index).also { it.ready = true }
            }
            waiter.signal()
        }
    }

    class AvailabilityWaiter internal constructor(private val ledger: ResourceBudgetLedger,
        internal val requestedBytes: Long, private var callback: (() -> Unit)?) : AutoCloseable {
        internal var ready = false
        internal fun signal() {
            val action = synchronized(ledger.gate) {
                if (!ready) null else callback.also { callback = null; ready = false }
            }
            if (action != null) try { action() } catch (error: Exception) {
                // A scheduling callback must not turn a settled resource release into a failed commit.
                java.util.logging.Logger.getLogger(ResourceBudgetLedger::class.java.name)
                    .log(java.util.logging.Level.WARNING, "Resource availability callback failed", error)
            }
        }
        override fun close() = synchronized(ledger.gate) {
            ledger.availabilityWaiters.remove(this)
            callback = null; ready = false
        }
    }

    private fun validate(bytes: Long, label: String, admissionCeilingBytes: Long) {
        require(bytes >= 0 && admissionCeilingBytes >= 0)
        require(label.isNotBlank() && label.length <= 256)
    }

    private fun available(others: Long, admissionCeilingBytes: Long): Long =
        (minOf(limitBytes, admissionCeilingBytes) - others).coerceAtLeast(0)

    /** A sampled external ceiling applies to the total claim, including other owners, under the gate. */
    private fun acquire(bytes: Long, label: String, admissionCeilingBytes: Long): Reservation? {
        val ceiling = minOf(limitBytes, admissionCeilingBytes)
        if (leases.size >= maximumReservations || reserved > ceiling || bytes > ceiling - reserved) return null
        val lease = Reservation(this, label)
        leases.add(Entry(lease, bytes))
        reserved = Math.addExact(reserved, bytes)
        peak = maxOf(peak, reserved)
        return lease
    }

    /** Update an explicitly measured baseline without discarding other owners' outstanding leases. */
    fun updateResident(bytes: Long) {
        synchronized(gate) {
            require(bytes >= 0)
            val outstanding = reserved - resident
            if (bytes > limitBytes - outstanding)
                throw ResourceBudgetExceededException(bytes, limitBytes - outstanding, "resident resources")
            resident = bytes
            reserved = Math.addExact(bytes, outstanding)
            peak = maxOf(peak, reserved)
        }
        notifyAvailable()
    }

    private fun entry(lease: Reservation): Entry? {
        var index = 0
        while (index < leases.size) {
            val value = leases[index++]
            if (value.lease === lease) return value
        }
        return null
    }
    private fun bytes(lease: Reservation): Long = synchronized(gate) { entry(lease)?.bytes ?: 0 }
    private fun closed(lease: Reservation): Boolean = synchronized(gate) { entry(lease) == null }
    private fun resize(lease: Reservation, bytes: Long, admissionCeilingBytes: Long) {
        synchronized(gate) {
            require(bytes >= 0 && admissionCeilingBytes >= 0)
            val value = checkNotNull(entry(lease)) { "Resource reservation is closed" }
            check(value.transfer == null) { "A prepared resource transfer owns this reservation" }
            val previous = value.bytes
            val others = reserved - previous
            val ceiling = minOf(limitBytes, admissionCeilingBytes)
            if (others > ceiling || bytes > ceiling - others)
                throw ResourceBudgetExceededException(bytes, available(others, admissionCeilingBytes), lease.label)
            value.bytes = bytes
            reserved = Math.addExact(others, bytes)
            peak = maxOf(peak, reserved)
        }
        notifyAvailable()
    }
    private fun release(lease: Reservation) {
        synchronized(gate) {
            val value = entry(lease) ?: return
            check(value.transfer == null) { "A prepared resource transfer owns this reservation" }
            reserved -= value.bytes
            leases.remove(value)
            Unit
        }
        notifyAvailable()
    }

    /** Pins the document/edit claims before native publication. The upper bound cannot exceed
     * their already admitted sum, so competing owners cannot invalidate a later shrinking transfer. */
    fun prepareTransfer(document: Reservation, edit: Reservation, finalDocumentUpperBytes: Long): TransferPlan = synchronized(gate) {
        require(document !== edit && finalDocumentUpperBytes >= 0)
        val installed = checkNotNull(entry(document)) { "Document reservation is closed or belongs to another ledger" }
        val transient = checkNotNull(entry(edit)) { "Edit reservation is closed or belongs to another ledger" }
        check(installed.transfer == null && transient.transfer == null) { "Resource transfer is already prepared" }
        val combined = Math.addExact(installed.bytes, transient.bytes)
        if (finalDocumentUpperBytes > combined)
            throw ResourceBudgetExceededException(finalDocumentUpperBytes, combined, "document transfer")
        val plan = TransferPlan(this, document, edit, finalDocumentUpperBytes)
        installed.transfer = plan; transient.transfer = plan
        plan
    }
    private fun confirm(plan: TransferPlan, finalDocumentBytes: Long): Boolean {
        val confirmed = synchronized(gate) {
            if (plan.confirmed) return@synchronized finalDocumentBytes == plan.confirmedBytes
            if (plan.aborted || finalDocumentBytes < 0 || finalDocumentBytes > plan.finalDocumentUpperBytes) return@synchronized false
            val installed = entry(plan.document) ?: return@synchronized false
            val transient = entry(plan.edit) ?: return@synchronized false
            if (installed.transfer !== plan || transient.transfer !== plan) return@synchronized false
            // All records are allocated and pinned. Only primitive writes and array removal remain.
            reserved -= installed.bytes + transient.bytes - finalDocumentBytes
            installed.bytes = finalDocumentBytes; installed.transfer = null
            transient.transfer = null; leases.remove(transient)
            plan.confirmedBytes = finalDocumentBytes; plan.confirmed = true
            true
        }
        if (confirmed) notifyAvailable()
        return confirmed
    }
    private fun abort(plan: TransferPlan) = synchronized(gate) {
        if (plan.confirmed || plan.aborted) return@synchronized
        entry(plan.document)?.let { if (it.transfer === plan) it.transfer = null }
        entry(plan.edit)?.let { if (it.transfer === plan) it.transfer = null }
        plan.aborted = true
    }

    /** Atomic Move admission may grow the installed selection and create a separately owned draft.
     * Pin all three claims before calling native; confirmation only redistributes admitted bytes. */
    fun prepareSplitTransfer(document: Reservation, peak: Reservation, draft: Reservation,
        finalDocumentUpperBytes: Long): SplitTransferPlan = synchronized(gate) {
        require(document !== peak && document !== draft && peak !== draft && finalDocumentUpperBytes >= 0)
        val installed = checkNotNull(entry(document)) { "Document reservation is closed or belongs to another ledger" }
        val transient = checkNotNull(entry(peak)) { "Peak reservation is closed or belongs to another ledger" }
        val adopted = checkNotNull(entry(draft)) { "Draft reservation is closed or belongs to another ledger" }
        check(installed.transfer == null && transient.transfer == null && adopted.transfer == null) {
            "Resource transfer is already prepared"
        }
        val combined = Math.addExact(Math.addExact(installed.bytes, transient.bytes), adopted.bytes)
        if (finalDocumentUpperBytes > combined)
            throw ResourceBudgetExceededException(finalDocumentUpperBytes, combined, "split document transfer")
        val plan = SplitTransferPlan(this, document, peak, draft, finalDocumentUpperBytes, combined)
        installed.transfer = plan; transient.transfer = plan; adopted.transfer = plan
        plan
    }
    private fun confirm(plan: SplitTransferPlan, finalDocumentBytes: Long, finalDraftBytes: Long): Boolean {
        val confirmed = synchronized(gate) {
            if (plan.confirmed) return@synchronized finalDocumentBytes == plan.confirmedDocumentBytes &&
                finalDraftBytes == plan.confirmedDraftBytes
            if (plan.aborted || finalDocumentBytes < 0 || finalDraftBytes < 0 ||
                finalDocumentBytes > plan.finalDocumentUpperBytes || finalDraftBytes > plan.combinedBytes ||
                finalDocumentBytes > plan.combinedBytes - finalDraftBytes) return@synchronized false
            val installed = entry(plan.document) ?: return@synchronized false
            val transient = entry(plan.peak) ?: return@synchronized false
            val adopted = entry(plan.draft) ?: return@synchronized false
            if (installed.transfer !== plan || transient.transfer !== plan || adopted.transfer !== plan) return@synchronized false
            // This shrinks the total claim; competing owners cannot invalidate publication settlement.
            reserved -= plan.combinedBytes - finalDocumentBytes - finalDraftBytes
            installed.bytes = finalDocumentBytes; installed.transfer = null
            adopted.bytes = finalDraftBytes; adopted.transfer = null
            transient.transfer = null; leases.remove(transient)
            plan.confirmedDocumentBytes = finalDocumentBytes; plan.confirmedDraftBytes = finalDraftBytes
            plan.confirmed = true
            true
        }
        if (confirmed) notifyAvailable()
        return confirmed
    }
    private fun abort(plan: SplitTransferPlan) = synchronized(gate) {
        if (plan.confirmed || plan.aborted) return@synchronized
        entry(plan.document)?.let { if (it.transfer === plan) it.transfer = null }
        entry(plan.peak)?.let { if (it.transfer === plan) it.transfer = null }
        entry(plan.draft)?.let { if (it.transfer === plan) it.transfer = null }
        plan.aborted = true
    }

    /** A malformed or unknown post-publication receipt leaves every claim pinned. Query the exact
     * native receipt before confirming; abort is only valid before publication or after native destruction. */
    class SplitTransferPlan internal constructor(private val ledger: ResourceBudgetLedger,
        internal val document: Reservation, internal val peak: Reservation, internal val draft: Reservation,
        val finalDocumentUpperBytes: Long, internal val combinedBytes: Long) {
        internal var confirmed = false
        internal var confirmedDocumentBytes = 0L
        internal var confirmedDraftBytes = 0L
        internal var aborted = false
        fun confirm(finalDocumentBytes: Long, finalDraftBytes: Long): Boolean =
            ledger.confirm(this, finalDocumentBytes, finalDraftBytes)
        fun abort() = ledger.abort(this)
    }

    /** Native failure/cancel aborts the plan before releasing edit storage. Native success confirms
     * it before DTO allocation/notification. An invalid post-publication receipt keeps both claims
     * pinned and returns false; callers must reconcile, never release/retry/undo a confirmed commit. */
    class TransferPlan internal constructor(private val ledger: ResourceBudgetLedger,
        internal val document: Reservation, internal val edit: Reservation, val finalDocumentUpperBytes: Long) {
        internal var confirmed = false
        internal var confirmedBytes = 0L
        internal var aborted = false
        fun confirm(finalDocumentBytes: Long): Boolean = ledger.confirm(this, finalDocumentBytes)
        fun abort() = ledger.abort(this)
    }

    /** Closing releases an accounting claim; the owner must separately destroy or retain its resource. */
    class Reservation internal constructor(private val ledger: ResourceBudgetLedger, val label: String) : AutoCloseable {
        val bytes: Long get() = ledger.bytes(this)
        val isClosed: Boolean get() = ledger.closed(this)
        fun resize(bytes: Long, admissionCeilingBytes: Long = ledger.limitBytes) =
            ledger.resize(this, bytes, admissionCeilingBytes)
        override fun close() = ledger.release(this)
    }
}
