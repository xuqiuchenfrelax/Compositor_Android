package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/** Pixel witnesses exercise the bounded fill JNI entry point and managed admission together. */
internal fun selectionFillTransformNativeSmoke(instrumentation: Instrumentation): String {
    val side = 32
    fun pixels(width: Int, height: Int, opaque: Boolean = true): ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4).apply {
            repeat(width * height) { put(0.toByte()); put(0.toByte()); put(if (opaque) (-1).toByte() else 0.toByte()); put(if (opaque) (-1).toByte() else 0.toByte()) }
            rewind()
        }
    fun image(editor: NativeEditor): ByteArray {
        val result = ByteBuffer.allocateDirect(side * side * 4)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), result)) { "Fill fixture could not render" }
        return ByteArray(result.capacity()).also { result.rewind(); result.get(it) }
    }
    fun claims(editor: NativeEditor, ledger: ResourceBudgetLedger, label: String) {
        val budget = ledger.snapshot()
        check(budget.reservations == 1 && budget.reservedBytes == editor.installedDocumentBytes() && budget.residentBytes == 0L) {
            "$label retained temporary fill claims: $budget"
        }
    }
    fun roundtrip(editor: NativeEditor, expected: ByteArray, label: String) {
        val file = File(instrumentation.targetContext.cacheDir, "selection-fill-$label-${System.nanoTime()}.checkpoint")
        try {
            val revision = editor.save(file)
            NativeEditor.load(file).use { reopened ->
                check(reopened.currentRevision() == revision && image(reopened).contentEquals(expected)) {
                    "$label checkpoint changed pixels or revision"
                }
                check(!reopened.pixelSelection().active) { "$label unexpectedly persisted the session selection" }
            }
        } finally { file.delete() }
    }
    data class Case(val name: String, val transform: Transform64, val blank: Boolean = false, val exactDocumentPixels: Boolean = true)
    val cases = listOf(
        Case("translated", Transform64(12.0, 8.0, 8.0, 8.0)),
        Case("scaled", Transform64(8.0, 6.0, 16.0, 12.0), exactDocumentPixels = false),
        Case("rotated", Transform64(10.0, 8.0, 12.0, 12.0, 37.0), exactDocumentPixels = false),
        Case("quarter-turn", Transform64(12.0, 8.0, 8.0, 8.0, 90.0)),
        Case("flipped", Transform64(12.0, 8.0, 8.0, 8.0, flipX = true, flipY = true)),
        Case("blank", Transform64(12.0, 8.0, 32.0, 32.0), blank = true),
    )
    for (case in cases) {
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            val id = if (case.blank) {
                editor.addLayer()
                editor.state().layers.single().also {
                    check(!it.assetPresent) { "Blank fixture unexpectedly has an imported image asset" }
                }.id
            } else editor.importPixels(8, 8, pixels(8, 8))
            editor.setTransform(id, case.transform)
            // The selection extends beyond the original layer, forcing image growth on Fill.
            editor.setPixelSelection(PixelSelectionRect(4.0, 4.0, 24.0, 20.0))
            val before = image(editor)
            val state = editor.state()
            val selection = editor.pixelSelection()
            val callbacks = mutableListOf<Long>()
            editor.committed = { callbacks += it }
            check(runCatching { editor.fillPixelSelection(0xff00ff00.toInt(), id, 1) }.isFailure) {
                "${case.name} admitted a one-byte transformed Fill"
            }
            check(editor.state() == state && editor.pixelSelection() == selection &&
                image(editor).contentEquals(before) && callbacks.isEmpty()) { "${case.name} denied Fill was not atomic" }
            claims(editor, ledger, "${case.name} refusal")
            // The default requested allowance is large, but only one byte remains in
            // this ledger. JNI must receive that admitted byte, not the requested cap.
            val hold = ledger.reserve(ledger.snapshot().availableBytes - 1, "fill JNI admission witness")
            try {
                check(runCatching { editor.fillPixelSelection(0xff00ff00.toInt(), id) }.isFailure) {
                    "${case.name} Fill bypassed the actual one-byte ledger allowance"
                }
                check(editor.state().copy(selectedLayerActions = null) == state.copy(selectedLayerActions = null) && editor.pixelSelection() == selection &&
                    image(editor).contentEquals(before) && callbacks.isEmpty()) {
                    "${case.name} ledger-limited refusal changed document or callbacks"
                }
            } finally { hold.close() }
            claims(editor, ledger, "${case.name} ledger-limited refusal")
            editor.fillPixelSelection(0xff00ff00.toInt(), id)
            val filled = image(editor)
            if (case.exactDocumentPixels) repeat(side * side) { pixel ->
                val x = pixel % side; val y = pixel / side
                val selected = x in 4..27 && y in 4..23
                repeat(4) { channel ->
                    val expected = if (selected) if (channel == 1 || channel == 3) 255 else 0
                        else before[pixel * 4 + channel].toInt() and 255
                    val actual = filled[pixel * 4 + channel].toInt() and 255
                    check(actual == expected) { "${case.name} Fill pixel($x,$y) channel=$channel expected=$expected actual=$actual" }
                }
            }
            // Arbitrary rotation/scaling retain the source grid. Their resampled boundary
            // is not the same operation as filling an already-rendered document image.
            for ((x, y) in listOf(8 to 10, 16 to 12, 24 to 18)) {
                check((filled[(y * side + x) * 4 + 1].toInt() and 255) == 255 &&
                    (filled[(y * side + x) * 4 + 3].toInt() and 255) == 255) {
                    "${case.name} Fill did not cover interior document pixel $x,$y"
                }
            }
            check(filled.take(4) == before.take(4)) { "${case.name} Fill changed remote document corner" }
            val filledTransform = editor.state().layers.single().transform
            check(filledTransform.rotation == case.transform.rotation && filledTransform.flipX == case.transform.flipX &&
                filledTransform.flipY == case.transform.flipY) { "${case.name} Fill lost rotation or flip state" }
            check(callbacks == listOf(state.revision + 1)) { "${case.name} Fill callbacks=$callbacks" }
            claims(editor, ledger, "${case.name} Fill")
            editor.undo(false)
            check(image(editor).contentEquals(before) && editor.state().layers == state.layers) { "${case.name} Fill Undo lost transform or pixels" }
            editor.undo(true)
            check(image(editor).contentEquals(filled)) { "${case.name} Fill Redo changed pixels" }
            claims(editor, ledger, "${case.name} history")
            roundtrip(editor, filled, case.name)
            // Remove the left part. Native may tight-crop storage but document coordinates stay fixed.
            editor.setPixelSelection(PixelSelectionRect(4.0, 4.0, 8.0, 20.0))
            val cutRevision = editor.currentRevision()
            editor.fillPixelSelection(0, id)
            val cut = image(editor)
            if (case.exactDocumentPixels) repeat(side * side) { pixel ->
                val selected = pixel % side in 4..11 && pixel / side in 4..23
                repeat(4) { channel ->
                    val expected = if (selected) 0 else filled[pixel * 4 + channel].toInt() and 255
                    check((cut[pixel * 4 + channel].toInt() and 255) == expected) {
                        "${case.name} Cut misplaced document pixel ${pixel % side},${pixel / side} channel=$channel"
                    }
                }
            }
            check((cut[(12 * side + 8) * 4 + 3].toInt() and 255) == 0) { "${case.name} Cut missed selection interior" }
            check((cut[(12 * side + 20) * 4 + 1].toInt() and 255) == 255 &&
                (cut[(12 * side + 20) * 4 + 3].toInt() and 255) == 255) { "${case.name} Cut damaged preserved fill interior" }
            check(editor.currentRevision() == cutRevision + 1) { "${case.name} Cut failed to commit exactly once" }
            editor.undo(false); check(image(editor).contentEquals(filled)) { "${case.name} Cut Undo changed pixels" }
            editor.undo(true); check(image(editor).contentEquals(cut)) { "${case.name} Cut Redo changed pixels" }
            claims(editor, ledger, "${case.name} Cut history")
            roundtrip(editor, cut, "${case.name}-cut")
            editor.committed = null
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0) { "${case.name} close leaked ledger claims" }
    }
    for (linked in listOf(false, true)) {
        val label = "mask-linked=$linked"
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            val id = editor.importPixels(side, side, pixels(side, side))
            editor.createMask(id, true)
            editor.linkMask(id, linked)
            if (!linked) editor.setMaskPlacement(id, Transform64(4.0, 4.0, 24.0, 24.0))
            editor.selectLayers(listOf(id), id, mask = true)
            editor.setPixelSelection(PixelSelectionRect(8.0, 8.0, 8.0, 8.0))
            val before = image(editor)
            val state = editor.state()
            val selection = editor.pixelSelection()
            check(runCatching { editor.fillPixelSelection(0xff000000.toInt(), id, 1) }.isFailure) { "$label admitted one-byte Fill" }
            check(editor.state() == state && editor.pixelSelection() == selection && image(editor).contentEquals(before)) { "$label refused Fill changed document" }
            claims(editor, ledger, "$label refusal")
            editor.fillPixelSelection(0xff000000.toInt(), id)
            val black = image(editor)
            check((black[(12 * side + 12) * 4 + 3].toInt() and 255) == 0) { "$label Fill did not map into selection center" }
            check(black[(20 * side + 20) * 4 + 3] == before[(20 * side + 20) * 4 + 3]) { "$label Fill touched outside selection" }
            editor.undo(false); check(image(editor).contentEquals(before)) { "$label Undo changed mask pixels" }
            editor.undo(true); check(image(editor).contentEquals(black)) { "$label Redo changed mask pixels" }
            claims(editor, ledger, "$label history")
            roundtrip(editor, black, "mask-$linked")
            editor.undo(false)
            editor.fillPixelSelection(0, id)
            check(image(editor).contentEquals(black)) { "$label alpha-zero mask Fill changed the established zero-mask contract" }
            claims(editor, ledger, "$label alpha-zero")
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0) { "$label close leaked ledger claims" }
    }
    selectionSameColorMaskGrowthSmoke(instrumentation)
    return "Same-color white Mask Fill 16x12@12,10 to 48x40@0 geometry/history/checkpoint passed. Bounded Fill JNI passed exact translated/quarter-turn/flipped/blank Fill growth and Cut pixels, scaled/arbitrary-rotation interior and transform checks, linked/placed mask mapping, one-byte refusal atomicity, exact Undo/Redo, checkpoint reopen and ledger release. Alpha-zero mask behavior remains zero coverage; no Mac Delete background or full pixel-parity claim."
}

