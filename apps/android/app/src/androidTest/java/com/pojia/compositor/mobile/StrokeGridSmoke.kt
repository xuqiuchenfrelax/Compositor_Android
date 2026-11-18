package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Independent coordinate/geometry expectations; continuous deposition is not a desktop stamp golden. */
internal fun strokeGridGpuSmoke(instrumentation: Instrumentation, recreateContext: () -> Unit): String {
    val context = instrumentation.targetContext
    val assets = instrumentation.context.assets
    val directory = File(context.cacheDir, "stroke-grid-${System.nanoTime()}").apply { check(mkdirs()) }
    var maximumDisplayDifference = 0
    fun scenePixels(scene: NativeScene, width: Int, height: Int, viewport: Viewport): ByteArray {
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        scene.draw(width, height, viewport, pixels)
        return ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
    }
    fun sourcePixels(scene: NativeScene, index: Int): ByteArray {
        val layer = scene.layerMetadata(index)
        val raster = scene.rasterViewMetadata(index)
        check(!raster.hasFill && !raster.hasSampledBase)
        val result = ByteArray(layer.pixelWidth * layer.pixelHeight * 4)
        for (tile in 0 until layer.tileCount) {
            val view = scene.tileViewMetadata(index, tile)
            val pixels = ByteBuffer.allocateDirect(view.width * view.height * 4)
            scene.readTile(index, tile, view.width * 4, pixels)
            for (y in 0 until view.height) for (x in 0 until view.width) for (channel in 0..3)
                result[((view.y + y) * layer.pixelWidth + view.x + x) * 4 + channel] =
                    pixels.get((y * view.width + x) * 4 + channel)
        }
        return result
    }
    fun display(renderer: GpuDisplayRenderer, scene: NativeScene, width: Int = 16, height: Int = 16) {
        val viewport = Viewport(0.0, 0.0, width.toDouble(), height.toDouble())
        val reference = scenePixels(scene, width, height, viewport)
        renderer.viewport = viewport; renderer.onSurfaceChanged(null, width, height); renderer.onDrawFrame(null)
        val actual = ByteBuffer.allocateDirect(reference.size)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        for (y in 0 until height) for (x in 0 until width) for (channel in 0..3) {
            val offset = (y * width + x) * 4
            val alpha = reference[offset + 3].toInt() and 255
            val checker = if ((x / 16 + (height - 1 - y) / 16) % 2 == 0) 0.19 else 0.24
            val expected = if (channel == 3) 255 else
                ((reference[offset + channel].toInt() and 255) + checker * (255 - alpha)).roundToInt()
            val value = actual.get(((height - 1 - y) * width + x) * 4 + channel).toInt() and 255
            maximumDisplayDifference = maxOf(maximumDisplayDifference, abs(value - expected))
            check(abs(value - expected) <= 1) { "Working/committed GLES mismatch at $x,$y/$channel: $value != $expected" }
        }
    }
    fun sameTransform(actual: Transform64, expected: Transform64) {
        check(actual.flipX == expected.flipX && actual.flipY == expected.flipY)
        check(actual.numbers().zip(expected.numbers()).all { (a, b) -> abs(a - b) <= 1e-10 }) {
            "Stroke geometry differs: $actual != $expected"
        }
    }
    fun fixture(name: String): File = File(directory, "$name.checkpoint").also { destination ->
        assets.open("stroke-grid/$name.checkpoint").use { input -> destination.outputStream().use { input.copyTo(it) } }
    }
    fun maskPixels(editor: NativeEditor, layer: LayerState): ByteArray {
        val bytes = ByteBuffer.allocateDirect(layer.maskWidth * layer.maskHeight)
        editor.maskPixels(layer.id, layer.maskWidth, bytes)
        return ByteArray(bytes.capacity()).also { bytes.rewind(); bytes.get(it) }
    }
    fun projectRoundtrip(editor: NativeEditor, stem: String, expectedCanvas: ByteArray,
        expectedMask: ByteArray? = null, expectedSource: ByteArray? = null) {
        editor.state().layers.filter { it.name.isEmpty() }.forEach { editor.renameLayer(it.id, "Stroke grid $stem") }
        val state = editor.state(); val target = File(directory, "$stem.comp")
        editor.captureProjectSnapshot().use { snapshot ->
            NativeProjectWriteControl(64L * 1024 * 1024, 1024 * 1024).use { control ->
                val result = snapshot.write(context, target, false, control)
                check(result.successful && result.capturedRevision == editor.currentRevision())
            }
        }
        NativeEditor.loadProjectCandidate(context, target, 64L * 1024 * 1024, 0).use { restored ->
            check(restored.state().layers == state.layers && restored.state().document == state.document)
            if (expectedMask != null) check(maskPixels(restored, restored.state().layers.single()).contentEquals(expectedMask))
            checkNotNull(restored.captureScene()).use { scene ->
                val viewport = Viewport(0.0, 0.0, restored.canvasWidth.toDouble(), restored.canvasHeight.toDouble())
                check(scenePixels(scene, restored.canvasWidth, restored.canvasHeight, viewport).contentEquals(expectedCanvas))
                if (expectedSource != null) check(sourcePixels(scene, 0).contentEquals(expectedSource))
            }
        }
        val layer = state.layers.single()
        checkNotNull(expectedSource)
        fun array(values: Iterable<Any>) = JSONArray().also { output -> values.forEach { output.put(it) } }
        fun transform(value: Transform64) = JSONObject().put("origin", array(listOf(value.x, value.y)))
            .put("size", array(listOf(value.width, value.height))).put("rotation", value.rotation)
            .put("flipX", value.flipX).put("flipY", value.flipY).put("sampling", "Nearest")
        val record = JSONObject().put("id", layer.uuid).put("name", layer.name).put("isVisible", layer.visible)
            .put("opacity", layer.opacity).put("blendMode", "Normal").put("transform", transform(layer.transform))
            .put("isGroup", false).put("imageFile", "${layer.uuid}.png")
        val expectedAssets = JSONArray()
        fun asset(mask: Boolean, width: Int, height: Int, pixels: ByteArray) {
            expectedAssets.put(JSONObject().put("layerID", layer.uuid).put("mask", mask).put("width", width)
                .put("height", height).put("pixels", array(pixels.map { it.toInt() and 255 })))
        }
        asset(false, layer.pixelWidth, layer.pixelHeight, expectedSource)
        if (expectedMask != null) {
            record.put("maskFile", "${layer.uuid}.mask.png").put("maskEnabled", layer.maskEnabled)
                .put("maskLinked", layer.maskLinked)
            layer.maskPlacement?.let { record.put("maskPlacement", transform(it)) }
            asset(true, layer.maskWidth, layer.maskHeight, expectedMask)
        }
        check(state.document.guides.isEmpty() && layer.parentId == 0L && layer.maskSourceId == 0L)
        val manifest = JSONObject().put("format", "com.compositor.project").put("version", 13)
            .put("documentID", state.document.uuid).put("width", editor.canvasWidth).put("height", editor.canvasHeight)
            .put("resolution", state.document.resolution).put("colorSpace", "sRGB").put("guides", JSONArray())
            .put("layers", JSONArray().put(record))
        if (state.document.activeLayerId != 0L) manifest.put("activeLayerID", layer.uuid)
        val readback = File(context.cacheDir, "android-stroke-grid-readback/$stem-${System.nanoTime()}").apply { check(mkdirs()) }
        check(target.copyRecursively(File(readback, "roundtrip.comp")))
        File(readback, "expected.json").writeText(JSONObject().put("manifest", manifest).put("assets", expectedAssets)
            .put("tolerance", 0).toString(), Charsets.UTF_8)
        File(readback, "expected-canvas.rgba").writeBytes(expectedCanvas)
    }
    try {
        check(StrokeAdmissionPolicy.calculate(0, 1024L * 1024 * 1024, 0) == StrokeAdmissionPolicy.DEFAULT_LIMIT_BYTES)
        val eightMiB = 8L * 1024 * 1024
        check(StrokeAdmissionPolicy.calculate(0, StrokeAdmissionPolicy.EXTERNAL_RESERVE + eightMiB, 0) == eightMiB)
        check(runCatching { StrokeAdmissionPolicy.calculate(0, StrokeAdmissionPolicy.EXTERNAL_RESERVE, 0) }
            .exceptionOrNull()?.message?.contains("reserved memory") == true)
        try { StrokeAdmissionPolicy.calculate(Long.MAX_VALUE, Long.MAX_VALUE, 0); error("Overflow admitted a stroke") }
        catch (_: ArithmeticException) { }

        NativeEditor(16, addDefaultLayers = false).use { editor ->
            editor.addLayer()
            val id = editor.state().layers.single().id
            val transform = Transform64(100.0, 100.0, 4.0, 4.0)
            editor.setTransform(id, transform)
            val original = editor.state().layers.single()
            val revision = editor.currentRevision()
            var notifications = 0; editor.committed = { notifications++ }
            val old = checkNotNull(editor.captureScene())
            val generation = old.generation()
            val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
            renderer.onSurfaceCreated(null, null)
            try {
                editor.startStroke(id, 3f, false)
                check(editor.currentRevision() == revision && editor.currentSceneGeneration() != generation)
                checkNotNull(editor.captureScene()).use { working ->
                    check(working.layerMetadata(0).pixelWidth == 104 && working.layerMetadata(0).pixelHeight == 104)
                    sameTransform(working.layerMetadata(0).transform, Transform64(0.0, 0.0, 104.0, 104.0))
                    check(working.metadata()[0] == revision && working.generation() == editor.currentSceneGeneration())
                    check(sourcePixels(working, 0).all { it == 0.toByte() }); display(renderer, working)
                }
                editor.endStroke(true)
                check(editor.state().layers.single() == original && editor.currentRevision() == revision && notifications == 0)
                check(editor.currentSceneGeneration() != generation && old.generation() == generation)
                editor.startStroke(id, 3f, false); editor.endStroke(false)
                check(editor.state().layers.single() == original && editor.currentRevision() == revision)
                editor.startStroke(id, 3f, false)
                editor.samples(doubleArrayOf(-20.0, -20.0, 1.0, 0.0)); editor.endStroke(false)
                check(editor.state().layers.single() == original && editor.currentRevision() == revision && notifications == 0)

                editor.startStroke(id, 3f, false)
                editor.samples(doubleArrayOf(8.5, 6.5, 1.0, 0.0))
                checkNotNull(editor.captureScene()).use { working ->
                    val before = scenePixels(working, 16, 16, Viewport(0.0, 0.0, 16.0, 16.0))
                    check(before[(6 * 16 + 8) * 4 + 3] != 0.toByte()) { "Moved absent source cannot receive canvas paint" }
                    display(renderer, working)
                    editor.endStroke(true)
                    check(scenePixels(working, 16, 16, Viewport(0.0, 0.0, 16.0, 16.0)).contentEquals(before))
                    checkNotNull(editor.captureScene()).use { cancelled -> display(renderer, cancelled) }
                }
                check(editor.state().layers.single() == original && editor.currentRevision() == revision && notifications == 0)

                editor.startStroke(id, 3f, true)
                editor.samples(doubleArrayOf(8.5, 6.5, 1.0, 0.0)); editor.endStroke(false)
                val materialized = editor.state().layers.single()
                check(materialized.assetPresent && materialized.pixelWidth == 104 && materialized.pixelHeight == 104)
                sameTransform(materialized.transform, Transform64(0.0, 0.0, 104.0, 104.0))
                check(notifications == 1 && editor.currentRevision() != revision)
                checkNotNull(editor.captureScene()).use { committed ->
                    val pixels = sourcePixels(committed, 0)
                    check(pixels.all { it == 0.toByte() }); display(renderer, committed)
                    val saved = File(directory, "blank-eraser.checkpoint")
                    editor.save(saved)
                    val header = ByteArray(12).also { DataInputStream(saved.inputStream()).use { input -> input.readFully(it) } }
                    check(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(8) == 5)
                    NativeEditor.load(saved).use { restored ->
                        check(restored.state().layers.single() == materialized)
                        checkNotNull(restored.captureScene()).use { scene ->
                            check(sourcePixels(scene, 0).contentEquals(pixels))
                            check(scene.rasterViewMetadata(0) == committed.rasterViewMetadata(0))
                        }
                    }
                    editor.undo(false)
                    check(editor.state().layers.single() == original)
                    check(sourcePixels(committed, 0).contentEquals(pixels))
                    editor.undo(true); check(editor.state().layers.single() == materialized)
                }
                recreateContext(); renderer.onSurfaceCreated(null, null)
                checkNotNull(editor.captureScene()).use { display(renderer, it) }
                check(old.layerMetadata(0).transform == transform && sourcePixels(old, 0).all { it == 0.toByte() })
                editor.close(); renderer.onDrawFrame(null)
                check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 0)
                check(old.layerMetadata(0).transform == transform && sourcePixels(old, 0).all { it == 0.toByte() })
            } finally { old.close() }
        }

        for (transform in listOf(Transform64(0.125, -0.375, 3.5, 2.5),
            Transform64(2.25, -1.5, 3.5, 2.5, 37.0, true, false))) {
            NativeEditor(16, addDefaultLayers = false).use { editor ->
                editor.addLayer(); val id = editor.state().layers.single().id
                editor.setTransform(id, transform)
                val original = editor.state().layers.single(); val revision = editor.currentRevision()
                val expected = strokeGridGeometry(transform, 4, 3, 16, 16)
                editor.startStroke(id, 3f, false)
                checkNotNull(editor.captureScene()).use { scene ->
                    val layer = scene.layerMetadata(0)
                    check(layer.pixelWidth == expected.width && layer.pixelHeight == expected.height)
                    sameTransform(layer.transform, expected.transform)
                }
                editor.endStroke(false)
                check(editor.state().layers.single() == original && editor.currentRevision() == revision)
            }
        }

        NativeEditor(16, addDefaultLayers = false).use { editor ->
            val source = ByteArray(5 * 3 * 4) { index ->
                val pixel = index / 4; val x = pixel % 5; val y = pixel / 5
                when (index % 4) { 0 -> (7 * x + 3 * y).toByte(); 1 -> (5 * x + 11 * y).toByte()
                    2 -> (13 * x + 17 * y).toByte(); else -> 255.toByte() }
            }
            val id = editor.importPixels(5, 3, ByteBuffer.allocateDirect(source.size).apply { put(source); rewind() })
            val transform = Transform64(20.0, 19.0, 5.0, 3.0)
            editor.setTransform(id, transform)
            val original = editor.state().layers.single(); val revision = editor.currentRevision()
            editor.startStroke(id, 3f, false)
            checkNotNull(editor.captureScene()).use { preview ->
                val layer = preview.layerMetadata(0)
                check(layer.pixelWidth == 25 && layer.pixelHeight == 22)
                sameTransform(layer.transform, Transform64(0.0, 0.0, 25.0, 22.0))
                val pixels = sourcePixels(preview, 0)
                for (y in 0 until 22) for (x in 0 until 25) for (channel in 0..3) {
                    val expected = if (x in 20..24 && y in 19..21) source[((y - 19) * 5 + x - 20) * 4 + channel] else 0.toByte()
                    check(pixels[(y * 25 + x) * 4 + channel] == expected)
                }
                editor.samples(doubleArrayOf(8.5, 6.5, 1.0, 0.0))
                checkNotNull(editor.captureScene()).use { painted ->
                    val changed = sourcePixels(painted, 0)
                    for (y in 19..21) for (x in 20..24) for (channel in 0..3)
                        check(changed[(y * 25 + x) * 4 + channel] == source[((y - 19) * 5 + x - 20) * 4 + channel])
                    editor.endStroke(false)
                    val committed = editor.state().layers.single()
                    val cropX = ((committed.transform.x - transform.x) / (transform.width / 5) + 20).roundToInt()
                    val cropY = ((committed.transform.y - transform.y) / (transform.height / 3) + 19).roundToInt()
                    checkNotNull(editor.captureScene()).use { scene ->
                        val bytes = sourcePixels(scene, 0)
                        for (y in 0 until committed.pixelHeight) for (x in 0 until committed.pixelWidth) for (channel in 0..3)
                            check(bytes[(y * committed.pixelWidth + x) * 4 + channel] == changed[((y + cropY) * 25 + x + cropX) * 4 + channel])
                    }
                    editor.undo(false); check(editor.state().layers.single() == original)
                    editor.undo(true); check(editor.state().layers.single() == committed)
                }
                editor.close()
                check(sourcePixels(preview, 0).contentEquals(pixels) && preview.metadata()[0] == revision)
            }
        }

        NativeEditor(16, addDefaultLayers = false).use { editor ->
            val id = editor.importPixels(20, 10, ByteBuffer.allocateDirect(20 * 10 * 4))
            val transform = Transform64(0.0, 0.0, 3.5, 2.5, 37.0, false, true)
            editor.setTransform(id, transform)
            val expected = strokeGridGeometry(transform, 20, 10, 16, 16)
            editor.startStroke(id, 3f, true)
            checkNotNull(editor.captureScene()).use { scene ->
                check(scene.layerMetadata(0).pixelWidth == expected.width && scene.layerMetadata(0).pixelHeight == expected.height)
                sameTransform(scene.layerMetadata(0).transform, expected.transform)
            }
            editor.samples(doubleArrayOf(1.75, 1.25, 1.0, 0.0)); editor.endStroke(false)
            val retained = editor.state().layers.single()
            check(retained.assetPresent && retained.pixelWidth == 20 && retained.pixelHeight == 10)
            sameTransform(retained.transform, transform)
            checkNotNull(editor.captureScene()).use { check(sourcePixels(it, 0).all { value -> value == 0.toByte() }) }
        }

        NativeEditor(16, addDefaultLayers = false).use { editor ->
            editor.addLayer(); val id = editor.state().layers.single().id
            editor.setTransform(id, Transform64(0.0, 0.0, 30000.49, 1.0))
            val original = editor.state()
            editor.startStroke(id, 3f, false)
            checkNotNull(editor.captureScene()).use { check(it.layerMetadata(0).pixelWidth == 30000) }
            editor.endStroke(false); check(editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null))
            editor.setTransform(id, Transform64(0.0, 0.0, 30000.5, 1.0))
            val rejected = editor.state()
            try { editor.startStroke(id, 3f, false); error("Rounded source beyond 30000 admitted") }
            catch (failure: IllegalStateException) { check(failure.message != "Rounded source beyond 30000 admitted") }
            check(editor.state() == rejected)
        }

        NativeEditor(16, addDefaultLayers = false).use { editor ->
            val group = editor.addGroup(); editor.setTransform(group, Transform64(500.0, 400.0, 800.0, 700.0, 30.0))
            editor.addLayer(); val id = editor.state().layers.last().id
            editor.reparentLayer(id, group); editor.setTransform(id, Transform64(100.0, 100.0, 4.0, 4.0))
            val original = editor.state().layers
            editor.startStroke(id, 3f, true)
            checkNotNull(editor.captureScene()).use { scene ->
                sameTransform(scene.layerMetadata(1).transform, Transform64(0.0, 0.0, 104.0, 104.0))
                check(scene.layerMetadata(0).transform == original[0].transform)
            }
            editor.samples(doubleArrayOf(8.5, 6.5, 1.0, 0.0)); editor.endStroke(false)
            check(editor.state().layers[0] == original[0])
            sameTransform(editor.state().layers[1].transform, Transform64(0.0, 0.0, 104.0, 104.0))
            editor.undo(false); check(editor.state().layers == original)
        }

        NativeEditor.load(fixture("implicit-black-seed")).use { editor ->
            check(editor.canvasWidth == 16 && editor.canvasHeight == 12)
            val original = editor.state().layers.single(); val id = original.id
            check(original.assetPresent && original.pixelWidth == 4 && original.pixelHeight == 4 &&
                original.transform == Transform64(5.0, 4.0, 4.0, 4.0) && original.hasMask &&
                original.maskWidth == 1 && original.maskHeight == 1 && original.maskPlacement == null &&
                original.maskEnabled && original.maskLinked && maskPixels(editor, original).contentEquals(byteArrayOf(0)))
            val revision = editor.currentRevision()
            val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
            renderer.onSurfaceCreated(null, null)
            editor.startStroke(id, 1f, false)
            checkNotNull(editor.captureScene()).use { emptyPreview ->
                check(emptyPreview.layerMetadata(0).transform == Transform64(0.0, 0.0, 16.0, 12.0))
                check(emptyPreview.layerStructure(0)[8] == 0L && emptyPreview.maskPlacement(0) == null)
                check(scenePixels(emptyPreview, 16, 12, Viewport(0.0, 0.0, 16.0, 12.0)).all { it == 0.toByte() })
                display(renderer, emptyPreview, 16, 12)
                editor.endStroke(true)
                check(editor.state().layers.single() == original && editor.currentRevision() == revision)
            }
            var notifications = 0; editor.committed = { notifications++ }
            editor.startStroke(id, 1f, false); editor.samples(doubleArrayOf(1.5, 1.5, 1.0, 0.0))
            checkNotNull(editor.captureScene()).use { painted ->
                val before = scenePixels(painted, 16, 12, Viewport(0.0, 0.0, 16.0, 12.0))
                check(before[(1 * 16 + 1) * 4 + 3] != 0.toByte())
                for (y in 4..7) for (x in 5..8) check(before[(y * 16 + x) * 4 + 3] == 0.toByte())
                display(renderer, painted, 16, 12)
                editor.endStroke(false)
                val committed = editor.state().layers.single()
                check(committed.pixelWidth == 8 && committed.pixelHeight == 7 &&
                    committed.transform == Transform64(1.0, 1.0, 8.0, 7.0) && committed.maskWidth == 8 &&
                    committed.maskHeight == 7 && committed.maskPlacement == null && committed.maskEnabled && committed.maskLinked)
                check(notifications == 1)
                val expectedMask = ByteArray(8 * 7) { 255.toByte() }
                for (y in 3..6) for (x in 4..7) expectedMask[y * 8 + x] = 0
                check(maskPixels(editor, committed).contentEquals(expectedMask)) { "Implicit black grid failed white expansion" }
                checkNotNull(editor.captureScene()).use { scene ->
                    val after = scenePixels(scene, 16, 12, Viewport(0.0, 0.0, 16.0, 12.0))
                    check(after.contentEquals(before)); display(renderer, scene, 16, 12)
                }
                val saved = File(directory, "implicit-black-roundtrip.checkpoint"); editor.save(saved)
                NativeEditor.load(saved).use { restored ->
                    check(restored.state().layers.single() == committed && maskPixels(restored, committed).contentEquals(expectedMask))
                    checkNotNull(restored.captureScene()).use { check(scenePixels(it, 16, 12,
                        Viewport(0.0, 0.0, 16.0, 12.0)).contentEquals(before)) }
                }
                editor.undo(false)
                check(editor.state().layers.single() == original && maskPixels(editor, original).contentEquals(byteArrayOf(0)))
                editor.undo(true); check(editor.state().layers.single() == committed)
                val committedSource = checkNotNull(editor.captureScene()).use { sourcePixels(it, 0) }
                for (y in 3..6) for (x in 4..7) {
                    val at = (y * 8 + x) * 4
                    check(committedSource[at] == 0.toByte() && committedSource[at + 1] == 0.toByte() &&
                        committedSource[at + 2] == 255.toByte() && committedSource[at + 3] == 255.toByte())
                }
                projectRoundtrip(editor, "implicit-black-crop", before, expectedMask, committedSource)
                recreateContext(); renderer.onSurfaceCreated(null, null); display(renderer, painted, 16, 12)
                editor.close(); renderer.onDrawFrame(null)
                check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 0)
                check(scenePixels(painted, 16, 12, Viewport(0.0, 0.0, 16.0, 12.0)).contentEquals(before))
            }
        }

        NativeEditor.load(fixture("implicit-black-expanded")).use { editor ->
            val layer = editor.state().layers.single()
            val expectedMask = assets.open("stroke-grid/implicit-black-expanded.mask").use { it.readBytes() }
            val expectedSource = assets.open("stroke-grid/implicit-black-expanded.source.rgba").use { it.readBytes() }
            val expectedCanvas = assets.open("stroke-grid/implicit-black-expanded.rgba").use { it.readBytes() }
            check(layer.maskPlacement == null && layer.maskWidth == 8 && layer.maskHeight == 7)
            check(maskPixels(editor, layer).contentEquals(expectedMask))
            checkNotNull(editor.captureScene()).use { scene ->
                check(sourcePixels(scene, 0).contentEquals(expectedSource))
                check(scenePixels(scene, 16, 12, Viewport(0.0, 0.0, 16.0, 12.0)).contentEquals(expectedCanvas))
                val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
                renderer.onSurfaceCreated(null, null); display(renderer, scene, 16, 12)
                editor.close(); renderer.onDrawFrame(null)
                check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 0 && renderer.fallbackBytes == 0)
                check(scenePixels(scene, 16, 12, Viewport(0.0, 0.0, 16.0, 12.0)).contentEquals(expectedCanvas))
            }
        }

        NativeEditor.load(fixture("implicit-black-seed")).use { editor ->
            val id = editor.state().layers.single().id
            val placement = Transform64(5.0, 4.0, 4.0, 4.0)
            editor.setMaskPlacement(id, placement)
            val original = editor.state().layers.single()
            editor.startStroke(id, 1f, false); editor.samples(doubleArrayOf(1.5, 1.5, 1.0, 0.0))
            checkNotNull(editor.captureScene()).use { check(it.maskPlacement(0) == placement) }
            editor.endStroke(false)
            val committed = editor.state().layers.single()
            check(committed.maskPlacement == placement && committed.maskWidth == 1 && committed.maskHeight == 1 &&
                maskPixels(editor, committed).contentEquals(byteArrayOf(0)))
            editor.undo(false); check(editor.state().layers.single() == original)
        }

        NativeEditor.load(fixture("transparent-base-seed")).use { editor ->
            val layer = editor.state().layers.single()
            check(editor.canvasWidth == 8 && editor.canvasHeight == 6 && layer.assetPresent &&
                layer.pixelWidth == 8 && layer.pixelHeight == 6 && !layer.hasMask && layer.parentId == 0L &&
                layer.maskSourceId == 0L && !layer.isGroup && layer.transform == Transform64(0.0, 0.0, 8.0, 6.0))
            val expected = ByteArray(8 * 6 * 4)
            for (y in 2..3) for (x in 2..4) {
                val sx = x - 2; val sy = y - 2; val at = (y * 8 + x) * 4
                expected[at] = (13 * sx + 7 * sy).toByte(); expected[at + 1] = (5 * sx + 19 * sy).toByte()
                expected[at + 2] = (3 * sx + 11 * sy).toByte(); expected[at + 3] = 255.toByte()
            }
            val retained = checkNotNull(editor.captureScene())
            try {
                check(retained.metadata()[4] == 0L && retained.layerMetadata(0).tileCount == 0)
                check(!retained.rasterViewMetadata(0).hasFill && retained.rasterViewMetadata(0).hasSampledBase)
                check(scenePixels(retained, 8, 6, Viewport(0.0, 0.0, 8.0, 6.0)).contentEquals(expected))
                val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
                renderer.onSurfaceCreated(null, null)
                display(renderer, retained, 8, 6)
                check(renderer.fallbackBytes > 0 && renderer.totalGpuBytes <= renderer.textureBudgetBytes)
                val saved = File(directory, "transparent-base-roundtrip.checkpoint"); editor.save(saved)
                NativeEditor.load(saved).use { restored ->
                    check(restored.state().layers.single() == layer)
                    checkNotNull(restored.captureScene()).use { scene ->
                        check(!scene.rasterViewMetadata(0).hasFill && scene.rasterViewMetadata(0).hasSampledBase)
                        check(scenePixels(scene, 8, 6, Viewport(0.0, 0.0, 8.0, 6.0)).contentEquals(expected))
                    }
                }
                projectRoundtrip(editor, "transparent-base", expected, expectedSource = expected)
                renderer.setTextureBudget(64 * 1024); display(renderer, retained, 8, 6)
                check(renderer.fallbackBytes > 0 && renderer.totalGpuBytes <= renderer.textureBudgetBytes)
                recreateContext(); renderer.onSurfaceCreated(null, null); display(renderer, retained, 8, 6)
                check(renderer.fallbackBytes > 0 && renderer.totalGpuBytes <= renderer.textureBudgetBytes)
                editor.close(); renderer.onDrawFrame(null)
                check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 0 && renderer.fallbackBytes == 0)
                check(scenePixels(retained, 8, 6, Viewport(0.0, 0.0, 8.0, 6.0)).contentEquals(expected))
            } finally { retained.close() }
        }

        NativeEditor.load(fixture("sampled-crop-window")).use { editor ->
            val expected = assets.open("stroke-grid/sampled-crop-window.rgba").use { it.readBytes() }
            check(editor.canvasWidth == 4 && editor.canvasHeight == 5 && expected.size == 4 * 5 * 4)
            checkNotNull(editor.captureScene()).use { scene ->
                check(scene.rasterViewMetadata(0).hasSampledBase)
                check(scenePixels(scene, 4, 5, Viewport(0.0, 0.0, 4.0, 5.0)).contentEquals(expected))
                val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
                renderer.onSurfaceCreated(null, null); display(renderer, scene, 4, 5)
                check(renderer.fallbackBytes > 0)
                val saved = File(directory, "sampled-crop-roundtrip.checkpoint"); editor.save(saved)
                NativeEditor.load(saved).use { restored ->
                    checkNotNull(restored.captureScene()).use { check(scenePixels(it, 4, 5,
                        Viewport(0.0, 0.0, 4.0, 5.0)).contentEquals(expected)) }
                }
                projectRoundtrip(editor, "sampled-crop-window", expected, expectedSource = expected)
                editor.close(); renderer.onDrawFrame(null)
                check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 0 && renderer.fallbackBytes == 0)
                check(scenePixels(scene, 4, 5, Viewport(0.0, 0.0, 4.0, 5.0)).contentEquals(expected))
            }
        }

        NativeEditor(16, addDefaultLayers = false).use { editor ->
            val id = editor.importPixels(4, 4, ByteBuffer.allocateDirect(4 * 4 * 4))
            editor.setTransform(id, Transform64(100.0, 100.0, 4.0, 4.0)); editor.createMask(id, true)
            val original = editor.state().layers.single(); val revision = editor.currentRevision()
            editor.startStroke(id, 1f, false, mask = true)
            checkNotNull(editor.captureScene()).use { scene ->
                check(scene.layerMetadata(0).transform == original.transform)
                check(scene.maskPlacement(0) == Transform64(0.0, 0.0, 104.0, 104.0))
            }
            editor.endStroke(true); check(editor.state().layers.single() == original && editor.currentRevision() == revision)
            editor.startStroke(id, 1f, false, mask = true); editor.samples(doubleArrayOf(8.5, 6.5, 1.0, 0.0))
            editor.endStroke(false)
            val black = editor.state().layers.single()
            check(black.transform == original.transform && black.pixelWidth == 4 && black.pixelHeight == 4 &&
                black.maskPlacement == Transform64(0.0, 0.0, 104.0, 104.0) && black.maskWidth == 104 && black.maskHeight == 104)
            val bytes = maskPixels(editor, black)
            check(bytes[6 * 104 + 8] == 0.toByte() && bytes[100 * 104 + 100] == 255.toByte())
            editor.startStroke(id, 1f, true, mask = true); editor.samples(doubleArrayOf(8.5, 6.5, 1.0, 0.0))
            editor.endStroke(false)
            val erased = editor.state().layers.single()
            check(erased.transform == original.transform && erased.maskPlacement == black.maskPlacement &&
                maskPixels(editor, erased)[6 * 104 + 8] == 255.toByte())
            editor.undo(false); check(editor.state().layers.single() == black && maskPixels(editor, black).contentEquals(bytes))
            editor.undo(false); check(editor.state().layers.single() == original)
            editor.undo(true); check(editor.state().layers.single() == black)
            val saved = File(directory, "mask-growth.checkpoint"); editor.save(saved)
            NativeEditor.load(saved).use { check(it.state().layers.single() == black && maskPixels(it, black).contentEquals(bytes)) }
        }

        NativeEditor(2048, addDefaultLayers = false).use { editor ->
            editor.addLayer(); val original = editor.state(); val id = original.layers.single().id
            val retained = editor.retainedMemory(); val generation = editor.currentSceneGeneration()
            try { editor.startStroke(id, 512f, false, transientLimitBytes = 1); error("One-byte constructor admitted stroke state") }
            catch (error: NativeCommandException) { check(error.status == 3) }
            check(editor.state() == original && editor.currentSceneGeneration() == generation)
            editor.startStroke(id, 512f, false, transientLimitBytes = eightMiB)
            try { editor.samples(doubleArrayOf(10.0, 10.0, 1.0, 0.0, 2038.0, 2038.0, 1.0, 1.0)); error("Bounded append admitted excess growth") }
            catch (error: NativeCommandException) { check(error.status == 3) }
            editor.endStroke(true)
            check(editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null) && editor.retainedMemory() == retained)
            editor.startStroke(id, 3f, false); editor.endStroke(true)
            check(editor.state().copy(selectedLayerActions = null) == original.copy(selectedLayerActions = null))
            editor.createMask(id, true)
            val masked = editor.state(); val maskRetained = editor.retainedMemory()
            try { editor.startStroke(id, 512f, false, mask = true, transientLimitBytes = 1); error("One-byte mask begin admitted") }
            catch (error: NativeCommandException) { check(error.status == 3) }
            editor.startStroke(id, 512f, false, mask = true, transientLimitBytes = eightMiB)
            try { editor.samples(doubleArrayOf(10.0, 10.0, 1.0, 0.0, 2038.0, 2038.0, 1.0, 1.0)); error("Bounded mask append admitted excess growth") }
            catch (error: NativeCommandException) { check(error.status == 3) }
            editor.endStroke(true)
            check(editor.state().copy(selectedLayerActions = null) == masked.copy(selectedLayerActions = null) && editor.retainedMemory() == maskRetained)
            editor.startStroke(id, 3f, true, mask = true); editor.endStroke(true)
            check(editor.state().copy(selectedLayerActions = null) == masked.copy(selectedLayerActions = null))
        }
        return "Stroke grid: shifted working preview/cancel/no samples/off-canvas, independent fractional/rotated/flip geometry, patterned source coordinates retained outside canvas, transparent eraser materialization/one undo/v5 restore, generation separated from revision, implicit black differing-grid atomic source+mask crop, explicit mask placement independence, mask growth+bounded eraser+history, plain transparent-fill sampled-base CPU fallback, bounded image/mask constructor+append abort and actual GLES/EGL passed; display maximumByteDifference=$maximumDisplayDifference (acceptance1). Continuous brush bytes are not a desktop CPU stamp golden; these fixtures do not establish complete desktop mask sampling parity."
    } finally { directory.deleteRecursively() }
}

