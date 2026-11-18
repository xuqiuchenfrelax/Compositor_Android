package com.pojia.compositor.mobile

import java.nio.ByteBuffer

internal enum class DuplicateOutcome { NO_COPIES, PROMOTED }
internal data class DuplicateReceipt(val instance: Long, val operation: Long, val status: Int,
    val outcome: DuplicateOutcome, val acceptedRoots: Int, val skippedRoots: Int,
    val copiedLayers: Int, val draft: TransformDraftInfo, val memory: TransformDraftMemory)
internal data class DuplicateLayerMapping(val sourceId: Long, val copyId: Long, val root: Boolean)
internal data class DraftLayerPresentation(val state: EditorState, val selection: NativeSelection,
    val generation: Long, val mapping: List<DuplicateLayerMapping>,
    private val lease: ResourceBudgetLedger.Reservation) : AutoCloseable {
    override fun close() = lease.close()
}

/** ABI15 records are independent of the atomic DOWN receipt. */
internal object NativeDuplicateBridge {
    fun request(record: ByteBuffer, selection: NativeSelection, operation: Long, token: Long) {
        record.putLong(0, selection.instance); record.putLong(8, operation)
        record.putLong(16, selection.revision); record.putLong(24, selection.generation)
        record.putLong(32, selection.epoch); record.putLong(40, token); record.putLong(48, 0)
    }
    fun receipt(record: ByteBuffer): DuplicateReceipt {
        val status = record.getInt(40)
        NativeTransformBridge.requireSuccess(status)
        val outcome = DuplicateOutcome.entries[record.getInt(44).also { check(it in 0..1) }]
        val accepted = record.getInt(48).also { check(it >= 0) }
        val skipped = record.getInt(52).also { check(it >= 0) }
        val layers = record.getInt(56).also { check(it >= 0) }
        check(record.getInt(60) == 0)
        val info = record.duplicate().order(java.nio.ByteOrder.nativeOrder()).apply { position(64) }
            .slice().order(java.nio.ByteOrder.nativeOrder())
        val memory = record.duplicate().order(java.nio.ByteOrder.nativeOrder()).apply { position(168) }
            .slice().order(java.nio.ByteOrder.nativeOrder())
        return DuplicateReceipt(record.getLong(0), record.getLong(8), status, outcome,
            accepted, skipped, layers, NativeTransformBridge.info(info), NativeTransformBridge.memory(memory))
    }
    external fun estimate(document: Long, request: ByteBuffer, output: ByteBuffer): Int
    external fun promote(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int
    external fun acknowledge(document: Long, instance: Long, operation: Long): Int
    external fun layerIds(document: Long, token: Long, generation: Long, output: ByteBuffer, count: ByteBuffer): Int
    external fun layer(document: Long, token: Long, generation: Long, index: Int,
        precision: ByteBuffer, structure: ByteBuffer, metadata: ByteBuffer): Int
    external fun layerName(document: Long, token: Long, generation: Long, index: Int,
        output: ByteBuffer, required: ByteBuffer): Int
    external fun roots(document: Long, token: Long, output: ByteBuffer, count: ByteBuffer): Int
    external fun mapping(document: Long, token: Long, generation: Long, output: ByteBuffer, count: ByteBuffer): Int
    external fun prepareCancel(document: Long, token: Long, output: ByteBuffer): Int
    external fun cancelBudgeted(document: Long, plan: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun cancelReceipt(document: Long, instance: Long, token: Long, output: ByteBuffer): Int
    external fun settlePredecessor(document: Long, token: Long, output: ByteBuffer): Int
}

internal interface DuplicateTransport {
    fun promote(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer) =
        NativeDuplicateBridge.promote(document, request, limit, resident, output)
    fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer) =
        NativeDuplicateBridge.query(document, instance, operation, output)
    fun acknowledge(document: Long, instance: Long, operation: Long) =
        NativeDuplicateBridge.acknowledge(document, instance, operation)
}
internal object DirectDuplicateTransport : DuplicateTransport

internal interface DuplicateCancelTransport {
    fun cancel(document: Long, plan: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer) =
        NativeDuplicateBridge.cancelBudgeted(document, plan, limit, resident, output)
    fun query(document: Long, instance: Long, token: Long, output: ByteBuffer) =
        NativeDuplicateBridge.cancelReceipt(document, instance, token, output)
}
internal object DirectDuplicateCancelTransport : DuplicateCancelTransport
