package com.pojia.compositor.mobile

import java.util.concurrent.CompletableFuture

/** One context request waits for the existing transform's main acknowledgment. */
internal class MoveContextSwitch(private val postMain: (() -> Unit) -> Unit,
    private val pendingChanged: (Boolean) -> Unit, private val failed: (Throwable) -> Unit) {
    private var request: Any? = null
    val pending: Boolean get() = request != null

    fun submit(finish: (() -> CompletableFuture<TransformController.Terminal>)?,
        current: () -> Boolean, apply: () -> Unit): Boolean {
        if (pending || !current()) return false
        if (finish == null) {
            apply()
            return true
        }
        val token = Any()
        request = token; pendingChanged(true)
        try {
            finish().whenComplete { terminal, error -> postMain {
                if (request !== token) return@postMain
                request = null; pendingChanged(false)
                // A confirmed publication with an observer error stays committed, but does
                // not change context. Retrying the context request cannot replay that edit.
                if (current() && error == null && terminal?.committed == true &&
                    !terminal.cancelled && terminal.error == null) {
                    try { apply() } catch (failure: Throwable) { failed(failure) }
                }
            } }
        } catch (error: Throwable) {
            if (request === token) { request = null; pendingChanged(false) }
            failed(error)
            return false
        }
        return true
    }

    fun invalidate() {
        request = null; pendingChanged(false)
    }
}
