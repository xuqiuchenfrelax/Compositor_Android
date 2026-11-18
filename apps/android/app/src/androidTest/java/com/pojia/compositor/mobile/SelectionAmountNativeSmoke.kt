package com.pojia.compositor.mobile

import java.nio.ByteBuffer

/** Runs the shipping JNI bridge and native document, without relying on UI string checks. */
internal fun selectionAmountNativeSmoke(): String {
    val side = 32
    val rect = PixelSelectionRect(8.0, 8.0, 16.0, 16.0)
    val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
    fun image(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(side * side * 4)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun alpha(bytes: ByteArray, x: Int, y: Int) = bytes[(y * side + x) * 4 + 3].toInt() and 255
    fun empty(editor: NativeEditor) {
        val bounds = editor.pixelSelection()
        val regions = editor.pixelSelectionRegions()
        check(bounds.active && bounds.width == 0.0 && bounds.height == 0.0) { "Contract deselected the canvas" }
        check(regions.active && regions.epoch == bounds.epoch && regions.generation == bounds.generation) {
            "Empty selection readback disagrees across the JNI records"
        }
    }
    NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
        try {
            val original = ByteArray(side * side * 4).also { bytes ->
                repeat(side * side) { pixel ->
                    bytes[4 * pixel] = -1
                    bytes[4 * pixel + 2] = if (pixel % side < side / 2) 0 else -1
                    bytes[4 * pixel + 3] = -1
                }
            }
            val layer = editor.importPixels(side, side,
                ByteBuffer.allocateDirect(original.size).apply { put(original); rewind() })
            editor.setPixelSelection(rect)
            editor.modifyPixelSelection(PixelSelectionAmountOperation.CONTRACT, 500)
            empty(editor)
            val before = image(editor)
            val revision = editor.currentRevision()
            val callbacks = mutableListOf<Long>()
            editor.committed = { callbacks += it }
            editor.fillPixelSelection(0xff00ff00.toInt(), layer)
            editor.fillPixelSelection(0, layer)
            check(image(editor).contentEquals(before) && editor.currentRevision() == revision)
            check(runCatching { editor.fillPixelSelection(0, Long.MAX_VALUE) }.isFailure) {
                "Empty selection bypassed target validation"
            }
            val brush = StrokeSettings(diameter = 32f, hardness = 1f, red = 0f, green = 1f, blue = 0f)
            for (mode in 0..2) {
                if (mode == 0) editor.startStroke(layer, brush)
                else editor.startCloneStroke(layer, brush, -16.0, 0.0, mode == 2)
                editor.samples(doubleArrayOf(24.5, 16.5, 1.0, 0.0))
                check(image(editor).contentEquals(before)) { "Empty selection painted a stroke preview in mode $mode" }
                editor.endStroke(false)
                check(image(editor).contentEquals(before) && editor.currentRevision() == revision)
                empty(editor)
            }
            check(callbacks.isEmpty()) { "No-op edits emitted commits: $callbacks" }
            editor.setPixelSelection(rect)
            editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 8)
            editor.fillPixelSelection(0, layer)
            val cut = image(editor)
            check(alpha(cut, 7, 16) in 1..254 && alpha(cut, 8, 16) in 1 until alpha(cut, 7, 16))
            check(alpha(cut, 16, 16) < alpha(cut, 8, 16))
            editor.undo(false); check(image(editor).contentEquals(before))
            editor.undo(true); check(image(editor).contentEquals(cut))
            editor.undo(false)
            editor.setPixelSelection(rect)
            editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 3)
            val firstFeather = editor.pixelSelection()
            editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 4)
            check(editor.pixelSelection().epoch == firstFeather.epoch + 1)
            editor.fillPixelSelection(0, layer)
            val additive = image(editor)
            editor.undo(false)
            editor.setPixelSelection(rect)
            editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 7)
            editor.fillPixelSelection(0, layer)
            check(image(editor).contentEquals(additive)) { "Feather 3 + 4 must equal 7" }
            editor.undo(false)
            editor.setPixelSelection(rect)
            editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 5)
            editor.fillPixelSelection(0, layer)
            check(!image(editor).contentEquals(additive)) { "Feather retained legacy quadratic accumulation" }
            editor.undo(false)
            editor.setPixelSelection(rect)
            editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 250)
            val capped = editor.pixelSelection()
            editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 1)
            check(editor.pixelSelection() == capped) { "Capped feather changed the selection stamp" }
            editor.setPixelSelection(rect)
            editor.fillPixelSelection(0, layer)
            val hard = image(editor)
            repeat(side * side) { pixel ->
                val x = pixel % side; val y = pixel / side
                check(alpha(hard, x, y) == if (x in 8..23 && y in 8..23) 0 else 255) {
                    "Identical geometry replacement retained soft coverage at $x,$y"
                }
            }
            editor.setPixelSelection(rect)
            editor.modifyPixelSelection(PixelSelectionAmountOperation.CONTRACT, 500)
            editor.combinePixelSelection(PixelSelectionRect(2.0, 3.0, 4.0, 5.0),
                PixelSelectionShape.RECTANGLE, PixelSelectionOperation.ADD)
            editor.fillPixelSelection(0xff00ff00.toInt(), layer)
            val added = image(editor)
            check((added[(3 * side + 2) * 4 + 1].toInt() and 255) == 255)
            check(added[(2 * side + 2) * 4 + 1].toInt() == 0)
            editor.committed = null
        } finally { editor.endStroke(true) }
    }
    check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0) {
        "Selection amount JNI smoke leaked reservations: ${ledger.snapshot()}"
    }
    return "selection amount JNI passed: active-empty readback, validated no-op Fill/Cut/Brush/Clone, soft Cut Undo/Redo, additive 3+4=7 not 5, capped no-op, hard replacement, Add recovery"
}
