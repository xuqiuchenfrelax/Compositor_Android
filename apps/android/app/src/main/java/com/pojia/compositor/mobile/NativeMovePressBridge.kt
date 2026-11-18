package com.pojia.compositor.mobile

import java.nio.ByteBuffer

internal data class NativeSelection(val instance: Long, val revision: Long, val generation: Long,
    val epoch: Long, val primary: Long, val contentIdentity: Long, val ownedBytes: Long,
    val retainedBytes: Long, val mask: Boolean, val roots: List<Long>)

/** DOWN eligibility is immutable even when a preceding Inspector commit changes the stamps. */
internal data class MovePressInput(val point: MovePoint, val autoSelect: Boolean = false,
    val command: Boolean = false, val shift: Boolean = false, val picksAllowedAtDown: Boolean = false)

/** Raw records are allocated by their managed owner before any native mutation. Instance IDs
 * are opaque 64-bit values; comparison never converts them through a signed numeric domain. */
internal object NativeMovePressBridge {
    fun selection(header: ByteBuffer, roots: ByteBuffer): NativeSelection {
        val count = header.getInt(64)
        check(count >= 0 && count.toLong() * 8 <= roots.capacity())
        check(header.getInt(68) in 0..1)
        return NativeSelection(header.getLong(0), header.getLong(8), header.getLong(16),
            header.getLong(24), header.getLong(32), header.getLong(40), header.getLong(48),
            header.getLong(56), header.getInt(68) != 0, List(count) { roots.getLong(it * 8) })
    }
    fun request(record: ByteBuffer, selection: ByteBuffer, operation: Long, token: Long, input: MovePressInput) {
        record.putLong(0, selection.getLong(0)); record.putLong(8, operation)
        record.putLong(16, selection.getLong(8)); record.putLong(24, selection.getLong(16))
        record.putLong(32, selection.getLong(24)); record.putLong(40, token)
        record.putDouble(48, input.point.x); record.putDouble(56, input.point.y)
        record.putInt(64, if (input.autoSelect) 1 else 0); record.putInt(68, if (input.command) 1 else 0)
        record.putInt(72, if (input.shift) 1 else 0); record.putInt(76, 1)
        record.putInt(80, if (input.picksAllowedAtDown) 1 else 0); record.putInt(84, 0)
    }
    external fun read(document: Long, header: ByteBuffer, roots: ByteBuffer): Int
    external fun set(document: Long, request: ByteBuffer, roots: ByteBuffer, limit: Long,
        resident: Long, output: ByteBuffer): Int
    external fun press(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int
    external fun acknowledge(document: Long, instance: Long, operation: Long): Int
    external fun members(document: Long, token: Long, output: ByteBuffer): Int
}

/** Delivery boundary is injectable so failures can be verified after a real native publication.
 * The default path is direct JNI; recovery always uses the same document and operation ID. */
internal interface MovePressTransport {
    fun press(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int =
        NativeMovePressBridge.press(document, request, limit, resident, output)
    fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int =
        NativeMovePressBridge.query(document, instance, operation, output)
    fun acknowledge(document: Long, instance: Long, operation: Long): Int =
        NativeMovePressBridge.acknowledge(document, instance, operation)
}
internal object DirectMovePressTransport : MovePressTransport
