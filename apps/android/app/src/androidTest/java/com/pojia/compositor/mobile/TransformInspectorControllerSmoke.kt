package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Holds the real JNI command lock and main receipts separately; no timing-based event ordering. */
internal fun transformInspectorControllerSmoke(instrumentation: Instrumentation): String {
    val initial = ImageIoAdmission.ledger.snapshot()
    InspectorControllerFixture(instrumentation).use { f ->
        val controller = f.controller
        val baseline = f.editor.state()
        val original = baseline.layers.single().transform
        val moved = original.copy(x = original.x + 2.0, y = original.y + 1.0)
        val completed = AtomicInteger()
        val owner = f.editor.serialized {
            val value = f.begin()
            check(value.fromFields && value.retainsDraft && !value.persistent && value.predecessorIdentity == null)
            check(controller.snapshot()?.phase == TransformController.Phase.BEGINNING)
            check(controller.update(value, moved.copy(x = moved.x - 1.0)))
            val first = checkNotNull(controller.snapshot()?.accepted)
            check(controller.update(value, moved))
            val accepted = checkNotNull(controller.snapshot()?.accepted)
            check(accepted.sequence > first.sequence && accepted.box == moved && accepted.sampling == 0)
            check(controller.snapshot()?.info == null && controller.snapshot()?.appliedSequence == 0L)
            check(!controller.update(value, moved.copy(width = Double.NaN)))
            check(controller.snapshot()?.accepted == accepted)
            var staleCalls = 0
            val staleDetach = controller.attach { staleCalls++ }
            var rebound: TransformController.Snapshot? = null
            controller.attach { rebound = it }
            staleDetach()
            f.main()
            check(staleCalls == 0 && rebound?.accepted == accepted && rebound?.info == null)
            check(rebound?.appliedSequence == 0L)
            controller.attach { f.delivered.add(it) }
            check(!controller.canQueueInspectorFlip(value) && !controller.queueInspectorFlip(value, true))
            check(controller.finish(value) === value.finished && controller.isFinishing(value))
            check(controller.queueInspectorFlip(value, true) && controller.queueInspectorFlip(value, false))
            check(controller.snapshot()?.accepted == accepted) { "Queued flips changed the predecessor's accepted state" }
            value.finished.whenComplete { _, _ -> completed.incrementAndGet() }
            check(f.editor.activeTransformCompletion() === value.finished && f.editor.reserveStroke() == null &&
                f.editor.tryRegisterPendingEdit() == null)
            value
        }
        f.terminal()
        val predecessor = checkNotNull(controller.snapshot())
        check(predecessor.info?.box == moved && predecessor.info?.sampling == 0)
        check(predecessor.accepted?.sequence == predecessor.appliedSequence && !owner.finished.isDone)
        check(f.editor.state().revision == baseline.revision + 1 && f.editor.reserveStroke() == null)
        f.main()
        f.waitFor("Inspector successor did not apply both flip parities") {
            controller.snapshot()?.let { it.owner.predecessorIdentity == owner.identity &&
                it.info?.box == moved.copy(flipX = !moved.flipX, flipY = !moved.flipY) &&
                it.appliedSequence == it.accepted?.sequence } == true
        }
        val successor = checkNotNull(controller.snapshot()?.owner)
        check(successor !== owner && successor.fromFields && successor.retainsDraft && !successor.persistent)
        check(successor.rootLayerId == owner.rootLayerId && successor.target == owner.target &&
            successor.sessionEpoch == owner.sessionEpoch && successor.viewEpoch == owner.viewEpoch &&
            successor.inputEpoch == owner.inputEpoch)
        check(successor.finished === owner.finished && f.editor.activeTransformCompletion() === owner.finished &&
            !owner.finished.isDone && f.editor.reserveStroke() == null && f.editor.tryRegisterPendingEdit() == null)
        check(checkNotNull(controller.snapshot()?.accepted).sequence > checkNotNull(predecessor.accepted).sequence)
        controller.finish(owner)
        check(!controller.isFinishing(successor)) { "A stale field completion finished the successor" }
        check(controller.finishInspectorChain(owner) === owner.finished)
        check(!controller.queueInspectorFlip(successor, true))
        f.terminal()
        check(!owner.finished.isDone && f.editor.reserveStroke() == null)
        f.main()
        val terminal = owner.finished.get(10, TimeUnit.SECONDS)
        check(terminal.committed && !terminal.cancelled && terminal.error == null && completed.get() == 1)
        val flipped = moved.copy(flipX = !moved.flipX, flipY = !moved.flipY)
        check(f.editor.state().layers.single().transform == flipped && f.editor.state().revision == baseline.revision + 2)
        check(f.editor.activeTransformCompletion() == null && controller.snapshot() == null)
        check(f.pixels(moved).contentEquals(byteArrayOf(-1, -1, 0, -1, 0, 0, -1, -1,
            0, -1, 0, -1, -1, 0, 0, -1))) { "Committed H/V pixels differ from the independent asymmetric oracle" }
        f.editor.undo(false)
        check(f.editor.state().layers.single().transform == moved && f.pixels(moved).contentEquals(f.source))
        f.editor.undo(false)
        check(f.editor.state().layers == baseline.layers)
        f.editor.undo(true); f.editor.undo(true)
        check(f.editor.state().layers.single().transform == flipped && completed.get() == 1)
    }
    InspectorControllerFixture(instrumentation).use { f ->
        val baseline = f.editor.state()
        val owner = f.editor.serialized {
            val value = f.begin()
            check(f.controller.update(value, baseline.layers.single().transform.copy(x = 1.0)))
            f.controller.finish(value)
            check(f.controller.queueInspectorFlip(value, true) && f.controller.queueInspectorFlip(value, true))
            value
        }
        f.terminal(); f.main()
        check(owner.finished.get(10, TimeUnit.SECONDS).committed)
        check(f.controller.snapshot() == null && f.editor.state().revision == baseline.revision + 1 &&
            !f.editor.state().layers.single().transform.flipX)
        f.editor.undo(false)
        check(f.editor.state().layers == baseline.layers)
    }
    InspectorControllerFixture(instrumentation).use { f ->
        val baseline = f.editor.state()
        val owner = f.editor.serialized {
            val value = f.begin()
            check(f.controller.update(value, baseline.layers.single().transform.copy(x = 1.0)))
            f.controller.finish(value)
            check(f.controller.queueInspectorFlip(value, true))
            f.controller.cancel(value)
            check(!f.controller.queueInspectorFlip(value, false))
            value
        }
        f.terminal(); f.main()
        val terminal = owner.finished.get(10, TimeUnit.SECONDS)
        check(terminal.cancelled && !terminal.committed && terminal.error == null && f.editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null))
    }
    InspectorControllerFixture(instrumentation).use { f ->
        val baseline = f.editor.state()
        val owner = f.editor.serialized {
            val value = f.begin()
            check(f.controller.update(value, baseline.layers.single().transform.copy(x = 1.0)))
            f.controller.finish(value)
            check(f.controller.queueInspectorFlip(value, false))
            check(f.controller.finishInspectorChain(value) === value.finished)
            check(!f.controller.queueInspectorFlip(value, true))
            value
        }
        f.terminal(); f.main()
        f.waitFor("Early drain successor terminal did not arrive") {
            owner.finished.isDone || f.controller.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING
        }
        f.main()
        check(owner.finished.get(10, TimeUnit.SECONDS).committed && f.editor.state().revision == baseline.revision + 2 &&
            f.editor.state().layers.single().transform.flipY)
    }
    InspectorControllerFixture(instrumentation).use { f ->
        val owner = checkNotNull(f.controller.begin(f.root, TransformTarget.LAYER, 1, 2, 3, persistent = true))
        check(owner.retainsDraft && !owner.fromFields)
        f.controller.finish(owner)
        check(!f.controller.canQueueInspectorFlip(owner) && !f.controller.queueInspectorFlip(owner, true))
        f.terminal(); f.main(); check(owner.finished.get(10, TimeUnit.SECONDS).committed)
        check(f.controller.begin(f.root, TransformTarget.LAYER, 1, 2, 3, persistent = true, fromFields = true) == null)
    }
    InspectorControllerFixture(instrumentation).use { f ->
        val baseline = f.editor.state()
        f.editor.committed = { error("Inspector confirmed commit observer fixture") }
        val owner = f.editor.serialized {
            val value = f.begin()
            check(f.controller.update(value, baseline.layers.single().transform.copy(x = 1.0)))
            f.controller.finish(value)
            check(f.controller.queueInspectorFlip(value, true))
            value
        }
        f.terminal(); f.main()
        val terminal = owner.finished.get(10, TimeUnit.SECONDS)
        check(terminal.committed && !terminal.cancelled && terminal.error != null)
        check(f.editor.state().revision == baseline.revision + 1 && !f.editor.state().layers.single().transform.flipX &&
            f.controller.snapshot() == null)
        f.editor.committed = null
    }
    InspectorControllerFixture(instrumentation).use { f ->
        val baseline = f.editor.state()
        val detach = f.controller.attach {}
        val owner = f.editor.serialized {
            val value = f.begin()
            check(f.controller.update(value, baseline.layers.single().transform.copy(x = 1.0)))
            f.controller.finish(value)
            check(f.controller.queueInspectorFlip(value, true))
            f.controller.finishInspectorChain(value)
            detach()
            value
        }
        check(owner.finished.get(10, TimeUnit.SECONDS).committed)
        check(f.controller.snapshot() == null && f.editor.activeTransformCompletion() == null &&
            f.editor.state().revision == baseline.revision + 2 && f.editor.state().layers.single().transform.flipX)
    }
    InspectorControllerFixture(instrumentation).use { f ->
        val baseline = f.editor.state()
        val owner = f.editor.serialized {
            val value = f.begin()
            check(f.controller.update(value, baseline.layers.single().transform.copy(x = 1.0)))
            f.controller.finish(value)
            check(f.controller.queueInspectorFlip(value, true))
            f.controller.close()
            check(!f.controller.queueInspectorFlip(value, false))
            value
        }
        check(owner.finished.get(10, TimeUnit.SECONDS).cancelled)
        f.controller.awaitClosed()
        check(f.editor.state().copy(selectedLayerActions = null) == baseline.copy(selectedLayerActions = null) && f.editor.activeTransformCompletion() == null)
    }
    val final = ImageIoAdmission.ledger.snapshot()
    check(final.reservedBytes == initial.reservedBytes && final.reservations == initial.reservations)
    return "Inspector controller real JNI: retained accepted/applied state and observer replacement; bounded H/V successor " +
        "shares the command/autosave fence; stale field finish cannot finish its successor; early chain drain; asymmetric " +
        "pixels and two Undo/Redo segments; H/H no extra segment; cancel, confirmed failure, detached drain and close. " +
        "No View recreation, pointer, physical-input or performance claim."
}

