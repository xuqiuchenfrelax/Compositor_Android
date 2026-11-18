package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.res.Configuration
import android.os.LocaleList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Real JNI fixed-capacity publication admission with shared immutable tile credit. */
internal fun displayPublicationAdmissionSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val evidence = File(context.cacheDir, "display-publication-admission-${System.nanoTime()}").apply { check(mkdir()) }
    val report = JSONObject().put("scope", "Fixed ledger-limit committed document + one ordinary future scene; immutable current tiles are credited once, real 2048-square eight-layer acceptance and save/reopen; no guarantee under competing owners")
        .put("calibrationFixtureWidth", 8).put("calibrationFixtureHeight", 8)
    val observations = JSONArray()
    val checkpoint = File(evidence, "eight-layers.checkpoint")
    val roomy = 32L * 1024 * 1024
    val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags("zh-CN"))
    })

    fun pixels(index: Int, width: Int = 8, height: Int = 8): ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4).apply {
            repeat(width * height) {
                put((index * 19).toByte()); put((index * 13).toByte()); put((index * 7).toByte()); put(255.toByte())
            }
            rewind()
        }
    fun readInfo(editor: NativeEditor): LongArray = editor.displayAdmissionInfoForTest().also {
        check(it.size == 6 && it.all { value -> value >= 0 })
        check(it[4] == Math.addExact(it[2], it[3]) && it[5] == editor.resourceLedger.limitBytes)
    }
    fun infoJson(info: LongArray) = JSONObject().put("revision", info[0]).put("sceneGeneration", info[1])
        .put("retainedBytes", info[2]).put("sceneCapturePeakBytes", info[3]).put("requiredBytes", info[4])
        .put("limitBytes", info[5])
    fun scenePixels(scene: NativeScene): ByteArray {
        val target = ByteBuffer.allocateDirect(8 * 8 * 4)
        scene.draw(8, 8, Viewport(0.0, 0.0, 8.0, 8.0), target)
        return ByteArray(target.capacity()).also { target.rewind(); target.get(it) }
    }
    fun editorPixels(editor: NativeEditor): ByteArray = checkNotNull(editor.captureScene()).use { scenePixels(it) }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun equalClaims(before: ResourceBudgetSnapshot, after: ResourceBudgetSnapshot) =
        before.limitBytes == after.limitBytes && before.residentBytes == after.residentBytes &&
            before.reservedBytes == after.reservedBytes && before.availableBytes == after.availableBytes &&
            before.reservations == after.reservations
    fun requireMemoryFailure(stage: String, action: () -> Unit): Throwable {
        val failure = runCatching(action).exceptionOrNull() ?: error("$stage unexpectedly succeeded")
        val status = when (failure) {
            is NativeCommandException -> failure.status
            is TransformDraftException -> failure.status
            else -> null
        }
        check(status == 3) { "$stage did not reach typed native capacity refusal: $failure" }
        val notice = uiFailure(failure, UiNoticeKey.EDITOR_COMMAND_FAILED)
        check(notice.key == UiNoticeKey.MEMORY_LIMIT)
        check(notice.text(chinese) == chinese.getString(R.string.notice_memory_limit))
        check(notice.text(chinese).any { it in '\u4e00'..'\u9fff' })
        observations.put(JSONObject().put("stage", stage).put("errorType", failure.javaClass.simpleName)
            .put("nativeStatus", status).put("noticeKey", notice.key.name).put("chineseNotice", notice.text(chinese)))
        return failure
    }
    fun noClaims(ledger: ResourceBudgetLedger) {
        val state = ledger.snapshot()
        check(state.reservedBytes == 0L && state.reservations == 0)
    }

    try {
        // An IO snapshot owns its full claim and must not wait for itself when
        // later edits retire document tiles. A denied second capture cannot leak.
        val independentLedger = ResourceBudgetLedger(roomy)
        val independentEditor = NativeEditor.createWithLedgerForTest(8, independentLedger, false)
        var independentScene: NativeScene? = null
        try {
            val layer = independentEditor.importPixels(8, 8, pixels(3))
            val expected = editorPixels(independentEditor)
            independentScene = checkNotNull(independentEditor.captureScene())
            val captured = independentLedger.snapshot()
            check(captured.reservations == 2 && captured.reservedBytes > independentEditor.installedDocumentBytes())
            independentLedger.reserve(captured.availableBytes, "independent snapshot refusal fixture").use {
                val before = independentLedger.snapshot()
                check(runCatching { independentEditor.captureScene()?.close() }.exceptionOrNull() is ResourceBudgetExceededException)
                check(equalClaims(before, independentLedger.snapshot()))
            }
            independentEditor.deleteLayer(layer)
            check(independentEditor.state().layers.isEmpty())
            check(scenePixels(checkNotNull(independentScene)).contentEquals(expected))
            independentEditor.undo(false)
            check(editorPixels(independentEditor).contentEquals(expected))
            independentEditor.close()
            check(independentLedger.snapshot().reservations == 1)
            check(scenePixels(checkNotNull(independentScene)).contentEquals(expected))
            independentScene.close(); independentScene = null
            noClaims(independentLedger)
            observations.put(JSONObject().put("stage", "independent-io-snapshot-edit-close-budget")
                .put("captureRefusalClaimsPreserved", true).put("survivesDeleteUndoAndClose", true))
        } finally { independentEditor.close(); independentScene?.close() }

        // Calibrate exact production inventories instead of guessing a tile/metadata allowance.
        val referenceLedger = ResourceBudgetLedger(roomy)
        lateinit var empty: LongArray
        lateinit var seven: LongArray
        lateinit var eight: LongArray
        NativeEditor.createWithLedgerForTest(8, referenceLedger, false).use { reference ->
            empty = readInfo(reference)
            for (index in 1..8) {
                reference.importPixels(8, 8, pixels(index))
                if (index == 7) seven = readInfo(reference)
            }
            eight = readInfo(reference)
            reference.save(checkpoint)
        }
        noClaims(referenceLedger)
        val recoveryProbeLedger = ResourceBudgetLedger(roomy)
        val recoveredEight = RecoveryAdmission.forTest(recoveryProbeLedger).load(checkpoint).use { readInfo(it) }
        noClaims(recoveryProbeLedger)
        val cap = minOf(eight[4], recoveredEight[4]) - 1
        check(seven[4] <= cap && eight[2] < cap && recoveredEight[2] < cap && cap < roomy)
        report.put("calibration", JSONObject().put("empty", infoJson(empty)).put("seven", infoJson(seven))
            .put("eight", infoJson(eight)).put("recoveredEight", infoJson(recoveredEight)).put("testedCapBytes", cap))

        // Constructor and recovery must reject before returning a candidate to workspace installation.
        val emptyTight = ResourceBudgetLedger(empty[4] - 1)
        check(empty[2] <= emptyTight.limitBytes)
        requireMemoryFailure("constructor-document-fits-but-scene-does-not") {
            NativeEditor.createWithLedgerForTest(8, emptyTight, false).close()
        }
        noClaims(emptyTight)
        val checkpointHash = digest(checkpoint.readBytes())
        val recoveryTight = ResourceBudgetLedger(cap)
        requireMemoryFailure("recovery-candidate-before-return") {
            RecoveryAdmission.forTest(recoveryTight).load(checkpoint).close()
        }
        noClaims(recoveryTight)
        check(digest(checkpoint.readBytes()) == checkpointHash)

        // Exercise actual replacement, including the old workspace's durable current pointer.
        val workspaceFolder = File(evidence, "workspace")
        val workspace = AndroidWorkspace(workspaceFolder)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(10)
            check(!workspace.snapshot().loading)
            workspace.flushAsync().get(10, TimeUnit.SECONDS)
            val oldWorkspace = workspace.snapshot()
            val oldEditor = checkNotNull(oldWorkspace.editor)
            val oldState = oldEditor.state()
            val oldPixels = editorPixels(oldEditor)
            val pointerFile = File(workspaceFolder, "active.json")
            val pointerBytes = pointerFile.readBytes()
            val store = WorkspaceRecoveryStore(workspaceFolder)
            val oldTarget = checkNotNull(store.read())
            val oldCheckpoint = store.checkpoint(oldTarget)
            val oldCheckpointHash = digest(oldCheckpoint.readBytes())
            val replacementLedger = ResourceBudgetLedger(cap)
            var loadCalls = 0
            val refusal = workspace.installCandidate("display-capacity-negative", DirtyDisposition.DISCARD) {
                ++loadCalls
                RecoveryAdmission.forTest(replacementLedger).load(checkpoint)
            }.result.get(10, TimeUnit.SECONDS)
            check(loadCalls == 1 && !refusal.installed && refusal.errorNotice?.key == UiNoticeKey.MEMORY_LIMIT)
            check(refusal.errorNotice?.text(chinese) == chinese.getString(R.string.notice_memory_limit))
            check(workspace.snapshot() == oldWorkspace && workspace.snapshot().editor === oldEditor)
            check(oldEditor.state() == oldState && editorPixels(oldEditor).contentEquals(oldPixels))
            check(pointerFile.readBytes().contentEquals(pointerBytes) && store.read() == oldTarget)
            check(digest(oldCheckpoint.readBytes()) == oldCheckpointHash && digest(checkpoint.readBytes()) == checkpointHash)
            noClaims(replacementLedger)
            observations.put(JSONObject().put("stage", "workspace-replacement-capacity-refusal")
                .put("candidateLoadCalls", loadCalls).put("noticeKey", refusal.errorNotice?.key?.name)
                .put("oldEditorStateGenerationPixelsAndPointerPreserved", true)
                .put("oldAndCandidateCheckpointBytesPreserved", true).put("candidateClaimsReleased", true))
        } finally {
            workspace.closeAsync().get(10, TimeUnit.SECONDS)
        }

        val ledger = ResourceBudgetLedger(cap)
        NativeEditor.createWithLedgerForTest(8, ledger, false).use { editor ->
            val identities = mutableMapOf<Int, Long>()
            for (index in 1..7) {
                editor.importPixels(8, 8, pixels(index))
                identities[index] = editor.currentContentIdentity()
                check(readInfo(editor)[4] <= cap)
            }
            val baseline = editor.state()
            val baselineIdentity = editor.currentContentIdentity()
            val baselineGeneration = editor.currentSceneGeneration()
            val baselinePixels = editorPixels(editor)
            val baselineInfo = readInfo(editor)
            val baselineClaims = ledger.snapshot()
            check(baseline.layers.size == 7 && baseline.undo && !baseline.redo)
            check(baselinePixels.contentEquals(ByteArray(8 * 8 * 4).also {
                val expected = pixels(7); expected.get(it)
            }))
            requireMemoryFailure("eighth-import-fixed-capacity") { editor.importPixels(8, 8, pixels(8)) }
            check(editor.state() == baseline && editor.currentContentIdentity() == baselineIdentity)
            check(editor.currentSceneGeneration() == baselineGeneration && readInfo(editor).contentEquals(baselineInfo))
            check(equalClaims(baselineClaims, ledger.snapshot()))
            check(editorPixels(editor).contentEquals(baselinePixels)) { "Refused import changed strict scene pixels" }
            observations.put(JSONObject().put("stage", "seven-visible-eighth-refused")
                .put("info", infoJson(readInfo(editor))).put("pixelSha256", digest(baselinePixels))
                .put("layers", editor.state().layers.size).put("claimsPreserved", true)
                .put("stateHistoryRevisionIdentityGenerationPreserved", true))

            editor.undo(false)
            check(editor.state().layers.size == 6 && editor.state().redo && editor.currentContentIdentity() == identities[6])
            val undone = editor.state()
            val undoneInfo = readInfo(editor)
            val undoneIdentity = editor.currentContentIdentity()
            val undonePixels = editorPixels(editor)
            val undoneClaims = ledger.snapshot()
            // Refusal must preserve an existing redo branch as well as the ordinary undo branch.
            requireMemoryFailure("oversized-import-preserves-redo") {
                editor.importPixels(257, 257, pixels(8, 257, 257))
            }
            check(editor.state() == undone && editor.currentContentIdentity() == undoneIdentity)
            check(readInfo(editor).contentEquals(undoneInfo) && equalClaims(undoneClaims, ledger.snapshot()))
            check(editorPixels(editor).contentEquals(undonePixels))
            editor.undo(true)
            check(editor.state().layers == baseline.layers && editor.currentContentIdentity() == baselineIdentity)
            check(editor.state().undo && !editor.state().redo && editorPixels(editor).contentEquals(baselinePixels))
            check(readInfo(editor)[4] <= cap)
            observations.put(JSONObject().put("stage", "undo-redo-after-refusal")
                .put("layers", editor.state().layers.size).put("contentIdentityRestored", true))
        }
        noClaims(ledger)

        // Reproduce the reported production capacity boundary; keep only one Java input buffer.
        val denseLedger = ResourceBudgetLedger(240L * 1024 * 1024)
        val denseCheckpoint = File(evidence, "dense-eight-layers.checkpoint")
        val denseInputBuffer = ByteBuffer.allocateDirect(2048 * 2048 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val denseWords = denseInputBuffer.asIntBuffer()
        val tileScratch = ByteBuffer.allocateDirect(256 * 256 * 4)
        fun fillDenseInput(index: Int): ByteBuffer {
            val rgba = (255 shl 24) or (index * 11 shl 16) or (index * 17 shl 8) or (index * 29)
            denseWords.rewind()
            repeat(denseWords.capacity()) { denseWords.put(rgba) }
            denseInputBuffer.rewind()
            return denseInputBuffer
        }
        fun denseSceneHash(scene: NativeScene, layerCount: Int): String {
            val hash = MessageDigest.getInstance("SHA-256")
            check(scene.metadata()[1] == 2048L && scene.metadata()[2] == 2048L && scene.metadata()[3] == layerCount.toLong())
            var tiles = 0
            for (layer in 0 until layerCount) {
                check(scene.layerMetadata(layer).tileCount == 64)
                for (tile in 0 until 64) {
                    val view = scene.tileViewMetadata(layer, tile)
                    check(view.width == 256 && view.height == 256)
                    tileScratch.clear()
                    scene.readTile(layer, tile, 256 * 4, tileScratch)
                    hash.update(tileScratch.asReadOnlyBuffer())
                    ++tiles
                }
            }
            check(tiles == layerCount * 64)
            val center = ByteBuffer.allocateDirect(3 * 3 * 4)
            scene.draw(3, 3, Viewport(1022.0, 1022.0, 3.0, 3.0), center)
            repeat(9) { pixel ->
                val actual = (0 until 4).map { center.get(pixel * 4 + it).toInt() and 255 }
                val expected = listOf(layerCount * 29, layerCount * 17, layerCount * 11, 255)
                check(actual == expected) { "dense center pixel=$pixel actual=$actual expected=$expected" }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        lateinit var denseState: EditorState
        lateinit var denseBaselineHash: String
        var denseSavedRevision = -1L
        NativeEditor.createWithLedgerForTest(2048, denseLedger, false).use { dense ->
            for (index in 1..8) {
                dense.importPixels(2048, 2048, fillDenseInput(index))
                // requiredBytes is the standalone scene occurrence estimate;
                // the managed claim is the retained document bytes.
                check(readInfo(dense)[2] <= denseLedger.limitBytes)
            }
            denseState = dense.state()
            val identity = dense.currentContentIdentity()
            val generation = dense.currentSceneGeneration()
            val beforeInfo = readInfo(dense)
            denseBaselineHash = checkNotNull(dense.captureDisplayScene()).use { denseSceneHash(it, 8) }
            val claims = denseLedger.snapshot()
            check(dense.state().layers.size == 8 && dense.currentContentIdentity() == identity &&
                dense.currentSceneGeneration() == generation && readInfo(dense)[2] <= denseLedger.limitBytes)
            check(denseLedger.snapshot().reservedBytes >= claims.reservedBytes)
            check(checkNotNull(dense.captureDisplayScene()).use { denseSceneHash(it, 8) } == denseBaselineHash)
            denseSavedRevision = dense.save(denseCheckpoint)
            check(denseSavedRevision == denseState.revision && dense.state() == denseState)
            observations.put(JSONObject().put("stage", "2048-dense-eight-visible-shared-tile-admission")
                .put("ledgerLimitBytes", denseLedger.limitBytes).put("layers", 8).put("tilesHashed", 512)
                .put("sourceRasterSha256", denseBaselineHash).put("inputBuffers", 1).put("inputBufferBytes", denseInputBuffer.capacity())
                .put("info", infoJson(beforeInfo)).put("strictCenterRGBA", JSONArray(listOf(232, 136, 88, 255)))
                .put("stateHistoryRevisionIdentityGenerationClaimsPreserved", true)
                .put("sharedCurrentTilesCreditedOnce", true))
        }
        noClaims(denseLedger)
        RecoveryAdmission.forTest(denseLedger).load(denseCheckpoint).use { reopened ->
            check(reopened.currentRevision() == denseSavedRevision && reopened.state().layers == denseState.layers &&
                reopened.state().document == denseState.document && !reopened.state().undo && !reopened.state().redo)
            check(readInfo(reopened)[2] <= denseLedger.limitBytes)
            check(checkNotNull(reopened.captureDisplayScene()).use { denseSceneHash(it, 8) } == denseBaselineHash)
            observations.put(JSONObject().put("stage", "2048-dense-eight-save-reopen")
                .put("sourceRasterSha256", denseBaselineHash).put("checkpointBytes", denseCheckpoint.length())
                .put("capturedRevision", denseSavedRevision).put("recoveredHistoryEmpty", true)
                .put("layerAndDocumentMetadataExact", true).put("tilesHashed", 512))
        }
        noClaims(denseLedger)

        // Active previews keep the committed estimate; canceled and accepted strokes use real JNI.
        val strokeLedger = ResourceBudgetLedger(roomy)
        val strokeEditor = NativeEditor.createWithLedgerForTest(8, strokeLedger, false)
        var retained: NativeScene? = null
        try {
            val layer = strokeEditor.importPixels(8, 8, pixels(1))
            val initialState = strokeEditor.state()
            val initialIdentity = strokeEditor.currentContentIdentity()
            val initialPixels = editorPixels(strokeEditor)
            val initialInfo = readInfo(strokeEditor)
            val settings = StrokeSettings(diameter = 3f, red = 1f)
            strokeEditor.startStroke(layer, settings)
            strokeEditor.samples(doubleArrayOf(4.0, 4.0, 1.0, 0.0))
            val active = readInfo(strokeEditor)
            check(active[2] == initialInfo[2] && active[3] == initialInfo[3] && active[4] == initialInfo[4])
            strokeEditor.endStroke(true)
            check(strokeEditor.currentContentIdentity() == initialIdentity && editorPixels(strokeEditor).contentEquals(initialPixels))
            check(strokeEditor.state().layers == initialState.layers && strokeEditor.state().undo == initialState.undo &&
                strokeEditor.state().redo == initialState.redo)
            // Mutating commands fence on outstanding display scenes. Release the
            // initial one before starting the committed stroke; retain a final
            // committed scene below to verify lifetime across document close.
            retained = checkNotNull(strokeEditor.captureScene())
            check(scenePixels(checkNotNull(retained)).contentEquals(initialPixels))
            checkNotNull(retained).close(); retained = null
            strokeEditor.startStroke(layer, settings)
            strokeEditor.samples(doubleArrayOf(4.0, 4.0, 1.0, 0.0))
            strokeEditor.endStroke(false)
            val painted = editorPixels(strokeEditor)
            val paintedIdentity = strokeEditor.currentContentIdentity()
            check(!painted.contentEquals(initialPixels) && paintedIdentity != initialIdentity)
            check(readInfo(strokeEditor)[4] <= roomy)
            strokeEditor.undo(false)
            check(strokeEditor.currentContentIdentity() == initialIdentity && editorPixels(strokeEditor).contentEquals(initialPixels))
            strokeEditor.undo(true)
            check(strokeEditor.currentContentIdentity() == paintedIdentity && editorPixels(strokeEditor).contentEquals(painted))
            retained = checkNotNull(strokeEditor.captureScene())
            strokeEditor.close()
            check(strokeLedger.snapshot().reservedBytes > 0 && strokeLedger.snapshot().reservations == 1)
            check(scenePixels(checkNotNull(retained)).contentEquals(painted)) { "Document retirement changed retained scene" }
            checkNotNull(retained).close(); retained = null
            noClaims(strokeLedger)
            observations.put(JSONObject().put("stage", "stroke-cancel-commit-history-scene-lifetime")
                .put("canceledPixelsPreserved", true).put("activeEstimateUsesCommittedState", true)
                .put("undoRedoPixelExact", true).put("retainedSceneSurvivesDocumentClose", true))
        } finally {
            strokeEditor.close(); retained?.close()
        }
        noClaims(strokeLedger)

        for (failure in listOf(NativeCommandException(3), TransformDraftException(3),
            ResourceBudgetExceededException(1, 0, "localized admission regression"))) {
            val notice = uiFailure(failure, UiNoticeKey.EDITOR_COMMAND_FAILED)
            check(notice.key == UiNoticeKey.MEMORY_LIMIT && notice.text(chinese) == chinese.getString(R.string.notice_memory_limit))
        }
        check(uiFailure(NativeCommandException(2), UiNoticeKey.EDITOR_COMMAND_FAILED).key == UiNoticeKey.EDITOR_COMMAND_FAILED)
        check(uiFailure(NativeCommandException(5), UiNoticeKey.EDITOR_COMMAND_FAILED).key == UiNoticeKey.EDITOR_COMMAND_FAILED)
        check(uiFailure(TransformDraftException(5), UiNoticeKey.TRANSFORM_FAILED).key == UiNoticeKey.TRANSFORM_FAILED)
        report.put("passed", true)
    } catch (error: Throwable) {
        report.put("passed", false).put("error", error.stackTraceToString())
        throw error
    } finally {
        report.put("observations", observations)
        File(evidence, "result.json").writeText(report.toString(2))
    }
    return "Fixed-capacity JNI admission passed: calibrated small raster and actual 2048-square 240 MiB eight-layer fixtures with shared current tiles credited once; dense eight-layer save/reopen exact; strict pixels, history, revision, identity, generation and claims verified; constructor/recovery and workspace refusal gates preserved; committed estimate during stroke, cancel/commit/undo/redo and retained-scene lifetime passed; typed Chinese memory notice. Evidence=${evidence.absolutePath}"
}
