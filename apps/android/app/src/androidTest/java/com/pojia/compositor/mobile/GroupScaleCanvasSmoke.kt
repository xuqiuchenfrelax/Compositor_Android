package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal data class GroupScaleFixture(val a: Long, val b: Long, val group: Long, val hidden: Long)

internal fun installGroupScaleFixture(editor: NativeEditor, grouped: Boolean): GroupScaleFixture {
    fun pixels(red: Int, green: Int): Long = editor.importPixels(4, 4,
        ByteBuffer.allocateDirect(64).apply { repeat(16) { put(byteArrayOf(red.toByte(), green.toByte(), 0, -1)) }; rewind() })
    val a = pixels(180, 0); val b = pixels(0, 180)
    editor.renameLayer(a, "Scale A"); editor.renameLayer(b, "Scale B")
    editor.setTransform(a, Transform64(20.0, 20.0, 16.0, 16.0))
    editor.setTransform(b, Transform64(60.0, 40.0, 16.0, 16.0, flipX = true))
    val group = editor.addGroup()
    editor.renameLayer(group, "Scale group")
    val nested = editor.addGroup()
    editor.reparentLayer(nested, group)
    val hidden = pixels(0, 0)
    editor.setTransform(hidden, Transform64(180.0, 180.0, 16.0, 16.0))
    editor.setVisible(hidden, false); editor.reparentLayer(hidden, group)
    if (grouped) {
        editor.reparentLayer(a, nested); editor.reparentLayer(b, group); editor.selectLayer(group)
    } else editor.selectLayers(listOf(a, b), a)
    return GroupScaleFixture(a, b, group, hidden)
}

internal fun groupScaleBoxEquals(actual: Transform64, expected: Transform64): Boolean =
    actual.numbers().zip(expected.numbers()).all { (a, b) -> abs(a - b) < 1e-8 } &&
        actual.flipX == expected.flipX && actual.flipY == expected.flipY

internal fun groupScalePreview(editor: NativeEditor, ids: GroupScaleFixture,
    a: Transform64, b: Transform64): Boolean = editor.serialized {
    editor.captureDisplayScene()?.use { scene ->
        val layers = (0 until scene.metadata()[3].toInt()).map(scene::layerMetadata).associateBy { it.id }
        layers[ids.a]?.transform?.let { groupScaleBoxEquals(it, a) } == true &&
            layers[ids.b]?.transform?.let { groupScaleBoxEquals(it, b) } == true
    } == true
}

/** Source payload IDs and bytes: transforming the group must not resample either source raster. */
internal fun groupScaleSource(editor: NativeEditor, ids: GroupScaleFixture): Map<Long, Pair<List<Long>, String>> =
    checkNotNull(editor.captureDisplayScene()).use { scene ->
        (0 until scene.metadata()[3].toInt()).mapNotNull { index ->
            val layer = scene.layerMetadata(index)
            if (layer.id != ids.a && layer.id != ids.b) null else {
                check(layer.tileCount == 1 && layer.pixelWidth == 4 && layer.pixelHeight == 4)
                val buffer = ByteBuffer.allocateDirect(64)
                scene.readTile(index, 0, 16, buffer)
                val bytes = ByteArray(64).also { buffer.rewind(); buffer.get(it) }
                layer.id to (scene.tileViewsMetadata(index).map { it.payloadResourceId } to moveInspectorSha(bytes))
            }
        }.toMap().also { check(it.size == 2) }
    }

