package com.pojia.compositor.mobile

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Exact native checkpoint serialization metadata; this is not a disk or resident-memory reservation. */
internal data class CheckpointSnapshotInfo(val capturedRevision: Long, val serializedBytes: Long)

/** Conservative native allowances by retained surface occurrence; these are not allocator or process RSS limits. */
internal data class CheckpointSnapshotMemory(val sharedRetainedBytes: Long, val snapshotOwnedBytes: Long,
    val heldBytes: Long, val capturePeakBytes: Long, val writeScratchBytes: Long) {
    init {
        require(sharedRetainedBytes >= 0 && snapshotOwnedBytes > 0 && heldBytes > 0 &&
            capturePeakBytes >= heldBytes && writeScratchBytes > 0)
        require(Math.addExact(sharedRetainedBytes, snapshotOwnedBytes) == heldBytes)
    }
}

/** The immutable native state outlives its editor; close waits for writers and releases its retained owner once. */
internal class NativeCheckpointSnapshot internal constructor(loader: () -> LongArray,
    private val admission: CheckpointAdmission, private val reservation: ResourceBudgetLedger.Reservation,
    private var released: (() -> Unit)? = null) : AutoCloseable {
    private val lifetime = ReentrantReadWriteLock()
    private var handle = 0L
    val info: CheckpointSnapshotInfo
    val memory: CheckpointSnapshotMemory
    val capturedRevision: Long get() = info.capturedRevision
    val serializedBytes: Long get() = info.serializedBytes
    /** Captured content tag in 0..Long.MAX_VALUE; JNI rejects larger unsigned tags.
     * This remains in the originating document's lifetime domain after editor close. It is transient:
     * never persist it or compare it with a reloaded/imported document's new domain. */
    val capturedContentIdentity: Long get() = lifetime.read {
        check(handle != 0L) { "Checkpoint snapshot is closed" }
        contentIdentity(handle)
    }

    init {
        val fields = loader()
        val owned = fields.getOrNull(1) ?: 0L
        try {
            check(fields.size == 9 && fields[0] in 0..Int.MAX_VALUE.toLong()) { "Checkpoint snapshot outcome is unavailable" }
            if (fields[0] != 0L) throw CheckpointException(fields[0].toInt())
            check(owned != 0L && fields[2] >= 0L && fields[3] >= 32L) { "Checkpoint snapshot metadata is unavailable" }
            info = CheckpointSnapshotInfo(fields[2], fields[3])
            memory = CheckpointSnapshotMemory(fields[4], fields[5], fields[6], fields[7], fields[8])
            check(memory.capturePeakBytes <= reservation.bytes) { "Checkpoint snapshot exceeded its admitted capture allowance" }
            // Shrinking does not resample headroom: the claim was admitted before native capture.
            reservation.resize(memory.heldBytes)
            handle = owned
        } catch (error: Throwable) {
            if (owned != 0L) destroy(owned)
            throw error
        }
    }
    fun write(file: File): CheckpointWriteResult = lifetime.read {
        check(handle != 0L) { "Checkpoint snapshot is closed" }
        val result = admission.withWriter(memory) { limit, resident ->
            CheckpointWriteResult.fromNative(writeCheckpoint(handle, file.absolutePath.toByteArray(Charsets.UTF_8), limit, resident))
        }
        check(!result.revisionCaptured || result.capturedRevision == capturedRevision) { "Checkpoint snapshot write changed its captured revision" }
        result
    }
    override fun close() = lifetime.write {
        if (handle != 0L) {
            val closing = handle; handle = 0L
            try { destroy(closing) }
            finally {
                val callback = released; released = null
                try { callback?.invoke() }
                finally { reservation.close() }
            }
        }
    }
    private external fun destroy(handle: Long)
    private external fun contentIdentity(handle: Long): Long
    private external fun writeCheckpoint(handle: Long, path: ByteArray, limitBytes: Long, residentBytes: Long): LongArray
}
