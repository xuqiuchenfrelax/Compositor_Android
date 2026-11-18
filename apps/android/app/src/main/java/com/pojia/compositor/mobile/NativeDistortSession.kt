package com.pojia.compositor.mobile

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Managed owner for one native P55 Distort worker.  It owns the native arena claim and keeps the
 * full draft/ticket identities available for the P54 commit protocol.  Waiting never touches the
 * document; submit, publish, commit and teardown are fenced by NativeEditor's command gate.
 */
internal class NativeDistortSession internal constructor(
    private val editor: NativeEditor,
    internal val token: Long,
    internal val draft: DistortDraftIdentity,
    internal val arenaReservation: ResourceBudgetLedger.Reservation,
    internal val nativeEnvelopeLimit: Long,
    internal val growthBytes: Long,
) : AutoCloseable {
    private val quad = NativeDistortSessionBridge.record(NativeDistortSessionBridge.QUAD_BYTES)
    private val ticketLock = Any()
    private val sealed = AtomicBoolean(false)
    @Volatile private var sealing = false
    private var submitted = false
    private var published = false
    private var fullResolution = false
    private var acceptedCorners: DoubleArray? = null
    private var acceptedFlipX = false
    private var acceptedFlipY = false
    private var latestTicket: ByteBuffer? = null
    private var committedOwner: NativeDistortCommitOwner? = null

    fun submitQuad(corners: DoubleArray, previewLimit: Double = 2048.0,
        flipX: Boolean = false, flipY: Boolean = false): Int {
        check(!sealed.get() && !sealing) { "Distort session is sealed or sealing" }
        return synchronized(ticketLock) {
            val capturedCorners = corners.copyOf()
            NativeDistortSessionBridge.putQuad(quad, corners, flipX, flipY, previewLimit)
            val ticket = NativeDistortSessionBridge.record(NativeDistortSessionBridge.TICKET_BYTES)
            val status = editor.distortSubmit(token, quad, ticket)
            if (status == 0) {
                submitted = true; published = false; latestTicket = ticket
                fullResolution = previewLimit == 0.0
                acceptedCorners = capturedCorners; acceptedFlipX = flipX; acceptedFlipY = flipY
            }
            status
        }
    }

    /** Keep dragging previews bounded; Apply renders the last accepted quad at full resolution. */
    fun submitFinalQuad(): Int = synchronized(ticketLock) {
        val corners = checkNotNull(acceptedCorners) { "Distort worker has no accepted quad" }
        submitQuad(corners, previewLimit = 0.0, flipX = acceptedFlipX, flipY = acceptedFlipY)
    }

    /** Worker-only mailbox wait; safe to call from a background executor. */
    fun wait(timeoutMillis: Long): Int {
        val ticket = synchronized(ticketLock) { latestTicket }
        check(!sealed.get() && !sealing && submitted && ticket != null) {
            "Distort worker has no submitted ticket"
        }
        require(timeoutMillis >= 0)
        val start = System.nanoTime()
        do {
            if (sealed.get() || sealing) return NativeDistortSessionBridge.WAIT_CLOSED
            val remaining = (timeoutMillis - (System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0)
            // Each JNI wait holds the session gate. Release it frequently so Cancel/exit can
            // retire a long final render instead of blocking the UI for the entire timeout.
            val status = NativeDistortSessionBridge.wait(token, checkNotNull(ticket), minOf(remaining, 50L))
            if (status != NativeDistortSessionBridge.WAIT_TIMEOUT) return status
        } while ((System.nanoTime() - start) / 1_000_000L < timeoutMillis)
        return NativeDistortSessionBridge.WAIT_TIMEOUT
    }

    fun publish(): Int {
        val ticket = synchronized(ticketLock) { latestTicket }
        check(!sealed.get() && !sealing && submitted && ticket != null) {
            "Distort worker has no submitted ticket"
        }
        val status = editor.distortPublish(token, checkNotNull(ticket))
        if (status == 0) synchronized(ticketLock) { published = true }
        return status
    }

    /** Capture the immutable native draft scene after a successful publish. */
    fun snapshot(): NativeScene {
        check(!sealed.get() && published) { "Publish a ready Distort preview before snapshotting" }
        val record = NativeDistortSessionBridge.record(NativeDistortSessionBridge.SNAPSHOT_BYTES)
        val status = editor.distortSnapshot(token, record)
        check(status == 0) { "Distort preview snapshot failed ($status)" }
        val values = NativeDistortSessionBridge.snapshot(record)
        check(values[0] > 0L) { "Distort preview returned an empty scene" }
        val sceneToken = values[0]
        return NativeScene.takeDistortSnapshot(sceneToken, editor.resourceLedger)
    }

    /** Commits the published preview through the existing P54 receipt/ledger protocol. */
    fun apply(): Int {
        check(!sealed.get() && published && fullResolution) { "Publish a full-resolution Distort result before applying" }
        if (committedOwner == null) {
            // Store the owner before dispatching.  If prepare/apply/ledger
            // settlement throws, the unresolved identity remains reachable and
            // can be recovered by a later Apply or by controlled teardown.
            committedOwner = editor.commitDistortSession(this)
        }
        editor.recoverDistortCommit(committedOwner!!)
        check(committedOwner?.resultStatus == 0) {
            "Distort commit failed (${committedOwner?.resultStatus})"
        }
        check(committedOwner?.isResolved == true) {
            "Distort commit is unresolved (${committedOwner?.phase}); keep the session for recovery"
        }
        return 0
    }

    fun ticket(): DistortWorkIdentity {
        val ticket = synchronized(ticketLock) { latestTicket }
        checkNotNull(ticket) { "Distort worker has no submitted ticket" }
        val values = NativeDistortSessionBridge.ticket(ticket)
        return DistortWorkIdentity(values[0].toULong(), values[1].toULong(), values[2].toULong(),
            values[3].toULong(), values[4].toULong(), values[5].toULong())
    }

    fun cancel() {
        if (sealed.get()) return
        // A dispatched P54 transaction owns live document/arena claims until
        // it resolves.  Do not tear down the worker and lose the only recovery
        // handle when Apply needs another query/ack attempt.
        committedOwner?.takeIf { !it.isResolved }?.let { owner ->
            val status = editor.recoverDistortCommit(owner)
            check(status == 0 && owner.isResolved) {
                "Distort commit is unresolved (${owner.phase}); apply or recover it before cancelling"
            }
        }
        if (!published && submitted) runCatching { editor.distortCancel(token) }
        seal()
    }

    /** Worker join precedes registry destruction and arena release. */
    fun seal() {
        synchronized(ticketLock) {
            if (sealed.get() || sealing) return
            check(committedOwner?.isResolved != false) {
                "Distort commit is unresolved; keep the session for recovery"
            }
            // Do not make a failed join irreversible.  A transient native/JNI
            // error must leave the token and unresolved owner available for a
            // retry rather than leaking the worker and its budget claim.
            sealing = true
        }
        try {
            check(editor.distortSeal(token) == 0) { "Distort worker join failed" }
            check(editor.distortDestroy(token) == 0) { "Distort session destruction failed" }
            editor.endDistortSession(token)
            synchronized(ticketLock) {
                sealed.set(true)
                sealing = false
            }
            if (committedOwner?.isResolved != false) {
                committedOwner?.releasePinsAfterNativeDestruction()
                arenaReservation.close()
            }
        } catch (error: Throwable) {
            synchronized(ticketLock) { sealing = false }
            throw error
        }
    }

    override fun close() = cancel()
}
