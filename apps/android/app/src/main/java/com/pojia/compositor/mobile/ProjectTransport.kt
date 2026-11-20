package com.pojia.compositor.mobile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Bounds apply to encoded transport, independently of the native decoded-pixel reservation. */
internal data class ProjectTransportPolicy(
    val manifestBytes: Long = 4L * 1024 * 1024,
    val assetBytes: Long = 512L * 1024 * 1024,
    val jobBytes: Long = 512L * 1024 * 1024,
    val rootEntries: Int = 32,
    val imageEntries: Int = 20_000,
    val totalEntries: Int = 20_032,
    val layers: Int = 10_000,
    val references: Int = 20_000,
    val jsonDepth: Int = 64,
    val nameBytes: Int = 16_384,
    val fileNameBytes: Int = 255,
    val providerTextBytes: Int = 16_384,
    val observedMetadataBytes: Long = 16L * 1024 * 1024,
    val diskReserveBytes: Long = 256L * 1024 * 1024,
    val copyChunkBytes: Int = 64 * 1024,
) {
    init {
        require(manifestBytes in 1..Int.MAX_VALUE.toLong() && assetBytes > 0 && jobBytes >= manifestBytes)
        require(rootEntries > 0 && imageEntries > 0 && totalEntries > 0 && layers > 0 && references > 0)
        require(jsonDepth > 0 && nameBytes > 0 && fileNameBytes > 0 && providerTextBytes > 0 &&
            observedMetadataBytes > 0 && diskReserveBytes >= 0 && copyChunkBytes > 0)
    }
}

internal enum class ProjectTransportFailure { INVALID, UNSUPPORTED, LIMIT, IO, CHANGED, CANCELLED, DISK }
internal class ProjectTransportException(val failure: ProjectTransportFailure, message: String,
    cause: Throwable? = null) : IllegalStateException(message, cause)

internal class ProjectTransportCancellation {
    private val cancelled = AtomicBoolean(false)
    val signal = CancellationSignal()
    private val lock = Any()
    private var active: Closeable? = null
    fun cancel() {
        cancelled.set(true)
        signal.cancel()
        synchronized(lock) { runCatching { active?.close() } }
    }
    fun check() {
        if (cancelled.get() || signal.isCanceled) throw ProjectTransportException(
            ProjectTransportFailure.CANCELLED, "Project import was cancelled")
    }
    internal fun register(stream: Closeable) {
        synchronized(lock) {
            if (cancelled.get() || signal.isCanceled) { stream.close(); check() }
            check(active == null) { "One transport cancellation must belong to one import job" }
            active = stream
        }
    }
    internal fun unregister(stream: Closeable) { synchronized(lock) { if (active === stream) active = null } }
}

internal data class ProjectTransportDocument(
    val uri: Uri,
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val flags: Int,
    val size: Long?,
    val modified: Long?,
    val canonicalIdentity: String,
) {
    val directory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    internal fun fields(): List<String> = listOf(uri.toString(), documentId, displayName, mimeType,
        flags.toString(), size?.toString() ?: "?", modified?.toString() ?: "?", canonicalIdentity)
}

/** Implementations enumerate through a callback so a hostile cursor cannot allocate an unbounded list. */
internal interface ProjectTransportSource {
    fun root(treeUri: Uri, cancellation: ProjectTransportCancellation): ProjectTransportDocument
    fun stat(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): ProjectTransportDocument
    fun children(parent: ProjectTransportDocument, cancellation: ProjectTransportCancellation,
        visit: (ProjectTransportDocument) -> Unit)
    fun open(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): InputStream
}

internal data class ProjectTransportProgress(val phase: String, val bytesCopied: Long,
    val declaredBytes: Long, val assetsCopied: Int, val assetCount: Int)

/** The caller closes staging after native validation/loading, including an unsuccessful candidate. */
internal class StagedProject internal constructor(
    val directory: File,
    val sourceUri: Uri,
    val displayName: String,
    val fingerprint: String,
    val sourceIdentity: String,
    private val stagingLease: ProjectStagingLease,
) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) stagingLease.close() }
}

