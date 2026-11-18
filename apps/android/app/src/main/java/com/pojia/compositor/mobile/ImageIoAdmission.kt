package com.pojia.compositor.mobile

import android.app.ActivityManager
import android.content.Context

/** Shared image-job accounting, not a unified document/GPU ledger or a process memory guarantee. */
internal object ImageIoAdmission {
    private const val MIB = 1024L * 1024
    private const val MANAGED_LIMIT = 512 * MIB
    const val CODEC_ALLOWANCE = 16 * MIB
    const val NATIVE_METADATA_ALLOWANCE = 4 * MIB
    val ledger = ResourceBudgetLedger(MANAGED_LIMIT - ProjectAdmissionPolicy.EXTERNAL_RESERVE)

    fun reserve(context: Context, ledger: ResourceBudgetLedger, peakBytes: Long,
        residentBytes: Long, label: String): ResourceBudgetLedger.Reservation {
        require(residentBytes >= 0)
        val total = Math.addExact(peakBytes, residentBytes)
        // Counting the resident snapshot once per simultaneous job is deliberately conservative.
        return ledger.reserve(total, label, observedHeadroom(context))
    }

    /** Reserve the currently admissible allowance, with the sampled device ceiling applied
     * again under the ledger gate. Native determines the actual bounded command peak. */
    fun reserveAvailable(context: Context, ledger: ResourceBudgetLedger, label: String): ResourceBudgetLedger.Reservation {
        val ceiling = observedHeadroom(context)
        val snapshot = ledger.snapshot()
        val allowance = minOf(snapshot.availableBytes, (ceiling - snapshot.reservedBytes).coerceAtLeast(0))
        return ledger.reserve(allowance, label, ceiling)
    }

    fun resize(context: Context, reservation: ResourceBudgetLedger.Reservation,
        peakBytes: Long, residentBytes: Long) {
        val total = Math.addExact(peakBytes, residentBytes)
        if (total > reservation.bytes) reservation.resize(total, observedHeadroom(context))
        else reservation.resize(total)
    }

    private fun observedHeadroom(context: Context): Long {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        check(!memory.lowMemory) {
            "Not enough device memory for this image; close other apps or use a smaller image"
        }
        // Applied under the ledger gate to all image-job claims. Already allocated claims may be double counted.
        return (memory.availMem - memory.threshold).coerceAtLeast(0)
    }
}
