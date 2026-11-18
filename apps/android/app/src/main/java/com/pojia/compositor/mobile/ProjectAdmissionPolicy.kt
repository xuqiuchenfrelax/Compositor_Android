package com.pojia.compositor.mobile

import android.app.ActivityManager
import android.content.Context

internal data class ProjectAdmission(val limitBytes: Long, val residentBytes: Long)

/** Reservations complement the native payload report; they are not an RSS ceiling. */
internal object ProjectAdmissionPolicy {
    private const val MIB = 1024L * 1024
    private const val MANAGED_LIMIT = 512 * MIB
    private const val GPU_RESERVE = 64 * MIB
    private const val DISPLAY_RESERVE = 16 * MIB
    private const val TRANSPORT_RESERVE = 64 * MIB
    private const val PLATFORM_RESERVE = 128 * MIB
    internal const val EXTERNAL_RESERVE = GPU_RESERVE + DISPLAY_RESERVE + TRANSPORT_RESERVE + PLATFORM_RESERVE

    fun forContext(context: Context, retained: RetainedMemory): ProjectAdmission {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        check(!memory.lowMemory) { "Free device memory before opening a project" }
        return calculate(retained.totalBytes, memory.availMem, memory.threshold)
    }

    internal fun calculate(retainedBytes: Long, availableBytes: Long, thresholdBytes: Long): ProjectAdmission {
        require(retainedBytes >= 0 && availableBytes >= 0 && thresholdBytes >= 0)
        val resident = Math.addExact(retainedBytes, EXTERNAL_RESERVE)
        val usable = (availableBytes - thresholdBytes).coerceAtLeast(0)
        val freeForJob = (usable - EXTERNAL_RESERVE).coerceAtLeast(0)
        val remaining = minOf((MANAGED_LIMIT - resident).coerceAtLeast(0), freeForJob)
        check(remaining >= 16 * MIB) { "Not enough reserved memory to open this project; close other apps or use a smaller project" }
        return ProjectAdmission(resident + remaining, resident)
    }
}
