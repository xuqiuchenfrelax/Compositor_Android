package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

internal fun layerAlignmentSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val cases = JSONObject(instrumentation.context.assets.open("layer-alignment/pc-cases.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
    for (index in 0 until cases.length()) {
        val row = cases.getJSONObject(index)
        val boxes = row.getJSONArray("boxes")
        val items = List(boxes.length()) { i -> boxes.getJSONArray(i).let { b ->
            AlignmentItem(emptyList(), MoveBounds(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)))
        } }
        val actual = if (row.getString("kind") == "align") {
            val t = row.getJSONArray("target")
            LayerAlignmentGeometry.alignmentOffsets(items, LayerAlignment.entries[row.getInt("operation")], 160, 120,
                MoveBounds(t.getDouble(0), t.getDouble(1), t.getDouble(2), t.getDouble(3)))
        } else LayerAlignmentGeometry.distributionOffsets(items, LayerDistribution.entries[row.getInt("operation")])
        val expected = row.getJSONArray("offsets")
        actual.forEachIndexed { i, offset ->
            val e = expected.getJSONArray(i)
            check(offset.x == e.getDouble(0) && offset.y == e.getDouble(1)) { "PC alignment oracle case $index item $i: $offset != $e" }
        }
    }
    // Native transactions use actual PC output for three visible, asset-bearing layers.
    var transactions = 0
    for (index in 0 until cases.length()) {
        val row = cases.getJSONObject(index)
        val boxes = row.getJSONArray("boxes")
        if (boxes.length() != 3 || (row.getString("kind") == "align" && row.getInt("targetKind") != 1)) continue
        // The first three-layer seed is sufficient here; every numeric oracle is checked above.
        if (index >= 66) break
        NativeEditor(160, addDefaultLayers = false).use { editor ->
            val ids = List(3) { i ->
                val pixels = ByteBuffer.allocateDirect(4).put(byteArrayOf((70 + 40 * i).toByte(), 90, 120, -1)).apply { rewind() }
                val id = editor.importPixels(1, 1, pixels)
                val b = boxes.getJSONArray(i)
                editor.setTransform(id, Transform64(b.getDouble(0), b.getDouble(1), b.getDouble(2) - b.getDouble(0), b.getDouble(3) - b.getDouble(1)))
                id
            }
            val before = editor.state()
            if (row.getString("kind") == "align") EditorCommand.AlignLayers(LayerAlignment.entries[row.getInt("operation")], ids).apply(editor)
            else EditorCommand.DistributeLayers(LayerDistribution.entries[row.getInt("operation")], ids).apply(editor)
            val after = editor.state()
            val offsets = row.getJSONArray("offsets")
            after.layers.forEachIndexed { i, layer ->
                val original = before.layers[i].transform; val delta = offsets.getJSONArray(i)
                check(layer.transform == original.copy(x = original.x + delta.getDouble(0), y = original.y + delta.getDouble(1)))
            }
            if (after.layers != before.layers) {
                check(after.revision == before.revision + 1) { "Align/distribute must publish exactly once" }
                editor.undo(false); check(editor.state().layers == before.layers) { "One Undo did not restore all layers" }
                editor.undo(true); check(editor.state().layers == after.layers) { "One Redo did not restore all layers" }
                val checkpoint = File(context.cacheDir, "p128-alignment-${System.nanoTime()}.checkpoint")
                try {
                    editor.save(checkpoint)
                    NativeEditor.load(checkpoint).use { check(it.state().layers == after.layers) }
                } finally { checkpoint.delete() }
                typedProjectRoundTripSmoke(context, editor, ids[0]) { restored, _ ->
                    check(restored.state().layers.map { it.transform } == after.layers.map { it.transform })
                }
                val packageRoot = File(context.cacheDir, "p128-alignment-projects").apply { mkdirs() }
                val target = File(packageRoot, "operation-$index.comp")
                if (target.exists()) check(target.deleteRecursively())
                editor.captureProjectSnapshot().use { snapshot ->
                    NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                        check(snapshot.write(context, target, false, control).successful)
                    }
                }
            }
            transactions++
        }
    }
    check(transactions == 10) { "Expected all six alignments and four distributions, got $transactions" }
    alignmentGroupingAndMasks()
    return "pcCases=${cases.length()} nativeOperations=$transactions oneUndoRedo=true checkpoint=true compRoundTrip=true groupsMasksSelection=true ${layerAlignmentUiSmoke(instrumentation)}"
}

