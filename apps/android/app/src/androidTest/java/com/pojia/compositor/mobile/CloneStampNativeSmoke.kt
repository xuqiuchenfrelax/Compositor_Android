package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer

/** Real JNI Clone Stamp transactions with independent source-color and history expectations. */
internal fun cloneStampNativeSmoke(instrumentation: Instrumentation): String {
    val side = 32
    val tip = StrokeSettings(diameter = 6f, hardness = 1f)
    fun rgba(color: (Int, Int) -> IntArray): ByteArray = ByteArray(side * side * 4).also { bytes ->
        repeat(side * side) { pixel ->
            val value = color(pixel % side, pixel / side)
            repeat(4) { bytes[pixel * 4 + it] = value[it].toByte() }
        }
    }
    val colors = rgba { x, y ->
        when {
            x in 4..9 && y in 12..19 -> intArrayOf(0, 255, 0, 255)
            x < 16 -> intArrayOf(255, 0, 0, 255)
            else -> intArrayOf(0, 0, 255, 255)
        }
    }
    fun importPixels(editor: NativeEditor, bytes: ByteArray): Long = editor.importPixels(side, side,
        ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() })
    fun image(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(side * side * 4)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun pixel(bytes: ByteArray, x: Int, y: Int): List<Int> =
        (0..3).map { bytes[(y * side + x) * 4 + it].toInt() and 255 }
    fun expectPixel(bytes: ByteArray, x: Int, y: Int, expected: List<Int>, label: String) {
        check(pixel(bytes, x, y) == expected) { "$label ($x,$y): expected $expected, got ${pixel(bytes, x, y)}" }
    }
    fun outside(before: ByteArray, after: ByteArray, label: String) {
        repeat(side * side) { position ->
            val x = position % side; val y = position / side
            if (x !in 20..29 || y !in 12..21) repeat(4) { channel ->
                check(before[position * 4 + channel] == after[position * 4 + channel]) {
                    "$label changed an outside pixel ($x,$y), channel $channel"
                }
            }
        }
    }
    fun claims(editor: NativeEditor, ledger: ResourceBudgetLedger, label: String) {
        val live = ledger.snapshot()
        check(live.residentBytes == 0L && live.reservations == 1 &&
            live.reservedBytes == editor.installedDocumentBytes()) { "$label retained a temporary stroke claim: $live" }
    }
    fun fixture(bytes: ByteArray = colors, action: (NativeEditor, Long, ResourceBudgetLedger) -> Unit) {
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            val id = importPixels(editor, bytes)
            check(image(editor).contentEquals(bytes))
            try { action(editor, id, ledger) }
            finally { editor.endStroke(true) }
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0) {
            "Closed Clone Stamp fixture leaked resource claims"
        }
    }
    fun samples(editor: NativeEditor) = editor.samples(doubleArrayOf(24.5, 16.5, 1.0, 0.0, 25.0, 16.5, 1.0, .05))
    fun clone(editor: NativeEditor, id: Long, settings: StrokeSettings = tip, all: Boolean = false) {
        editor.startCloneStroke(id, settings, -18.0, 0.0, all)
        samples(editor)
        editor.endStroke(false)
    }

    fixture { editor, id, ledger ->
        val before = image(editor)
        val state = editor.state()
        val baseline = ledger.snapshot()
        val callbacks = mutableListOf<Long>()
        editor.committed = { callbacks.add(it) }
        editor.startCloneStroke(id, tip, -18.0, 0.0, false)
        samples(editor)
        check(editor.currentRevision() == state.revision) { "Clone preview changed committed revision" }
        editor.endStroke(true)
        check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && image(editor).contentEquals(before) && callbacks.isEmpty())
        claims(editor, ledger, "Canceled Clone")
        check(ledger.snapshot().reservedBytes == baseline.reservedBytes)

        clone(editor, id)
        val copied = image(editor)
        expectPixel(copied, 24, 16, listOf(0, 255, 0, 255), "Current-layer green source")
        expectPixel(copied, 30, 2, listOf(0, 0, 255, 255), "Untouched blue destination")
        outside(before, copied, "Clone")
        check(editor.currentRevision() == state.revision + 1 && callbacks == listOf(state.revision + 1))
        claims(editor, ledger, "Committed Clone")
        editor.undo(false)
        check(image(editor).contentEquals(before)) { "One Undo did not remove the whole clone stroke" }
        claims(editor, ledger, "Clone Undo")
        editor.undo(true)
        check(image(editor).contentEquals(copied)) { "Redo changed clone pixels" }
        check(callbacks == listOf(state.revision + 1, state.revision + 2, state.revision + 3))
        claims(editor, ledger, "Clone Redo")
        val checkpoint = File(instrumentation.targetContext.cacheDir, "clone-stamp-${System.nanoTime()}.checkpoint")
        try {
            val saved = editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { reopened ->
                check(reopened.currentRevision() == saved && image(reopened).contentEquals(copied)) {
                    "Cold checkpoint reopen changed clone result"
                }
                check(!reopened.state().undo && !reopened.state().redo)
            }
        } finally { checkpoint.delete() }
        editor.committed = null
    }

    fixture { editor, id, ledger ->
        clone(editor, id, tip.copy(opacity = .25f))
        val copied = image(editor)
        // A full-coverage green sample at 25% over opaque blue has premultiplied RGBA 0,64,191,255.
        expectPixel(copied, 24, 16, listOf(0, 64, 191, 255), "Clone opacity")
        outside(colors, copied, "Opacity Clone")
        claims(editor, ledger, "Opacity Clone")
    }

    // Mac Clone Stamp captures its own fixed tip/opacity; stylus pressure belongs
    // to the ordinary brush options and must not shrink, fade, or skip this tool.
    var fullPressureClone: ByteArray? = null
    for (pressure in listOf(1.0, .25, 0.0)) {
        fixture { editor, id, ledger ->
            val revision = editor.currentRevision()
            val callbacks = mutableListOf<Long>()
            editor.committed = { callbacks.add(it) }
            editor.startCloneStroke(id, tip.copy(opacity = .25f), -18.0, 0.0, false)
            editor.samples(doubleArrayOf(24.5, 16.5, pressure, 0.0, 25.0, 16.5, pressure, .05))
            editor.endStroke(false)
            val copied = image(editor)
            expectPixel(copied, 24, 16, listOf(0, 64, 191, 255), "Pressure-independent Clone $pressure")
            outside(colors, copied, "Pressure-independent Clone $pressure")
            val reference = fullPressureClone
            if (reference == null) fullPressureClone = copied
            else check(copied.contentEquals(reference)) {
                "Clone pressure $pressure changed full RGBA output compared with pressure 1"
            }
            check(callbacks == listOf(revision + 1) && editor.currentRevision() == revision + 1)
            claims(editor, ledger, "Pressure-independent Clone $pressure")
            editor.committed = null
        }
    }

    val gradient = rgba { x, y -> intArrayOf(x * 7, y * 5, 255 - x * 7, 255) }
    fixture(gradient) { editor, id, ledger ->
        editor.startCloneStroke(id, tip.copy(diameter = 4f), -4.0, 0.0, false)
        // The second segment samples pixels painted by the first segment. Both must
        // still read the stroke-start source, never the preview's earlier writes.
        editor.samples(doubleArrayOf(12.5, 16.5, 1.0, 0.0, 18.5, 16.5, 1.0, .05))
        editor.samples(doubleArrayOf(24.5, 16.5, 1.0, .1))
        editor.endStroke(false)
        val copied = image(editor)
        for (x in listOf(16, 20, 23))
            expectPixel(copied, x, 16, pixel(gradient, x - 4, 16), "Stroke-start source snapshot")
        expectPixel(copied, 2, 2, pixel(gradient, 2, 2), "Snapshot fixture outside")
        claims(editor, ledger, "Overlapping Clone")
    }

    for (all in listOf(false, true)) for (showOverlay in listOf(false, true)) {
        val base = rgba { x, _ -> if (x < 16) intArrayOf(255, 0, 0, 255) else intArrayOf(0, 0, 255, 255) }
        fixture(base) { editor, id, ledger ->
            val overlay = importPixels(editor, rgba { x, y ->
                if (x in 4..9 && y in 12..19) intArrayOf(0, 255, 0, 255) else intArrayOf(0, 0, 0, 0)
            })
            editor.setVisible(overlay, showOverlay)
            val hidden = importPixels(editor, rgba { x, y ->
                if (x in 4..9 && y in 12..19) intArrayOf(255, 0, 255, 255) else intArrayOf(0, 0, 0, 0)
            })
            editor.setVisible(hidden, false)
            editor.selectLayer(id)
            val before = image(editor)
            clone(editor, id, all = all)
            val copied = image(editor)
            expectPixel(copied, 24, 16, if (all && showOverlay) listOf(0, 255, 0, 255) else listOf(255, 0, 0, 255),
                "Sampling all=$all overlay=$showOverlay with hidden magenta layer")
            outside(before, copied, "Layer sampling Clone")
            claims(editor, ledger, "Layer sampling Clone")
        }
    }

    fixture { editor, id, ledger ->
        val state = editor.state()
        val before = image(editor)
        val callbacks = mutableListOf<Long>()
        editor.committed = { callbacks.add(it) }
        for (all in listOf(false, true)) {
            val baseline = ledger.snapshot()
            val failure = runCatching {
                editor.startCloneStroke(id, tip, -18.0, 0.0, all, transientLimitBytes = 1)
                samples(editor)
                editor.endStroke(false)
            }.exceptionOrNull()
            check(failure != null) { "One-byte clone allowance accepted source/working storage" }
            editor.endStroke(true)
            check(editor.state() == state && image(editor).contentEquals(before) && callbacks.isEmpty())
            claims(editor, ledger, "Rejected Clone all=$all")
            check(ledger.snapshot().reservedBytes == baseline.reservedBytes)
        }
        clone(editor, id)
        expectPixel(image(editor), 24, 16, listOf(0, 255, 0, 255), "Clone retry after budget refusal")
        check(callbacks == listOf(state.revision + 1))
        claims(editor, ledger, "Clone budget retry")
        editor.committed = null
    }

    // A large caller request must never expand the smaller segment actually
    // admitted by the shared ledger, for Clone Stamp or the ordinary brush.
    for (cloning in listOf(false, true)) for (available in listOf(0L, 1L)) {
        fixture { editor, id, ledger ->
            val state = editor.state()
            val before = image(editor)
            val callbacks = mutableListOf<Long>()
            editor.committed = { callbacks.add(it) }
            fun start() {
                val requested = 64L * 1024 * 1024
                if (cloning) editor.startCloneStroke(id, tip, -18.0, 0.0, true, requested)
                else editor.startStroke(id, tip.copy(green = 1f), transientLimitBytes = requested)
            }
            ledger.reserve(ledger.snapshot().availableBytes - available, "Shared stroke fixture pressure").use {
                val occupied = ledger.snapshot()
                check(occupied.availableBytes == available)
                val failure = runCatching { start() }.exceptionOrNull()
                check(failure != null) {
                    "${if (cloning) "Clone" else "Brush"} admitted a 64 MiB request with only $available byte(s) available"
                }
                editor.endStroke(true)
                check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && image(editor).contentEquals(before) && callbacks.isEmpty())
                val after = ledger.snapshot()
                check(after.reservedBytes == occupied.reservedBytes && after.reservations == occupied.reservations &&
                    after.availableBytes == occupied.availableBytes) { "Refused shared stroke leaked or expanded its admitted segment" }
            }
            claims(editor, ledger, "Released shared stroke pressure")
            start(); samples(editor); editor.endStroke(false)
            expectPixel(image(editor), 24, 16, listOf(0, 255, 0, 255), "Shared stroke admission retry")
            check(callbacks == listOf(state.revision + 1))
            claims(editor, ledger, "Shared stroke retry")
            editor.committed = null
        }
    }

    fixture { editor, id, ledger ->
        editor.createMask(id, true)
        editor.selectLayers(listOf(id), id, mask = true)
        fun rejected(label: String) {
            val state = editor.state()
            val selection = editor.pixelSelection()
            val before = image(editor)
            val budget = ledger.snapshot()
            check(runCatching { editor.startCloneStroke(id, tip, -18.0, 0.0, false) }.isFailure) {
                "$label must reject this unsupported clone target"
            }
            editor.endStroke(true)
            check(editor.state() == state && editor.pixelSelection() == selection && image(editor).contentEquals(before))
            claims(editor, ledger, label)
            check(ledger.snapshot().reservedBytes == budget.reservedBytes)
        }
        rejected("Mask-focused Clone")
        editor.selectLayer(id)
        editor.setPixelSelection(PixelSelectionRect(24.0, 15.0, 1.0, 2.0))
        clone(editor, id)
        val clipped = image(editor)
        expectPixel(clipped, 24, 16, listOf(0, 255, 0, 255), "Selected clone target")
        expectPixel(clipped, 25, 16, listOf(0, 0, 255, 255), "Selection excludes adjacent covered target")
        claims(editor, ledger, "Mask rejection followed by selected clone")
    }
    return "Real JNI Clone Stamp passed exact source colors, unchanged outside pixels, 25% opacity, identical full RGBA output at synthetic sample pressures 0/0.25/1, stroke-start snapshot across overlapping writes, current/all-visible sampling and hidden-layer exclusion. Cancel preserved state; one committed stroke produced one callback and one Undo/Redo step; checkpoint cold reopen preserved bytes. One-byte source/working admission refused without changing state or leaking claims, then retry committed. Clone and ordinary Brush rejected 64 MiB requests when the shared ledger admitted only zero/one byte; state and claims remained unchanged and retries committed after pressure release. Mask focus rejected unchanged; after returning to image pixels an active selection clipped the adjacent covered target. No UI, transformed-target, mask-painting, physical-stylus or sustained-performance claim."
}
