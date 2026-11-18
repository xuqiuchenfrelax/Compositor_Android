package com.pojia.compositor.mobile

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.concurrent.withLock

/** Allocated before publication; native fills these four fields even on a committed IO error. */
internal class ProjectWriteResult internal constructor(internal val fields: LongArray = LongArray(4)) {
    val status: Int get() = fields[0].toInt()
    val capturedRevision: Long get() = fields[1]
    val committed: Boolean get() = fields[2] != 0L
    val durabilityConfirmed: Boolean get() = fields[3] != 0L
    val successful: Boolean get() = status == 0 && committed && durabilityConfirmed
}

/** Cancel may overlap the single writer. Destruction waits for all native users. */
internal class NativeProjectWriteControl(limitBytes: Long, residentBytes: Long) : AutoCloseable {
    companion object { init { System.loadLibrary("compositor_android") } }
    private val lifetime = ReentrantReadWriteLock()
    private val writer = ReentrantLock()
    private var handle: Long
    init {
        require(limitBytes > 0 && residentBytes in 0..limitBytes)
        val result = create(limitBytes, residentBytes)
        if (result[0] != 0L) throw ProjectImportException(result[0].toInt())
        handle = result[1]
        check(handle != 0L)
    }
    internal fun <T> useWriter(action: (Long) -> T): T = writer.withLock { lifetime.read {
        check(handle != 0L) { "Project write control is closed" }; action(handle)
    } }
    fun cancel() = lifetime.read { if (handle != 0L) cancel(handle) }
    fun stats(): LongArray = useWriter { stats(it) }
    override fun close() = lifetime.write {
        if (handle != 0L) { destroy(handle); handle = 0 }
    }
    private external fun create(limitBytes: Long, residentBytes: Long): LongArray
    private external fun cancel(handle: Long)
    private external fun destroy(handle: Long)
    private external fun stats(handle: Long): LongArray
}

/** Complete immutable state shares retained tiles and survives later editing and document close. */
internal class NativeProjectSnapshot internal constructor(loader: () -> LongArray,
    private var released: (() -> Unit)? = null) : AutoCloseable {
    private val lifetime = ReentrantReadWriteLock()
    private var handle: Long
    val capturedRevision: Long
    /** Captured content tag in 0..Long.MAX_VALUE; JNI rejects larger unsigned tags.
     * This remains in the originating document's lifetime domain after editor close. It is transient:
     * never persist it or compare it with a reloaded/imported document's new domain. */
    val capturedContentIdentity: Long get() = lifetime.read {
        check(handle != 0L) { "Project snapshot is closed" }
        contentIdentity(handle)
    }
    init {
        val result = loader()
        if (result[0] != 0L) throw ProjectImportException(result[0].toInt())
        handle = result[1]; capturedRevision = result[2]
        check(handle != 0L)
    }
    fun write(context: Context, directory: File, replace: Boolean, control: NativeProjectWriteControl,
        result: ProjectWriteResult = ProjectWriteResult()): ProjectWriteResult {
        val path = NativeProjectIO.ownedPath(context, directory)
        require(path.parentFile?.isDirectory == true) { "The project target parent does not exist" }
        return lifetime.read {
            check(handle != 0L) { "Project snapshot is closed" }
            control.useWriter { nativeControl ->
                writeDirectory(handle, path.path.toByteArray(Charsets.UTF_8), replace, nativeControl, result.fields)
            }
            result
        }
    }
    fun writeTiffSourceLayers(file: File, allowance: Long) = lifetime.read {
        check(handle != 0L) { "Project snapshot is closed" }
        NativeTiffCodec.exportSourceLayersNative(handle, file.absolutePath.toByteArray(Charsets.UTF_8), allowance)
    }
    override fun close() = lifetime.write {
        if (handle != 0L) { destroy(handle); handle = 0; released?.invoke(); released = null }
    }
    private external fun destroy(handle: Long)
    private external fun contentIdentity(handle: Long): Long
    private external fun writeDirectory(handle: Long, path: ByteArray, replace: Boolean, control: Long, result: LongArray)
}
