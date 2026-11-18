package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Six independent color cells check CPU and actual Surface GLES pixels at a fixed viewport/LOD. */
internal fun moveInspectorPixelsSmoke(instrumentation: Instrumentation): String {
    MoveInspectorFixtureSmoke(instrumentation, "moveInspectorPixels").use { fixture ->
        with(fixture) {
            val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(),
                0xffffff00.toInt(), 0xffff00ff.toInt(), 0xff00ffff.toInt())
            val expectedSource = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1,
                -1, -1, 0, -1, -1, 0, -1, -1, 0, -1, -1, -1)
            data class ViewportStamp(val width: Int, val height: Int, val scale: Double, val x: Double, val y: Double)
            fun viewport(): ViewportStamp {
                stableBounds()
                var value: ViewportStamp? = null
                instrumentation.runOnMainSync {
                    // Read viewport offset with an axis-aligned unit box, never a rotated or flipped production point.
                    val origin = canvas.moveOverlay(Transform64(0.0, 0.0, 1.0, 1.0)).handles[0]
                    value = ViewportStamp(canvas.width, canvas.height, canvas.zoomPercent / 100, origin.x, origin.y)
                }
                return checkNotNull(value)
            }
            fun stats(): GpuStats {
                val latch = CountDownLatch(1); var result: GpuStats? = null
                canvas.debugGpuStats { result = it; latch.countDown() }
                check(latch.await(10, TimeUnit.SECONDS)) { "Inspector GPU stats did not fence" }
                return checkNotNull(result)
            }
            fun generation(box: Transform64): Long = editor.serialized {
                checkNotNull(editor.captureDisplayScene()).use {
                    val layer = it.layerMetadata(0)
                    check(layer.id == editor.state().layers.single().id && layer.transform == box && layer.sampling == 0)
                    it.generation()
                }
            }
            fun frameAfter(previous: GpuStats, box: Transform64, fixed: ViewportStamp): GpuStats {
                check(viewport() == fixed) { "Warm cohort viewport changed before rendering $box" }
                val generation = generation(box)
                canvas.requestRender()
                var ready: GpuStats? = null
                waitUntil("Inspector target GLES preview generation $generation did not complete for $box") {
                    val current = stats()
                    if (current.currentContext && current.usesTiles && current.sceneGeneration == generation &&
                        current.completedFrames > previous.completedFrames) ready = current
                    ready != null
                }
                return checkNotNull(ready)
            }
            fun expectedPixels(box: Transform64, order: IntArray): ByteArray {
                check(box.rotation == 0.0 && box.width.toInt() % 3 == 0 && box.height.toInt() % 2 == 0)
                return ByteArray(256 * 256 * 4).also { bytes ->
                    for (y in box.y.toInt() until (box.y + box.height).toInt())
                        for (x in box.x.toInt() until (box.x + box.width).toInt()) {
                            val column = (x - box.x.toInt()) / (box.width.toInt() / 3)
                            val row = (y - box.y.toInt()) / (box.height.toInt() / 2)
                            val color = colors[order[row * 3 + column]]
                            val index = (y * 256 + x) * 4
                            bytes[index] = (color ushr 16).toByte(); bytes[index + 1] = (color ushr 8).toByte()
                            bytes[index + 2] = color.toByte(); bytes[index + 3] = (-1).toByte()
                        }
                }
            }
            fun witnesses(stage: String, box: Transform64, order: IntArray, gpu: GpuStats,
                fixed: ViewportStamp, sourceBefore: Pair<List<Long>, String>) {
                check(viewport() == fixed && generation(box) == gpu.sceneGeneration && source() == sourceBefore)
                val actual = pixels()
                val expected = if (box.rotation == 0.0) expectedPixels(box, order) else null
                val mismatch = expected?.let { actual.indices.firstOrNull { index -> actual[index] != it[index] } }
                val record = JSONObject().put("expectedBox", box.toString()).put("viewport", fixed.toString())
                    .put("cpuSHA256", moveInspectorSha(actual)).put("expectedSHA256", expected?.let(::moveInspectorSha) ?: JSONObject.NULL)
                    .put("firstCpuMismatchByte", mismatch ?: JSONObject.NULL).put("sourcePayloadIds", JSONArray(sourceBefore.first))
                    .put("sourceSHA256", sourceBefore.second).put("gpuBeforeCopy", gpu.toString()).put("passed", false)
                val bitmap = Bitmap.createBitmap(fixed.width, fixed.height, Bitmap.Config.ARGB_8888)
                val samples = JSONArray()
                record.put("samples", samples)
                try {
                    check(mismatch == null) { "$stage independent complete CPU raster differs at byte $mismatch" }
                    check(minOf(box.width / 6, box.height / 4) > 3 && minOf(box.width / 6, box.height / 4) * fixed.scale > 4)
                    val latch = CountDownLatch(1); var result = PixelCopy.ERROR_UNKNOWN
                    instrumentation.runOnMainSync {
                        PixelCopy.request(canvas, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    check(latch.await(10, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Inspector Surface PixelCopy failed: $result" }
                    for (row in 0..1) for (column in 0..2) {
                        // Probe the rotated DISPLAY-local grid. Flip affects the frozen color order, not these points.
                        val angle = box.rotation * Math.PI / 180
                        val localX = ((column + .5) / 3 - .5) * box.width
                        val localY = ((row + .5) / 2 - .5) * box.height
                        val documentX = box.x + box.width / 2 + localX * cos(angle) - localY * sin(angle)
                        val documentY = box.y + box.height / 2 + localX * sin(angle) + localY * cos(angle)
                        val cpuX = floor(documentX).toInt(); val cpuY = floor(documentY).toInt()
                        val x = (fixed.x + documentX * fixed.scale).roundToInt()
                        val y = (fixed.y + documentY * fixed.scale).roundToInt()
                        check(cpuX in 1 until 255 && cpuY in 1 until 255 &&
                            x in 1 until bitmap.width - 1 && y in 1 until bitmap.height - 1)
                        val color = colors[order[row * 3 + column]]
                        samples.put(JSONObject().put("column", column).put("row", row).put("x", x).put("y", y)
                            .put("documentX", documentX).put("documentY", documentY).put("cpuX", cpuX).put("cpuY", cpuY)
                            .put("expectedARGB", color).put("actualARGB", bitmap.getPixel(x, y)))
                        val shifts = intArrayOf(16, 8, 0, 24)
                        for (dy in -1..1) for (dx in -1..1) {
                            val cpuIndex = ((cpuY + dy) * 256 + cpuX + dx) * 4
                            for (channel in 0..3) check((actual[cpuIndex + channel].toInt() and 255) ==
                                (color ushr shifts[channel] and 255)) { "$stage independent CPU interior cell($column,$row) differs" }
                            val pixel = bitmap.getPixel(x + dx, y + dy)
                            for (shift in shifts) check(abs((pixel ushr shift and 255) - (color ushr shift and 255)) <= 1) {
                                "$stage GLES cell($column,$row) at($x,$y) expected $color, actual $pixel"
                            }
                        }
                    }
                    val after = stats()
                    record.put("gpuAfterCopy", after.toString())
                    check(after.currentContext && after.usesTiles && after.sceneGeneration == gpu.sceneGeneration &&
                        after.completedFrames >= gpu.completedFrames && after.uploads == gpu.uploads && after.deletes == gpu.deletes &&
                        viewport() == fixed && generation(box) == gpu.sceneGeneration && source() == sourceBefore) {
                        "Target preview, viewport or source tiles changed across PixelCopy"
                    }
                    record.put("passed", true)
                } finally { bitmap.recycle(); observation(stage, record) }
            }
            try {
                for (rotation in listOf(0.0, 37.0)) {
                    val original = Transform64(64.0, 64.0, 96.0, 64.0, rotation)
                    install(3, 2, original)
                    val before = editor.state(); val sourceBefore = source()
                    check(sourceBefore.second == moveInspectorSha(expectedSource)) { "Six-color fixture source bytes differ" }
                    val flipViewport = viewport()
                    val warm = frameAfter(stats(), original, flipViewport)
                    check(warm.uploads > 0)
                    witnesses("r$rotation-base", original, intArrayOf(0, 1, 2, 3, 4, 5), warm, flipViewport, sourceBefore)
                    var previous = warm
                    var expected = original
                    val flips = listOf(
                        Triple("Flip Horizontal", original.copy(flipX = true), intArrayOf(2, 1, 0, 5, 4, 3)),
                        Triple("Flip Vertical", original.copy(flipX = true, flipY = true), intArrayOf(5, 4, 3, 2, 1, 0)),
                        Triple("Flip Horizontal", original.copy(flipY = true), intArrayOf(3, 4, 5, 0, 1, 2)),
                        Triple("Flip Vertical", original, intArrayOf(0, 1, 2, 3, 4, 5)))
                    var flipOwner: java.util.concurrent.CompletableFuture<TransformController.Terminal>? = null
                    for ((index, step) in flips.withIndex()) {
                        click(step.first); expected = step.second; preview(expected)
                        if (flipOwner == null) flipOwner = checkNotNull(editor.activeTransformCompletion())
                        check(editor.activeTransformCompletion() === flipOwner && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && source() == sourceBefore)
                        val current = frameAfter(previous, expected, flipViewport)
                        check(current.uploads == warm.uploads && current.deletes == warm.deletes) { "Local Flip reuploaded or evicted warm source tiles" }
                        witnesses("r$rotation-flip-$index", expected, step.third, current, flipViewport, sourceBefore); previous = current
                    }
                    key(KeyEvent.KEYCODE_ENTER); settled()
                    check(checkNotNull(flipOwner).get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == false && it.error == null })
                    check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && source() == sourceBefore)
                    check(focusScale().toDouble() == 3200.0)
                    // Focus/IME may relayout the surface; establish a separate cohort after that layout is stable.
                    val scaleViewport = viewport()
                    val scaleWarm = frameAfter(stats(), original, scaleViewport)
                    witnesses("r$rotation-scale-warm", original, intArrayOf(0, 1, 2, 3, 4, 5), scaleWarm, scaleViewport, sourceBefore)
                    scaleText("1600", focus = false); expected = Transform64(88.0, 80.0, 48.0, 32.0, rotation)
                    preview(expected)
                    val scaleOwner = checkNotNull(editor.activeTransformCompletion())
                    var current = frameAfter(scaleWarm, expected, scaleViewport)
                    check(current.uploads == scaleWarm.uploads && current.deletes == scaleWarm.deletes && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
                    witnesses("r$rotation-scale-1600", expected, intArrayOf(0, 1, 2, 3, 4, 5), current, scaleViewport, sourceBefore); previous = current
                    scaleText("3200", focus = false); preview(original)
                    check(editor.activeTransformCompletion() === scaleOwner && !scaleOwner.isDone && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null))
                    current = frameAfter(previous, original, scaleViewport)
                    check(current.uploads == scaleWarm.uploads && current.deletes == scaleWarm.deletes && source() == sourceBefore)
                    witnesses("r$rotation-scale-3200", original, intArrayOf(0, 1, 2, 3, 4, 5), current, scaleViewport, sourceBefore)
                    key(KeyEvent.KEYCODE_ENTER); settled()
                    check(scaleOwner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == false && it.error == null })
                    check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && source() == sourceBefore)
                }
                successful = true
            } catch (error: Throwable) { diagnose(error); throw error }
        }
    }
    return "Independent 3x2 six-color full axis CPU rasters and 0/37-degree forward-local CPU/GLES 3x3 interior witnesses for H/HV/V/identity and 1600→3200 Scale; separate fixed-viewport warm cohorts completed exact preview generations without additional uploads/deletes; immutable source payload/hash and no-op history/redo preserved. Full rotated Mac golden/edges, masks, context-loss and physical performance remain separate acceptance."
}