internal class ProjectTransport(
    private val source: ProjectTransportSource,
    private val stagingRoot: File,
    private val policy: ProjectTransportPolicy = ProjectTransportPolicy(),
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    private val storage = ProjectStagingStorage(stagingRoot)
    companion object {
        fun forContext(context: Context, policy: ProjectTransportPolicy = ProjectTransportPolicy()): ProjectTransport {
            val root = NativeProjectIO.ownedPath(context, File(context.filesDir, "project-staging"))
            return ProjectTransport(DocumentsProjectTransportSource(context.contentResolver, policy), root, policy)
        }
    }

    /** Optional cold-start worker hook; stage also sweeps before allocating its own leased directory. */
    fun reclaimOrphans(cancellation: ProjectTransportCancellation = ProjectTransportCancellation()): ProjectStagingReclaim =
        storage.reclaimOrphans(cancellation)

    /** Synchronous worker-thread operation. No provider writes, permissions changes, or editor mutations. */
    fun stage(treeUri: Uri, cancellation: ProjectTransportCancellation,
        progress: (ProjectTransportProgress) -> Unit = {}): StagedProject {
        cancellation.check()
        if (treeUri.scheme != "content" || treeUri.authority.isNullOrEmpty() || treeUri.query != null ||
            treeUri.fragment != null || !DocumentsContract.isTreeUri(treeUri))
            fail(ProjectTransportFailure.INVALID, "A SAF document tree is required")
        if (treeUri.toString().toByteArray(Charsets.UTF_8).size > policy.providerTextBytes)
            fail(ProjectTransportFailure.LIMIT, "Selected tree URI exceeds the ${policy.providerTextBytes} byte limit")
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        fun scoped(document: ProjectTransportDocument) {
            if (document.uri.authority != treeUri.authority || !DocumentsContract.isTreeUri(document.uri) ||
                DocumentsContract.getTreeDocumentId(document.uri) != treeId ||
                DocumentsContract.getDocumentId(document.uri) != document.documentId)
                fail(ProjectTransportFailure.INVALID, "Provider document escaped its selected tree URI scope")
        }
        storage.reclaimOrphans(cancellation)
        val stagingLease = try { storage.create() }
            catch (error: Exception) { throw ProjectTransportException(ProjectTransportFailure.IO, "Cannot create app-owned project staging", error) }
        val staging = stagingLease.directory
        var completed = false
        try {
            val packageRoot = source.root(treeUri, cancellation)
            scoped(packageRoot)
            validateName(packageRoot.displayName)
            if (!packageRoot.directory || !packageRoot.displayName.endsWith(".comp"))
                fail(ProjectTransportFailure.INVALID, "Select a .comp project directory")
            val identities = HashSet<String>()
            val aliases = HashSet<String>()
            registerIdentity(packageRoot, identities, aliases)
            var entries = 0
            var observedBytes = packageRoot.fields().sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
            if (observedBytes > policy.observedMetadataBytes) fail(ProjectTransportFailure.LIMIT,
                "Provider metadata exceeds the ${policy.observedMetadataBytes} byte limit")
            fun list(parent: ProjectTransportDocument, limit: Int, record: Boolean): List<ProjectTransportDocument> {
                val result = ArrayList<ProjectTransportDocument>()
                val names = HashSet<String>()
                val documentIds = HashSet<String>()
                val canonicalIds = HashSet<String>()
                var listingBytes = 0L
                source.children(parent, cancellation) { document ->
                    cancellation.check()
                    scoped(document)
                    if (result.size >= limit) fail(ProjectTransportFailure.LIMIT,
                        "${parent.displayName} exceeds the $limit entry limit")
                    if (record && ++entries > policy.totalEntries) fail(ProjectTransportFailure.LIMIT,
                        "Project exceeds the ${policy.totalEntries} total entry limit")
                    validateName(document.displayName)
                    validateMetadata(document)
                    val metadataBytes = document.fields().sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
                    listingBytes += metadataBytes
                    if (listingBytes > policy.observedMetadataBytes) fail(ProjectTransportFailure.LIMIT,
                        "Provider directory metadata exceeds the ${policy.observedMetadataBytes} byte limit")
                    if (!names.add(document.displayName)) fail(ProjectTransportFailure.INVALID,
                        "Duplicate document filename: ${document.displayName}")
                    if (!documentIds.add(document.documentId) || !canonicalIds.add(document.canonicalIdentity))
                        fail(ProjectTransportFailure.INVALID, "Duplicate provider document identity")
                    if (record) {
                        registerIdentity(document, identities, aliases)
                        observedBytes += metadataBytes
                        if (observedBytes > policy.observedMetadataBytes) fail(ProjectTransportFailure.LIMIT,
                            "Provider metadata exceeds the ${policy.observedMetadataBytes} byte limit")
                    }
                    result.add(document)
                }
                return result
            }
            val rootListing = list(packageRoot, policy.rootEntries, true)
            val manifest = rootListing.singleOrNull { it.displayName == "manifest.json" }
                ?: fail(ProjectTransportFailure.INVALID, "Project manifest.json is missing")
            regularFile(manifest, policy.manifestBytes, "Manifest", setOf("application/json", "text/json", "application/octet-stream"))
            var copied = 0L
            var total = manifest.size!!
            var assetCount = 0
            var assetsCopied = 0
            fun emit(phase: String) {
                cancellation.check()
                progress(ProjectTransportProgress(phase, copied, total, assetsCopied, assetCount))
                cancellation.check()
            }
            fun reserve(remaining: Long) {
                cancellation.check()
                val free = availableBytes(staging)
                if (free < policy.diskReserveBytes || remaining > free - policy.diskReserveBytes)
                    fail(ProjectTransportFailure.DISK,
                        "Project needs $remaining bytes plus ${policy.diskReserveBytes} bytes of free disk reserve")
            }
            reserve(total)
            val manifestFile = File(staging, "manifest.json")
            val hashes = linkedMapOf<String, String>()
            emit("manifest")
            hashes[manifest.displayName] = copyExact(manifest, manifestFile, cancellation) { count ->
                copied += count; reserve(total - copied); emit("manifest")
            }
            val references = ManifestReferences(policy, cancellation).read(manifestFile.readBytes())
            assetCount = references.size
            val images = rootListing.singleOrNull { it.displayName == "images" }
            if (images != null && !images.directory) fail(ProjectTransportFailure.INVALID, "Project images must be a directory")
            if (references.isNotEmpty() && images == null) fail(ProjectTransportFailure.INVALID, "Project images directory is missing")
            // Only the root and images directories are listed. QuickLook and other subtrees are never opened.
            val imageListing = if (images != null) list(images, policy.imageEntries, true) else emptyList()
            val byName = imageListing.associateBy { it.displayName }
            val assets = references.map { name ->
                val document = byName[name] ?: fail(ProjectTransportFailure.INVALID, "Referenced asset is missing: $name")
                regularFile(document, policy.assetBytes, "Asset $name", setOf("image/png", "application/octet-stream"))
                if (document.size!! > policy.jobBytes - total) fail(ProjectTransportFailure.LIMIT,
                    "Encoded project exceeds the ${policy.jobBytes} byte job limit")
                total += document.size
                document
            }
            if (total > policy.jobBytes) fail(ProjectTransportFailure.LIMIT,
                "Encoded project exceeds the ${policy.jobBytes} byte job limit")
            reserve(total - copied)
            if (assets.isNotEmpty() && !File(staging, "images").mkdir())
                fail(ProjectTransportFailure.IO, "Cannot create staged images directory")
            assets.forEach { document ->
                emit("assets")
                hashes[document.displayName] = copyExact(document, File(staging, "images/${document.displayName}"), cancellation) { count ->
                    copied += count; reserve(total - copied); emit("assets")
                }
                ++assetsCopied
            }
            emit("verify")
            fun sameListing(before: List<ProjectTransportDocument>, after: List<ProjectTransportDocument>): Boolean =
                before.sortedBy { it.documentId } == after.sortedBy { it.documentId }
            if (source.stat(packageRoot, cancellation) != packageRoot ||
                !sameListing(rootListing, list(packageRoot, policy.rootEntries, false)) ||
                (images != null && !sameListing(imageListing, list(images, policy.imageEntries, false))))
                fail(ProjectTransportFailure.CHANGED, "Project directory changed during import")
            // Even providers without a useful mtime can be checked against the bytes we observed.
            (listOf(manifest) + assets).forEach { document ->
                if (copyExact(document, null, cancellation) {} != hashes.getValue(document.displayName))
                    fail(ProjectTransportFailure.CHANGED, "Project asset changed during import: ${document.displayName}")
            }
            cancellation.check()
            val observationHash = MessageDigest.getInstance("SHA-256")
            fun observe(value: String) {
                cancellation.check()
                val bytes = value.toByteArray(Charsets.UTF_8)
                observationHash.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
                observationHash.update(bytes)
            }
            (listOf(packageRoot) + rootListing + imageListing).sortedBy { it.documentId }
                .forEach { document -> document.fields().forEach { observe(it) } }
            hashes.toSortedMap().forEach { (name, hash) -> observe(name); observe(hash) }
            val fingerprint = observationHash.digest().transportHex()
            // Fixed length provenance fits the workspace identity bound. It is not a provider transaction token.
            val identity = "saf:${transportHash(treeUri.toString())}:$fingerprint"
            emit("complete")
            val result = StagedProject(staging, treeUri, packageRoot.displayName, fingerprint, identity, stagingLease)
            completed = true
            return result
        } catch (error: Exception) {
            cancellation.check()
            if (error is ProjectTransportException) throw error
            throw ProjectTransportException(ProjectTransportFailure.IO, "Project provider read failed", error)
        } finally {
            if (!completed) stagingLease.close()
        }
    }

    private fun validateName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' } ||
            !validUnicode(name) || name.toByteArray(Charsets.UTF_8).size > policy.fileNameBytes)
            fail(ProjectTransportFailure.INVALID, "Provider returned an unsafe or oversized document filename")
    }

    private fun registerIdentity(document: ProjectTransportDocument, ids: MutableSet<String>, aliases: MutableSet<String>) {
        validateMetadata(document)
        if (document.uri.scheme != "content" || document.uri.authority.isNullOrEmpty() ||
            document.uri.query != null || document.uri.fragment != null || document.documentId.isEmpty() ||
            document.canonicalIdentity.isEmpty()) fail(ProjectTransportFailure.INVALID, "Invalid provider document identity")
        if (!ids.add("${document.uri.authority}:${document.documentId}") || !aliases.add(document.canonicalIdentity))
            fail(ProjectTransportFailure.INVALID, "Duplicate document ID or provider URI alias")
    }

    private fun validateMetadata(document: ProjectTransportDocument) {
        if (document.fields().any { !validUnicode(it) || it.toByteArray(Charsets.UTF_8).size > policy.providerTextBytes })
            fail(ProjectTransportFailure.LIMIT, "Provider metadata field exceeds the ${policy.providerTextBytes} byte limit")
        val canonical = Uri.parse(document.canonicalIdentity)
        if (canonical.scheme != "content" || canonical.authority != document.uri.authority || canonical.query != null ||
            canonical.fragment != null || document.flags < 0)
            fail(ProjectTransportFailure.INVALID, "Provider returned an invalid canonical identity or flags")
    }

    private fun regularFile(document: ProjectTransportDocument, maximum: Long, label: String, mimes: Set<String>) {
        if (document.directory || document.mimeType !in mimes ||
            document.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0)
            fail(ProjectTransportFailure.UNSUPPORTED, "$label must be a regular readable file of its supported type")
        val size = document.size ?: fail(ProjectTransportFailure.UNSUPPORTED, "$label has an unknown encoded size")
        if (size < 0) fail(ProjectTransportFailure.UNSUPPORTED, "$label has an unknown encoded size")
        if (size > maximum) fail(ProjectTransportFailure.LIMIT, "$label exceeds the $maximum encoded byte limit")
    }

    private fun copyExact(document: ProjectTransportDocument, destination: File?, cancellation: ProjectTransportCancellation,
        onBytes: (Long) -> Unit): String {
        cancellation.check()
        if (source.stat(document, cancellation) != document)
            fail(ProjectTransportFailure.CHANGED, "Provider metadata changed: ${document.displayName}")
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = source.open(document, cancellation)
        try {
            cancellation.register(stream)
            stream.use { input ->
                val output = destination?.let { FileOutputStream(it) }
                try {
                    var remaining = document.size!!
                    val buffer = ByteArray(policy.copyChunkBytes)
                    while (remaining > 0) {
                        cancellation.check()
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count <= 0) fail(ProjectTransportFailure.CHANGED, "Short provider read: ${document.displayName}")
                        cancellation.check()
                        output?.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count
                        onBytes(count.toLong())
                    }
                    cancellation.check()
                    if (input.read() != -1) fail(ProjectTransportFailure.CHANGED,
                        "Provider file grew during import: ${document.displayName}")
                    output?.fd?.sync()
                } finally { output?.close() }
            }
        } finally { cancellation.unregister(stream); runCatching { stream.close() } }
        cancellation.check()
        if (source.stat(document, cancellation) != document)
            fail(ProjectTransportFailure.CHANGED, "Provider metadata changed: ${document.displayName}")
        return digest.digest().transportHex()
    }
}

