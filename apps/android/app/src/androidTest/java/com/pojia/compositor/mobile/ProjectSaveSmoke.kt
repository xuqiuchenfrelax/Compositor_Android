package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real JNI writer roundtrips immutable source/mask state and durable workspace baseline. */
internal fun projectSaveSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val root = File(context.cacheDir, "project-save-${System.nanoTime()}").apply { check(mkdirs()) }
    val limit = 64L * 1024 * 1024
    fun rendered(editor: NativeEditor): ByteArray {
        val pixels = ByteBuffer.allocateDirect(editor.canvasWidth * editor.canvasHeight * 4)
        check(editor.draw(editor.canvasWidth, editor.canvasHeight, 0.0, 0.0,
            editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), pixels))
        return ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
    }
    val workspaces = mutableListOf<AndroidWorkspace>()
    fun opened(folder: File): AndroidWorkspace {
        val workspace = AndroidWorkspace(folder); workspaces += workspace
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(10)
        check(!workspace.snapshot().loading && workspace.snapshot().editor != null)
        return workspace
    }
    try {
        val input = File(root, "input.comp")
        copyProjectAssets(instrumentation.context.assets, "nearest-editable.comp", input)
        val original = NativeEditor.loadProjectCandidate(context, input, limit, 0)
        val state = original.state(); val pixels = rendered(original)
        val artifact = File(context.cacheDir, "android-project-save-roundtrip")
        check(!artifact.exists() || artifact.deleteRecursively()); check(artifact.mkdir())
        fun array(values: Iterable<Any>) = JSONArray().also { output -> values.forEach { output.put(it) } }
        fun transform(value: Transform64, sampling: Int) = JSONObject()
            .put("origin", array(listOf(value.x, value.y))).put("size", array(listOf(value.width, value.height)))
            .put("rotation", value.rotation).put("flipX", value.flipX).put("flipY", value.flipY)
            .put("sampling", if (sampling == 0) "Nearest" else error("Unsupported fixture sampler"))
        val ids = state.layers.associate { it.id to it.uuid }
        val records = JSONArray()
        val expectedAssets = JSONArray()
        checkNotNull(original.captureScene()).use { scene ->
            state.layers.forEachIndexed { index, layer ->
                val record = JSONObject().put("id", layer.uuid).put("name", layer.name)
                    .put("isVisible", layer.visible).put("opacity", layer.opacity).put("blendMode", "Normal")
                    .put("transform", transform(layer.transform, layer.sampling)).put("isGroup", layer.isGroup)
                if (layer.parentId != 0L) record.put("parentID", ids.getValue(layer.parentId))
                if (layer.maskSourceId != 0L) record.put("maskSourceID", ids.getValue(layer.maskSourceId))
                fun asset(mask: Boolean, width: Int, height: Int) {
                    val buffer = ByteBuffer.allocateDirect(width * height * if (mask) 1 else 4)
                    if (mask) original.maskPixels(layer.id, width, buffer) else scene.readTile(index, 0, width * 4, buffer)
                    val bytes = ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
                    expectedAssets.put(JSONObject().put("layerID", layer.uuid).put("mask", mask).put("width", width)
                        .put("height", height).put("pixels", array(bytes.map { it.toInt() and 255 })))
                }
                if (layer.assetPresent) { record.put("imageFile", "${layer.uuid}.png"); asset(false, layer.pixelWidth, layer.pixelHeight) }
                if (layer.hasMask) {
                    record.put("maskFile", "${layer.uuid}.mask.png").put("maskEnabled", layer.maskEnabled)
                        .put("maskLinked", layer.maskLinked)
                    layer.maskPlacement?.let { record.put("maskPlacement", transform(it, layer.maskSampling)) }
                    asset(true, layer.maskWidth, layer.maskHeight)
                }
                records.put(record)
            }
        }
        val manifest = JSONObject().put("format", "com.compositor.project").put("version", 13)
            .put("documentID", state.document.uuid).put("width", original.canvasWidth).put("height", original.canvasHeight)
            .put("resolution", state.document.resolution).put("colorSpace", "sRGB").put("layers", records)
            .put("activeLayerID", ids.getValue(state.document.activeLayerId)).put("guides", JSONArray().also { guides ->
                state.document.guides.forEach { guide -> guides.put(JSONObject().put("id", guide.uuid)
                    .put("axis", if (guide.horizontal) "horizontal" else "vertical").put("position", guide.position)) }
            })
        File(artifact, "expected.json").writeText(JSONObject().put("manifest", manifest).put("assets", expectedAssets)
            .put("tolerance", 0).toString(), Charsets.UTF_8)
        val snapshot = original.captureProjectSnapshot()
        val captured = snapshot.capturedRevision
        original.renameLayer(state.layers.last().id, "Later edit")
        original.close()
        val target = File(root, "written.comp")
        NativeProjectWriteControl(limit, 1024 * 1024).use { control ->
            val result = snapshot.write(context, target, false, control)
            check(result.successful && result.capturedRevision == captured)
        }
        snapshot.close(); snapshot.close()
        check(runCatching { NativeProjectWriteControl(limit, 0).use { control ->
            snapshot.write(context, File(root, "closed.comp"), false, control)
        } }.isFailure)
        check(target.copyRecursively(File(artifact, "roundtrip.comp")))
        NativeEditor.loadProjectCandidate(context, target, limit, 0).use { restored ->
            // Runtime native IDs/revisions are reconstructed; UUID relationships and source pixels persist.
            check(restored.state().document == state.document)
            check(restored.state().layers == state.layers)
            check(rendered(restored).contentEquals(pixels))
        }
        NativeEditor(8).use { editor ->
            editor.captureProjectSnapshot().use { immutable ->
                NativeProjectWriteControl(limit, 0).use { control ->
                    control.cancel()
                    val cancelled = immutable.write(context, File(root, "cancelled.comp"), false, control)
                    check(cancelled.status == 9 && !cancelled.committed && !File(root, "cancelled.comp").exists())
                }
                NativeProjectWriteControl(1, 0).use { control ->
                    val budget = immutable.write(context, File(root, "budget.comp"), false, control)
                    check(budget.status == 3 && !budget.committed && !File(root, "budget.comp").exists())
                }
                check(runCatching { NativeProjectWriteControl(limit, 0).use { control ->
                    immutable.write(context, File("/sdcard/outside.comp"), false, control)
                } }.isFailure)
            }
            val layer = editor.state().layers.last().id
            editor.startStroke(layer, 2f, false)
            check(runCatching { editor.captureProjectSnapshot() }.isFailure)
            editor.endStroke(true)
        }
        val folder = File(root, "workspace")
        val workspace = opened(folder)
        val editor = checkNotNull(workspace.snapshot().editor)
        val saved = workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
            lease.saveProject(context, File(root, "workspace.comp"), false, limit, lease.memory.totalBytes + 1024 * 1024, "local-copy")
        }, {}).get(10, TimeUnit.SECONDS)
        check(saved.successful && saved.baselineUpdated && !workspace.snapshot().projectDirty) { "Saved status=${saved.status} committed=${saved.committed} durable=${saved.durabilityConfirmed} baseline=${saved.baselineUpdated} error=${saved.error}" }
        val baseline = workspace.snapshot().projectBaseline
        val recoveryBefore = workspace.snapshot().recoverySavedRevision
        editor.addLayer()
        val failed = workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
            lease.saveProject(context, File(root, "failed.comp"), false, lease.memory.totalBytes + 1, lease.memory.totalBytes, "failed-copy")
        }, {}).get(10, TimeUnit.SECONDS)
        check(!failed.committed && !failed.baselineUpdated && workspace.snapshot().projectBaseline == baseline)
        check(workspace.snapshot().projectDirty && workspace.snapshot().recoverySavedRevision >= recoveryBefore)

        val uncertain = workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
            lease.saveProject(context, File(root, "uncertain.comp"), false, limit,
                lease.memory.totalBytes + 1024 * 1024, "uncertain-copy", RecoveryFault.AFTER_RENAME)
        }, {}).get(10, TimeUnit.SECONDS)
        check(uncertain.committed && uncertain.durabilityConfirmed && !uncertain.baselineUpdated && uncertain.metadataDurabilityUncertain)
        check(workspace.snapshot().projectBaseline == baseline && workspace.snapshot().projectDirty)

        // Fence the worker, capture a save, then edit before its immutable writer starts.
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        workspace.runIo({ entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }, {})
        check(entered.await(10, TimeUnit.SECONDS))
        val operation = workspace.saveProject(context, File(root, "continue.comp"), false, limit,
            editor.retainedMemory().totalBytes + 1024 * 1024, "continued-copy")
        editor.addLayer(); val laterRevision = editor.currentRevision()
        resume.countDown()
        val continued = operation.result.get(10, TimeUnit.SECONDS)
        check(continued.successful && continued.baselineUpdated && continued.capturedRevision < laterRevision)
        check(workspace.snapshot().projectDirty && workspace.snapshot().projectBaseline == continued.capturedRevision)
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        val reopened = opened(folder)
        check(reopened.snapshot().projectBaseline == continued.capturedRevision && reopened.snapshot().projectDirty)
        val closing = opened(File(root, "closing"))
        val closeEntered = CountDownLatch(1); val closeResume = CountDownLatch(1)
        closing.runIo({ closeEntered.countDown(); check(closeResume.await(10, TimeUnit.SECONDS)) }, {})
        check(closeEntered.await(10, TimeUnit.SECONDS))
        val cancelledSave = closing.saveProject(context, File(root, "closed-workspace.comp"), false, limit, 1024 * 1024, "closed")
        val closed = closing.closeAsync(); closeResume.countDown()
        val cancelledOutcome = cancelledSave.result.get(10, TimeUnit.SECONDS)
        check(cancelledOutcome.status == 9 && !cancelledOutcome.committed && !cancelledOutcome.baselineUpdated)
        closed.get(10, TimeUnit.SECONDS)

        val late = opened(File(root, "late"))
        val mainEntered = CountDownLatch(1); val mainResume = CountDownLatch(1); val callback = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { mainEntered.countDown(); mainResume.await(15, TimeUnit.SECONDS) }
        check(mainEntered.await(10, TimeUnit.SECONDS))
        try {
            val lateSave = late.saveProject(context, File(root, "late.comp"), false, limit, 1024 * 1024, "late") { callback.set(true) }
            check(lateSave.result.get(10, TimeUnit.SECONDS).baselineUpdated)
            check(late.installCandidate("replacement", DirtyDisposition.DISCARD) { NativeEditor(8) }
                .result.get(10, TimeUnit.SECONDS).installed)
        } finally { mainResume.countDown() }
        instrumentation.waitForIdleSync()
        check(!callback.get()) { "Late saved-project callback reached another generation" }
        return "JNI immutable writer: desktop source/mask roundtrip, close/edit lifetime, cancel/budget/path gates; frozen save durable baseline, failed writer leaves dirty, continue-edit/cold recovery remain dirty"
    } finally {
        workspaces.forEach { runCatching { it.closeAsync().get(10, TimeUnit.SECONDS) } }
        root.deleteRecursively()
    }
}
