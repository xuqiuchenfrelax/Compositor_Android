package com.pojia.compositor.mobile

import android.opengl.GLES30
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Immutable tiles live in texture-array pages; same-layer/page geometry is submitted in one draw. */
internal class GpuDisplayRenderer(private val editor: NativeEditor, requestFrame: (() -> Unit)? = null,
    waitingChanged: (Boolean) -> Unit = {},
    private val failed: (String) -> Unit) : GLSurfaceView.Renderer {
    var enableLod = true
    var viewport = Viewport(0.0, 0.0, NativeEditor.SIZE.toDouble(), NativeEditor.SIZE.toDouble())
    private var reference: CpuReferenceRenderer? = null
    private var program = 0
    private var checker = 0
    private class LayerUniforms(program: Int) {
        val opacity = GLES30.glGetUniformLocation(program, "opacity")
        val documentClip = GLES30.glGetUniformLocation(program, "documentClip")
        val image = GLES30.glGetUniformLocation(program, "image")
        val destination = GLES30.glGetUniformLocation(program, "destination")
        val combineDestination = GLES30.glGetUniformLocation(program, "combineDestination")
        val blendMode = GLES30.glGetUniformLocation(program, "blendMode")
        val opacityByte = GLES30.glGetUniformLocation(program, "opacityByte")
        val exactPixels = GLES30.glGetUniformLocation(program, "exactPixels")
        val pixelStep = GLES30.glGetUniformLocation(program, "pixelStep")
        val sourceOffset = GLES30.glGetUniformLocation(program, "sourceOffset")
        val surfaceHeight = GLES30.glGetUniformLocation(program, "surfaceHeight")
    }
    private class CheckerUniforms(program: Int) {
        val composed = GLES30.glGetUniformLocation(program, "composed")
        val hasComposition = GLES30.glGetUniformLocation(program, "hasComposition")
        val surfaceSize = GLES30.glGetUniformLocation(program, "surfaceSize")
    }
    private lateinit var layerUniforms: LayerUniforms
    private lateinit var checkerUniforms: CheckerUniforms
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var compositionTexture = 0
    private var compositionFramebuffer = 0
    private var alternateTexture = 0
    private var alternateFramebuffer = 0
    private var compositionWidth = 0
    private var compositionHeight = 0
    private var slotsPerPage = 64
    private var tick = 0L
    private var vertices = ByteBuffer.allocateDirect(64 * 6 * 11 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val tilePixels = ByteBuffer.allocateDirect(256 * 256 * 4)
    private val polygonClip = GpuPolygonClip()
    private val batchPool = BatchPool()
    private data class TextureKey(val readResourceId: Long, val level: Int)
    private data class PreparedTile(val index: Int, val metadata: SceneTileView, val local: DoubleArray)
    private class Page(val name: Int, val side: Int, capacity: Int) {
        val free = ArrayDeque<Int>().apply { repeat(capacity) { addLast(it) } }
        val keys = LinkedHashSet<TextureKey>()
        val bytes = side * side * 4 * capacity
        var lastUse = 0L
    }
    private data class Texture(val page: Page, val slot: Int, val bytes: Int)
    private class Batch {
        var values = FloatArray(64 * 6 * 11)
        var size = 0
        fun vertex(x: Float, y: Float, u: Float, v: Float, dx: Float, dy: Float, slot: Int, tileX: Int, tileY: Int, tileWidth: Int, tileHeight: Int) {
            if (size + 11 > values.size) values = values.copyOf(values.size * 2)
            values[size++] = x; values[size++] = y; values[size++] = u; values[size++] = v
            values[size++] = dx; values[size++] = dy; values[size++] = slot.toFloat()
            values[size++] = tileX.toFloat(); values[size++] = tileY.toFloat()
            values[size++] = tileWidth.toFloat(); values[size++] = tileHeight.toFloat()
        }
    }
    /** Bounded working-array reuse; oversized clipped batches are deliberately dropped. */
    private class BatchPool {
        private val available = ArrayDeque<Batch>()
        private var retainedBytes = 0
        fun acquire(): Batch {
            if (available.isEmpty()) return Batch()
            val batch = available.removeFirst()
            retainedBytes -= batch.values.size * 4
            return batch
        }
        fun release(batch: Batch) {
            batch.size = 0
            val bytes = batch.values.size * 4
            if (bytes <= MAX_BYTES_PER_BATCH && available.size < MAX_BATCHES &&
                retainedBytes <= MAX_RETAINED_BYTES - bytes) {
                available.addLast(batch); retainedBytes += bytes
            }
        }
        fun clear() { available.clear(); retainedBytes = 0 }
        companion object {
            private const val MAX_BATCHES = 8
            private const val MAX_BYTES_PER_BATCH = 64 * 1024
            private const val MAX_RETAINED_BYTES = 512 * 1024
        }
    }
    private val textures = LinkedHashMap<TextureKey, Texture>(128, 0.75f, true)
    private val pages = ArrayList<Page>()
    private var allocatedBytes = 0
    val cacheBytes: Int get() = allocatedBytes
    val cacheLiveBytes: Long get() = textures.values.sumOf { it.bytes.toLong() }
    val pageCount: Int get() = pages.size
    var textureBudgetBytes = 64 * 1024 * 1024; private set
    private var tileBudgetBytes = textureBudgetBytes
    val surfaceBytes: Int get() = if (compositionTexture != 0) compositionWidth * compositionHeight * 4 * (if (alternateTexture != 0) 2 else 1) else reference?.gpuBytes ?: 0
    val fallbackBytes: Int get() = reference?.gpuBytes ?: 0
    val totalGpuBytes: Int get() = allocatedBytes + surfaceBytes
    var peakTotalGpuBytes = 0; private set
    private var requestedBudgetBytes = textureBudgetBytes
    private var pendingClear = false
    /** Optional native Distort preview. The lease owns retirement across skipped GL events. */
    private var previewLease: DistortPreviewLease? = null
    var textureDeleteCount = 0L; private set
    var peakTextureBytes = 0; private set
    var uploadedBytes = 0L; private set
    val lodUploads = LongArray(9)
    var textureAllocations = 0L; private set
    var slotReuseCount = 0L; private set
    var pageEvictions = 0L; private set
    var lastFrameAllocations = 0L; private set
    var uploadCount = 0L; private set
    var evictionCount = 0L; private set
    var lastFrameUploads = 0L; private set
    var lastFrameEvictions = 0L; private set
    var lastFrameDraws = 0; private set
    var lastFrameExactPixelLayers = 0; private set
    var lastFrameNanos = 0L; private set
    var completedFrameNumber = 0L; private set
    var completedSceneGeneration = -1L; private set
    var completedPreviewToken = 0L; private set
    private var drawingPreviewToken = 0L
    var completedFrameUsesTiles = false; private set
    private var frames = 0
    private var totalNanos = 0L
    private val canDeferCapture = requestFrame != null
    private val sceneAdmission = DisplaySceneAdmission(editor.resourceLedger, requestFrame ?: {}, waitingChanged)
    private var presentedPath = 0
    private var presentedViewport: Viewport? = null
    private var presentedWidth = 0
    private var presentedHeight = 0
    internal fun sceneAdmissionState(): DisplaySceneAdmission.State = sceneAdmission.snapshot()
    internal fun hasDeferredFrameReady(): Boolean = sceneAdmission.hasReadyFrame
    internal fun retireSceneAdmission() = sceneAdmission.close()
    /** The Canvas wrapper could not enter the document gate; retain the last GL presentation. */
    internal fun deferForCommands(drawCached: Boolean) {
        if (!sceneAdmission.beginAttempt()) return
        sceneAdmission.deferUntilCommands(editor::awaitCommandsAvailable)
        if (drawCached) drawDeferred()
    }
    internal fun setPreviewScene(lease: DistortPreviewLease?) {
        // queueEvent also runs while paused with no current EGL context. This
        // replaces only native ownership/cache metadata; GL uploads wait for a frame.
        if (previewLease === lease) return
        previewLease?.rendererReleased()
        previewLease = lease
        reference?.invalidateSceneCache()
        presentedPath = 0
    }
    /** Idempotently retire a preview. A stale queued clear may name an older lease. */
    internal fun retirePreviewScene(lease: DistortPreviewLease? = null) {
        // Retirement only drops the native scene lease; it is safe even when a
        // paused GLSurfaceView drains a queued event without an EGL context.
        val current = previewLease
        if (lease == null || current === lease) {
            previewLease = null
            reference?.invalidateSceneCache()
            presentedPath = 0
        }
        lease?.rendererReleased()
        if (lease == null) current?.rendererReleased()
    }
    /** Compatibility spelling for callers that treat preview retirement as close. */
    internal fun closePreview(lease: DistortPreviewLease? = null) = retirePreviewScene(lease)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        presentedPath = 0; presentedViewport = null
        textures.clear(); pages.clear(); allocatedBytes = 0
        reference = null; compositionTexture = 0; compositionFramebuffer = 0
        alternateTexture = 0; alternateFramebuffer = 0
        compositionWidth = 0; compositionHeight = 0
        batchPool.clear()
        val maximum = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS, maximum, 0)
        check(maximum[0] > 0) { "GLES texture arrays are unavailable" }
        slotsPerPage = minOf(64, maximum[0])
        program = makeProgram("""
            #version 300 es
            layout(location=0) in vec2 position;
            layout(location=1) in vec2 uv;
            layout(location=2) in vec2 documentPosition;
            layout(location=3) in float arraySlot;
            layout(location=4) in vec4 tileCoordinates;
            out vec2 coordinate;
            out vec2 documentCoordinate;
            flat out float slot;
            flat out vec4 sourceTile;
            void main() { gl_Position=vec4(position,0,1); coordinate=uv; documentCoordinate=documentPosition; slot=arraySlot; sourceTile=tileCoordinates; }
        """.trimIndent(), """
            #version 300 es
            precision highp float;
            precision highp int;
            precision highp sampler2DArray;
            in vec2 coordinate;
            in vec2 documentCoordinate;
            flat in float slot;
            flat in vec4 sourceTile;
            uniform sampler2DArray image;
            uniform highp sampler2D destination;
            uniform int blendMode;
            uniform bool combineDestination;
            uniform float opacity;
            uniform uint opacityByte;
            uniform vec4 documentClip;
            uniform bool exactPixels;
            uniform ivec2 pixelStep;
            uniform ivec2 sourceOffset;
            uniform int surfaceHeight;
            out vec4 color;
            ${GpuBlendFunctions.source}
            void main() {
                if (any(lessThan(documentCoordinate,documentClip.xy)) || any(greaterThanEqual(documentCoordinate,documentClip.zw))) discard;
                if (exactPixels) {
                    ivec2 screen=ivec2(gl_FragCoord.xy);
                    screen.y=surfaceHeight-1-screen.y;
                    ivec2 source=sourceOffset+screen*pixelStep+pixelStep/2-ivec2(sourceTile.xy);
                    if (any(lessThan(source,ivec2(0))) || any(greaterThanEqual(source,ivec2(sourceTile.zw)))) discard;
                    color=texelFetch(image,ivec3(source,int(slot)),0);
                } else color=texture(image,vec3(coordinate,slot));
                color=combineDestination ? cpBlendPixel(color,texelFetch(destination,ivec2(gl_FragCoord.xy),0),opacity,blendMode,opacityByte) : cpOpacityPixel(color,opacityByte);
            }
        """.trimIndent())
        layerUniforms = LayerUniforms(program)
        checker = makeProgram("""
            #version 300 es
            layout(location=0) in vec2 position;
            void main() { gl_Position=vec4(position,0,1); }
        """.trimIndent(), """
            #version 300 es
            precision highp float;
            uniform sampler2D composed;
            uniform vec2 surfaceSize;
            uniform bool hasComposition;
            out vec4 color;
            void main() {
                float pattern=mod(floor(gl_FragCoord.x/16.0)+floor(gl_FragCoord.y/16.0),2.0);
                vec3 background=mix(vec3(0.19),vec3(0.24),pattern);
                vec4 source=hasComposition ? texture(composed,gl_FragCoord.xy/surfaceSize) : vec4(0);
                color=vec4(source.rgb+background*(1.0-source.a),1);
            }
        """.trimIndent())
        checkerUniforms = CheckerUniforms(checker)
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        if (requestedBudgetBytes >= 4) reference?.configureSurface(width, height, requestedBudgetBytes)
        else { reference?.release(); reference = null }
        GLES30.glViewport(0, 0, width, height)
    }
    override fun onDrawFrame(gl: GL10?) {
        val start = System.nanoTime()
        val previousUploads = uploadCount; val previousAllocations = textureAllocations; val previousEvictions = evictionCount
        lastFrameDraws = 0; lastFrameExactPixelLayers = 0
        completedFrameUsesTiles = false
        try {
            // GLSurfaceView also executes queued events while paused, without a current context.
            // Only renderer callbacks may retire the actual GL storage and its accounting.
            applyTextureBudget()
            // Standalone callers without a redraw owner retain the original strict capture contract.
            val preview = previewLease
            if (preview?.baseRevision?.let { it != editor.currentRevision() } == true)
                retirePreviewScene(preview)
            val borrowedPreview = preview?.acquire()
            drawingPreviewToken = if (borrowedPreview != null) preview.previewToken else 0L
            try {
                val captured = borrowedPreview ?: if (canDeferCapture) editor.captureDisplayScene(sceneAdmission) else editor.captureDisplayScene()
                if (captured == null) {
                    if (sceneAdmission.isDeferred) { drawDeferred(); return }
                    releaseSurfaces(); clearTiles(); drawChecker(false)
                }
                captured?.use { scene ->
                val info = scene.metadata()
                val plan = scene.unmaskedDrawPlan()
                if (textureBudgetBytes < 4) { releaseSurfaces(); clearTiles(); drawChecker(false); return@use }
                if (plan == null) { drawReference(scene, gl); return@use }
                val layers = plan.layerIndices.mapIndexed { index, sceneIndex ->
                    scene.layerMetadata(sceneIndex).copy(opacity = plan.effectiveOpacities[index],
                        blend = plan.blendModes[index], visible = true)
                }
                val needsAlternate = layers.any { it.blend != 0 }
                val requiredSurfaceBytes = surfaceWidth.toLong() * surfaceHeight * 4 * if (needsAlternate) 2 else 1
                if (requiredSurfaceBytes + 256 > textureBudgetBytes) { drawReference(scene, gl); return@use }
                presentedPath = 0
                reference?.release(); reference = null
                ensureComposition(needsAlternate)
                tileBudgetBytes = maxOf(0, textureBudgetBytes - surfaceBytes)
                evictFor(0, emptySet())
                val levels = IntArray(layers.size) { index ->
                    val layer = layers[index]; val angle = (layer.transform.rotation % 360.0) * Math.PI / 180
                    if (enableLod) mipLevel(layer, cos(angle), sin(angle)) else 0
                }
                val prepared = layers.mapIndexed { index, layer ->
                    if (layer.visible && layer.opacity > 0.0)
                        scene.tileViewsMetadata(plan.layerIndices[index]).mapIndexedNotNull { tileIndex, tile ->
                            prepareTile(tileIndex, tile, layer,
                                info[1].toDouble(), info[2].toDouble())
                        } else emptyList()
                }
                fun estimatedBytes(): Long {
                    val groups = HashMap<Int, Long>()
                    val keys = HashSet<TextureKey>()
                    prepared.forEachIndexed { index, tiles ->
                        for (item in tiles) {
                            val tile = item.metadata
                            val level = tile.level(levels[index]); val divisor = 1 shl level
                            if (!keys.add(TextureKey(tile.readResourceId, level))) continue
                            val side = pageSide((maxOf(tile.width, tile.height) + divisor - 1) / divisor)
                            groups[side] = (groups[side] ?: 0) + 1
                        }
                    }
                    return groups.entries.sumOf { (side, count) -> ((count + slotsPerPage - 1) / slotsPerPage) * side * side * 4 * slotsPerPage }
                }
                if (enableLod && tileBudgetBytes > 0) {
                    while (estimatedBytes() > tileBudgetBytes) {
                        var raised = false
                        for (index in levels.indices) if (prepared[index].any { levels[index] < it.metadata.maximumLod }) {
                            levels[index]++; raised = true
                        }
                        if (!raised) break
                    }
                }
                // A phase/clipped view may only have LOD0. If one array page cannot fit, use bounded CPU display.
                val cannotFitPage = prepared.withIndex().any { (index, tiles) -> tiles.any { item ->
                    val tile = item.metadata; val divisor = 1 shl tile.level(levels[index])
                    val side = pageSide((maxOf(tile.width, tile.height) + divisor - 1) / divisor)
                    side.toLong() * side * 4 * slotsPerPage > tileBudgetBytes
                } }
                if (cannotFitPage) { drawReference(scene, gl); return@use }
                val currentKeys = HashSet<TextureKey>()
                prepared.forEachIndexed { index, tiles -> tiles.forEach {
                    currentKeys.add(TextureKey(it.metadata.readResourceId, it.metadata.level(levels[index])))
                } }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, compositionFramebuffer)
                GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
                GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glUseProgram(program)
                GLES30.glUniform4f(layerUniforms.documentClip,
                    (maxOf(0.0, -viewport.x) / viewport.width).coerceIn(0.0, 1.0).toFloat(),
                    (maxOf(0.0, -viewport.y) / viewport.height).coerceIn(0.0, 1.0).toFloat(),
                    ((info[1].toDouble() - viewport.x) / viewport.width).coerceIn(0.0, 1.0).toFloat(),
                    ((info[2].toDouble() - viewport.y) / viewport.height).coerceIn(0.0, 1.0).toFloat())
                GLES30.glUniform1i(layerUniforms.image, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                layers.forEachIndexed { layerIndex, layer ->
                    if (!layer.visible || layer.opacity <= 0.0) return@forEachIndexed
                    if (layer.blend != 0) {
                        check(alternateTexture != 0 && compositionTexture != alternateTexture)
                        // Copy the untouched backdrop, then sample only the old surface while writing its alternate.
                        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, compositionFramebuffer)
                        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, alternateFramebuffer)
                        GLES30.glBlitFramebuffer(0, 0, surfaceWidth, surfaceHeight, 0, 0, surfaceWidth, surfaceHeight,
                            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, alternateFramebuffer)
                        GLES30.glDisable(GLES30.GL_BLEND)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositionTexture)
                    } else {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, compositionFramebuffer)
                        GLES30.glEnable(GLES30.GL_BLEND)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                    }
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glUniform1i(layerUniforms.destination, 1)
                    GLES30.glUniform1i(layerUniforms.combineDestination, if (layer.blend != 0) 1 else 0)
                    GLES30.glUniform1i(layerUniforms.blendMode, layer.blend)
                    val q = kotlin.math.floor((layer.opacity.coerceIn(0.0, 1.0).toFloat() * 255f).toDouble() + .5).toInt()
                    GLES30.glUniform1ui(layerUniforms.opacityByte, q)
                    // Preserve the Double ancestor product, then apply the desktop's
                    // Float opacity and Float product rounding for RGBA8 surfaces.
                    val surfaceMode = when (layer.blend) {
                        0, 3, 4, 7, 8, 10, 12, 13, 14, 15, 18, 19, 20, 21, 22, 23 -> true
                        else -> false
                    }
                    GLES30.glUniform1f(layerUniforms.opacity, if (surfaceMode) q / 255f else layer.opacity.toFloat())
                    val exact = if (prepared[layerIndex].all { it.metadata.level(levels[layerIndex]) == 0 &&
                        it.metadata.x <= (1 shl 24) && it.metadata.y <= (1 shl 24) })
                        exactPixelMapping(layer, info[1].toDouble(), info[2].toDouble()) else null
                    GLES30.glUniform1i(layerUniforms.exactPixels, if (exact != null) 1 else 0)
                    if (exact != null) {
                        lastFrameExactPixelLayers++
                        GLES30.glUniform2i(layerUniforms.pixelStep, exact[0], exact[1])
                        GLES30.glUniform2i(layerUniforms.sourceOffset, exact[2], exact[3])
                        GLES30.glUniform1i(layerUniforms.surfaceHeight, surfaceHeight)
                    }
                    val batches = LinkedHashMap<Page, Batch>()
                    val pinned = HashSet<Page>()
                    fun releaseBatches() {
                        for (batch in batches.values) batchPool.release(batch)
                        batches.clear(); pinned.clear()
                    }
                    fun flush() {
                        for ((page, batch) in batches) {
                            if (vertices.capacity() < batch.size)
                                vertices = ByteBuffer.allocateDirect(batch.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                            vertices.clear(); vertices.put(batch.values, 0, batch.size)
                            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, page.name)
                            bindVertices(); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, batch.size / 11); lastFrameDraws++
                        }
                        releaseBatches()
                    }
                    try {
                        for (item in prepared[layerIndex]) {
                            val tile = item.metadata; val local = item.local
                            val level = tile.level(levels[layerIndex])
                            val texture = texture(scene, plan.layerIndices[layerIndex], item.index,
                                tile, level, pinned, currentKeys, ::flush)
                            pinned.add(texture.page)
                            val batch = batches.getOrPut(texture.page) { batchPool.acquire() }
                            // Partial mip blocks retain their true source extent; padding is never sampled.
                            val uMax = tile.width.toFloat() / ((1 shl level) * texture.page.side)
                            val vMax = tile.height.toFloat() / ((1 shl level) * texture.page.side)
                            for (triangle in 1 until local.size / 4 - 1) {
                                var corner = 0
                                while (corner < 3) {
                                    val i = when (corner) { 0 -> 0; 1 -> triangle; else -> triangle + 1 }
                                    val x = local[i * 4] / viewport.width; val y = local[i * 4 + 1] / viewport.height
                                    batch.vertex((x * 2 - 1).toFloat(), (1 - y * 2).toFloat(),
                                        (local[i * 4 + 2] * uMax).toFloat(), (local[i * 4 + 3] * vMax).toFloat(),
                                        x.toFloat(), y.toFloat(), texture.slot, tile.x, tile.y, tile.width, tile.height)
                                    corner++
                                }
                            }
                        }
                        flush()
                        if (layer.blend != 0) {
                            val oldTexture = compositionTexture; compositionTexture = alternateTexture; alternateTexture = oldTexture
                            val oldFramebuffer = compositionFramebuffer; compositionFramebuffer = alternateFramebuffer; alternateFramebuffer = oldFramebuffer
                        }
                    } finally {
                        // Texture eviction can throw after a partial batch; do not retain its arrays.
                        releaseBatches()
                    }
                }
                GLES30.glDisable(GLES30.GL_BLEND)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                drawChecker(true)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU canvas submission failed" }
                rememberPresentation(1)
                completedSceneGeneration = scene.generation()
                completedPreviewToken = drawingPreviewToken
                completedFrameUsesTiles = true
                completedFrameNumber++
                peakTotalGpuBytes = maxOf(peakTotalGpuBytes, totalGpuBytes)
                }
            } finally { borrowedPreview?.let { preview?.releaseBorrow() } }
        } catch (error: Exception) {
            failed(error.message ?: "GPU canvas failed")
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glDisable(GLES30.GL_BLEND)
        }
        lastFrameNanos = System.nanoTime() - start
        lastFrameUploads = uploadCount - previousUploads; lastFrameAllocations = textureAllocations - previousAllocations
        lastFrameEvictions = evictionCount - previousEvictions
        totalNanos += lastFrameNanos; frames++
        if (frames % 60 == 0) Log.d("CompositorGPU", "cpuSubmitAvgMs=${totalNanos / frames / 1e6} draws=$lastFrameDraws " +
            "frameUploads=$lastFrameUploads frameEvictions=$lastFrameEvictions framePageAllocations=$lastFrameAllocations " +
            "pages=${pages.size} allocatedMiB=${allocatedBytes / 1048576.0} liveMiB=${cacheLiveBytes / 1048576.0} " +
            "surfaceMiB=${surfaceBytes / 1048576.0} fallbackMiB=${fallbackBytes / 1048576.0} totalGpuMiB=${totalGpuBytes / 1048576.0} " +
            "peakMiB=${peakTextureBytes / 1048576.0} peakTotalGpuMiB=${peakTotalGpuBytes / 1048576.0}")
    }
    private fun drawReference(scene: NativeScene, gl: GL10?) {
        presentedPath = 0
        completedFrameUsesTiles = false
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glDisable(GLES30.GL_BLEND)
        releaseComposition(); clearTiles()
        val cpu = reference ?: CpuReferenceRenderer(editor, failed).also { it.onSurfaceCreated(gl, null); reference = it }
        cpu.configureSurface(surfaceWidth, surfaceHeight, textureBudgetBytes)
        tileBudgetBytes = maxOf(0, textureBudgetBytes - surfaceBytes)
        cpu.viewport = viewport
        if (cpu.drawScene(scene, gl)) {
            rememberPresentation(2)
            completedSceneGeneration = scene.generation()
            completedPreviewToken = drawingPreviewToken
            completedFrameNumber++
        }
        peakTotalGpuBytes = maxOf(peakTotalGpuBytes, totalGpuBytes)
    }
    private fun rememberPresentation(path: Int) {
        presentedPath = path; presentedViewport = viewport
        presentedWidth = surfaceWidth; presentedHeight = surfaceHeight
    }
    private fun drawDeferred() {
        val reusable = presentedViewport == viewport && presentedWidth == surfaceWidth && presentedHeight == surfaceHeight
        if (reusable && presentedPath == 1 && compositionTexture != 0) drawChecker(true)
        else if (reusable && presentedPath == 2 && reference?.drawCached() == true) Unit
        else drawChecker(false)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU canvas submission failed" }
    }
    private fun drawChecker(hasComposition: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glUseProgram(checker)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositionTexture)
        GLES30.glUniform1i(checkerUniforms.composed, 0)
        GLES30.glUniform1i(checkerUniforms.hasComposition, if (hasComposition) 1 else 0)
        GLES30.glUniform2f(checkerUniforms.surfaceSize, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        vertices.clear()
        for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f))
            vertices.put(floatArrayOf(x, y, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        bindVertices(); GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4); lastFrameDraws++
    }
    private data class Composition(val texture: Int, val framebuffer: Int)
    private fun ensureComposition(needsAlternate: Boolean) {
        if (compositionWidth != surfaceWidth || compositionHeight != surfaceHeight) releaseComposition()
        if (!needsAlternate) releaseAlternate()
        tileBudgetBytes = maxOf(0, textureBudgetBytes - surfaceWidth * surfaceHeight * 4 * if (needsAlternate) 2 else 1)
        evictFor(0, emptySet())
        if (compositionTexture == 0) {
            val primary = allocateComposition()
            compositionTexture = primary.texture; compositionFramebuffer = primary.framebuffer
            compositionWidth = surfaceWidth; compositionHeight = surfaceHeight
        }
        if (needsAlternate && alternateTexture == 0) {
            val alternate = allocateComposition()
            alternateTexture = alternate.texture; alternateFramebuffer = alternate.framebuffer
        }
    }
    private fun allocateComposition(): Composition {
        var texture = 0; var framebuffer = 0
        try {
            val names = IntArray(1); GLES30.glGenTextures(1, names, 0); texture = names[0]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, surfaceWidth, surfaceHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenFramebuffers(1, names, 0); framebuffer = names[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU composition allocation failed" }
            return Composition(texture, framebuffer)
        } catch (error: Exception) {
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            throw error
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }
    private fun releaseAlternate() {
        if (alternateTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(alternateTexture), 0)
        if (alternateFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(alternateFramebuffer), 0)
        alternateTexture = 0; alternateFramebuffer = 0
    }
    /** Transparent RGBA8 composition readback; false when display selected the bounded CPU path. */
    internal fun readComposition(pixels: ByteBuffer): Boolean {
        if (compositionFramebuffer == 0) return false
        require(pixels.capacity().toLong() >= compositionWidth.toLong() * compositionHeight * 4)
        pixels.clear()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, compositionFramebuffer)
        GLES30.glReadPixels(0, 0, compositionWidth, compositionHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return true
    }
    private fun releaseComposition() {
        releaseAlternate()
        if (compositionTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(compositionTexture), 0)
        if (compositionFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(compositionFramebuffer), 0)
        compositionTexture = 0; compositionFramebuffer = 0; compositionWidth = 0; compositionHeight = 0
    }
    private fun releaseSurfaces() { presentedPath = 0; releaseComposition(); reference?.release(); reference = null }
    private fun clearTiles() {
        val saved = tileBudgetBytes; tileBudgetBytes = 0
        evictFor(0, emptySet()); tileBudgetBytes = saved
    }
    private fun prepareTile(index: Int, tile: SceneTileView, layer: SceneLayer, documentWidth: Double, documentHeight: Double): PreparedTile? {
        val transform = layer.transform
        val angle = (transform.rotation % 360.0) * Math.PI / 180
        val cosine = cos(angle); val sine = sin(angle)
        val rightX = tile.x.toDouble() + tile.width; val bottomY = tile.y.toDouble() + tile.height
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        // Subtract the viewport origin before adding offsets, before any Float conversion.
        val centerX = (transform.x - viewport.x) + transform.width / 2
        val centerY = (transform.y - viewport.y) + transform.height / 2
        for (i in 0..3) {
            val pointX = if (i % 2 == 0) tile.x.toDouble() else rightX
            val pointY = if (i < 2) tile.y.toDouble() else bottomY
            val sourceX = (pointX / layer.pixelWidth - 0.5) * transform.width * if (transform.flipX) -1 else 1
            val sourceY = (pointY / layer.pixelHeight - 0.5) * transform.height * if (transform.flipY) -1 else 1
            val x = centerX + cosine * sourceX - sine * sourceY
            val y = centerY + sine * sourceX + cosine * sourceY
            polygonClip.setCorner(i, x, y)
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val left = maxOf(0.0, -viewport.x); val right = minOf(viewport.width, documentWidth - viewport.x)
        val top = maxOf(0.0, -viewport.y); val bottom = minOf(viewport.height, documentHeight - viewport.y)
        if (left >= right || top >= bottom || maxX <= left || minX >= right || maxY <= top || minY >= bottom) return null
        // Clip huge offscreen quads in Double too; sending their extreme NDC coordinates
        // to float clipping would lose a very small visible ROI and its interpolated UVs.
        val clipped = polygonClip.clip(left, right, top, bottom) ?: return null
        return PreparedTile(index, tile, clipped)
    }
    private fun mipLevel(layer: SceneLayer, cosine: Double, sine: Double): Int {
        val sx = surfaceWidth / viewport.width; val sy = surfaceHeight / viewport.height
        val sourceX = layer.transform.width / layer.pixelWidth; val sourceY = layer.transform.height / layer.pixelHeight
        val a = cosine * sourceX * sx; val b = sine * sourceX * sy
        val c = -sine * sourceY * sx; val d = cosine * sourceY * sy
        val sum = a * a + b * b + c * c + d * d; val determinant = a * d - b * c
        val scale = sqrt((sum + sqrt(maxOf(0.0, sum * sum - 4 * determinant * determinant))) / 2)
        return if (!scale.isFinite() || scale <= 0) 0 else floor(log2(1.0 / scale)).toInt().coerceIn(0, 8)
    }
    /** Integer unit mapping has exactly the CPU sampler's source indices, including nearest ties. */
    private fun exactPixelMapping(layer: SceneLayer, documentWidth: Double, documentHeight: Double): IntArray? {
        val transform = layer.transform
        if (viewport.x < 0 || viewport.y < 0 || viewport.x + viewport.width > documentWidth ||
            viewport.y + viewport.height > documentHeight ||
            transform.rotation % 360.0 != 0.0 || transform.flipX || transform.flipY ||
            transform.width != layer.pixelWidth.toDouble() || transform.height != layer.pixelHeight.toDouble() ||
            surfaceWidth !in 1..(1 shl 20) || surfaceHeight !in 1..(1 shl 20)) return null
        val sx = viewport.width / surfaceWidth; val sy = viewport.height / surfaceHeight
        val values = doubleArrayOf(sx, sy, viewport.x, viewport.y, transform.x, transform.y)
        if (values.any { !it.isFinite() || it != floor(it) || abs(it) > (1 shl 28) }) return null
        if (sx < 2 || sy < 2 || sx.toInt() % 2 != 0 || sy.toInt() % 2 != 0 ||
            viewport.width > (1 shl 28) || viewport.height > (1 shl 28)) return null
        val dx = viewport.x - transform.x; val dy = viewport.y - transform.y
        if (abs(dx) + viewport.width + layer.pixelWidth > (1 shl 29) ||
            abs(dy) + viewport.height + layer.pixelHeight > (1 shl 29)) return null
        return intArrayOf(sx.toInt(), sy.toInt(), dx.toInt(), dy.toInt())
    }
    private fun pageSide(required: Int): Int { var side = 1; while (side < required) side *= 2; return side }
    fun setTextureBudget(bytes: Int) {
        requestedBudgetBytes = bytes.coerceIn(0, 64 * 1024 * 1024)
        if (requestedBudgetBytes == 0) pendingClear = true
    }
    private fun applyTextureBudget() {
        if (pendingClear) {
            clearTiles(); releaseSurfaces(); batchPool.clear()
            pendingClear = false
        }
        textureBudgetBytes = requestedBudgetBytes
        tileBudgetBytes = maxOf(0, textureBudgetBytes - surfaceBytes)
        evictFor(0, emptySet())
    }
    private fun evictFor(additional: Int, pinned: Set<Page>): Boolean {
        while (allocatedBytes + additional > tileBudgetBytes) {
            val page = pages.filter { it !in pinned }.minByOrNull { it.lastUse } ?: return false
            for (key in page.keys) { textures.remove(key); evictionCount++ }
            GLES30.glDeleteTextures(1, intArrayOf(page.name), 0)
            textureDeleteCount++
            allocatedBytes -= page.bytes; pages.remove(page); pageEvictions++
        }
        return true
    }
    private fun texture(scene: NativeScene, layer: Int, index: Int, tile: SceneTileView, level: Int,
        pinned: Set<Page>, currentKeys: Set<TextureKey>, flush: () -> Unit): Texture {
        val key = TextureKey(tile.readResourceId, level)
        textures[key]?.let { it.page.lastUse = ++tick; return it }
        val divisor = 1 shl level
        val width = (tile.width + divisor - 1) / divisor; val height = (tile.height + divisor - 1) / divisor
        val side = pageSide(maxOf(width, height))
        var page = pages.firstOrNull { it.side == side && it.free.isNotEmpty() }
        if (page == null) {
            // Recycle a retired resource slot only when none of that page's geometry is pending.
            val retired = textures.entries.firstOrNull { it.value.page.side == side && it.value.page !in pinned && it.key !in currentKeys }
            if (retired != null && pages.sumOf { it.bytes.toLong() } + side.toLong() * side * 4 * slotsPerPage > tileBudgetBytes) {
                val old = retired.value; textures.remove(retired.key); old.page.keys.remove(retired.key)
                old.page.free.addLast(old.slot); evictionCount++; slotReuseCount++; page = old.page
            }
        }
        if (page == null) {
            val bytes = side * side * 4 * slotsPerPage
            if (!evictFor(bytes, pinned)) { flush(); check(evictFor(bytes, emptySet())) { "GPU page budget cannot fit a tile" } }
            val names = IntArray(1); GLES30.glGenTextures(1, names, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, names[0])
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RGBA8, side, side, slotsPerPage)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            page = Page(names[0], side, slotsPerPage); pages.add(page)
            allocatedBytes += bytes; textureAllocations++; peakTextureBytes = maxOf(peakTextureBytes, allocatedBytes)
        }
        val slot = page.free.removeFirst()
        tilePixels.clear(); scene.readTile(layer, index, width * 4, tilePixels, level); tilePixels.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, page.name)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, slot, width, height, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, tilePixels)
        val texture = Texture(page, slot, width * height * 4)
        textures[key] = texture; page.keys.add(key); page.lastUse = ++tick
        uploadCount++; uploadedBytes += texture.bytes; lodUploads[level]++
        return texture
    }
    private fun bindVertices() {
        for (index in 0..4) {
            vertices.position(if (index == 4) 7 else index * 2)
            GLES30.glVertexAttribPointer(index, if (index == 4) 4 else if (index == 3) 1 else 2, GLES30.GL_FLOAT, false, 44, vertices)
            GLES30.glEnableVertexAttribArray(index)
        }
    }
    private fun makeProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type); GLES30.glShaderSource(id, source); GLES30.glCompileShader(id)
            val result = IntArray(1); GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, result, 0)
            check(result[0] != 0) { GLES30.glGetShaderInfoLog(id) }; return id
        }
        val v = shader(GLES30.GL_VERTEX_SHADER, vertex); val f = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val id = GLES30.glCreateProgram(); GLES30.glAttachShader(id, v); GLES30.glAttachShader(id, f); GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        val result = IntArray(1); GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, result, 0)
        check(result[0] != 0) { GLES30.glGetProgramInfoLog(id) }; return id
    }
}
