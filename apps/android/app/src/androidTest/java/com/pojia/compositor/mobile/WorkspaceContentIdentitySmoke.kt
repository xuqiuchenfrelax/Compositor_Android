package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/** Real workspace/JNI/save/recovery contracts; tags are never restored from pointer numbers. */
internal fun workspaceContentIdentitySmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val root = File(context.cacheDir, "workspace-content-${UUID.randomUUID()}").apply { check(mkdir()) }
    val workspaces = mutableListOf<AndroidWorkspace>()
    val releases = mutableListOf<CountDownLatch>()
    fun opened(folder: File, hooks: RecoveryPublicationHooks? = null): AndroidWorkspace {
        val workspace = AndroidWorkspace(folder, recoveryHooks = hooks).also(workspaces::add)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(10)
        check(!workspace.snapshot().loading) { "Workspace did not open" }
        return workspace
    }
    fun ready(workspace: AndroidWorkspace): NativeEditor {
        check(workspace.snapshot().recoveryError == null) { workspace.snapshot().recoveryError.orEmpty() }
        return checkNotNull(workspace.snapshot().editor)
    }
    fun fixture(red: Byte = 32): NativeEditor = NativeEditor(8, addDefaultLayers = false).also { editor ->
        repeat(2) { index ->
            val pixels = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(red, index.toByte(), 0, -1)); rewind() }
            val id = editor.importPixels(1, 1, pixels)
            editor.renameLayer(id, "Layer $index")
        }
    }
    fun copyPointed(folder: File, name: String): File {
        val destination = File(root, name).apply { check(mkdir()) }
        val bytes = File(folder, "active.json").readBytes()
        val fields = JSONObject(JSONObject(String(bytes, Charsets.UTF_8)).getString("payload"))
        File(folder, fields.getString("checkpoint")).copyTo(File(destination, fields.getString("checkpoint")))
        File(destination, "active.json").writeBytes(bytes)
        return destination
    }
    fun rewrite(folder: File, version: Int, alter: (JSONObject) -> Unit) {
        val file = File(folder, "active.json")
        val envelope = JSONObject(file.readText())
        val fields = JSONObject(envelope.getString("payload"))
        alter(fields)
        val payload = fields.toString()
        envelope.put("version", version).put("payload", payload)
            .put("crc32", CRC32().apply { update(payload.toByteArray(Charsets.UTF_8)) }.value)
        file.writeText(envelope.toString())
    }
    fun frozenSave(workspace: AndroidWorkspace, name: String, fault: RecoveryFault = RecoveryFault.NONE): ProjectSaveResult =
        workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
            lease.saveProject(context, File(root, "$name.comp"), false, 64L * 1024 * 1024,
                lease.memory.totalBytes + 1024 * 1024, "source-$name", fault)
        }, {}).get(10, TimeUnit.SECONDS)
    try {
        val folder = File(root, "workspace")
        val workspace = opened(folder)
        ready(workspace)
        check(workspace.snapshot().projectDirty && workspace.snapshot().projectContentBaseline == null)
        check(workspace.installCandidate("fixture-source", DirtyDisposition.DISCARD, candidate = { fixture() })
            .result.get(10, TimeUnit.SECONDS).installed)
        val editor = ready(workspace)
        val layer = editor.state().layers.first().id
        val initial = workspace.snapshot()
        check(!initial.projectDirty && initial.projectContentBaseline == editor.currentContentIdentity())
        editor.selectLayer(layer)
        check(workspace.snapshot().currentRevision > initial.currentRevision && !workspace.snapshot().projectDirty)
        editor.renameLayer(layer, "Changed")
        check(workspace.snapshot().projectDirty)
        editor.undo(false)
        check(!workspace.snapshot().projectDirty && workspace.snapshot().currentRevision > initial.currentRevision)
        editor.undo(true)
        check(workspace.snapshot().projectDirty)
        editor.undo(false)
        check(!workspace.snapshot().projectDirty)

        // A saved immutable project may finish after a later content edit; its own tag is the baseline.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        workspace.runIo({ entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }, {})
        check(entered.await(10, TimeUnit.SECONDS))
        val capturedTag = editor.currentContentIdentity()
        val saving = workspace.saveProject(context, File(root, "late.comp"), false, 64L * 1024 * 1024,
            editor.retainedMemory().totalBytes + 1024 * 1024, "late-source")
        editor.renameLayer(layer, "After project capture")
        val laterTag = editor.currentContentIdentity()
        release.countDown()
        val saved = saving.result.get(10, TimeUnit.SECONDS)
        check(saved.successful && saved.baselineUpdated)
        check(workspace.snapshot().projectContentBaseline == capturedTag && workspace.snapshot().projectDirty)
        check(editor.currentContentIdentity() == laterTag)
        val store = WorkspaceRecoveryStore(folder)
        check(store.read()!!.projectClean == false)
        val dirtyCopy = copyPointed(folder, "cold-dirty")
        val coldDirty = opened(dirtyCopy)
        check(ready(coldDirty).currentContentIdentity() == 0L && coldDirty.snapshot().projectContentBaseline == null && coldDirty.snapshot().projectDirty)
        editor.undo(false)
        check(!workspace.snapshot().projectDirty && workspace.snapshot().projectContentBaseline == capturedTag)
        editor.undo(true)
        check(workspace.snapshot().projectDirty)
        editor.undo(false)
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        check(store.read()!!.projectClean == true)
        val cleanCopy = copyPointed(folder, "cold-clean")
        val coldClean = opened(cleanCopy)
        check(ready(coldClean).currentContentIdentity() == 0L && coldClean.snapshot().projectContentBaseline == 0L && !coldClean.snapshot().projectDirty)
        // Two loaded native lifetimes can have equal revision and zero tags, but different known-save relations.
        val otherDomainFolder = copyPointed(cleanCopy, "same-revision-other-domain")
        rewrite(otherDomainFolder, 2) { it.put("projectClean", false).put("sourceIdentity", "unverified-other-source") }
        val otherDomain = opened(otherDomainFolder)
        check(ready(otherDomain).currentContentIdentity() == 0L)
        check(otherDomain.snapshot().currentRevision == coldClean.snapshot().currentRevision)
        check(otherDomain.snapshot().projectContentBaseline == null && otherDomain.snapshot().projectDirty)
        check(coldClean.snapshot().projectContentBaseline == 0L && !coldClean.snapshot().projectDirty)

        // Pointer before-rename failure leaves baseline and pointer intact; after-rename is adopted but uncertain.
        editor.renameLayer(layer, "Failure subject")
        val before = workspace.snapshot()
        val oldPointer = File(folder, "active.json").readBytes()
        val failed = frozenSave(workspace, "before", RecoveryFault.BEFORE_RENAME)
        check(failed.successful && !failed.baselineUpdated && workspace.snapshot().projectDirty)
        check(workspace.snapshot().projectContentBaseline == before.projectContentBaseline &&
            workspace.snapshot().sourceIdentity == before.sourceIdentity)
        check(File(folder, "active.json").readBytes().contentEquals(oldPointer))
        val uncertain = frozenSave(workspace, "after", RecoveryFault.AFTER_RENAME)
        check(uncertain.successful && !uncertain.baselineUpdated && uncertain.metadataDurabilityUncertain)
        check(workspace.snapshot().projectContentBaseline == before.projectContentBaseline && workspace.snapshot().projectDirty &&
            workspace.snapshot().sourceIdentity == before.sourceIdentity && workspace.snapshot().recoveryDurabilityUncertain)
        check(store.read()!!.projectClean == true && store.read()!!.sourceIdentity == "source-after")
        val uncertainCopy = copyPointed(folder, "cold-uncertain")
        val recoveredUncertain = opened(uncertainCopy)
        ready(recoveredUncertain)
        check(!recoveredUncertain.snapshot().projectDirty && recoveredUncertain.snapshot().projectContentBaseline == 0L)
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        check(store.read()!!.projectClean == false && store.read()!!.sourceIdentity == before.sourceIdentity)

        // A replaced generation cannot publish a colliding identity or stale callback into its successor.
        val oldCallback = editor.committed
        check(workspace.installCandidate("replacement-source", DirtyDisposition.DISCARD, candidate = { fixture(112) })
            .result.get(10, TimeUnit.SECONDS).installed)
        val replacement = workspace.snapshot()
        oldCallback?.invoke(Long.MAX_VALUE)
        check(workspace.snapshot() == replacement && !replacement.projectDirty)
        val replacementEditor = ready(workspace)
        check(replacement.projectContentBaseline == replacementEditor.currentContentIdentity())
        replacementEditor.selectLayer(replacementEditor.state().layers.first().id)
        check(workspace.installCandidate("clean-replacement", candidate = { fixture(48) })
            .result.get(10, TimeUnit.SECONDS).installed) { "Selection-only changes incorrectly required discard" }

        // Strict v2 validation and conservative v1 migration; opening alone must preserve the old file bytes.
        val migrationBase = copyPointed(folder, "migration-base")
        val migrationTarget = checkNotNull(WorkspaceRecoveryStore(migrationBase).read())
        val checkpointRevision = NativeEditor.load(WorkspaceRecoveryStore(migrationBase).checkpoint(migrationTarget)).use { it.currentRevision() }
        for (mode in listOf("equal", "older", "unknown")) {
            val legacy = copyPointed(migrationBase, "v1-$mode")
            rewrite(legacy, 1) { fields ->
                fields.remove("projectClean")
                fields.put("projectBaseline", when (mode) { "equal" -> checkpointRevision; "older" -> checkpointRevision - 1; else -> JSONObject.NULL })
            }
            val original = File(legacy, "active.json").readBytes()
            val migrated = opened(legacy)
            val migratedEditor = ready(migrated)
            check(migratedEditor.currentContentIdentity() == 0L)
            check(migrated.snapshot().projectDirty == (mode != "equal"))
            check(File(legacy, "active.json").readBytes().contentEquals(original))
            migratedEditor.selectLayer(migratedEditor.state().layers.first().id)
            migrated.flushAsync().get(10, TimeUnit.SECONDS)
            check(JSONObject(File(legacy, "active.json").readText()).getInt("version") == 2)
            check(WorkspaceRecoveryStore(legacy).read()!!.projectClean == (mode == "equal"))
        }
        for (mode in listOf("string", "number", "null", "missing", "cleanWithoutBaseline", "extra", "v3")) {
            val invalid = copyPointed(migrationBase, "invalid-$mode")
            rewrite(invalid, if (mode == "v3") 3 else 2) { fields ->
                when (mode) {
                    "string" -> fields.put("projectClean", "true")
                    "number" -> fields.put("projectClean", 1)
                    "null" -> fields.put("projectClean", JSONObject.NULL)
                    "missing" -> fields.remove("projectClean")
                    "cleanWithoutBaseline" -> fields.put("projectClean", true).put("projectBaseline", JSONObject.NULL)
                    "extra" -> fields.put("contentIdentity", 0)
                }
            }
            val original = File(invalid, "active.json").readBytes()
            val rejected = opened(invalid)
            check(rejected.snapshot().editor == null && rejected.snapshot().recoveryError != null)
            check(File(invalid, "active.json").readBytes().contentEquals(original))
        }

        // Capture clean, edit after the checkpoint writer, and publish the older clean relation unchanged.
        val lateFolder = File(root, "late-checkpoint")
        val armed = AtomicBoolean(false)
        val checkpointEntered = CountDownLatch(1)
        val checkpointRelease = CountDownLatch(1).also(releases::add)
        val lateWorkspace = opened(lateFolder, RecoveryPublicationHooks(afterCheckpoint = { kind, _, _, _ ->
            if (kind == RecoverySaveKind.FLUSH && armed.compareAndSet(true, false)) {
                checkpointEntered.countDown()
                check(checkpointRelease.await(10, TimeUnit.SECONDS))
            }
        }))
        ready(lateWorkspace)
        check(lateWorkspace.installCandidate("late-fixture", DirtyDisposition.DISCARD, candidate = { fixture() })
            .result.get(10, TimeUnit.SECONDS).installed)
        val lateEditor = ready(lateWorkspace)
        lateEditor.selectLayer(lateEditor.state().layers.first().id)
        armed.set(true)
        val flushing = lateWorkspace.flushAsync()
        check(checkpointEntered.await(10, TimeUnit.SECONDS))
        lateEditor.renameLayer(lateEditor.state().layers.first().id, "After checkpoint capture")
        check(lateWorkspace.snapshot().projectDirty)
        // Queue a second worker fence before releasing the writer so the follow-up autosave cannot
        // overwrite the pointed checkpoint before its cold-recovery relation is inspected.
        val afterFlushEntered = CountDownLatch(1)
        val afterFlushRelease = CountDownLatch(1).also(releases::add)
        lateWorkspace.runIo({ afterFlushEntered.countDown(); check(afterFlushRelease.await(10, TimeUnit.SECONDS)) }, {})
        checkpointRelease.countDown()
        flushing.get(10, TimeUnit.SECONDS)
        check(afterFlushEntered.await(10, TimeUnit.SECONDS))
        val lateColdFolder = copyPointed(lateFolder, "late-checkpoint-cold")
        check(WorkspaceRecoveryStore(lateColdFolder).read()!!.projectClean == true)
        val lateCold = opened(lateColdFolder)
        ready(lateCold)
        check(!lateCold.snapshot().projectDirty && lateWorkspace.snapshot().projectDirty)
        afterFlushRelease.countDown()
        return "PASS: content dirty/selection/history; late project and checkpoint captures; v2 strict/v1 conservative migration; before/after rename; zero-domain recovery and stale-generation rejection"
    } finally {
        releases.forEach { it.countDown() }
        workspaces.forEach { it.closeAsync().get(10, TimeUnit.SECONDS) }
        root.deleteRecursively()
    }
}
