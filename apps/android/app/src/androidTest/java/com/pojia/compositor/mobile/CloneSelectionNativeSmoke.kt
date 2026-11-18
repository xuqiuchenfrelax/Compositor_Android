package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer

/** Selection predicates are independently evaluated at document pixel centers. */
internal fun cloneSelectionNativeSmoke(instrumentation: Instrumentation): String {
    val side = 32
    val green = byteArrayOf(0, -1, 0, -1)
    val original = ByteArray(side * side * 4).also { bytes ->
        repeat(side * side) { pixel ->
            val color = if (pixel % side < 16) green else byteArrayOf(0, 0, -1, -1)
            repeat(4) { bytes[4 * pixel + it] = color[it] }
        }
    }
    fun rect(x: Double, y: Double, left: Double, top: Double, width: Double, height: Double) =
        x >= left && y >= top && x < left + width && y < top + height
    fun ellipse(x: Double, y: Double): Boolean {
        val dx = (x - 24.0) / 4.0; val dy = (y - 16.0) / 4.0
        return dx * dx + dy * dy <= 1.0
    }
    fun triangle(x: Double, y: Double, left: Double, top: Double, width: Double, height: Double): Boolean {
        if (y < top || y >= top + height) return false
        val inset = (y - top) * width / (2.0 * height)
        return x >= left + inset && x < left + width - inset
    }
    fun trianglePoints(left: Double, top: Double, width: Double, height: Double) = listOf(
        PixelSelectionPoint(left, top), PixelSelectionPoint(left + width, top),
        PixelSelectionPoint(left + width / 2, top + height))
    data class Case(val name: String, val configure: (NativeEditor) -> Unit,
        val contains: (Double, Double) -> Boolean, val translated: Boolean = false)
    val rectangle = PixelSelectionRect(20.0, 12.0, 8.0, 8.0)
    val cases = mutableListOf(
        Case("rectangle", { it.setPixelSelection(rectangle) }, { x, y -> rect(x, y, 20.0, 12.0, 8.0, 8.0) }),
        Case("ellipse", { it.setPixelSelection(rectangle, PixelSelectionShape.ELLIPSE) }, ::ellipse),
        Case("triangle", { it.combinePixelSelectionPolygon(trianglePoints(20.0, 12.0, 8.0, 8.0), PixelSelectionOperation.REPLACE) },
            { x, y -> triangle(x, y, 20.0, 12.0, 8.0, 8.0) }),
        Case("ring-island", { editor ->
            editor.setPixelSelection(rectangle)
            editor.combinePixelSelection(PixelSelectionRect(22.0, 14.0, 4.0, 4.0), PixelSelectionShape.RECTANGLE, PixelSelectionOperation.SUBTRACT)
            editor.combinePixelSelection(PixelSelectionRect(29.0, 12.0, 2.0, 3.0), PixelSelectionShape.RECTANGLE, PixelSelectionOperation.ADD)
        }, { x, y -> rect(x, y, 20.0, 12.0, 8.0, 8.0) && !rect(x, y, 22.0, 14.0, 4.0, 4.0) || rect(x, y, 29.0, 12.0, 2.0, 3.0) }),
        Case("polygon-hole-island", { editor ->
            editor.combinePixelSelectionPolygon(listOf(PixelSelectionPoint(20.0, 12.0), PixelSelectionPoint(28.0, 12.0),
                PixelSelectionPoint(28.0, 20.0), PixelSelectionPoint(20.0, 20.0)), PixelSelectionOperation.REPLACE)
            editor.combinePixelSelectionPolygon(trianglePoints(22.0, 14.0, 4.0, 4.0), PixelSelectionOperation.SUBTRACT)
            editor.combinePixelSelectionPolygon(trianglePoints(29.0, 12.0, 2.0, 3.0), PixelSelectionOperation.ADD)
        }, { x, y -> rect(x, y, 20.0, 12.0, 8.0, 8.0) && !triangle(x, y, 22.0, 14.0, 4.0, 4.0) ||
            triangle(x, y, 29.0, 12.0, 2.0, 3.0) }),
        Case("translated-rectangle", { it.setPixelSelection(rectangle) },
            { x, y -> rect(x, y, 20.0, 12.0, 8.0, 8.0) }, translated = true),
    )
    for (operation in PixelSelectionOperation.REPLACE..PixelSelectionOperation.INTERSECT) cases += Case("boolean-$operation", { editor ->
        editor.setPixelSelection(PixelSelectionRect(20.0, 12.0, 6.0, 8.0))
        val incoming = PixelSelectionRect(24.0, 16.0, 6.0, 8.0)
        if (operation == PixelSelectionOperation.REPLACE) editor.setPixelSelection(incoming)
        else editor.combinePixelSelection(incoming, PixelSelectionShape.RECTANGLE, operation)
    }, { x, y ->
        val a = rect(x, y, 20.0, 12.0, 6.0, 8.0); val b = rect(x, y, 24.0, 16.0, 6.0, 8.0)
        when (operation) {
            PixelSelectionOperation.REPLACE -> b
            PixelSelectionOperation.ADD -> a || b
            PixelSelectionOperation.SUBTRACT -> a && !b
            else -> a && b
        }
    })
    fun image(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(original.size)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun claims(editor: NativeEditor, ledger: ResourceBudgetLedger, label: String) {
        val budget = ledger.snapshot()
        check(budget.residentBytes == 0L && budget.reservations == 1 && budget.reservedBytes == editor.installedDocumentBytes()) {
            "$label leaked a selection/stroke reservation: $budget"
        }
    }
    val tip = StrokeSettings(diameter = 32f, hardness = 1f)
    for (case in cases) for (all in listOf(false, true)) {
        val label = "${case.name}, all-visible=$all"
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            try {
                val id = editor.importPixels(side, side, ByteBuffer.allocateDirect(original.size).apply { put(original); rewind() })
                if (case.translated) editor.setTransform(id, editor.state().layers.single().transform.copy(x = 2.0, y = 1.0))
                case.configure(editor)
                check(editor.pixelSelection().active)
                val state = editor.state()
                val before = image(editor)
                val regions = editor.pixelSelectionRegions().regions
                val callbacks = mutableListOf<Long>()
                editor.committed = { callbacks.add(it) }
                fun start(limit: Long = 64L * 1024 * 1024) = editor.startCloneStroke(id, tip, -16.0, 0.0, all, limit)
                fun samples() = editor.samples(doubleArrayOf(24.5, 16.5, 1.0, 0.0))

                // A selection snapshot also consumes bounded native stroke storage.
                check(runCatching { start(1) }.isFailure) { "$label accepted a one-byte stroke allowance" }
                check(editor.state() == state && image(editor).contentEquals(before))
                check(editor.pixelSelectionRegions().regions == regions && callbacks.isEmpty())
                claims(editor, ledger, "$label refused begin")

                start(); samples()
                val heldSelection = editor.pixelSelection()
                check(runCatching { editor.setPixelSelection(PixelSelectionRect(0.0, 0.0, 1.0, 1.0)) }.isFailure) {
                    "$label allowed selection replacement during the stroke"
                }
                check(editor.pixelSelection() == heldSelection && editor.currentRevision() == state.revision)
                editor.endStroke(true)
                check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && image(editor).contentEquals(before) && callbacks.isEmpty())
                check(editor.pixelSelectionRegions().regions == regions)
                claims(editor, ledger, "$label cancel")

                start(); samples(); editor.endStroke(false)
                val copied = image(editor)
                var selected = 0
                repeat(side * side) { pixel ->
                    val x = pixel % side; val y = pixel / side
                    val chosen = case.contains(x + .5, y + .5)
                    if (chosen) {
                        ++selected
                        check(!case.contains(x + .5 - 16.0, y + .5)) { "$label source unexpectedly lies in target selection" }
                    }
                    repeat(4) { channel ->
                        val expected = if (chosen) green[channel] else before[4 * pixel + channel]
                        check(copied[4 * pixel + channel] == expected) {
                            "$label pixel($x,$y) channel$channel expected ${expected.toInt() and 255}, got ${copied[4 * pixel + channel].toInt() and 255}; selected=$chosen"
                        }
                    }
                }
                check(selected > 0 && callbacks == listOf(state.revision + 1))
                claims(editor, ledger, "$label commit")
                editor.undo(false)
                check(image(editor).contentEquals(before)) { "$label Undo left clipped pixels" }
                claims(editor, ledger, "$label Undo")
                editor.undo(true)
                check(image(editor).contentEquals(copied)) { "$label Redo changed clipped pixels" }
                check(callbacks == listOf(state.revision + 1, state.revision + 2, state.revision + 3))
                claims(editor, ledger, "$label Redo")
                if (case.name == "ring-island") {
                    val file = File(instrumentation.targetContext.cacheDir, "clone-selection-$all-${System.nanoTime()}.checkpoint")
                    try {
                        val saved = editor.save(file)
                        NativeEditor.load(file).use { reopened ->
                            check(reopened.currentRevision() == saved && image(reopened).contentEquals(copied))
                            check(!reopened.pixelSelection().active) { "Session selection unexpectedly entered the checkpoint" }
                        }
                    } finally { file.delete() }
                }
                editor.committed = null
            } finally { editor.endStroke(true) }
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0) { "$label close leaked claims" }
    }
    for (operation in listOf(PixelSelectionOperation.SUBTRACT, PixelSelectionOperation.INTERSECT)) {
        for (all in listOf(false, true)) {
            val label = "Active Boolean-empty operation=$operation all-visible=$all"
            val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
            NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
                try {
                    val id = editor.importPixels(side, side, ByteBuffer.allocateDirect(original.size).apply { put(original); rewind() })
                    val first = PixelSelectionRect(20.0, 12.0, 2.0, 2.0)
                    editor.setPixelSelection(first)
                    editor.combinePixelSelection(if (operation == PixelSelectionOperation.SUBTRACT) first
                        else PixelSelectionRect(28.0, 20.0, 2.0, 2.0), PixelSelectionShape.RECTANGLE, operation)
                    check(editor.pixelSelection().active && editor.pixelSelectionRegions().regions.size == 2) {
                        "$label fixture cleared the selection instead of preserving empty Boolean geometry"
                    }
                    val state = editor.state()
                    val before = image(editor)
                    val callbacks = mutableListOf<Long>()
                    editor.committed = { callbacks.add(it) }
                    editor.startCloneStroke(id, tip, -16.0, 0.0, all)
                    editor.samples(doubleArrayOf(24.5, 16.5, 1.0, 0.0))
                    editor.endStroke(false)
                    check(image(editor).contentEquals(before) && editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && callbacks.isEmpty()) {
                        "$label changed pixels, history flags, revision, or emitted a committed callback"
                    }
                    check(!editor.hasActiveStrokes && editor.pendingEditCount == 0)
                    claims(editor, ledger, label)
                    // Import was the only content edit. One Undo must remove it,
                    // proving the empty clone did not insert an invisible history entry.
                    editor.undo(false)
                    check(editor.state().layers.isEmpty() && image(editor).all { it == 0.toByte() }) {
                        "$label inserted an extra history entry"
                    }
                    editor.undo(true)
                    check(image(editor).contentEquals(before) && editor.state().layers == state.layers)
                    check(callbacks == listOf(state.revision + 1, state.revision + 2))
                    claims(editor, ledger, "$label history probe")

                    callbacks.clear()
                    editor.setPixelSelection(null)
                    check(!editor.pixelSelection().active)
                    val beforeUnrestricted = editor.currentRevision()
                    editor.startCloneStroke(id, tip, -16.0, 0.0, all)
                    editor.samples(doubleArrayOf(24.5, 16.5, 1.0, 0.0))
                    editor.endStroke(false)
                    val unrestricted = image(editor)
                    check(!unrestricted.contentEquals(before)) { "$label still clipped after selection was cleared" }
                    repeat(4) { channel ->
                        check(unrestricted[(16 * side + 24) * 4 + channel] == green[channel])
                    }
                    check(callbacks == listOf(beforeUnrestricted + 1) && editor.currentRevision() == beforeUnrestricted + 1)
                    claims(editor, ledger, "$label cleared-selection retry")
                    editor.committed = null
                } finally { editor.endStroke(true) }
            }
            check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
        }
    }
    return "Real JNI selection-clipped Clone Stamp passed 20 current/all-visible cases: rectangle, ellipse, triangle, compound holes/islands, all four Boolean modes and an integer-translated layer. Every document pixel matched an independent center predicate; source pixels outside the target selection remained readable. Four additional active Boolean-empty cases preserved all pixels/revision/callbacks and added no history entry; clearing the selection restored ordinary cloning. Mid-stroke selection replacement refused; cancel and one Undo/Redo preserved exact pixels/callbacks, ring/island checkpoint cold reopen preserved edits without persisting session selection. One-byte admission refused unchanged and retry returned temporary claims; close released all claims. No UI, rotated/scaled target, feathered selection or sustained-performance claim."
}
