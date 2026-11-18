package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Uses real JNI, immutable snapshots, PNG export/import and recovery rather than a managed mock. */
internal fun lineShapeNativeSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val scratch = File(context.cacheDir.canonicalFile, "line-v15-${System.nanoTime()}").apply { check(mkdir()) }
    val ledgerBefore = ImageIoAdmission.ledger.snapshot().reservedBytes
    var cases = 0
    fun pixels(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(64 * 64 * 4)
        check(editor.draw(64, 64, 0.0, 0.0, 64.0, 64.0, output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun export(editor: NativeEditor, target: File) {
        editor.captureProjectSnapshot().use { snapshot ->
            NativeProjectWriteControl(256L * 1024 * 1024, editor.retainedMemory().totalBytes).use { control ->
                check(snapshot.write(context, target, false, control).successful)
            }
        }
    }
    try {
        for (style in ShapeLineStyle.entries) for (cap in ShapeLineCap.entries) {
            NativeEditor(64, addDefaultLayers = false).use { editor ->
                val draft = shapeDraft(47.0, 38.0, 10.0, 12.0, ShapeKind.LINE, false, false,
                    3.5, style, cap)
                val spec = checkNotNull(draft.spec(0xff319be7.toInt(), 0.0, 3.5))
                val id = editor.createShape(spec, draft.x, draft.y)
                val metadata = checkNotNull(editor.lineShape(id))
                check(metadata.style == style.wireValue && metadata.cap == cap.wireValue)
                check(metadata.lineWidth == 3.5 && metadata.startX == spec.startX && metadata.endY == spec.endY)
                check(metadata.red == 49 / 255.0 && metadata.green == 155 / 255.0 && metadata.blue == 231 / 255.0)
                val original = pixels(editor)
                check(original.any { it.toInt() != 0 }) { "Empty line raster: $style/$cap" }
                editor.undo(false); check(editor.state().layers.isEmpty())
                editor.undo(true); check(editor.lineShape(id) == metadata && pixels(editor).contentEquals(original))
                val checkpoint = File(scratch, "line-$cases.checkpoint")
                editor.captureCheckpointSnapshot().use { check(it.write(checkpoint).successful) }
                NativeEditor.load(checkpoint).use { restored ->
                    check(restored.lineShape(id) == metadata && pixels(restored).contentEquals(original))
                }
                val project = File(scratch, "line-$cases.comp")
                export(editor, project)
                val retained = File(context.filesDir, "p77-line-roundtrip/line-$cases.comp")
                check(!retained.exists() || retained.deleteRecursively())
                check(project.copyRecursively(retained))
                val json = JSONObject(File(project, "manifest.json").readText())
                check(json.getInt("version") == 17)
                val shape = json.getJSONArray("layers").getJSONObject(0).getJSONObject("shape")
                check(shape.has("lineStyle") == (style != ShapeLineStyle.SOLID))
                check(shape.has("lineCap") == (cap != ShapeLineCap.ROUND))
                NativeEditor.loadProjectCandidate(context, project, 256L * 1024 * 1024, 0).use { loaded ->
                    val loadedId = loaded.state().layers.single().id
                    check(loaded.lineShape(loadedId) == metadata && pixels(loaded).contentEquals(original))
                }
                val initial = editor.state().layers.single().transform
                val moved = initial.copy(x = initial.x + 1, y = initial.y - 1, rotation = 17.0, flipX = true)
                editor.setTransform(id, moved)
                check(editor.lineShape(id) == metadata) { "Position/rotation/flip lost line metadata" }
                editor.setTransform(id, moved.copy(width = moved.width + 3))
                check(editor.lineShape(id) == null) { "Scaled line incorrectly remained an editable shape" }
                editor.undo(false); check(editor.lineShape(id) == metadata)
                editor.undo(false); check(pixels(editor).contentEquals(original))
                editor.createMask(id, true)
                editor.startStroke(id, 3f, erasing = true, mask = true)
                editor.samples(doubleArrayOf(47.5, 38.5, 1.0, 0.0)); editor.endStroke(false)
                check(editor.lineShape(id) == metadata) { "Mask paint discarded line metadata" }
                editor.deleteMask(id)
                editor.startStroke(id, 5f, erasing = true)
                editor.samples(doubleArrayOf(47.5, 38.5, 1.0, 0.0)); editor.endStroke(false)
                check(editor.lineShape(id) == null) { "Pixel edit kept stale line metadata" }
                editor.undo(false); check(editor.lineShape(id) == metadata)
                val beforeInvalid = editor.state()
                check(runCatching { editor.createShape(spec.copy(color = 0x80319be7.toInt()), draft.x, draft.y) }.isFailure)
                check(editor.state() == beforeInvalid) { "Rejected alpha changed document" }
                ++cases
            }
        }
        val fixtures = JSONObject(instrumentation.context.assets.open("expected-v15.json").bufferedReader().use { it.readText() })
            .getJSONArray("fixtures")
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val root = fixture.getString("path")
            val manifest = instrumentation.context.assets.open("$root/manifest.json").use { it.readBytes() }
            val assets = instrumentation.context.assets.list("$root/images").orEmpty().associateWith { name ->
                instrumentation.context.assets.open("$root/images/$name").use { it.readBytes() }
            }
            val source = MutableProjectTransportSource(manifest, assets)
            ProjectTransport(source, File(scratch, "staging"), ProjectTransportPolicy()) { Long.MAX_VALUE }
                .stage(source.tree, ProjectTransportCancellation()).use { staged ->
                    NativeEditor.loadProjectCandidate(context, staged.directory, 256L * 1024 * 1024, 0).use { loaded ->
                        val id = loaded.state().layers.single().id
                        val expectedShape = fixture.getJSONObject("metadata").getJSONArray("layers").getJSONObject(0).getJSONObject("shape")
                        val typed = checkNotNull(loaded.lineShape(id))
                        check(typed.red == expectedShape.getDouble("red") && typed.green == expectedShape.getDouble("green") &&
                            typed.blue == expectedShape.getDouble("blue")) { "Desktop RGB precision lost: $root" }
                        val expectedPixels = instrumentation.context.assets.open(fixture.getString("rgbaPath")).use { it.readBytes() }
                        fun sourcePixels(value: NativeEditor): ByteArray = checkNotNull(value.captureDisplayScene()).use { scene ->
                            val layer = scene.layerMetadata(0)
                            check(layer.tileCount == 1 && layer.pixelWidth == fixture.getInt("imageWidth") && layer.pixelHeight == fixture.getInt("imageHeight"))
                            val output = ByteBuffer.allocateDirect(expectedPixels.size)
                            scene.readTile(0, 0, layer.pixelWidth * 4, output)
                            ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
                        }
                        check(sourcePixels(loaded).contentEquals(expectedPixels)) { "Desktop PNG pixels changed at import: $root" }
                        loaded.renameLayer(id, "Android edited $root")
                        val exported = File(context.filesDir, "p77-line-roundtrip/desktop-$root")
                        check(!exported.exists() || exported.deleteRecursively())
                        export(loaded, exported)
                        val savedShape = JSONObject(File(exported, "manifest.json").readText()).getJSONArray("layers").getJSONObject(0).getJSONObject("shape")
                        val keys = expectedShape.keys().asSequence().toSet()
                        check(keys == savedShape.keys().asSequence().toSet()) { "Optional shape fields changed: $root" }
                        for (key in keys) {
                            val expected = expectedShape.get(key); val actual = savedShape.get(key)
                            check(if (expected is Number && actual is Number) expected.toDouble() == actual.toDouble()
                                else expected.toString() == actual.toString()) { "Desktop shape field $key changed: $root" }
                        }
                        NativeEditor.loadProjectCandidate(context, exported, 256L * 1024 * 1024, 0).use { reopened ->
                            check(reopened.lineShape(reopened.state().layers.single().id) == typed)
                            check(sourcePixels(reopened).contentEquals(expectedPixels)) { "Desktop source pixels changed after export: $root" }
                        }
                    }
                }
        }
        val result = JSONObject().put("passed", true).put("styleCapCases", cases).put("desktopFixtures", fixtures.length())
            .put("coverage", "JNI, fractional reverse geometry, double RGB, flags, undo/redo, checkpoint, PNG project roundtrip, transform invalidation, mask retention, pixel invalidation, alpha rejection")
        File(context.filesDir, "p77-line-native.json").writeText(result.toString(2))
        return "Line v15 native passed: $cases style/cap cases and ${fixtures.length()} desktop fixtures, exact project/checkpoint pixel and metadata roundtrip, history and invalidation."
    } finally {
        scratch.deleteRecursively()
        check(ImageIoAdmission.ledger.snapshot().reservedBytes == ledgerBefore) { "Line test leaked a resource claim" }
    }
}
