package com.pojia.compositor.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DistortDraftIdentity(val document: ULong, val owner: ULong, val revision: ULong,
    val source: ULong, val layer: ULong)
internal data class DistortWorkIdentity(val document: ULong, val owner: ULong, val revision: ULong,
    val source: ULong, val generation: ULong, val worker: ULong)
internal data class DistortCommitPlan(val draft: DistortDraftIdentity, val ticket: DistortWorkIdentity,
    val sceneGeneration: ULong, val currentRetainedBytes: ULong, val metadataAllowanceBytes: ULong)
internal enum class DistortCommitPhase { PENDING, TERMINAL }
internal data class DistortCommitReceipt(val operation: ULong, val plan: DistortCommitPlan,
    val phase: DistortCommitPhase, val status: Int, val revision: ULong,
    val sceneGeneration: ULong, val retainedBytes: ULong, val changed: Boolean)

/** ABI16 transport only. A live editor owner must serialize document lifetime and
 * buffer access. No draft is started and no Android resource ownership is transferred.
 * Native IDs and counts retain all 64 bits; ledger admission needs a separate checked conversion. */
internal object NativeDistortBridge {
    const val DRAFT_BYTES = 40
    const val TICKET_BYTES = 48
    const val PLAN_BYTES = 112
    const val RECEIPT_BYTES = 160
    const val RETAINED_BYTES = 24

    fun record(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    private fun checked(record: ByteBuffer, bytes: Int) {
        require(record.isDirect && record.capacity() == bytes && record.order() == ByteOrder.nativeOrder())
    }
    private fun draft(record: ByteBuffer, offset: Int) = DistortDraftIdentity(
        record.getLong(offset).toULong(), record.getLong(offset + 8).toULong(),
        record.getLong(offset + 16).toULong(), record.getLong(offset + 24).toULong(),
        record.getLong(offset + 32).toULong())
    private fun ticket(record: ByteBuffer, offset: Int) = DistortWorkIdentity(
        record.getLong(offset).toULong(), record.getLong(offset + 8).toULong(),
        record.getLong(offset + 16).toULong(), record.getLong(offset + 24).toULong(),
        record.getLong(offset + 32).toULong(), record.getLong(offset + 40).toULong())
    private fun plan(record: ByteBuffer, offset: Int) = DistortCommitPlan(draft(record, offset),
        ticket(record, offset + 40), record.getLong(offset + 88).toULong(),
        record.getLong(offset + 96).toULong(), record.getLong(offset + 104).toULong())
    fun plan(record: ByteBuffer): DistortCommitPlan {
        checked(record, PLAN_BYTES)
        return plan(record, 0)
    }
    fun putDraft(record: ByteBuffer, value: DistortDraftIdentity) {
        checked(record, DRAFT_BYTES)
        record.putLong(0, value.document.toLong()); record.putLong(8, value.owner.toLong())
        record.putLong(16, value.revision.toLong()); record.putLong(24, value.source.toLong())
        record.putLong(32, value.layer.toLong())
    }
    fun putTicket(record: ByteBuffer, value: DistortWorkIdentity) {
        checked(record, TICKET_BYTES)
        record.putLong(0, value.document.toLong()); record.putLong(8, value.owner.toLong())
        record.putLong(16, value.revision.toLong()); record.putLong(24, value.source.toLong())
        record.putLong(32, value.generation.toLong()); record.putLong(40, value.worker.toLong())
    }
    /** A zero record means no receipt. Query OK means existence, including pending
     * and terminal failure; inspect phase before status. Decode may allocate after
     * publication: retain these wire bytes and query the same ID if delivery is lost. */
    fun receipt(record: ByteBuffer): DistortCommitReceipt? {
        checked(record, RECEIPT_BYTES)
        val phase = record.getInt(120)
        if (phase == 0) {
            repeat(RECEIPT_BYTES / 8) { check(record.getLong(it * 8) == 0L) }
            return null
        }
        check(phase in 1..2 && record.getInt(156) == 0)
        val status = record.getInt(124).also { check(it in 0..9) }
        val changed = record.getInt(152).also { check(it in 0..1) } != 0
        val operation = record.getLong(0).toULong().also { check(it != 0uL) }
        return DistortCommitReceipt(operation, plan(record, 8),
            if (phase == 1) DistortCommitPhase.PENDING else DistortCommitPhase.TERMINAL,
            status, record.getLong(128).toULong(), record.getLong(136).toULong(),
            record.getLong(144).toULong(), changed)
    }

    // Long scalar identities are opaque uint64 bit patterns, including negative values.
    // Buffers are direct and exact-capacity; output must be writable. JNI ignores position/limit.
    external fun prepare(document: Long, draft: ByteBuffer, ticket: ByteBuffer, output: ByteBuffer): Int
    external fun apply(document: Long, operation: Long, plan: ByteBuffer, output: ByteBuffer): Int
    external fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int
    external fun acknowledge(document: Long, instance: Long, operation: Long): Int
    external fun cancel(document: Long, draft: ByteBuffer): Int
    external fun retained(document: Long, output: ByteBuffer): Int
    external fun setRetainedLimit(document: Long, bytes: Long): Int
}
