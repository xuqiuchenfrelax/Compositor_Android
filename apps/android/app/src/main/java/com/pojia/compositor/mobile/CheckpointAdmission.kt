package com.pojia.compositor.mobile

import android.app.ActivityManager
import android.content.Context

/** Snapshot claims share the image/recovery ledger; mutable documents and GPU resources are not globally accounted. */
internal class CheckpointAdmission private constructor(private val ledger: ResourceBudgetLedger,
    private val headroom: () -> Long, private val beforeCaptureForTest: ((Long) -> Unit)?,
    private val beforeWriteForTest: ((Long) -> Unit)?) {
    constructor() : this(ImageIoAdmission.ledger, { ImageIoAdmission.ledger.limitBytes }, null, null)

    companion object {
        fun forContext(context: Context): CheckpointAdmission {
            val application = context.applicationContext
            return CheckpointAdmission(ImageIoAdmission.ledger, {
                val manager = application.getSystemService(ActivityManager::class.java)
                val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
                if (memory.lowMemory) throw ResourceBudgetExceededException(1, 0, "checkpoint snapshot")
                // The fixed ledger cap already excludes this reserve; the sampled device ceiling excludes it separately.
                (memory.availMem - memory.threshold - ProjectAdmissionPolicy.EXTERNAL_RESERVE).coerceAtLeast(0)
            }, null, null)
        }

        internal fun forTest(ledger: ResourceBudgetLedger, headroom: () -> Long = { ledger.limitBytes },
            beforeCapture: ((Long) -> Unit)? = null, beforeWrite: ((Long) -> Unit)? = null) =
            CheckpointAdmission(ledger, headroom, beforeCapture, beforeWrite)
    }

    internal fun sharesLedger(other: ResourceBudgetLedger): Boolean = ledger === other

    fun reserveCapture(): ResourceBudgetLedger.Reservation {
        val ceiling = headroom()
        require(ceiling >= 0)
        val current = ledger.snapshot()
        val claim = minOf(current.availableBytes, (ceiling - current.reservedBytes).coerceAtLeast(0))
        if (claim <= 0) throw ResourceBudgetExceededException(1, 0, "checkpoint snapshot")
        // Native admits the unknown capture peak before holder/State allocation. A racing owner may refuse safely.
        return ledger.reserve(claim, "checkpoint snapshot", ceiling)
    }

    fun beforeCapture(reservation: ResourceBudgetLedger.Reservation) = beforeCaptureForTest?.invoke(reservation.bytes)

    fun <T> withWriter(memory: CheckpointSnapshotMemory, action: (Long, Long) -> T): T {
        // Acquire before caller path encoding or JNI carrier allocation, not just before the native serializer.
        return ledger.reserve(memory.writeScratchBytes, "checkpoint writer", headroom()).use { scratch ->
            val limit = Math.addExact(memory.heldBytes, scratch.bytes)
            beforeWriteForTest?.invoke(scratch.bytes)
            action(limit, memory.heldBytes)
        }
    }
}