internal fun seedAlignmentDocument(editor: NativeEditor): List<Long> = List(3) { i ->
    val pixels = ByteBuffer.allocateDirect(16).apply {
        repeat(4) { put(byteArrayOf((60 + i * 60).toByte(), 100, 110, -1)) }
        rewind()
    }
    editor.importPixels(2, 2, pixels).also { id ->
        editor.renameLayer(id, "Align ${i + 1}")
        editor.setTransform(id, Transform64(10.5 + i * 32, 8.0 + i * 12, 10.0, 8.0))
    }
}

private fun alignmentGroupingAndMasks() {
    NativeEditor(160, addDefaultLayers = false).use { editor ->
        val ids = seedAlignmentDocument(editor)
        editor.createMask(ids[1], true)
        editor.setMaskPlacement(ids[1], Transform64(43.5, 20.0, 10.0, 8.0), 0)
        editor.createMask(ids[2], true)
        editor.setMaskPlacement(ids[2], Transform64(75.5, 32.0, 10.0, 8.0), 0)
        editor.linkMask(ids[2], false)
        val group = editor.addGroup()
        editor.reparentLayer(ids[0], group); editor.reparentLayer(ids[1], group)
        val nested = editor.addGroup(); editor.reparentLayer(nested, group)
        val hidden = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).putInt(-1).apply { rewind() })
        editor.reparentLayer(hidden, nested); editor.setVisible(hidden, false)
        val grouped = LayerAlignmentGeometry.items(editor.state(), listOf(group, ids[0], ids[2]))
        check(grouped.size == 2 && grouped[0].members.map { it.id } == ids.take(2))
        val before = editor.state()
        EditorCommand.AlignLayers(LayerAlignment.LEFT, listOf(group, ids[0], ids[2])).apply(editor)
        val moved = editor.state()
        check(moved.layers.single { it.id == ids[0] }.transform == before.layers.single { it.id == ids[0] }.transform)
        check(moved.layers.single { it.id == ids[2] }.transform.x == 10.5)
        check(moved.layers.single { it.id == ids[2] }.maskPlacement == before.layers.single { it.id == ids[2] }.maskPlacement)
        editor.undo(false); check(editor.state().layers == before.layers)
        EditorCommand.AlignLayers(LayerAlignment.LEFT, ids).apply(editor)
        val linked = editor.state().layers.single { it.id == ids[1] }
        check(linked.transform.x == 10.5 && linked.maskPlacement?.x == 11.5) { "Linked mask did not follow aligned layer" }
        editor.undo(false)
        editor.setPixelSelection(PixelSelectionRect(20.0, 30.0, 40.0, 40.0))
        EditorCommand.AlignLayers(LayerAlignment.RIGHT, listOf(ids[0])).apply(editor)
        check(editor.state().layers.single { it.id == ids[0] }.transform.x == 50.5) { "Selection target or half-away rounding differs from PC" }
        editor.undo(false); editor.setPixelSelection(null)
        EditorCommand.AlignLayers(LayerAlignment.RIGHT, listOf(ids[0])).apply(editor)
        check(editor.state().layers.single { it.id == ids[0] }.transform.x == 150.5) { "Single item must align to canvas" }
        val rotated = editor.state().layers.single { it.id == ids[0] }.copy(transform = Transform64(10.0, 20.0, 12.0, 4.0, 90.0))
        val bounds = LayerAlignmentGeometry.items(EditorState(layers = listOf(rotated.copy(parentId = 0))), listOf(rotated.id)).single().box
        check(kotlin.math.abs(bounds.left - 14.0) < 1e-10 && kotlin.math.abs(bounds.top - 16.0) < 1e-10)
    }
}
