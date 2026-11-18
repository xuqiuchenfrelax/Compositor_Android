package com.pojia.compositor.mobile

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class TransformTarget(val nativeValue: Int) { LAYER(0), UNLINKED_MASK(1), GROUP(2) }
internal data class TransformDraftInfo(val token: Long, val baseRevision: Long, val previewGeneration: Long,
    val primaryLayerId: Long, val box: Transform64, val target: TransformTarget, val sampling: Int,
    val rootCount: Int, val memberCount: Int, val changed: Boolean)
internal data class TransformDraftMemory(val ownedBytes: Long, val beginPeakBytes: Long,
    val commitPeakBytes: Long, val previewOwnedBytes: Long, val previewSharedBytes: Long,
    val previewCapturePeakBytes: Long) {
    val editPeakBytes: Long get() = maxOf(beginPeakBytes, commitPeakBytes)
}
internal data class TransformDraftMember(val layerId: Long, val original: Transform64,
    val originalSampling: Int, val hasMask: Boolean, val originalMask: Transform64,
    val originalMaskSampling: Int, val hasMaskPlacement: Boolean, val maskLinked: Boolean,
    val maskEnabled: Boolean)
internal data class TransformCommitResult(val revision: Long, val sceneGeneration: Long,
    val primaryLayerId: Long, val changed: Boolean)
internal class TransformDraftException(val status: Int) : IllegalStateException("Transform draft command failed ($status)")

/** ABI13 fixed records preserve ABI12 layouts and use native byte order; buffers exist before native publication.
 * JNI does no Java allocation or array transport after begin/update/commit. */
