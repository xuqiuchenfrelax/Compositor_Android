package com.pojia.compositor.mobile

import java.nio.ByteBuffer

/** Exercises soft selection clipping through the shipping JNI stroke transactions. */
internal fun selectionSoftStrokeNativeSmoke(): String {
    val side = 32
    val rect = PixelSelectionRect(20.0, 12.0, 8.0, 8.0)
    val tip = StrokeSettings(diameter = 64f, hardness = 1f, green = 1f)
    val original = ByteArray(side * side * 4).also { bytes ->
        repeat(side * side) { pixel ->
            bytes[4 * pixel + if (pixel % side < 16) 1 else 2] = -1
            bytes[4 * pixel + 3] = -1
        }
    }
    fun image(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(original.size)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), output)) {
            "Could not render the soft selection JNI fixture"
        }
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun value(bytes: ByteArray, x: Int, y: Int, channel: Int) =
        bytes[(y * side + x) * 4 + channel].toInt() and 255
    fun claims(editor: NativeEditor, ledger: ResourceBudgetLedger, label: String) {
        val budget = ledger.snapshot()
        check(budget.residentBytes == 0L && budget.reservations == 1 &&
            budget.reservedBytes == editor.installedDocumentBytes()) { "$label leaked temporary claims: $budget" }
    }
    for (shape in listOf(PixelSelectionShape.RECTANGLE, PixelSelectionShape.ELLIPSE)) {
        var brushCoverage: ByteArray? = null
        for (mode in listOf("brush", "erase", "mask", "clone-current", "clone-all")) {
            val label = "shape=$shape, mode=$mode"
            val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
            NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
                try {
                    val id = editor.importPixels(side, side,
                        ByteBuffer.allocateDirect(original.size).apply { put(original); rewind() })
                    if (mode == "mask") editor.createMask(id, true)
                    editor.setPixelSelection(rect, shape)
                    editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 4)
                    val before = image(editor)
                    val state = editor.state()
                    val selection = editor.pixelSelection()
                    val regions = editor.pixelSelectionRegions().regions
                    var observedGeneration = selection.generation
                    fun selectionUnchanged(stage: String, expectedRevision: Long) {
                        val current = editor.pixelSelection()
                        check(current.instance == selection.instance && current.epoch == selection.epoch &&
                            current.active == selection.active && current.x == selection.x && current.y == selection.y &&
                            current.width == selection.width && current.height == selection.height &&
                            current.shape == selection.shape && editor.pixelSelectionRegions().regions == regions) {
                            "$label $stage changed selection identity or geometry: before=$selection after=$current"
                        }
                        check(current.revision == expectedRevision && editor.currentRevision() == expectedRevision) {
                            "$label $stage revision expected=$expectedRevision, selection=${current.revision}, document=${editor.currentRevision()}"
                        }
                        // This is the document's scene generation, not the selection epoch.
                        // Painting previews, cancel, commit and history may advance it.
                        check(current.generation >= observedGeneration) {
                            "$label $stage scene generation regressed: $observedGeneration -> ${current.generation}"
                        }
                        observedGeneration = current.generation
                    }
                    val callbacks = mutableListOf<Long>()
                    editor.committed = { callbacks += it }
                    fun start(limit: Long = 64L * 1024 * 1024) {
                        when (mode) {
                            "clone-current", "clone-all" -> editor.startCloneStroke(id, tip, -16.0, 0.0,
                                mode == "clone-all", limit)
                            else -> editor.startStroke(id, tip, erasing = mode == "erase", mask = mode == "mask",
                                transientLimitBytes = limit)
                        }
                    }
                    fun sample() = editor.samples(doubleArrayOf(24.5, 16.5, 1.0, 0.0))
                    check(runCatching { start(1) }.isFailure) { "$label accepted one-byte stroke allowance" }
                    check(editor.state() == state && editor.pixelSelection() == selection &&
                        image(editor).contentEquals(before) && callbacks.isEmpty()) { "$label refused begin changed state" }
                    claims(editor, ledger, "$label refused begin")

                    start(); sample()
                    val preview = image(editor)
                    check(!preview.contentEquals(before)) { "$label did not paint a soft preview" }
                    val heldSelection = editor.pixelSelection()
                    check(runCatching { editor.modifyPixelSelection(PixelSelectionAmountOperation.FEATHER, 1) }.isFailure) {
                        "$label allowed selection mutation during an active stroke"
                    }
                    check(editor.pixelSelection() == heldSelection) {
                        "$label refused selection mutation changed the active preview selection record"
                    }
                    selectionUnchanged("preview", state.revision)
                    editor.endStroke(true)
                    check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && image(editor).contentEquals(before) && callbacks.isEmpty()) {
                        "$label cancel retained pixels, history, or a callback"
                    }
                    selectionUnchanged("cancel", state.revision)
                    claims(editor, ledger, "$label cancel")

                    start(); sample(); editor.endStroke(false)
                    val after = image(editor)
                    check(after.contentEquals(preview)) { "$label commit changed the rendered preview" }
                    fun coverage(x: Int, y: Int): Int = if (mode == "erase" || mode == "mask")
                        255 - value(after, x, y, 3) else value(after, x, y, 1)
                    val center = coverage(24, 16)
                    val edge = coverage(20, 16)
                    val outside = coverage(18, 16)
                    check(center in 128..254 && edge in 1 until center && outside in 1 until edge) {
                        "$label lost feather falloff: center=$center edge=$edge outside=$outside"
                    }
                    check(coverage(24, 0) == 0) { "$label painted outside the four-sigma selection support" }
                    if (mode == "brush") brushCoverage = after
                    else {
                        val reference = checkNotNull(brushCoverage) { "$label has no preceding Brush coverage reference" }
                        // All source samples in this right half are green. The same selection must
                        // attenuate painting, erasing, mask painting, and either Clone source mode.
                        for (y in 0 until side) for (x in 16 until side) {
                            val expected = value(reference, x, y, 1)
                            check(kotlin.math.abs(coverage(x, y) - expected) <= 1) {
                                "$label coverage($x,$y)=${coverage(x, y)} differs from Brush=$expected"
                            }
                        }
                    }
                    check(callbacks == listOf(state.revision + 1) && !editor.hasActiveStrokes && editor.pendingEditCount == 0) {
                        "$label commit callbacks=$callbacks, activeStroke=${editor.hasActiveStrokes}, pending=${editor.pendingEditCount}"
                    }
                    selectionUnchanged("commit", state.revision + 1)
                    claims(editor, ledger, "$label commit")
                    editor.undo(false)
                    check(image(editor).contentEquals(before)) { "$label Undo left pixels" }
                    selectionUnchanged("Undo", state.revision + 2)
                    claims(editor, ledger, "$label Undo")
                    editor.undo(true)
                    check(image(editor).contentEquals(after)) { "$label Redo changed pixels" }
                    check(callbacks == listOf(state.revision + 1, state.revision + 2, state.revision + 3)) {
                        "$label Undo/Redo callbacks differ from the three committed revisions: $callbacks"
                    }
                    selectionUnchanged("Redo", state.revision + 3)
                    claims(editor, ledger, "$label Redo")
                    editor.committed = null
                } finally { editor.endStroke(true) }
            }
            val closed = ledger.snapshot()
            check(closed.reservedBytes == 0L && closed.reservations == 0 && closed.residentBytes == 0L) {
                "$label close leaked claims: $closed"
            }
        }
    }
    return "Soft selection JNI passed 10 rectangle/ellipse Brush, Erase, Mask and current/all-visible Clone cases: fractional edge/outside falloff, cross-tool coverage, preview/commit, atomic budget refusal, locked selection, cancel, exact Undo/Redo and released ledger claims. No Mac pixel-parity or sustained-performance claim."
}
