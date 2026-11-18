package com.pojia.compositor.mobile

import android.app.ActivityManager
import android.content.Context

/** A private-stroke allowance complements retained native storage; it is not an RSS ceiling. */
internal object StrokeAdmissionPolicy {
    private const val MIB = 1024L * 1024
    internal const val DEFAULT_LIMIT_BYTES = 64 * MIB
    private const val MANAGED_LIMIT = 512 * MIB
    // GPU 64 MiB, display 16 MiB, platform 128 MiB and bounded JNI batch staging 4 MiB.
    // Retained external scenes/snapshots and allocator bookkeeping remain caller responsibilities.
    internal const val EXTERNAL_RESERVE = (64 + 16 + 128 + 4) * MIB

    fun forContext(context: Context, retained: RetainedMemory): Long {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        check(!memory.lowMemory) { "Free device memory before painting" }
        return calculate(retained.totalBytes, memory.availMem, memory.threshold)
    }

    internal fun calculate(retainedBytes: Long, availableBytes: Long, thresholdBytes: Long): Long {
        require(retainedBytes >= 0 && availableBytes >= 0 && thresholdBytes >= 0)
        val resident = Math.addExact(retainedBytes, EXTERNAL_RESERVE)
        val deviceRoom = ((availableBytes - thresholdBytes).coerceAtLeast(0) - EXTERNAL_RESERVE).coerceAtLeast(0)
        val allowance = minOf(DEFAULT_LIMIT_BYTES, (MANAGED_LIMIT - resident).coerceAtLeast(0), deviceRoom)
        check(allowance >= 2 * MIB) { "Not enough reserved memory to paint; close other apps or use a smaller project" }
        return allowance
    }
}
