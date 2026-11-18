package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Preference failure must never turn an installed document or accepted export into a stuck operation. */
internal fun preferenceFailureSmoke(instrumentation: Instrumentation): String {
    val prefix = "preference-failure-${UUID.randomUUID()}"
    val root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdir()) }
    val preferences = mutableSetOf<String>()
    var failNew = false
    var failExport = false
    val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (name == "compositor_new_canvas" && failNew || name == "image-export" && failExport)
                throw IllegalStateException("Injected optional preference access failure")
            val isolated = "$prefix-$name"
            synchronized(preferences) { preferences.add(isolated) }
            return super.getSharedPreferences(isolated, mode)
        }
    }
    var workspace = AndroidWorkspace(File(root, "workspace"))
    var projects: ProjectFlowController? = null
    var images: ImageRequestOwner? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    try {
        waitFor("Workspace not ready") { workspace.snapshot().editor != null }
        check(workspace.installCandidate("preference-test", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed)
        val old = checkNotNull(workspace.snapshot().editor)
        main {
            projects = ProjectFlowController(context, workspace)
            failNew = true
            check(NewCanvasPreferences.load(context) == NewCanvasDraft())
            val flow = checkNotNull(projects)
            check(flow.chooseNewCanvas(old))
            flow.updateNewCanvas { it.copy(width = "11", height = "7", resolution = "144") }
            old.addLayer()
            // Refresh the request after the edit so its confirmation is current.
            flow.cancelNewCanvas()
            check(flow.chooseNewCanvas(old))
            flow.updateNewCanvas { it.copy(width = "11", height = "7", resolution = "144") }
            flow.createNewCanvas()
            check(flow.state.newConfirmation != null)
            flow.discardAndCreateNewCanvas()
        }
        waitFor("Preference error stranded New in a busy state") {
            workspace.snapshot().editor !== old && !checkNotNull(projects).state.busy && !workspace.snapshot().resourceGrowthFrozen
        }
        val created = checkNotNull(workspace.snapshot().editor)
        check(checkNotNull(projects).state.error == null)
        check(created.canvasWidth == 11 && created.canvasHeight == 7 && created.state().document.resolution == 144.0)
        check(workspace.snapshot().projectDirty && workspace.snapshot().projectBaseline == null)
        main {
            failExport = true
            images = ImageRequestOwner(context, workspace)
            val owner = checkNotNull(images)
            check(owner.confirmExportFormat(workspace.snapshot().generation, ImageExportFormat.TIFF))
            check(owner.startingExportFormat() == ImageExportFormat.TIFF)
            owner.bind(Any(), {}, {})
            val request = checkNotNull(owner.begin(ImageRequestPurpose.EXPORT_PNG, created))
            owner.pickerResult(request.id, request.purpose, android.net.Uri.fromFile(File(root, "result.png")))
        }
        waitFor("PNG completion stranded by optional format preference") { !checkNotNull(images).state.busy }
        check(checkNotNull(images).state.error == null && checkNotNull(images).state.outputUri != null)
        android.graphics.BitmapFactory.decodeFile(File(root, "result.png").absolutePath).let { bitmap ->
            checkNotNull(bitmap)
            try { check(bitmap.width == 11 && bitmap.height == 7) } finally { bitmap.recycle() }
        }
        main {
            val owner = checkNotNull(images)
            check(owner.startingExportFormat() == ImageExportFormat.PNG)
            val request = checkNotNull(owner.begin(ImageRequestPurpose.EXPORT_JPEG, created, JpegExportOptions(91)))
            ImageExportPreferences.saveJpegQuality(context, 91)
            owner.pickerResult(request.id, request.purpose, android.net.Uri.fromFile(File(root, "result.jpeg")))
        }
        waitFor("JPEG request stranded by optional quality preference") { !checkNotNull(images).state.busy }
        check(checkNotNull(images).state.error == null && checkNotNull(images).state.outputUri != null)
        android.graphics.BitmapFactory.decodeFile(File(root, "result.jpeg").absolutePath).let { bitmap ->
            checkNotNull(bitmap)
            try { check(bitmap.width == 11 && bitmap.height == 7) } finally { bitmap.recycle() }
        }
        main {
            val owner = checkNotNull(images)
            // Defaults can be remembered again after the transient failure is gone.
            failExport = false
            check(owner.confirmExportFormat(workspace.snapshot().generation, ImageExportFormat.JPEG))
            check(ImageExportPreferences.load(context) == ImageExportFormat.JPEG)
            ImageExportPreferences.saveJpegQuality(context, 91)
            check(context.getSharedPreferences("image-export", 0).getInt("jpeg-quality", 0) == 91)
            failNew = false
            NewCanvasPreferences.save(context, NewCanvasSpec(11, 7, 144.0, NewCanvasBackground.TRANSPARENT))
            check(NewCanvasPreferences.load(context) == NewCanvasDraft(width = "11", height = "7"))
            images?.close(); images = null
            projects?.close(); projects = null
        }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        workspace = AndroidWorkspace(File(root, "workspace"))
        waitFor("Document recovery after optional preference failure not ready") { workspace.snapshot().editor != null }
        check(workspace.snapshot().editor?.canvasWidth == 11 && workspace.snapshot().editor?.canvasHeight == 7)
        check(workspace.snapshot().projectDirty && workspace.snapshot().projectBaseline == null)
        return "Optional preference access failure: New defaults load safely, native canvas installs and exits busy, document recovers; Export As accepts document-local format, PNG/JPEG write and decode without stuck completion, JPEG quality does not throw, later preference writes work."
    } finally {
        main { images?.close(); projects?.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        synchronized(preferences) { preferences.toList() }.forEach { instrumentation.targetContext.deleteSharedPreferences(it) }
        check(root.deleteRecursively())
    }
}
