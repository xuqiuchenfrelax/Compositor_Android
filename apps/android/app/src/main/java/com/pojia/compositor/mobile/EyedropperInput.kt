package com.pojia.compositor.mobile

internal interface ColorSamplingSession : AutoCloseable {
    fun sample(x: Double, y: Double): Int?
}

internal data class SampleRingState(val x: Float, val y: Float, val original: Int, val sampled: Int)
internal data class EyedropperStats(val submittedPoints: Long, val sampledPoints: Long,
    val coalescedPoints: Long, val samplerCreates: Long, val samplerCloses: Long,
    val pendingRunnables: Int, val maximumPendingRunnables: Int)

/** DOWN is preserved; later absolute points share one mailbox and one queued GL callback. */
internal class EyedropperInput(private val enqueue: (() -> Unit) -> Unit,
    private val open: () -> ColorSamplingSession, private val deliver: (Long, Int?, Float, Float) -> Unit,
    private val failed: (Long, Exception) -> Unit, private val finished: (Long) -> Unit = {}) {
    private data class Point(val x: Double, val y: Double, val screenX: Float, val screenY: Float)
    private class Gesture(val token: Long, val down: Point) {
        @Volatile var downPending = true
        @Volatile var latest: Point? = null
        @Volatile var finished = false
        @Volatile var cancelled = false
        var completionDelivered = false
    }
    private val gate = Any()
    private val ownerGate = Any()
    private var nextToken = 0L
    private var requested: Gesture? = null
    private var owned: Gesture? = null
    private var session: ColorSamplingSession? = null
    private var scheduled = false
    private var submitted = 0L
    private var sampled = 0L
    private var coalesced = 0L
    private var creates = 0L
    private var closes = 0L
    private var maximumPending = 0

    fun begin(x: Double, y: Double, screenX: Float, screenY: Float): Long = synchronized(gate) {
        requested?.cancelled = true
        val gesture = Gesture(++nextToken, Point(x, y, screenX, screenY))
        requested = gesture; submitted++
        schedule()
        gesture.token
    }
    fun move(token: Long, x: Double, y: Double, screenX: Float, screenY: Float) = synchronized(gate) {
        val gesture = requested?.takeIf { it.token == token && !it.finished && !it.cancelled } ?: return@synchronized
        if (gesture.latest != null) coalesced++
        gesture.latest = Point(x, y, screenX, screenY); submitted++
        schedule()
    }
    fun finish(token: Long) = synchronized(gate) {
        requested?.takeIf { it.token == token && !it.cancelled }?.let { it.finished = true; schedule() }
    }
    fun cancel() {
        synchronized(gate) { requested?.let { it.cancelled = true; it.latest = null } }
        // Cleanup does not depend on the GL queue. Scene release still observes its document command fence.
        closeSession(synchronized(ownerGate) { detachSession() })
    }
    fun isCurrent(token: Long): Boolean = synchronized(gate) {
        requested?.let { it.token == token && !it.cancelled } == true
    }
    fun stats(): EyedropperStats = synchronized(gate) {
        EyedropperStats(submitted, sampled, coalesced, creates, closes, if (scheduled) 1 else 0, maximumPending)
    }
    private fun schedule() {
        if (!scheduled) {
            scheduled = true; maximumPending = maxOf(maximumPending, 1)
            enqueue(::drain)
        }
    }
    private fun detachSession(): ColorSamplingSession? {
        val closing = session; session = null; owned = null
        return closing
    }
    private fun closeSession(closing: ColorSamplingSession?) {
        if (closing != null) try { closing.close() } finally { synchronized(gate) { closes++ } }
    }
    private fun drain() {
        val gesture = synchronized(gate) { requested }
        try {
            closeSession(synchronized(ownerGate) {
                if (owned !== gesture || gesture?.cancelled != false) detachSession() else null
            })
            if (gesture != null && !gesture.cancelled) {
                val needsOwner = synchronized(ownerGate) { session == null }
                if (needsOwner && (gesture.downPending || gesture.latest != null)) {
                    val opened = open()
                    synchronized(gate) { creates++ }
                    val retained = synchronized(ownerGate) {
                        if (isCurrent(gesture.token)) { session = opened; owned = gesture; true } else false
                    }
                    if (!retained) closeSession(opened)
                }
                val points = synchronized(gate) {
                    if (requested !== gesture || gesture.cancelled) emptyList()
                    else buildList {
                        if (gesture.downPending) { add(gesture.down); gesture.downPending = false }
                        gesture.latest?.let { add(it); gesture.latest = null }
                    }
                }
                for (point in points) {
                    val result = synchronized(ownerGate) {
                        if (!isCurrent(gesture.token) || owned !== gesture) null
                        else Pair(true, checkNotNull(session).sample(point.x, point.y))
                    } ?: break
                    synchronized(gate) { sampled++ }
                    deliver(gesture.token, result.second, point.screenX, point.screenY)
                }
                val complete = synchronized(gate) {
                    if (gesture.finished && gesture.latest == null && !gesture.downPending && !gesture.completionDelivered) {
                        gesture.completionDelivered = true; true
                    } else false
                }
                if (complete) {
                    closeSession(synchronized(ownerGate) { if (owned === gesture) detachSession() else null })
                    finished(gesture.token)
                }
            }
        } catch (error: Exception) {
            if (gesture != null) {
                synchronized(gate) { gesture.cancelled = true; gesture.latest = null }
                failed(gesture.token, error)
            }
            try { closeSession(synchronized(ownerGate) { detachSession() }) } catch (closing: Exception) { error.addSuppressed(closing) }
        } finally {
            synchronized(gate) {
                scheduled = false
                val latest = requested
                if (latest !== gesture || latest?.let { !it.cancelled &&
                    (it.downPending || it.latest != null || (it.finished && !it.completionDelivered)) } == true)
                    schedule()
            }
        }
    }
}