/** The actual Mac fixture has independent canvas, image and placed-mask dimensions. */
private fun selectionSameColorMaskGrowthSmoke(instrumentation: Instrumentation) {
    val width = 48; val height = 40
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    val directory = File(instrumentation.targetContext.cacheDir, "selection-white-mask-${System.nanoTime()}.comp")
    val checkpoint = File(instrumentation.targetContext.cacheDir, "selection-white-mask-${System.nanoTime()}.checkpoint")
    check(directory.mkdir()) { "Could not create white-mask fixture directory" }
    try {
        val images = File(directory, "images")
        check(images.mkdir()) { "Could not create white-mask image directory" }
        val layerUUID = UUID.randomUUID().toString().uppercase()
        val source = ByteArray(width * height * 4).also { bytes ->
            repeat(width * height) { bytes[it * 4 + 2] = -1; bytes[it * 4 + 3] = -1 }
        }
        selectionFillFixturePng(File(images, "$layerUUID.png"), width, height, 4, source)
        selectionFillFixturePng(File(images, "$layerUUID.mask.png"), 16, 12, 1, ByteArray(16 * 12) { -1 })
        fun transform(x: Int, y: Int, w: Int, h: Int) = JSONObject()
            .put("origin", JSONArray().put(x).put(y)).put("size", JSONArray().put(w).put(h))
            .put("rotation", 0).put("flipX", false).put("flipY", false).put("sampling", "Nearest")
        val layer = JSONObject().put("id", layerUUID).put("name", "White mask growth")
            .put("isVisible", true).put("opacity", 1).put("blendMode", "Normal").put("isGroup", false)
            .put("imageFile", "$layerUUID.png").put("transform", transform(0, 0, width, height))
            .put("maskFile", "$layerUUID.mask.png").put("maskEnabled", true).put("maskLinked", false)
            .put("maskPlacement", transform(12, 10, 16, 12))
        File(directory, "manifest.json").writeText(JSONObject().put("format", "com.compositor.project").put("version", 13)
            .put("documentID", UUID.randomUUID().toString().uppercase()).put("width", width).put("height", height)
            .put("resolution", 72).put("colorSpace", "sRGB").put("activeLayerID", layerUUID)
            .put("guides", JSONArray()).put("layers", JSONArray().put(layer)).toString())
        fun image(editor: NativeEditor): ByteArray {
            val buffer = ByteBuffer.allocateDirect(width * height * 4)
            check(editor.draw(width, height, 0.0, 0.0, width.toDouble(), height.toDouble(), buffer)) {
                "White-mask growth fixture could not render"
            }
            return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
        }
        fun mask(editor: NativeEditor, expanded: Boolean) {
            val value = editor.state().layers.single()
            val expectedWidth = if (expanded) width else 16
            val expectedHeight = if (expanded) height else 12
            val placement = if (expanded) Transform64(0.0, 0.0, 48.0, 40.0) else Transform64(12.0, 10.0, 16.0, 12.0)
            check(value.hasMask && value.maskEnabled && !value.maskLinked && value.maskWidth == expectedWidth &&
                value.maskHeight == expectedHeight && value.maskPlacement == placement) {
                "White-mask growth expected ${expectedWidth}x$expectedHeight at $placement, actual=$value"
            }
            val bytes = ByteBuffer.allocateDirect(expectedWidth * expectedHeight)
            editor.maskPixels(value.id, expectedWidth, bytes)
            repeat(bytes.capacity()) { index ->
                check((bytes.get(index).toInt() and 255) == 255) { "White-mask growth altered gray byte $index" }
            }
        }
        NativeEditor.loadProjectCandidate(instrumentation.targetContext, directory, 64L * 1024 * 1024, 0).use { editor ->
            check(editor.canvasWidth == width && editor.canvasHeight == height) { "White-mask fixture canvas size changed" }
            val id = editor.state().layers.single().id
            editor.selectLayers(listOf(id), id, mask = true)
            editor.setPixelSelection(PixelSelectionRect(4.0, 7.0, 21.0, 19.0))
            val state = editor.state(); val selection = editor.pixelSelection(); val before = image(editor)
            val beforeClaims = ledger.snapshot()
            mask(editor, false)
            val callbacks = mutableListOf<Long>(); editor.committed = { callbacks += it }
            check(runCatching { editor.fillPixelSelection(-1, id, 1) }.isFailure) { "White-on-white mask fill bypassed one-byte allowance" }
            check(editor.state() == state && editor.pixelSelection() == selection && image(editor).contentEquals(before) && callbacks.isEmpty()) {
                "White-mask growth budget refusal changed state, geometry, pixels or callbacks"
            }
            mask(editor, false)
            check(ledger.snapshot().reservedBytes == beforeClaims.reservedBytes && ledger.snapshot().reservations == beforeClaims.reservations) {
                "White-mask growth budget refusal retained a claim"
            }
            editor.fillPixelSelection(-1, id)
            mask(editor, true)
            val expanded = editor.state().layers.single()
            check(image(editor).contentEquals(before) && callbacks == listOf(state.revision + 1)) {
                "White-on-white mask must preserve visible pixels but commit expanded geometry once"
            }
            check(expanded.transform == state.layers.single().transform && expanded.pixelWidth == width && expanded.pixelHeight == height) {
                "White-mask growth changed owner image geometry"
            }
            val expandedSelection = editor.pixelSelection()
            editor.fillPixelSelection(-1, id)
            check(editor.pixelSelection() == expandedSelection && callbacks == listOf(state.revision + 1)) {
                "Repeated white-mask fill added an unnecessary commit after geometry was already expanded"
            }
            editor.undo(false); mask(editor, false)
            check(editor.state().layers == state.layers && image(editor).contentEquals(before)) { "White-mask Undo did not restore exact original state" }
            editor.undo(true); mask(editor, true)
            check(editor.state().layers.single() == expanded && image(editor).contentEquals(before) &&
                callbacks == listOf(state.revision + 1, state.revision + 2, state.revision + 3)) {
                "White-mask Redo did not restore exact expanded geometry, pixels or callbacks"
            }
            val savedRevision = editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { reopened ->
                mask(reopened, true)
                check(reopened.currentRevision() == savedRevision && reopened.state().layers.single() == expanded &&
                    image(reopened).contentEquals(before)) { "White-mask checkpoint reopen lost expanded geometry or pixels" }
            }
            val settled = ledger.snapshot()
            check(settled.reservations == initial.reservations + 1 &&
                settled.reservedBytes == initial.reservedBytes + editor.installedDocumentBytes()) {
                "White-mask history/checkpoint leaked temporary ledger claims: $settled"
            }
            editor.committed = null
        }
        val closed = ledger.snapshot()
        check(closed.reservedBytes == initial.reservedBytes && closed.reservations == initial.reservations &&
            closed.residentBytes == initial.residentBytes) { "White-mask growth close leaked claims: before=$initial after=$closed" }
    } finally {
        checkpoint.delete()
        directory.deleteRecursively()
    }
}

private fun selectionFillFixturePng(file: File, width: Int, height: Int, channels: Int, pixels: ByteArray) {
    DataOutputStream(file.outputStream()).use { output ->
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(kind: String, bytes: ByteArray) {
            val type = kind.toByteArray(Charsets.US_ASCII)
            output.writeInt(bytes.size); output.write(type); output.write(bytes)
            output.writeInt(CRC32().apply { update(type); update(bytes) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use {
            it.writeInt(width); it.writeInt(height); it.writeByte(8); it.writeByte(if (channels == 1) 0 else 6)
            it.writeByte(0); it.writeByte(0); it.writeByte(0)
        } }.toByteArray()
        chunk("IHDR", header)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { encoder ->
            repeat(height) { row -> encoder.write(0); encoder.write(pixels, row * width * channels, width * channels) }
        }
        chunk("IDAT", compressed.toByteArray()); chunk("IEND", ByteArray(0))
    }
}
