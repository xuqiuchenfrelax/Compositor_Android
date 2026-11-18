package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Adversarial source tests plus a real ContentResolver/DocumentsProvider boundary. */
internal fun projectTransportSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val testRoot = File(context.cacheDir.canonicalFile, "project-transport-${System.nanoTime()}").apply { check(mkdirs()) }
    val policy = ProjectTransportPolicy()
    check(policy.manifestBytes == 4L * 1024 * 1024 && policy.assetBytes == 512L * 1024 * 1024 &&
        policy.jobBytes == 512L * 1024 * 1024 && policy.diskReserveBytes == 256L * 1024 * 1024)
    var checks = 0
    fun emptyStaging(): Boolean = testRoot.listFiles().orEmpty().all { it.name == ".registry.lock" }
    fun transport(source: ProjectTransportSource, limits: ProjectTransportPolicy = policy,
        free: Long = Long.MAX_VALUE): ProjectTransport = ProjectTransport(source, testRoot, limits) { free }
    fun rejected(expected: ProjectTransportFailure, source: MutableProjectTransportSource,
        limits: ProjectTransportPolicy = policy, free: Long = Long.MAX_VALUE,
        cancellation: ProjectTransportCancellation = ProjectTransportCancellation(),
        progress: (ProjectTransportProgress) -> Unit = {}) {
        try {
            transport(source, limits, free).stage(source.tree, cancellation, progress).use { error("Unsafe source was staged") }
        } catch (error: ProjectTransportException) {
            check(error.failure == expected) { "Expected $expected, got ${error.failure}: ${error.message}" }
        }
        check(emptyStaging()) { "Failed transport retained owned staging" }
        check(source.deleted == 0) { "Transport deleted source documents" }
        ++checks
    }
    try {
        val shortReads = MutableProjectTransportSource().apply { maximumRead = 2 }
        val firstIdentity: String
        transport(shortReads).stage(shortReads.tree, ProjectTransportCancellation()).use { staged ->
            check(File(staged.directory, "manifest.json").readBytes().contentEquals(shortReads.manifestBytes))
            check(File(staged.directory, "images/${FakeProjectDocumentsProvider.LAYER_ID}.png").readBytes()
                .contentEquals(FakeProjectDocumentsProvider.asset))
            check(!File(staged.directory, "QuickLook").exists() && !File(staged.directory, "images/ignored.png").exists())
            check(shortReads.openCounts["ignored.png"] == null && shortReads.listedPreview == 0)
            check(staged.sourceIdentity.length < 4096 && staged.fingerprint.length == 64)
            check(shortReads.closedStreams == shortReads.openCounts.values.sum())
            firstIdentity = staged.sourceIdentity
            ++checks
        }
        transport(MutableProjectTransportSource()).stage(shortReads.tree, ProjectTransportCancellation()).use {
            check(it.sourceIdentity == firstIdentity) { "Unchanged source provenance was unstable" }; ++checks
        }
        rejected(ProjectTransportFailure.CHANGED, MutableProjectTransportSource().apply { earlyEof = true })
        rejected(ProjectTransportFailure.CHANGED, MutableProjectTransportSource().apply { extraByte = true })
        rejected(ProjectTransportFailure.CHANGED, MutableProjectTransportSource().apply { mutateSecondAssetRead = true })
        rejected(ProjectTransportFailure.CHANGED, MutableProjectTransportSource().apply { metadataChangesAfterOpen = true })
        rejected(ProjectTransportFailure.IO, MutableProjectTransportSource().apply { readError = true })
        rejected(ProjectTransportFailure.IO, MutableProjectTransportSource().apply { revoked = true })
        rejected(ProjectTransportFailure.UNSUPPORTED, MutableProjectTransportSource().apply { changeAsset { it.copy(size = null) } })
        rejected(ProjectTransportFailure.UNSUPPORTED, MutableProjectTransportSource().apply { changeAsset { it.copy(size = -1) } })
        rejected(ProjectTransportFailure.UNSUPPORTED, MutableProjectTransportSource().apply {
            changeAsset { it.copy(mimeType = DocumentsContract.Document.MIME_TYPE_DIR) }
        })
        rejected(ProjectTransportFailure.UNSUPPORTED, MutableProjectTransportSource().apply {
            changeAsset { it.copy(flags = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) }
        })
        rejected(ProjectTransportFailure.LIMIT, MutableProjectTransportSource().apply { changeAsset { it.copy(size = policy.assetBytes + 1) } })
        rejected(ProjectTransportFailure.LIMIT, MutableProjectTransportSource(), policy.copy(manifestBytes = 8, jobBytes = 1000))
        val jobSource = MutableProjectTransportSource()
        rejected(ProjectTransportFailure.LIMIT, jobSource, policy.copy(manifestBytes = jobSource.manifestBytes.size.toLong(),
            jobBytes = jobSource.manifestBytes.size.toLong() + 1))
        rejected(ProjectTransportFailure.LIMIT, MutableProjectTransportSource(), policy.copy(rootEntries = 2))
        rejected(ProjectTransportFailure.LIMIT, MutableProjectTransportSource(), policy.copy(imageEntries = 1))
        rejected(ProjectTransportFailure.LIMIT, MutableProjectTransportSource(), policy.copy(totalEntries = 3))
        rejected(ProjectTransportFailure.LIMIT, MutableProjectTransportSource(), policy.copy(observedMetadataBytes = 100))
        rejected(ProjectTransportFailure.LIMIT, MutableProjectTransportSource(), policy.copy(providerTextBytes = 8))
        rejected(ProjectTransportFailure.DISK, MutableProjectTransportSource(), free = policy.diskReserveBytes)
        rejected(ProjectTransportFailure.INVALID, MutableProjectTransportSource().apply { imageChildren.removeAt(0) })
        rejected(ProjectTransportFailure.INVALID, MutableProjectTransportSource().apply { imageChildren.add(imageChildren.first()) })
        rejected(ProjectTransportFailure.INVALID, MutableProjectTransportSource().apply {
            imageChildren[1] = imageChildren[1].copy(canonicalIdentity = imageChildren[0].canonicalIdentity)
        })
        rejected(ProjectTransportFailure.INVALID, MutableProjectTransportSource().apply {
            imageChildren[1] = imageChildren[1].copy(documentId = imageChildren[0].documentId)
        })
        rejected(ProjectTransportFailure.INVALID, MutableProjectTransportSource().apply {
            imageChildren[1] = imageChildren[1].copy(displayName = "../escape.png")
        })
        fun alteredManifest(transform: (String) -> String): MutableProjectTransportSource =
            MutableProjectTransportSource(transform(String(FakeProjectDocumentsProvider.manifest, Charsets.UTF_8)).toByteArray())
        rejected(ProjectTransportFailure.INVALID, alteredManifest { it.replace("\"version\":13", "\"version\":13,\"version\":13") })
        rejected(ProjectTransportFailure.INVALID, alteredManifest { it.dropLast(1) + ",\"ignored\":{\"x\":0,\"x\":1}}" })
        rejected(ProjectTransportFailure.INVALID, alteredManifest { it.replace("Photo", "\\uD800") })
        rejected(ProjectTransportFailure.INVALID, alteredManifest { it.replace("Photo", "\\u3000") })
        rejected(ProjectTransportFailure.INVALID, MutableProjectTransportSource(byteArrayOf(123, -64, -81, 125)))
        rejected(ProjectTransportFailure.INVALID, alteredManifest { it.replace(".png", "/../bad.png") })
        rejected(ProjectTransportFailure.UNSUPPORTED, alteredManifest { it.replace(".png", ".exr") })
        for (version in listOf(15, 16, 17, 18, 19, 20, 21, 27)) {
            val supported = alteredManifest { it.replace("\"version\":13", "\"version\":$version") }
            transport(supported).stage(supported.tree, ProjectTransportCancellation()).use { staged ->
                check(JSONObject(File(staged.directory, "manifest.json").readText()).getInt("version") == version)
                ++checks
            }
        }
        for (version in (22..26) + listOf(0, 28, Int.MAX_VALUE)) {
            val unsupported = alteredManifest { it.replace("\"version\":13", "\"version\":$version") }
            rejected(ProjectTransportFailure.UNSUPPORTED, unsupported)
            check(unsupported.openCounts["${FakeProjectDocumentsProvider.LAYER_ID}.png"] == null)
        }
        for (version in listOf(16, 17, 18, 19, 20, 21, 27)) {
            val animated = JSONObject(String(FakeProjectDocumentsProvider.manifest, Charsets.UTF_8))
                .put("version", version)
            animated.getJSONArray("layers").getJSONObject(0).put("gif", JSONObject()
                .put("sourceFile", "${FakeProjectDocumentsProvider.LAYER_ID}.gif").put("selectedFrameIndex", 0))
            val source = MutableProjectTransportSource(animated.toString().toByteArray())
            rejected(ProjectTransportFailure.UNSUPPORTED, source)
            check(source.openCounts["${FakeProjectDocumentsProvider.LAYER_ID}.png"] == null) {
                "Animation rejection copied the selected-frame preview"
            }
            animated.getJSONArray("layers").getJSONObject(0).put("gif", JSONObject.NULL)
            val absent = MutableProjectTransportSource(animated.toString().toByteArray())
            transport(absent).stage(absent.tree, ProjectTransportCancellation()).use { ++checks }
        }
        rejected(ProjectTransportFailure.LIMIT, alteredManifest { it.dropLast(1) + ",\"ignored\":[[[[[0]]]]]}" }, policy.copy(jsonDepth = 4))
        val preCancelled = ProjectTransportCancellation().apply { cancel() }
        rejected(ProjectTransportFailure.CANCELLED, MutableProjectTransportSource(), cancellation = preCancelled)
        val activeCancellation = ProjectTransportCancellation()
        val interrupted = MutableProjectTransportSource().apply { cancelDuringRead = activeCancellation }
        rejected(ProjectTransportFailure.CANCELLED, interrupted, cancellation = activeCancellation)
        check(interrupted.closedStreams > 0) { "Cancellation did not close the active stream" }
        val callbackCancellation = ProjectTransportCancellation()
        rejected(ProjectTransportFailure.CANCELLED, MutableProjectTransportSource(), cancellation = callbackCancellation,
            progress = { if (it.phase == "assets") callbackCancellation.cancel() })

        // The unchanged desktop fixture also reaches the actual shared loader from transport staging.
        val fixtureRoot = "nearest-editable.comp"
        val fixtureManifest = instrumentation.context.assets.open("$fixtureRoot/manifest.json").use { it.readBytes() }
        val fixtureAssets = instrumentation.context.assets.list("$fixtureRoot/images").orEmpty().associateWith { name ->
            instrumentation.context.assets.open("$fixtureRoot/images/$name").use { it.readBytes() }
        }
        val fixture = MutableProjectTransportSource(fixtureManifest, fixtureAssets)
        transport(fixture).stage(fixture.tree, ProjectTransportCancellation()).use { staged ->
            NativeEditor.loadProjectCandidate(context, staged.directory, 64L * 1024 * 1024, 1024L * 1024).use {
                check(it.state().layers.size == 4 && it.state().revision == 0L && !it.state().undo)
            }
            ++checks
        }

        val v14Root = "raster-editable.comp"
        val v14Manifest = instrumentation.context.assets.open("$v14Root/manifest.json").use { it.readBytes() }
        check(JSONObject(String(v14Manifest, Charsets.UTF_8)).getInt("version") == 14)
        val v14Assets = instrumentation.context.assets.list("$v14Root/images").orEmpty().associateWith { name ->
            instrumentation.context.assets.open("$v14Root/images/$name").use { it.readBytes() }
        }
        val v14 = MutableProjectTransportSource(v14Manifest, v14Assets)
        transport(v14).stage(v14.tree, ProjectTransportCancellation()).use { staged ->
            check(File(staged.directory, "manifest.json").readBytes().contentEquals(v14Manifest))
            for ((name, bytes) in v14Assets) {
                check(File(staged.directory, "images/$name").readBytes().contentEquals(bytes))
            }
            NativeEditor.loadProjectCandidate(context, staged.directory, 64L * 1024 * 1024, 1024L * 1024).use { candidate ->
                val state = candidate.state()
                check(state.layers.size == JSONObject(String(v14Manifest, Charsets.UTF_8)).getJSONArray("layers").length() &&
                    state.revision == 0L && !state.undo && !state.redo)
            }
            ++checks
        }
        check(v14.deleted == 0 && emptyStaging())
        val v16Manifest = JSONObject(String(v14Manifest, Charsets.UTF_8)).put("version", 16)
            .toString().toByteArray(Charsets.UTF_8)
        val v16 = MutableProjectTransportSource(v16Manifest, v14Assets)
        transport(v16).stage(v16.tree, ProjectTransportCancellation()).use { staged ->
                NativeEditor.loadProjectCandidate(context, staged.directory, 64L * 1024 * 1024, 1024L * 1024).use {
                    check(it.state().layers.size == JSONObject(String(v16Manifest, Charsets.UTF_8)).getJSONArray("layers").length())
                }
                ++checks
            }
        val advancedV16 = JSONObject(String(v16Manifest, Charsets.UTF_8)).apply {
            getJSONArray("layers").getJSONObject(0).put("adjustment", JSONObject().put("kind", "Levels"))
        }.toString().toByteArray(Charsets.UTF_8)
        val advanced = MutableProjectTransportSource(advancedV16, v14Assets)
        try {
            transport(advanced).stage(advanced.tree, ProjectTransportCancellation()).use { staged ->
                NativeEditor.loadProjectCandidate(context, staged.directory, 64L * 1024 * 1024, 1024L * 1024).close()
                error("Unsupported v16 adjustment record was accepted")
            }
        } catch (error: ProjectImportException) {
            check(error.status == 8) { "Malformed image-bearing adjustment was not rejected as corrupt: ${error.status}" }
            check(emptyStaging())
            ++checks
        }

        // Acquisition is checked before provider startup, and some emulator shells lack MANAGE_DOCUMENTS.
        // A test APK activity grants its own provider trees from the actual provider-owner UID.
        val providerOwner = ComponentName(instrumentation.context.packageName,
            ProjectProviderGrantActivity::class.java.name).flattenToString()
        check(providerOwner.matches(Regex("[A-Za-z0-9_./]+")))
        val providerStart = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("am start -W -n $providerOwner"))
            .bufferedReader().use { it.readText() }
        check(Regex("(?m)^Status: ok\\s*$").containsMatchIn(providerStart) && !providerStart.contains("Error:")) {
            "Test provider owner activity did not start: $providerStart"
        }
        val grantDeadline = android.os.SystemClock.uptimeMillis() + 10_000
        fun hasProviderGrants(): Boolean = FakeProjectDocumentsProvider.testModes.all { mode ->
            context.checkUriPermission(FakeProjectDocumentsProvider.tree(mode), android.os.Process.myPid(),
                android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }
        while (!hasProviderGrants() && android.os.SystemClock.uptimeMillis() < grantDeadline) android.os.SystemClock.sleep(25)
        check(hasProviderGrants()) { "Test provider owner activity did not grant its trees" }
        val providerSource = DocumentsProjectTransportSource(instrumentation.context.contentResolver)
        transport(providerSource).stage(FakeProjectDocumentsProvider.tree("ok"), ProjectTransportCancellation()).use {
            check(File(it.directory, "manifest.json").readBytes().contentEquals(FakeProjectDocumentsProvider.manifest))
            check(File(it.directory, "images/${FakeProjectDocumentsProvider.LAYER_ID}.png").readBytes()
                .contentEquals(FakeProjectDocumentsProvider.asset))
            ++checks
        }
        fun providerRejected(mode: String, expected: ProjectTransportFailure) {
            try {
                transport(providerSource).stage(FakeProjectDocumentsProvider.tree(mode), ProjectTransportCancellation())
                    .use { error("Rejected provider mode $mode was accepted") }
            } catch (error: ProjectTransportException) { check(error.failure == expected) { "$mode: ${error.failure} ${error.message}" } }
            check(emptyStaging()); ++checks
        }
        providerRejected("pipe", ProjectTransportFailure.UNSUPPORTED)
        providerRejected("alias", ProjectTransportFailure.INVALID)
        providerRejected("duplicate", ProjectTransportFailure.INVALID)
        providerRejected("unknown", ProjectTransportFailure.UNSUPPORTED)
        providerRejected("outside", ProjectTransportFailure.INVALID)
        providerRejected("revoked", ProjectTransportFailure.IO)
        transport(providerSource).stage(FakeProjectDocumentsProvider.tree("editable"), ProjectTransportCancellation()).use { staged ->
            check(File(staged.directory, "manifest.json").readBytes().contentEquals(fixtureManifest))
            NativeEditor.loadProjectCandidate(context, staged.directory, 64L * 1024 * 1024, 1024L * 1024).use {
                check(it.state().layers.size == 4 && it.state().revision == 0L && !it.state().undo)
            }
            ++checks
        }
        return "Project transport: $checks bounded staging/provider checks; exact referenced bytes, static v15/v16/v17/v27 transport, native candidates and malformed adjustment refusal, animation rejection before assets, cleanup, mutation and cancellation\n" +
            projectStagingSmoke(context)
    } finally { testRoot.deleteRecursively() }
}

