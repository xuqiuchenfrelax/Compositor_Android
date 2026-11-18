package com.pojia.compositor.mobile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

internal enum class ProjectExportCommit { NOT_COMMITTED, COMMITTED_VERIFIED, UNKNOWN }
internal enum class ProjectExportCleanup { NOT_NEEDED, REMOVED, RETAINED, UNKNOWN }
internal data class ProjectExportResult(val capturedRevision: Long, val commit: ProjectExportCommit,
    val cleanup: ProjectExportCleanup, val destination: Uri? = null, val displayName: String = "",
    val cancelled: Boolean = false, val error: String? = null, val receiptId: String? = null) {
    // SAF does not expose a durable directory/remote transaction acknowledgment.
    val durabilityConfirmed: Boolean get() = false
}
internal class ProjectExportCancellation {
    val transport = ProjectTransportCancellation()
    private val gate = Any()
    private var control: NativeProjectWriteControl? = null
    @Volatile var publishing = false; private set
    fun cancel(): Boolean = synchronized(gate) {
        if (publishing) false else { transport.cancel(); control?.cancel(); true }
    }
    fun attach(value: NativeProjectWriteControl) = synchronized(gate) { control = value; transport.check() }
    fun detach() = synchronized(gate) { control = null }
    fun beginPublication() = synchronized(gate) { transport.check(); publishing = true }
}
internal interface ProjectExportOutput : Closeable {
    val stream: OutputStream
    fun sync()
}
/** Creation is not assumed exclusive or atomic; every returned identity must be checked before writing. */
internal interface ProjectExportTarget : ProjectTransportSource {
    fun create(parent: ProjectTransportDocument, name: String, mime: String): ProjectTransportDocument
    fun write(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): ProjectExportOutput
    fun rename(document: ProjectTransportDocument, name: String): ProjectTransportDocument
    fun delete(document: ProjectTransportDocument)
}
internal class DocumentsProjectExportTarget(private val resolver: ContentResolver,
    policy: ProjectTransportPolicy = ProjectTransportPolicy()) : ProjectExportTarget {
    private val source = DocumentsProjectTransportSource(resolver, policy)
    override fun root(treeUri: Uri, cancellation: ProjectTransportCancellation) = source.root(treeUri, cancellation)
    override fun stat(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation) = source.stat(document, cancellation)
    override fun children(parent: ProjectTransportDocument, cancellation: ProjectTransportCancellation,
        visit: (ProjectTransportDocument) -> Unit) = source.children(parent, cancellation, visit)
    override fun open(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation) = source.open(document, cancellation)
    private fun returned(parent: ProjectTransportDocument, uri: Uri?): ProjectTransportDocument {
        check(uri != null && uri.scheme == "content" && uri.authority == parent.uri.authority &&
            uri.query == null && uri.fragment == null) {
            "Provider returned an invalid created/renamed document URI"
        }
        val scoped = DocumentsContract.buildDocumentUriUsingTree(parent.uri, DocumentsContract.getDocumentId(uri))
        return stat(ProjectTransportDocument(scoped, DocumentsContract.getDocumentId(scoped), "", "", 0,
            null, null, scoped.toString()), ProjectTransportCancellation())
    }
    override fun create(parent: ProjectTransportDocument, name: String, mime: String) =
        returned(parent, DocumentsContract.createDocument(resolver, parent.uri, mime, name))
    override fun rename(document: ProjectTransportDocument, name: String) =
        returned(document, DocumentsContract.renameDocument(resolver, document.uri, name))
    override fun delete(document: ProjectTransportDocument) {
        check(DocumentsContract.deleteDocument(resolver, document.uri)) { "Provider did not remove the export document" }
    }
    override fun write(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): ProjectExportOutput {
        cancellation.check()
        check(!document.directory && document.size == 0L && document.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) {
            "Provider export requires a newly created empty regular file"
        }
        val descriptor = checkNotNull(resolver.openFileDescriptor(document.uri, "w", cancellation.signal))
        try {
            val value = Os.fstat(descriptor.fileDescriptor)
            check(OsConstants.S_ISREG(value.st_mode) && value.st_size == 0L) { "Provider export requires a finite empty regular file descriptor" }
            return object : ProjectExportOutput {
                override val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                override fun sync() { stream.flush(); Os.fsync(descriptor.fileDescriptor) }
                override fun close() = stream.close()
            }
        } catch (error: Exception) { descriptor.close(); throw error }
    }
}
internal data class ProjectExportPolicy(val directoryEntries: Int = 20_032, val metadataBytes: Long = 16L * 1024 * 1024,
    val encodedBytes: Long = 512L * 1024 * 1024, val chunkBytes: Int = 64 * 1024,
    val receiptBytes: Int = 4 * 1024 * 1024, val receipts: Int = 32,
    val receiptTotalBytes: Long = 32L * 1024 * 1024, val diskReserveBytes: Long = 256L * 1024 * 1024)