/** Real Canvas/retained-controller/native draft, with literal member placements and history checks. */
internal fun groupScaleCanvasSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger; val initial = ledger.snapshot()
    val records = JSONArray()
    val asynchronousError = AtomicReference<Throwable?>(null)
    var failure: String? = null
    // Main-thread assertion failures must reach the instrumentation thread so finally exports evidence.
    fun onMain(checkFailures: Boolean = true, block: () -> Unit) {
        var thrown: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { thrown = error } }
        thrown?.let { throw it }
        if (checkFailures) asynchronousError.get()?.let { throw it }
    }
    val evidence = File(context.filesDir, "p42-group-scale-canvas.json")
    fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) { Thread.sleep(10); instrumentation.waitForIdleSync() }
        asynchronousError.get()?.let { throw it }
        check(condition()) { message }
    }
    try {
        for (grouped in listOf(true, false)) NativeEditor(256, false).use { editor ->
            val ids = installGroupScaleFixture(editor, grouped)
            val before = editor.state(); val source = groupScaleSource(editor, ids)
            var view: CanvasView? = null
            var primaryFailure: Throwable? = null
            val record = JSONObject().put("grouped", grouped).put("passed", false).put("stage", "begin")
            records.put(record)
            fun stage(value: String) { record.put("stage", value) }
            try {
                onMain {
                    view = CanvasView(context, editor, {}, { asynchronousError.compareAndSet(null, IllegalStateException(it)) }).also {
                        it.layout(0, 0, 256, 256); it.actualPixels(); it.toolMode = CanvasToolMode.MOVE
                        it.layerId = editor.selection().primary
                        check(it.startPersistentMove())
                    }
                }
                val canvas = checkNotNull(view)
                stage("initial-active")
                await("Group begin did not become active") { editor.hasActiveTransformDraft && canvas.moveBox != null }
                val owner = checkNotNull(editor.activeTransformCompletion())
                check(canvas.moveScalePercent == 100.0)
                stage("scale50")
                val field = arrayOfNulls<MoveFieldSession>(1)
                onMain {
                    field[0] = canvas.beginMoveFieldSession()
                    check(canvas.editMoveScale(50.0, field[0]))
                }
                val half = Transform64(34.0, 29.0, 28.0, 18.0)
                await("Group50 member preview wrong") { canvas.moveBox == half && groupScalePreview(editor, ids,
                    Transform64(34.0, 29.0, 8.0, 8.0), Transform64(54.0, 39.0, 8.0, 8.0, flipX = true)) }
                check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && groupScaleSource(editor, ids) == source)
                stage("scale100")
                onMain {
                    check(canvas.editMoveScale(100.0, field[0]))
                    canvas.completeMoveField(checkNotNull(field[0]))
                }
                await("Group100 compounded from50") { canvas.moveBox == Transform64(20.0, 20.0, 56.0, 36.0) &&
                    groupScalePreview(editor, ids, Transform64(20.0, 20.0, 16.0, 16.0),
                        Transform64(60.0, 40.0, 16.0, 16.0, flipX = true)) }
                check(canvas.movePersistent && editor.activeTransformCompletion() === owner && !owner.isDone)
                stage("rotation90-scale50")
                onMain {
                    check(canvas.editMoveField(MoveField.ROTATION, 90.0))
                    check(canvas.editMoveScale(50.0))
                }
                await("Group Scale lost the current draft rotation") {
                    canvas.moveBox == half.copy(rotation = 90.0) && groupScalePreview(editor, ids,
                        Transform64(49.0, 24.0, 8.0, 8.0, 90.0),
                        Transform64(39.0, 44.0, 8.0, 8.0, 90.0, flipX = true))
                }
                check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && groupScaleSource(editor, ids) == source)
                stage("apply")
                onMain {
                    check(canvas.editMoveField(MoveField.ROTATION, 0.0))
                    canvas.applyMove()
                }
                await("Group scale apply did not settle") { !canvas.moveInProgress && editor.activeTransformCompletion() == null }
                val after = editor.state()
                check(owner.get(10, TimeUnit.SECONDS).let { it.committed && !it.cancelled && it.error == null })
                check(after.revision == before.revision + 1)
                check(after.layers.filter { it.id != ids.a && it.id != ids.b } == before.layers.filter { it.id != ids.a && it.id != ids.b })
                check(groupScaleSource(editor, ids) == source)
                stage("undo-redo")
                editor.undo(false); check(editor.state().layers == before.layers)
                editor.undo(true); check(editor.state().layers == after.layers)
                stage("restore-selection")
                // History restores the primary selection; explicitly reselect the multi-layer target.
                val restoredSelection = editor.selection()
                check(restoredSelection.roots == listOf(if (grouped) ids.group else ids.a))
                if (!grouped) editor.selectLayers(listOf(ids.a, ids.b), ids.a)
                val nextSelection = editor.selection()
                check(nextSelection.roots.toSet() == if (grouped) setOf(ids.group) else setOf(ids.a, ids.b))
                // New edit uses the committed half-size box as100%, then cancellation leaves it intact.
                stage("new100")
                onMain {
                    canvas.layerId = nextSelection.primary
                    check(canvas.startPersistentMove()) { "New group edit refused: $nextSelection" }
                    val newBox = checkNotNull(canvas.moveBox)
                    record.put("newBox", newBox.toString()).put("newScalePercent", canvas.moveScalePercent)
                    check(groupScaleBoxEquals(newBox, half)) { "New group target box differs: $newBox" }
                    check(canvas.moveScalePercent == 100.0) { "New group basis differs: ${canvas.moveScalePercent}" }
                }
                stage("cancel")
                onMain { check(canvas.editMoveScale(200.0)); canvas.cancelMove() }
                await("Group scale cancel did not settle") { !canvas.moveInProgress && editor.activeTransformCompletion() == null }
                check(editor.state().layers == after.layers && groupScaleSource(editor, ids) == source)
                record.put("passed", true).put("stage", "passed")
                    .put("basis", "56x36").put("halfBox", half.toString()).put("beforeRevision", before.revision)
                    .put("afterRevision", after.revision).put("sourcePayloadsStable", true)
                    .put("restoredSelection", restoredSelection.toString()).put("nextSelection", nextSelection.toString())
            } catch (error: Throwable) {
                primaryFailure = error
                record.put("failure", error.stackTraceToString())
                throw error
            } finally {
                val completion = editor.activeTransformCompletion()
                try {
                    // Retirement preserves persistent drafts; failed tests must cancel their own draft first.
                    onMain(checkFailures = false) {
                        try { view?.cancelMove() } finally { view?.retire(); view?.onPause() }
                    }
                    completion?.get(10, TimeUnit.SECONDS)
                } catch (cleanup: Throwable) {
                    record.put("cleanupFailure", cleanup.stackTraceToString())
                    val original = primaryFailure
                    if (original == null) throw cleanup else original.addSuppressed(cleanup)
                }
            }
        }
        return "PASS: native group/nested and multiselect Scale50→100 fixed begin basis, literal member placements including retained90-degree draft rotation/flip, unchanged committed preview/source tiles, persistent field completion, one Apply history segment, undo/redo, new100 basis and cancel."
    } catch (error: Throwable) {
        failure = error.stackTraceToString()
        throw error
    } finally {
        evidence.writeText(JSONObject().put("selector", "groupScaleCanvas").put("cases", records)
            .put("failure", failure ?: JSONObject.NULL).toString(2))
        check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
    }
}
