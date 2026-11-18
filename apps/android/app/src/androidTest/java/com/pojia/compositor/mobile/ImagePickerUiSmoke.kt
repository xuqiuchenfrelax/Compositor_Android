package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** The actual Photo Picker and DocumentsUI return URIs through the production per-request registry. */
internal fun imagePickerUiSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val label = UUID.randomUUID().toString()
    val jpegPrefs = context.getSharedPreferences("image-export", 0)
    val hadJpegQuality = jpegPrefs.contains("jpeg-quality")
    val priorJpegQuality = jpegPrefs.getInt("jpeg-quality", 85)
    val workspaceToken = "p132-image-picker-${System.nanoTime()}"
    check(validatedDebugWorkspaceToken(workspaceToken) == workspaceToken)
    val workspaceRoot = File(context.filesDir, "debug-workspaces/$workspaceToken").canonicalFile
    check(!workspaceRoot.exists())
    val input = File(context.cacheDir, "p21-photo-$label.png")
    writeImageRequestFixture(input)
    val resolver = context.contentResolver
    val photo = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "P21-$label.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }))
    resolver.openOutputStream(photo, "w")!!.use { output -> input.inputStream().use { it.copyTo(output) } }
    check(resolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
    instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, workspaceToken)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
    var output: Uri? = null
    var jpegOutput: Uri? = null
    var flattenedTiffOutput: Uri? = null
    var primaryFailure: Exception? = null
    var tiffInput: Uri? = null
    var bigTiffInput: Uri? = null
    val tiffFile = File(context.cacheDir, "p96-tiff-picker-$label.tiff")
    fun status(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    fun localized(text: String): String = when (text) {
        "File" -> context.getString(R.string.ui_file)
        "Import image" -> context.getString(R.string.ui_import_image)
        "Import image file" -> context.getString(R.string.ui_import_image_file)
        "Export PNG" -> context.getString(R.string.ui_export_png)
        "Export JPEG" -> context.getString(R.string.ui_export_jpeg)
        "Export TIFF (flattened)" -> context.getString(R.string.ui_export_tiff_flattened)
        "JPEG quality" -> context.getString(R.string.ui_jpeg_quality)
        "Export background" -> context.getString(R.string.ui_jpeg_background)
        "Hex color" -> context.getString(R.string.ui_hex_color)
        "OK" -> context.getString(R.string.ui_ok)
        "Cancel" -> context.getString(R.string.ui_cancel)
        "Export…" -> context.getString(R.string.ui_jpeg_export_continue)
        else -> text
    }
    fun available(text: String) = find(instrumentation.uiAutomation.rootInActiveWindow) {
        val labels = when (text) {
            "Save" -> listOf("Save", "保存")
            "List view" -> listOf("List view", "列表视图")
            else -> listOf(localized(text))
        }
        labels.any { label -> it.text?.toString()?.equals(label, true) == true ||
            it.contentDescription?.toString()?.equals(label, true) == true }
    }
    fun resource(id: String) = find(instrumentation.uiAutomation.rootInActiveWindow) { it.viewIdResourceName?.endsWith(":id/$id") == true }
    fun captureFailure(message: String, failure: Exception) {
        val prefix = "p21-image-picker-failure-${System.nanoTime()}"
        val record = JSONObject().put("message", message).put("error", failure.toString())
        fun tree(node: AccessibilityNodeInfo, depth: Int): JSONObject {
            val value = JSONObject().put("package", node.packageName ?: JSONObject.NULL)
                .put("class", node.className ?: JSONObject.NULL).put("id", node.viewIdResourceName ?: JSONObject.NULL)
                .put("text", node.text ?: JSONObject.NULL).put("description", node.contentDescription ?: JSONObject.NULL)
                .put("enabled", node.isEnabled).put("clickable", node.isClickable).put("visible", node.isVisibleToUser)
            if (depth < 64) value.put("children", JSONArray((0 until node.childCount).mapNotNull { index -> node.getChild(index)?.let { tree(it, depth + 1) } }))
            return value
        }
        runCatching { record.put("activeWindow", instrumentation.uiAutomation.rootInActiveWindow?.let { tree(it, 0) }) }
        instrumentation.runOnMainSync {
            record.put("ownerState", activity.retainedImageRequestsForTest().state.toString())
                .put("pendingPickers", activity.retainedImageRequestsForTest().pendingPickers().toString())
                .put("workspaceState", activity.retainedWorkspaceForTest().snapshot().toString())
                .put("activityLifecycle", activity.lifecycle.currentState.name)
        }
        File(context.filesDir, "$prefix.json").writeText(record.toString(2))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(context.filesDir, "$prefix.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        status("Image picker failure evidence: $prefix")
    }
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        try {
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            check(condition()) { message }
        } catch (failure: Exception) { captureFailure(message, failure); throw failure }
    }
    fun click(label: String, lookup: () -> AccessibilityNodeInfo? = { available(label) }) {
        var found: AccessibilityNodeInfo? = null
        waitFor("Missing image picker action: $label") { found = lookup(); found != null }
        try {
            var target = checkNotNull(found)
            while (!target.isClickable) target = checkNotNull(target.parent)
            check(target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Image picker action failed: $label" }
        } catch (failure: Exception) { captureFailure(label, failure); throw failure }
    }
    fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    fun canvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) canvas(view.getChildAt(index))?.let { return it }
        return null
    }
    fun ready() = activity.retainedEditorForTest() === activity.retainedWorkspaceForTest().snapshot().editor &&
        canvas(activity.window.decorView)?.let { it.isAttachedToWindow && it.holder.surface.isValid && it.width > 0 } == true
    fun recreate() {
        val previous = activity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { previous.recreate() }
        activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        instrumentation.removeMonitor(monitor)
        waitFor("Image picker recreation did not rebind") { ready() }
    }
    fun editorAction(label: String) {
        revealEditorControl(instrumentation, localized(label))
        click(label)
    }
    fun choosePhoto() {
        waitFor("Real Photo Picker did not open") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let {
                it.contains("providers.media") || it.contains("photopicker")
            } == true
        }
        click("fixture photo") {
            find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.isVisibleToUser && (it.viewIdResourceName?.endsWith(":id/icon_thumbnail") == true ||
                    it.viewIdResourceName?.endsWith(":id/item_thumbnail") == true ||
                    it.contentDescription?.toString()?.startsWith("Photo taken", true) == true)
            }
        }
        available("Add")?.let { click("Add") }
    }
    try {
        val workspace = activity.retainedWorkspaceForTest()
        check(activity.intent.getStringExtra(DEBUG_WORKSPACE_TOKEN_EXTRA) == workspaceToken &&
            workspace.recoveryDirectoryForTest().canonicalFile == workspaceRoot) { "Refusing real user workspace" }
        waitFor("Initial image picker editor missing") { workspace.snapshot().editor != null && ready() }
        check(workspace.installCandidate("image-picker", DirtyDisposition.DISCARD) { NativeEditor(8, addDefaultLayers = false) }
            .result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        waitFor("Image picker fixture editor did not bind") { activity.retainedEditorForTest() === editor && ready() }
        val owner = activity.retainedImageRequestsForTest()
        val baseline = editor.state()
        editorAction("Import image")
        waitFor("Image picker request missing") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val request = checkNotNull(owner.state.request)
        recreate()
        check(activity.retainedImageRequestsForTest() === owner && owner.state.request == request)
        choosePhoto()
        waitFor("Real Photo Picker import did not finish") { !owner.state.busy && owner.state.completedId == request.id }
        check(owner.state.error == null && editor.currentRevision() == baseline.revision + 1)
        check(editor.state().layers.size == 1 && imageRequestPixels(editor).contentEquals(imageRequestFixturePixels()))
        checkNotNull(owner.state.selectedUri).let { selected ->
            NativeImageIO.decode(context, selected).use { decoded ->
                val actual = ByteArray(decoded.pixels.capacity()).also { decoded.pixels.duplicate().apply { clear(); get(it) } }
                check(decoded.width == 8 && decoded.height == 8 && actual.contentEquals(imageRequestFixturePixels()))
            }
        }
        instrumentation.runOnMainSync { owner.pickerResult(request.id, request.purpose, photo) }
        check(owner.state.error?.contains("Duplicate") == true && editor.currentRevision() == baseline.revision + 1)
        instrumentation.runOnMainSync { owner.clearError() }
        editor.undo(false); check(editor.state().layers.isEmpty()); editor.undo(true)
        check(imageRequestPixels(editor).contentEquals(imageRequestFixturePixels()))
        status("Actual Photo Picker import/recreate/readback/single commit/undo passed")

        waitFor("Imported screen did not return") { ready() && available("File") != null }
        editorAction("Export PNG")
        waitFor("PNG request did not choose") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val export = checkNotNull(owner.state.request)
        var beforeExport = editor.state()
        recreate()
        check(owner.state.request == export && activity.retainedImageRequestsForTest() === owner)
        waitFor("Real CreateDocument did not open") { resource("toolbar") != null && available("Save") != null }
        if (resource("breadcrumb_text")?.text?.toString() != "Export project test") {
            if (available("Export project test") == null) click("provider drawer") {
                find(resource("toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
            }
            click("Export project test")
        }
        waitFor("PNG provider root missing") { resource("breadcrumb_text")?.text?.toString() == "Export project test" }
        val filename = "P21-$label.png"
        val field = checkNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.className?.toString() == "android.widget.EditText" })
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename) }))
        waitFor("PNG filename was not set") { available(filename) != null }
        click("Save")
        waitFor("Real PNG export did not finish") { !owner.state.busy && owner.state.completedId == export.id }
        check(owner.state.error == null && editor.state() == beforeExport)
        output = checkNotNull(owner.state.outputUri)
        NativeImageIO.decode(context, output!!).use { decoded ->
            val actual = ByteArray(decoded.pixels.capacity()).also { decoded.pixels.duplicate().apply { clear(); get(it) } }
            check(decoded.width == 8 && decoded.height == 8 && actual.contentEquals(imageRequestFixturePixels()))
        }
        status("Actual CreateDocument PNG export/recreate/provider readback/source unchanged passed")

        waitFor("JPEG source screen did not return") { ready() && available("File") != null }
        val visibleLayer = editor.state().layers.single()
        editor.setAppearanceForTest(visibleLayer.copy(visible = false))
        val beforeJpeg = editor.state()
        editorAction("Export JPEG")
        waitFor("JPEG settings did not open") { available("JPEG quality") != null }
        click("Cancel")
        check(!owner.state.busy && editor.state() == beforeJpeg)
        editorAction("Export JPEG")
        waitFor("JPEG quality slider missing") { available("JPEG quality") != null }
        checkNotNull(available("JPEG quality")).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f) })
        waitFor("JPEG quality did not reset") { available("JPEG quality")?.rangeInfo?.current == 0f }
        check(checkNotNull(available("JPEG quality")).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 100f) }))
        click("Export background")
        waitFor("JPEG background picker missing") { available("Hex color") != null }
        var hexField: AccessibilityNodeInfo? = null
        waitFor("JPEG background hex input missing") {
            hexField = find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.className?.toString() == "android.widget.EditText" && it.text?.toString() == "FFFFFF"
            }
            hexField != null
        }
        check(checkNotNull(hexField).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "00FF00") }))
        click("OK")
        waitFor("JPEG settings did not return") { available("Export…") != null }
        recreate()
        waitFor("JPEG settings lost after Activity recreation") { available("Export…") != null }
        click("Export…")
        waitFor("JPEG request did not choose") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val jpegRequest = checkNotNull(owner.state.request)
        check(jpegRequest.purpose == ImageRequestPurpose.EXPORT_JPEG &&
            jpegRequest.jpegOptions == JpegExportOptions(100, 0x00ff00)) { "JPEG settings were not retained in the request" }
        recreate()
        check(owner.state.request == jpegRequest && activity.retainedImageRequestsForTest() === owner)
        waitFor("JPEG CreateDocument did not open") { resource("toolbar") != null && available("Save") != null }
        if (resource("breadcrumb_text")?.text?.toString() != "Export project test") {
            if (available("Export project test") == null) click("JPEG provider drawer") {
                find(resource("toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
            }
            click("Export project test")
        }
        waitFor("JPEG provider root missing") { resource("breadcrumb_text")?.text?.toString() == "Export project test" }
        val jpegFilename = "P96-$label.jpg"
        val jpegField = checkNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.className?.toString() == "android.widget.EditText" })
        check(jpegField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, jpegFilename)
        }))
        waitFor("JPEG filename was not set") { available(jpegFilename) != null }
        click("Save")
        waitFor("Real JPEG export did not finish") { !owner.state.busy && owner.state.completedId == jpegRequest.id }
        check(owner.state.error == null && editor.state() == beforeJpeg)
        jpegOutput = checkNotNull(owner.state.outputUri)
        resolver.openInputStream(jpegOutput!!)!!.use {
            check(it.read() == 255 && it.read() == 216) { "JPEG picker output is not JPEG" }
        }
        NativeImageIO.decode(context, jpegOutput!!).use { decoded ->
            check(decoded.width == 8 && decoded.height == 8)
            val actual = ByteArray(decoded.pixels.capacity()).also { decoded.pixels.duplicate().apply { clear(); get(it) } }
            check((3 until actual.size step 4).all { (actual[it].toInt() and 255) == 255 })
            for (i in actual.indices step 4) {
                check((actual[i].toInt() and 255) <= 4 && (actual[i + 1].toInt() and 255) >= 251 &&
                    (actual[i + 2].toInt() and 255) <= 4) { "JPEG export did not use the chosen green background" }
            }
        }
        editor.setAppearanceForTest(visibleLayer)
        beforeExport = editor.state()
        check(imageRequestPixels(editor).contentEquals(imageRequestFixturePixels()))
        status("JPEG options cancel/recreate, chosen quality/background, actual CreateDocument output and source recovery passed")


        waitFor("TIFF export source screen did not return") { ready() && available("File") != null }
        editorAction("Export TIFF (flattened)")
        waitFor("TIFF export did not choose") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val flatRequest = checkNotNull(owner.state.request)
        check(flatRequest.purpose == ImageRequestPurpose.EXPORT_TIFF_FLATTENED)
        recreate()
        check(owner.state.request == flatRequest && activity.retainedImageRequestsForTest() === owner)
        waitFor("TIFF CreateDocument did not open") { resource("toolbar") != null && available("Save") != null }
        if (resource("breadcrumb_text")?.text?.toString() != "Export project test") {
            if (available("Export project test") == null) click("TIFF provider drawer") {
                find(resource("toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
            }
            click("Export project test")
        }
        waitFor("TIFF provider root missing") { resource("breadcrumb_text")?.text?.toString() == "Export project test" }
        val flatName = "P142-$label.tiff"
        val flatField = checkNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.className?.toString() == "android.widget.EditText" })
        check(flatField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, flatName)
        }))
        waitFor("TIFF filename was not set") { available(flatName) != null }
        click("Save")
        waitFor("Real TIFF export did not finish") { !owner.state.busy && owner.state.completedId == flatRequest.id }
        check(owner.state.error == null && editor.state() == beforeExport)
        flattenedTiffOutput = checkNotNull(owner.state.outputUri)
        NativeImageIO.decode(context, flattenedTiffOutput!!).use { decoded ->
            check(decoded.width == 8 && decoded.height == 8 && decoded.tiffPages.single().sourceBits == 8)
            val expected = imageRequestFixturePixels()
            for (y in 0 until 8) for (x in 0 until 8) {
                val pixel = decoded.workingPixel16(0, x, y)
                check((0..3).all { (pixel[it] + 128) / 257 == (expected[(y * 8 + x) * 4 + it].toInt() and 255) }) {
                    "Actual CreateDocument TIFF pixels differ at ($x,$y)"
                }
            }
        }
        status("Actual TIFF File action/CreateDocument/recreate/provider pixel readback and unchanged source passed")

        // Grant only prepares the provider fixture. The application still receives the selected
        // TIFF through the real OpenDocument registry callback, never a manually delivered result.
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,
            ProjectProviderGrantActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val fixtureRoot = DocumentsContract.buildDocumentUriUsingTree(ExportDocumentsProvider.tree("ok"), "ok/root")
        waitFor("TIFF fixture provider grant did not become available") {
            runCatching {
                resolver.query(fixtureRoot, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        }
        val tiffFilename = "P96-$label.tiff"
        val fixtureUri = checkNotNull(DocumentsContract.createDocument(resolver, fixtureRoot, "image/tiff", tiffFilename))
        tiffInput = fixtureUri
        writeRgbaTiff(tiffFile)
        checkNotNull(resolver.openOutputStream(fixtureUri, "w")).use { output ->
            tiffFile.inputStream().use { it.copyTo(output) }
        }
        check(resolver.getType(fixtureUri) == "image/tiff") { "TIFF fixture has the wrong provider MIME type" }
        waitFor("TIFF source screen did not return") { ready() && available("File") != null }
        val beforeTiff = editor.state()
        val beforeTiffPixels = imageRequestPixels(editor)
        revealEditorControl(instrumentation, localized("Import image file"))
        waitFor("Import image file action is not visible") { available("Import image file")?.isVisibleToUser == true }
        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            try {
                File(workspaceRoot, "p96-image-file-actions.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        click("Import image file")
        waitFor("TIFF file import did not choose") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val tiffRequest = checkNotNull(owner.state.request)
        check(tiffRequest.purpose == ImageRequestPurpose.IMPORT_FILE)
        recreate()
        check(owner.state.request == tiffRequest && activity.retainedImageRequestsForTest() === owner)
        waitFor("Real TIFF OpenDocument did not open") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true &&
                resource("toolbar") != null
        }
        if (resource("breadcrumb_text")?.text?.toString() != "Export project test") {
            if (available("Export project test") == null) click("TIFF provider drawer") {
                find(resource("toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
            }
            click("Export project test")
        }
        waitFor("TIFF provider root missing") { resource("breadcrumb_text")?.text?.toString() == "Export project test" }
        // Switch the system picker to its filename list before selecting the document;
        // thumbnail grids expose preview controls separately from the file selection row.
        available("List view")?.let { click("List view") }
        click(tiffFilename)
        waitFor("Real TIFF file import did not finish") { !owner.state.busy && owner.state.completedId == tiffRequest.id }
        check(owner.state.error == null) { "TIFF OpenDocument import failed: ${owner.state.error}" }
        val selectedTiff = checkNotNull(owner.state.selectedUri)
        check(selectedTiff.authority == fixtureUri.authority &&
            DocumentsContract.getDocumentId(selectedTiff) == DocumentsContract.getDocumentId(fixtureUri)) {
            "OpenDocument returned another file"
        }
        val afterTiff = editor.state()
        check(afterTiff.revision == beforeTiff.revision + 1 && afterTiff.layers.size == beforeTiff.layers.size + 1) {
            "TIFF picker import was not one commit"
        }
        val tiffLayer = afterTiff.layers.single { layer -> beforeTiff.layers.none { it.id == layer.id } }
        check(tiffLayer.pixelWidth == 2 && tiffLayer.pixelHeight == 1 && tiffLayer.assetPresent) {
            "TIFF picker import changed source dimensions"
        }
        checkNotNull(editor.captureScene()).use { scene ->
            val layerIndex = (0 until scene.metadata()[3].toInt()).single { scene.layerMetadata(it).id == tiffLayer.id }
            check(scene.layerMetadata(layerIndex).tileCount == 1)
            val tile = scene.tileViewMetadata(layerIndex, 0)
            check(tile.x == 0 && tile.y == 0 && tile.width == 2 && tile.height == 1)
            val sourcePixels = ByteBuffer.allocateDirect(8)
            scene.readTile(layerIndex, 0, 8, sourcePixels)
            val actual = ByteArray(8).also { sourcePixels.rewind(); sourcePixels.get(it) }
            check(actual.contentEquals(byteArrayOf(128.toByte(), 0, 0, 128.toByte(), 0, 255.toByte(), 0, 255.toByte()))) {
                "TIFF picker import changed RGBA order or alpha"
            }
        }
        editor.undo(false)
        check(editor.state().layers == beforeTiff.layers && imageRequestPixels(editor).contentEquals(beforeTiffPixels) &&
            beforeTiffPixels.contentEquals(imageRequestFixturePixels())) {
            "One TIFF undo did not restore the original photo document"
        }
        beforeExport = editor.state()
        status("Actual OpenDocument TIFF import/recreate/2x1 source pixels/single commit/single undo restored original photo passed")

        val bigTiffFilename = "P132-$label.tiff"
        val bigTiffUri = checkNotNull(DocumentsContract.createDocument(resolver, fixtureRoot, "image/tiff", bigTiffFilename))
        bigTiffInput = bigTiffUri
        writeBigTiff16(tiffFile, false)
        checkNotNull(resolver.openOutputStream(bigTiffUri, "w")).use { output ->
            tiffFile.inputStream().use { it.copyTo(output) }
        }
        waitFor("BigTIFF source screen did not return") { ready() && available("File") != null }
        editorAction("Import image file")
        waitFor("BigTIFF file import did not choose") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val bigTiffRequest = checkNotNull(owner.state.request)
        waitFor("Real BigTIFF OpenDocument did not open") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true &&
                resource("toolbar") != null
        }
        if (resource("breadcrumb_text")?.text?.toString() != "Export project test") {
            if (available("Export project test") == null) click("BigTIFF provider drawer") {
                find(resource("toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
            }
            click("Export project test")
        }
        waitFor("BigTIFF provider root missing") { resource("breadcrumb_text")?.text?.toString() == "Export project test" }
        available("List view")?.let { click("List view") }
        click(bigTiffFilename)
        waitFor("Real BigTIFF file import did not finish") { !owner.state.busy && owner.state.completedId == bigTiffRequest.id }
        check(owner.state.error == null) { "BigTIFF OpenDocument import failed: ${owner.state.error}" }
        val selectedBigTiff = checkNotNull(owner.state.selectedUri)
        check(selectedBigTiff.authority == bigTiffUri.authority &&
            DocumentsContract.getDocumentId(selectedBigTiff) == DocumentsContract.getDocumentId(bigTiffUri))
        NativeImageIO.decode(context, selectedBigTiff).use { decoded ->
            check(decoded.tiffPages.single().sourceBits == 16 &&
                decoded.sourceFirstPixel16(0).contentEquals(intArrayOf(0x1234, 0x4567, 0x789a)))
        }
        check(editor.currentRevision() == beforeExport.revision + 1 && editor.state().layers.size == beforeExport.layers.size + 1)
        editor.undo(false)
        check(editor.state().layers == beforeExport.layers && imageRequestPixels(editor).contentEquals(beforeTiffPixels))
        beforeExport = editor.state()
        status("Actual OpenDocument BigTIFF RGB16 import and undo passed")


        waitFor("Exported screen did not return") { ready() && available("File") != null }
        editorAction("Import image")
        waitFor("Cancel picker did not choose") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val cancel = checkNotNull(owner.state.request)
        recreate(); shell("input keyevent KEYCODE_BACK")
        waitFor("Real picker cancel did not clear busy") { !owner.state.busy && owner.state.completedId == cancel.id }
        check(owner.state.error == null && editor.state() == beforeExport)

        waitFor("Cancel screen did not return") { ready() && available("File") != null }
        editorAction("Import image")
        waitFor("Stale picker did not choose") { owner.state.phase == ImageRequestPhase.CHOOSING }
        val stale = checkNotNull(owner.state.request)
        check(workspace.installCandidate("image-picker-stale", DirtyDisposition.DISCARD) { NativeEditor(8, addDefaultLayers = false) }
            .result.get(10, TimeUnit.SECONDS).installed)
        val replacement = checkNotNull(workspace.snapshot().editor)
        val replacementState = replacement.state()
        choosePhoto()
        waitFor("Stale actual URI was not rejected") { !owner.state.busy && owner.pendingPickers().none { it.id == stale.id } }
        check(owner.state.error?.contains("changed") == true && replacement.state() == replacementState)
        recreate()
        check(owner.state.error?.contains("changed") == true)
        return "Actual Photo Picker and DocumentsUI PNG/JPEG/TIFF choosers survive stopped Activity recreation; TIFF source dimensions/premultiplied pixels and one-undo restoration verified; BigTIFF RGB16 imported through real OpenDocument and undone; returned URI bytes/pixels match independent8x8 RGBA, import commits once/one undo, PNG/JPEG/flattened TIFF source/baseline unchanged, system cancellation clears busy, actual late picker URI after document replacement is rejected with retained error. No direct native import substitutes for picker success."
    } catch (failure: Exception) {
        primaryFailure = failure
        runCatching { captureFailure("Image picker suite", failure) }
        throw failure
    } finally {
        jpegPrefs.edit().apply { if (hadJpegQuality) putInt("jpeg-quality", priorJpegQuality) else remove("jpeg-quality") }.commit()
        activity.retainedImageRequestsForTest().testHooks = null
        shell("input keyevent KEYCODE_BACK")
        instrumentation.runOnMainSync { activity.finish() }
        output?.let { uri -> runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
        jpegOutput?.let { uri -> runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
        flattenedTiffOutput?.let { uri -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        val tiffCleanup = runCatching {
            tiffInput?.let { uri -> check(DocumentsContract.deleteDocument(resolver, uri)) { "TIFF fixture cleanup failed" } }
            bigTiffInput?.let { uri -> check(DocumentsContract.deleteDocument(resolver, uri)) { "BigTIFF fixture cleanup failed" } }
        }
        val localTiffRemoved = !tiffFile.exists() || tiffFile.delete()
        val photoCleanup = runCatching { resolver.delete(photo, null, null) }
        input.delete()
        val cleanupFailures = listOfNotNull(tiffCleanup.exceptionOrNull(), photoCleanup.exceptionOrNull(),
            if (!localTiffRemoved) IllegalStateException("Local TIFF fixture cleanup failed") else null)
        if (primaryFailure != null) cleanupFailures.forEach { primaryFailure?.addSuppressed(it) }
        else cleanupFailures.firstOrNull()?.let { throw it }
    }
}
