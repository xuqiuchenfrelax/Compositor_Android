package com.pojia.compositor.mobile

import android.content.Context
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Retained control lane. Terminal work cannot queue behind an ordinary command awaiting it.
 * Input is absolute, with one latest-value slot; UP consumes it without recomputing geometry. */
internal class TransformController(private val editor: NativeEditor, context: Context,
    private val postMain: ((() -> Unit) -> Unit), onChanged: ((Snapshot) -> Unit)? = null) : AutoCloseable {
    enum class Phase { BEGINNING, PROMOTING, ACTIVE, TERMINAL_WAITING }
    internal class ControlChain {
        val finished = CompletableFuture<Terminal>()
        var draining = false
        @Volatile var cancelled = false
        var flipX = false
        var flipY = false
        var committed: Terminal? = null
    }
    class Owner internal constructor(val identity: Long, val sessionEpoch: Long, val viewEpoch: Long,
        val inputEpoch: Long, val persistent: Boolean, var rootLayerId: Long, var target: TransformTarget,
        val fromFields: Boolean = false, val predecessorIdentity: Long? = null,
        internal val chain: ControlChain = ControlChain(), internal val press: MovePressInput? = null,
        val duplicateAtDown: Boolean = false, internal var duplicateDownSampling: Int? = null) {
        val retainsDraft: Boolean get() = persistent || fromFields
        val finished: CompletableFuture<Terminal> get() = chain.finished
    }
    data class Terminal(val committed: Boolean, val cancelled: Boolean,
        val result: TransformCommitResult? = null, val error: Throwable? = null)
    data class Accepted(val sequence: Long, val box: Transform64, val sampling: Int) {
        val seq: Long get() = sequence
    }
    data class Snapshot(val owner: Owner, val phase: Phase, val info: TransformDraftInfo?,
        val member: TransformDraftMember? = null, val terminal: Terminal? = null,
        val accepted: Accepted? = null, val appliedSequence: Long = 0) {
        val appliedSeq: Long get() = appliedSequence
    }
    private data class Update(val box: Transform64, val sampling: Int, val sequence: Long)
    private val applicationContext = context.applicationContext
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Compositor transform control") }
    private val identities = AtomicLong()
    private var acceptedSequence = 0L
    private var owner: Owner? = null
    private var draft: NativeTransformDraft? = null
    private var latest: Update? = null
    private var promotionRequested = false
    private var promoted = false
    private var terminalRequested: Boolean? = null // true cancels; false commits.
    private var terminalDecision: Boolean? = null // sealed immediately before native terminal work.
    private var queued = false
    private var closed = false
    private var pendingMain: Snapshot? = null
    private var mainQueued = false
    private class Observer(val changed: (Snapshot) -> Unit)
    private var observer = onChanged?.let(::Observer)
    @Volatile private var current: Snapshot? = null

    fun snapshot(): Snapshot? = current
    /** A reattached Canvas replaces the previous listener; stale detach closures cannot remove it. */
    fun attach(changed: (Snapshot) -> Unit): () -> Unit {
        val binding = Observer(changed)
        val enqueue = synchronized(gate) {
            check(!closed) { "Transform controller is closed" }
            observer = binding
            current?.let(::queueMain) ?: false
        }
        if (enqueue) postMain(::deliverMain)
        return {
            val terminal = synchronized(gate) {
                if (observer === binding) {
                    observer = null
                    current?.takeIf { it.phase == Phase.TERMINAL_WAITING }?.owner
                } else null
            }
            if (terminal != null) acknowledge(terminal)
        }
    }
    fun activeCompletion(): CompletableFuture<Terminal>? = synchronized(gate) { owner?.finished }
    fun begin(rootLayerId: Long, target: TransformTarget, sessionEpoch: Long, viewEpoch: Long,
        inputEpoch: Long, persistent: Boolean = false, fromFields: Boolean = false, press: MovePressInput? = null,
        duplicateAtDown: Boolean = false, duplicateDownSampling: Int? = null): Owner? = editor.serialized { synchronized(gate) {
        if (closed || owner != null || (rootLayerId <= 0 && press == null) || (persistent && fromFields)) return@synchronized null
        val next = Owner(identities.incrementAndGet(), sessionEpoch, viewEpoch, inputEpoch,
            persistent, rootLayerId, target, fromFields, press = press, duplicateAtDown = duplicateAtDown,
            duplicateDownSampling = duplicateDownSampling)
        // The reservation is visible to dispatcher/save/close before any worker item exists.
        if (!editor.reserveTransformControl(next) { cancel(next) }) return@synchronized null
        owner = next
        current = Snapshot(next, Phase.BEGINNING, null)
        try { schedule(next) }
        catch (error: Throwable) {
            owner = null; current = null
            editor.retireTransformControl(next)
            next.finished.completeExceptionally(error)
            throw error
        }
        next
    } }
    fun update(value: Owner, box: Transform64, sampling: Int = 0): Boolean {
        val enqueue = synchronized(gate) {
            if (closed || owner !== value || terminalRequested != null || current?.phase == Phase.TERMINAL_WAITING ||
                sampling !in 0..2 || !(if (value.target == TransformTarget.GROUP) MoveGeometry.validGroup(box) else MoveGeometry.valid(box))) return false
            val accepted = Accepted(++acceptedSequence, box, sampling)
            current = checkNotNull(current).copy(accepted = accepted)
            latest = Update(box, sampling, accepted.sequence)
            schedule(value)
            queueMain(checkNotNull(current))
        }
        if (enqueue) postMain(::deliverMain)
        return true
    }
    fun firstDrag(value: Owner): Boolean {
        val enqueue = synchronized(gate) {
            if (closed || owner !== value || !value.duplicateAtDown || terminalDecision != null ||
                current?.phase == Phase.TERMINAL_WAITING) return false
            if (!promotionRequested) {
                promotionRequested = true
                current = checkNotNull(current).copy(phase = Phase.PROMOTING)
                schedule(value)
            }
            queueMain(checkNotNull(current))
        }
        if (enqueue) postMain(::deliverMain)
        return true
    }
    fun isFinishing(value: Owner): Boolean = synchronized(gate) {
        owner === value && (terminalRequested != null || current?.phase == Phase.TERMINAL_WAITING)
    }
    private fun canQueueFlip(value: Owner): Boolean = !closed && owner === value && value.fromFields &&
        !value.persistent && !value.chain.draining && !value.chain.cancelled && terminalRequested == false &&
        terminalDecision != true && current?.terminal?.let { it.committed && !it.cancelled && it.error == null } != false
    fun canQueueInspectorFlip(value: Owner): Boolean = synchronized(gate) { canQueueFlip(value) }
    fun queueInspectorFlip(value: Owner, horizontal: Boolean): Boolean = synchronized(gate) {
        if (!canQueueFlip(value)) false else {
            if (horizontal) value.chain.flipX = !value.chain.flipX else value.chain.flipY = !value.chain.flipY
            true
        }
    }
    fun finish(value: Owner): CompletableFuture<Terminal> = requestTerminal(value, false)
    fun cancel(value: Owner): CompletableFuture<Terminal> = requestTerminal(value, true)
    /** A context barrier seals further Inspector continuations and drains any admitted flip. */
    fun finishInspectorChain(value: Owner): CompletableFuture<Terminal> = synchronized(gate) {
        val active = owner
        if (active != null && active.chain === value.chain) {
            active.chain.draining = true
            if (terminalDecision == null && current?.phase != Phase.TERMINAL_WAITING) {
                terminalRequested = terminalRequested == true
                schedule(active)
            }
        }
        value.finished
    }
    private fun requestTerminal(value: Owner, cancel: Boolean): CompletableFuture<Terminal> = synchronized(gate) {
        val active = owner
        val matches = active === value || (cancel && active != null && active.chain === value.chain)
        if (matches && cancel) {
            value.chain.cancelled = true; value.chain.draining = true
            value.chain.flipX = false; value.chain.flipY = false
        }
        if (matches && active != null && terminalDecision == null && current?.phase != Phase.TERMINAL_WAITING) {
            // Cancellation may override a pending finish, never a published native commit.
            terminalRequested = terminalRequested == true || cancel
            schedule(active)
        }
        value.finished
    }
    private fun schedule(value: Owner) {
        if (queued) return
        queued = true
        worker.execute { drain(value) }
    }
    private fun publish(value: Snapshot) {
        val enqueue = synchronized(gate) {
            if (owner !== value.owner || current?.phase == Phase.TERMINAL_WAITING) return
            val accepted = current?.accepted
            val retained = if (accepted != null && accepted.sequence == value.appliedSequence && value.info != null)
                accepted.copy(box = value.info.box, sampling = value.info.sampling) else accepted
            val merged = value.copy(phase = if (promotionRequested && !promoted) Phase.PROMOTING else value.phase,
                accepted = retained,
                appliedSequence = maxOf(current?.appliedSequence ?: 0, value.appliedSequence))
            current = merged
            queueMain(merged)
        }
        if (enqueue) postMain(::deliverMain)
    }
    private fun queueMain(value: Snapshot): Boolean {
        pendingMain = value
        if (mainQueued) return false
        mainQueued = true
        return true
    }
    private fun deliverMain() {
        val value = synchronized(gate) {
            val next = pendingMain
            pendingMain = null; mainQueued = false
            next
        } ?: return
        val binding = synchronized(gate) { observer.takeIf { owner === value.owner } }
        try {
            if (binding != null && synchronized(gate) { observer === binding && owner === value.owner }) {
                binding.changed(value)
            }
        } catch (error: Throwable) {
            if (value.phase == Phase.TERMINAL_WAITING) synchronized(gate) {
                if (owner === value.owner && current?.phase == Phase.TERMINAL_WAITING) {
                    val terminal = checkNotNull(current?.terminal)
                    terminal.error?.let { if (it !== error) error.addSuppressed(it) }
                    current = checkNotNull(current).copy(terminal = terminal.copy(error = error))
                    value.owner.chain.flipX = false; value.owner.chain.flipY = false
                }
            }
            throw error
        } finally { if (value.phase == Phase.TERMINAL_WAITING) acknowledge(value.owner) }
    }
    private fun drain(value: Owner) {
        try {
            if (draft == null) {
                draft = if (value.press != null) editor.beginMovePress(applicationContext, value, value.press)
                    else editor.beginTransformDraft(applicationContext, value.rootLayerId, value.target, value)
                val active = draft
                if (active == null) { terminal(value, Terminal(false, value.chain.cancelled)); return }
                val info = active.info()
                if (value.duplicateAtDown && value.duplicateDownSampling == null)
                    value.duplicateDownSampling = info.sampling
                publish(Snapshot(value, Phase.ACTIVE, info,
                    if (info.memberCount == 1) active.member() else null))
            }
            while (true) {
                val work = synchronized(gate) {
                    if (owner !== value) { queued = false; return }
                    val update = latest; latest = null
                    val terminal = terminalRequested
                    if (update == null && terminal == null && !(promotionRequested && !promoted)) {
                        queued = false; return
                    }
                    update to terminal
                }
                var active = checkNotNull(draft)
                if (value.duplicateAtDown && promotionRequested && !promoted) {
                        active.settleDuplicatePredecessor()
                        active.commit()
                        active = checkNotNull(editor.beginTransformDraft(applicationContext,
                            value.rootLayerId, value.target, value))
                        draft = active
                        when (editor.promoteDuplicate(applicationContext, active)) {
                            DuplicateOutcome.NO_COPIES -> {
                                active.close(); terminal(value, Terminal(false, true)); return
                            }
                            DuplicateOutcome.PROMOTED -> {
                                promoted = true
                                val initial = active.info()
                                val sampling = checkNotNull(value.duplicateDownSampling)
                                if (initial.sampling != sampling) active.update(initial.box, sampling)
                                editor.refreshDuplicatePresentation(active)
                                val info = active.info()
                                value.rootLayerId = info.primaryLayerId
                                value.target = info.target
                                publish(Snapshot(value, Phase.ACTIVE, info,
                                    if (info.memberCount == 1) active.member() else null))
                            }
                        }
                }
                if (work.second != true && work.first != null) {
                    val update = checkNotNull(work.first)
                    val info = active.update(update.box, update.sampling)
                    publish(Snapshot(value, Phase.ACTIVE, info, current?.member, appliedSequence = update.sequence))
                }
                if (work.second != null) {
                    // Cancel can supersede the pending finish through the final update. The
                    // decision seals under the same input gate immediately before native work;
                    // later requests observe this terminal's future without changing its outcome.
                    val cancel = synchronized(gate) {
                        (terminalRequested == true).also { terminalDecision = it }
                    }
                    val terminal = if (cancel) {
                        val result = active.cancelWithResult()
                        Terminal(result?.changed == true, true, result)
                    } else {
                        val result = active.commit(); Terminal(true, false, result)
                    }
                    terminal(value, terminal)
                    return
                }
            }
        } catch (error: Throwable) {
            if (editor.hasUnresolvedMovePress) {
                try { editor.recoverMovePress(cancel = true) }
                catch (recovery: Throwable) { if (recovery !== error) error.addSuppressed(recovery) }
            }
            if (editor.hasUnresolvedDuplicate) {
                try { editor.recoverDuplicate() }
                catch (recovery: Throwable) { if (recovery !== error) error.addSuppressed(recovery) }
            }
            if (editor.hasUnresolvedDuplicateCancel) {
                try { editor.recoverDuplicateCancel() }
                catch (recovery: Throwable) { if (recovery !== error) error.addSuppressed(recovery) }
            }
            val active = draft
            val confirmed = active?.commitConfirmed == true || active?.cancelConfirmed == true
            if (!confirmed && !editor.hasUnresolvedMovePress && !editor.hasUnresolvedDuplicate && !editor.hasUnresolvedDuplicateCancel)
                try { active?.close() } catch (cancelError: Throwable) { error.addSuppressed(cancelError) }
            terminal(value, Terminal(active?.commitConfirmed == true || active?.cancelledResult?.changed == true,
                active?.cancelConfirmed == true || (!confirmed && !editor.hasUnresolvedMovePress &&
                    !editor.hasUnresolvedDuplicate && !editor.hasUnresolvedDuplicateCancel),
                active?.committedResult ?: active?.cancelledResult, error))
        }
    }
    private fun terminal(value: Owner, result: Terminal) {
        val enqueue = synchronized(gate) {
            if (owner !== value) return
            val terminal = checkNotNull(current).copy(phase = Phase.TERMINAL_WAITING, terminal = result)
            // Seal terminal under the input gate before exposing an idle worker slot.
            current = terminal
            latest = null; draft = null; queued = false
            if (observer == null) false else queueMain(terminal)
        }
        // Detached documents still release dispatcher/save/close waiters without a Canvas callback.
        if (enqueue) postMain(::deliverMain)
        if (synchronized(gate) { observer == null }) acknowledge(value)
    }
    /** Main acknowledgment is the last operation: next input and waiting ordinary commands remain
     * rejected until the view has settled its state. It must run even when the view has retired. */
    fun acknowledge(value: Owner) {
        var completion: Terminal? = null
        var enqueue = false
        // Retire and reserve under one editor command lock, so no command can enter between
        // two accepted Inspector segments. Successors retain the original chain fence.
        editor.serialized { synchronized(gate) {
            if (owner !== value || current?.phase != Phase.TERMINAL_WAITING) return@synchronized
            val snapshot = checkNotNull(current)
            val terminal = checkNotNull(snapshot.terminal)
            val chain = value.chain
            if (terminal.committed) chain.committed = terminal
            val seed = snapshot.info
            val continuation = !closed && !chain.cancelled && terminal.committed && !terminal.cancelled &&
                terminal.error == null && seed != null && (chain.flipX || chain.flipY)
            editor.retireTransformControl(value)
            owner = null; current = null; terminalRequested = null; terminalDecision = null
            promotionRequested = false; promoted = false
            if (pendingMain?.owner === value) pendingMain = null
            if (continuation) {
                val info = checkNotNull(seed)
                val box = info.box.copy(flipX = info.box.flipX xor chain.flipX, flipY = info.box.flipY xor chain.flipY)
                chain.flipX = false; chain.flipY = false
                val next = Owner(identities.incrementAndGet(), value.sessionEpoch, value.viewEpoch, value.inputEpoch,
                    false, value.rootLayerId, value.target, true, value.identity, chain)
                try {
                    check(snapshot.accepted?.let { it.sequence == snapshot.appliedSequence &&
                        it.box == info.box && it.sampling == info.sampling } != false) {
                        "Inspector continuation requires the final applied transform"
                    }
                    check(editor.reserveTransformControl(next) { cancel(next) }) { "Inspector continuation could not reserve transform control" }
                    owner = next
                    val accepted = Accepted(++acceptedSequence, box, info.sampling)
                    current = Snapshot(next, Phase.BEGINNING, null, accepted = accepted)
                    latest = Update(box, info.sampling, accepted.sequence)
                    if (chain.draining) terminalRequested = false
                    schedule(next)
                    enqueue = queueMain(checkNotNull(current))
                } catch (error: Throwable) {
                    editor.retireTransformControl(next)
                    owner = null; current = null; latest = null; queued = false
                    terminalRequested = null; terminalDecision = null
                    completion = terminal.copy(error = error)
                }
            } else {
                chain.flipX = false; chain.flipY = false
                val committed = chain.committed
                completion = if (committed != null && !terminal.committed)
                    terminal.copy(committed = true, result = terminal.result ?: committed.result) else terminal
            }
        } }
        if (enqueue) postMain(::deliverMain)
        completion?.let { value.finished.complete(it) }
    }
    override fun close() {
        val active = synchronized(gate) {
            closed = true; observer = null
            owner?.also { it.chain.flipX = false; it.chain.flipY = false }
        }
        if (active != null) {
            if (snapshot()?.phase == Phase.TERMINAL_WAITING) acknowledge(active) else cancel(active)
            active.finished.whenComplete { _, _ -> worker.shutdown() }
        } else worker.shutdown()
    }
    /** Document shutdown calls this after releasing the command lock and draining ordinary commands. */
    internal fun awaitClosed() {
        var interrupted = false
        try {
            while (true) {
                try { if (worker.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) return }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
