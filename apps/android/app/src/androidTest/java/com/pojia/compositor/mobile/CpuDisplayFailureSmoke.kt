package com.pojia.compositor.mobile

import android.opengl.GLES30
import java.nio.ByteBuffer

/** Real GLES allocation/upload errors must not publish a frame or suppress a retry. */
internal fun cpuDisplayFailureSmoke(): String = NativeEditor(8, addDefaultLayers = false).use { editor ->
    gpuSmoke(editor, independentGolden = { recreateContext ->
        fun solid(red: Int, green: Int, blue: Int) = ByteBuffer.allocateDirect(8 * 8 * 4).apply {
            repeat(64) { put(red.toByte()).put(green.toByte()).put(blue.toByte()).put(255.toByte()) }
            rewind()
        }
        editor.importPixels(8, 8, solid(190, 30, 10))
        val errors = mutableListOf<String>()
        val renderer = CpuReferenceRenderer(editor, errors::add)
        val fields = listOf("texture", "lastGeneration", "lastViewport", "hasPixels", "width", "height")
            .associateWith { CpuReferenceRenderer::class.java.getDeclaredField(it).apply { isAccessible = true } }
        fun generation() = fields.getValue("lastGeneration").getLong(renderer)
        fun texture() = fields.getValue("texture").getInt(renderer)
        fun verifyBlue(side: Int) {
            val pixels = ByteBuffer.allocateDirect(side * side * 4)
            GLES30.glReadPixels(0, 0, side, side, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            repeat(side * side) { index ->
                check((pixels.get(index * 4).toInt() and 255) == 20 &&
                    (pixels.get(index * 4 + 1).toInt() and 255) == 40 &&
                    (pixels.get(index * 4 + 2).toInt() and 255) == 210 &&
                    (pixels.get(index * 4 + 3).toInt() and 255) == 255) { "Retry did not display the updated scene" }
            }
        }
        try {
            renderer.viewport = Viewport(0.0, 0.0, 8.0, 8.0)
            renderer.onSurfaceCreated(null, null)
            renderer.configureSurface(8, 8, 1024)
            checkNotNull(editor.captureScene()).use { check(renderer.drawScene(it, null)) }
            val firstGeneration = generation()
            editor.importPixels(8, 8, solid(20, 40, 210))
            checkNotNull(editor.captureScene()).use { scene ->
                check(scene.generation() != firstGeneration)
                // Validly shrink storage; the next full-size upload must fail in the driver.
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture())
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                check(!renderer.drawScene(scene, null)) { "Failed texture upload was reported as a completed frame" }
                check(errors.lastOrNull() == "CPU display texture upload failed")
                check(generation() == firstGeneration && !fields.getValue("hasPixels").getBoolean(renderer))
                check(!renderer.drawCached()) { "Invalid texture was reused as a valid cached frame" }
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 8, 8, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                check(renderer.drawScene(scene, null)) { "Same-generation upload was not retried" }
                check(generation() == scene.generation())
                verifyBlue(8)

                // A one-byte PBO cannot satisfy a real pixel upload. The failed
                // upload must leave the previous generation unpublished and retryable.
                val buffers = IntArray(1)
                GLES30.glGenBuffers(1, buffers, 0)
                try {
                    editor.importPixels(8, 8, solid(20, 40, 210))
                    checkNotNull(editor.captureScene()).use { retryScene ->
                        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, buffers[0])
                        GLES30.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER, 1, null, GLES30.GL_STATIC_DRAW)
                        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                        check(!renderer.drawScene(retryScene, null)) { "Failed PBO upload was reported as a completed frame" }
                        check(errors.lastOrNull() == "CPU display texture upload failed")
                        check(generation() == scene.generation() && !fields.getValue("hasPixels").getBoolean(renderer))
                        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
                        check(renderer.drawScene(retryScene, null)) { "PBO upload failure was not retryable" }
                        check(generation() == retryScene.generation())
                        verifyBlue(8)
                    }
                } finally {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
                    GLES30.glDeleteBuffers(1, buffers, 0)
                }
                renderer.configureSurface(9, 9, 1024)
                check(renderer.drawScene(scene, null))
                verifyBlue(9)

                recreateContext()
                renderer.onSurfaceCreated(null, null)
                renderer.configureSurface(9, 9, 1024)
                check(renderer.drawScene(scene, null)) { "Same-size context recreation omitted texture storage" }
                verifyBlue(9)
                check(errors.size == 2)
            }
            "CPU display passed real GLES upload failure, unpublished generation/storage, " +
                "same-generation retry, exact updated pixels, and same-size context recreation"
        } finally { renderer.release() }
    })
}
