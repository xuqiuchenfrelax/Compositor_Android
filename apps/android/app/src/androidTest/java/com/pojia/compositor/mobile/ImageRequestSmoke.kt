package com.pojia.compositor.mobile

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Correlation/cancellation use the shipping retained owner and real codec; picker UI is a separate gate. */
internal fun imageRequestSmoke(instrumentation: Instrumentation): String {
    val preferencePrefix = "image-owner-${UUID.randomUUID()}-"
    val preferenceFiles = mutableSetOf<String>()
    val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = preferencePrefix + name
            preferenceFiles.add(isolated)
            return super.getSharedPreferences(isolated, mode)
        }
    }
    val root = File(context.cacheDir, "image-owner-${UUID.randomUUID()}").apply { check(mkdir()) }
    val input = File(root, "input.png")
    writeImageRequestFixture(input)
    val saved = SavedStateHandle()
    val workspace = AndroidWorkspace(File(root, "workspace"))
    val heldLatches = mutableListOf<CountDownLatch>()
    lateinit var owner: ImageRequestOwner
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    waitFor("Owner fixture did not load") { workspace.snapshot().editor != null }
    check(workspace.installCandidate("image-owner", DirtyDisposition.DISCARD) { NativeEditor(8, addDefaultLayers = false) }
        .result.get(10, TimeUnit.SECONDS).installed)
    var editor = checkNotNull(workspace.snapshot().editor)
    main { owner = ImageRequestOwner(context, workspace, saved); owner.bind(Any(), {}, {}) }
    fun start(purpose: ImageRequestPurpose = ImageRequestPurpose.IMPORT): ImagePickerRequest {
        lateinit var request: ImagePickerRequest
        main { owner.clearError(); request = checkNotNull(owner.begin(purpose, editor)) }
        return request
    }
    fun result(request: ImagePickerRequest, uri: Uri?) = main { owner.pickerResult(request.id, request.purpose, uri) }
    try {
        main {
            val preferences = context.getSharedPreferences("image-export", 0)
            preferences.edit().putInt("last-format", 42).commit()
            check(ImageExportPreferences.load(context) == ImageExportFormat.PNG)
            val generation = workspace.snapshot().generation
            check(!owner.confirmExportFormat(generation - 1, ImageExportFormat.TIFF))
            check(owner.startingExportFormat() == ImageExportFormat.PNG)
            check(owner.chooseExport(editor))
            check(owner.exportChoiceGeneration == generation)
            check(!owner.chooseExport(editor))
            check(owner.begin(ImageRequestPurpose.EXPORT_PNG, editor) == null)
            owner.dismissExportChoice()
            check(owner.exportChoiceGeneration == null && owner.startingExportFormat() == ImageExportFormat.PNG)
            check(owner.chooseExport(editor))
            owner.workspaceChanged(workspace.snapshot().copy(currentRevision = editor.currentRevision() + 1))
            check(owner.exportChoiceGeneration == null)
            owner.clearError()
            check(owner.confirmExportFormat(generation, ImageExportFormat.JPEG))
            check(ImageExportPreferences.load(context) == ImageExportFormat.JPEG)
            val recreated = ImageRequestOwner(context, workspace)
            check(recreated.startingExportFormat() == ImageExportFormat.JPEG)
            recreated.close()
        }
        main {
            val identity = "saf:fixture:project"
            val preferences = context.getSharedPreferences("project-display-names", 0)
            preferences.edit().putInt("recent", 42).commit()
            check(ProjectNamePreferences.projectName(context, identity) == null)
            ProjectNamePreferences.remember(context, identity, "云端.原稿.comp")
            check(ProjectNamePreferences.projectName(context, identity) == "云端.原稿.comp")
            check(ProjectNamePreferences.projectName(context, "saf:another:project") == null)
            check(ProjectNamePreferences.projectName(context, "local:本地.comp") == "本地.comp")
            ProjectNamePreferences.remember(context, identity, "../invalid.comp")
            check(ProjectNamePreferences.projectName(context, identity) == "云端.原稿.comp")
        }
        val cancelledExport = start(ImageRequestPurpose.EXPORT_JPEG)
        check(cancelledExport.outputName == "Untitled.jpeg")
        main {
            val restored = ImageRequestOwner(context, workspace,
                SavedStateHandle(mapOf(ImageRequestOwner.SAVED_KEY to saved.get<Bundle>(ImageRequestOwner.SAVED_KEY))))
            check(restored.pendingPickers().single().outputName == "Untitled.jpeg")
            restored.close()
            check(!owner.confirmExportFormat(workspace.snapshot().generation, ImageExportFormat.TIFF))
        }
        result(cancelledExport, null)
        main {
            check(owner.startingExportFormat() == ImageExportFormat.JPEG)
            check(ImageExportPreferences.load(context) == ImageExportFormat.JPEG)
        }
        val before = editor.state()
        val cancelled = start()
        main { check(owner.cancel()); check(!owner.state.busy) }
        val current = start()
        check(current.id != cancelled.id && current.key != cancelled.key)
        result(cancelled, Uri.fromFile(input))
        check(owner.state.busy && owner.state.request == current && owner.state.error?.contains("cancelled") == true)
        check(editor.state() == before)
        result(current, null)
        check(!owner.state.busy && editor.state() == before && saved.get<Bundle>(ImageRequestOwner.SAVED_KEY) == null)

        val entered = CountDownLatch(1); val resume = CountDownLatch(1).also { heldLatches.add(it) }
        main { owner.testHooks = ImageRequestHooks(beforeDecode = { entered.countDown(); check(resume.await(45, TimeUnit.SECONDS)) }) }
        val held = start(); result(held, Uri.fromFile(input)); check(entered.await(10, TimeUnit.SECONDS))
        result(held, Uri.fromFile(input))
        check(owner.state.error?.contains("Duplicate") == true && owner.state.busy)
        main { check(owner.cancel()) }; resume.countDown()
        waitFor("Cancelled decoder stayed busy") { !owner.state.busy }
        check(owner.state.error == null && owner.state.message.contains("cancelled") && editor.state() == before)
        main { owner.testHooks = null }

        val ownedBytesBefore = ImagePixelBuffer.liveBytes
        val ledgerBefore = ImageIoAdmission.ledger.snapshot()
        val decodedEntered = CountDownLatch(1); val decodedResume = CountDownLatch(1).also { heldLatches.add(it) }
        main { owner.testHooks = ImageRequestHooks(beforeCommit = {
            decodedEntered.countDown(); check(decodedResume.await(45, TimeUnit.SECONDS))
        }) }
        val decodedCancel = start(); result(decodedCancel, Uri.fromFile(input))
        check(decodedEntered.await(10, TimeUnit.SECONDS))
        check(ImagePixelBuffer.liveBytes > ownedBytesBefore && ImageIoAdmission.ledger.snapshot().reservations > ledgerBefore.reservations)
        main { check(owner.cancel()) }; decodedResume.countDown()
        waitFor("Decoded cancellation did not release its image") { !owner.state.busy }
        check(owner.state.error == null && owner.state.message.contains("cancelled") && editor.state() == before)
        check(ImagePixelBuffer.liveBytes == ownedBytesBefore)
        check(ImageIoAdmission.ledger.snapshot().let { it.reservedBytes == ledgerBefore.reservedBytes && it.reservations == ledgerBefore.reservations })
        main { owner.testHooks = null }

        val imported = start(); result(imported, Uri.fromFile(input))
        waitFor("Image import did not complete") { !owner.state.busy }
        check(owner.state.error == null && owner.state.completedId == imported.id)
        check(editor.currentRevision() == before.revision + 1 && editor.state().layers.size == 1)
        check(imageRequestPixels(editor).contentEquals(imageRequestFixturePixels()))
        result(imported, Uri.fromFile(input))
        check(owner.state.error?.contains("Duplicate") == true && editor.currentRevision() == before.revision + 1)
        editor.undo(false); check(editor.state().layers.isEmpty()); editor.undo(true)
        check(imageRequestPixels(editor).contentEquals(imageRequestFixturePixels()))

        val revision = editor.currentRevision()
        val invalid = start(); result(invalid, Uri.parse("content://com.pojia.invalid-image/missing.png"))
        waitFor("Invalid URI did not report completion") { !owner.state.busy }
        check(owner.state.error != null && editor.currentRevision() == revision)

        val denied = start()
        val deniedUri = Uri.parse("content://${ImageDeniedProvider.AUTHORITY}/fixture.png")
        // The owner has no foreground Activity in this test. Launch the test-only
        // provider owner through instrumentation's shell so OEM background-activity
        // restrictions do not silently suppress fixture preparation.
        val launch = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n com.pojia.compositor.mobile.test/com.pojia.compositor.mobile.ProjectProviderGrantActivity " +
                "--ez prepareDeniedImageOnly true").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        check(launch.contains("Status: ok")) { "Protected provider activity did not start: $launch" }
        var deniedProbe: Throwable? = null
        var deniedStreamOpened = false
        val deniedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deniedDeadline) {
            deniedStreamOpened = false
            deniedProbe = runCatching {
                context.contentResolver.openInputStream(deniedUri)?.use { deniedStreamOpened = true }
            }.exceptionOrNull()
            if (deniedProbe is SecurityException) break
            Thread.sleep(20)
        }
        check(deniedProbe is SecurityException) {
            "Protected provider fixture did not deny target access: " +
                "opened=$deniedStreamOpened, error=${deniedProbe?.javaClass?.name}: ${deniedProbe?.message}, " +
                "processUid=${android.os.Process.myUid()}, targetUid=${context.applicationInfo.uid}, " +
                "testUid=${instrumentation.context.applicationInfo.uid}"
        }
        // This is a registered protected provider with an actual PNG source and no grant to the target.
        val permission = runCatching { context.contentResolver.openInputStream(deniedUri)?.close() }.exceptionOrNull()
        check(permission is SecurityException) { "The denied URI fixture unexpectedly had permission: $permission" }
        result(denied, deniedUri)
        waitFor("Denied provider URI stayed busy") { !owner.state.busy }
        check(owner.state.error != null && editor.currentRevision() == revision)

        val stale = start()
        editor.addLayer(); val changed = editor.state()
        result(stale, Uri.fromFile(input)); waitFor("Revision mismatch stayed busy") { !owner.state.busy }
        check(owner.state.error?.contains("changed") == true && editor.state() == changed)

        val replacementRequest = start()
        check(workspace.installCandidate("local:替换.项目.comp", DirtyDisposition.DISCARD) { NativeEditor(8, addDefaultLayers = false) }
            .result.get(10, TimeUnit.SECONDS).installed)
        editor = checkNotNull(workspace.snapshot().editor)
        val replacementBefore = editor.state()
        result(replacementRequest, Uri.fromFile(input)); waitFor("Replacement result stayed busy") { !owner.state.busy }
        check(owner.state.error != null && editor.state() == replacementBefore)

        val namedPng = start(ImageRequestPurpose.EXPORT_PNG)
        check(namedPng.outputName == "替换.项目.png")
        result(namedPng, null)
        val namedSourceTiff = start(ImageRequestPurpose.EXPORT_TIFF)
        check(namedSourceTiff.outputName == "替换.项目-source-layers.tiff")
        result(namedSourceTiff, null)

        val interrupted = start()
        val hint = checkNotNull(saved.get<Bundle>(ImageRequestOwner.SAVED_KEY))
        check(hint.getBundle("activeRequest")?.getLong("revision") == interrupted.revision)
        val recoveredSaved = SavedStateHandle(mapOf(ImageRequestOwner.SAVED_KEY to Bundle(hint)))
        lateinit var recovered: ImageRequestOwner
        main {
            recovered = ImageRequestOwner(context, workspace, recoveredSaved)
            check(recovered.state.error == ImageRequestOwner.INTERRUPTED && !recovered.state.busy)
            recovered.bind(Any(), { request -> recovered.pickerResult(request.id, request.purpose, Uri.fromFile(input)) }, {})
            check(recovered.state.error == ImageRequestOwner.INTERRUPTED && recovered.pendingPickers().isEmpty())
            check(recoveredSaved.get<Bundle>(ImageRequestOwner.SAVED_KEY) == null)
            recovered.close(); check(owner.cancel())
        }
        check(editor.state() == replacementBefore)
        result(interrupted, null)

        val jpegDestination = File(root, "cancelled.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val jpegBefore = editor.state()
        val writeEntered = CountDownLatch(1)
        val writeResume = CountDownLatch(1).also { heldLatches.add(it) }
        main { owner.testHooks = ImageRequestHooks(beforeWrite = {
            writeEntered.countDown(); check(writeResume.await(45, TimeUnit.SECONDS))
        }) }
        val jpegCancelled = start(ImageRequestPurpose.EXPORT_JPEG)
        result(jpegCancelled, Uri.fromFile(jpegDestination))
        check(writeEntered.await(10, TimeUnit.SECONDS))
        main { check(owner.cancel()) }
        writeResume.countDown()
        waitFor("Cancelled JPEG remained busy") { !owner.state.busy }
        check(owner.state.error == null && owner.state.outputUri == null && editor.state() == jpegBefore)
        check(jpegDestination.readBytes().contentEquals(byteArrayOf(1, 2, 3))) { "JPEG cancellation wrote its destination" }
        main { owner.testHooks = null }

        val tiffDestination = File(root, "cancelled.tiff").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val tiffBefore = editor.state()
        val tiffEntered = CountDownLatch(1)
        val tiffResume = CountDownLatch(1).also { heldLatches.add(it) }
        val tiffBudget = ImageIoAdmission.ledger.snapshot()
        main { owner.testHooks = ImageRequestHooks(beforeWrite = {
            tiffEntered.countDown(); check(tiffResume.await(45, TimeUnit.SECONDS))
        }) }
        val tiffCancelled = start(ImageRequestPurpose.EXPORT_TIFF_FLATTENED)
        result(tiffCancelled, Uri.fromFile(tiffDestination))
        check(tiffEntered.await(10, TimeUnit.SECONDS)) { "TIFF did not reach its staged pre-write boundary" }
        main { check(owner.state.phase == ImageRequestPhase.ENCODING && owner.state.canCancel); check(owner.cancel()) }
        tiffResume.countDown()
        waitFor("Cancelled TIFF remained busy") { !owner.state.busy }
        check(owner.state.error == null && owner.state.outputUri == null && editor.state() == tiffBefore)
        check(tiffDestination.readBytes().contentEquals(byteArrayOf(7, 8, 9))) { "TIFF cancellation opened its destination" }
        check(ImageIoAdmission.ledger.snapshot().let {
            it.reservedBytes == tiffBudget.reservedBytes && it.reservations == tiffBudget.reservations
        }) { "TIFF cancellation did not release its immutable scene/encoding reservation" }
        main { owner.testHooks = null }
        val tiffRetry = start(ImageRequestPurpose.EXPORT_TIFF_FLATTENED)
        result(tiffRetry, Uri.fromFile(tiffDestination))
        waitFor("TIFF retry remained busy") { !owner.state.busy }
        check(owner.state.error == null && owner.state.outputUri == Uri.fromFile(tiffDestination) && editor.state() == tiffBefore)
        NativeImageIO.decode(context, Uri.fromFile(tiffDestination)).use {
            check(it.width == 8 && it.height == 8 && it.tiffPages.single().sourceBits == 8)
        }

        for ((format, dimensions) in listOf(ImageExportFormat.PNG to ImageExportSize(4, 4),
            ImageExportFormat.JPEG to ImageExportSize(16, 16), ImageExportFormat.TIFF to ImageExportSize(6, 6))) {
            val destination = File(root, "sized-${format.name}")
            val before = editor.state()
            lateinit var sized: ImagePickerRequest
            main {
                check(owner.chooseExport(editor))
                val choice = checkNotNull(owner.exportChoiceId)
                check(owner.launchExportChoice(choice - 1, editor, format, dimensions) == null)
                check(owner.exportChoiceId == choice)
                sized = checkNotNull(owner.launchExportChoice(choice, editor, format, dimensions,
                    JpegExportOptions(93, 0x2266aa)))
                check(sized.exportSize == dimensions && owner.exportChoiceGeneration == null)
            }
            result(sized, Uri.fromFile(destination))
            waitFor("Sized $format remained busy") { !owner.state.busy }
            check(owner.state.error == null && owner.state.outputUri == Uri.fromFile(destination))
            if (format == ImageExportFormat.TIFF) {
                NativeImageIO.decode(context, Uri.fromFile(destination)).use {
                    check(it.width == dimensions.width && it.height == dimensions.height)
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(destination.path, bounds)
                check(bounds.outWidth == dimensions.width && bounds.outHeight == dimensions.height)
            }
            check(editor.state() == before)
        }

        val writingSaved = SavedStateHandle(mapOf(ImageRequestOwner.SAVED_KEY to Bundle().apply {
            putBoolean("active", true); putString("phase", ImageRequestPhase.WRITING.name); putLong("nextId", interrupted.id)
        }))
        main {
            val writing = ImageRequestOwner(context, workspace, writingSaved)
            check(writing.state.error == ImageRequestOwner.INTERRUPTED_OUTPUT)
            check(!writing.state.busy && writingSaved.get<Bundle>(ImageRequestOwner.SAVED_KEY) == null)
            writing.close()
        }

        val closing = start()
        main { owner.close() }; workspace.closeAsync().get(10, TimeUnit.SECONDS)
        result(closing, Uri.fromFile(input))
        check(!owner.state.busy && owner.state.error?.contains("closed") == true)
        return "Retained image owner: request-specific keys, cancellation/tombstones before and after owned decode, exact scoped pixel/ledger release, duplicate result single commit, exact RGBA/one undo, invalid/permission-denied URI, revision/replacement/close rejection and saved-state interrupted hints (including uncertain image output; JPEG pre-write cancellation and TIFF staged cancellation/retry preserve destination/history and release claims) passed. Hints were reconstructed in-process; this is not actual process-kill validation or durable IO replay."
    } finally {
        heldLatches.forEach { it.countDown() }
        main { owner.testHooks = null; owner.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        root.deleteRecursively()
        preferenceFiles.forEach { instrumentation.targetContext.deleteSharedPreferences(it) }
    }
}

internal fun imageRequestFixturePixels(): ByteArray = ByteArray(8 * 8 * 4).apply {
    for (y in 0..7) for (x in 0..7) {
        val offset = (y * 8 + x) * 4
        this[offset] = (x * 30).toByte(); this[offset + 1] = (y * 30).toByte()
        this[offset + 2] = 120; this[offset + 3] = -1
    }
}
internal fun writeImageRequestFixture(file: File) {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    try {
        for (y in 0..7) for (x in 0..7) bitmap.setPixel(x, y, Color.rgb(x * 30, y * 30, 120))
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally { bitmap.recycle() }
}
internal fun imageRequestPixels(editor: NativeEditor): ByteArray {
    val buffer = ByteBuffer.allocateDirect(8 * 8 * 4)
    check(editor.draw(8, 8, 0f, 0f, 8f, 8f, buffer))
    return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
}
