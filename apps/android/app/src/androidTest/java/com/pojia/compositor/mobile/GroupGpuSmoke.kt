package com.pojia.compositor.mobile

import android.opengl.GLES30
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Independent integer source-over oracle; the expected hierarchy order is specified by each fixture. */
internal fun groupGpuSmoke(recreateContext: () -> Unit): String {
    fun compose(vararg contributions: Pair<IntArray, Float>): IntArray {
        var result = IntArray(4)
        for ((source, opacity) in contributions) {
            val remaining = 1.0 - source[3] / 255.0 * opacity
            result = IntArray(4) { (source[it] * opacity + result[it] * remaining).roundToInt().coerceIn(0, 255) }
        }
        return result
    }
    val red = intArrayOf(255, 0, 0, 255)
    val blue = intArrayOf(0, 0, 255, 255)
    val green = intArrayOf(0, 255, 0, 255)
    val translucent = intArrayOf(20, 60, 192, 192)
    check(compose(red to 0.5f, blue to 0.5f).contentEquals(intArrayOf(64, 0, 128, 192))) {
        "Independent pass-through fixture must produce alpha 192, not isolated-group alpha 128"
    }
    NativeEditor(1, addDefaultLayers = false).use { editor ->
        fun add(color: IntArray): Long = editor.importPixels(1, 1,
            ByteBuffer.allocateDirect(4).apply { color.forEach { put(it.toByte()) }; rewind() })
        fun appearance(id: Long, opacity: Float? = null, visible: Boolean? = null, blend: Int? = null) {
            val old = editor.state().layers.first { it.id == id }
            editor.setAppearanceForTest(old.copy(opacity = opacity?.toDouble() ?: old.opacity, visible = visible ?: old.visible,
                blend = blend ?: old.blend))
        }
        val r = add(red); val b = add(blue); val outer = editor.addGroup()
        editor.reparentLayer(r, outer); editor.reparentLayer(b, outer); appearance(outer, opacity = 0.5f)
        val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
        renderer.viewport = Viewport(0f, 0f, 1f, 1f)
        renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 64, 64)
        val actual = ByteBuffer.allocateDirect(64 * 64 * 4)
        val cpu = ByteBuffer.allocateDirect(4)
        fun verify(expected: IntArray, gpu: Boolean = true, checkerOnly: Boolean = false) {
            renderer.onDrawFrame(null)
            GLES30.glReadPixels(0, 0, 64, 64, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            check(renderer.totalGpuBytes <= renderer.textureBudgetBytes) { "Group display exceeded raster budget" }
            val target = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, target, 0)
            check(target[0] == 0 && !GLES30.glIsEnabled(GLES30.GL_BLEND)) { "Group pass leaked target/blending state" }
            if (!checkerOnly) {
                check(editor.draw(1, 1, 0f, 0f, 1f, 1f, cpu))
                for (channel in 0..3) check(abs((cpu.get(channel).toInt() and 255) - expected[channel]) <= 1) {
                    "Group core differs from independent oracle at $channel"
                }
            }
            for (y in 0 until 64) for (x in 0 until 64) for (channel in 0..3) {
                val background = if ((x / 16 + y / 16) % 2 == 0) 0.19f else 0.24f
                val oracle = if (channel == 3) 255 else if (checkerOnly) (background * 255).roundToInt()
                    else (expected[channel] + background * (255 - expected[3])).roundToInt()
                val value = actual.get((y * 64 + x) * 4 + channel).toInt() and 255
                check(abs(value - oracle) <= 1) { "Group display differs from independent oracle at $x,$y/$channel: $value != $oracle" }
            }
            if (!checkerOnly && expected[3] > 0) check((renderer.cacheBytes > 0) == gpu) {
                "Group-only scene did not choose the expected GPU/fallback path"
            }
            if (!checkerOnly) check((renderer.fallbackBytes == 0) == gpu) { "Group path retained the wrong CPU fallback surface" }
        }
        fun warm(expected: IntArray) {
            verify(expected)
            val uploads = renderer.uploadCount; val allocations = renderer.textureAllocations; val evictions = renderer.evictionCount
            repeat(3) { verify(expected) }
            check(renderer.uploadCount == uploads && renderer.textureAllocations == allocations && renderer.evictionCount == evictions) {
                "Warm group frames uploaded, allocated or evicted immutable source tiles"
            }
        }
        // These four literal results also come from real desktop CG/CI fixtures in compatibility/render_graph.
        val half = intArrayOf(64, 0, 128, 192)
        warm(half)
        check(renderer.lastFrameDraws == 3 && renderer.surfaceBytes == 64 * 64 * 4) {
            "Group-only path added isolated group surfaces or extra composition draws"
        }
        val uploads = renderer.uploadCount
        editor.renameLayer(outer, "Pass-through 🎨"); verify(half)
        appearance(outer, opacity = 0.25f); verify(compose(red to 0.25f, blue to 0.25f))
        check(renderer.uploadCount == uploads) { "Group name/opacity change reuploaded source tiles" }
        editor.undo(false); verify(half); editor.undo(true); verify(compose(red to 0.25f, blue to 0.25f))
        appearance(outer, opacity = 0.5f)

        val inner = editor.addGroup(); editor.reparentLayer(inner, outer); appearance(inner, opacity = 0.5f)
        editor.reparentLayer(r, inner); editor.reparentLayer(b, inner)
        val quarter = intArrayOf(48, 0, 64, 112)
        check(compose(red to 0.25f, blue to 0.25f).contentEquals(quarter))
        warm(quarter)
        editor.reparentLayer(r, outer); editor.reparentLayer(b, outer); editor.deleteLayer(inner); verify(half)

        val g = add(green)
        // Storage order matches the real desktop fixture: r,g,outer,b. Hierarchy order must be g,r,b.
        editor.moveLayer(g, 1)
        editor.moveLayer(outer, 2)
        check(editor.state().layers.map { it.id } == listOf(r, g, outer, b))
        val noncontiguous = intArrayOf(64, 63, 128, 255)
        val integerNoncontiguous = compose(green to 1f, red to 0.5f, blue to 0.5f)
        check((0..3).all { abs(integerNoncontiguous[it] - noncontiguous[it]) <= 1 }) {
            "Independent integer oracle differs from the desktop fixture beyond the channel-byte gate"
        }
        warm(noncontiguous)
        checkNotNull(editor.captureScene()).use { scene ->
            val plan = checkNotNull(scene.normalDrawPlan())
            check(plan.layerIndices.contentEquals(intArrayOf(1, 0, 3))) { "Shared plan lost hierarchy scene indices" }
            check(plan.effectiveOpacities.contentEquals(floatArrayOf(1f, 0.5f, 0.5f)))
            appearance(outer, visible = false); verify(green)
            val pixels = ByteBuffer.allocateDirect(4)
            scene.draw(1, 1, renderer.viewport, pixels)
            for (channel in 0..3) check(abs((pixels.get(channel).toInt() and 255) - noncontiguous[channel]) <= 1)
            check(checkNotNull(scene.normalDrawPlan()).layerIndices.contentEquals(plan.layerIndices)) {
                "Later group edit mutated captured plan"
            }
            editor.undo(false); warm(noncontiguous)
        }
        val nested = editor.addGroup(); editor.reparentLayer(nested, outer); appearance(nested, opacity = 0.5f)
        editor.deleteLayer(b)
        val t = add(translucent); editor.reparentLayer(t, nested)
        val nestedExpected = compose(green to 1f, red to 0.5f, translucent to 0.25f)
        warm(nestedExpected)
        appearance(nested, visible = false); verify(compose(green to 1f, red to 0.5f))
        editor.undo(false); verify(nestedExpected)
        appearance(outer, visible = false); verify(green); editor.undo(false); verify(nestedExpected)
        editor.deleteLayer(nested); verify(compose(green to 1f, red to 0.5f)); editor.undo(false); warm(nestedExpected)
        recreateContext(); renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 64, 64); warm(nestedExpected)
        for (budget in intArrayOf(1024 * 1024, 20 * 1024, 3, 64 * 1024 * 1024)) {
            renderer.setTextureBudget(budget)
            verify(nestedExpected, checkerOnly = budget < 4)
        }
        warm(nestedExpected)
        // Disabled masks remain unsupported; hidden non-normal leaves are omitted by the generalized native plan.
        editor.createMask(t, true); editor.enableMask(t, false)
        checkNotNull(editor.captureScene()).use { check(it.normalDrawPlan() == null) }
        verify(nestedExpected, gpu = false)
        editor.deleteMask(t); warm(nestedExpected)
        appearance(t, visible = false, blend = 2)
        checkNotNull(editor.captureScene()).use { check(it.normalDrawPlan() == null) }
        verify(compose(green to 1f, red to 0.5f), gpu = true)
        editor.undo(false); editor.undo(false); warm(nestedExpected)
        editor.close(); renderer.onDrawFrame(null); check(renderer.totalGpuBytes == 0)
    }
    return "Group GPU passed: independent source-over oracle (opaque red/blue at group50% → alpha192), nested/translucent/hidden/noncontiguous hierarchy, captured plan/undo/delete, warm zero uploads/allocations/evictions, context recreation, budget/restore; disabled masks remain fallback; hidden non-normal leaves use native generalized GPU plan"
}