private class InspectorControllerFixture(private val instrumentation: Instrumentation) : AutoCloseable {
    val editor = NativeEditor(8, false)
    val source = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, 0, -1)
    val root = editor.importPixels(2, 2, ByteBuffer.allocateDirect(source.size).apply { put(source); rewind() })
    private val callbacks = ConcurrentLinkedQueue<() -> Unit>()
    val delivered = mutableListOf<TransformController.Snapshot>()
    val controller = TransformController(editor, instrumentation.targetContext, { callbacks.add(it) }, { delivered.add(it) })
    fun begin() = checkNotNull(controller.begin(root, TransformTarget.LAYER, 10, 20, 30, fromFields = true))
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        error(message)
    }
    fun terminal() = waitFor("Inspector controller terminal did not arrive") {
        controller.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING
    }
    fun main() = instrumentation.runOnMainSync { while (true) (callbacks.poll() ?: break).invoke() }
    fun pixels(box: Transform64): ByteArray = checkNotNull(editor.captureScene()).use { scene ->
        val output = ByteBuffer.allocateDirect(16)
        scene.draw(2, 2, Viewport(box.x, box.y, box.width, box.height), output)
        ByteArray(16).also { output.rewind(); output.get(it) }
    }
    override fun close() {
        editor.committed = null
        controller.close()
        controller.awaitClosed()
        try { main() } finally { editor.close() }
    }
}
