package com.pojia.compositor.mobile

import java.nio.ByteBuffer

/** Real preview/full-resolution worker tickets and the production P54 owner, without a UI. */
internal fun distortSessionCommitSmoke(): String {
    val editor = NativeEditor(32, addDefaultLayers = false)
    var session: NativeDistortSession? = null
    try {
        val source = ByteBuffer.allocateDirect(32 * 32 * 4)
        repeat(32) { y -> repeat(32) { x ->
            source.put((x * 7).toByte()).put((y * 7).toByte()).put(60.toByte()).put(255.toByte())
        } }
        source.rewind()
        editor.importPixels(32, 32, source)
        fun pixels(): ByteArray {
            val output = ByteBuffer.allocateDirect(32 * 32 * 4)
            check(editor.draw(32, 32, 0.0, 0.0, 32.0, 32.0, output))
            return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
        }
        val before = pixels()
        val revision = editor.currentRevision()
        var notifications = 0
        editor.committed = { notifications++ }
        val worker = editor.beginDistortSession(editor.state().document.activeLayerId)
        session = worker
        check(worker.submitQuad(doubleArrayOf(4.0, 4.0, 32.0, 0.0, 32.0, 32.0, 0.0, 32.0)) == 0)
        check(worker.wait(10_000) == NativeDistortSessionBridge.WAIT_READY)
        check(worker.publish() == 0)
        val previewTicket = worker.ticket()
        val draftWire = NativeDistortBridge.record(NativeDistortBridge.DRAFT_BYTES)
        val ticketWire = NativeDistortBridge.record(NativeDistortBridge.TICKET_BYTES)
        val planWire = NativeDistortBridge.record(NativeDistortBridge.PLAN_BYTES)
        NativeDistortBridge.putDraft(draftWire, worker.draft)
        NativeDistortBridge.putTicket(ticketWire, previewTicket)
        val handleField = NativeEditor::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val previewStatus = editor.serialized {
            NativeDistortBridge.prepare(handleField.getLong(editor), draftWire, ticketWire, planWire)
        }
        check(previewStatus == 4 && (0 until planWire.capacity()).all { planWire.get(it) == 0.toByte() }) {
            "Even an unscaled preview-only ticket must reject commit preparation"
        }
        check(runCatching { worker.apply() }.isFailure)
        check(editor.currentRevision() == revision && notifications == 0)
        // A rejected new shape must not replace the accepted quad used by Apply.
        check(worker.submitQuad(doubleArrayOf(0.0, 0.0, 32.0, 32.0, 32.0, 0.0, 0.0, 32.0)) != 0)
        check(worker.submitFinalQuad() == 0)
        check(worker.ticket().generation > previewTicket.generation)
        check(worker.wait(10_000) == NativeDistortSessionBridge.WAIT_READY)
        check(worker.publish() == 0)
        val finalPixels = worker.snapshot().use { scene ->
            val output = ByteBuffer.allocateDirect(32 * 32 * 4)
            scene.draw(32, 32, Viewport(0.0, 0.0, 32.0, 32.0), output)
            ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
        }
        check(!finalPixels.contentEquals(before))
        check(worker.apply() == 0)
        check(worker.apply() == 0) { "Repeated Apply did not recover its completed receipt" }
        check(editor.currentRevision() == revision + 1 && notifications == 1) {
            "Distort published more than once or omitted its committed notification"
        }
        worker.seal()
        session = null
        check(pixels().contentEquals(finalPixels)) { "P54 committed pixels differ from the full-resolution scene" }
        editor.undo(false)
        check(pixels().contentEquals(before)) { "Distort Undo changed source pixels" }
        editor.undo(true)
        check(pixels().contentEquals(finalPixels)) { "Distort Redo changed final pixels" }
        return "preview-only prepare rejected; accepted-quad final ticket; full scene -> P54 exact pixels; repeated Apply once; committed notification; Undo/Redo exact"
    } finally {
        session?.close()
        editor.close()
    }
}
