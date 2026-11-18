package com.pojia.compositor.mobile

import android.app.ActivityManager
import android.content.Context
import java.io.File

internal data class RecoveryBudget(val limitBytes: Long, val residentBytes: Long) {
    init { require(limitBytes > 0 && residentBytes >= 0 && residentBytes <= limitBytes) }
}

/** Admits decoding and transfers the same reservation to the installed document owner. */
internal class RecoveryAdmission private constructor(private val ledger: ResourceBudgetLedger,
    private val headroom: () -> Long, private val resident: () -> Long,
    private val beforeNativeForTest: ((RecoveryBudget) -> Unit)?) {
    constructor() : this(ImageIoAdmission.ledger, { ImageIoAdmission.ledger.limitBytes }, { 0 }, null)

    companion object {
        fun forContext(context: Context): RecoveryAdmission {
            val application = context.applicationContext
            return RecoveryAdmission(ImageIoAdmission.ledger, {
                val manager = application.getSystemService(ActivityManager::class.java)
                val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
                check(!memory.lowMemory) { MEMORY_MESSAGE }
                // The ledger limit already excludes this reserve from the fixed 512 MiB cap.
                // Separately exclude it from the sampled device headroom, not from that limit again.
                (memory.availMem - memory.threshold - ProjectAdmissionPolicy.EXTERNAL_RESERVE).coerceAtLeast(0)
            }, { 0 }, null)
        }

        internal fun forTest(ledger: ResourceBudgetLedger, headroom: () -> Long = { ledger.limitBytes },
            resident: () -> Long = { 0 }, beforeNative: ((RecoveryBudget) -> Unit)? = null) =
            RecoveryAdmission(ledger, headroom, resident, beforeNative)

        private const val MEMORY_MESSAGE = "Not enough memory to recover this document; close other apps and try again"
    }

    fun load(file: File): NativeEditor {
        val sampledHeadroom = headroom()
        val sampledResident = resident()
        require(sampledHeadroom >= 0 && sampledResident >= 0)
        val snapshot = ledger.snapshot()
        val claim = minOf(snapshot.availableBytes, (sampledHeadroom - snapshot.reservedBytes).coerceAtLeast(0))
        check(claim > sampledResident) { MEMORY_MESSAGE }
        // The decoded peak is unknown until native validation. Reserve the whole admitted segment.
        // A racing owner can cause a safe refusal under the ledger gate; no unbounded retry follows.
        val reservation = try { ledger.reserve(claim, "document recovery", sampledHeadroom) }
            catch (error: ResourceBudgetExceededException) { throw IllegalStateException(MEMORY_MESSAGE, error) }
        return try {
            val budget = RecoveryBudget(reservation.bytes, sampledResident)
            beforeNativeForTest?.invoke(budget)
            NativeEditor.loadWithBudget(file, budget, ledger, reservation)
        } catch (error: Throwable) { reservation.close(); throw error }
    }
}
