package com.pojia.compositor.mobile

import android.opengl.GLES30
import java.nio.ByteBuffer
import kotlin.math.abs

/** Odd source edges are clipped boxes, not equal-width stretched mip texels. */
internal fun partialLodSmoke(): String = NativeEditor(documentSize = 259, addDefaultLayers = false).use { editor ->
    val pixels = ByteBuffer.allocateDirect(259 * 259 * 4)
    for (y in 0 until 259) for (x in 0 until 259) {
        pixels.put((if (x % 2 == 0) 240 else 16).toByte())
        pixels.put((if (y % 2 == 0) 200 else 40).toByte())
        pixels.put((if ((x + y) % 2 == 0) 220 else 60).toByte()).put(255.toByte())
    }
    pixels.rewind(); editor.importPixels(259, 259, pixels)
    val renderer = GpuDisplayRenderer(editor) { throw IllegalStateException(it) }
    renderer.viewport = Viewport(-0.5f, -0.5f, 260f, 260f)
    renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 130, 130)
    renderer.onDrawFrame(null)
    val actual = ByteBuffer.allocateDirect(130 * 130 * 4)
    GLES30.glReadPixels(0, 0, 130, 130, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
    check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
    for (y in 0 until 130) for (x in 0 until 130) {
        val expected = intArrayOf(if (x == 129) 240 else 128, if (y == 129) 200 else 120,
            if (x == 129 && y == 129) 220 else 140, 255)
        for (channel in 0..3) {
            val value = actual.get(((129 - y) * 130 + x) * 4 + channel).toInt() and 255
            check(abs(value - expected[channel]) <= 1) { "Partial LOD edge mismatch $x,$y channel$channel: $value != ${expected[channel]}" }
        }
    }
    val uploads = renderer.uploadCount; val allocations = renderer.textureAllocations
    renderer.onDrawFrame(null)
    check(renderer.uploadCount == uploads && renderer.textureAllocations == allocations)
    "Partial259² LOD1 independent oracle±1byte; clipped edge coverage and padded array slots passed"
}