internal class MutableProjectTransportSource(
    val manifestBytes: ByteArray = FakeProjectDocumentsProvider.manifest,
    private val assetBytes: Map<String, ByteArray> = mapOf("${FakeProjectDocumentsProvider.LAYER_ID}.png" to FakeProjectDocumentsProvider.asset),
) : ProjectTransportSource {
    val tree: Uri = DocumentsContract.buildTreeDocumentUri("test.transport", "opaque/root:0")
    private val directoryMime = DocumentsContract.Document.MIME_TYPE_DIR
    private fun document(id: String, name: String, mime: String, bytes: ByteArray? = null): ProjectTransportDocument {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return ProjectTransportDocument(uri, id, name, mime, 0, bytes?.size?.toLong(), 1, uri.toString())
    }
    private val packageRoot = document("opaque/root:0", "fixture.comp", directoryMime)
    private val images = document("opaque/root:0/images", "images", directoryMime)
    private val manifest = document("opaque/root:0/manifest", "manifest.json", "application/json", manifestBytes)
    private val preview = document("opaque/root:0/preview", "QuickLook", directoryMime)
    val rootChildren = mutableListOf(manifest, images, preview)
    val imageChildren = assetBytes.map { (name, bytes) -> document("opaque/root:0/images/$name", name, "image/png", bytes) }
        .toMutableList().apply { add(document("opaque/root:0/images/ignored", "ignored.png", "image/png", byteArrayOf(42))) }
    val openCounts = HashMap<String, Int>()
    var maximumRead = Int.MAX_VALUE
    var earlyEof = false
    var extraByte = false
    var mutateSecondAssetRead = false
    var metadataChangesAfterOpen = false
    var readError = false
    var revoked = false
    var cancelDuringRead: ProjectTransportCancellation? = null
    var closedStreams = 0
    var listedPreview = 0
    val deleted = 0 // No deletion method exists on the injected transport interface.
    fun changeAsset(change: (ProjectTransportDocument) -> ProjectTransportDocument) { imageChildren[0] = change(imageChildren[0]) }
    override fun root(treeUri: Uri, cancellation: ProjectTransportCancellation): ProjectTransportDocument {
        cancellation.check()
        if (revoked) throw SecurityException("Permission revoked")
        check(treeUri == tree)
        return packageRoot
    }
    override fun stat(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): ProjectTransportDocument {
        cancellation.check()
        return if (metadataChangesAfterOpen && (openCounts[document.displayName] ?: 0) > 0) document.copy(modified = 2) else document
    }
    override fun children(parent: ProjectTransportDocument, cancellation: ProjectTransportCancellation,
        visit: (ProjectTransportDocument) -> Unit) {
        cancellation.check()
        val children = when (parent.documentId) {
            packageRoot.documentId -> rootChildren
            images.documentId -> imageChildren
            else -> { ++listedPreview; error("Transport descended into an ignored subtree") }
        }
        children.forEach { cancellation.check(); visit(it) }
    }
    override fun open(document: ProjectTransportDocument, cancellation: ProjectTransportCancellation): InputStream {
        cancellation.check()
        if (revoked) throw SecurityException("Permission revoked")
        val count = (openCounts[document.displayName] ?: 0) + 1
        openCounts[document.displayName] = count
        var bytes = if (document.displayName == "manifest.json") manifestBytes else assetBytes.getValue(document.displayName)
        if (earlyEof) bytes = bytes.copyOf(bytes.size - 1)
        if (extraByte) bytes += byteArrayOf(0)
        if (mutateSecondAssetRead && count == 2 && document.displayName != "manifest.json") bytes = bytes.reversedArray()
        return object : ByteArrayInputStream(bytes) {
            private var closed = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (readError) throw IOException("Provider read error")
                cancelDuringRead?.cancel()
                return super.read(buffer, offset, minOf(length, maximumRead))
            }
            override fun close() { if (!closed) { closed = true; ++closedStreams }; super.close() }
        }
    }
}
