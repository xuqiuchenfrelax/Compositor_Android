package com.pojia.compositor.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Private P55 boundary for the native Distort worker.  Handles are opaque
 * generation tokens. Only takeScene transfers an owned raw scene pointer to
 * NativeScene's existing JNI boundary. All records
 * are direct, exact-capacity, native-order buffers and JNI ignores position
 * and limit.  The caller must serialize document operations through the same
 * NativeEditor gate used by other document mutations.
 */
internal object NativeDistortSessionBridge {
    const val DRAFT_BYTES = 40
    const val TICKET_BYTES = 48
    const val QUAD_BYTES = 80
    const val SNAPSHOT_BYTES = 48
    const val STATS_BYTES = 48

    const val WAIT_READY = 0
    const val WAIT_SUPERSEDED = 1
    const val WAIT_CLOSED = 2
    const val WAIT_TIMEOUT = 3

    fun record(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    private fun checked(record: ByteBuffer, bytes: Int) {
        check(record.isDirect && record.capacity() == bytes && record.order() == ByteOrder.nativeOrder())
    }

    fun putQuad(record: ByteBuffer, corners: DoubleArray, flipX: Boolean = false,
        flipY: Boolean = false, previewLimit: Double = 0.0) {
        checked(record, QUAD_BYTES)
        require(corners.size == 8 && corners.all { it.isFinite() })
        repeat(8) { record.putDouble(it * 8, corners[it]) }
        record.putInt(64, if (flipX) 1 else 0)
        record.putInt(68, if (flipY) 1 else 0)
        require(previewLimit == 0.0 || previewLimit.isFinite() && previewLimit > 0.0)
        record.putDouble(72, previewLimit)
    }

    fun draft(record: ByteBuffer): LongArray {
        checked(record, DRAFT_BYTES)
        return LongArray(5) { record.getLong(it * 8) }
    }

    fun ticket(record: ByteBuffer): LongArray {
        checked(record, TICKET_BYTES)
        return LongArray(6) { record.getLong(it * 8) }
    }

    /** scene token, revision, scene generation, owned, shared, capture peak */
    fun snapshot(record: ByteBuffer): LongArray {
        checked(record, SNAPSHOT_BYTES)
        return LongArray(6) { record.getLong(it * 8) }
    }

    /** limit, reserved, peak, initial resident, control use count, cancelled */
    fun stats(record: ByteBuffer): LongArray {
        checked(record, STATS_BYTES)
        return LongArray(6) { record.getLong(it * 8) }
    }

    external fun begin(document: Long, layer: Long, limit: Long, resident: Long,
        draft: ByteBuffer): Long
    external fun submitQuad(session: Long, quad: ByteBuffer, ticket: ByteBuffer): Int
    external fun wait(session: Long, ticket: ByteBuffer, timeoutMillis: Long): Int
    external fun publish(session: Long, ticket: ByteBuffer): Int
    external fun snapshot(session: Long, output: ByteBuffer): Int
    external fun cancelDraft(session: Long): Int
    external fun sealJoin(session: Long): Int
    external fun stats(session: Long, output: ByteBuffer): Int
    external fun destroyScene(scene: Long): Int
    /** Removes the registry owner; the returned pointer must be owned by NativeScene exactly once. */
    external fun takeScene(scene: Long): Long
    external fun destroySession(session: Long): Int
}