private data class StrokeGridGeometry(val width: Int, val height: Int, val transform: Transform64)

/** Canvas corners and source-to-document mapping are computed independently of native brush code. */
private fun strokeGridGeometry(t: Transform64, sourceWidth: Int, sourceHeight: Int, canvasWidth: Int, canvasHeight: Int): StrokeGridGeometry {
    val radians = t.rotation * Math.PI / 180; val c = cos(radians); val s = sin(radians)
    val scaleX = t.width / sourceWidth * if (t.flipX) -1 else 1
    val scaleY = t.height / sourceHeight * if (t.flipY) -1 else 1
    val cx = t.x + t.width / 2; val cy = t.y + t.height / 2
    val corners = listOf(0.0 to 0.0, canvasWidth.toDouble() to 0.0, 0.0 to canvasHeight.toDouble(),
        canvasWidth.toDouble() to canvasHeight.toDouble()).map { (x, y) ->
        ((x - cx) * c + (y - cy) * s) / scaleX + sourceWidth / 2.0 to
            (-(x - cx) * s + (y - cy) * c) / scaleY + sourceHeight / 2.0
    }
    val x0 = minOf(0.0, floor(corners.minOf { it.first })); val y0 = minOf(0.0, floor(corners.minOf { it.second }))
    val x1 = maxOf(sourceWidth.toDouble(), ceil(corners.maxOf { it.first }))
    val y1 = maxOf(sourceHeight.toDouble(), ceil(corners.maxOf { it.second }))
    val width = (x1 - x0).toInt(); val height = (y1 - y0).toInt()
    val centerX = ((x0 + x1) / 2 - sourceWidth / 2.0) * scaleX
    val centerY = ((y0 + y1) / 2 - sourceHeight / 2.0) * scaleY
    val w = width * t.width / sourceWidth; val h = height * t.height / sourceHeight
    return StrokeGridGeometry(width, height, t.copy(x = cx + centerX * c - centerY * s - w / 2,
        y = cy + centerX * s + centerY * c - h / 2, width = w, height = h))
}
