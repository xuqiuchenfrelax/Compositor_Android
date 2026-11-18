package com.pojia.compositor.mobile

import java.io.File
import java.nio.ByteBuffer

/** Native typed text creation/edit/readback/undo contract using the same ABI path as the inspector. */
internal fun textLayerNativeSmoke(context: android.content.Context): String {
    NativeEditor(16, addDefaultLayers = true).use { ordinary ->
        val rasterLayer = ordinary.state().layers.firstOrNull { it.assetPresent }
            ?: error("Default document did not create a raster layer")
        check(ordinary.textStyleOrNull(rasterLayer.id) == null) { "Ordinary raster layer was reported as typed text" }
    }
    NativeEditor(128, addDefaultLayers = false).use { editor ->
        val original = TextLayerModel(content = "中文\uD83D\uDE80", fontName = "Noto Sans CJK", fontSize = 22.0,
            alignment = TextParagraphAlignment.CENTER, tracking = 1.25, leading = 30.0,
            colorRuns = listOf(TextColorRun(2, 2, 1.0, .25, .1)),
            fontRuns = listOf(TextFontRun(0, 2, "Noto Serif CJK"))).validate()
        val id = editor.commitTextLayer(null, original, 20.0, 24.0)
        check(id > 0)
        check(editor.textStyleOrNull(id) == original)
        val layer = editor.state().layers.single { it.id == id }
        check(layer.assetPresent && layer.pixelWidth > 0 && layer.pixelHeight > 0)
        editor.createMask(id, white = true)
        val maskedLayer = editor.state().layers.single { it.id == id }
        val originalMask = ByteBuffer.allocateDirect(maskedLayer.maskWidth * maskedLayer.maskHeight)
        editor.maskPixels(id, maskedLayer.maskWidth, originalMask)

        val edited = original.replacingContent("新中文\uD83D\uDE80").copy(leading = 36.0).validate()
        check(editor.commitTextLayer(id, edited, 0.0, 0.0) == id)
        check(editor.textStyleOrNull(id) == edited)
        val afterEdit = editor.state().layers.single { it.id == id }
        check(afterEdit.hasMask && afterEdit.maskWidth == maskedLayer.maskWidth && afterEdit.maskHeight == maskedLayer.maskHeight)
        val retainedMask = ByteBuffer.allocateDirect(afterEdit.maskWidth * afterEdit.maskHeight)
        editor.maskPixels(id, afterEdit.maskWidth, retainedMask)
        check((0 until retainedMask.capacity()).all { retainedMask.get(it) == originalMask.get(it) })
        editor.undo(false)
        check(editor.textStyleOrNull(id) == original) { "Undo did not restore source text metadata" }
        editor.undo(true)
        check(editor.textStyleOrNull(id) == edited) { "Redo did not restore source text metadata" }
        val checkpoint = File.createTempFile("typed-text-recovery", ".checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { recovered ->
                check(recovered.textStyleOrNull(id) == edited) { "Checkpoint recovery lost UTF-16 style runs" }
                val recoveredLayer = recovered.state().layers.single { it.id == id }
                check(recoveredLayer.hasMask)
                val recoveredMask = ByteBuffer.allocateDirect(recoveredLayer.maskWidth * recoveredLayer.maskHeight)
                recovered.maskPixels(id, recoveredLayer.maskWidth, recoveredMask)
                check((0 until recoveredMask.capacity()).all { recoveredMask.get(it) == originalMask.get(it) })
            }
        } finally { checkpoint.delete() }
        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId ->
            check(restored.textStyleOrNull(restoredId) == edited)
        }
        editor.undo(false)
        editor.undo(false)
        editor.undo(false)
        check(editor.state().layers.none { it.id == id }) { "Undo did not remove the new typed text layer" }
    }
    paragraphTextNativeSmoke(context)
    return "nativeTypedText=Chinese+emoji createEditReadbackUndoRedo=verified checkpointRecovery=verified maskPreserved=verified utf16Runs=roundtrip rasterCache=present typedProjectSaveReopen=exact paragraphResize=scaleRotationFlipsAnchorHistoryRecoveryVerified"
}

private fun paragraphTextNativeSmoke(context: android.content.Context) {
    NativeEditor(256, addDefaultLayers = false).use { editor ->
        val style = TextLayerModel(content = "中文段落🚀 自动换行\nSecond paragraph", fontSize = 24.0,
            boxWidth = 160.0, boxHeight = 100.0, alignment = TextParagraphAlignment.CENTER).validate()
        val id = editor.commitTextLayer(null, style, 40.0, 60.0)
        val before = editor.state().layers.single()
        val placement = Transform64(30.0, 40.0, 200.0, 75.0, 37.0, flipX = true, flipY = true)
        editor.setTransform(id, placement)
        editor.createMask(id, white = true)
        val masked = editor.state().layers.single()
        check(masked.maskPlacement == null)
        val edited = style.withTextBox(240.0, 144.0)
        check(editor.commitTextLayer(id, edited, 0.0, 0.0) == id)
        val after = editor.state().layers.single()
        check(before.pixelWidth == 160 && before.pixelHeight == 100 && after.pixelWidth == 240 && after.pixelHeight == 144)
        check(after.transform.width == 300.0 && after.transform.height == 108.0 &&
            after.transform.rotation == 37.0 && after.transform.flipX && after.transform.flipY)
        fun topLeft(t: Transform64): Pair<Double, Double> {
            val matrix = android.graphics.Matrix()
            matrix.setRotate(t.rotation.toFloat())
            val corner = floatArrayOf((-t.width / 2).toFloat(), (-t.height / 2).toFloat())
            matrix.mapPoints(corner)
            return t.x + t.width / 2 + corner[0] to t.y + t.height / 2 + corner[1]
        }
        val a = topLeft(placement); val b = topLeft(after.transform)
        check(kotlin.math.abs(a.first - b.first) < .0001 && kotlin.math.abs(a.second - b.second) < .0001)
        check(after.maskWidth == masked.maskWidth && after.maskHeight == masked.maskHeight && after.hasMask)
        check(after.maskPlacement == placement && after.maskSampling == masked.sampling) { "Text resize moved the original mask" }
        editor.undo(false)
        check(editor.textStyleOrNull(id) == style && editor.state().layers.single().transform == placement)
        check(editor.state().layers.single().maskPlacement == null)
        editor.undo(true)
        check(editor.textStyleOrNull(id) == edited && editor.state().layers.single().transform == after.transform)
        check(editor.state().layers.single().maskPlacement == placement)
        val checkpoint = File(context.cacheDir, "p115-paragraph-${System.nanoTime()}.checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { restored ->
                check(restored.textStyleOrNull(id) == edited && restored.state().layers.single().transform == after.transform)
                check(restored.state().layers.single().maskPlacement == placement)
            }
        } finally { check(checkpoint.delete()) }
        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId ->
            check(restored.textStyleOrNull(restoredId) == edited && restored.state().layers.single().transform == after.transform)
            check(restored.state().layers.single().maskPlacement == placement)
        }
    }
}
