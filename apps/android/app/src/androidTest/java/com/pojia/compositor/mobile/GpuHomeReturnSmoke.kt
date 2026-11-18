package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Host-driven actual HOME/retained-task return; no GPU draw is requested while the Activity is stopped. */
internal fun gpuHomeReturnSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val token = "p123-gpu-home-${System.nanoTime()}"
    val root = File(context.filesDir, "debug-workspaces/$token").canonicalFile
    check(validatedDebugWorkspaceToken(token) == token && !root.exists())
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as MainActivity
    val workspace = activity.retainedWorkspaceForTest()
    val events = JSONArray()
    val evidence = JSONObject().put("token", token).put("taskId", activity.taskId).put("passed", false)
        .put("events", events).put("backgroundGpuDrawRequested", false)
        .put("scope", "Actual Activity HOME/return, GLSurfaceView context state, opaque fixture PixelCopy, CPU bytes and Canvas command history; not sustained performance or OEM driver root cause")
    var failure: Throwable? = null
    lateinit var canvas: CanvasView
    lateinit var editor: NativeEditor
    fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    fun find(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    fun await(message: String, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(15), predicate: () -> Boolean) {
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        error(message)
    }
    fun publish(phase: String, cycle: Int, detail: JSONObject = JSONObject()) {
        val event = main {
            JSONObject().put("phase", phase).put("cycle", cycle).put("atNanos", System.nanoTime())
                .put("lifecycle", activity.lifecycle.currentState.name).put("focused", activity.hasWindowFocus())
                .put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
                .put("taskId", activity.taskId).put("detail", detail)
        }
        events.put(event)
        evidence.put("phase", phase).put("cycle", cycle)
        val temporary = File(root, "gpu-home-return.json.tmp")
        temporary.writeText(evidence.toString(2))
        check(temporary.renameTo(File(root, "gpu-home-return.json")))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "GPU HOME/return workspace=$token; phase=$phase; cycle=$cycle; task=${activity.taskId}\n")
        })
    }
    fun stats(): GpuStats {
        val latch = CountDownLatch(1)
        var value: GpuStats? = null
        canvas.debugGpuStats { value = it; latch.countDown() }
        check(latch.await(8, TimeUnit.SECONDS)) { "Actual canvas GL queue did not answer" }
        return checkNotNull(value)
    }
    fun statsJson(value: GpuStats) = JSONObject().put("currentContext", value.currentContext)
        .put("tileBytes", value.bytes).put("surfaceBytes", value.surfaceBytes).put("uploads", value.uploads)
        .put("deletes", value.deletes).put("completedFrames", value.completedFrames)
        .put("sceneGeneration", value.sceneGeneration).put("usesTiles", value.usesTiles)
    fun pixels(): ByteArray {
        val out = ByteBuffer.allocateDirect(8 * 8 * 4)
        check(editor.draw(8, 8, 0.0, 0.0, 8.0, 8.0, out))
        return ByteArray(out.capacity()).also { out.rewind(); out.get(it) }
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun rendered(): GpuStats {
        main { canvas.requestRender() }
        var value: GpuStats? = null
        await("Canvas did not present the current native generation") {
            value = stats()
            val generation = checkNotNull(editor.captureScene()).use { it.generation() }
            value!!.let { it.currentContext && it.bytes > 0 && it.usesTiles && it.sceneGeneration == generation }
        }
        return checkNotNull(value)
    }
    fun capture(cycle: Int): Int {
        val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = -1
        PixelCopy.request(canvas, bitmap, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        check(copied.await(8, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "Actual canvas PixelCopy failed: $status" }
        try {
            val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            File(root, "canvas-return-$cycle.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(color == Color.rgb(96, 144, 192)) { "Restored surface center is $color instead of the opaque fixture" }
            return color
        } finally { bitmap.recycle() }
    }
    fun command(value: EditorCommand) {
        check(main { canvas.command(value) }) { "Retained canvas rejected $value" }
        editor.commandDispatcher.awaitIdleForTest().get(8, TimeUnit.SECONDS)
        await("Canvas edit did not settle") { main { !canvas.editingInProgress } }
    }
    try {
        check(workspace.recoveryDirectoryForTest().canonicalFile == root)
        check(root != File(context.filesDir, "workspace").canonicalFile)
        await("Isolated canvas not ready") {
            main { find(activity.window.decorView)?.let { view ->
                view.isAttachedToWindow && view.width > 0 && view.height > 0 && view.holder.surface.isValid && activity.hasWindowFocus()
            } == true && activity.retainedEditorForTest() != null }
        }
        check(workspace.installCandidate("gpu-home-return", DirtyDisposition.DISCARD) {
            NativeEditor(8, addDefaultLayers = false).also { fixture ->
                val bytes = ByteBuffer.allocateDirect(8 * 8 * 4)
                repeat(64) { bytes.put(96.toByte()).put(144.toByte()).put(192.toByte()).put(255.toByte()) }
                bytes.rewind(); fixture.importPixels(8, 8, bytes)
            }
        }.result.get(10, TimeUnit.SECONDS).installed)
        editor = checkNotNull(workspace.snapshot().editor)
        val bound = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
        await("Fixture not bound to the actual focused canvas") {
            main { find(activity.window.decorView)?.let { view ->
                if (bound.get(view) === editor && view.holder.surface.isValid && view.hasWindowFocus()) { canvas = view; true } else false
            } == true }
        }
        val expected = ByteArray(8 * 8 * 4) { byteArrayOf(96, -112, -64, -1)[it % 4] }
        check(pixels().contentEquals(expected))
        val homeComponent = editorUiAutomation(instrumentation).executeShellCommand(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }.lineSequence().map { it.trim() }.last { it.contains('/') && !it.contains(' ') }
        val homePackage = homeComponent.substringBefore('/')
        evidence.put("homeComponent", homeComponent).put("expectedPixelSHA256", hash(expected))
        val layer = editor.state().layers.single().id
        for (cycle in 0 until 3) {
            val initialState = editor.state()
            val warm = rendered()
            capture(-cycle - 1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            publish("await-home", cycle, statsJson(warm).put("deadlineNanos", deadline))
            await("HOME did not stop the actual Activity and expose the default launcher", deadline) {
                val stopped = main { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    !activity.hasWindowFocus() && !activity.isDestroyed && !activity.isFinishing }
                val active = editorUiAutomation(instrumentation).rootInActiveWindow
                stopped && active?.packageName?.toString() == homePackage && active.isVisibleToUser
            }
            val paused = stats()
            check(!paused.currentContext) { "Stopped Activity retained a current GLES context" }
            val trimLevel = listOf(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)[cycle]
            // Instrumentation keeps the process foreground even with a stopped Activity, so shell HIDDEN is rejected.
            // Deliver the callback explicitly; HOME and retained-task return remain real system transitions.
            main { activity.onTrimMemory(trimLevel) }
            val trimmed = stats()
            check(!trimmed.currentContext && trimmed.completedFrames == paused.completedFrames &&
                trimmed.bytes == paused.bytes && trimmed.deletes == paused.deletes) {
                "Stopped trim drew or discarded accounting without a GLES context: $paused -> $trimmed"
            }
            publish("await-return", cycle, statsJson(trimmed).put("revision", editor.currentRevision())
                .put("trimLevel", trimLevel).put("trimDelivery", "direct Activity callback while stopped")
                .put("deadlineNanos", deadline))
            await("Retained task did not return within the shared HOME/return deadline", deadline) {
                main { activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() &&
                    activity.retainedWorkspaceForTest() === workspace && activity.retainedEditorForTest() === editor &&
                    find(activity.window.decorView) === canvas && canvas.holder.surface.isValid }
            }
            check(editor.state() == initialState && pixels().contentEquals(expected)) { "HOME/return changed document or pixels" }
            val restored = rendered()
            check(restored.completedFrames > warm.completedFrames) { "No completed frame after actual return" }
            val center = capture(cycle)
            // Use the actual Canvas command queue after returning, including exact history and presentation checks.
            command(EditorCommand.SetOpacity(layer, (cycle + 1) / 4.0))
            val edited = pixels(); check(!edited.contentEquals(expected)); rendered()
            command(EditorCommand.Undo(false)); check(pixels().contentEquals(expected)); rendered(); capture(cycle + 10)
            command(EditorCommand.Undo(true)); check(pixels().contentEquals(edited)); rendered()
            command(EditorCommand.Undo(false)); check(pixels().contentEquals(expected)); rendered()
            publish("returned", cycle, statsJson(restored).put("surfaceCenterArgb", center)
                .put("originalRevision", initialState.revision).put("afterHistoryRevision", editor.currentRevision()))
        }
        workspace.flushAsync().get(10, TimeUnit.SECONDS)
        evidence.put("passed", true)
        publish("complete", 3)
        return "workspace=$token; task=${activity.taskId}; actual HOME/stopped/no-current-context/retained return x3; " +
            "opaque CPU bytes and PixelCopy exact; current scene/frame restored; Canvas opacity/undo/redo usable after each return"
    } catch (error: Throwable) {
        failure = error
        evidence.put("error", error.toString())
        runCatching { publish("failed", evidence.optInt("cycle", -1)) }.onFailure(error::addSuppressed)
        throw error
    } finally {
        try {
            main { activity.finish() }
            instrumentation.waitForIdleSync()
            workspace.closeAsync().get(12, TimeUnit.SECONDS)
        } catch (cleanup: Throwable) {
            if (failure != null) failure!!.addSuppressed(cleanup) else throw cleanup
        }
    }
}
