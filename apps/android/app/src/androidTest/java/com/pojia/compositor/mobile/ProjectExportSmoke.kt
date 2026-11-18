package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual ContentResolver/standalone SAF faults exercise shipping publication and private recovery receipts. */
internal fun projectExportSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    fun grant(restore: String? = null) {
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,
            ProjectProviderGrantActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("restoreExport", restore))
        instrumentation.waitForIdleSync()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        var ready = false
        while (!ready && System.nanoTime() < deadline) {
            ready = runCatching { DocumentsProjectExportTarget(context.contentResolver).root(
                ExportDocumentsProvider.tree(restore ?: "ok"), ProjectTransportCancellation()); true }.getOrDefault(false)
            if (!ready) Thread.sleep(20)
        }
        check(ready) { "Standalone provider grant/restoration did not become observable" }
    }
    grant("revoked")
    val scratch = File(context.cacheDir.canonicalFile, "project-export-test-${System.nanoTime()}").apply { check(mkdirs()) }
    val target = DocumentsProjectExportTarget(context.contentResolver)
    val receipts = ProjectExportReceipts(context)
    val cancel = ProjectTransportCancellation()
    fun children(parent: ProjectTransportDocument): List<ProjectTransportDocument> = buildList { target.children(parent, cancel) { add(it) } }
    fun root(mode: String) = target.root(ExportDocumentsProvider.tree(mode), ProjectTransportCancellation())
    fun oldHash(mode: String): Map<String, String> {
        val old = children(root(mode)).single { it.displayName == "old.comp" }
        val result = sortedMapOf<String, String>()
        fun scan(parent: ProjectTransportDocument, prefix: String) {
            for (child in children(parent)) if (child.directory) scan(child, "$prefix${child.displayName}/")
            else result["$prefix${child.displayName}"] = target.open(child, cancel).use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
            }
        }
        scan(old, ""); check(result.containsKey("manifest.json") && result.keys.any { it.endsWith(".png") }); return result
    }
    val referencePackage = File(scratch, "source.comp")
    var expectedCanvas = ByteArray(0); var captured = 0L
    NativeEditor(8).use { editor ->
        editor.state().layers.forEachIndexed { index, layer -> editor.renameLayer(layer.id, "Export layer $index") }
        val id = editor.state().layers.last().id
        editor.startStroke(id, 2f, false); editor.samples(doubleArrayOf(3.0, 3.0, 1.0, 0.0)); editor.endStroke(false)
        val pixels = java.nio.ByteBuffer.allocateDirect(8 * 8 * 4)
        check(editor.draw(8, 8, 0.0, 0.0, 8.0, 8.0, pixels)); expectedCanvas = ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
        editor.captureProjectSnapshot().use { snapshot ->
            captured = snapshot.capturedRevision
            NativeProjectWriteControl(512L * 1024 * 1024, editor.retainedMemory().totalBytes).use { control ->
                check(snapshot.write(context, referencePackage, false, control).successful)
            }
            editor.undo(false) // Exported data and result identify the captured revision despite subsequent changes.
            check(editor.currentRevision() != captured)
        }
    }
    val reports = ArrayList<String>()
    try {
        for (mode in listOf("ok", "alias", "wrongname", "diskfull", "pipe", "corrupt", "renamethrow", "foreign", "revoked")) {
            val before = oldHash(mode); val writes = AtomicInteger(); val deletes = AtomicInteger()
            val recording = object : ProjectExportTarget by target {
                override fun write(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): ProjectExportOutput {
                    check(!document.documentId.contains("/old.comp/")); writes.incrementAndGet(); return target.write(document, cancellation)
                }
                override fun delete(document: ProjectTransportDocument) {
                    check(!document.documentId.contains("/old.comp")); deletes.incrementAndGet(); target.delete(document)
                }
            }
            val result = ProjectExporter(recording, receipts).publish(referencePackage, ExportDocumentsProvider.tree(mode), captured, ProjectExportCancellation(), suggestedName = "中文.工程.comp")
            check(result.capturedRevision == captured && !result.durabilityConfirmed)
            check(result.displayName.startsWith("中文.工程--") && result.displayName.endsWith(".comp"))
            when (mode) {
                "ok" -> {
                    check(result.commit == ProjectExportCommit.COMMITTED_VERIFIED && result.cleanup == ProjectExportCleanup.NOT_NEEDED && result.destination != null)
                    check(DocumentsContract.getDocumentId(result.destination).endsWith(result.displayName)) { "Changed rename ID was not adopted" }
                    val published = children(root(mode)).single { it.displayName == result.displayName }
                    val source = object : ProjectTransportSource by target { override fun root(treeUri: Uri, cancellation: ProjectTransportCancellation) = published }
                    ProjectTransport(source, File(scratch, "readback")).stage(ExportDocumentsProvider.tree(mode), ProjectTransportCancellation()).use { staged ->
                        val paths = referencePackage.walkTopDown().filter { it.isFile }.map { it.relativeTo(referencePackage).invariantSeparatorsPath }.toList()
                        for (path in paths) check(File(staged.directory, path).readBytes().contentEquals(File(referencePackage, path).readBytes()))
                        NativeEditor.loadProjectCandidate(context, staged.directory, 512L * 1024 * 1024, 0).use { loaded ->
                            val pixels = java.nio.ByteBuffer.allocateDirect(expectedCanvas.size)
                            check(loaded.draw(8, 8, 0.0, 0.0, 8.0, 8.0, pixels))
                            check(ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }.contentEquals(expectedCanvas))
                        }
                    }
                    receipts.remove(checkNotNull(result.receiptId))
                }
                "alias" -> check(result.commit == ProjectExportCommit.NOT_COMMITTED && writes.get() == 0 && deletes.get() == 0 && result.cleanup == ProjectExportCleanup.UNKNOWN)
                "wrongname" -> check(result.commit == ProjectExportCommit.NOT_COMMITTED && writes.get() == 0 && deletes.get() == 0)
                "renamethrow" -> {
                    check(result.commit == ProjectExportCommit.UNKNOWN && result.cleanup == ProjectExportCleanup.UNKNOWN)
                    check(children(root(mode)).any { it.displayName == result.displayName })
                    receipts.recover(target)
                    check(children(root(mode)).any { it.displayName == result.displayName }) { "Unknown publication was deleted" }
                }
                "foreign" -> {
                    check(result.commit == ProjectExportCommit.NOT_COMMITTED && result.cleanup == ProjectExportCleanup.RETAINED)
                    val partial = children(root(mode)).single { it.displayName.contains(checkNotNull(result.receiptId)) }
                    check(children(partial).any { it.displayName == "foreign.txt" }) { "Cleanup removed an unknown external item" }
                }
                "revoked" -> {
                    check(result.commit == ProjectExportCommit.NOT_COMMITTED && result.cleanup == ProjectExportCleanup.RETAINED)
                    grant("revoked")
                    check(ProjectExportReceipts(context).recover(target).retained >= 1) { "Interrupted directories must be retained" }
                    val partial = children(root(mode)).single { it.displayName.contains(checkNotNull(result.receiptId)) }
                    check(children(partial).all { it.directory }) { "Restored grant did not reclaim unchanged owned files" }
                }
                else -> check(result.commit == ProjectExportCommit.NOT_COMMITTED && result.cleanup == ProjectExportCleanup.RETAINED)
            }
            check(oldHash(mode) == before) { "$mode export changed original manifest/PNG bytes" }
            reports.add("$mode commit=${result.commit} cleanup=${result.cleanup} captured=$captured old-source exact")
        }
        val cancelDuringCopy = ProjectExportCancellation()
        val cancelled = ProjectExporter(target, receipts).publish(referencePackage, ExportDocumentsProvider.tree("ok"), captured, cancelDuringCopy) { phase, _ ->
            if (phase.startsWith("Exporting")) check(cancelDuringCopy.cancel())
        }
        check(cancelled.cancelled && cancelled.commit == ProjectExportCommit.NOT_COMMITTED && cancelled.cleanup == ProjectExportCleanup.RETAINED)
        val cancelAtPublication = ProjectExportCancellation(); var accepted = true
        val late = ProjectExporter(target, receipts).publish(referencePackage, ExportDocumentsProvider.tree("ok"), captured, cancelAtPublication) { _, canCancel ->
            if (!canCancel) accepted = cancelAtPublication.cancel()
        }
        check(!accepted && late.commit == ProjectExportCommit.COMMITTED_VERIFIED && !late.cancelled)
        receipts.remove(checkNotNull(late.receiptId))
        // Real private-receipt exhaustion after observable publication must not erase a verified outcome.
        val receiptFolder = NativeProjectIO.ownedPath(context, File(context.filesDir, "project-export-receipts"))
        val budgetFile = File(receiptFolder, "completion-budget-test.tmp")
        val completionPolicy = ProjectExportPolicy(receiptTotalBytes = 128L * 1024)
        val completionReceipts = ProjectExportReceipts(context, completionPolicy)
        val exhaustionTarget = object : ProjectExportTarget by target {
            override fun rename(document: ProjectTransportDocument, name: String): ProjectTransportDocument {
                val renamed = target.rename(document, name)
                budgetFile.writeBytes(ByteArray(128 * 1024))
                return renamed
            }
        }
        val exhausted = try { ProjectExporter(exhaustionTarget, completionReceipts, completionPolicy).publish(
            referencePackage, ExportDocumentsProvider.tree("ok"), captured, ProjectExportCancellation()) }
            finally { budgetFile.delete() }
        check(exhausted.commit == ProjectExportCommit.COMMITTED_VERIFIED && exhausted.destination != null && exhausted.error != null)
        check(children(root("ok")).any { it.displayName == exhausted.displayName })
        val durablePreRename = receipts.readAll().single { it.id == exhausted.receiptId }
        check(durablePreRename.phase == "PUBLISHING")
        receipts.recover(target)
        check(children(root("ok")).any { it.displayName == exhausted.displayName })
        receipts.remove(checkNotNull(exhausted.receiptId))

        // A malformed receipt disables the bounded automatic sweep, retaining every external item.
        val corruptId = UUID.randomUUID().toString()
        receipts.write(ProjectExportReceipt(corruptId, ExportDocumentsProvider.tree("ok"), captured,
            "Compositor-$corruptId.comp", "UNKNOWN", emptyList()))
        val corruptReceipt = File(receiptFolder, "$corruptId.json")
        val originalReceipt = corruptReceipt.readBytes()
        fun rewriteReceipt(version: Int, name: String) {
            val wrapper = org.json.JSONObject(String(originalReceipt, Charsets.UTF_8))
            val payload = org.json.JSONObject(wrapper.getString("payload")).put("version", version).put("finalName", name).toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            corruptReceipt.writeText(org.json.JSONObject().put("payload", payload).put("sha256", digest).toString())
        }
        rewriteReceipt(1, "Compositor-$corruptId.comp")
        check(receipts.readAll().single { it.id == corruptId }.finalName == "Compositor-$corruptId.comp")
        val namedReceipt = ProjectExportPackageNames.finalName(corruptId, "中文.工程.comp")
        rewriteReceipt(2, namedReceipt)
        check(receipts.readAll().single { it.id == corruptId }.finalName == namedReceipt)
        val migrationInventory = children(root("ok")).map { it.documentId }
        for ((version, name) in listOf(1 to namedReceipt, 3 to "Compositor-$corruptId.comp", 2 to "../$namedReceipt")) {
            rewriteReceipt(version, name)
            check(receipts.recover(target).limited && children(root("ok")).map { it.documentId } == migrationInventory)
        }
        corruptReceipt.writeBytes(originalReceipt)
        corruptReceipt.writeText("{\"payload\":\"damaged\",\"sha256\":\"wrong\"}")
        val inventoryBeforeRecovery = children(root("ok")).map { it.documentId }
        check(receipts.recover(target).limited && children(root("ok")).map { it.documentId } == inventoryBeforeRecovery)
        corruptReceipt.writeBytes(originalReceipt); receipts.remove(corruptId)
        val beforeLimit = children(root("ok")).map { it.documentId }
        val limited = ProjectExporter(target, receipts, ProjectExportPolicy(directoryEntries = 1)).publish(referencePackage,
            ExportDocumentsProvider.tree("ok"), captured, ProjectExportCancellation())
        check(limited.commit == ProjectExportCommit.NOT_COMMITTED && children(root("ok")).map { it.documentId } == beforeLimit)
        val deleteFailureCancel = ProjectExportCancellation()
        val retained = ProjectExporter(target, receipts).publish(referencePackage, ExportDocumentsProvider.tree("deletefail"), captured, deleteFailureCancel) { _, _ -> deleteFailureCancel.cancel() }
        check(retained.cancelled && retained.cleanup == ProjectExportCleanup.RETAINED)
        check(ProjectExportReceipts(context).recover(target).retained >= 1)
        reports.add("pre-publication cancel retains directories; publication gate rejects late cancel and retains verified commit; package/list bounds fail before mutation; cleanup refusal/unknown external children/permission recovery persist receipts")
        reports.add("Receipt checksum corruption prevents provider cleanup; real post-publication receipt budget failure preserves COMMITTED_VERIFIED and PUBLISHING receipt/final directory")
        return reports.joinToString("\n") + "\nSAF readback confirms observable complete bytes, not provider atomic rename or durable/cloud commit."
    } finally {
        // Test receipts only. No provider folder/final project is removed here.
        runCatching { receipts.readAll().filter { it.tree.authority == ExportDocumentsProvider.AUTHORITY }.forEach { receipts.remove(it.id) } }
        scratch.deleteRecursively()
    }
}
