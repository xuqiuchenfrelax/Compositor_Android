package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal enum class FolderMaskFixture(val stem: String, val label: String, val transform: Transform64? = null,
                                     val childMask: Transform64? = null) {
    OWNER("p193-folder-mask", "folder-mask-owner"),
    INTERSECTION("p202-folder-mask", "folder-mask-intersection"),
    OFFSET_RASTER_MASK("p203-raster-mask", "offset-raster-mask", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(2.5, 2.0, 4.0, 4.0, 0.0, false, false)),
    RASTER_MASK_PHASE("p204-raster-mask", "raster-mask-phase", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(2.0 + 1.0 / 256, 2.0, 4.0, 4.0, 0.0, false, true)),
    RASTER_MASK_CORNER("p205-raster-mask", "raster-mask-corner", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(2.50390625, 2.5, 4.0, 4.0, 0.0, false, false)),
    RASTER_MASK_NEAR_FULL("p206-raster-mask", "raster-mask-near-full", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(2.0 + 1.0 / 256, 2.5, 4.0, 4.0, 0.0, false, false)),
    RASTER_MASK_ROTATION("p207-raster-mask", "raster-mask-rotation", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(2.125, 2.25, 4.0, 4.0, 2.0, false, true)),
    RASTER_MASK_CARDINAL("p208-raster-mask", "raster-mask-cardinal", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(1.0, 3.0, 4.0, 4.0, -90.0, false, false)),
    RASTER_MASK_HALF_EDGE("p209-raster-mask", "raster-mask-half-edge", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(1.5, 2.0, 4.0, 4.0, 90.0, false, false)),
    RASTER_MASK_HALF_ROW("p210-raster-mask", "raster-mask-half-row", Transform64(0.0, 0.0, 8.0, 8.0, 0.0, false, false),
        Transform64(1.0, 1.5, 4.0, 4.0, -90.0, false, false)),
    ROTATION("p194-folder-mask", "folder-mask-rotation", Transform64(1.125, 2.25, 4.5, 4.75, 19.0, false, false)),
    MINIFIED("p195-folder-mask", "folder-mask-minified", Transform64(1.125, 4.0, 1.0, 6.5, -45.0, false, true)),
    EDGE("p196-folder-mask", "folder-mask-edge", Transform64(1.25, 1.125, 4.75, 5.5, 32.0, false, false)),
    CHILD_MIDPOINT("p201-folder-mask", "folder-mask-child-midpoint", Transform64(0.0, 0.0, 8.0, 8.0, 19.0, false, false)),
    MINIFIED_CHILD("p200-folder-mask", "folder-mask-minified-child", Transform64(0.0, 0.0, 8.0, 8.0, 19.0, false, false)),
    SOLID_FRINGE("p199-folder-mask", "folder-mask-solid-fringe", Transform64(0.0, 0.0, 8.0, 8.0, 19.0, false, false)),
    CHILD_TEXEL("p198-folder-mask", "folder-mask-child-texel", Transform64(1.25, 1.125, 4.75, 5.5, 32.0, false, false)),
    CHILD_EDGE("p197-folder-mask", "folder-mask-child-edge", Transform64(1.25, 1.125, 4.75, 5.5, 32.0, false, false)),
}

/** Uses the desktop-export golden after opening real project assets through the workspace. */
internal fun folderMaskOwnerSmoke(instrumentation: Instrumentation, variant: FolderMaskFixture): String {
    val stem = variant.stem
    val label = variant.label
    val context = instrumentation.targetContext
    val assets = instrumentation.context.assets
    val root = File(context.cacheDir, "$label-${System.nanoTime()}").apply { check(mkdir()) }
    val input = File(root, "input.comp")
    val output = File(root, "output.comp")
    val golden = assets.open("$stem.rgba").use { it.readBytes() }
    val limit = 64L * 1024 * 1024
    val placed = Transform64(5.0, 0.0, 2.0, 2.0, 0.0, false, false)
    val view = Viewport(0.0, 0.0, 8.0, 8.0)
    fun metadata(editor: NativeEditor) {
        val layers = editor.state().layers
        val folders = layers.filter { it.isGroup }
        if (variant.transform != null) {
            val folder = folders.single()
            check(folder.transform == variant.transform)
            check(folder.maskSampling == 2)
        } else {
            check(folders.size == 2 && folders[1].parentId == folders[0].id)
            check(folders.map { it.maskSampling } == listOf(2, 1))
        }
        check(folders.all { it.maskPlacement == placed && it.sampling == 0 && it.maskEnabled && !it.maskLinked })
        if (variant.childMask != null) {
            val child = layers.single { !it.isGroup }
            check(child.parentId == folders.single().id && child.maskEnabled && !child.maskLinked)
            check(child.maskWidth == 4 && child.maskHeight == 4 && child.maskSampling == 0)
            check(child.maskPlacement == variant.childMask)
        }
    }
    fun verify(editor: NativeEditor, display: Boolean) {
        checkNotNull(if (display) editor.captureDisplayScene() else editor.captureScene()).use { scene ->
            val buffer = ByteBuffer.allocateDirect(golden.size)
            scene.draw(8, 8, view, buffer)
            val actual = ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
            check(actual.contentEquals(golden)) { "$label pixels differ from Mac export (display=$display)" }
        }
    }
    try {
        check(golden.size == 8 * 8 * 4)
        copyProjectAssets(assets, "$stem.comp", input)
        MoveInspectorFixtureSmoke(instrumentation, label).use { fixture ->
            try {
                fixture.installAuthority { NativeEditor.loadProjectCandidate(context, input, limit, 0) }
                metadata(fixture.editor)
                verify(fixture.editor, true)
                fixture.editor.captureProjectSnapshot().use { snapshot ->
                    NativeProjectWriteControl(limit, fixture.editor.retainedMemory().totalBytes).use { control ->
                        check(snapshot.write(context, output, false, control).successful)
                    }
                }
                fixture.observation("folder-mask-display", JSONObject().put("comparedRgbaBytes", golden.size))
                fixture.successful = true
            } catch (error: Throwable) {
                fixture.diagnose(error)
                throw error
            }
        }
        NativeEditor.loadProjectCandidate(context, output, limit, 0).use { restored ->
            metadata(restored)
            verify(restored, false)
            val png = File(root, "folder-mask.png")
            val uri = Uri.fromFile(png)
            NativeImageIO.exportPng(context, uri, restored)
            NativeImageIO.decode(context, uri).use { decoded ->
                check(decoded.width == 8 && decoded.height == 8)
                val bytes = ByteArray(golden.size).also { decoded.pixels.duplicate().get(it) }
                check(bytes.contentEquals(golden)) { "Exported PNG differs from Mac export" }
            }
            png.copyTo(File(context.filesDir, "$stem.png"), overwrite = true)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(golden).joinToString("") { "%02x".format(it.toInt() and 255) }
        File(context.filesDir, "$stem.json").writeText(JSONObject().put("rgbaBytes", golden.size)
            .put("rgbaSHA256", hash).put("displaySceneExact", true).put("projectReloadExact", true)
            .put("pngExact", true).put("storedQualitiesRetained", true).toString(2))
        return "$label matches desktop across retained workspace display scene, project reload and PNG ($hash)"
    } finally {
        root.deleteRecursively()
    }
}