/** Real SAF adapter: finite regular file descriptors only; pipe/virtual/cloud streams are unsupported. */
internal class DocumentsProjectTransportSource(private val resolver: ContentResolver,
    private val policy: ProjectTransportPolicy = ProjectTransportPolicy()) : ProjectTransportSource {
    private val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED)

    override fun root(treeUri: Uri, cancellation: ProjectTransportCancellation): ProjectTransportDocument {
        cancellation.check()
        if (!DocumentsContract.isTreeUri(treeUri)) fail(ProjectTransportFailure.INVALID, "A SAF document tree is required")
        basicUri(treeUri, treeUri.authority)
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return queryOne(uri, cancellation)
    }

    override fun stat(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): ProjectTransportDocument =
        queryOne(document.uri, cancellation)

    override fun children(parent: ProjectTransportDocument, cancellation: ProjectTransportCancellation,
        visit: (ProjectTransportDocument) -> Unit) {
        cancellation.check()
        checkedDocumentUri(parent.uri)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parent.documentId)
        val cursor = resolver.query(uri, projection, null, cancellation.signal)
            ?: fail(ProjectTransportFailure.IO, "Provider returned no directory listing")
        cursor.use {
            while (it.moveToNext()) {
                cancellation.check()
                val id = requiredString(it, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                // IDs are opaque provider identifiers, including legitimate IDs containing slashes.
                val childUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, id)
                checkedDocumentUri(childUri)
                if (childUri.authority != parent.uri.authority) fail(ProjectTransportFailure.INVALID, "Provider authority changed")
                val document = fromCursor(it, childUri)
                val childEvidence = try { DocumentsContract.isChildDocument(resolver, parent.uri, childUri) }
                    catch (_: UnsupportedOperationException) { null }
                if (childEvidence == false) fail(ProjectTransportFailure.INVALID, "Provider returned a document outside its selected directory")
                // When isChildDocument is unsupported, the parent-scoped listing and exact stat are the available evidence.
                if (queryOne(childUri, cancellation) != document)
                    fail(ProjectTransportFailure.CHANGED, "Provider listing and document metadata disagree")
                visit(document)
            }
        }
    }

    override fun open(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): InputStream {
        cancellation.check()
        checkedDocumentUri(document.uri)
        val descriptor = resolver.openFileDescriptor(document.uri, "r", cancellation.signal)
            ?: fail(ProjectTransportFailure.IO, "Provider returned no readable file")
        try {
            val stat = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode)) fail(ProjectTransportFailure.UNSUPPORTED,
                "Pipe and nonregular document providers are not supported by this import version")
            if (document.size == null || stat.st_size != document.size) fail(ProjectTransportFailure.CHANGED,
                "Provider descriptor size disagrees with document metadata")
            cancellation.check()
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        } catch (error: Throwable) { descriptor.close(); throw error }
    }

    private fun queryOne(uri: Uri, cancellation: ProjectTransportCancellation): ProjectTransportDocument {
        cancellation.check()
        checkedDocumentUri(uri)
        val cursor = resolver.query(uri, projection, null, cancellation.signal)
            ?: fail(ProjectTransportFailure.IO, "Provider document is missing")
        return cursor.use {
            if (!it.moveToFirst()) fail(ProjectTransportFailure.IO, "Provider document is missing")
            val result = fromCursor(it, uri)
            if (it.moveToNext()) fail(ProjectTransportFailure.INVALID, "Provider returned duplicate document rows")
            result
        }
    }

    private fun fromCursor(cursor: android.database.Cursor, uri: Uri): ProjectTransportDocument {
        val id = requiredString(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        if (id != DocumentsContract.getDocumentId(uri)) fail(ProjectTransportFailure.INVALID, "Provider document ID disagrees with URI")
        val canonical = resolver.canonicalize(uri) ?: uri
        basicUri(canonical, uri.authority)
        fun number(column: String): Long? = cursor.getColumnIndex(column).let { index ->
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
        val flags = number(DocumentsContract.Document.COLUMN_FLAGS)
            ?: fail(ProjectTransportFailure.INVALID, "Provider omitted document flags")
        if (flags !in 0..Int.MAX_VALUE.toLong()) fail(ProjectTransportFailure.INVALID, "Invalid provider document flags")
        return ProjectTransportDocument(uri, id, requiredString(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            requiredString(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE), flags.toInt(),
            number(DocumentsContract.Document.COLUMN_SIZE), number(DocumentsContract.Document.COLUMN_LAST_MODIFIED), canonical.toString())
    }

    private fun requiredString(cursor: android.database.Cursor, column: String): String {
        val index = cursor.getColumnIndex(column)
        if (index < 0 || cursor.isNull(index)) fail(ProjectTransportFailure.INVALID, "Provider omitted $column")
        return cursor.getString(index).also {
            if (!validUnicode(it) || it.toByteArray(Charsets.UTF_8).size > policy.providerTextBytes)
                fail(ProjectTransportFailure.LIMIT, "Provider $column exceeds the ${policy.providerTextBytes} byte limit")
        }
    }
    private fun checkedDocumentUri(uri: Uri) {
        basicUri(uri, uri.authority)
        if (!DocumentsContract.isTreeUri(uri)) fail(ProjectTransportFailure.INVALID, "Provider document URI is not tree-derived")
        DocumentsContract.getDocumentId(uri)
        DocumentsContract.getTreeDocumentId(uri)
    }
    private fun basicUri(uri: Uri, authority: String?) {
        if (uri.scheme != "content" || authority.isNullOrEmpty() || uri.authority != authority || uri.query != null || uri.fragment != null)
            fail(ProjectTransportFailure.INVALID, "Invalid provider URI or authority")
    }
}

/** Strict duplicate-key/depth/UTF-8 validation; native package loading remains the full schema authority. */
private class ManifestReferences(private val policy: ProjectTransportPolicy,
    private val cancellation: ProjectTransportCancellation) {
    private lateinit var reader: JsonReader
    private val assets = ArrayList<String>()
    private val ids = HashSet<String>()
    fun read(bytes: ByteArray): List<String> {
        try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            reader = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), decoder)).apply { isLenient = false }
            reader.use {
                var format: String? = null
                var version: Int? = null
                var layers = false
                objectFields(1) { name ->
                    when (name) {
                        "format" -> format = string()
                        "version" -> {
                            if (reader.peek() != JsonToken.NUMBER) fail(ProjectTransportFailure.INVALID, "Invalid project version")
                            val raw = reader.nextString()
                            if (!raw.matches(Regex("[0-9]+"))) fail(ProjectTransportFailure.INVALID, "Invalid project version")
                            version = raw.toIntOrNull()
                        }
                        "layers" -> {
                            layers = true
                            requireToken(JsonToken.BEGIN_ARRAY)
                            reader.beginArray()
                            var count = 0
                            while (reader.hasNext()) {
                                if (++count > policy.layers) fail(ProjectTransportFailure.LIMIT, "Manifest exceeds the ${policy.layers} layer limit")
                                layer(3)
                            }
                            reader.endArray()
                        }
                        else -> value(2)
                    }
                }
                if (format != "com.compositor.project" || version == null || !layers)
                    fail(ProjectTransportFailure.INVALID, "Invalid project manifest header")
                // Keep legacy mobile saves readable alongside current desktop
                // static projects. Native validation still checks every typed record.
                if (version!! !in 1..22 && version != 27)
                    fail(ProjectTransportFailure.UNSUPPORTED, "Project version $version is not supported")
                if (reader.peek() != JsonToken.END_DOCUMENT) fail(ProjectTransportFailure.INVALID, "Trailing manifest data")
            }
            return assets
        } catch (error: ProjectTransportException) { throw error }
        catch (error: Exception) { throw ProjectTransportException(ProjectTransportFailure.INVALID, "Manifest is not strict UTF-8 JSON", error) }
    }
    private fun layer(depth: Int) {
        var id: String? = null
        var name: String? = null
        var image: String? = null
        var mask: String? = null
        objectFields(depth) { key ->
            when (key) {
                "id" -> id = string()
                "name" -> name = string()
                "imageFile" -> image = nullableString()
                "maskFile" -> mask = nullableString()
                "gif" -> {
                    if (reader.peek() != JsonToken.NULL)
                        fail(ProjectTransportFailure.UNSUPPORTED, "Animated project layers are not supported")
                    reader.nextNull()
                }
                else -> value(depth + 1)
            }
        }
        val rawId = id ?: fail(ProjectTransportFailure.INVALID, "Layer UUID is missing")
        val uuid = try { UUID.fromString(rawId).toString().uppercase(Locale.ROOT) }
            catch (_: IllegalArgumentException) { fail(ProjectTransportFailure.INVALID, "Invalid layer UUID") }
        if (rawId.length != 36 || !uuid.equals(rawId, ignoreCase = true) || !ids.add(uuid))
            fail(ProjectTransportFailure.INVALID, "Invalid or duplicate layer UUID")
        val label = name ?: fail(ProjectTransportFailure.INVALID, "Layer name is missing")
        if (label.contains('\u0000') || !validUnicode(label) || label.toByteArray(Charsets.UTF_8).size > policy.nameBytes ||
            label.isEmpty() || label.all { Character.isWhitespace(it) || Character.isSpaceChar(it) || it == '\u0085' })
            fail(ProjectTransportFailure.INVALID, "Invalid, blank, or oversized layer name (limit ${policy.nameBytes} bytes)")
        fun reference(file: String?, expected: String) {
            if (file == null) return
            if (file == "$uuid.exr") fail(ProjectTransportFailure.UNSUPPORTED, "EXR project assets are not supported by this Android import version")
            if (file != expected) fail(ProjectTransportFailure.INVALID, "Asset filename is not derived from its layer UUID")
            if (assets.size >= policy.references) fail(ProjectTransportFailure.LIMIT, "Manifest exceeds the ${policy.references} asset reference limit")
            assets.add(file)
        }
        reference(image, "$uuid.png")
        reference(mask, "$uuid.mask.png")
    }
    private fun objectFields(depth: Int, field: (String) -> Unit) {
        cancellation.check()
        boundedDepth(depth)
        requireToken(JsonToken.BEGIN_OBJECT)
        reader.beginObject()
        val keys = HashSet<String>()
        while (reader.hasNext()) {
            cancellation.check()
            val key = reader.nextName()
            if (!validUnicode(key) || !keys.add(key)) fail(ProjectTransportFailure.INVALID, "Duplicate or invalid JSON object key")
            boundedDepth(depth + 1)
            field(key)
        }
        reader.endObject()
    }
    private fun value(depth: Int) {
        cancellation.check()
        boundedDepth(depth)
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> objectFields(depth) { value(depth + 1) }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) value(depth + 1)
                reader.endArray()
            }
            JsonToken.STRING -> string()
            JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> fail(ProjectTransportFailure.INVALID, "Invalid JSON value")
        }
    }
    private fun boundedDepth(depth: Int) { if (depth > policy.jsonDepth) fail(ProjectTransportFailure.LIMIT, "Manifest exceeds the ${policy.jsonDepth} JSON depth limit") }
    private fun requireToken(token: JsonToken) { if (reader.peek() != token) fail(ProjectTransportFailure.INVALID, "Unexpected manifest JSON type") }
    private fun string(): String {
        requireToken(JsonToken.STRING)
        return reader.nextString().also { if (!validUnicode(it)) fail(ProjectTransportFailure.INVALID, "Invalid Unicode in manifest") }
    }
    private fun nullableString(): String? = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else string()
}

private fun validUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (Character.isHighSurrogate(character)) {
            if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
        } else if (Character.isLowSurrogate(character)) return false
    }
    return true
}
private fun fail(failure: ProjectTransportFailure, message: String): Nothing = throw ProjectTransportException(failure, message)
private fun transportHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).transportHex()
private fun ByteArray.transportHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
