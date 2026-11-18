package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal fun exportProcessSourceHashes(context: Context): Map<String, String> {
    val target = DocumentsProjectExportTarget(context.contentResolver)
    val cancel = ProjectTransportCancellation()
    fun children(parent: ProjectTransportDocument) = buildList { target.children(parent, cancel) { add(it) } }
    val old = children(target.root(ExportDocumentsProvider.tree("ok"), cancel)).single { it.displayName == "old.comp" }
    val result = sortedMapOf<String, String>()
    fun scan(parent: ProjectTransportDocument, prefix: String) {
        for (child in children(parent)) if (child.directory) scan(child, "$prefix${child.displayName}/")
        else result["$prefix${child.displayName}"] = target.open(child, cancel).use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        }
    }
    scan(old, ""); check(result.containsKey("manifest.json") && result.keys.any { it.endsWith(".png") }); return result
}

/** A second instrumentation process verifies the durable shipping receipt after an external actual force-stop. */
internal fun projectExportProcessRecoverySmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val markerFile = File(context.filesDir.canonicalFile, "p14-export-kill-ready.json")
    val marker = JSONObject(markerFile.readText())
    val receiptId = marker.getString("receiptId")
    val receipts = ProjectExportReceipts(context)
    val interrupted = receipts.readAll().single { it.id == receiptId }
    check(interrupted.phase == "WRITING" && interrupted.revision == marker.getLong("revision") && interrupted.owned.size == 2)
    val expectedObject = marker.getJSONObject("source")
    val expected = expectedObject.keys().asSequence().associateWith { expectedObject.getString(it) }
    check(exportProcessSourceHashes(context) == expected)
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    val owner = activity.retainedProjectFlowForTest()
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (owner.startupCleanupResult == null && System.nanoTime() < deadline) Thread.sleep(20)
        check(owner.startupCleanupResult?.isSuccess == true && owner.exportRecoveryNotice != null)
        check(receipts.readAll().single { it.id == receiptId }.phase == "WRITING")
        val target = DocumentsProjectExportTarget(context.contentResolver); val cancel = ProjectTransportCancellation()
        val children = buildList { target.children(target.root(interrupted.tree, cancel), cancel) { add(it) } }
        check(children.none { it.displayName == interrupted.finalName })
        val partial = children.single { it.documentId == interrupted.owned.first().document.documentId }
        val contents = buildList { target.children(partial, cancel) { add(it) } }
        check(contents.size == 1 && contents.single().directory && contents.single().displayName == "images")
        check(exportProcessSourceHashes(context) == expected)
        return "Actual external force-stop interrupted ordinary native export after durable WRITING receipt and provider root/images creation. A new process/startup owner retained nonrecursive directories, published no final, reported review notice, and preserved old.comp manifest/PNG hashes and captured revision."
    } finally {
        instrumentation.runOnMainSync { activity.finish() }
        activity.retainedWorkspaceForTest().closeAsync().get(10, TimeUnit.SECONDS)
        // Metadata-only acknowledgment; directories remain in the provider for inspection.
        receipts.remove(receiptId); markerFile.delete()
    }
}
