package com.pojia.compositor.mobile

import java.nio.ByteBuffer

/** Menu eligibility and its native authority are captured with the editor state.
 * A later selection or document edit must not redirect an already queued action. */
internal data class SelectedLayerActions(
    val instance: Long, val revision: Long, val generation: Long, val selectionEpoch: Long,
    val primary: Long, val targetCount: Int, val visibilityValue: Boolean,
    val canVisibility: Boolean, val releasesClipping: Boolean, val canClipping: Boolean,
) {
    internal fun request(action: Int): ByteBuffer = NativeTransformBridge.record(40).apply {
        putLong(0, instance); putLong(8, revision); putLong(16, generation); putLong(24, selectionEpoch)
        putInt(32, action); putInt(36, 0)
    }
}

internal object NativeSelectedLayerActionsBridge {
    const val INFO_BYTES = 112
    const val VISIBILITY = 0
    const val CLIPPING = 1
    fun requireSuccess(status: Int) { if (status != 0) throw NativeCommandException(status) }
    fun result(record: ByteBuffer): SelectedLayerActions {
        check(record.getInt(88) >= 0 && (92..104 step 4).all { record.getInt(it) in 0..1 } &&
            record.getInt(108) == 0) { "Invalid selected-layer action record" }
        return SelectedLayerActions(record.getLong(0), record.getLong(8), record.getLong(16), record.getLong(24),
            record.getLong(32), record.getInt(88), record.getInt(92) != 0, record.getInt(96) != 0,
            record.getInt(100) != 0, record.getInt(104) != 0)
    }
    external fun estimate(document: Long, output: ByteBuffer): Int
    external fun read(document: Long, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun apply(document: Long, request: ByteBuffer, limit: Long, resident: Long): Int
}
