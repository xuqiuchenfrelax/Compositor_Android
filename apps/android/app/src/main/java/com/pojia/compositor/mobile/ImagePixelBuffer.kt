package com.pojia.compositor.mobile

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** IO-thread-owned storage; no buffer or duplicate may be used after this owner closes. */
internal class ImagePixelBuffer(bytes: Int) : AutoCloseable {
    companion object {
        init { System.loadLibrary("compositor_android") }
        private val allocated = AtomicLong()
        val liveBytes: Long get() = allocated.get()
    }
    private val owner = Thread.currentThread()
    private var storage: ByteBuffer? = allocate(bytes).also { allocated.addAndGet(bytes.toLong()) }
    val buffer: ByteBuffer get() {
        check(Thread.currentThread() === owner) { "Image pixels belong to another IO thread" }
        return checkNotNull(storage) { "Image pixels are closed" }
    }
    override fun close() {
        check(Thread.currentThread() === owner) { "Image pixels belong to another IO thread" }
        val value = storage ?: return
        storage = null
        release(value)
        allocated.addAndGet(-value.capacity().toLong())
    }
    private external fun allocate(bytes: Int): ByteBuffer
    private external fun release(buffer: ByteBuffer)
}
