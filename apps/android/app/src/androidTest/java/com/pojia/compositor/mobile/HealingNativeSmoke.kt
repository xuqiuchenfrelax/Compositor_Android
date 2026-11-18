package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer

/** The shipping JNI, budget ledger and history operate on independent damaged-image documents. */
internal fun healingNativeSmoke(instrumentation: Instrumentation): String {
    val side = 64
    val seed = 0xfedcba98L
    val tip = StrokeSettings(diameter = 12f, hardness = 1f, opacity = 1f)
    val input = ByteArray(side * side * 4).also { bytes ->
        repeat(side * side) { p ->
            val x = p % side; val y = p / side
            val damaged = x in 26..37 && y in 26..37
            bytes[p * 4] = (if (damaged) 255 else x * 3).toByte()
            bytes[p * 4 + 1] = (if (damaged) 255 else y * 3).toByte()
            bytes[p * 4 + 2] = (if (damaged) 255 else (x + y) * 2).toByte()
            bytes[p * 4 + 3] = 255.toByte()
        }
    }
    fun image(editor: NativeEditor): ByteArray {
        val out = ByteBuffer.allocateDirect(input.size)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), out))
        return ByteArray(out.capacity()).also { out.rewind(); out.get(it) }
    }
    fun claims(editor: NativeEditor, ledger: ResourceBudgetLedger) {
        val live = ledger.snapshot()
        check(live.residentBytes == 0L && live.reservations == 1 && live.reservedBytes == editor.installedDocumentBytes()) {
            "Healing leaked a working claim: $live"
        }
    }
    fun fixture(action: (NativeEditor, Long, ResourceBudgetLedger) -> Unit) {
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            val id = editor.importPixels(side, side, ByteBuffer.allocateDirect(input.size).apply { put(input); rewind() })
            check(image(editor).contentEquals(input))
            try { action(editor, id, ledger) } finally { editor.endStroke(true) }
        }
        check(ledger.snapshot().reservations == 0 && ledger.snapshot().reservedBytes == 0L)
    }
    fun samples(editor: NativeEditor, pressure: Double = 1.0) = editor.samples(
        doubleArrayOf(22.0, 32.0, pressure, 0.0, 42.0, 32.0, pressure, .1))
    fun stroke(editor: NativeEditor, id: Long, mode: HealingMode, pressure: Double = 1.0,
        settings: StrokeSettings = tip) {
        editor.startHealingStroke(id, settings, mode, seed)
        samples(editor, pressure); editor.endStroke(false)
    }
    for (mode in HealingMode.entries) {
        fixture { editor, id, ledger ->
            val before = image(editor); val state = editor.state()
            val callbacks = mutableListOf<Long>(); editor.committed = { callbacks.add(it) }
            editor.startHealingStroke(id, tip, mode, seed)
            samples(editor)
            check(editor.currentRevision() == state.revision && callbacks.isEmpty())
            check(!image(editor).contentEquals(before)) { "$mode did not publish its dark coverage preview" }
            editor.endStroke(true)
            check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && image(editor).contentEquals(before) && callbacks.isEmpty())
            claims(editor, ledger)
            stroke(editor, id, mode)
            val after = image(editor)
            val center = (32 * side + 32) * 4
            check((0..2).any { after[center + it] != before[center + it] }) { "$mode left the damage unchanged" }
            repeat(side * side) { p ->
                if (p / side !in 26..37) repeat(4) { c -> check(after[p * 4 + c] == before[p * 4 + c]) }
            }
            check(editor.currentRevision() == state.revision + 1 && callbacks == listOf(state.revision + 1))
            claims(editor, ledger)
            editor.undo(false); check(image(editor).contentEquals(before)); claims(editor, ledger)
            editor.undo(true); check(image(editor).contentEquals(after)); claims(editor, ledger)
            check(callbacks == listOf(state.revision + 1, state.revision + 2, state.revision + 3))
            val file = File(instrumentation.targetContext.cacheDir, "healing-${mode.name}-${System.nanoTime()}.checkpoint")
            try {
                val saved = editor.save(file)
                NativeEditor.load(file).use { reopened ->
                    check(reopened.currentRevision() == saved && image(reopened).contentEquals(after))
                    check(!reopened.state().undo && !reopened.state().redo)
                }
            } finally { file.delete() }
            editor.committed = null
        }
        var reference: ByteArray? = null
        for (pressure in listOf(1.0, .25, 0.0)) fixture { editor, id, ledger ->
            stroke(editor, id, mode, pressure, tip.copy(pressureSize = true, pressureOpacity = true,
                red = pressure.toFloat(), green = .5f, blue = 1f))
            val after = image(editor)
            reference?.let { check(it.contentEquals(after)) { "$mode pressure or foreground affected healing" } }
                ?: run { reference = after }
            claims(editor, ledger)
        }
        fixture { editor, id, ledger ->
            editor.setPixelSelection(PixelSelectionRect(28.0, 28.0, 8.0, 8.0))
            val before = image(editor)
            stroke(editor, id, mode)
            val after = image(editor)
            check(!after.contentEquals(before))
            repeat(side * side) { p ->
                if (p % side !in 28..35 || p / side !in 28..35)
                    repeat(4) { c -> check(after[p * 4 + c] == before[p * 4 + c]) { "$mode escaped selection" } }
            }
            claims(editor, ledger)
        }
        fixture { editor, id, ledger ->
            val before = image(editor); val state = editor.state()
            check(runCatching { editor.startHealingStroke(id, tip, mode, seed, transientLimitBytes = 1) }.isFailure)
            editor.endStroke(true)
            check(editor.state() == state && image(editor).contentEquals(before)); claims(editor, ledger)
            stroke(editor, id, mode); claims(editor, ledger)
            editor.createMask(id, true); editor.selectLayers(listOf(id), id, mask = true)
            val masked = editor.state(); val maskedPixels = image(editor)
            val failure = runCatching { editor.startHealingStroke(id, tip, mode, seed) }.exceptionOrNull()
            check(failure is UiNoticeException && failure.notice.key == UiNoticeKey.HEALING_PIXELS_ONLY)
            check(editor.state() == masked && image(editor).contentEquals(maskedPixels)); claims(editor, ledger)
        }
    }
    check(newHealingSeed() in 0..0xffffffffL)
    return "Three real JNI Healing modes passed preview/cancel, damaged-center and untouched-pixel witnesses, fixed UInt32 seed repeatability across pressure/foreground, one callback and Undo/Redo, cold checkpoint reopen, selection clipping, one-byte admission refusal/retry, mask rejection and ledger release. Isolated RGBA8 documents; no rich-format, transformed-target or sustained-performance claim."
}
