package com.pojia.compositor.mobile

import android.opengl.EGL14
import android.opengl.GLES30
import java.nio.ByteBuffer
import kotlin.math.abs

internal fun gpuSmoke(editor: NativeEditor, relationshipsOnly: Boolean = false, groupsOnly: Boolean = false, precisionOnly: Boolean = false, lodOnly: Boolean = false,
    independentGolden: ((() -> Unit) -> String)? = null): String {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(display != EGL14.EGL_NO_DISPLAY)
    check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
    val configurations = arrayOfNulls<android.opengl.EGLConfig>(1)
    val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
    check(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, IntArray(1), 0))
    val config = checkNotNull(configurations[0])
    var context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
    val surface = EGL14.eglCreatePbufferSurface(display, config,
        intArrayOf(EGL14.EGL_WIDTH, 1024, EGL14.EGL_HEIGHT, 1024, EGL14.EGL_NONE), 0)
    check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
    check(EGL14.eglMakeCurrent(display, surface, surface, context))
    try {
        fun recreateContext() {
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
            check(EGL14.eglDestroyContext(display, context))
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT && EGL14.eglMakeCurrent(display, surface, surface, context))
        }
        independentGolden?.let { return it(::recreateContext) }
        if (relationshipsOnly) return structureGpuSmoke()
        if (groupsOnly) return groupGpuSmoke(::recreateContext)
        if (precisionOnly) return precisionGpuSmoke()
        if (lodOnly) return partialLodSmoke()
        val renderer = GpuDisplayRenderer(editor) { error -> throw IllegalStateException(error) }
        renderer.enableLod = false
        renderer.viewport = Viewport(0f, 0f, 2048f, 2048f)
        renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 256, 256)
        val actual = ByteBuffer.allocateDirect(256 * 256 * 4)
        val expected = ByteBuffer.allocateDirect(actual.capacity())
        fun verify() {
            renderer.onDrawFrame(null)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU shader/draw produced GL error" }
            GLES30.glReadPixels(0, 0, 256, 256, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
            check(editor.draw(256, 256, 0f, 0f, 2048f, 2048f, expected))
            for (y in 0 until 256) for (x in 0 until 256) for (channel in 0..3) {
                val a = actual.get(((255 - y) * 256 + x) * 4 + channel).toInt() and 255
                val e = expected.get((y * 256 + x) * 4 + channel).toInt() and 255
                check(abs(a - e) <= 1) { "GPU/reference mismatch at $x,$y channel $channel: $a != $e" }
            }
        }
        verify()
        val initialTileReport = checkNotNull(editor.captureScene()).use { scene ->
            check(scene.layerMetadata(0).tileCount == 1)
            val background = scene.tileViewMetadata(0, 0)
            check(background.width == 1 && background.height == 1)
            val backgroundPixel = ByteBuffer.allocateDirect(4)
            scene.readTile(0, 0, 4, backgroundPixel)
            check((0..3).all { (backgroundPixel.get(it).toInt() and 255) == 255 })
            val capacity = minOf(64, IntArray(1).also {
                GLES30.glGetIntegerv(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS, it, 0)
            }[0])
            val paintTiles = scene.layerMetadata(1).tileCount
            val catalogs = HashMap<Int, Int>()
            val identities = HashSet<Long>()
            fun admit(tile: SceneTileView) {
                if (!identities.add(tile.readResourceId)) return
                var side = 1
                while (side < maxOf(tile.width, tile.height)) side *= 2
                catalogs[side] = (catalogs[side] ?: 0) + 1
            }
            admit(background)
            for (index in 0 until paintTiles) {
                val tile = scene.tileViewMetadata(1, index)
                val paintLayer = scene.layerMetadata(1)
                check(tile.x + tile.width <= paintLayer.pixelWidth && tile.y + tile.height <= paintLayer.pixelHeight)
                admit(tile)
            }
            val expectedPages = catalogs.values.sumOf { (it + capacity - 1) / capacity }
            val expectedBytes = catalogs.entries.sumOf { (side, count) ->
                ((count + capacity - 1) / capacity) * side.toLong() * side * 4 * capacity
            }
            check(renderer.cacheBytes.toLong() == expectedBytes && renderer.pageCount == expectedPages &&
                renderer.uploadCount == identities.size.toLong()) {
                "White source and actual paint catalog must occupy exactly their required texture-array pages"
            }
            "initial paintCatalog=$paintTiles pageCapacity=$capacity pages=${renderer.pageCount} cacheBytes=$expectedBytes"
        }
        val initialUploads = renderer.uploadCount
        val paint = editor.state().layers.last()
        editor.setAppearanceForTest(paint.copy(opacity = 0.5))
        verify()
        check(renderer.uploadCount == initialUploads) { "Opacity edit reuploaded immutable image tiles" }
        editor.setAppearanceForTest(paint.copy(visible = false))
        verify()
        check(renderer.uploadCount == initialUploads) { "Visibility edit reuploaded immutable image tiles" }
        editor.setAppearanceForTest(paint)
        verify()
        // Render and release a scene between every append: cached texture IDs must track unique mutable tiles.
        editor.startStroke(paint.id, 32f, false)
        for (i in 0..19) {
            editor.samples(doubleArrayOf(300.0 + i * 65, 600.0 + i * 25, 1.0, i * 0.01))
            verify()
        }
        editor.endStroke(false)
        verify()
        check(renderer.lastFrameExactPixelLayers > 0) { "Integer brush source must avoid interpolated nearest texel ties after commit crop" }
        editor.undo(false); verify()
        editor.undo(true); verify()
        val afterStrokeUploads = renderer.uploadCount
        renderer.viewport = Viewport(-100f, -100f, 2248f, 2248f)
        renderer.onDrawFrame(null)
        check(renderer.uploadCount == afterStrokeUploads) { "Pan/zoom reuploaded cached tiles" }
        renderer.onSurfaceChanged(null, 1024, 1024)
        renderer.onDrawFrame(null); GLES30.glFinish()
        val warmUploads = renderer.uploadCount
        val warmEvictions = renderer.evictionCount
        val times = LongArray(30) {
            val start = System.nanoTime(); renderer.onDrawFrame(null); GLES30.glFinish(); System.nanoTime() - start
        }.sorted()
        check(renderer.uploadCount == warmUploads && renderer.evictionCount == warmEvictions) { "Warm scene thrashed texture cache" }
        val translucentReport = translucentGpuSmoke()
        val budgetReport = budgetGpuSmoke()
        val structureReport = structureGpuSmoke()
        val groupReport = groupGpuSmoke(::recreateContext)
        val precisionReport = precisionGpuSmoke()
        val partialReport = partialLodSmoke()
        val denseReport = denseGpuSmoke(::recreateContext)
        return "GPU normal/opacity/visibility/incremental-stroke/undo/redo matched CPU ±1 byte; pan/zoom reused textures; " +
            "$initialTileReport; " +
            "1024² warm 2-layer 2048² sparse-document submit+finish median=${times[15] / 1e6}ms p95=${times[28] / 1e6}ms " +
            "uploads=${renderer.uploadCount} evictions=${renderer.evictionCount} warmUploads=0 warmEvictions=0\n$translucentReport\n$budgetReport\n$structureReport\n$groupReport\n$precisionReport\n$partialReport\n$denseReport"
    } finally {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
    }
}
