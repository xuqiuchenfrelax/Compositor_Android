package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONObject
import java.io.File

/** Independent desktop outputs plus actual native type publication, history and persistence. */
internal fun layerFilterSmoke(instrumentation: Instrumentation): String {
    val fixture = JSONObject(instrumentation.context.assets.open("layer-filters/pc-cases.json").bufferedReader().use { it.readText() })
    val layers = fixture.getJSONArray("layers").let { rows -> (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val kind = LayerKind.entries.single { it.name.lowercase() == row.getString("kind") }
        LayerState(row.getLong("id"), 1.0, true, 0, name = row.getString("name"),
            parentId = row.getLong("parent"), isGroup = kind == LayerKind.FOLDER, kind = kind)
    } }
    val cases = fixture.getJSONArray("cases")
    for (index in 0 until cases.length()) {
        val row = cases.getJSONObject(index)
        fun ids(field: String) = row.getJSONArray(field).let { values -> (0 until values.length()).map { values.getLong(it) } }
        val types = row.getJSONArray("types").let { values -> (0 until values.length()).map { i ->
            when (values.getString(i)) {
                "image" -> LayerFilterType.IMAGES
                "shape" -> LayerFilterType.SHAPES
                "text" -> LayerFilterType.TEXT
                "folder" -> LayerFilterType.FOLDERS
                "adjustment" -> LayerFilterType.ADJUSTMENTS
                else -> LayerFilterType.SELECTED
            }
        }.toSet() }
        val actual = filteredDisplayedLayers(layers, ids("collapsed").toSet(), row.getString("query"), types, ids("selected").toSet()).map { it.id }
        check(actual == ids("expected")) { "PC filter case $index: $actual != ${ids("expected")}" }
    }
    val context = instrumentation.targetContext
    val checkpoint = File(context.cacheDir, "p126-filter-${System.nanoTime()}.checkpoint")
    try {
        NativeEditor(96, addDefaultLayers = true).use { editor ->
            seedLayerFilterDocument(editor)
            assertLayerFilterKinds(editor)
            val before = editor.state()
            repeat(5) { assertLayerFilterKinds(editor) }
            check(editor.state() == before) { "Reading layer kinds mutated the document" }
            val line = before.layers.single { it.name == "Line" }.id
            editor.startStroke(line, 5f, erasing = true)
            editor.samples(doubleArrayOf(20.0, 20.0, 1.0, 0.0)); editor.endStroke(false)
            check(editor.state().layers.single { it.id == line }.kind == LayerKind.IMAGE) { "Raster edit retained shape classification" }
            editor.undo(false)
            check(editor.state().layers.single { it.id == line }.kind == LayerKind.SHAPE)
            editor.undo(true)
            check(editor.state().layers.single { it.id == line }.kind == LayerKind.IMAGE)
            editor.undo(false)
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use(::assertLayerFilterKinds)
            val text = editor.state().layers.single { it.name == "中文 Text" }.id
            typedProjectRoundTripSmoke(context, editor, text) { restored, _ -> assertLayerFilterKinds(restored) }
        }
    } finally { checkpoint.delete() }
    val ui = layerFilterUiSmoke(instrumentation)
    return "pcCases=${cases.length()} nativeKinds=5 rasterRetirementUndoRedo=true checkpoint=true projectRoundTrip=true $ui"
}

internal fun seedLayerFilterDocument(editor: NativeEditor) {
    val initial = editor.state().layers
    editor.renameLayer(initial[0].id, "Image")
    editor.renameLayer(initial[1].id, "Blank")
    val text = editor.commitTextLayer(null, TextLayerModel(content = "中文", fontSize = 14.0), 2.0, 2.0)
    editor.renameLayer(text, "中文 Text")
    val line = editor.createShape(ShapeSpec(ShapeKind.LINE, 48, 48, 0xff319be7.toInt(), 0.0, 3.0), 0.0, 0.0)
    editor.renameLayer(line, "Line")
    val adjustment = editor.commitAdjustmentLayer(null, AdjustmentLayerModel.create(AdjustmentKind.INVERT))
    editor.renameLayer(adjustment, "Tone")
    val folder = editor.addGroup()
    editor.renameLayer(folder, "Folder")
    editor.reparentLayer(text, folder)
    editor.reparentLayer(line, folder)
    editor.selectLayer(text)
}

internal fun assertLayerFilterKinds(editor: NativeEditor) {
    val kinds = editor.state().layers.associate { it.name to it.kind }
    check(kinds == mapOf("Image" to LayerKind.IMAGE, "Blank" to LayerKind.IMAGE,
        "中文 Text" to LayerKind.TEXT, "Line" to LayerKind.SHAPE,
        "Tone" to LayerKind.ADJUSTMENT, "Folder" to LayerKind.FOLDER)) { "Unexpected native kinds: $kinds" }
}
