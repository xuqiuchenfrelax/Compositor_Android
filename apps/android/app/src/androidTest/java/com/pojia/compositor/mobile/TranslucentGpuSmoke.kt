package com.pojia.compositor.mobile

import android.opengl.GLES30
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Transparent CPU composition, then checker, is the independent display reference. */
internal fun translucentGpuSmoke(): String {
    val stack = listOf(
        intArrayOf(193,234,109,239) to 0.7f, intArrayOf(7,1,106,230) to 0.1f,
        intArrayOf(34,14,31,73) to 0.1f, intArrayOf(4,64,23,78) to 0.1f,
        intArrayOf(0,62,80,80) to 0.1f, intArrayOf(143,134,32,208) to 0.3f,
        intArrayOf(94,59,40,109) to 0.1f, intArrayOf(49,30,45,58) to 0.2f,
        intArrayOf(12,15,13,24) to 0.3f, intArrayOf(55,25,54,182) to 0.2f)
    val fixtures = listOf(stack, stack.reversed(), stack.take(1),
        List(10) { intArrayOf(1,2,3,4) to 0.5f }, List(10) { intArrayOf(31,17,7,39) to 0.37f })
    val results = ArrayList<String>()
    var maximum = 0
    for ((index, fixture) in fixtures.withIndex()) NativeEditor(documentSize = 1, addDefaultLayers = false).use { editor ->
        for ((color, opacity) in fixture) {
            val bytes = ByteBuffer.allocateDirect(4).apply { color.forEach { put(it.toByte()) }; rewind() }
            val id = editor.importPixels(1, 1, bytes)
            editor.setAppearanceForTest(LayerState(id, opacity.toDouble(), true, 0))
        }
        val cpu = ByteBuffer.allocateDirect(4)
        check(editor.draw(1, 1, 0f, 0f, 1f, 1f, cpu))
        val alpha = cpu.get(3).toInt() and 255
        val expected = IntArray(4) { channel -> if (channel == 3) 255 else
            ((cpu.get(channel).toInt() and 255) + 0.19f * (255 - alpha)).roundToInt() }
        val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
        renderer.viewport = Viewport(0f, 0f, 1f, 1f)
        renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1, 1); renderer.onDrawFrame(null)
        val pixels = ByteBuffer.allocateDirect(4)
        GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        val actual = IntArray(4) { pixels.get(it).toInt() and 255 }
        maximum = maxOf(maximum, (0..3).maxOf { abs(actual[it] - expected[it]) })
        results.add("fixture$index cpu+checker=${expected.toList()} gpu=${actual.toList()}")
        if (index == 0) {
            val top = editor.state().layers.last()
            for (mode in 1..23) {
                editor.setAppearanceForTest(top.copy(blend = mode))
                renderer.onDrawFrame(null)
                check(editor.draw(1, 1, 0f, 0f, 1f, 1f, cpu))
                GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                val modeAlpha = cpu.get(3).toInt() and 255
                for (channel in 0..3) {
                    val value = pixels.get(channel).toInt() and 255
                    val oracle = if (channel == 3) 255 else
                        ((cpu.get(channel).toInt() and 255) + 0.19f * (255 - modeAlpha)).roundToInt()
                    check(abs(value - oracle) <= 1) { "GPU blend mode $mode differs at channel $channel: $value != $oracle" }
                }
                check(renderer.cacheBytes == 256 && renderer.surfaceBytes == 8 && renderer.fallbackBytes == 0) { "GPU blend did not use bounded double composition" }
            }
            editor.setAppearanceForTest(top)
            renderer.onDrawFrame(null)
            check(renderer.cacheBytes == 256 && renderer.surfaceBytes == 4) { "Returning to normal retained CPU fallback storage" }
            GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            for (channel in 0..3) check(abs((pixels.get(channel).toInt() and 255) - expected[channel]) <= 2)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        }
        editor.close(); renderer.onDrawFrame(null)
        check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 0) { "Closed document retained GPU raster storage" }
        GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        for (channel in 0..3) {
            val value = pixels.get(channel).toInt() and 255
            check(abs(value - if (channel == 3) 255 else 48) <= 1) { "Closed document left its previous image visible" }
        }
    }
    val report = "Translucent GPU maxByteDifference=$maximum; ${results.joinToString("; ")}"
    check(maximum <= 2) { report }
    return "$report; all23 GPU blend modes/normal return±1byte; close→checker+zero raster storage passed"
}

/** Both fallback selection and resizing an existing CPU display honor positive tiny budgets. */
internal fun budgetGpuSmoke(): String = NativeEditor(documentSize = 1, addDefaultLayers = false).use { editor ->
    for (color in listOf(intArrayOf(60,30,20,100), intArrayOf(20,60,25,90))) {
        val bytes = ByteBuffer.allocateDirect(4).apply { color.forEach { put(it.toByte()) }; rewind() }
        val id = editor.importPixels(1, 1, bytes)
        editor.setAppearanceForTest(LayerState(id, 0.6, true, 0))
    }
    val top = editor.state().layers.last()
    val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
    renderer.viewport = Viewport(0f, 0f, 1f, 1f)
    renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1024, 1024)
    val actual = ByteBuffer.allocateDirect(1024 * 1024 * 4)
    val cpu = ByteBuffer.allocateDirect(4)
    fun drawAndVerify(budget: Int) {
        renderer.setTextureBudget(budget); renderer.onDrawFrame(null)
        val target = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, target, 0)
        check(target[0] == 0 && !GLES30.glIsEnabled(GLES30.GL_BLEND)) { "Display transition retained offscreen target or blending" }
        check(renderer.totalGpuBytes <= budget) { "Display storage ${renderer.totalGpuBytes} exceeds $budget budget" }
        check(editor.draw(1, 1, 0f, 0f, 1f, 1f, cpu))
        val alpha = cpu.get(3).toInt() and 255
        GLES30.glReadPixels(0, 0, 1024, 1024, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        for (y in 0 until 1024) for (x in 0 until 1024) for (channel in 0..3) {
            val background = if ((x / 16 + y / 16) % 2 == 0) 0.19f else 0.24f
            val expected = if (channel == 3) 255 else if (budget < 4) (background * 255).roundToInt()
                else ((cpu.get(channel).toInt() and 255) + background * (255 - alpha)).roundToInt()
            val value = actual.get((y * 1024 + x) * 4 + channel).toInt() and 255
            check(abs(value - expected) <= 1) { "Budget $budget pixel differs at $x,$y channel $channel: $value != $expected" }
        }
    }
    for (mode in intArrayOf(0, 2)) {
        editor.setAppearanceForTest(top.copy(blend = mode))
        drawAndVerify(64 * 1024 * 1024)
        drawAndVerify(1024 * 1024)
        check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 1024 * 1024)
        drawAndVerify(256 * 1024)
        check(renderer.cacheBytes == 0 && renderer.surfaceBytes == 256 * 1024)
        drawAndVerify(3)
        check(renderer.totalGpuBytes == 0)
        drawAndVerify(64 * 1024 * 1024)
        check(renderer.surfaceBytes == (if (mode == 0) 4 else 8) * 1024 * 1024 && renderer.fallbackBytes == 0) { "Restored budget did not restore GPU display quality" }
        check(renderer.cacheBytes == 256)
    }
    editor.close(); renderer.onDrawFrame(null)
    "GPU budget passed: normal/non-normal1MiB→256KiB→3bytes→64MiB, totalGpuBytes≤budget, display pixels±1byte, quality restored"
}
