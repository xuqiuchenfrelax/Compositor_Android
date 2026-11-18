package com.pojia.compositor.mobile

/** A display may wait for shared claims without failing an already committed editor command. */
internal class DisplaySceneAdmission(private val ledger: ResourceBudgetLedger,
    private val requestFrame: () -> Unit, private val waitingChanged: (Boolean) -> Unit = {}) : AutoCloseable {
    internal data class State(val attempts: Long, val capturedScenes: Long, val deferredAttempts: Long,
        val wakeRequests: Long, val waiting: Boolean, val deferred: Boolean, val closed: Boolean,
        val requestedBytes: Long)
    private class Request {
        var waiter: AutoCloseable? = null
        var fired = false
    }
    private val gate = Any()
    private var request: Request? = null
    private var closed = false
    private var deferred = false
    private var attempts = 0L
    private var capturedScenes = 0L
    private var deferredAttempts = 0L
    private var wakeRequests = 0L
    private var requestedBytes = 0L

    internal fun beginAttempt(): Boolean {
        val old = synchronized(gate) {
            if (closed) return false
            attempts++
            request.also { request = null }
        }
        old?.waiter?.close()
        return true
    }
    internal fun captured() {
        val changed = synchronized(gate) {
            if (!closed) capturedScenes++
            deferred.also { deferred = false; requestedBytes = 0 }
        }
        if (changed) waitingChanged(false)
    }
    internal fun unavailable() {
        val changed = synchronized(gate) {
            deferred.also { deferred = false; requestedBytes = 0 }
        }
        if (changed) waitingChanged(false)
    }
    internal val isDeferred: Boolean get() = synchronized(gate) { deferred }
    internal val hasReadyFrame: Boolean get() = synchronized(gate) { !closed && deferred && request?.fired == true }

    internal fun defer(bytes: Long) = deferUntil(bytes) { wake -> ledger.awaitAvailable(bytes, wake) }
    internal fun deferUntilCommands(register: (() -> Unit) -> AutoCloseable) = deferUntil(0, register)

    private fun deferUntil(bytes: Long, register: (() -> Unit) -> AutoCloseable) {
        val next = Request()
        var changed = false
        val old = synchronized(gate) {
            if (closed) return
            changed = !deferred
            deferred = true; requestedBytes = bytes; deferredAttempts++
            request.also { request = next }
        }
        old?.waiter?.close()
        if (changed) waitingChanged(true)
        // Both registration owners recheck availability. The callback may run before return.
        val waiter = register {
            val wake = synchronized(gate) {
                if (closed || request !== next || next.fired) false
                else { next.fired = true; wakeRequests++; true }
            }
            if (wake) requestFrame()
        }
        val retain = synchronized(gate) {
            if (!closed && request === next && !next.fired) { next.waiter = waiter; true } else false
        }
        if (!retain) waiter.close()
    }
    internal fun snapshot(): State = synchronized(gate) {
        State(attempts, capturedScenes, deferredAttempts, wakeRequests,
            request?.fired == false, deferred, closed, requestedBytes)
    }
    override fun close() {
        var changed = false
        val old = synchronized(gate) {
            if (closed) return
            changed = deferred
            closed = true; deferred = false
            request.also { request = null }
        }
        old?.waiter?.close()
        if (changed) waitingChanged(false)
    }
}
