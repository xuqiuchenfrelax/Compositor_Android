package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated real project writes; system dialog interaction is covered separately by UI suites. */
internal fun projectCopyNameSmoke(instrumentation: Instrumentation): String {
    val root = File(instrumentation.targetContext.cacheDir, "copy-name-${UUID.randomUUID()}").apply { check(mkdir()) }
    val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }
    var workspace = AndroidWorkspace(File(root, "workspace"))
    var flow: ProjectFlowController? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    fun hashes(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to java.security.MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }
    try {
        waitFor("Workspace not ready") { workspace.snapshot().editor != null }
        val editor = checkNotNull(workspace.snapshot().editor)
        main { flow = ProjectFlowController(context, workspace) }
        val controller = checkNotNull(flow)
        main {
            controller.save(editor)
            check(controller.state.projectCopy?.name == "Untitled.comp")
            controller.updateCopyName("中文.工程.comp")
            controller.cancelSaveCopy()
            check(!controller.state.busy && workspace.snapshot().sourceIdentity == null)
            controller.chooseSaveCopy(editor)
            controller.updateCopyName("../bad.comp")
            controller.confirmSaveCopy()
            check(controller.state.projectCopy != null)
            controller.cancelSaveCopy()
            controller.chooseSaveCopy(editor)
            editor.addLayer()
            controller.confirmSaveCopy()
            check(controller.state.projectCopy == null && controller.state.error != null)
            controller.clearError()
        }
        check(LocalProjectLibrary.projects(context).isEmpty())
        fun save(name: String) {
            main { controller.chooseSaveCopy(editor); controller.updateCopyName(name); controller.confirmSaveCopy() }
            waitFor("Save remained active") { !controller.state.busy }
            check(controller.state.error == null) { controller.state.error.orEmpty() }
            check(!workspace.snapshot().projectDirty && workspace.snapshot().projectBaseline != null)
        }
        save("中文.工程.comp")
        check(workspace.snapshot().sourceIdentity == "local:中文.工程.comp")
        val first = LocalProjectLibrary.projects(context).single()
        val original = hashes(first)
        main {
            controller.chooseSaveCopy(editor)
            check(controller.state.projectCopy?.name == "中文.工程.comp")
            controller.cancelSaveCopy()
        }
        save("中文.工程.comp")
        check(workspace.snapshot().sourceIdentity == "local:中文.工程 (2).comp")
        check(LocalProjectLibrary.projects(context).size == 2 && hashes(first) == original)
        check(ImageExportNames.projectName(workspace.snapshot().sourceIdentity, null) == "中文.工程 (2).comp")
        val current = LocalProjectLibrary.projects(context).single { it.name == "中文.工程 (2).comp" }
        val beforeSave = hashes(current)
        main { editor.addLayer(); controller.save(editor) }
        waitFor("Regular save remained active") { !controller.state.busy }
        check(controller.state.error == null && !workspace.snapshot().projectDirty)
        check(LocalProjectLibrary.projects(context).size == 2 && hashes(first) == original && hashes(current) != beforeSave)
        val savedLayers = editor.state().layers.size
        // Recovery failure occurs AFTER project publication: never report a clean baseline or create a copy.
        for (fault in listOf(RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            main {
                editor.addLayer()
                controller.testHooks = ProjectFlowHooks(copySaveFault = fault)
                controller.save(editor)
            }
            waitFor("Faulted regular save remained active") { !controller.state.busy }
            check(controller.state.error != null && workspace.snapshot().projectDirty)
            check(LocalProjectLibrary.projects(context).size == 2 && hashes(first) == original)
            main { controller.testHooks = null; controller.clearError(); controller.save(editor) }
            waitFor("Regular save retry remained active") { !controller.state.busy }
            check(controller.state.error == null && !workspace.snapshot().projectDirty)
        }
        val finalLayers = editor.state().layers.size
        check(finalLayers == savedLayers + 2)
        // Missing and linked source paths must fail without recreating a package or touching another one.
        val held = File(current.parentFile, "held-package")
        check(current.renameTo(held))
        main { editor.addLayer(); controller.save(editor) }
        waitFor("Missing source save remained active") { !controller.state.busy }
        check(controller.state.error != null && !current.exists() && workspace.snapshot().projectDirty)
        main { controller.clearError() }
        android.system.Os.symlink(first.absolutePath, current.absolutePath)
        main { controller.save(editor) }
        waitFor("Linked source save remained active") { !controller.state.busy }
        check(controller.state.error != null && hashes(first) == original && workspace.snapshot().projectDirty)
        check(current.delete() && held.renameTo(current))
        main { controller.clearError(); controller.save(editor) }
        waitFor("Restored source save remained active") { !controller.state.busy }
        check(controller.state.error == null && !workspace.snapshot().projectDirty)
        main { controller.listSaved(editor) }
        waitFor("Saved list not ready for regular-save readback") { controller.state.savedProjects != null }
        main { controller.openSaved(editor, current) }
        waitFor("Regular-save readback remained active") { !controller.state.busy }
        check(controller.state.error == null && workspace.snapshot().editor !== editor)
        check(checkNotNull(workspace.snapshot().editor).state().layers.size == finalLayers + 1)
        main { controller.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        workspace = AndroidWorkspace(File(root, "workspace"))
        waitFor("Saved name recovery not ready") { workspace.snapshot().editor != null }
        check(workspace.snapshot().sourceIdentity == "local:中文.工程 (2).comp")
        check(checkNotNull(workspace.snapshot().editor).state().layers.size == finalLayers + 1)
        return "Project copy naming: retained draft cancellation, invalid/stale refusal, Chinese real save, baseline/source publication, numbered collision without overwrite regular Save replacement/retry, recovery faults, missing/link refusal and disk recovery passed."
    } finally {
        main { flow?.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        check(root.deleteRecursively())
    }
}
