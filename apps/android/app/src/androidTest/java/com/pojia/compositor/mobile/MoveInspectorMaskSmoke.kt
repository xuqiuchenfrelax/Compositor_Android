package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.floor

/** DRAFT: public Inspector UI over independent gray8 comp inputs; source/raw masks are immutable witnesses. */
internal fun moveInspectorMaskSmoke(instrumentation: Instrumentation): String {
    val initialClaims = ImageIoAdmission.ledger.snapshot()
    MaskInspectorFixture(instrumentation).use { f ->
        try {
            // Linked mask selection transforms the layer; 100% is 40x20 source pixels, not 8x8 mask pixels.
            f.install(linked = true)
            var before = f.editor.state()
            var beforePixels = f.pixels()
            var assets = f.assets()
            check(f.ui.focusScale().toDouble() == 500.0)
            f.ui.key(KeyEvent.KEYCODE_ENTER); f.settled()
            check(f.editor.state() == before && f.pixels().contentEquals(beforePixels))
            var owner = f.persistent()
            f.ui.scaleText("50")
            var expected = Transform64(110.0, 75.0, 20.0, 10.0)
            f.preview(expected, TransformTarget.LAYER); f.unchanged(before, assets, owner)
            check(f.canvas.moveScalePercent == 50.0)
            f.witnesses("linked-scale50", expected, expected)
            f.ui.key(KeyEvent.KEYCODE_ENTER)
            check(f.editor.activeTransformCompletion() === owner && !owner.isDone)
            f.ui.click("Flip Horizontal"); expected = expected.copy(flipX = true)
            f.preview(expected, TransformTarget.LAYER); f.witnesses("linked-H", expected, expected)
            f.ui.click("Flip Vertical"); expected = expected.copy(flipY = true)
            f.preview(expected, TransformTarget.LAYER); f.witnesses("linked-HV", expected, expected)
            f.unchanged(before, assets, owner)
            f.ui.click("Cancel Transform"); f.settled()
            check(owner.get(10, TimeUnit.SECONDS).cancelled)
            check(f.editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && f.assets() == assets && f.pixels().contentEquals(beforePixels))

            owner = f.persistent()
            f.ui.scaleText("50"); expected = Transform64(110.0, 75.0, 20.0, 10.0)
            f.preview(expected, TransformTarget.LAYER); f.ui.key(KeyEvent.KEYCODE_ENTER)
            f.ui.click("Flip Horizontal"); f.ui.click("Flip Vertical")
            expected = expected.copy(flipX = true, flipY = true)
            f.preview(expected, TransformTarget.LAYER)
            var previewPixels = f.pixels()
            f.ui.click("Apply Transform"); f.settled()
            f.committed(owner, before, expected, TransformTarget.LAYER, previewPixels, assets)
            f.undoRedo(before, beforePixels, previewPixels, expected, TransformTarget.LAYER, assets)
            f.roundtrip("linked-HV", assets)

            // Independent mask: source40x20, mask8x8, owner200x100, placement40x30 are deliberately all different.
            f.install(linked = false)
            before = f.editor.state(); beforePixels = f.pixels(); assets = f.assets()
            check(f.ui.focusScale().toDouble() == 20.0)
            f.ui.key(KeyEvent.KEYCODE_ENTER); f.settled()
            check(f.editor.state() == before && f.pixels().contentEquals(beforePixels))
            owner = f.persistent()
            f.ui.scaleText("50")
            expected = Transform64(50.0, 55.0, 100.0, 50.0)
            f.preview(expected, TransformTarget.UNLINKED_MASK); f.unchanged(before, assets, owner)
            check(f.canvas.moveScalePercent == 50.0)
            f.witnesses("unlinked-scale50", MaskInspectorFixture.OWNER, expected)
            f.ui.scaleText("100", focus = false)
            expected = Transform64(0.0, 30.0, 200.0, 100.0)
            f.preview(expected, TransformTarget.UNLINKED_MASK); f.unchanged(before, assets, owner)
            check(f.canvas.moveScalePercent == 100.0) { "Independent mask basis compounded from preview placement" }
            f.witnesses("unlinked-scale100-fixed-owner-basis", MaskInspectorFixture.OWNER, expected)
            f.ui.scaleText("50", focus = false)
            expected = Transform64(50.0, 55.0, 100.0, 50.0)
            f.preview(expected, TransformTarget.UNLINKED_MASK); f.ui.key(KeyEvent.KEYCODE_ENTER)
            f.ui.click("Flip Horizontal"); expected = expected.copy(flipX = true)
            f.preview(expected, TransformTarget.UNLINKED_MASK)
            f.witnesses("unlinked-H-only-mask", MaskInspectorFixture.OWNER, expected)
            f.ui.click("Flip Vertical"); expected = expected.copy(flipY = true)
            f.preview(expected, TransformTarget.UNLINKED_MASK)
            f.witnesses("unlinked-HV-only-mask", MaskInspectorFixture.OWNER, expected)
            f.unchanged(before, assets, owner)
            f.ui.click("Cancel Transform"); f.settled()
            check(owner.get(10, TimeUnit.SECONDS).cancelled)
            check(f.editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && f.assets() == assets && f.pixels().contentEquals(beforePixels))

            owner = f.persistent()
            f.ui.scaleText("50"); f.preview(Transform64(50.0, 55.0, 100.0, 50.0), TransformTarget.UNLINKED_MASK)
            f.ui.key(KeyEvent.KEYCODE_ENTER)
            f.ui.click("Flip Horizontal"); f.ui.click("Flip Vertical")
            expected = Transform64(50.0, 55.0, 100.0, 50.0, flipX = true, flipY = true)
            f.preview(expected, TransformTarget.UNLINKED_MASK); previewPixels = f.pixels()
            f.ui.click("Apply Transform"); f.settled()
            f.committed(owner, before, expected, TransformTarget.UNLINKED_MASK, previewPixels, assets)
            f.undoRedo(before, beforePixels, previewPixels, expected, TransformTarget.UNLINKED_MASK, assets)
            f.roundtrip("unlinked-HV", assets)

            // A new ordinary edit after committed mask placement still uses the unchanged owning layer's size.
            before = f.editor.state(); beforePixels = f.pixels()
            f.ui.scaleText("100")
            expected = Transform64(0.0, 30.0, 200.0, 100.0, flipX = true, flipY = true)
            f.preview(expected, TransformTarget.UNLINKED_MASK)
            owner = checkNotNull(f.editor.activeTransformCompletion())
            check(f.canvas.moveFromFields && !f.canvas.movePersistent && f.canvas.moveScalePercent == 100.0)
            f.unchanged(before, assets, owner)
            previewPixels = f.pixels()
            f.ui.key(KeyEvent.KEYCODE_ENTER); f.settled()
            f.committed(owner, before, expected, TransformTarget.UNLINKED_MASK, previewPixels, assets)
            f.undoRedo(before, beforePixels, previewPixels, expected, TransformTarget.UNLINKED_MASK, assets)

            before = f.editor.state(); beforePixels = f.pixels()
            f.ui.click("Flip Horizontal")
            expected = expected.copy(flipX = false)
            f.preview(expected, TransformTarget.UNLINKED_MASK)
            owner = checkNotNull(f.editor.activeTransformCompletion())
            f.unchanged(before, assets, owner)
            f.ui.key(KeyEvent.KEYCODE_ESCAPE); f.settled()
            check(owner.get(10, TimeUnit.SECONDS).cancelled && f.editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) &&
                f.pixels().contentEquals(beforePixels) && f.assets() == assets)

            // Rotation and existing local flips survive Scale, even when the disabled mask has no visible effect.
            f.install(linked = false, enabled = false, placement = Transform64(80.0, 65.0, 40.0, 30.0, 37.0, true, false))
            before = f.editor.state(); beforePixels = f.pixels(); assets = f.assets()
            owner = f.persistent()
            f.ui.scaleText("50")
            expected = Transform64(50.0, 55.0, 100.0, 50.0, 37.0, true, false)
            f.preview(expected, TransformTarget.UNLINKED_MASK); f.unchanged(before, assets, owner)
            f.ui.key(KeyEvent.KEYCODE_ENTER)
            f.ui.click("Flip Horizontal"); f.ui.click("Flip Vertical")
            expected = expected.copy(flipX = false, flipY = true)
            f.preview(expected, TransformTarget.UNLINKED_MASK)
            check(f.pixels().contentEquals(beforePixels)) { "Disabled mask transform affected compositing" }
            previewPixels = f.pixels()
            f.ui.click("Apply Transform"); f.settled()
            f.committed(owner, before, expected, TransformTarget.UNLINKED_MASK, previewPixels, assets)
            check(!f.editor.state().layers.single().maskEnabled)
            f.undoRedo(before, beforePixels, previewPixels, expected, TransformTarget.UNLINKED_MASK, assets)
            f.roundtrip("unlinked-disabled-rotated-HV", assets)
            f.ui.successful = true
        } catch (error: Throwable) { f.ui.diagnose(error); throw error }
    }
    val finalClaims = ImageIoAdmission.ledger.snapshot()
    check(finalClaims.reservedBytes == initialClaims.reservedBytes && finalClaims.reservations == initialClaims.reservations)
    return "Linked/unlinked mask Inspector UI: source versus fixed committed-owner Scale basis, actual text edits, " +
        "preview/cancel/commit and single-segment Undo/Redo, independent asymmetric interior pixels, immutable source/raw-gray8 masks, " +
        "disabled rotated placement and Android supported-comp roundtrip. Exported independent pre-write expected files await actual Mac reader verification. " +
        "No masked GLES, full edge raster, lifecycle recreation or true Smooth/High quality claim."
}

