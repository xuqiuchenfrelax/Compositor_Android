package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Metadata expectations are emitted by the actual desktop Swift method bodies. */
internal fun selectedLayerActionsSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val input = JSONObject(instrumentation.context.assets.open("selected-layer-actions/pc-cases.json")
        .bufferedReader().use { it.readText() }).getJSONArray("cases")
    val initialLedger = ImageIoAdmission.ledger.snapshot()
    val evidence = File(context.cacheDir, "p143-selected-layer-evidence").apply { check(mkdirs()) }
    val saved = JSONArray()
    var states = 0
    var operations = 0
    var refusals = 0
    fun array(values: JSONArray) = List(values.length()) { values.getInt(it) }
    fun setup(editor: NativeEditor, row: JSONObject): Map<Int, Long> {
        val layers = row.getJSONArray("layers")
        val ids = linkedMapOf<Int, Long>()
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            val id = when {
                layer.getBoolean("group") -> editor.addGroup()
                layer.getBoolean("adjustment") -> editor.commitAdjustmentLayer(null, AdjustmentLayerModel.create(AdjustmentKind.INVERT))
                else -> editor.importPixels(2, 2, ByteBuffer.allocateDirect(16).apply {
                    repeat(4) { put(byteArrayOf((20 + index * 20).toByte(), 60, 90, -1)) }; rewind()
                })
            }
            ids[layer.getInt("id")] = id
            editor.renameLayer(id, "PC ${layer.getInt("id")}")
        }
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index); val id = ids.getValue(layer.getInt("id"))
            if (layer.getInt("parent") != 0) editor.reparentLayer(id, ids.getValue(layer.getInt("parent")))
            if (layer.getInt("source") != 0) editor.clipLayer(id, ids.getValue(layer.getInt("source")))
            if (!layer.getBoolean("visible")) editor.setVisible(id, false)
        }
        // Structural APIs may reorder storage. Restore the oracle's valid bottom-to-top order.
        for (index in 0 until layers.length()) {
            val id = ids.getValue(layers.getJSONObject(index).getInt("id"))
            if (editor.state().layers.indexOfFirst { it.id == id } != index) editor.moveLayer(id, index)
        }
        val selected = array(row.getJSONArray("selected")).map(ids::getValue)
        val active = row.getInt("active").let { if (it == 0) 0L else ids.getValue(it) }
        editor.selectLayers(selected, active)
        return ids
    }
    try {
        for (index in 0 until input.length()) {
            val row = input.getJSONObject(index)
            if (!row.getBoolean("runtimeEligible")) continue
            states++
            for (operation in listOf("visibility", "clipping")) {
                val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
                NativeEditor.createWithLedgerForTest(8, ledger, addDefaultLayers = false).use { editor ->
                    val ids = setup(editor, row)
                    val before = editor.state()
                    val selection = editor.selection()
                    val plan = checkNotNull(before.selectedLayerActions)
                    val expected = row.getJSONObject(operation)
                    val enabled = if (operation == "visibility") plan.canVisibility else plan.canClipping
                    check(enabled == expected.getBoolean("enabled")) {
                        "PC $operation gate ${row.getString("name")}: $enabled != $expected"
                    }
                    if (operation == "visibility") check(plan.visibilityValue == expected.getString("title").startsWith("Show ")) {
                        "PC visibility title/value differs: ${row.getString("name")}"
                    }
                    if (operation == "clipping") check(plan.releasesClipping == expected.getBoolean("releases"))
                    val command = if (operation == "visibility") EditorCommand.ToggleSelectedVisibility(plan)
                        else EditorCommand.ToggleSelectedClipping(plan)
                    if (!enabled) {
                        check(runCatching { command.apply(editor) }.isFailure) { "Disabled PC action accepted: ${row.getString("name")}" }
                        check(editor.state().layers == before.layers && editor.currentRevision() == before.revision)
                        refusals++
                    } else {
                        command.apply(editor)
                        val after = editor.state()
                        val wantedVisible = expected.getJSONArray("visible")
                        val wantedSources = expected.getJSONArray("sources")
                        val layerRows = row.getJSONArray("layers")
                        for (i in 0 until layerRows.length()) {
                            val id = ids.getValue(layerRows.getJSONObject(i).getInt("id"))
                            val layer = after.layers.single { it.id == id }
                            val source = wantedSources.getInt(i).let { if (it == 0) 0L else ids.getValue(it) }
                            check(layer.visible == wantedVisible.getBoolean(i) && layer.maskSourceId == source) {
                                "PC $operation ${row.getString("name")} layer $i: visible=${layer.visible}, source=${layer.maskSourceId}; $expected"
                            }
                        }
                        check(after.document.activeLayerId == before.document.activeLayerId && editor.selection().roots == selection.roots)
                        val changed = after.layers != before.layers
                        check(after.revision == before.revision + if (changed) 1 else 0) { "Selected layers must publish once" }
                        if (changed) {
                            editor.undo(false); check(editor.state().layers == before.layers) { "One Undo did not restore selected-layer action" }
                            editor.undo(true); check(editor.state().layers == after.layers) { "One Redo did not restore selected-layer action" }
                        }
                        operations++
                        val name = row.getString("name")
                        val retain = changed && ((operation == "visibility" && name == "active-union-normalized") ||
                            (operation == "clipping" && name in setOf("active-union-normalized", "release-cascade-unselected", "release-separated-bases")))
                        if (retain) {
                            val checkpoint = File(evidence, "$operation-$index.checkpoint")
                            editor.save(checkpoint)
                            NativeEditor.load(checkpoint).use { restored -> check(restored.state().layers == after.layers) }
                            typedProjectRoundTripSmoke(context, editor, ids.values.first()) { restored, _ ->
                                val current = restored.state().layers.associateBy { it.uuid }
                                for (old in after.layers) {
                                    val actual = current.getValue(old.uuid)
                                    val sourceUUID = after.layers.firstOrNull { it.id == old.maskSourceId }?.uuid
                                    val actualSourceUUID = restored.state().layers.firstOrNull { it.id == actual.maskSourceId }?.uuid
                                    check(actual.visible == old.visible && actualSourceUUID == sourceUUID)
                                }
                            }
                            val target = File(evidence, "$operation-$index.comp")
                            editor.captureProjectSnapshot().use { snapshot ->
                                NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                                    check(snapshot.write(context, target, false, control).successful)
                                }
                            }
                            saved.put(JSONObject().put("name", name).put("operation", operation).put("project", target.name)
                                .put("layers", JSONArray(after.layers.map { layer -> JSONObject().put("uuid", layer.uuid)
                                    .put("visible", layer.visible).put("sourceUUID", after.layers.firstOrNull { it.id == layer.maskSourceId }?.uuid ?: JSONObject.NULL) })))
                        }
                    }
                }
                check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
            }
        }
        selectedActionRefusalAndRetry(context)
        check(saved.length() == 4) { "Expected four distinct PC project readback fixtures, got ${saved.length()}" }
        File(evidence, "manifest.json").writeText(JSONObject().put("fixtures", saved).toString(2))
        return "desktopInputs=${input.length()} nativeRepresentableStates=$states operations=$operations disabledRefusals=$refusals " +
            "oneUndoRedo=true staleRevisionAndSelection=true budgetRetry=true checkpointAndComp=true retainedPCProjects=${saved.length()}"
    } finally {
        check(ImageIoAdmission.ledger.snapshot().let { it.reservedBytes == initialLedger.reservedBytes && it.reservations == initialLedger.reservations })
    }
}

