package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Independent channel expectations exercise settings through the shipping JNI entry point. */
internal fun brushSettingsSmoke(instrumentation: Instrumentation): String {
    fun image(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
        check(editor.draw(32, 32, 0.0, 0.0, 32.0, 32.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun sample(editor: NativeEditor) = editor.samples(doubleArrayOf(16.5, 16.5, 1.0, 0.0))
    fun channel(bytes: ByteArray, x: Int, y: Int, channel: Int) = bytes[(y * 32 + x) * 4 + channel].toInt() and 255
    fun color(settings: StrokeSettings, expected: IntArray): ByteArray {
        NativeEditor(32, addDefaultLayers = false).use { editor ->
            editor.addLayer()
            val id = editor.state().layers.single().id
            val before = image(editor)
            val revision = editor.currentRevision()
            editor.startStroke(id, settings); sample(editor)
            check(editor.currentRevision() == revision) { "Preview modified committed revision" }
            editor.endStroke(true)
            check(image(editor).contentEquals(before) && editor.currentRevision() == revision) { "Cancel changed pixels or revision" }
            editor.startStroke(id, settings); sample(editor); editor.endStroke(false)
            val painted = image(editor)
            for (c in 0..3) check(abs(channel(painted, 16, 16, c) - expected[c]) <= 1) {
                "Configured color/opacity failed: channel $c expected ${expected[c]}, actual ${channel(painted, 16, 16, c)}"
            }
            editor.undo(false)
            check(image(editor).contentEquals(before)) { "One undo did not restore the complete stroke" }
            editor.undo(true)
            check(image(editor).contentEquals(painted)) { "Redo changed configured stroke pixels" }
            val checkpoint = File(instrumentation.targetContext.cacheDir, "brush-settings-${System.nanoTime()}.checkpoint")
            try {
                editor.save(checkpoint)
                NativeEditor.load(checkpoint).use { check(image(it).contentEquals(painted)) { "Checkpoint changed configured pixels" } }
            } finally { checkpoint.delete() }
            return painted
        }
    }
    val red = color(StrokeSettings(diameter = 8f, opacity = .25f, red = 1f), intArrayOf(64, 0, 0, 64))
    color(StrokeSettings(diameter = 8f, opacity = .5f, green = 1f), intArrayOf(0, 128, 0, 128))
    color(StrokeSettings(diameter = 8f, blue = 1f), intArrayOf(0, 0, 255, 255))
    val soft = color(StrokeSettings(diameter = 8f, hardness = 0f, opacity = .25f, red = 1f), intArrayOf(64, 0, 0, 64))
    check(!soft.contentEquals(red)) { "Hardness was ignored by the JNI settings path" }

    for (white in listOf(false, true)) for (erasing in listOf(false, true)) {
        NativeEditor(32, addDefaultLayers = false).use { editor ->
            editor.addLayer(); val id = editor.state().layers.single().id
            editor.createMask(id, !white)
            fun mask(): ByteArray {
                val layer = editor.state().layers.single()
                val buffer = ByteBuffer.allocateDirect(layer.maskWidth * layer.maskHeight)
                editor.maskPixels(id, layer.maskWidth, buffer)
                return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
            }
            val before = mask(); val revision = editor.currentRevision()
            editor.startStroke(id, StrokeSettings(diameter = 8f, maskPaintWhite = white), erasing, mask = true)
            sample(editor); editor.endStroke(true)
            check(mask().contentEquals(before) && editor.currentRevision() == revision)
            editor.startStroke(id, StrokeSettings(diameter = 8f, maskPaintWhite = white), erasing, mask = true)
            sample(editor); editor.endStroke(false)
            val painted = mask()
            check((painted[16 * 32 + 16].toInt() and 255) == if (white) 255 else 0) {
                "Mask target changed with Eraser: white=$white erasing=$erasing"
            }
            editor.undo(false); check(mask().contentEquals(before))
            editor.undo(true); check(mask().contentEquals(painted))
        }
    }

    // A 5 px string at 100% stays slack for 3 px, then advances to x=5 for a pointer at x=10.
    val input = StrokeInput(StrokeSettings(smoothing = 5f), 1.0)
    val first = StrokeInputSample(0.0, 0.0, .8, 0.0)
    check(input.append(first) == first)
    check(input.append(StrokeInputSample(3.0, 0.0, .4, .01)) == null)
    check(input.append(StrokeInputSample(10.0, 0.0, .6, .02)) == StrokeInputSample(5.0, 0.0, .6, .02))
    check(input.finish() == StrokeInputSample(10.0, 0.0, .6, .02))
    val zoomed = StrokeInput(StrokeSettings(smoothing = 5f), 2.0)
    zoomed.append(first)
    check(zoomed.append(StrokeInputSample(10.0, 0.0, .3, .03)) == StrokeInputSample(7.5, 0.0, .3, .03))
    zoomed.cancel()
    check(runCatching { zoomed.finish() }.exceptionOrNull() is IllegalStateException)
    val unchanged = StrokeInput(StrokeSettings(), 1.0)
    check(unchanged.append(first) == first)
    val second = StrokeInputSample(10.0, 2.0, .35, .04)
    check(unchanged.append(second) == second && unchanged.finish() == null)
    for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2001f)) {
        check(runCatching { StrokeSettings(diameter = invalid) }.isFailure) { "Invalid brush size accepted" }
    }
    return "Configured JNI RGB/opacity channels, hardness, cancel/revision, single undo/redo/checkpoint, four independent black/white mask-Eraser combinations, string slack/zoom/endpoint and pressure-time preservation passed. No timing or complete desktop brush parity claim."
}