private data class MaskAssets(val sourceHash: String, val maskHash: String, val sourceResources: List<Long>)

/** The existing UI fixture has no mask installation hook; reuse UI actions and own only test setup/readback here. */
private class MaskInspectorFixture(private val instrumentation: Instrumentation) : AutoCloseable {
    companion object { val OWNER = Transform64(20.0, 30.0, 200.0, 100.0) }
    val ui = MoveInspectorFixtureSmoke(instrumentation, "moveInspectorMask")
    private val context = instrumentation.targetContext
    private val directory = File(context.filesDir, "p37-mask-${System.nanoTime()}").apply { check(mkdirs()) }
    private val source = moveInspectorColorPixels(40, 20)
    private val mask = ByteArray(64) { index -> if (index % 8 < 4 && index / 8 < 4) (-1).toByte() else 0 }
    lateinit var editor: NativeEditor
        private set
    lateinit var canvas: CanvasView
        private set
    private var root = 0L
    private var initialPlacement: Transform64? = null
    private var linked = false
    private var enabled = true
    private val limit = 64L * 1024 * 1024

    fun install(linked: Boolean, enabled: Boolean = true, placement: Transform64 = Transform64(80.0, 65.0, 40.0, 30.0)) {
        this.linked = linked; this.enabled = enabled; initialPlacement = if (linked) null else placement
        val id = UUID.randomUUID().toString().uppercase()
        val documentId = UUID.randomUUID().toString().uppercase()
        val input = File(directory, "input-${System.nanoTime()}.comp").apply { check(mkdir()) }
        val images = File(input, "images").apply { check(mkdir()) }
        maskPng(File(images, "$id.png"), 40, 20, 4, source)
        maskPng(File(images, "$id.mask.png"), 8, 8, 1, mask)
        File(input, "manifest.json").writeText(manifest(id, documentId, OWNER, initialPlacement).toString(2))
        val previous = findCanvas()
        check(ui.workspace.installCandidate("p37-mask-$id", DirtyDisposition.DISCARD) {
            NativeEditor.loadProjectCandidate(context, input, limit, 0)
        }.result.get(15, TimeUnit.SECONDS).installed)
        editor = checkNotNull(ui.workspace.snapshot().editor)
        root = editor.state().layers.single().id
        ui.waitUntil("Independent mask candidate did not bind the actual Canvas") {
            findCanvas()?.let { next ->
                if (next !== previous && next.isAttachedToWindow && next.holder.surface.isValid &&
                    next.width > 0 && next.height > 0 && ui.activity.retainedEditorForTest() === editor) canvas = next
            }
            ::canvas.isInitialized && canvas !== previous && canvas.layerId == root
        }
        check(editor.state().layers.single().let { it.pixelWidth == 40 && it.pixelHeight == 20 &&
            it.maskWidth == 8 && it.maskHeight == 8 && it.maskLinked == linked && it.maskEnabled == enabled &&
            it.transform == OWNER && it.maskPlacement == initialPlacement && it.sampling == 0 && it.maskSampling == 0 })
        check(rawSource(editor).contentEquals(source) && rawMask(editor).contentEquals(mask))
        ui.click("Move / Transform (V)"); ui.click("Mask")
        ui.waitUntil("Mask UI target did not bind") { canvas.toolMode == CanvasToolMode.MOVE && canvas.editingMask }
        observe("installed", initialPlacement ?: OWNER)
    }

