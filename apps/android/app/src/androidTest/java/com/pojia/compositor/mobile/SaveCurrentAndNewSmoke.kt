package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Small documents and isolated preferences exercise real save/New transactions and failure readback. */
internal fun saveCurrentAndNewSmoke(instrumentation: Instrumentation): String {
    val prefix = "save-current-new-${UUID.randomUUID()}"
    val root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdir()) }
    val preferences = mutableSetOf<String>()
    val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            synchronized(preferences) { preferences.add(isolated) }
            return super.getSharedPreferences(isolated, mode)
        }
    }
    val workspace = AndroidWorkspace(File(root, "workspace"))
    var owner: ProjectFlowController? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun editor() = checkNotNull(workspace.snapshot().editor)
    fun flow() = checkNotNull(owner)
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    fun awaitIdle() = waitFor("Save/New remained active") { !flow().state.busy }
    fun mark(name: String) = main {
        editor().addLayer()
        editor().renameLayer(editor().state().document.activeLayerId, name)
    }
    fun hashes(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    fun request() = main {
        check(flow().chooseNewCanvas(editor()))
        flow().updateNewCanvas { it.copy(width = "17", height = "9", resolution = "144", background = NewCanvasBackground.WHITE) }
        flow().createNewCanvas()
        check(flow().state.newConfirmation != null)
    }
    fun reopen(file: File) {
        main { flow().listSaved(editor()) }
        waitFor("Saved list unavailable") { flow().state.savedProjects != null }
        main { flow().openSaved(editor(), file); if (flow().state.confirmation != null) flow().discardAndOpen() }
        awaitIdle(); check(flow().state.error == null)
    }
    fun verifyNew() {
        check(flow().state.error == null) { flow().state.error.orEmpty() }
        check(editor().canvasWidth == 17 && editor().canvasHeight == 9)
        check(editor().state().document.resolution == 144.0)
        check(workspace.snapshot().sourceIdentity == null && workspace.snapshot().projectBaseline == null && workspace.snapshot().projectDirty)
    }
    try {
        waitFor("Workspace not ready") { workspace.snapshot().editor != null }
        check(workspace.installCandidate("new-test", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed)
        main { owner = ProjectFlowController(context, workspace) }
        mark("未命名修改")
        request()
        val originalRequest = flow().state.newConfirmation
        main {
            flow().saveAndCreateNewCanvas()
            check(flow().state.projectCopy?.newAfter == originalRequest)
            flow().cancelSaveCopy()
            check(flow().state.newConfirmation == originalRequest)
            flow().saveAndCreateNewCanvas()
            flow().updateCopyName("../bad.comp"); flow().confirmSaveCopy()
            check(flow().state.projectCopy != null)
        }
        mark("命名后修改")
        val stale = editor()
        main { flow().updateCopyName("中文.comp"); flow().confirmSaveCopy() }
        check(!flow().state.busy && flow().state.error != null && editor() === stale)
        check(LocalProjectLibrary.projects(context).isEmpty())
        main { flow().clearError() }
        val unnamedNames = editor().state().layers.map { it.name }
        request()
        main { flow().saveAndCreateNewCanvas(); flow().updateCopyName("中文.comp"); flow().confirmSaveCopy() }
        awaitIdle(); verifyNew()
        val source = LocalProjectLibrary.projects(context).single()
        reopen(source)
        check(editor().state().layers.map { it.name } == unnamedNames)
        main { flow().saveCopy(editor(), "保留.comp") }
        awaitIdle(); check(flow().state.error == null)
        val other = LocalProjectLibrary.projects(context).single { it.name == "保留.comp" }
        val otherHash = hashes(other)
        reopen(source)

        for (fault in listOf(RecoveryFault.BEFORE_RENAME, RecoveryFault.AFTER_RENAME)) {
            mark("恢复失败 $fault")
            val current = editor()
            val generation = workspace.snapshot().generation
            val names = current.state().layers.map { it.name }
            request()
            main { flow().testHooks = ProjectFlowHooks(newSaveFault = fault); flow().saveAndCreateNewCanvas() }
            awaitIdle()
            check(flow().state.error != null && editor() === current && workspace.snapshot().generation == generation)
            check(workspace.snapshot().projectDirty && workspace.snapshot().sourceIdentity == "local:中文.comp")
            check(LocalProjectLibrary.projects(context).size == 2 && hashes(other) == otherHash)
            main { flow().testHooks = null; flow().clearError() }
            request()
            main { flow().saveAndCreateNewCanvas(); check(flow().state.projectCopy == null) }
            awaitIdle(); verifyNew()
            check(LocalProjectLibrary.projects(context).size == 2 && hashes(other) == otherHash)
            reopen(source)
            check(editor().state().layers.map { it.name } == names)
        }
        mark("来源缺失")
        val held = File(source.parentFile, "held-source")
        check(source.renameTo(held))
        val missing = editor()
        request()
        main { flow().saveAndCreateNewCanvas() }
        awaitIdle()
        check(flow().state.error != null && editor() === missing && workspace.snapshot().projectDirty && !source.exists())
        check(held.renameTo(source) && hashes(other) == otherHash)
        main { flow().clearError() }

        // Save succeeded but creating the candidate failed: keep the now-saved document, never install a blank.
        val names = editor().state().layers.map { it.name }
        val remembered = NewCanvasPreferences.load(context)
        request()
        main {
            flow().testHooks = ProjectFlowHooks(beforeCandidateLoad = { error("New candidate failure") })
            flow().saveAndCreateNewCanvas()
        }
        awaitIdle()
        check(flow().state.error != null && editor() === missing && !workspace.snapshot().projectDirty)
        check(NewCanvasPreferences.load(context) == remembered && hashes(other) == otherHash)
        main { flow().testHooks = null; flow().clearError() }
        reopen(source)
        check(editor().state().layers.map { it.name } == names)

        // Explicit copy remains distinct, including cancellation returning to the retained New specification.
        mark("显式副本修改")
        val sourceHash = hashes(source)
        request()
        val copyRequest = flow().state.newConfirmation
        main {
            flow().saveCopyAndCreateNewCanvas()
            check(flow().state.projectCopy?.newAfter == copyRequest)
            flow().cancelSaveCopy()
            check(flow().state.newConfirmation == copyRequest)
            flow().saveCopyAndCreateNewCanvas()
            flow().updateCopyName("明确副本.comp"); flow().confirmSaveCopy()
        }
        awaitIdle(); verifyNew()
        check(LocalProjectLibrary.projects(context).size == 3 && hashes(source) == sourceHash && hashes(other) == otherHash)
        return "Save/New: nonlocal naming/cancel/invalid/stale, real save and readback; local atomic update without duplicate, both recovery faults/retry, missing-source refusal, candidate failure preserves saved document, explicit named copy, retained specification and fresh unsaved canvas passed."
    } finally {
        main { owner?.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        synchronized(preferences) { preferences.toList() }.forEach { instrumentation.targetContext.deleteSharedPreferences(it) }
        check(root.deleteRecursively())
    }
}