private fun selectedActionRefusalAndRetry(context: android.content.Context) {
    val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
    NativeEditor.createWithLedgerForTest(8, ledger, addDefaultLayers = false).use { editor ->
        val ids = List(3) { editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).putInt(-1).apply { rewind() }) }
        editor.selectLayers(ids.drop(1), ids.last())
        val initial = editor.state()
        val stale = checkNotNull(initial.selectedLayerActions)
        editor.selectLayer(ids.last())
        editor.selectLayers(ids.drop(1), ids.last())
        val afterSelection = editor.state()
        check(afterSelection.copy(selectedLayerActions = null) == initial.copy(selectedLayerActions = null))
        check(afterSelection != initial && checkNotNull(afterSelection.selectedLayerActions).selectionEpoch > stale.selectionEpoch) {
            "Same-primary selection changes must refresh presentation state without changing the committed document"
        }
        check(runCatching { EditorCommand.ToggleSelectedClipping(stale).apply(editor) }.isFailure) {
            "Selected-layer command accepted a stale selection epoch after ABA selection"
        }
        check(editor.state().layers == afterSelection.layers && editor.currentRevision() == afterSelection.revision)
        val oldRevision = checkNotNull(afterSelection.selectedLayerActions)
        editor.renameLayer(ids.first(), "Renamed base")
        val renamed = editor.state()
        check(runCatching { EditorCommand.ToggleSelectedVisibility(oldRevision).apply(editor) }.isFailure)
        check(editor.state().layers == renamed.layers && editor.currentRevision() == renamed.revision)
        val retry = checkNotNull(renamed.selectedLayerActions)
        val memory = ledger.snapshot()
        ledger.reserve(memory.availableBytes, "competing selected-layer action").use {
            check(runCatching { EditorCommand.ToggleSelectedClipping(retry).apply(editor) }.isFailure)
        }
        check(editor.state().layers == renamed.layers && editor.currentRevision() == renamed.revision)
        EditorCommand.ToggleSelectedClipping(retry).apply(editor)
        check(editor.state().layers.drop(1).all { it.maskSourceId == ids.first() })
        editor.undo(false); check(editor.state().layers == renamed.layers)
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        editor.selectLayer(ids.last())
        val beforeDraft = editor.state()
        val oldGeneration = checkNotNull(beforeDraft.selectedLayerActions)
        editor.beginTransformDraft(context, ids.last()).use { draft ->
            val box = beforeDraft.layers.last().transform
            draft.update(box.copy(x = box.x + 1.0))
            check(editor.state().selectedLayerActions == null) { "Active Move must suspend selected-layer actions" }
        }
        val afterDraft = editor.state()
        check(afterDraft.copy(selectedLayerActions = null) == beforeDraft.copy(selectedLayerActions = null))
        check(checkNotNull(afterDraft.selectedLayerActions).generation > oldGeneration.generation)
        check(runCatching { EditorCommand.ToggleSelectedVisibility(oldGeneration).apply(editor) }.isFailure) {
            "Cancelled preview must invalidate the old scene-generation action stamp"
        }
    }
    check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
}
