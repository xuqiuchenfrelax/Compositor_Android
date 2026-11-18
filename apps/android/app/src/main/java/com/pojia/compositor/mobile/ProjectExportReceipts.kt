package com.pojia.compositor.mobile

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.security.MessageDigest

internal data class ProjectExportReceipt(val id: String, val tree: Uri, val revision: Long, val finalName: String,
    var phase: String, val owned: List<ProjectExportOwned>, var destination: Uri? = null)
internal data class ProjectExportRecovery(val removed: Int, val retained: Int, val uncertain: Int, val limited: Boolean)

/** Bounded private receipts survive restart. Publication/final receipts never authorize automatic deletion. */
internal class ProjectExportReceipts(context: Context, private val policy: ProjectExportPolicy = ProjectExportPolicy()) {
    private val root = NativeProjectIO.ownedPath(context, File(context.filesDir, "project-export-receipts"))
    private fun directory(): File {
        check(!Files.isSymbolicLink(root.toPath())) { "Export receipt folder is unsafe" }
        if (!root.exists()) {
            check(root.mkdir()) { "Export receipt folder cannot be created" }
            val parent = Os.open(root.parentFile!!.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try { Os.fsync(parent) } finally { Os.close(parent) }
        }
        check(root.isDirectory)
        return root
    }
    private fun name(id: String): String { check(UUID.fromString(id).toString() == id); return "$id.json" }
    private fun document(value: ProjectTransportDocument) = JSONObject().put("uri", value.uri.toString())
        .put("id", value.documentId).put("name", value.displayName).put("mime", value.mimeType)
        .put("flags", value.flags).put("size", value.size ?: JSONObject.NULL)
        .put("modified", value.modified ?: JSONObject.NULL).put("canonical", value.canonicalIdentity)
    private fun document(value: JSONObject): ProjectTransportDocument {
        val fields = listOf("uri", "id", "name", "mime", "canonical").map { value.getString(it) }
        check(fields.all { it.toByteArray().size <= 16_384 }) { "Export receipt metadata is too large" }
        return ProjectTransportDocument(Uri.parse(fields[0]), fields[1], fields[2], fields[3], value.getInt("flags"),
            if (value.isNull("size")) null else value.getLong("size"),
            if (value.isNull("modified")) null else value.getLong("modified"), fields[4])
    }
    private fun entries(): List<File> {
        val files = ArrayList<File>(); var bytes = 0L
        Files.newDirectoryStream(directory().toPath()).use { stream -> for (path in stream) {
            check(files.size < policy.receipts * 3) { "Export receipt folder exceeds its entry limit" }
            check(!Files.isSymbolicLink(path) && Files.isRegularFile(path)) { "Unsafe export receipt entry" }
            val file = path.toFile(); check(file.length() <= policy.receiptBytes)
            bytes += file.length(); check(bytes <= policy.receiptTotalBytes) { "Export receipt storage exceeds its limit" }
            files.add(file)
        } }
        return files
    }
    private fun syncDirectory() {
        val fd = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    fun write(value: ProjectExportReceipt) {
        val file = File(directory(), name(value.id))
        val existing = entries()
        check(existing.count { it.name.endsWith(".json") && it.name != file.name } < policy.receipts) { "Too many retained export receipts" }
        check(value.owned.size <= policy.directoryEntries && value.tree.toString().toByteArray().size <= 16_384)
        check(ProjectExportPackageNames.validReceiptName(2, value.id, value.finalName)) { "Invalid export receipt name" }
        val payload = JSONObject().put("version", 2).put("id", value.id).put("tree", value.tree.toString())
            .put("revision", value.revision).put("finalName", value.finalName).put("phase", value.phase)
            .put("destination", value.destination?.toString() ?: JSONObject.NULL)
            .put("owned", JSONArray().also { array -> value.owned.forEach { entry ->
                array.put(JSONObject().put("parent", document(entry.parent)).put("document", document(entry.document))
                    .put("writtenHash", entry.writtenHash ?: JSONObject.NULL))
            } }).toString()
        val json = JSONObject().put("payload", payload).put("sha256", hash(payload)).toString().toByteArray(Charsets.UTF_8)
        check(json.size <= policy.receiptBytes && existing.sumOf { if (it.name == file.name) 0L else it.length() } + json.size * 2L <= policy.receiptTotalBytes) {
            "Export receipt metadata exceeds its storage budget"
        }
        check(root.usableSpace >= policy.diskReserveBytes + json.size * 2L) { "Not enough disk reserve for the export receipt" }
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(json); output.fd.sync(); atomic.finishWrite(output); syncDirectory() }
        catch (error: Exception) { runCatching { atomic.failWrite(output) }; throw error }
    }
    fun readAll(): List<ProjectExportReceipt> = entries().filter { it.name.endsWith(".json") }.map { file ->
        check(file.length() <= policy.receiptBytes)
        val wrapper = JSONObject(AtomicFile(file).openRead().use { it.reader(Charsets.UTF_8).readText() })
        val payload = wrapper.getString("payload"); check(hash(payload) == wrapper.getString("sha256")) { "Export receipt checksum differs" }
        val json = JSONObject(payload)
        val version = json.getInt("version")
        check(version in 1..2 && name(json.getString("id")) == file.name)
        val array = json.getJSONArray("owned"); check(array.length() <= policy.directoryEntries)
        val owned = (0 until array.length()).map { index -> val entry = array.getJSONObject(index)
            ProjectExportOwned(document(entry.getJSONObject("parent")), document(entry.getJSONObject("document")),
                if (!entry.has("writtenHash") || entry.isNull("writtenHash")) null else entry.getString("writtenHash").also {
                    check(it.matches(Regex("[0-9a-f]{64}"))) }) }
        val id = json.getString("id"); val finalName = json.getString("finalName")
        check(ProjectExportPackageNames.validReceiptName(version, id, finalName))
        val tree = Uri.parse(json.getString("tree")); check(tree.toString().toByteArray().size <= 16_384)
        check(tree.scheme == "content" && tree.query == null && tree.fragment == null && android.provider.DocumentsContract.isTreeUri(tree))
        val phase = json.getString("phase"); check(phase in setOf("CREATING", "WRITING", "PARTIAL", "PUBLISHING", "UNKNOWN", "COMPLETE"))
        fun scoped(doc: ProjectTransportDocument) {
            check(doc.uri.scheme == "content" && doc.uri.query == null && doc.uri.fragment == null &&
                doc.uri.authority == tree.authority && android.provider.DocumentsContract.isTreeUri(doc.uri) &&
                android.provider.DocumentsContract.getTreeDocumentId(doc.uri) == android.provider.DocumentsContract.getTreeDocumentId(tree) &&
                android.provider.DocumentsContract.getDocumentId(doc.uri) == doc.documentId)
        }
        val ids = HashSet<String>(); val aliases = HashSet<String>(); val parents = HashSet<String>()
        for ((index, entry) in owned.withIndex()) {
            val doc = entry.document
            scoped(entry.parent); scoped(doc)
            check(entry.parent.directory && (entry.writtenHash == null || !doc.directory))
            check(doc.uri.authority == tree.authority && android.provider.DocumentsContract.isTreeUri(doc.uri) &&
                android.provider.DocumentsContract.getTreeDocumentId(doc.uri) == android.provider.DocumentsContract.getTreeDocumentId(tree) &&
                android.provider.DocumentsContract.getDocumentId(doc.uri) == doc.documentId && ids.add(doc.documentId) && aliases.add(doc.canonicalIdentity))
            if (index == 0) check(doc.displayName == ".Compositor-$id.partial" && doc.directory &&
                android.provider.DocumentsContract.getDocumentId(entry.parent.uri) == android.provider.DocumentsContract.getTreeDocumentId(tree))
            else {
                check(entry.parent.documentId in parents)
                val knownParent = owned.take(index).single { it.document.documentId == entry.parent.documentId }.document
                check(knownParent.directory && knownParent.uri == entry.parent.uri &&
                    knownParent.canonicalIdentity == entry.parent.canonicalIdentity && knownParent.displayName == entry.parent.displayName &&
                    knownParent.mimeType == entry.parent.mimeType)
            }
            if (doc.directory) parents.add(doc.documentId)
        }
        val destination = if (json.isNull("destination")) null else Uri.parse(json.getString("destination")).also {
            check(it.toString().toByteArray().size <= 16_384)
            scoped(ProjectTransportDocument(it, android.provider.DocumentsContract.getDocumentId(it), "", "", 0, null, null, ""))
        }
        ProjectExportReceipt(id, tree, json.getLong("revision"), finalName, phase, owned, destination)
    }
    fun remove(id: String) { AtomicFile(File(directory(), name(id))).delete(); syncDirectory() }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    /** Explicitly invokes provider cleanup only for interrupted, proven pre-publication work. */
    fun recover(target: ProjectExportTarget): ProjectExportRecovery {
        var removed = 0; var retained = 0; var uncertain = 0
        val records = try { readAll() } catch (_: Exception) { return ProjectExportRecovery(0, 1, 0, true) }
        val exporter = ProjectExporter(target, this, policy)
        for (receipt in records) {
            if (receipt.phase !in setOf("WRITING", "PARTIAL")) { ++uncertain; continue }
            if (exporter.cleanup(receipt.owned) in setOf(ProjectExportCleanup.REMOVED, ProjectExportCleanup.NOT_NEEDED)) {
                try { remove(receipt.id); ++removed } catch (_: Exception) { ++retained }
            } else ++retained
        }
        return ProjectExportRecovery(removed, retained, uncertain, false)
    }
}
