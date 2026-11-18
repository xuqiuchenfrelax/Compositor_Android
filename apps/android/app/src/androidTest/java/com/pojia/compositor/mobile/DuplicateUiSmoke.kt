package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

internal fun duplicateUiSmoke(instrumentation: Instrumentation, wide: Boolean): String {
    val label = if (wide) "duplicate-wide" else "duplicate-phone"
    MoveInspectorFixtureSmoke(instrumentation, label).use { fixture -> with(fixture) {
        check((activity.resources.configuration.screenWidthDp >= 700) == wide)
        installAuthority {
            NativeEditor(256, false).also { candidate ->
                val pixels = ByteBuffer.allocateDirect(32 * 32 * 4).apply {
                    repeat(32 * 32) { put(byteArrayOf(90, 20, 10, -1)) }
                    rewind()
                }
                val id = candidate.importPixels(32, 32, pixels)
                candidate.setTransform(id, Transform64(20.0, 20.0, 96.0, 96.0))
                candidate.renameLayer(id, "Source")
                candidate.selectLayer(id)
            }
        }
        if (!wide) click("Layers")
        click("Duplicate on drag")
        waitUntil("Touch duplicate arm did not reach Canvas") { canvas.moveDuplicateArmed }
        val original = editor.state().layers.single()
        val overlay = canvas.moveOverlay(original.transform)
        val location = IntArray(2)
        instrumentation.runOnMainSync {
            canvas.moveShowControls = false
            canvas.getLocationOnScreen(location)
        }
        val x = location[0] + ((overlay.handles[0].x + overlay.handles[4].x) / 2).toFloat()
        val y = location[1] + ((overlay.handles[0].y + overlay.handles[4].y) / 2).toFloat()
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, dx: Float, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action, x + dx, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        pointer(MotionEvent.ACTION_DOWN, 0f, 0)
        pointer(MotionEvent.ACTION_MOVE, 12f, 30)
        waitUntil("Copied draft did not enter the layer presentation") {
            editor.duplicateLayerPresentation()?.state?.layers?.any { it.name == "Source copy" } == true
        }
        val automation = instrumentation.uiAutomation
        waitUntil("Pending copied row is absent") {
            automation.clearCache()
            node(automation.rootInActiveWindow, "Select Source copy") != null
        }
        automation.clearCache()
        val copyVisibility = checkNotNull(node(automation.rootInActiveWindow, "Visibility Source copy"))
        check(!copyVisibility.isEnabled) { "A draft copy exposed a committed visibility command" }
        check(editor.state().layers.size == 1) { "Preview topology reached committed getters" }
        val preview = checkNotNull(editor.duplicateLayerPresentation())
        val screenshot = File(instrumentation.targetContext.filesDir,
            "p41-$label-pending-copy-${System.nanoTime()}.png")
        val image = checkNotNull(automation.takeScreenshot()) { "Pending copy screenshot unavailable" }
        try { screenshot.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        check(screenshot.length() > 0)
        observation("pending-copy-row", JSONObject()
            .put("sourceLayers", editor.state().layers.size)
            .put("copyLayers", preview.state.layers.size - editor.state().layers.size)
            .put("previewLayers", preview.state.layers.size)
            .put("selectedPreviewId", preview.selection.primary)
            .put("selectedPreviewUUID", preview.state.layers.single { it.id == preview.selection.primary }.uuid)
            .put("screenshot", screenshot.name))
        pointer(MotionEvent.ACTION_UP, 40f, 60)
        settled()
        check(editor.state().layers.size == 2)
        check(editor.state().layers.first { it.id == original.id }.transform == original.transform)
        check(editor.state().layers.any { it.name == "Source copy" })
        editor.undo(false)
        check(editor.state().layers.size == 1)
        successful = true
        return "Compose $label: touch duplicate arm, pending copied row disabled, one committed copy and undo."
    } }
}