internal data class ProjectExportOwned(val parent: ProjectTransportDocument, var document: ProjectTransportDocument,
    var writtenHash: String? = null)
internal data class ProjectExportAsset(val path: String, val file: File, val bytes: Long, val hash: String)

/** Native writer output only: no ZIP, Bitmap flattening, external overwrite, or baseline mutation. */
internal class ProjectExporter(private val target: ProjectExportTarget, private val receipts: ProjectExportReceipts,
    private val policy: ProjectExportPolicy = ProjectExportPolicy()) {
    private fun listing(parent: ProjectTransportDocument, cancel: ProjectTransportCancellation): List<ProjectTransportDocument> {
        val entries = ArrayList<ProjectTransportDocument>(); var bytes = 0L
        val ids = HashSet<String>(); val aliases = HashSet<String>(); val names = HashSet<String>()
        target.children(parent, cancel) { item ->
            check(entries.size < policy.directoryEntries) { "Export provider directory exceeds its entry limit" }
            check(item.uri.authority == parent.uri.authority && DocumentsContract.isTreeUri(item.uri) &&
                DocumentsContract.getTreeDocumentId(item.uri) == DocumentsContract.getTreeDocumentId(parent.uri) &&
                DocumentsContract.getDocumentId(item.uri) == item.documentId) { "Export document escaped the selected tree" }
            check(item.displayName.isNotEmpty() && item.displayName !in setOf(".", "..") &&
                item.displayName.none { it == '/' || it == '\\' || it == '\u0000' } &&
                item.displayName.toByteArray().size <= 255) { "Provider returned an unsafe export name" }
            val fields = item.fields()
            check(fields.all { it.toByteArray().size <= 16_384 }) { "Export provider metadata field exceeds its limit" }
            bytes += fields.sumOf { it.toByteArray().size.toLong() }
            check(bytes <= policy.metadataBytes && ids.add(item.documentId) && aliases.add(item.canonicalIdentity) &&
                names.add(item.displayName)) { "Export provider directory contains duplicate or excessive metadata" }
            check(target.stat(item, cancel) == item) { "Export provider listing changed" }
            entries.add(item)
        }
        return entries
    }
    private fun identity(before: ProjectTransportDocument, after: ProjectTransportDocument) =
        before.uri == after.uri && before.documentId == after.documentId && before.canonicalIdentity == after.canonicalIdentity &&
            before.displayName == after.displayName && before.mimeType == after.mimeType
    private fun observed(parent: ProjectTransportDocument, child: ProjectTransportDocument, cancel: ProjectTransportCancellation) =
        listing(parent, cancel).singleOrNull { it.documentId == child.documentId }?.let { identity(child, it) } == true
    private fun hash(document: ProjectTransportDocument, expected: ProjectExportAsset, cancel: ProjectTransportCancellation) {
        cancel.check()
        check(!document.directory && document.size == expected.bytes &&
            document.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) { "Provider export length/type changed" }
        val before = target.stat(document, cancel)
        check(before == document) { "Provider export metadata changed before readback" }
        val digest = MessageDigest.getInstance("SHA-256"); var remaining = expected.bytes
        val input = target.open(document, cancel)
        try {
            cancel.register(input)
            input.use {
                val buffer = ByteArray(policy.chunkBytes)
                while (remaining > 0) {
                    cancel.check(); val count = it.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    check(count > 0) { "Provider export readback was short" }
                    digest.update(buffer, 0, count); remaining -= count
                }
                check(it.read() == -1 && digest.digest().joinToString("") { value -> "%02x".format(value.toInt() and 255) } == expected.hash) {
                    "Provider export readback bytes differ"
                }
            }
        } finally { cancel.unregister(input); runCatching { input.close() } }
        cancel.check(); check(target.stat(document, cancel) == before) { "Provider export changed during readback" }
    }
    private fun assets(directory: File): List<ProjectExportAsset> {
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val roots = directory.listFiles()?.toList() ?: error("Export staging is unavailable")
        check(roots.size == 2 && roots.map { it.name }.toSet() == setOf("manifest.json", "images")) { "Unexpected native export package entries" }
        val images = File(directory, "images")
        check(images.isDirectory && !Files.isSymbolicLink(images.toPath()))
        val files = ArrayList<File>()
        Files.newDirectoryStream(images.toPath()).use { stream -> for (path in stream) {
            check(files.size < policy.directoryEntries - 2) { "Export package contains too many assets" }; files.add(path.toFile())
        } }
        files.sortBy { it.name }; files.add(File(directory, "manifest.json"))
        var total = 0L
        return files.map { file ->
            check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Export asset is not a regular private file" }
            val size = file.length(); check(size >= 0 && size <= policy.encodedBytes - total) { "Encoded export exceeds its job limit" }; total += size
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(policy.chunkBytes); while (true) {
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            ProjectExportAsset(file.relativeTo(directory).invariantSeparatorsPath, file, size,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }
    private fun verify(root: ProjectTransportDocument, expected: List<ProjectExportAsset>, cancel: ProjectTransportCancellation) {
        val roots = listing(root, cancel)
        check(roots.map { it.displayName }.toSet() == setOf("manifest.json", "images") && roots.size == 2) { "Export package root changed" }
        val images = roots.single { it.displayName == "images" }; check(images.directory)
        val children = listing(images, cancel)
        val imageAssets = expected.filter { it.path.startsWith("images/") }
        check(children.size == imageAssets.size && children.map { it.displayName }.toSet() == imageAssets.map { it.file.name }.toSet()) {
            "Export package asset inventory changed"
        }
        expected.forEach { asset -> hash(if (asset.path == "manifest.json") roots.single { it.displayName == "manifest.json" }
            else children.single { it.displayName == asset.file.name }, asset, cancel) }
    }
    /** SAF cannot compare-and-delete atomically. Never delete directories, whose provider delete may recurse. */
    fun cleanup(owned: List<ProjectExportOwned>): ProjectExportCleanup {
        if (owned.isEmpty()) return ProjectExportCleanup.NOT_NEEDED
        val cancel = ProjectTransportCancellation(); var retained = false
        for (entry in owned.asReversed()) try {
            val current = listing(entry.parent, cancel).singleOrNull { it.documentId == entry.document.documentId } ?: continue
            if (current.directory || current != entry.document) { retained = true; continue }
            if (entry.writtenHash != null) {
                val expected = ProjectExportAsset("", File(""), checkNotNull(current.size), checkNotNull(entry.writtenHash))
                hash(current, expected, cancel)
            } else if (current.size != 0L) { retained = true; continue }
            target.delete(current)
            if (listing(entry.parent, cancel).any { it.documentId == current.documentId || it.canonicalIdentity == current.canonicalIdentity }) retained = true
        } catch (_: Exception) { retained = true }
        return if (retained) ProjectExportCleanup.RETAINED else ProjectExportCleanup.REMOVED
    }
    fun publish(directory: File, tree: Uri, revision: Long, cancellation: ProjectExportCancellation,
        suggestedName: String? = null, progress: (String, Boolean) -> Unit = { _, _ -> }): ProjectExportResult {
        val cancel = cancellation.transport; val id = UUID.randomUUID().toString()
        val finalName = ProjectExportPackageNames.finalName(id, suggestedName); val partialName = ".Compositor-$id.partial"
        val owned = ArrayList<ProjectExportOwned>(); var receipt: ProjectExportReceipt? = null
        var attemptedCreate = false; var published: ProjectTransportDocument? = null; var commitVerified = false
        try {
            cancel.check(); check(tree.scheme == "content" && DocumentsContract.isTreeUri(tree) && tree.query == null && tree.fragment == null)
            val root = target.root(tree, cancel); check(root.directory && root.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                "Selected export folder cannot create documents"
            }
            check(root.uri.scheme == "content" && root.uri.authority == tree.authority && DocumentsContract.isTreeUri(root.uri) &&
                DocumentsContract.getTreeDocumentId(root.uri) == DocumentsContract.getTreeDocumentId(tree) &&
                DocumentsContract.getDocumentId(root.uri) == root.documentId) { "Export parent escaped its selected tree" }
            val expected = assets(directory)
            receipt = ProjectExportReceipt(id, tree, revision, finalName, "WRITING", owned)
            receipts.write(receipt)
            fun create(parent: ProjectTransportDocument, name: String, mime: String): ProjectTransportDocument {
                cancel.check(); val before = listing(parent, cancel)
                check(before.none { it.displayName.equals(name, ignoreCase = true) }) { "Export name conflicts with an existing document" }
                receipt!!.phase = "CREATING"; receipts.write(receipt!!); attemptedCreate = true
                val created = target.create(parent, name, mime)
                check(created.documentId != parent.documentId && created.canonicalIdentity != parent.canonicalIdentity &&
                    before.none { it.documentId == created.documentId || it.canonicalIdentity == created.canonicalIdentity } &&
                    owned.none { it.document.documentId == created.documentId || it.document.canonicalIdentity == created.canonicalIdentity } &&
                    created.displayName == name && created.mimeType == mime && observed(parent, created, cancel)) {
                    "Provider creation did not prove a new document identity; nothing will be written or deleted through it"
                }
                owned.add(ProjectExportOwned(parent, created)); receipt!!.phase = "WRITING"; receipts.write(receipt!!)
                attemptedCreate = false
                check(created.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) { "Provider cannot clean up newly created export documents" }
                return created
            }
            val partial = create(root, partialName, DocumentsContract.Document.MIME_TYPE_DIR)
            check(partial.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0 &&
                partial.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) { "Provider cannot create and publish a new project folder" }
            val images = create(partial, "images", DocumentsContract.Document.MIME_TYPE_DIR)
            for ((index, asset) in expected.withIndex()) {
                progress("Exporting project: ${index + 1} / ${expected.size} files", true)
                val parent = if (asset.path == "manifest.json") partial else images
                val document = create(parent, asset.file.name, if (asset.path == "manifest.json") "application/json" else "image/png")
                check(document.size == 0L && document.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0) { "Provider did not create an empty writable export file" }
                val output = target.write(document, cancel)
                try {
                    cancel.register(output)
                    output.use { handle -> asset.file.inputStream().use { input ->
                        val buffer = ByteArray(policy.chunkBytes)
                        while (true) { cancel.check(); val count = input.read(buffer); if (count < 0) break; handle.stream.write(buffer, 0, count) }
                        cancel.check(); handle.sync()
                    } }
                } finally { cancel.unregister(output); runCatching { output.close() } }
                val written = target.stat(document, cancel)
                val ownership = owned.single { it.document.documentId == document.documentId }
                check(identity(document, written)) { "Provider file identity changed after writing" }
                ownership.document = written; ownership.writtenHash = asset.hash; receipts.write(receipt)
                hash(written, asset, cancel)
            }
            progress("Checking exported project…", true); verify(partial, expected, cancel)
            check(listing(root, cancel).none { it.displayName.equals(finalName, ignoreCase = true) }) { "Export destination name now conflicts" }
            check(observed(root, partial, cancel)) { "Export folder identity changed before publication" }
            cancellation.beginPublication(); progress("Publishing project copy…", false)
            receipt.phase = "PUBLISHING"; receipts.write(receipt)
            val inventory = listing(root, ProjectTransportCancellation())
            published = target.rename(partial, finalName)
            check(published.displayName == finalName && published.directory &&
                inventory.none { it.documentId != partial.documentId && (it.documentId == published.documentId || it.canonicalIdentity == published.canonicalIdentity) } &&
                observed(root, published, ProjectTransportCancellation())) { "Provider returned an unexpected published project identity" }
            verify(published, expected, ProjectTransportCancellation())
            commitVerified = true
            receipt.phase = "COMPLETE"; receipt.destination = published.uri
            receipts.write(receipt)
            return ProjectExportResult(revision, ProjectExportCommit.COMMITTED_VERIFIED, ProjectExportCleanup.NOT_NEEDED,
                published.uri, finalName, receiptId = id)
        } catch (error: Exception) {
            val cancelled = runCatching { cancel.check(); false }.getOrDefault(true)
            val commit = if (commitVerified) ProjectExportCommit.COMMITTED_VERIFIED else if (cancellation.publishing)
                ProjectExportCommit.UNKNOWN else ProjectExportCommit.NOT_COMMITTED
            val cleanup = if (commitVerified) ProjectExportCleanup.NOT_NEEDED else if (commit == ProjectExportCommit.UNKNOWN)
                ProjectExportCleanup.UNKNOWN else cleanup(owned)
            receipt?.let { value ->
                value.phase = if (commitVerified) "COMPLETE" else if (commit == ProjectExportCommit.UNKNOWN || attemptedCreate) "UNKNOWN" else "PARTIAL"
                value.destination = published?.uri
                runCatching { receipts.write(value) }
                if (!attemptedCreate && commit == ProjectExportCommit.NOT_COMMITTED && cleanup in setOf(ProjectExportCleanup.REMOVED, ProjectExportCleanup.NOT_NEEDED))
                    runCatching { receipts.remove(value.id) }
            }
            return ProjectExportResult(revision, commit, if (attemptedCreate && cleanup == ProjectExportCleanup.NOT_NEEDED)
                ProjectExportCleanup.UNKNOWN else cleanup, published?.uri, finalName, cancelled, error.message, receipt?.id)
        }
    }
    companion object {
        fun forContext(context: Context) = ProjectExporter(DocumentsProjectExportTarget(context.contentResolver), ProjectExportReceipts(context))
    }
}
