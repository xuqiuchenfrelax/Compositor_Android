package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.res.AssetManager
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Desktop-authored PNG/metadata goldens exercise the shared codec and private atomic builder. */
internal fun projectCandidateSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val assets = instrumentation.context.assets
    val directory = File(context.cacheDir, "project-candidate-${System.nanoTime()}").apply { check(mkdirs()) }
    val expected = assets.open("expected.json").use { JSONObject(it.reader().readText()) }
    val metadata = expected.getJSONObject("editablePackage").getJSONObject("metadata")
    val fixtures = expected.getJSONArray("fixtures")
    val imageId = metadata.getJSONArray("layers").getJSONObject(1).getString("id")
    val placedId = metadata.getJSONArray("layers").getJSONObject(2).getString("id")
    val limit = 64L * 1024 * 1024
    fun copiedPackage(name: String = "nearest-editable.comp"): File =
        File(directory, "copy-${System.nanoTime()}.comp").also { copyProjectAssets(assets, name, it) }
    fun rendered(editor: NativeEditor): ByteArray {
        val pixels = ByteBuffer.allocateDirect(editor.canvasWidth * editor.canvasHeight * 4)
        check(editor.draw(editor.canvasWidth, editor.canvasHeight, 0.0, 0.0,
            editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), pixels))
        return ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
    }
    fun sourcePixels(editor: NativeEditor, index: Int): ByteArray = checkNotNull(editor.captureScene()).use { scene ->
        val layer = scene.layerMetadata(index)
        check(layer.tileCount == 1)
        val pixels = ByteBuffer.allocateDirect(layer.pixelWidth * layer.pixelHeight * 4)
        scene.readTile(index, 0, layer.pixelWidth * 4, pixels)
        ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
    }
    fun assertMetadata(editor: NativeEditor) {
        val state = editor.state()
        check(editor.canvasWidth == metadata.getInt("width") && editor.canvasHeight == metadata.getInt("height"))
        check(state.document.uuid == metadata.getString("documentID"))
        check(state.document.resolution == metadata.getDouble("resolution"))
        val records = metadata.getJSONArray("layers")
        check(state.layers.size == records.length())
        val ids = state.layers.associate { it.uuid to it.id }
        check(state.document.activeLayerId == ids.getValue(metadata.getString("activeLayerID")))
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index); val layer = state.layers[index]
            check(layer.uuid == record.getString("id") && layer.name == record.getString("name"))
            check(layer.visible == record.getBoolean("isVisible") && layer.opacity == record.getDouble("opacity"))
            check(layer.blend == 0 && layer.sampling == 0 && layer.transform == projectTransform(record.getJSONObject("transform")))
            check(layer.isGroup == record.optBoolean("isGroup", false))
            check(layer.assetPresent == record.has("imageFile"))
            check(layer.parentId == record.optString("parentID").let { ids[it] ?: 0 })
            check(layer.maskSourceId == record.optString("maskSourceID").let { ids[it] ?: 0 })
            check(layer.hasMask == record.has("maskFile"))
            if (layer.hasMask) {
                check(layer.maskEnabled == record.getBoolean("maskEnabled") && layer.maskLinked == record.getBoolean("maskLinked"))
                check(layer.maskPlacement == if (record.has("maskPlacement")) projectTransform(record.getJSONObject("maskPlacement")) else null)
                check(layer.maskSampling == 0 && layer.maskWidth == 3 && layer.maskHeight == 2)
                val pixels = ByteBuffer.allocateDirect(6)
                editor.maskPixels(layer.id, 3, pixels)
                val actual = ByteArray(6).also { pixels.rewind(); pixels.get(it) }
                check(actual.contentEquals(byteArrayOf(0,1,17,63,-128,-1))) { "Mask coverage was color transformed" }
            }
            if (layer.assetPresent) check(layer.pixelWidth == 3 && layer.pixelHeight == 2)
        }
        val guides = metadata.getJSONArray("guides")
        check(state.document.guides.size == guides.length())
        for (index in 0 until guides.length()) {
            val record = guides.getJSONObject(index); val guide = state.document.guides[index]
            check(guide.uuid == record.getString("id") && guide.position == record.getDouble("position") &&
                guide.horizontal == (record.getString("axis") == "horizontal"))
        }
    }
    try {
        NativeEditor(64).use { old ->
            val checkpoint = File(directory, "old.checkpoint")
            old.save(checkpoint)
            val oldState = old.state(); val oldImage = rendered(old); val oldCheckpoint = checkpoint.readBytes()
            val resident = oldState.bytes + 1024 * 1024 // Includes conservative manifest/control/scratch caller overhead in this small fixture.
            fun unchanged() {
                check(old.state() == oldState && rendered(old).contentEquals(oldImage)) { "Candidate operation changed old editor" }
                check(checkpoint.readBytes().contentEquals(oldCheckpoint)) { "Candidate operation changed old checkpoint" }
            }
            val staging = copiedPackage()
            NativeEditor.loadProjectCandidate(context, staging, limit, resident).use { candidate ->
                assertMetadata(candidate)
                val baseline = candidate.state()
                check(baseline.revision == 0L && !baseline.undo && !baseline.redo)
                val golden = assets.open("rendered-project.json").use { JSONObject(it.reader().readText()) }
                    .getJSONArray("observations").getJSONObject(0).getJSONArray("expected").pixelBytes()
                compareProjectPixels(rendered(candidate), golden, 2, "Actual desktop complete 8×8 project golden")
                val source = sourcePixels(candidate, 1)
                val base = baseline.layers[1]
                candidate.startStroke(base.id, 1.5f, false)
                candidate.samples(doubleArrayOf(1.5,1.5,1.0,0.0))
                candidate.endStroke(false)
                check(candidate.state().undo && !sourcePixels(candidate, 1).contentEquals(source))
                candidate.undo(false)
                check(candidate.state().layers == baseline.layers && candidate.state().document == baseline.document)
                check(sourcePixels(candidate, 1).contentEquals(source))
                candidate.setOpacity(base.id, 0.5123456789012345)
                val saved = candidate.state(); val savedImage = rendered(candidate)
                val recoveredFile = File(directory, "candidate.checkpoint")
                check(candidate.save(recoveredFile) == saved.revision)
                NativeEditor.load(recoveredFile).use { recovered ->
                    val state = recovered.state()
                    check(state.layers == saved.layers && state.document == saved.document && state.revision == saved.revision)
                    check(!state.undo && !state.redo && rendered(recovered).contentEquals(savedImage))
                }
                checkNotNull(candidate.captureScene()).use { retained ->
                    candidate.close(); candidate.close()
                    check(candidate.state().layers.isEmpty())
                    val pixels = ByteBuffer.allocateDirect(8 * 8 * 4)
                    retained.draw(8, 8, Viewport(0.0,0.0,8.0,8.0), pixels)
                    val bytes = ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
                    check(bytes.contentEquals(savedImage)) { "Candidate destruction invalidated retained scene" }
                }
            }
            unchanged()
            val v14 = copiedPackage("raster-editable.comp")
            check(JSONObject(File(v14, "manifest.json").readText()).getInt("version") == 14)
            NativeEditor.loadProjectCandidate(context, v14, limit, resident).use { candidate ->
                assertMetadata(candidate)
                val state = candidate.state(); val pixels = rendered(candidate)
                check(state.revision == 0L && !state.undo && !state.redo)
                val golden = assets.open("rendered-project.json").use { JSONObject(it.reader().readText()) }
                    .getJSONArray("observations").getJSONObject(0).getJSONArray("expected").pixelBytes()
                compareProjectPixels(pixels, golden, 2, "Actual desktop v14 complete 8×8 project golden")
                val exported = File(directory, "v14-roundtrip.comp")
                candidate.captureProjectSnapshot().use { snapshot ->
                    NativeProjectWriteControl(limit, resident).use { control ->
                        check(snapshot.write(context, exported, false, control).successful)
                    }
                }
                check(JSONObject(File(exported, "manifest.json").readText()).getInt("version") == 17)
                NativeEditor.loadProjectCandidate(context, exported, limit, resident).use { restored ->
                    check(restored.state().document == state.document && restored.state().layers == state.layers)
                    check(rendered(restored).contentEquals(pixels))
                }
            }
            unchanged()
            val ledger = ImageIoAdmission.ledger
            val beforeHandoff = ledger.snapshot()
            val segment = 32L * 1024 * 1024
            check(beforeHandoff.availableBytes > segment)
            ledger.reserve(beforeHandoff.availableBytes - segment, "candidate handoff competing owner").use {
                val held = ledger.snapshot()
                NativeEditor.loadProjectCandidate(context, staging, limit, resident).use { candidate ->
                    check(candidate.resourceLedger === ledger)
                    check(ledger.snapshot().reservedBytes == held.reservedBytes + candidate.installedDocumentBytes())
                    check(ledger.snapshot().reservations == held.reservations + 1)
                    assertMetadata(candidate)
                    candidate.setOpacity(candidate.state().layers[1].id, .75)
                    candidate.undo(false)
                    assertMetadata(candidate)
                }
                check(ledger.snapshot().reservedBytes == held.reservedBytes &&
                    ledger.snapshot().reservations == held.reservations)
                try {
                    NativeEditor.loadCancelledProjectCandidateForTest(context, staging, limit, resident).close()
                    error("Cancelled candidate adopted its claim")
                } catch (error: ProjectImportException) { check(error.status == 9) }
                check(ledger.snapshot().reservedBytes == held.reservedBytes &&
                    ledger.snapshot().reservations == held.reservations)
            }
            check(ledger.snapshot().reservedBytes == beforeHandoff.reservedBytes &&
                ledger.snapshot().reservations == beforeHandoff.reservations)
            unchanged()
            // Each source fixture goes through the package loader and shared codec, never Android Bitmap.
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                if (fixture.getBoolean("mask") || fixture.getString("sharedLimitedCodecExpectation") != "decode") continue
                val packageDirectory = copiedPackage()
                assets.open(fixture.getString("file")).use { input ->
                    File(packageDirectory, "images/$imageId.png").outputStream().use { input.copyTo(it) }
                }
                NativeEditor.loadProjectCandidate(context, packageDirectory, limit, resident).use { candidate ->
                    assertMetadata(candidate)
                    compareProjectPixels(sourcePixels(candidate, 1), fixture.getJSONArray("expectedPixels").pixelBytes(),
                        1, "Apple PNG source ${fixture.getString("name")}")
                }
                unchanged()
            }
            fun fails(status: Int, packageDirectory: File = copiedPackage(), budget: Long = limit, cancelled: Boolean = false) {
                try {
                    val candidate = if (cancelled) NativeEditor.loadCancelledProjectCandidateForTest(context, packageDirectory, budget, resident)
                        else NativeEditor.loadProjectCandidate(context, packageDirectory, budget, resident)
                    candidate.close(); error("Invalid project candidate was accepted")
                } catch (error: ProjectImportException) { check(error.status == status) { "Expected status $status, got ${error.status}" } }
                unchanged()
            }
            fails(9, cancelled = true)
            fails(3, budget = resident + 16)
            fails(5, copiedPackage("nearest-metadata.comp"))
            for (smoothing in listOf(true, false)) {
                val shape = copiedPackage("shape-smoothing-$smoothing.comp")
                val manifest = JSONObject(File(shape, "manifest.json").readText())
                check(manifest.getInt("version") == 14)
                val layers = manifest.getJSONArray("layers")
                check((0 until layers.length()).any { index ->
                    layers.getJSONObject(index).optJSONObject("shape")?.getBoolean("smoothing") == smoothing
                })
                val before = ledger.snapshot()
                fails(5, shape)
                check(ledger.snapshot().reservedBytes == before.reservedBytes &&
                    ledger.snapshot().reservations == before.reservations)
            }
            val future = copiedPackage("raster-editable.comp")
            val futureManifest = File(future, "manifest.json")
            futureManifest.writeText(JSONObject(futureManifest.readText()).put("version", 22).toString())
            fails(5, future)
            val corrupt = copiedPackage()
            val lastAsset = File(corrupt, "images/$placedId.mask.png")
            val bytes = lastAsset.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); lastAsset.writeBytes(bytes)
            fails(8, corrupt)
            val unsupported = copiedPackage()
            assets.open("assets/p3_cicp_without_icc_rgba8.png").use { input ->
                File(unsupported, "images/$placedId.png").outputStream().use { input.copyTo(it) }
            }
            fails(5, unsupported)
            for (path in listOf(File("content://documents/nearest.comp"), File("/tmp/nearest.comp"))) {
                try { NativeEditor.loadProjectCandidate(context, path, limit, resident).close(); error("Non-app staging was accepted") }
                catch (_: IllegalArgumentException) { unchanged() }
            }
            val alias = File(directory, "symbolic.comp")
            Os.symlink(staging.absolutePath, alias.absolutePath)
            try { NativeEditor.loadProjectCandidate(context, alias, limit, resident).close(); error("Symbolic staging was accepted") }
            catch (_: IllegalArgumentException) { unchanged() }
        }
        return "Project candidate passed: real desktop PNG source/color/Adam7 and 8×8 render goldens, exact metadata/UUID/guides/active/relationships/nil placement/raw mask, v14 raster reads and writes13 with exact state/pixels, edit+undo/v3 recovery/retained scene lifetime; shape smoothing true/false and Android-unsupported v16, cancellation/budget/late corrupt asset/cICP-only/large blank reject with null output and old editor/checkpoint unchanged; app-owned staging only, no workspace replacement or SAF UI"
    } finally { directory.deleteRecursively() }
}

internal fun copyProjectAssets(assets: AssetManager, source: String, target: File) {
    check(target.mkdirs())
    for (name in checkNotNull(assets.list(source))) {
        val path = "$source/$name"; val children = assets.list(path)
        if (!children.isNullOrEmpty()) copyProjectAssets(assets, path, File(target, name))
        else assets.open(path).use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
    }
}
private fun projectTransform(record: JSONObject): Transform64 {
    val origin = record.getJSONArray("origin"); val size = record.getJSONArray("size")
    check(record.getString("sampling") == "Nearest")
    return Transform64(origin.getDouble(0), origin.getDouble(1), size.getDouble(0), size.getDouble(1),
        record.getDouble("rotation"), record.getBoolean("flipX"), record.getBoolean("flipY"))
}
private fun JSONArray.pixelBytes(): ByteArray = ByteArray(length()) { getInt(it).toByte() }
private fun compareProjectPixels(actual: ByteArray, expected: ByteArray, tolerance: Int, label: String) {
    check(actual.size == expected.size)
    val error = actual.indices.maxOf { abs((actual[it].toInt() and 255) - (expected[it].toInt() and 255)) }
    check(error <= tolerance) { "$label differs by $error channel bytes (limit $tolerance)" }
}
