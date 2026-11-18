package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real save/replace/readback transactions in a private library; never touches user projects. */
internal fun saveCurrentAndOpenSmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "save-current-open-${UUID.randomUUID()}").apply { check(mkdir()) }
    val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }
    val workspace = AndroidWorkspace(File(root, "workspace"))
    var owner: ProjectFlowController? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    fun editor() = checkNotNull(workspace.snapshot().editor)
    fun flow() = checkNotNull(owner)
    fun awaitIdle() = waitFor("Save/open remained active") { !flow().state.busy }
    fun hashes(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    fun mark(name: String) = main {
        val current = editor()
        current.addLayer()
        current.renameLayer(current.state().document.activeLayerId, name)
    }
    fun select(file: File) {
        main { flow().listSaved(editor()) }
        waitFor("Saved list not ready") { flow().state.savedProjects != null }
        main { flow().openSaved(editor(), file) }
    }
    fun openClean(file: File) {
        check(!workspace.snapshot().projectDirty)
        select(file); awaitIdle()
        check(flow().state.error == null) { flow().state.error.orEmpty() }
    }
    try {
        waitFor("Workspace not ready") { workspace.snapshot().editor != null }
        val installed = workspace.installCandidate("save-current-open-test", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS)
        check(installed.installed)
        main { owner = ProjectFlowController(context, workspace); flow().saveCopy(editor(), "当前.comp") }
        awaitIdle(); check(flow().state.error == null)
        val source = LocalProjectLibrary.projects(context).single()
        mark("目标独有层")
        main { flow().saveCopy(editor(), "目标.comp") }
        awaitIdle(); check(flow().state.error == null)
        val target = LocalProjectLibrary.projects(context).single { it.name == "目标.comp" }
        val targetHash = hashes(target)
        openClean(source)

        // A stale confirmation must never write either package or replace the current editor.
        mark("确认前修改")
        val oldHash = hashes(source)
        select(target)
        check(flow().state.confirmation != null)
        mark("确认后修改")
        val staleEditor = editor()
        main { flow().chooseSaveAndOpen() }
        check(flow().state.error != null && !flow().state.busy && editor() === staleEditor)
        check(hashes(source) == oldHash && hashes(target) == targetHash)
        main { flow().clearError() }

        // Opening the same source must reload the newly saved file, not a pre-save snapshot.
        val selfNames = editor().state().layers.map { it.name }
        select(source)
        main { flow().chooseSaveAndOpen() }
        awaitIdle()
        check(flow().state.error == null && editor() !== staleEditor && !workspace.snapshot().projectDirty)
        check(editor().state().layers.map { it.name } == selfNames)
        check(LocalProjectLibrary.projects(context).size == 2 && hashes(target) == targetHash)

        // A different target opens only after the current source and its baseline are confirmed.
        mark("切换前保存")
        val expectedNames = editor().state().layers.map { it.name }
        select(target)
        main { flow().chooseSaveAndOpen(); check(flow().state.projectCopy == null) }
        awaitIdle()
        check(flow().state.error == null && workspace.snapshot().sourceIdentity == "local:目标.comp")
        check(hashes(target) == targetHash && LocalProjectLibrary.projects(context).size == 2)
        openClean(source)
        check(editor().state().layers.map { it.name } == expectedNames)

        for (fault in listOf(RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            mark("恢复故障 $fault")
            val current = editor()
            val generation = workspace.snapshot().generation
            val expected = current.state().layers.map { it.name }
            select(target)
            main { flow().testHooks = ProjectFlowHooks(copySaveFault = fault); flow().chooseSaveAndOpen() }
            awaitIdle()
            check(flow().state.error != null && editor() === current && workspace.snapshot().generation == generation)
            check(workspace.snapshot().projectDirty && workspace.snapshot().sourceIdentity == "local:当前.comp")
            check(LocalProjectLibrary.projects(context).size == 2 && hashes(target) == targetHash)
            main { flow().testHooks = null; flow().clearError() }
            select(target)
            main { flow().chooseSaveAndOpen() }
            awaitIdle()
            check(flow().state.error == null && workspace.snapshot().sourceIdentity == "local:目标.comp")
            openClean(source)
            check(editor().state().layers.map { it.name } == expected)
        }

        mark("缺失来源拒绝")
        val held = File(source.parentFile, "held-source")
        check(source.renameTo(held))
        select(target)
        val missingEditor = editor()
        main { flow().chooseSaveAndOpen() }
        awaitIdle()
        check(flow().state.error != null && editor() === missingEditor && workspace.snapshot().projectDirty)
        check(!source.exists() && hashes(target) == targetHash)
        check(held.renameTo(source))
        main { flow().clearError() }

        // Target validation is still after confirmation on Android. Its failure keeps the saved source clean.
        val broken = File(source.parentFile, "损坏.comp").apply { check(mkdir()) }
        select(broken)
        val brokenEditor = editor()
        val beforeBroken = brokenEditor.state().layers.map { it.name }
        main { flow().chooseSaveAndOpen() }
        awaitIdle()
        check(flow().state.error != null && editor() === brokenEditor && !workspace.snapshot().projectDirty)
        check(workspace.snapshot().sourceIdentity == "local:当前.comp" && hashes(target) == targetHash)
        check(LocalProjectLibrary.projects(context).size == 3)
        main { flow().clearError() }
        openClean(source)
        check(editor().state().layers.map { it.name } == beforeBroken)

        // The same regular-save continuation also opens image documents.
        val image = File(root, "新图.png")
        Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        mark("图像打开前保存")
        val beforeImage = editor().state().layers.map { it.name }
        main {
            check(flow().chooseImageDocument(editor()))
            flow().imagePickerResult(Uri.fromFile(image))
            check(flow().state.confirmation?.imageDocument == true)
            flow().chooseSaveAndOpen()
        }
        awaitIdle()
        check(flow().state.error == null && editor().canvasWidth == 2 && editor().canvasHeight == 1)
        check(ImageDocumentSource.decode(workspace.snapshot().sourceIdentity)?.name == "新图.png")
        check(LocalProjectLibrary.projects(context).size == 3)
        // Nonlocal sources use retained naming; cancellation returns to the same open confirmation.
        mark("图像文档修改")
        select(target)
        main {
            flow().chooseSaveAndOpen()
            check(flow().state.projectCopy?.openAfter?.file == target)
            flow().cancelSaveCopy()
            check(flow().state.confirmation?.file == target)
            flow().chooseSaveAndOpen()
            flow().updateCopyName("图像文档.comp")
            flow().confirmSaveCopy()
        }
        awaitIdle()
        check(flow().state.error == null && workspace.snapshot().sourceIdentity == "local:目标.comp")
        check(LocalProjectLibrary.projects(context).size == 4 && hashes(target) == targetHash)
        openClean(source)
        check(editor().state().layers.map { it.name } == beforeImage)
        return "Regular save-before-open: stale rejection, same-source reload, different-source readback, recovery faults/retry, missing-source refusal, corrupt-target retention, image continuation and nonlocal naming/cancel/save passed. No implicit duplicate of existing local source."
    } finally {
        main { owner?.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        check(root.deleteRecursively())
    }
}
