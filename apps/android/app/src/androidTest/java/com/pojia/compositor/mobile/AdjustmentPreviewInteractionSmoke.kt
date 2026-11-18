package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.PixelCopy
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import android.view.ViewGroup
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Exercises the production adjustment draft path around the asynchronous preview worker.
 * This intentionally uses the debug bridge only to drive the same closures as the inspector;
 * native JNI parity alone cannot prove that a cancelled or recreated draft releases its lease.
 */
internal fun adjustmentPreviewInteractionSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val token = "p141-adjustment-preview-ui-${System.nanoTime()}"
    val directory = File(context.filesDir, "debug-workspaces/$token").apply { check(mkdirs()) }
    var activity: MainActivity? = null
    var workspace: AndroidWorkspace? = null

    fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
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
    fun gpu(canvas: CanvasView): GpuStats {
        val ready = CountDownLatch(1)
        var result: GpuStats? = null
        canvas.debugGpuStats { result = it; ready.countDown() }
        check(ready.await(5, TimeUnit.SECONDS)) { "Preview GL statistics timed out" }
        return checkNotNull(result)
    }
    fun presentedPixel(canvas: CanvasView, token: Long): Int {
        waitFor("Preview frame $token was not presented") {
            gpu(canvas).let { it.currentContext && it.completedFrames > 0 && it.previewToken == token }
        }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val ready = CountDownLatch(1)
            var status = -1
            PixelCopy.request(canvas, bitmap, { status = it; ready.countDown() }, Handler(Looper.getMainLooper()))
            check(ready.await(5, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "Canvas PixelCopy failed: $status" }
            return bitmap.getPixel(8, 8)
        } finally { bitmap.recycle() }
    }
    fun findCanvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }
    try {
        NativeEditor(32, addDefaultLayers = false).use { seed ->
            val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
            repeat(32 * 32) { pixels.put(byteArrayOf(70, 100, 130.toByte(), -1)) }
            pixels.rewind()
            seed.importPixels(32, 32, pixels)
            seed.save(File(directory, "current.checkpoint"))
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
        workspace = activity!!.retainedWorkspaceForTest()
        main { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val editor = checkNotNull(activity!!.retainedEditorForTest())
        fun access() = checkNotNull(activity!!.typedLayerTestAccessForTest())
        waitFor("Adjustment preview Activity did not attach") {
            main { access().adjustment() == null && !access().busy() && editor.state().layers.size == 1 }
        }

        // Creation enters the same post-publish editor used by the inspector.
        main { access().beginAdjustment(AdjustmentKind.EXPOSURE) }
        waitFor("Exposure adjustment did not publish") {
            main { access().adjustment()?.layerId != null && !access().busy() }
        }
        val created = main { checkNotNull(access().adjustment()) }
        val layerId = checkNotNull(created.layerId)
        val committedBefore = checkNotNull(editor.adjustmentStyleOrNull(layerId))
        val revisionBefore = editor.state().revision
        val reservationsBefore = editor.resourceLedger.snapshot().reservations
        val canvas = main { checkNotNull(findCanvas(activity!!.window.decorView)) }
        waitFor("Adjustment preview did not receive a display token") {
            main { canvas.adjustmentPreviewTokenForTest() > 0L }
        }

        // Revision-changing commands must be refused while a draft owns the preview lease;
        // otherwise they can wait indefinitely for the display scene to retire.
        check(!main { canvas.command(EditorCommand.Undo(false)) }) { "Undo was admitted during adjustment draft" }
        check(!main { canvas.command(EditorCommand.Undo(true)) }) { "Redo was admitted during adjustment draft" }
        check(!main { canvas.command(EditorCommand.SelectLayers(listOf(layerId), layerId)) }) {
            "Selection change was admitted during adjustment draft"
        }
        check(!main { canvas.command(EditorCommand.DeleteLayer(layerId)) }) {
            "Layer deletion was admitted during adjustment draft"
        }

        val initialToken = main { canvas.adjustmentPreviewTokenForTest() }
        val basePixel = presentedPixel(canvas, initialToken)
        val generation = gpu(canvas).sceneGeneration
        main { access().updateAdjustment(checkNotNull(access().adjustment()).withExposureSlider(0, 1.0)) }
        waitFor("Exposure slider draft was not requested") {
            main { access().adjustment()?.style?.exposure == 1.0 && canvas.adjustmentPreviewTokenForTest() != initialToken }
        }
        val brightToken = main { canvas.adjustmentPreviewTokenForTest() }
        val brightPixel = presentedPixel(canvas, brightToken)
        main { access().updateAdjustment(checkNotNull(access().adjustment()).withExposureSlider(0, -1.0)) }
        waitFor("Second exposure slider draft was not requested") {
            main { canvas.adjustmentPreviewTokenForTest() != brightToken }
        }
        val darkPixel = presentedPixel(canvas, main { canvas.adjustmentPreviewTokenForTest() })
        check(brightPixel != darkPixel && brightPixel != basePixel) { "Two same-revision drafts reused stale screen pixels" }
        check(gpu(canvas).sceneGeneration == generation && editor.currentRevision() == revisionBefore)

        // A worker can complete while GLSurfaceView is paused. Its event queue still
        // runs without an EGL context, so publication must not require a current one.
        main { canvas.pauseInput(); canvas.onPause() }
        try {
            main { access().updateAdjustment(checkNotNull(access().adjustment()).withExposureSlider(0, 0.75)) }
            waitFor("Paused preview did not finish") {
                main { access().adjustment()?.style?.exposure == 0.75 && canvas.adjustmentPreviewIdleForTest() }
            }
            check(!gpu(canvas).currentContext) { "Paused preview test did not exercise an absent EGL context" }
        } finally {
            main { canvas.onResume(); canvas.restoreTextureBudget() }
        }
        val resumedPixel = presentedPixel(canvas, main { canvas.adjustmentPreviewTokenForTest() })
        check(resumedPixel != darkPixel) { "Paused preview did not display after resume" }

        // Feed a burst of values to the coalescing worker, then cancel while work may still be
        // in flight. Cancellation must invalidate the token and leave committed state untouched.
        main {
            var draft = checkNotNull(access().adjustment())
            for (value in listOf(-1.0, 0.5, 1.25, 2.0, -0.75, 0.25)) {
                draft = draft.copy(style = draft.style.withExposure(value = value))
                access().updateAdjustment(draft)
            }
            access().cancelAdjustment()
        }
        waitFor("Cancel did not retire the adjustment draft") {
            main { access().adjustment() == null && !access().busy() }
        }
        check(presentedPixel(canvas, 0L) == basePixel) { "Cancel did not restore committed screen pixels" }
        check(editor.state().revision == revisionBefore) { "Preview changed document revision" }
        check(equalAdjustmentJson(checkNotNull(editor.adjustmentStyleOrNull(layerId)).toMacJson(), committedBefore.toMacJson())) {
            "Cancelled preview changed committed settings"
        }
        waitFor("Preview lease did not settle after cancel") {
            editor.resourceLedger.snapshot().reservations <= reservationsBefore
        }

        // A second draft must publish one edit transaction and leave no draft after Apply.
        main { access().editAdjustment() }
        waitFor("Created layer did not reopen for preview Apply") {
            main { access().adjustment()?.layerId == layerId && !access().busy() }
        }
        val changed = main { checkNotNull(access().adjustment()).copy(
            style = checkNotNull(access().adjustment()).style.withExposure(value = 1.5)) }
        main { access().updateAdjustment(changed) }
        waitFor("Changed exposure draft did not compose") { main { access().adjustment()?.style?.exposure == 1.5 } }
        main { access().applyAdjustment() }
        waitFor("Apply did not retire the adjustment draft") {
            main { access().adjustment() == null && !access().busy() }
        }
        check(editor.state().revision == revisionBefore + 1) { "Apply did not publish one revision" }
        check(checkNotNull(editor.adjustmentStyleOrNull(layerId)).exposure == 1.5)

        // Saveable state must keep an in-progress edit across recreation, while committed state
        // remains the last applied value until the restored draft is explicitly cancelled.
        main { access().editAdjustment() }
        waitFor("Applied adjustment did not reopen for recreation") {
            main { access().adjustment()?.layerId == layerId && !access().busy() }
        }
        val restoredDraft = main { checkNotNull(access().adjustment()).copy(
            style = checkNotNull(access().adjustment()).style.withExposure(value = -1.25)) }
        main { access().updateAdjustment(restoredDraft) }
        val oldActivity = activity!!
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            main { oldActivity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        main { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        waitFor("Recreated Activity did not restore adjustment draft") {
            main { activity!!.typedLayerTestAccessForTest()?.adjustment()?.layerId == layerId &&
                activity!!.typedLayerTestAccessForTest()?.busy() == false }
        }
        val afterRecreate = main { checkNotNull(activity!!.typedLayerTestAccessForTest()).adjustment() }
        check(afterRecreate?.style?.exposure == -1.25) { "Saveable adjustment draft was lost on recreation" }
        main { checkNotNull(activity!!.typedLayerTestAccessForTest()).cancelAdjustment() }
        waitFor("Restored draft did not cancel") {
            main { activity!!.typedLayerTestAccessForTest()?.adjustment() == null }
        }
        check(editor.state().revision == revisionBefore + 1)
        check(checkNotNull(editor.adjustmentStyleOrNull(layerId)).exposure == 1.5)
        return "Adjustment preview interaction: same-generation screen pixels, command fences, paused publication/resume, burst cancel restore, one Apply revision, recreation restore passed"
    } finally {
        runCatching { activity?.let { instrumentation.runOnMainSync { it.finish() } } }
        runCatching { workspace?.closeAsync()?.get(15, TimeUnit.SECONDS) }
        check(!directory.exists() || directory.deleteRecursively())
    }
}
