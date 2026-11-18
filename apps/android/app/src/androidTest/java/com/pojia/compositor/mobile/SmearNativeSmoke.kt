package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer

/** Real JNI carried-color Smudge and forward-warp Liquify, on isolated documents. */
internal fun smearNativeSmoke(instrumentation: Instrumentation): String {
    val side = 64
    val tip = StrokeSettings(diameter = 8f, hardness = 1f, opacity = 1f)
    fun pixels(transparent: Boolean = false) = ByteArray(side * side * 4).also { bytes ->
        repeat(side * side) { p ->
            val x = p % side
            if (!transparent || x >= 32) {
                bytes[p * 4] = (x * 4).toByte()
                bytes[p * 4 + 2] = (255 - x * 4).toByte()
                bytes[p * 4 + 3] = 255.toByte()
            }
        }
    }
    fun image(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(side * side * 4)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun pixel(bytes: ByteArray, x: Int, y: Int) =
        (0..3).map { bytes[(y * side + x) * 4 + it].toInt() and 255 }
    fun expectPixel(bytes: ByteArray, x: Int, y: Int, expected: List<Int>, label: String) {
        check(pixel(bytes, x, y) == expected) { "$label ($x,$y): expected $expected, got ${pixel(bytes, x, y)}" }
    }
    fun claims(editor: NativeEditor, ledger: ResourceBudgetLedger, label: String) {
        val live = ledger.snapshot()
        check(live.residentBytes == 0L && live.reservations == 1 &&
            live.reservedBytes == editor.installedDocumentBytes()) { "$label leaked a temporary claim: $live" }
    }
    fun fixture(transparent: Boolean = false, action: (NativeEditor, Long, ResourceBudgetLedger) -> Unit) {
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        val input = pixels(transparent)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            val id = editor.importPixels(side, side,
                ByteBuffer.allocateDirect(input.size).apply { put(input); rewind() })
            check(image(editor).contentEquals(input))
            try { action(editor, id, ledger) }
            finally { editor.endStroke(true) }
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0) {
            "Closed Smear fixture leaked resource claims"
        }
    }
    fun samples(editor: NativeEditor, pressure: Double = 1.0) =
        editor.samples(doubleArrayOf(16.0, 32.0, pressure, 0.0, 48.0, 32.0, pressure, .1))
    fun stroke(editor: NativeEditor, id: Long, mode: SmearMode, pressure: Double = 1.0) {
        editor.startSmearStroke(id, tip, mode)
        samples(editor, pressure)
        editor.endStroke(false)
    }

    for (mode in listOf(SmearMode.SMUDGE, SmearMode.LIQUIFY)) {
        fixture { editor, id, ledger ->
            val before = image(editor)
            val state = editor.state()
            val callbacks = mutableListOf<Long>()
            editor.committed = { callbacks.add(it) }
            editor.startSmearStroke(id, tip, mode)
            editor.samples(doubleArrayOf(16.0, 32.0, 0.0, 0.0))
            check(image(editor).contentEquals(before) && editor.currentRevision() == state.revision) {
                "$mode pickup-only sample changed the document"
            }
            editor.samples(doubleArrayOf(48.0, 32.0, 0.0, .1))
            expectPixel(image(editor), 48, 32, listOf(64, 0, 191, 255), "$mode carried original x16")
            check(editor.currentRevision() == state.revision && callbacks.isEmpty())
            editor.endStroke(true)
            check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && image(editor).contentEquals(before) && callbacks.isEmpty())
            claims(editor, ledger, "$mode cancel")

            stroke(editor, id, mode)
            val after = image(editor)
            expectPixel(after, 48, 32, listOf(64, 0, 191, 255), "$mode commit carry")
            expectPixel(after, 48, 4, pixel(before, 48, 4), "$mode untouched pixel")
            check(editor.currentRevision() == state.revision + 1 && callbacks == listOf(state.revision + 1))
            claims(editor, ledger, "$mode commit")
            editor.undo(false)
            check(image(editor).contentEquals(before)) { "$mode did not undo in one step" }
            claims(editor, ledger, "$mode undo")
            editor.undo(true)
            check(image(editor).contentEquals(after)) { "$mode redo changed pixels" }
            check(callbacks == listOf(state.revision + 1, state.revision + 2, state.revision + 3))
            claims(editor, ledger, "$mode redo")
            val checkpoint = File(instrumentation.targetContext.cacheDir, "smear-${mode.name}-${System.nanoTime()}.checkpoint")
            try {
                val saved = editor.save(checkpoint)
                NativeEditor.load(checkpoint).use { reopened ->
                    check(reopened.currentRevision() == saved && image(reopened).contentEquals(after)) {
                        "$mode checkpoint cold reopen changed pixels"
                    }
                    check(!reopened.state().undo && !reopened.state().redo)
                }
            } finally { checkpoint.delete() }
            editor.committed = null
        }
        fixture(transparent = true) { editor, id, ledger ->
            stroke(editor, id, mode)
            expectPixel(image(editor), 48, 32, listOf(0, 0, 0, 0), "$mode transparent replacement")
            expectPixel(image(editor), 48, 4, listOf(192, 0, 63, 255), "$mode untouched opaque pixel")
            claims(editor, ledger, "$mode transparent")
        }
        var reference: ByteArray? = null
        for (pressure in listOf(1.0, .25, 0.0)) fixture { editor, id, ledger ->
            stroke(editor, id, mode, pressure)
            val after = image(editor)
            expectPixel(after, 48, 32, listOf(64, 0, 191, 255), "$mode pressure $pressure")
            reference?.let { check(after.contentEquals(it)) { "$mode pressure changed full RGBA output" } }
                ?: run { reference = after }
            claims(editor, ledger, "$mode pressure $pressure")
        }
        fixture { editor, id, ledger ->
            editor.setPixelSelection(PixelSelectionRect(38.0, 24.0, 18.0, 16.0))
            val before = image(editor)
            stroke(editor, id, mode)
            val after = image(editor)
            expectPixel(after, 48, 32, listOf(64, 0, 191, 255), "$mode source outside selection")
            repeat(side * side) { p ->
                val x = p % side; val y = p / side
                if (x !in 38..55 || y !in 24..39) repeat(4) { channel ->
                    check(before[p * 4 + channel] == after[p * 4 + channel]) {
                        "$mode changed unselected pixel ($x,$y) channel $channel"
                    }
                }
            }
            claims(editor, ledger, "$mode selection")
        }
        fixture { editor, id, ledger ->
            val state = editor.state()
            val before = image(editor)
            val callbacks = mutableListOf<Long>()
            editor.committed = { callbacks.add(it) }
            val baseline = ledger.snapshot()
            check(runCatching { editor.startSmearStroke(id, tip, mode, transientLimitBytes = 1) }.isFailure) {
                "$mode accepted a one-byte working-copy allowance"
            }
            editor.endStroke(true)
            check(editor.state() == state && image(editor).contentEquals(before) && callbacks.isEmpty())
            claims(editor, ledger, "$mode one-byte refusal")
            check(ledger.snapshot().reservedBytes == baseline.reservedBytes)
            ledger.reserve(ledger.snapshot().availableBytes - 1, "Smear admitted-byte pressure").use {
                val occupied = ledger.snapshot()
                check(runCatching {
                    editor.startSmearStroke(id, tip, mode, transientLimitBytes = 64L * 1024 * 1024)
                }.isFailure) { "$mode expanded the ledger's one admitted byte" }
                editor.endStroke(true)
                check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && image(editor).contentEquals(before) && callbacks.isEmpty())
                val refused = ledger.snapshot()
                check(refused.reservedBytes == occupied.reservedBytes && refused.reservations == occupied.reservations)
            }
            stroke(editor, id, mode)
            check(callbacks == listOf(state.revision + 1))
            claims(editor, ledger, "$mode retry after refusal")
            editor.committed = null
        }
        fixture { editor, id, ledger ->
            editor.createMask(id, true)
            editor.selectLayers(listOf(id), id, mask = true)
            val state = editor.state()
            val before = image(editor)
            check(runCatching { editor.startSmearStroke(id, tip, mode) }.isFailure) {
                "$mode accepted a mask-focused target"
            }
            editor.endStroke(true)
            check(editor.state() == state && image(editor).contentEquals(before))
            claims(editor, ledger, "$mode mask refusal")
        }
    }
    return "Real JNI Smudge and Liquify passed exact carried-color witnesses and transparent replacement, pickup no-op, cancel, one callback/Undo/Redo, cold checkpoint reopen, identical full RGBA at pressures 0/0.25/1, selection clipping with source outside selection, one-byte and ledger-only-one-byte admission refusals, retry and resource-claim release. Mask focus refused unchanged. Isolated 64px RGBA8 documents; no UI, transformed-target, Metal or sustained-performance claim."
}
