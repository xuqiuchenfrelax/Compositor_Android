package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

internal fun duplicateControllerSmoke(instrumentation: Instrumentation): String {
    val baselineClaims = ImageIoAdmission.ledger.snapshot()
    val editor = NativeEditor(16, false)
    val callbacks = ConcurrentLinkedQueue<() -> Unit>()
    val controller = TransformController(editor, instrumentation.targetContext, { callbacks.add(it) }, {})
    fun awaitTerminal() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (controller.snapshot()?.phase != TransformController.Phase.TERMINAL_WAITING && System.nanoTime() < end)
            Thread.sleep(5)
        check(controller.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING)
        instrumentation.runOnMainSync { while (true) (callbacks.poll() ?: break).invoke() }
    }
    try {
        val source = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(70, 0, 0, -1)); rewind()
        })
        editor.selectLayer(source)
        val baseline = editor.state()
        fun begin(epoch: Long) = checkNotNull(controller.begin(source, TransformTarget.LAYER,
            1, 1, epoch, press = MovePressInput(MovePoint(.5, .5)), duplicateAtDown = true))

        val click = begin(1)
        controller.finish(click)
        awaitTerminal()
        check(click.finished.get(10, TimeUnit.SECONDS).committed)
        check(editor.state().layers.size == baseline.layers.size)

        val drag = begin(2)
        check(controller.firstDrag(drag))
        check(!controller.update(drag, Transform64(x = Double.NaN)))
        controller.finish(drag)
        awaitTerminal()
        val committed = drag.finished.get(10, TimeUnit.SECONDS)
        check(committed.committed && !committed.cancelled && committed.result?.changed == true)
        check(editor.state().layers.size == baseline.layers.size + 1)
        check(editor.state().layers.first { it.id == source }.transform == baseline.layers.single().transform)
        editor.undo(false)
        check(editor.state().layers.size == baseline.layers.size)

        val cancelled = begin(3)
        check(controller.firstDrag(cancelled))
        controller.cancel(cancelled)
        awaitTerminal()
        check(cancelled.finished.get(10, TimeUnit.SECONDS).cancelled)
        check(editor.state().layers.size == baseline.layers.size)

        editor.setSampling(source, 2)
        editor.createMask(source, true)
        editor.linkMask(source, false)
        editor.setMaskPlacement(source, Transform64(0.0, 0.0, 1.0, 1.0), 0)
        editor.selectLayers(listOf(source), source, true)
        val maskOwner = checkNotNull(controller.begin(source, TransformTarget.UNLINKED_MASK,
            1, 1, 4, press = MovePressInput(MovePoint(.5, .5)), duplicateAtDown = true))
        check(controller.firstDrag(maskOwner))
        check(!controller.update(maskOwner, Transform64(x = Double.NaN)))
        controller.finish(maskOwner)
        awaitTerminal()
        check(maskOwner.finished.get(10, TimeUnit.SECONDS).committed)
        val copy = editor.selection().primary
        check(copy != source && !editor.selection().mask)
        check(editor.state().layers.first { it.id == source }.sampling == 2)
        check(editor.state().layers.first { it.id == copy }.sampling == 0) {
            "Duplicate discarded the mask sampling captured at DOWN"
        }
        return "Controller duplicate: click, first drag, queued terminal, cancel and mask sampling carried to copied owner."
    } finally {
        controller.snapshot()?.owner?.let { owner ->
            if (controller.snapshot()?.phase != TransformController.Phase.TERMINAL_WAITING)
                controller.cancel(owner)
            awaitTerminal()
        }
        controller.close(); editor.close()
        val final = ImageIoAdmission.ledger.snapshot()
        check(final.reservedBytes == baselineClaims.reservedBytes &&
            final.reservations == baselineClaims.reservations)
    }
}
