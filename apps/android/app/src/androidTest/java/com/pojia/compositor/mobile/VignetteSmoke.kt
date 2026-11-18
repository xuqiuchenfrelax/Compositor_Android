package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import org.json.JSONObject

internal fun vignettePixels(editor: NativeEditor): ByteArray {
    val bytes = ByteBuffer.allocateDirect(editor.canvasWidth * editor.canvasHeight * 4)
    check(editor.draw(editor.canvasWidth, editor.canvasHeight, 0.0, 0.0,
        editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), bytes))
    return ByteArray(bytes.capacity()).also { bytes.rewind(); bytes.get(it) }
}

internal fun vignetteSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val cases = JSONObject(instrumentation.context.assets.open("vignette/pc-cases.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
    var pixels = 0
    for (index in 0 until cases.length()) {
        val row = cases.getJSONObject(index); val w = row.getInt("width"); val h = row.getInt("height")
        val input = row.getJSONArray("pixels").let { p -> ByteArray(p.length()) { p.getInt(it).toByte() } }
        val expected = row.getJSONArray("expected").let { p -> ByteArray(p.length()) { p.getInt(it).toByte() } }
        NativeEditor(maxOf(w, h), addDefaultLayers = false).use { editor ->
            editor.crop(w, h, 0.0, 0.0)
            val image = ByteBuffer.allocateDirect(input.size).put(input).apply { rewind() }
            editor.importPixels(w, h, image)
            val before = editor.state()
            val style = AdjustmentLayerModel.fromMacJson(JSONObject(AdjustmentLayerModel.create(AdjustmentKind.VIGNETTE).toMacJson().toString()).apply {
                val settings = row.getJSONObject("settings")
                if (settings.has("filterLayerSettings")) put("filterLayerSettings", settings.getJSONObject("filterLayerSettings"))
            })
            val id = editor.commitAdjustmentLayer(null, style)
            check(editor.state().revision == before.revision + 1)
            check(vignettePixels(editor).contentEquals(expected)) { "PC vignette pixel mismatch case $index" }
            editor.undo(false); check(vignettePixels(editor).contentEquals(input))
            editor.undo(true); check(vignettePixels(editor).contentEquals(expected))
            // White/black masks force the general graph path rather than the whole-buffer fast path.
            if (index % 20 == 0) {
                editor.createMask(id, true); check(vignettePixels(editor).contentEquals(expected))
                editor.deleteMask(id); editor.createMask(id, false); check(vignettePixels(editor).contentEquals(input))
                editor.enableMask(id, false); check(vignettePixels(editor).contentEquals(expected))
                editor.deleteMask(id)
            }
            if (index % 60 == 0) {
                val checkpoint = File(context.cacheDir, "p129-$index.checkpoint")
                try {
                    editor.save(checkpoint)
                    NativeEditor.load(checkpoint).use { check(vignettePixels(it).contentEquals(expected)) }
                } finally { checkpoint.delete() }
                typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId ->
                    check(restored.adjustmentStyleOrNull(restoredId)!!.vignette == style.vignette)
                }
                val directory = File(context.cacheDir, "p129-vignette-projects").apply { mkdirs() }
                val target = File(directory, "vignette-$index.comp")
                if (target.exists()) check(target.deleteRecursively())
                editor.captureProjectSnapshot().use { snapshot ->
                    NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                        check(snapshot.write(context, target, false, control).successful)
                    }
                }
                check(JSONObject(File(target, "manifest.json").readText()).getInt("version") == 18)
                val manifest = File(target, "manifest.json")
                val originalManifest = manifest.readText()
                try {
                    manifest.writeText(JSONObject(originalManifest).put("version", 17).toString())
                    val rejected = runCatching {
                        NativeEditor.loadProjectCandidate(context, target, 128L * 1024 * 1024, 0).use { }
                    }.exceptionOrNull()
                    check(rejected is ProjectImportException && rejected.status == 8) { "Vignette must reject a pre-v18 project" }
                    check(vignettePixels(editor).contentEquals(expected))
                } finally { manifest.writeText(originalManifest) }
            }
            pixels += w * h
        }
    }
    return "pcCases=${cases.length()} pixels=$pixels rgbaBytesExact=true maskGraph=true undoRedo=true checkpoint=true projectV18=true rejectsPreV18=true ${vignetteUiSmoke(instrumentation)}"
}
