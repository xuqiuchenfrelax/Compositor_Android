package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Real JNI documents and recovery pointers verify the retained New owner; source candidate, not yet executed. */
internal fun newCanvasLifecycleSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val root = File(context.cacheDir, "new-canvas-${System.nanoTime()}").apply { check(mkdirs()) }
    val outgoingCheckpoint = AtomicReference<RecoveryTarget?>()
    val autosaveCheckpoint = AtomicReference<Pair<RecoveryTarget, Long>?>()
    val workspace = AndroidWorkspace(File(root, "workspace"), recoveryHooks = RecoveryPublicationHooks(
        afterCheckpoint = { kind, target, _, revision ->
            if (kind == RecoverySaveKind.OUTGOING_NEW) outgoingCheckpoint.set(target)
            if (kind == RecoverySaveKind.AUTOSAVE) autosaveCheckpoint.set(target to revision)
        }))
    lateinit var owner: ProjectFlowController
    var ownerCreated = false
    val createdCopies = mutableListOf<File>()
    fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(condition()) { message }
    }
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitIdle() = waitUntil("New owner did not finish") { owner.state.phase == ProjectFlowPhase.IDLE }
    fun pixels(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
        check(editor.draw(32, 32, 0.0, 0.0, editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun recoveryDiagnostic(label: String, expected: EditorState, actual: EditorState,
        expectedPixels: ByteArray, actualPixels: ByteArray, expectedWidth: Int, expectedHeight: Int,
        actualWidth: Int, actualHeight: Int) {
        fun transform(value: Transform64) = JSONObject().put("x", value.x).put("y", value.y)
            .put("width", value.width).put("height", value.height).put("rotation", value.rotation)
            .put("flipX", value.flipX).put("flipY", value.flipY)
        fun state(value: EditorState) = JSONObject().put("undo", value.undo).put("redo", value.redo)
            .put("revision", value.revision).put("bytes", value.bytes)
            .put("document", JSONObject().put("uuid", value.document.uuid).put("resolution", value.document.resolution)
                .put("activeLayerId", value.document.activeLayerId).put("guides", JSONArray(value.document.guides.map {
                    JSONObject().put("uuid", it.uuid).put("horizontal", it.horizontal).put("position", it.position)
                })))
            .put("layers", JSONArray(value.layers.map {
                JSONObject().put("id", it.id).put("opacity", it.opacity).put("visible", it.visible)
                    .put("blend", it.blend).put("name", it.name).put("parentId", it.parentId)
                    .put("maskSourceId", it.maskSourceId).put("isGroup", it.isGroup).put("hasMask", it.hasMask)
                    .put("maskEnabled", it.maskEnabled).put("maskLinked", it.maskLinked)
                    .put("maskWidth", it.maskWidth).put("maskHeight", it.maskHeight)
                    .put("pixelWidth", it.pixelWidth).put("pixelHeight", it.pixelHeight)
                    .put("transform", transform(it.transform)).put("sampling", it.sampling).put("uuid", it.uuid)
                    .put("assetPresent", it.assetPresent).put("maskPlacement", it.maskPlacement?.let(::transform) ?: JSONObject.NULL)
                    .put("maskSampling", it.maskSampling)
            }))
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        runCatching {
            val differences = expectedPixels.indices.filter { it >= actualPixels.size || expectedPixels[it] != actualPixels[it] }
            val extraBytes = (actualPixels.size - expectedPixels.size).coerceAtLeast(0)
            val record = JSONObject().put("label", label).put("expectedState", state(expected)).put("actualState", state(actual))
                .put("expectedStateText", expected.toString()).put("actualStateText", actual.toString())
                .put("expectedCanvasWidth", expectedWidth).put("expectedCanvasHeight", expectedHeight)
                .put("actualCanvasWidth", actualWidth).put("actualCanvasHeight", actualHeight)
                .put("stateEqual", expected == actual).put("layersEqual", expected.layers == actual.layers)
                .put("documentEqual", expected.document == actual.document).put("revisionEqual", expected.revision == actual.revision)
                .put("expectedPixelBytes", expectedPixels.size).put("actualPixelBytes", actualPixels.size)
                .put("expectedPixelsSHA256", sha(expectedPixels)).put("actualPixelsSHA256", sha(actualPixels))
                .put("pixelByteDifferenceCount", differences.size + extraBytes)
                .put("pixelDifferenceCount", differences.map { it / 4 }.distinct().size + (extraBytes + 3) / 4)
                .put("firstDifferences", JSONArray(differences.take(20).map {
                    JSONObject().put("offset", it).put("expected", expectedPixels[it].toInt() and 255)
                        .put("actual", if (it < actualPixels.size) actualPixels[it].toInt() and 255 else JSONObject.NULL)
                }))
            val file = File(context.filesDir, "p19-new-recovery-diagnostic01-${System.nanoTime()}.json")
            file.writeText(record.toString(2))
            android.util.Log.i("CompositorNewRecovery", "Saved diagnostic ${file.name}: $label")
        }.onFailure { android.util.Log.e("CompositorNewRecovery", "Could not preserve $label diagnostic", it) }
    }
    fun request(editor: NativeEditor, width: String = "31", height: String = "17", dpi: String = "300") = main {
        owner.clearError()
        check(owner.chooseNewCanvas(editor))
        owner.updateNewCanvas { it.copy(width = width, height = height, resolution = dpi) }
        owner.createNewCanvas()
    }
    try {
        check(NewCanvasDraft().spec() == NewCanvasSpec(1920, 1080, 72.0, NewCanvasBackground.TRANSPARENT))
        for (invalid in listOf("", "0", "-1", "1.5", "NaN", "Infinity", "30001"))
            check(NewCanvasDraft(width = invalid).spec() == null)
        for (invalid in listOf("0", "9601", "NaN", "Infinity"))
            check(NewCanvasDraft(resolution = invalid).spec() == null)
        check(NewCanvasDraft(width = "2,54", height = "5.08", resolution = "300", unit = NewCanvasUnit.CENTIMETERS)
            .spec() == NewCanvasSpec(300, 600, 300.0, NewCanvasBackground.TRANSPARENT))
        check(NewCanvasDraft(width = "0.5", height = "0.5", resolution = "1", unit = NewCanvasUnit.INCHES).spec()?.width == 1)
        check(NewCanvasDraft().withUnit(NewCanvasUnit.MILLIMETERS).spec()?.width == 1920)

        waitUntil("Workspace did not open") { !workspace.snapshot().loading }
        val old = checkNotNull(workspace.snapshot().editor)
        main { owner = ProjectFlowController(context, workspace); ownerCreated = true }
        val pointer = File(root, "workspace/active.json")
        var originalPointer = pointer.readBytes()
        val recoveryStore = WorkspaceRecoveryStore(File(root, "workspace"))
        val originalRecoveryTarget = checkNotNull(recoveryStore.read())
        val original = old.state()
        val oldLease = checkNotNull(workspace.lease(old))
        main {
            check(owner.chooseNewCanvas(old))
            check(owner.state.newCanvas?.draft == NewCanvasPreferences.load(context))
            owner.updateNewCanvas { it.copy(width = "0") }
            owner.createNewCanvas()
            check(owner.state.newCanvas != null && owner.state.newConfirmation == null)
            owner.cancelNewCanvas()
        }
        check(old.state() == original && pointer.readBytes().contentEquals(originalPointer))

        request(old)
        main { check(owner.state.newConfirmation != null); owner.cancelNewCanvas() }
        check(workspace.snapshot().editor === old && old.state() == original && oldLease.isCurrent())
        check(pointer.readBytes().contentEquals(originalPointer))

        main { check(owner.chooseNewCanvas(old)) }
        old.addLayer() // Simulates a late queued commit while the dialog was open.
        val changed = old.state()
        main { owner.createNewCanvas(); check(owner.state.error?.contains("changed") == true) }
        check(workspace.snapshot().editor === old && old.state() == changed)

        // Confirm the late edit's real autosave before testing failures that must keep its pointer unchanged.
        waitUntil("Late edit autosave did not publish its recovery pointer") {
            val autosave = autosaveCheckpoint.get()
            val snapshot = workspace.snapshot()
            autosave != null && autosave.second == changed.revision &&
                snapshot.savedRevision == changed.revision && !snapshot.recoveryDurabilityUncertain && snapshot.saveError == null &&
                recoveryStore.read() == autosave.first
        }
        val confirmedAutosave = checkNotNull(autosaveCheckpoint.get())
        val autosavedTarget = confirmedAutosave.first
        val autosavedState = workspace.snapshot()
        check(confirmedAutosave.second == changed.revision && autosavedState.savedRevision == changed.revision &&
            !autosavedState.recoveryDurabilityUncertain && autosavedState.saveError == null)
        check(autosavedTarget.fileName != originalRecoveryTarget.fileName &&
            autosavedTarget.copy(fileName = originalRecoveryTarget.fileName) == originalRecoveryTarget)
        check(autosavedState.editor === old && autosavedState.generation == originalRecoveryTarget.generation &&
            autosavedState.projectBaseline == originalRecoveryTarget.projectBaseline &&
            autosavedState.sourceIdentity == originalRecoveryTarget.sourceIdentity && autosavedState.projectDirty &&
            old.state() == changed && oldLease.isCurrent())
        val autosavedPointer = pointer.readBytes()
        val autosavedEnvelope = JSONObject(String(autosavedPointer, Charsets.UTF_8))
        val autosavedPayload = autosavedEnvelope.getString("payload")
        check(autosavedEnvelope.getInt("version") == 2 && autosavedEnvelope.getLong("crc32") ==
            java.util.zip.CRC32().apply { update(autosavedPayload.toByteArray(Charsets.UTF_8)) }.value)
        val autosavedFields = JSONObject(autosavedPayload)
        check(RecoveryTarget(autosavedFields.getLong("generation"), autosavedFields.getString("checkpoint"),
            autosavedFields.getString("documentUuid"),
            if (autosavedFields.isNull("projectBaseline")) null else autosavedFields.getLong("projectBaseline"),
            if (autosavedFields.isNull("sourceIdentity")) null else autosavedFields.getString("sourceIdentity"),
            autosavedFields.getBoolean("projectClean")) == autosavedTarget)
        originalPointer = autosavedPointer
        val autosaveEvidence = File(context.filesDir, "p36-new-autosave-prerequisite01-${System.nanoTime()}.json")
        autosaveEvidence.writeText(JSONObject().put("kind", RecoverySaveKind.AUTOSAVE.name)
            .put("capturedRevision", confirmedAutosave.second).put("target", autosavedTarget.toString())
            .put("workspace", autosavedState.toString()).put("pointerEnvelope", autosavedEnvelope)
            .put("scope", "Confirmed autosave prerequisite; not evidence of the original runtime09 failing operand")
            .toString(2))
        android.util.Log.i("CompositorNewRecovery", "Saved autosave prerequisite ${autosaveEvidence.name}")

        val rememberedBeforeFailure = NewCanvasPreferences.load(context)
        main { owner.testHooks = ProjectFlowHooks(beforeCandidateLoad = { error("New decoder fault") }) }
        request(old)
        main { owner.discardAndCreateNewCanvas() }
        waitIdle()
        check(owner.state.error == "New decoder fault" && workspace.snapshot().editor === old)
        check(NewCanvasPreferences.load(context) == rememberedBeforeFailure)
        check(!workspace.snapshot().resourceGrowthFrozen && oldLease.isCurrent())
        check(pointer.readBytes().contentEquals(originalPointer))

        // A written project copy without confirmed baseline publication must not permit New to discard the old document.
        val beforeFailedSave = workspace.snapshot()
        val libraryBeforeFailure = LocalProjectLibrary.projects(context).toSet()
        main { owner.testHooks = ProjectFlowHooks(newSaveFault = RecoveryFault.BEFORE_RENAME) }
        request(old)
        main { owner.saveAndCreateNewCanvas(); if (owner.state.projectCopy != null) owner.confirmSaveCopy() }
        waitIdle()
        createdCopies += LocalProjectLibrary.projects(context).toSet() - libraryBeforeFailure
        check(owner.state.error?.contains("before pointer commit") == true)
        check(workspace.snapshot().editor === old && workspace.snapshot().generation == beforeFailedSave.generation &&
            workspace.snapshot().projectBaseline == beforeFailedSave.projectBaseline &&
            workspace.snapshot().sourceIdentity == beforeFailedSave.sourceIdentity && workspace.snapshot().projectDirty)
        try {
            check(old.state() == changed && oldLease.isCurrent() && pointer.readBytes().contentEquals(originalPointer))
        } catch (error: Exception) {
            runCatching {
                val record = JSONObject().put("label", "failed-save-preservation")
                    .put("scope", "Fresh observations after failure; original compound operands and short-circuit evaluation remain unknown")
                    .put("originalFailure", error.toString()).put("elapsedRealtimeMillis", android.os.SystemClock.elapsedRealtime())
                    .put("expectedState", changed.toString()).put("expectedPointer", String(originalPointer, Charsets.UTF_8))
                    .put("beforeFailedSave", beforeFailedSave.toString()).put("leaseToken", oldLease.token.toString())
                fun capture(name: String, action: () -> Any?) {
                    runCatching { record.put(name, action() ?: JSONObject.NULL) }
                        .onFailure { record.put("${name}Error", it.toString()) }
                }
                capture("freshState") { old.state().also { record.put("freshStateEqual", it == changed) }.toString() }
                capture("freshLeaseCurrent") { oldLease.isCurrent() }
                capture("freshPointer") {
                    pointer.readBytes().also { record.put("freshPointerEqual", it.contentEquals(originalPointer)) }
                        .toString(Charsets.UTF_8)
                }
                capture("freshWorkspace") { workspace.snapshot().toString() }
                capture("freshOwner") { owner.state.toString() }
                val file = File(context.filesDir, "p36-new-save-preservation-failure01-${System.nanoTime()}.json")
                file.writeText(record.toString(2))
                android.util.Log.i("CompositorNewRecovery", "Saved failure-time diagnostic ${file.name}")
            }.onFailure { android.util.Log.e("CompositorNewRecovery", "Could not preserve failed-save diagnostic", it) }
            throw error
        }

        // Queue cancellation before resource admission; no staging/publication or replacement is allowed.
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        workspace.runIo(checkNotNull(workspace.token()), true, {
            blocked.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }) {}
        check(blocked.await(10, TimeUnit.SECONDS))
        try {
            main { owner.testHooks = null }
            request(old)
            main { owner.discardAndCreateNewCanvas(); check(owner.cancel()) }
        } finally { release.countDown() }
        waitIdle()
        check(workspace.snapshot().editor === old && old.state() == changed)
        check(pointer.readBytes().contentEquals(originalPointer))

        val retiredTarget = checkNotNull(WorkspaceRecoveryStore(File(root, "workspace")).read())
        val retiredCheckpoint = WorkspaceRecoveryStore(File(root, "workspace")).checkpoint(retiredTarget)
        val retiredBytes = retiredCheckpoint.readBytes()
        val outgoingState = old.state()
        val outgoingPixels = pixels(old)
        val outgoingWidth = old.canvasWidth; val outgoingHeight = old.canvasHeight
        outgoingCheckpoint.set(null)
        request(old)
        main { owner.discardAndCreateNewCanvas() }
        waitUntil("Rectangular canvas was not installed") { workspace.snapshot().editor !== old }
        waitIdle()
        var current = checkNotNull(workspace.snapshot().editor)
        check(current.canvasWidth == 31 && current.canvasHeight == 17 && current.state().document.resolution == 300.0)
        check(current.state().layers.single().let { it.name == "Layer 1" && !it.assetPresent && it.pixelWidth == 31 && it.pixelHeight == 17 })
        check(workspace.snapshot().projectDirty && workspace.snapshot().projectBaseline == null && workspace.snapshot().sourceIdentity == null)
        check(!oldLease.isCurrent() && runCatching { old.currentRevision() }.isFailure)
        val retainedTarget = checkNotNull(outgoingCheckpoint.get()) { "New did not record its outgoing recovery attempt" }
        check(retainedTarget.fileName != retiredTarget.fileName && retainedTarget.generation == retiredTarget.generation &&
            retainedTarget.documentUuid == retiredTarget.documentUuid)
        check(retiredCheckpoint.isFile && retiredCheckpoint.readBytes().contentEquals(retiredBytes)) {
            "New overwrote the previously published outgoing checkpoint"
        }
        NativeEditor.load(WorkspaceRecoveryStore(File(root, "workspace")).checkpoint(retainedTarget)).use { retained ->
            val actualState = retained.state(); val actualPixels = pixels(retained)
            // Checkpoint v5 preserves document state, not the live session's undo/redo history.
            val expectedState = outgoingState.copy(undo = false, redo = false)
            val preserved = actualState.copy(selectedLayerActions = null) == expectedState.copy(selectedLayerActions = null) && actualPixels.contentEquals(outgoingPixels) &&
                retained.canvasWidth == outgoingWidth && retained.canvasHeight == outgoingHeight
            if (!preserved)
                recoveryDiagnostic("discard-and-new-retired-checkpoint", expectedState, actualState, outgoingPixels,
                    actualPixels, outgoingWidth, outgoingHeight, retained.canvasWidth, retained.canvasHeight)
            check(preserved) {
                "Discard-and-New did not retain the latest outgoing document checkpoint"
            }
        }
        check(!pixels(current).any { it != 0.toByte() })
        val blank = pixels(current)
        current.startStroke(current.state().layers.single().id, StrokeSettings(diameter = 8f))
        current.samples(doubleArrayOf(15.0, 8.0, 1.0, 0.0)); current.endStroke(false)
        check(!pixels(current).contentEquals(blank)) { "New blank canvas could not actually paint/render" }

        val painted = current
        val paintedState = painted.state()
        val paintedPixels = pixels(painted)
        val libraryBefore = LocalProjectLibrary.projects(context).toSet()
        request(painted, "9", "11", "72")
        main { owner.saveAndCreateNewCanvas(); if (owner.state.projectCopy != null) owner.confirmSaveCopy() }
        waitUntil("Save-and-New did not install canvas") { workspace.snapshot().editor !== painted }
        waitIdle()
        current = checkNotNull(workspace.snapshot().editor)
        check(current.canvasWidth == 9 && current.canvasHeight == 11)
        check(workspace.snapshot().projectBaseline == null && workspace.snapshot().sourceIdentity == null && workspace.snapshot().projectDirty)
        val copy = (LocalProjectLibrary.projects(context).toSet() - libraryBefore).single()
        createdCopies += copy
        NativeEditor.loadProjectCandidate(context, copy, 256L * 1024 * 1024, 0).use { saved ->
            check(saved.state().document.uuid == paintedState.document.uuid)
            check(saved.state().layers == paintedState.layers)
            check(pixels(saved).contentEquals(paintedPixels)) { "Save-and-New did not preserve the previous document copy" }
        }

        // Decode/checkpoint failures must preserve the previous generation. An oversized solid background is
        // rejected before allocating its bitmap, using exactly the same policy as the shipping New flow.
        val preserved = current.state()
        val beforeOversize = pointer.readBytes()
        main {
            owner.clearError(); check(owner.chooseNewCanvas(current))
            owner.updateNewCanvas { it.copy(width = "30000", height = "30000", background = NewCanvasBackground.WHITE) }
            owner.createNewCanvas(); owner.discardAndCreateNewCanvas()
        }
        waitIdle()
        check(owner.state.error?.contains("too large") == true)
        check(workspace.snapshot().editor === current && current.state() == preserved)
        check(pointer.readBytes().contentEquals(beforeOversize) && !workspace.snapshot().resourceGrowthFrozen)

        for (background in listOf(NewCanvasBackground.WHITE, NewCanvasBackground.BLACK)) {
            val before = current
            val stagingRoot = File(context.filesDir, "project-staging")
            val existingStaging = stagingRoot.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val staged = AtomicReference(JSONArray())
            var requestState = ""
            var originalHooks: ProjectFlowHooks? = null
            main {
                owner.clearError(); check(owner.chooseNewCanvas(before))
                owner.updateNewCanvas { it.copy(width = "7", height = "5", resolution = "300", background = background) }
                // Capture only packages created by this fixture before the loader's cleanup removes them.
                originalHooks = owner.testHooks
                owner.testHooks = ProjectFlowHooks(stage = originalHooks?.stage, beforeCandidateLoad = {
                    runCatching {
                        val entries = JSONArray()
                        stagingRoot.listFiles()?.filter { it.name !in existingStaging && it.name.endsWith(".comp") }
                            ?.take(8)?.forEach { directory ->
                                val manifest = File(directory, "manifest.json")
                                val record = JSONObject().put("name", directory.name).put("manifestText", manifest.readText())
                                val assets = JSONArray()
                                File(directory, "images").listFiles()?.take(16)?.forEach { image ->
                                    val bytes = image.readBytes()
                                    assets.put(JSONObject().put("name", image.name).put("size", bytes.size)
                                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                                            .joinToString("") { "%02x".format(it.toInt() and 255) })
                                        .put("fixtureBytesBase64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                                }
                                entries.put(record.put("assets", assets))
                            }
                        staged.set(entries)
                    }.onFailure { staged.set(JSONArray().put(JSONObject().put("captureError", it.toString()))) }
                    originalHooks?.beforeCandidateLoad?.invoke()
                }, exportProgress = originalHooks?.exportProgress,
                    newSaveFault = originalHooks?.newSaveFault ?: RecoveryFault.NONE)
                owner.createNewCanvas(); owner.discardAndCreateNewCanvas()
                requestState = owner.state.toString()
            }
            try {
                waitUntil("Solid background canvas was not installed") { workspace.snapshot().editor !== before }
            } catch (error: IllegalStateException) {
                runCatching {
                    val record = JSONObject().put("stage", "solid-background-${background.name}")
                        .put("requestedDraft", NewCanvasDraft(width = "7", height = "5", resolution = "300", background = background).toString())
                        .put("requestFlowState", requestState).put("stagingBeforeDecode", staged.get())
                    main {
                        val flow = owner.state
                        val state = workspace.snapshot()
                        record.put("ownerState", flow.toString()).put("phase", flow.phase.name)
                            .put("error", flow.error ?: JSONObject.NULL).put("message", flow.message)
                            .put("newDraft", flow.newCanvas?.toString() ?: JSONObject.NULL)
                            .put("newConfirmation", flow.newConfirmation?.toString() ?: JSONObject.NULL)
                            .put("workspaceState", state.toString())
                            .put("editorState", state.editor?.state()?.toString() ?: JSONObject.NULL)
                            .put("canvasWidth", state.editor?.canvasWidth ?: JSONObject.NULL)
                            .put("canvasHeight", state.editor?.canvasHeight ?: JSONObject.NULL)
                    }
                    record.put("activePointer", if (pointer.exists()) pointer.readText() else JSONObject.NULL)
                        .put("workspaceFiles", JSONArray(File(root, "workspace").listFiles()?.map {
                            JSONObject().put("name", it.name).put("size", it.length()).put("directory", it.isDirectory)
                        } ?: emptyList<JSONObject>()))
                    val file = File(context.filesDir, "p19-new-background-diagnostic01-${System.nanoTime()}.json")
                    file.writeText(record.toString(2))
                    android.util.Log.i("CompositorNewBackground", "Saved diagnostic ${file.name}: ${background.name}")
                }.onFailure { android.util.Log.e("CompositorNewBackground", "Could not preserve background diagnostic", it) }
                throw error
            } finally {
                main { owner.testHooks = originalHooks }
            }
            waitIdle(); current = checkNotNull(workspace.snapshot().editor)
            check(current.state().layers.single().let { it.name == "Background" && it.assetPresent && it.pixelWidth == 7 && it.pixelHeight == 5 })
            val rendered = pixels(current)
            for (i in rendered.indices step 4) {
                val rgb = if (background == NewCanvasBackground.WHITE) 255 else 0
                check((rendered[i].toInt() and 255) == rgb && (rendered[i + 1].toInt() and 255) == rgb &&
                    (rendered[i + 2].toInt() and 255) == rgb && (rendered[i + 3].toInt() and 255) == 255)
            }
        }
        val last = current.state()
        main {
            check(owner.chooseNewCanvas(current))
            check(owner.state.newCanvas?.draft == NewCanvasDraft(width = "7", height = "5"))
            owner.updateNewCanvas { it.copy(width = "123", height = "456") }
            owner.cancelNewCanvas()
            check(NewCanvasPreferences.load(context) == NewCanvasDraft(width = "7", height = "5"))
        }
        val lastPixels = pixels(current); val lastWidth = current.canvasWidth; val lastHeight = current.canvasHeight
        main { owner.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        val recovered = AndroidWorkspace(File(root, "workspace"))
        try {
            waitUntil("New canvas did not cold recover") { !recovered.snapshot().loading }
            val recoveredEditor = checkNotNull(recovered.snapshot().editor)
            val recoveredState = recoveredEditor.state(); val recoveredPixels = pixels(recoveredEditor)
            val expectedState = last.copy(undo = false, redo = false)
            val preserved = recoveredState.copy(selectedLayerActions = null) == expectedState.copy(selectedLayerActions = null) && recoveredPixels.contentEquals(lastPixels) &&
                recoveredEditor.canvasWidth == lastWidth && recoveredEditor.canvasHeight == lastHeight
            if (!preserved)
                recoveryDiagnostic("cold-recovery", expectedState, recoveredState, lastPixels, recoveredPixels,
                    lastWidth, lastHeight, recoveredEditor.canvasWidth, recoveredEditor.canvasHeight)
            check(preserved)
            check(recovered.snapshot().projectDirty && recovered.snapshot().projectBaseline == null && recovered.snapshot().sourceIdentity == null)
        } finally { recovered.closeAsync().get(10, TimeUnit.SECONDS) }
        return "New lifecycle: validation/units/defaults, cancel/dirty choice/stale revision/queued cancel/decode+save publication fault preservation, real rectangular DPI blank+paint, save-and-New exact previous copy, oversized background admission, white/black full-size pixels, retired lease and cold recovery dirty identity"
    } finally {
        if (ownerCreated) main { owner.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        createdCopies.forEach { it.deleteRecursively() }
        root.deleteRecursively()
    }
}