internal object NativeTransformBridge {
    fun record(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    fun requireSuccess(status: Int) { if (status != 0) throw TransformDraftException(status) }
    private fun ByteBuffer.unsigned(offset: Int): Long = getLong(offset).also {
        check(it >= 0) { "Native transform counter exceeds the signed managed domain" }
    }
    private fun ByteBuffer.flag(offset: Int): Boolean = getInt(offset).also {
        check(it in 0..1) { "Invalid native transform flag" }
    } != 0
    private fun ByteBuffer.box(offset: Int) = Transform64(getDouble(offset), getDouble(offset + 8),
        getDouble(offset + 16), getDouble(offset + 24), getDouble(offset + 32), flag(offset + 40), flag(offset + 44))
    fun putBox(buffer: ByteBuffer, value: Transform64) {
        buffer.putDouble(0, value.x); buffer.putDouble(8, value.y)
        buffer.putDouble(16, value.width); buffer.putDouble(24, value.height)
        buffer.putDouble(32, value.rotation); buffer.putInt(40, if (value.flipX) 1 else 0)
        buffer.putInt(44, if (value.flipY) 1 else 0)
    }
    fun info(buffer: ByteBuffer): TransformDraftInfo {
        check(buffer.getInt(100) == 0)
        val target = TransformTarget.entries.first { it.nativeValue == buffer.getInt(80) }
        val sampling = buffer.getInt(84); val roots = buffer.getInt(88); val members = buffer.getInt(92)
        check(sampling in 0..2 && roots > 0 && members > 0)
        return TransformDraftInfo(buffer.unsigned(0).also { check(it > 0) }, buffer.unsigned(8),
            buffer.unsigned(16), buffer.unsigned(24).also { check(it > 0) }, buffer.box(32),
            target, sampling, roots, members, buffer.flag(96))
    }
    fun memory(buffer: ByteBuffer) = TransformDraftMemory(buffer.unsigned(0), buffer.unsigned(8),
        buffer.unsigned(16), buffer.unsigned(24), buffer.unsigned(32), buffer.unsigned(40))
    fun member(buffer: ByteBuffer) = TransformDraftMember(buffer.unsigned(0), buffer.box(8),
        buffer.getInt(56), buffer.flag(60), buffer.box(64), buffer.getInt(112), buffer.flag(116),
        buffer.flag(120), buffer.flag(124))
    fun commit(buffer: ByteBuffer): TransformCommitResult {
        check(buffer.getInt(28) == 0)
        return TransformCommitResult(buffer.unsigned(0), buffer.unsigned(8), buffer.unsigned(16), buffer.flag(24))
    }
    external fun estimate(document: Long, root: Long, target: Int, output: ByteBuffer): Int
    external fun begin(document: Long, root: Long, target: Int, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun info(document: Long, token: Long, output: ByteBuffer): Int
    external fun memory(document: Long, token: Long, output: ByteBuffer): Int
    external fun member(document: Long, token: Long, output: ByteBuffer): Int
    external fun update(document: Long, token: Long, box: ByteBuffer, sampling: Int, output: ByteBuffer): Int
    external fun snapshot(document: Long, token: Long, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun commit(document: Long, token: Long, output: ByteBuffer): Int
    external fun retained(document: Long, output: ByteBuffer): Int
    external fun setRetainedLimit(document: Long, limit: Long): Int
    external fun setDisplayLimit(document: Long, limit: Long): Int
    external fun displayAdmission(document: Long, output: ByteBuffer): Int
    external fun prepareCommit(document: Long, token: Long, output: ByteBuffer): Int
    external fun commitBudgeted(document: Long, plan: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun estimateScene(document: Long, output: ByteBuffer): Int
    external fun estimateAdjustmentPreview(document: Long, layer: Long, expectedRevision: Long,
        settingsLength: Long, output: ByteBuffer): Int
    external fun snapshotBudgeted(document: Long, limit: Long, resident: Long, output: ByteBuffer): Int
    external fun cancel(document: Long, token: Long): Int
    external fun destroyScene(scene: Long)
}

/** Serialized native draft with independent scene claims and prevalidated committed-resource
 * transfer. Managed claims conservatively count shared payload occurrences; they are not RSS. */
internal class NativeTransformDraft internal constructor(private val editor: NativeEditor,
    private val context: Context, internal val reservation: ResourceBudgetLedger.Reservation,
    internal val nativeResidentBytes: Long, internal val nativeLimitBytes: Long) : AutoCloseable {
    internal val infoRecord = NativeTransformBridge.record(104)
    private val memoryRecord = NativeTransformBridge.record(48)
    private val memberRecord = NativeTransformBridge.record(128)
    private val boxRecord = NativeTransformBridge.record(48)
    private val sceneRecord = NativeTransformBridge.record(8)
    private val commitRecord = NativeTransformBridge.record(40)
    private val planRecord = NativeTransformBridge.record(48)
    internal var token = 0L
    internal var active = false
    internal var duplicatePromoted = false
    fun layerPresentation(base: EditorState, selection: NativeSelection): DraftLayerPresentation =
        editor.withTransformDraft(this) { handle ->
            val info = info()
            val count = NativeTransformBridge.record(4)
            val required = NativeTransformBridge.record(8)
            val empty = NativeTransformBridge.record(0)
            NativeTransformBridge.requireSuccess(NativeDuplicateBridge.layerIds(handle, token,
                info.previewGeneration, empty, count))
            val size = count.getInt(0).also { check(it in 0..10000) }
            var nameBytesTotal = 0L
            repeat(size) { index ->
                NativeTransformBridge.requireSuccess(NativeDuplicateBridge.layerName(handle, token,
                    info.previewGeneration, index, empty, required))
                val length = required.getLong(0).also { check(it in 1..16385) }
                nameBytesTotal = Math.addExact(nameBytesTotal, length - 1)
            }
            NativeTransformBridge.requireSuccess(NativeDuplicateBridge.roots(handle, token,
                empty, count))
            val rootCount = count.getInt(0).also { check(it in 0..size) }
            NativeTransformBridge.requireSuccess(NativeDuplicateBridge.mapping(handle, token,
                info.previewGeneration, empty, count))
            val mappingCount = count.getInt(0).also { check(it in 0..size) }
            val bytes = Math.addExact(ImageIoAdmission.NATIVE_METADATA_ALLOWANCE,
                Math.addExact(Math.addExact(Math.multiplyExact(size.toLong(), 1024L),
                    Math.multiplyExact(nameBytesTotal, 2L)),
                    Math.addExact(32769L, Math.addExact(Math.multiplyExact(rootCount.toLong(), 128L),
                        Math.multiplyExact(mappingCount.toLong(), 128L)))))
            val lease = ImageIoAdmission.reserve(context, editor.resourceLedger, bytes, 0,
                "duplicate layer presentation")
            try {
                val ids = NativeTransformBridge.record(Math.multiplyExact(size, 8))
                NativeTransformBridge.requireSuccess(NativeDuplicateBridge.layerIds(handle, token,
                    info.previewGeneration, ids, count))
                val precision = NativeTransformBridge.record(88)
                val structure = NativeTransformBridge.record(96)
                val metadata = NativeTransformBridge.record(44)
                val name = NativeTransformBridge.record(16385)
                val utf8 = ByteArray(16384)
                val uuidBytes = ByteArray(36)
                val layers = List(size) { index ->
                    NativeTransformBridge.requireSuccess(NativeDuplicateBridge.layer(handle, token,
                        info.previewGeneration, index, precision, structure, metadata))
                    check(precision.getLong(0) == ids.getLong(index * 8))
                    NativeTransformBridge.requireSuccess(NativeDuplicateBridge.layerName(handle, token,
                        info.previewGeneration, index, name, required))
                    val length = required.getLong(0).also { check(it in 1..16385) }.toInt()
                    name.position(0); name.get(utf8, 0, length - 1)
                    repeat(36) { uuidBytes[it] = metadata.get(it) }
                    val transform = Transform64(precision.getDouble(16), precision.getDouble(24),
                        precision.getDouble(32), precision.getDouble(40), precision.getDouble(48),
                        precision.getInt(56) != 0, precision.getInt(60) != 0)
                    val maskTransform = Transform64(structure.getDouble(40), structure.getDouble(48),
                        structure.getDouble(56), structure.getDouble(64), structure.getDouble(72),
                        structure.getInt(80) != 0, structure.getInt(84) != 0)
                    LayerState(precision.getLong(0), precision.getDouble(64), precision.getInt(76) != 0,
                        precision.getInt(72), String(utf8, 0, length - 1, Charsets.UTF_8), structure.getLong(0),
                        structure.getLong(8), structure.getInt(16) != 0, structure.getInt(20) != 0,
                        structure.getInt(24) != 0, structure.getInt(28) != 0,
                        structure.getInt(32), structure.getInt(36), precision.getInt(8), precision.getInt(12),
                        transform, precision.getInt(80), String(uuidBytes, Charsets.UTF_8),
                        metadata.getInt(40) != 0, if (structure.getInt(88) != 0) maskTransform else null,
                        structure.getInt(92))
                }
                val roots = NativeTransformBridge.record(Math.multiplyExact(rootCount, 8))
                NativeTransformBridge.requireSuccess(NativeDuplicateBridge.roots(handle, token, roots, count))
                val selected = List(rootCount) { roots.getLong(it * 8) }
                val mappingRecords = NativeTransformBridge.record(Math.multiplyExact(mappingCount, 24))
                NativeTransformBridge.requireSuccess(NativeDuplicateBridge.mapping(handle, token,
                    info.previewGeneration, mappingRecords, count))
                val mapping = List(mappingCount) { index ->
                    val offset = index * 24
                    DuplicateLayerMapping(mappingRecords.getLong(offset), mappingRecords.getLong(offset + 8),
                        mappingRecords.getInt(offset + 16) != 0)
                }
                val presented = selection.copy(generation = info.previewGeneration, primary = info.primaryLayerId,
                    mask = false, roots = selected)
                DraftLayerPresentation(base.copy(layers = layers,
                    document = base.document.copy(activeLayerId = info.primaryLayerId)), presented,
                    info.previewGeneration, mapping, lease)
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
        }
    /** Set before decoding the commit record or notifying observers. Never retry after true. */
    @Volatile internal var commitConfirmed = false
        private set
    @Volatile internal var cancelConfirmed = false
        private set
    internal var cancelledResult: TransformCommitResult? = null
        private set
    private class PendingCancel(val plan: ByteBuffer, val result: ByteBuffer,
        val transfer: ResourceBudgetLedger.TransferPlan, val instance: Long,
        val originalBytes: Long, val transport: DuplicateCancelTransport) {
        var called = false
        var returnedStatus: Int? = null
    }
    private var pendingCancel: PendingCancel? = null
    internal var committedResult: TransformCommitResult? = null
        private set
    val isActive: Boolean get() = editor.serialized { active }
    fun info(): TransformDraftInfo = editor.withTransformDraft(this) { handle ->
        NativeTransformBridge.requireSuccess(NativeTransformBridge.info(handle, token, infoRecord))
        NativeTransformBridge.info(infoRecord)
    }
    fun memory(): TransformDraftMemory = editor.withTransformDraft(this) { handle ->
        NativeTransformBridge.requireSuccess(NativeTransformBridge.memory(handle, token, memoryRecord))
        NativeTransformBridge.memory(memoryRecord)
    }
    fun members(): List<TransformDraftMember> = editor.withTransformDraft(this) { handle ->
        NativeTransformBridge.requireSuccess(NativeTransformBridge.info(handle, token, infoRecord))
        val count = infoRecord.getInt(92)
        check(count > 0)
        val records = NativeTransformBridge.record(Math.multiplyExact(count, 128))
        NativeTransformBridge.requireSuccess(NativeMovePressBridge.members(handle, token, records))
        List(count) { index ->
            val value = records.duplicate().order(java.nio.ByteOrder.nativeOrder())
            value.position(index * 128)
            NativeTransformBridge.member(value.slice().order(java.nio.ByteOrder.nativeOrder()))
        }
    }
    fun member(): TransformDraftMember = editor.withTransformDraft(this) { handle ->
        NativeTransformBridge.requireSuccess(NativeTransformBridge.member(handle, token, memberRecord))
        NativeTransformBridge.member(memberRecord)
    }
    fun update(box: Transform64, sampling: Int = 0): TransformDraftInfo = editor.withTransformDraft(this) { handle ->
        NativeTransformBridge.putBox(boxRecord, box)
        NativeTransformBridge.requireSuccess(NativeTransformBridge.update(handle, token, boxRecord, sampling, infoRecord))
        NativeTransformBridge.info(infoRecord)
    }
    fun settleDuplicatePredecessor(): TransformDraftInfo = editor.withTransformDraft(this) { handle ->
        NativeTransformBridge.requireSuccess(NativeDuplicateBridge.settlePredecessor(handle, token, infoRecord))
        NativeTransformBridge.info(infoRecord)
    }
    fun captureScene(): NativeScene = editor.withTransformDraft(this) { handle ->
        NativeTransformBridge.requireSuccess(NativeTransformBridge.memory(handle, token, memoryRecord))
        val allowance = NativeTransformBridge.memory(memoryRecord).previewCapturePeakBytes
        val lease = ImageIoAdmission.reserve(context, editor.resourceLedger,
            Math.addExact(allowance, ImageIoAdmission.NATIVE_METADATA_ALLOWANCE), 0, "transform preview scene")
        var captured = 0L
        editor.retainTransformScene()
        try {
            NativeTransformBridge.requireSuccess(NativeTransformBridge.snapshot(handle, token, lease.bytes, 0, sceneRecord))
            captured = sceneRecord.getLong(0)
            check(captured != 0L)
            // A scene remains independent of the token and document, and destroys before releasing its claim.
            NativeScene(captured, editor.resourceLedger) { editor.releaseTransformScene(); lease.close() }
        } catch (error: Throwable) {
            if (captured != 0L) NativeTransformBridge.destroyScene(captured)
            editor.releaseTransformScene(); lease.close(); throw error
        }
    }
    fun commit(): TransformCommitResult {
        val result = editor.withTransformDraft(this) { handle ->
            NativeTransformBridge.requireSuccess(NativeTransformBridge.prepareCommit(handle, token, planRecord))
            val upper = planRecord.getLong(32).also { check(it >= 0) }
            val additional = planRecord.getLong(40).also { check(it >= 0) }
            // Existing committed and transient owners are already admitted; reserve only
            // the plan's additional candidate/history peak before pinning their replacement.
            val resident = Math.addExact(editor.installedDocumentBytes(), reservation.bytes)
            val original = reservation.bytes
            ImageIoAdmission.resize(context, reservation, Math.addExact(original, additional), 0)
            val transfer = try { editor.prepareTransformTransfer(reservation, upper) }
            catch (error: Throwable) { reservation.resize(original); throw error }
            try {
                editor.setInstalledLimit(handle, upper)
                NativeTransformBridge.requireSuccess(NativeTransformBridge.commitBudgeted(handle, planRecord,
                    Math.addExact(resident, additional), resident, commitRecord))
            } catch (error: Throwable) {
                editor.abortPreparedTransfer(transfer)
                reservation.resize(original)
                editor.setInstalledLimit(handle, editor.installedDocumentBytes())
                throw error
            }
            // Publication is irrevocable. Retire and transfer before DTO/observer allocation.
            commitConfirmed = true
            editor.retireTransformDraft(this, retainClaim = true)
            val accounted = editor.confirmPreparedTransfer(transfer, commitRecord.getLong(32))
            check(accounted) { "Confirmed transform receipt requires resource reconciliation" }
            editor.setInstalledLimit(handle, editor.installedDocumentBytes())
            NativeTransformBridge.commit(commitRecord).also { committedResult = it }
        }
        if (result.changed) editor.committed?.invoke(result.revision)
        return result
    }
    internal fun cancelNative(handle: Long, transport: DuplicateCancelTransport = DirectDuplicateCancelTransport): TransformCommitResult? {
        if (!active) return cancelledResult
        check(!editor.hasUnresolvedDuplicate) { "Duplicate promotion delivery remains unresolved" }
        pendingCancel?.let { return recoverCancelNative(handle) }
        if (!duplicatePromoted) {
            NativeTransformBridge.requireSuccess(NativeTransformBridge.cancel(handle, token))
            editor.retireTransformDraft(this, retainClaim = false)
            return null
        }
        val plan = NativeTransformBridge.record(48)
        val result = NativeTransformBridge.record(40)
        NativeTransformBridge.requireSuccess(NativeDuplicateBridge.prepareCancel(handle, token, plan))
        val upper = plan.getLong(32).also { check(it >= 0) }
        val additional = plan.getLong(40).also { check(it >= 0) }
        val original = reservation.bytes
        val instance = editor.selection().instance
        val resident = Math.addExact(editor.installedDocumentBytes(), original)
        ImageIoAdmission.resize(context, reservation, Math.addExact(original, additional), 0)
        val transfer = try { editor.prepareTransformTransfer(reservation, upper) }
            catch (error: Throwable) { reservation.resize(original); throw error }
        val pending = PendingCancel(plan, result, transfer, instance, original, transport)
        pendingCancel = pending
        editor.markPendingDuplicateCancel(this)
        try {
            editor.setInstalledLimit(handle, upper)
            pending.called = true
            pending.returnedStatus = transport.cancel(handle, plan,
                Math.addExact(resident, additional), resident, result)
            return recoverCancelNative(handle)
        } catch (error: Throwable) {
            if (pendingCancel == null) throw error
            try {
                return recoverCancelNative(handle)
            } catch (unresolved: Throwable) {
                if (unresolved !== error) error.addSuppressed(unresolved)
                throw error
            }
        }
    }
    internal fun recoverCancelNative(handle: Long): TransformCommitResult? {
        val pending = pendingCancel ?: return cancelledResult
        if (cancelConfirmed) return reconcileCancelNative(handle, pending)
        if (pending.returnedStatus == 0) {
            cancelConfirmed = true
            editor.retireTransformDraft(this, retainClaim = true)
            return reconcileCancelNative(handle, pending)
        }
        val status = try { pending.transport.query(handle, pending.instance, token, pending.result) }
            catch (error: Throwable) { editor.freezePendingDuplicateCancel(this); throw error }
        if (status == 2) {
            editor.abortPreparedTransfer(pending.transfer)
            pendingCancel = null
            reservation.resize(pending.originalBytes)
            editor.setInstalledLimit(handle, editor.installedDocumentBytes())
            editor.finishPendingDuplicateCancel(this)
            throw TransformDraftException(pending.returnedStatus ?: status)
        }
        if (status != 0) { editor.freezePendingDuplicateCancel(this); NativeTransformBridge.requireSuccess(status) }
        cancelConfirmed = true
        editor.retireTransformDraft(this, retainClaim = true)
        return reconcileCancelNative(handle, pending)
    }
    private fun reconcileCancelNative(handle: Long, pending: PendingCancel): TransformCommitResult {
        if (!editor.confirmPreparedTransfer(pending.transfer, pending.result.getLong(32))) {
            editor.freezePendingDuplicateCancel(this)
            error("Confirmed duplicate cancel requires resource reconciliation")
        }
        pendingCancel = null
        editor.finishPendingDuplicateCancel(this)
        val result = NativeTransformBridge.commit(pending.result).also { cancelledResult = it }
        editor.setInstalledLimit(handle, editor.installedDocumentBytes())
        if (result.changed) editor.committed?.invoke(result.revision)
        return result
    }
    fun cancelWithResult(transport: DuplicateCancelTransport = DirectDuplicateCancelTransport): TransformCommitResult? =
        editor.cancelTransformDraft(this, transport)
    override fun close() { editor.cancelTransformDraft(this) }
}
