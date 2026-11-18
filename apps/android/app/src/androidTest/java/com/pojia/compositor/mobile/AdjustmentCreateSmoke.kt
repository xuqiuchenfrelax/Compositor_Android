package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Focused product path: create a real default layer, then edit that exact layer. */
internal fun adjustmentCreateSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val token = "p140-adjustment-create-${System.nanoTime()}"
    val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
    var activity: MainActivity? = null
    var workspace: AndroidWorkspace? = null
    fun <T> main(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        }
        error(message)
    }
    try {
        NativeEditor(32, addDefaultLayers = false).use { seed ->
            val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
            repeat(32 * 32) { pixels.put(byteArrayOf(60, 90, 110, -1)) }
            pixels.rewind(); seed.importPixels(32, 32, pixels)
            seed.save(File(directory, "current.checkpoint"))
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
        workspace = activity!!.retainedWorkspaceForTest()
        main { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val editor = checkNotNull(activity!!.retainedEditorForTest())
        // Call only inside `main {}` blocks, matching the production debug bridge's UI ownership.
        fun access() = checkNotNull(activity!!.typedLayerTestAccessForTest())
        waitFor("Adjustment creation Activity did not attach") {
            main { access().adjustment() == null && access().busy() == false && editor.state().layers.size == 1 }
        }

        // Queue a native barrier so the production create request remains in-flight across rotation.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        editor.commandDispatcher.enqueueBarrierForTest {
            entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
        }
        main { access().beginAdjustment(AdjustmentKind.LEVELS) }
        check(entered.await(3, TimeUnit.SECONDS))
        waitFor("Create did not expose its busy fence") { main { access().busy() && access().adjustment() == null } }
        val oldActivity = activity!!
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            main { oldActivity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        main { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        check(main { access().busy() && access().adjustment() == null })
        release.countDown()
        waitFor("Created Levels editor did not rebind its native layer") {
            main { access().adjustment()?.layerId != null && !access().busy() }
        }
        val created = main { checkNotNull(access().adjustment()) }
        val createdId = checkNotNull(created.layerId)
        val creationRevision = editor.state().revision
        check(editor.state().layers.size == 2 && editor.state().document.activeLayerId == createdId)
        val defaultStyle = checkNotNull(editor.adjustmentStyleOrNull(createdId))
        check(equalAdjustmentJson(defaultStyle.toMacJson(), created.style.toMacJson()))

        // Cancel closes the settings editor but preserves the already-created layer.
        main { access().cancelAdjustment() }
        waitFor("Cancel did not close newly-created editor") { main { access().adjustment() == null && !access().busy() } }
        check(editor.state().layers.size == 2 && editor.adjustmentStyleOrNull(createdId)?.let {
            equalAdjustmentJson(it.toMacJson(), defaultStyle.toMacJson())
        } == true)

        // Reopen the selected layer, commit one settings edit, then prove no-op Apply adds no history.
        main { access().editAdjustment() }
        waitFor("Selected created layer did not reopen") { main { access().adjustment()?.layerId == createdId && !access().busy() } }
        val edit = main { checkNotNull(access().adjustment()).withLevelsInput(0, "18") }
        main { access().updateAdjustment(edit); access().applyAdjustment() }
        waitFor("Editing the created layer did not publish") { main { access().adjustment() == null && !access().busy() } }
        val editedStyle = checkNotNull(editor.adjustmentStyleOrNull(createdId))
        check(editedStyle.ranges[0].black == 18.0 && editor.state().layers.size == 2)
        val editedRevision = editor.state().revision
        main { access().editAdjustment() }
        waitFor("Edited layer did not reopen for no-op") { main { access().adjustment()?.layerId == createdId && !access().busy() } }
        main { access().applyAdjustment() }
        waitFor("No-op Apply did not close editor") { main { access().adjustment() == null && !access().busy() } }
        check(editor.state().revision == editedRevision && editor.state().layers.size == 2)

        editor.undo(false)
        check(editor.state().layers.size == 2 && editor.adjustmentStyleOrNull(createdId)?.let {
            equalAdjustmentJson(it.toMacJson(), defaultStyle.toMacJson())
        } == true)
        editor.undo(true)
        check(editor.state().layers.size == 2 && editor.adjustmentStyleOrNull(createdId)?.let {
            equalAdjustmentJson(it.toMacJson(), editedStyle.toMacJson())
        } == true)
        val afterRedoRevision = editor.state().revision

        // Invert has no settings editor and is still a single creation transaction.
        main { access().beginAdjustment(AdjustmentKind.INVERT) }
        waitFor("Invert creation did not publish") { main { !access().busy() && access().adjustment() == null && editor.state().layers.size == 3 } }
        check(editor.state().revision == afterRedoRevision + 1) {
            "Invert revision mismatch: afterRedo=$afterRedoRevision after=${editor.state().revision}"
        }
        return "P140 create/edit: busy fence across Activity recreation, exact native layerId, Cancel preserves created layer, edit Apply + no-op Apply, one settings undo/redo, Invert immediate"
    } finally {
        runCatching { activity?.let { instrumentation.runOnMainSync { it.finish() } } }
        runCatching { workspace?.closeAsync()?.get(15, TimeUnit.SECONDS) }
        check(!directory.exists() || directory.deleteRecursively())
    }
}
