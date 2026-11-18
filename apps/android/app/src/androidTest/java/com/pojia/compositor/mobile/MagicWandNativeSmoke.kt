package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.nio.ByteBuffer

/** Real JNI render -> wand trace -> compound selection -> Fill/Cut pixel verification. */
@Suppress("UNUSED_PARAMETER")
internal fun magicWandNativeSmoke(instrumentation: Instrumentation): String {
    val side = 9
    fun ring(x: Int, y: Int) = x in 1..5 && y in 1..5 && (x == 1 || x == 5 || y == 1 || y == 5)
    fun white(x: Int, y: Int) = ring(x, y) || x == 7 && (y == 1 || y == 2 || y == 7)
    val original = ByteArray(side * side * 4).also { bytes ->
        repeat(side * side) { pixel ->
            val value = if (white(pixel % side, pixel / side)) 255.toByte() else 0.toByte()
            bytes[pixel * 4] = value; bytes[pixel * 4 + 1] = value; bytes[pixel * 4 + 2] = value
            bytes[pixel * 4 + 3] = 255.toByte()
        }
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(original.size)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), output))
        return ByteArray(original.size).also { output.rewind(); output.get(it) }
    }
    fun sameClaims(before: ResourceBudgetSnapshot, after: ResourceBudgetSnapshot, label: String) {
        check(after.reservedBytes == before.reservedBytes && after.reservations == before.reservations &&
            after.residentBytes == before.residentBytes) { "$label leaked resource reservations: $before -> $after" }
    }
    // Query the same real JNI retained receipt used by publication reconciliation,
    // without adding a production-only test getter. These fixtures have one owner.
    val handleField = NativeEditor::class.java.getDeclaredField("handle").apply { isAccessible = true }
    fun nativeClaim(editor: NativeEditor, label: String): Long = editor.serialized {
        val receipt = NativeTransformBridge.record(24)
        NativeTransformBridge.requireSuccess(NativeTransformBridge.retained(handleField.getLong(editor), receipt))
        val bytes = receipt.getLong(16)
        check(bytes > 0 && bytes == editor.installedDocumentBytes()) {
            "$label native retained bytes $bytes differ from installed claim ${editor.installedDocumentBytes()}"
        }
        check(receipt.getLong(0) == editor.currentRevision())
        bytes
    }
    fun installedClaim(editor: NativeEditor, ledger: ResourceBudgetLedger, label: String) {
        val bytes = nativeClaim(editor, label)
        val budget = ledger.snapshot()
        check(budget.reservations == 1 && budget.residentBytes == 0L && budget.reservedBytes == bytes) {
            "$label left a temporary publication lease or undercharged its installed document: $budget"
        }
    }
    fun fixture(action: (NativeEditor, Long, ResourceBudgetLedger) -> Unit) {
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            val input = ByteBuffer.allocateDirect(original.size).apply { put(original); rewind() }
            val layer = editor.importPixels(side, side, input)
            check(pixels(editor).contentEquals(original)) { "Fixture import changed exact RGBA pixels" }
            action(editor, layer, ledger)
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0) {
            "Closing the Wand fixture retained native resource claims"
        }
    }
    fun apply(editor: NativeEditor, contiguous: Boolean, operation: Int) {
        editor.applyMagicWand(1.5, 1.5, 0, 0, contiguous, operation, editor.pixelSelection(), MagicWandCancellation())
    }
    fun expectPixels(editor: NativeEditor, cut: Boolean, label: String, selected: (Int, Int) -> Boolean) {
        val actual = pixels(editor)
        repeat(side * side) { pixel ->
            val x = pixel % side; val y = pixel / side
            repeat(4) { channel ->
                val expected = if (!selected(x, y)) original[pixel * 4 + channel]
                    else if (cut) 0.toByte()
                    else if (channel == 0 || channel == 3) 255.toByte() else 0.toByte()
                check(actual[pixel * 4 + channel] == expected) {
                    "$label pixel ($x,$y) channel $channel: expected ${expected.toInt() and 255}, got ${actual[pixel * 4 + channel].toInt() and 255}"
                }
            }
        }
    }
    fun fillAndHistory(editor: NativeEditor, layer: Long, ledger: ResourceBudgetLedger, cut: Boolean,
        label: String, selected: (Int, Int) -> Boolean) {
        val before = editor.currentRevision()
        val callbacks = mutableListOf<Long>()
        editor.committed = { callbacks.add(it) }
        try {
            editor.fillPixelSelection(if (cut) 0 else 0xffff0000.toInt(), layer)
            check(editor.currentRevision() == before + 1 && callbacks == listOf(before + 1)) {
                "$label must publish one revision and exactly one committed callback"
            }
            installedClaim(editor, ledger, label)
            expectPixels(editor, cut, label, selected)
            val changed = pixels(editor)
            editor.undo(false)
            check(pixels(editor).contentEquals(original)) { "$label Undo did not restore exact source pixels" }
            check(callbacks == listOf(before + 1, before + 2)) { "$label Undo callback was lost or duplicated" }
            installedClaim(editor, ledger, "$label Undo")
            editor.undo(true)
            check(pixels(editor).contentEquals(changed)) { "$label Redo did not restore exact edited pixels" }
            check(editor.currentRevision() == before + 3 && callbacks == listOf(before + 1, before + 2, before + 3)) {
                "$label Redo callback was lost or duplicated"
            }
            installedClaim(editor, ledger, "$label Redo")
        } finally { editor.committed = null }
    }

    fixture { editor, _, ledger ->
        val before = ledger.snapshot()
        val state = editor.state()
        val selection = editor.pixelSelection()
        val contiguous = editor.magicWand(1.5, 1.5, 0, 0, true)
        check(contiguous.selectedPixels == 16L && contiguous.loops.size == 2) { "Contiguous ring lost its hole" }
        sameClaims(before, ledger.snapshot(), "Contiguous trace")
        val disconnected = editor.magicWand(1.5, 1.5, 0, 0, false)
        check(disconnected.selectedPixels == 19L && disconnected.loops.size == 4) {
            "Noncontiguous trace lost disconnected islands or the inner hole"
        }
        sameClaims(before, ledger.snapshot(), "Noncontiguous trace")
        for (radius in 0..2) {
            val all = editor.magicWand(4.5, 4.5, radius, 255, true)
            check(all.selectedPixels == (side * side).toLong() && all.loops.size == 1)
            sameClaims(before, ledger.snapshot(), "Radius $radius trace")
        }
        check(editor.state() == state && editor.pixelSelection() == selection)
        check(pixels(editor).contentEquals(original))
    }

    for (cut in listOf(false, true)) {
        fixture { editor, layer, ledger ->
            apply(editor, true, PixelSelectionOperation.REPLACE)
            check(editor.pixelSelection().active && editor.pixelSelectionRegions().regions.isNotEmpty())
            fillAndHistory(editor, layer, ledger, cut, "Contiguous ${if (cut) "Cut" else "Fill"}", ::ring)
        }
        for (operation in PixelSelectionOperation.REPLACE..PixelSelectionOperation.INTERSECT) {
            fixture { editor, layer, ledger ->
                editor.setPixelSelection(PixelSelectionRect(0.0, 0.0, 9.0, 3.0))
                apply(editor, false, operation)
                check(editor.pixelSelection().active)
                fillAndHistory(editor, layer, ledger, cut, "Operation $operation ${if (cut) "Cut" else "Fill"}") { x, y ->
                    val wand = white(x, y); val previous = y < 3
                    when (operation) {
                        PixelSelectionOperation.REPLACE -> wand
                        PixelSelectionOperation.ADD -> previous || wand
                        PixelSelectionOperation.SUBTRACT -> previous && !wand
                        PixelSelectionOperation.INTERSECT -> previous && wand
                        else -> error("Unexpected fixture operation")
                    }
                }
            }
        }
    }

    fixture { editor, layer, ledger ->
        apply(editor, true, PixelSelectionOperation.REPLACE)
        val state = editor.state()
        val selection = editor.pixelSelection()
        val callbacks = mutableListOf<Long>()
        editor.committed = { callbacks.add(it) }
        try {
            ledger.reserve(ledger.snapshot().availableBytes, "Fill admission fixture pressure").use {
                val occupied = ledger.snapshot()
                val failed = runCatching { editor.fillPixelSelection(0xffff0000.toInt(), layer) }.exceptionOrNull()
                check(failed is ResourceBudgetExceededException || failed is TransformDraftException && failed.status == 3) {
                    "Exhausted Fill should refuse before publication, got $failed"
                }
                check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && editor.pixelSelection() == selection && callbacks.isEmpty())
                check(pixels(editor).contentEquals(original)) { "Rejected Fill changed pixels" }
                sameClaims(occupied, ledger.snapshot(), "Rejected Fill")
                nativeClaim(editor, "Rejected Fill")
            }
            installedClaim(editor, ledger, "Released Fill pressure")
            editor.fillPixelSelection(0xffff0000.toInt(), layer)
            check(callbacks == listOf(state.revision + 1) && editor.currentRevision() == state.revision + 1)
            installedClaim(editor, ledger, "Fill retry")
            expectPixels(editor, false, "Fill retry", ::ring)
        } finally { editor.committed = null }
    }

    fixture { editor, _, ledger ->
        editor.setPixelSelection(PixelSelectionRect(2.0, 2.0, 2.0, 2.0))
        val before = editor.pixelSelection()
        val beforeRegions = editor.pixelSelectionRegions()
        val claims = ledger.snapshot()
        val canceled = MagicWandCancellation().also { it.cancel() }
        editor.applyMagicWand(1.5, 1.5, 0, 0, false, PixelSelectionOperation.REPLACE, before, canceled)
        check(editor.pixelSelection() == before && editor.pixelSelectionRegions() == beforeRegions)
        check(pixels(editor).contentEquals(original))
        sameClaims(claims, ledger.snapshot(), "Canceled command")

        editor.setPixelSelection(PixelSelectionRect(6.0, 6.0, 2.0, 2.0))
        val current = editor.pixelSelection()
        val failure = runCatching {
            editor.applyMagicWand(1.5, 1.5, 0, 0, false, PixelSelectionOperation.REPLACE,
                before, MagicWandCancellation())
        }.exceptionOrNull()
        check(failure is IllegalStateException && failure.message == "Selection changed before Magic Wand started")
        check(editor.pixelSelection() == current)
        sameClaims(claims, ledger.snapshot(), "Stale selection rejection")

        ledger.reserve(ledger.snapshot().availableBytes, "Wand admission fixture pressure").use {
            val occupied = ledger.snapshot()
            check(runCatching { editor.magicWand(1.5, 1.5, 0, 0, false) }.exceptionOrNull() is ResourceBudgetExceededException)
            check(editor.pixelSelection() == current)
            sameClaims(occupied, ledger.snapshot(), "Trace admission rejection")
        }
        sameClaims(claims, ledger.snapshot(), "Released trace pressure")
        check(editor.magicWand(1.5, 1.5, 0, 0, false).selectedPixels == 19L)
        sameClaims(claims, ledger.snapshot(), "Retry trace")
    }
    return "Real Android JNI Wand render/trace/readback selected the 16-pixel ring with its hole and 19 pixels across disconnected islands. Replace/Add/Subtract/Intersect each passed exact RGBA Fill/Cut, Undo/Redo, exactly-once committed callbacks and native retained receipt/installed ledger agreement; all three sample radii passed full-tolerance selection. Exhausted Fill preserved pixels/state/selection/claims and emitted no callback, then committed a successful retry after pressure release. Pre-canceled and stale commands preserved selection; temporary resource claims returned after trace, admission rejection, retry and close. No UI, mid-trace cancellation timing, JNI allocation fault or sustained-performance claim."
}
