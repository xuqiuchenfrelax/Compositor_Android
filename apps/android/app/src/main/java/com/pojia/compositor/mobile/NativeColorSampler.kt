package com.pojia.compositor.mobile

import android.app.ActivityManager
import android.content.Context

/** One immutable scene and cached native graph per gesture; neither palette edits nor samples enter history. */
internal class NativeColorSampler private constructor(private val scene: NativeScene,
    private val reservation: ResourceBudgetLedger.Reservation, loader: () -> LongArray) : ColorSamplingSession {
    private var handle = 0L
    companion object {
        // Keep transport headroom in addition to the scene's independent lease, not an RSS ceiling.
        internal const val SCENE_ALLOWANCE = ImageIoAdmission.NATIVE_METADATA_ALLOWANCE
        fun capture(context: Context, editor: NativeEditor): NativeColorSampler {
            val manager = context.applicationContext.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val ceiling = if (memory.lowMemory) 0 else
                (memory.availMem - memory.threshold - ProjectAdmissionPolicy.EXTERNAL_RESERVE).coerceAtLeast(0)
            val ledger = editor.resourceLedger
            var scene: NativeScene? = null
            var reservation: ResourceBudgetLedger.Reservation? = null
            try {
                val captured = checkNotNull(editor.captureScene()) { "Document is closed" }
                scene = captured
                // Capture owns its lease before the sampler reserves the remaining headroom.
                val budget = ledger.snapshot()
                val claim = minOf(budget.availableBytes, (ceiling - budget.reservedBytes).coerceAtLeast(0))
                if (claim <= SCENE_ALLOWANCE) throw ResourceBudgetExceededException(SCENE_ALLOWANCE + 1, claim, "color sampling")
                val admitted = ledger.reserve(claim, "color sampling", ceiling)
                reservation = admitted
                return NativeColorSampler(captured, admitted) { captured.createColorSampler(claim - SCENE_ALLOWANCE) }
            } catch (error: Throwable) {
                try { scene?.close() } finally { reservation?.close() }
                throw error
            }
        }
    }
    init {
        val values = loader()
        val received = values.getOrNull(1) ?: 0L
        try {
            check(values.size == 6 && values[0] in 0..Int.MAX_VALUE.toLong()) { "Color sampler outcome is unavailable" }
            if (values[0] != 0L) throw IllegalStateException("Color sampling is unavailable (${values[0]})")
            check(received != 0L && (2..5).all { values[it] >= 0 } &&
                Math.addExact(values[2], values[3]) == values[4] && values[5] >= values[4] &&
                values[5] <= reservation.bytes - SCENE_ALLOWANCE) { "Color sampler memory outcome is inconsistent" }
            reservation.resize(Math.addExact(values[4], SCENE_ALLOWANCE))
            handle = received
        } catch (error: Throwable) {
            if (received != 0L) destroy(received)
            throw error
        }
    }
    @Synchronized override fun sample(x: Double, y: Double): Int? {
        check(handle != 0L) { "Color sampler is closed" }
        require(x.isFinite() && y.isFinite()) { "Color sample coordinates must be finite" }
        return sample(handle, x, y).takeIf { it != 0 }
    }
    @Synchronized override fun close() {
        if (handle == 0L) return
        val closing = handle; handle = 0
        try { destroy(closing) } finally { try { scene.close() } finally { reservation.close() } }
    }
    private external fun destroy(handle: Long)
    private external fun sample(handle: Long, x: Double, y: Double): Int
}
