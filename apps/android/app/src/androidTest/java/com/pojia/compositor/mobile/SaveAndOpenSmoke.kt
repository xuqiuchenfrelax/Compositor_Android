package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real transactions in an isolated library, without touching the user's projects. */
internal fun saveAndOpenSmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "save-open-${UUID.randomUUID()}").apply { check(mkdir()) }
    val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }
    val workspace = AndroidWorkspace(File(root, "workspace"))
    var flow: ProjectFlowController? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    fun awaitIdle() {
        waitFor("Save/open remained active") { !checkNotNull(flow).state.busy }
    }
    try {
        waitFor("Workspace not ready") { workspace.snapshot().editor != null }
        val original = checkNotNull(workspace.snapshot().editor)
        main { flow = ProjectFlowController(context, workspace); checkNotNull(flow).saveCopy(original, "原始.comp") }
        awaitIdle()
        val controller = checkNotNull(flow)
        check(controller.state.error == null && !workspace.snapshot().projectDirty)
        val target = LocalProjectLibrary.projects(context).single()
        val targetManifest = File(target, "manifest.json").readBytes()
        fun select(file: File) {
            main { controller.listSaved(checkNotNull(workspace.snapshot().editor)) }
            waitFor("Saved list not ready") { controller.state.savedProjects != null }
            main { controller.openSaved(checkNotNull(workspace.snapshot().editor), file) }
            check(controller.state.confirmation != null)
        }
        main { original.addLayer() }
        val generation = workspace.snapshot().generation
        val source = workspace.snapshot().sourceIdentity
        select(target)
        main {
            controller.chooseSaveCopyAndOpen()
            check(controller.state.confirmation == null && controller.state.projectCopy?.openAfter?.file == target)
            controller.updateCopyName("draft.comp")
            controller.cancelSaveCopy()
            check(controller.state.confirmation?.file == target && controller.state.projectCopy == null)
            controller.cancelConfirmation()
        }
        check(LocalProjectLibrary.projects(context).size == 1 && workspace.snapshot().projectDirty)
        select(target)
        main {
            controller.chooseSaveCopyAndOpen()
            controller.updateCopyName("../invalid")
            controller.confirmSaveCopy()
            check(controller.state.projectCopy != null)
            original.addLayer()
            controller.updateCopyName("stale.comp")
            controller.confirmSaveCopy()
            check(controller.state.error != null && !controller.state.busy)
            controller.clearError()
        }
        check(LocalProjectLibrary.projects(context).size == 1)
        check(workspace.snapshot().editor === original && workspace.snapshot().generation == generation)
        for (fault in listOf(RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            select(target)
            main {
                controller.chooseSaveCopyAndOpen()
                controller.updateCopyName("失败.comp")
                controller.testHooks = ProjectFlowHooks(copySaveFault = fault)
                controller.confirmSaveCopy()
            }
            awaitIdle()
            check(controller.state.error != null && workspace.snapshot().editor === original)
            check(workspace.snapshot().generation == generation && workspace.snapshot().projectDirty)
            check(workspace.snapshot().sourceIdentity == source && File(target, "manifest.json").readBytes().contentEquals(targetManifest))
            main { controller.testHooks = null; controller.clearError() }
        }
        val broken = File(target.parentFile, "broken.comp").apply { check(mkdir()) }
        select(broken)
        main {
            controller.chooseSaveCopyAndOpen()
            controller.updateCopyName("保留.comp")
            controller.confirmSaveCopy()
        }
        awaitIdle()
        check(controller.state.error != null && workspace.snapshot().editor === original)
        check(workspace.snapshot().generation == generation && !workspace.snapshot().projectDirty)
        check(workspace.snapshot().sourceIdentity == "local:保留.comp")
        check(File(context.filesDir, "saved-projects/保留.comp/manifest.json").isFile)
        main { controller.clearError(); original.addLayer() }
        val savedLayerCount = original.state().layers.size
        select(target)
        main {
            controller.chooseSaveCopyAndOpen()
            controller.updateCopyName("打开前.comp")
            controller.confirmSaveCopy()
        }
        awaitIdle()
        check(controller.state.error == null) { controller.state.error.orEmpty() }
        val installed = checkNotNull(workspace.snapshot().editor)
        check(installed !== original && installed.state().layers.size < savedLayerCount)
        check(workspace.snapshot().sourceIdentity == "local:原始.comp" && !workspace.snapshot().projectDirty)
        check(File(context.filesDir, "saved-projects/打开前.comp/manifest.json").isFile)
        check(File(target, "manifest.json").readBytes().contentEquals(targetManifest))
        val image = File(root, "新图.png")
        Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        main {
            installed.addLayer()
            check(controller.chooseImageDocument(installed))
            controller.imagePickerResult(Uri.fromFile(image))
            check(controller.state.confirmation?.imageDocument == true)
            controller.chooseSaveCopyAndOpen()
            controller.updateCopyName("图像打开前.comp")
            controller.confirmSaveCopy()
        }
        awaitIdle()
        check(controller.state.error == null) { controller.state.error.orEmpty() }
        check(workspace.snapshot().editor !== installed && workspace.snapshot().editor?.canvasWidth == 2)
        check(ImageDocumentSource.decode(workspace.snapshot().sourceIdentity)?.name == image.name)
        check(workspace.snapshot().projectDirty)
        check(File(context.filesDir, "saved-projects/图像打开前.comp/manifest.json").isFile)
        return "Save then open: cancellation, invalid/stale refusal, save durability failure blocks replacement, bad target retains saved document, real project and image continuations passed."
    } finally {
        main { flow?.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        check(root.deleteRecursively())
    }
}