    private fun findCanvas(): CanvasView? {
        fun find(view: View): CanvasView? {
            if (view is CanvasView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var result: CanvasView? = null
        instrumentation.runOnMainSync { result = find(ui.activity.window.decorView) }
        return result
    }

    fun persistent(): CompletableFuture<TransformController.Terminal> {
        ui.key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
        ui.waitUntil("Mask Ctrl+T did not begin a persistent transform") { canvas.movePersistent && editor.hasActiveTransformDraft }
        return checkNotNull(editor.activeTransformCompletion())
    }

    fun preview(expected: Transform64, target: TransformTarget) {
        ui.waitUntil("Mask local/native preview differs from $expected ($target)") {
            canvas.moveBox == expected && editor.serialized {
                editor.hasActiveTransformDraft && editor.captureDisplayScene()?.use { scene ->
                    val layer = scene.layerMetadata(0)
                    layer.id == root && if (target == TransformTarget.UNLINKED_MASK)
                        layer.transform == OWNER && scene.maskPlacement(0) == expected
                    else layer.transform == expected && scene.maskPlacement(0) == null
                } == true
            }
        }
        check(canvas.editingMask)
    }

    fun settled() = ui.waitUntil("Mask transaction did not settle") {
        !canvas.moveInProgress && editor.activeTransformCompletion() == null && !editor.hasActiveTransformDraft
    }

    fun assets(): MaskAssets = checkNotNull(editor.captureDisplayScene()).use { scene ->
        MaskAssets(moveInspectorSha(rawSource(editor)), moveInspectorSha(rawMask(editor)),
            scene.tileViewsMetadata(0).map { it.payloadResourceId })
    }

    fun unchanged(before: EditorState, assets: MaskAssets, owner: CompletableFuture<TransformController.Terminal>) {
        check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && this.assets() == assets && editor.activeTransformCompletion() === owner && !owner.isDone)
        check(editor.state().layers.single().transform == before.layers.single().transform)
    }

    fun pixels(document: NativeEditor = editor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(256 * 256 * 4)
        checkNotNull(document.captureDisplayScene()).use { it.draw(256, 256, Viewport(0.0, 0.0, 256.0, 256.0), buffer) }
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }

    /** Exact interior witnesses avoid claiming an unmeasured affine-edge filter oracle. No tolerance/fallback. */
    fun witnesses(stage: String, owner: Transform64, placement: Transform64) {
        check(owner.rotation == 0.0 && placement.rotation == 0.0 && enabled)
        val actual = pixels()
        val points = JSONArray()
        for (v in listOf(.25, .75)) for (u in listOf(.25, .75)) {
            val x = floor(placement.x + u * placement.width).toInt()
            val y = floor(placement.y + v * placement.height).toInt()
            check(x in 0..255 && y in 0..255)
            val layerU = (x + .5 - owner.x) / owner.width
            val layerV = (y + .5 - owner.y) / owner.height
            check(layerU > 0 && layerU < 1 && layerV > 0 && layerV < 1)
            val sourceX = floor((if (owner.flipX) 1 - layerU else layerU) * 40).toInt()
            val sourceY = floor((if (owner.flipY) 1 - layerV else layerV) * 20).toInt()
            val maskX = if (placement.flipX) 1 - u else u
            val maskY = if (placement.flipY) 1 - v else v
            val visible = maskX < .5 && maskY < .5
            val expected = if (visible) source.copyOfRange((sourceY * 40 + sourceX) * 4, (sourceY * 40 + sourceX + 1) * 4)
                else ByteArray(4)
            val offset = (y * 256 + x) * 4
            check(actual.copyOfRange(offset, offset + 4).contentEquals(expected)) {
                "$stage exact masked interior at($x,$y) differs: expected=${expected.toList()}, actual=${actual.copyOfRange(offset, offset + 4).toList()}"
            }
            points.put(JSONObject().put("x", x).put("y", y).put("expectedRGBA", array(expected.map { it.toInt() and 255 })))
        }
        File(directory, "$stage.rgba").writeBytes(actual)
        ui.observation(stage, JSONObject().put("rasterSHA256", moveInspectorSha(actual)).put("points", points)
            .put("owner", owner.toString()).put("placement", placement.toString()).put("byteTolerance", 0))
    }

    fun committed(owner: CompletableFuture<TransformController.Terminal>, before: EditorState, expected: Transform64,
        target: TransformTarget, previewPixels: ByteArray, assets: MaskAssets) {
        check(owner.get(10, TimeUnit.SECONDS).let { it.committed && !it.cancelled && it.error == null && it.result?.changed == true })
        check(editor.state().revision == before.revision + 1)
        assertGeometry(expected, target)
        check(pixels().contentEquals(previewPixels) && this.assets() == assets)
        observe("committed", expected)
    }

    private fun assertGeometry(expected: Transform64, target: TransformTarget) {
        val layer = editor.state().layers.single()
        check(layer.maskLinked == linked && layer.maskEnabled == enabled && layer.sampling == 0 && layer.maskSampling == 0)
        if (target == TransformTarget.UNLINKED_MASK) check(layer.transform == OWNER && layer.maskPlacement == expected)
        else check(layer.transform == expected && layer.maskPlacement == null)
    }

    fun undoRedo(before: EditorState, beforePixels: ByteArray, afterPixels: ByteArray, expected: Transform64,
        target: TransformTarget, assets: MaskAssets) {
        ui.click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        check(editor.state().layers == before.layers && pixels().contentEquals(beforePixels) && this.assets() == assets)
        ui.click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
        assertGeometry(expected, target)
        check(pixels().contentEquals(afterPixels) && this.assets() == assets)
    }

    fun roundtrip(label: String, assets: MaskAssets) {
        settled()
        val before = editor.state()
        val beforePixels = pixels()
        val layer = before.layers.single()
        val expectedManifest = manifest(layer.uuid, before.document.uuid, layer.transform, layer.maskPlacement)
        val expected = JSONObject().put("manifest", expectedManifest).put("tolerance", 0)
            .put("assets", JSONArray().put(JSONObject().put("layerID", layer.uuid).put("mask", false)
                .put("width", 40).put("height", 20).put("pixels", array(source.map { it.toInt() and 255 })))
                .put(JSONObject().put("layerID", layer.uuid).put("mask", true).put("width", 8).put("height", 8)
                    .put("pixels", array(mask.map { it.toInt() and 255 }))))
        // Independent expectations are captured before the writer, never decoded from its output.
        File(directory, "$label-expected.json").writeText(expected.toString(2))
        val output = File(directory, "$label.comp")
        editor.captureProjectSnapshot().use { snapshot ->
            NativeProjectWriteControl(limit, editor.retainedMemory().totalBytes).use { control ->
                val result = snapshot.write(context, output, false, control)
                check(result.successful && result.capturedRevision == before.revision)
            }
        }
        check(JSONObject(File(output, "manifest.json").readText()).getInt("version") == 17)
        NativeEditor.loadProjectCandidate(context, output, limit, 0).use { restored ->
            check(restored.state().document == before.document && restored.state().layers == before.layers)
            check(pixels(restored).contentEquals(beforePixels))
            check(rawSource(restored).contentEquals(source) && rawMask(restored).contentEquals(mask))
        }
        check(editor.state() == before && this.assets() == assets)
        ui.observation("$label-supported-comp-roundtrip", JSONObject().put("package", output.absolutePath)
            .put("expected", File(directory, "$label-expected.json").absolutePath)
            .put("rasterSHA256", moveInspectorSha(beforePixels)).put("actualMacReaderExecuted", false))
    }

    private fun manifest(layerId: String, documentId: String, owner: Transform64, placement: Transform64?): JSONObject {
        val layer = JSONObject().put("id", layerId).put("name", "Mask Inspector")
            .put("isVisible", true).put("opacity", 1).put("blendMode", "Normal").put("isGroup", false)
            .put("imageFile", "$layerId.png").put("transform", transform(owner))
            .put("maskFile", "$layerId.mask.png").put("maskEnabled", enabled).put("maskLinked", linked)
        placement?.let { layer.put("maskPlacement", transform(it)) }
        return JSONObject().put("format", "com.compositor.project").put("version", 13)
            .put("documentID", documentId).put("width", 256).put("height", 256).put("resolution", 72)
            .put("colorSpace", "sRGB").put("activeLayerID", layerId).put("guides", JSONArray())
            .put("layers", JSONArray().put(layer))
    }

    private fun transform(value: Transform64) = JSONObject().put("origin", array(listOf(value.x, value.y)))
        .put("size", array(listOf(value.width, value.height))).put("rotation", value.rotation)
        .put("flipX", value.flipX).put("flipY", value.flipY).put("sampling", "Nearest")

    private fun rawSource(document: NativeEditor): ByteArray = checkNotNull(document.captureDisplayScene()).use { scene ->
        check(scene.layerMetadata(0).tileCount == 1)
        val buffer = ByteBuffer.allocateDirect(40 * 20 * 4)
        scene.readTile(0, 0, 40 * 4, buffer)
        ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }

    private fun rawMask(document: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(64)
        document.maskPixels(document.state().layers.single().id, 8, buffer)
        return ByteArray(64).also { buffer.rewind(); buffer.get(it) }
    }

    private fun observe(stage: String, expected: Transform64) = ui.observation(stage, JSONObject()
        .put("expected", expected.toString()).put("accepted", canvas.moveBox?.toString() ?: JSONObject.NULL)
        .put("committed", editor.state().toString()).put("artifactDirectory", directory.absolutePath))

    override fun close() {
        try {
            if (::editor.isInitialized) editor.activeTransformCompletion()?.let { owner ->
                instrumentation.runOnMainSync { canvas.cancelMove() }
                owner.get(10, TimeUnit.SECONDS)
            }
        } finally {
            ui.close()
        }
    }
}

private fun array(values: Iterable<Any>): JSONArray = JSONArray().also { output -> values.forEach { output.put(it) } }

/** Small fixture encoder writes actual gray8/no-alpha masks; Bitmap ARGB PNG would violate desktop mask validation. */
private fun maskPng(file: File, width: Int, height: Int, channels: Int, bytes: ByteArray) {
    check(channels == 1 || channels == 4)
    check(bytes.size == width * height * channels)
    DataOutputStream(file.outputStream()).use { output ->
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(kind: String, data: ByteArray) {
            val type = kind.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size); output.write(type); output.write(data)
            val crc = CRC32().apply { update(type); update(data) }
            output.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use {
            it.writeInt(width); it.writeInt(height); it.writeByte(8); it.writeByte(if (channels == 1) 0 else 6)
            it.writeByte(0); it.writeByte(0); it.writeByte(0)
        } }.toByteArray()
        chunk("IHDR", header)
        val encoded = ByteArrayOutputStream()
        DeflaterOutputStream(encoded).use { deflater ->
            repeat(height) { row -> deflater.write(0); deflater.write(bytes, row * width * channels, width * channels) }
        }
        chunk("IDAT", encoded.toByteArray()); chunk("IEND", ByteArray(0))
    }
}
