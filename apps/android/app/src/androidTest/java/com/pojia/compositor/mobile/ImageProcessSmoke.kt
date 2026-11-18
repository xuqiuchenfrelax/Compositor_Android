package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Preparation ends before a normal app process opens the chooser; ADB owns the actual kill/return evidence. */
internal fun imageProcessPrepareSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val label = UUID.randomUUID().toString()
    val fixture = File(context.cacheDir, "p21-process-$label.png")
    writeImageRequestFixture(fixture)
    val photo = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "P21-process-$label.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }))
    context.contentResolver.openOutputStream(photo, "w")!!.use { output -> fixture.inputStream().use { it.copyTo(output) } }
    check(context.contentResolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    val workspace = activity.retainedWorkspaceForTest()
    try {
        waitForImageProcess("Process fixture workspace did not open") { workspace.snapshot().editor != null }
        check(workspace.installCandidate("image-process-$label", DirtyDisposition.DISCARD) { NativeEditor(8, addDefaultLayers = false) }
            .result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        // A distinctive persisted baseline is created before any image chooser request.
        editor.addLayer()
        val layer = editor.state().layers.single().id
        editor.renameLayer(layer, "Process baseline $label")
        val savedRevision = workspace.flushAsync().get(10, TimeUnit.SECONDS)
        check(savedRevision == editor.currentRevision())
        val marker = JSONObject().put("schema", 1).put("label", label).put("photoUri", photo.toString())
            .put("exportName", "P21-process-$label.png").put("documentUuid", editor.state().document.uuid)
            .put("revision", savedRevision).put("layers", JSONArray(editor.state().layers.map { JSONObject().put("uuid", it.uuid).put("name", it.name) }))
            .put("pixelSha256", imageProcessHash(imageRequestPixels(editor)))
            .put("contract", "Normal Activity picker -> system saved Activity state -> background am kill -> actual picker result -> new process interrupted rejection. No durable command replay.")
        val file = File(context.filesDir, "p21-image-process-$label.json")
        check(!file.exists())
        file.outputStream().use { output -> output.write(marker.toString(2).toByteArray()); output.fd.sync() }
        fixture.delete()
        return "Prepared persisted image process baseline and unique MediaStore photo. Marker=${file.name}; ordinary app launch/picker/kill/return must be captured externally before imageProcessVerify. This preparation is not process-death acceptance."
    } finally { instrumentation.runOnMainSync { activity.finish() } }
}

/** A new instrumentation process checks the persisted baseline after the externally observed interrupted result. */
internal fun imageProcessVerifySmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val markers = context.filesDir.listFiles()!!.filter { it.name.startsWith("p21-image-process-") && it.name.endsWith(".json") }
    check(markers.size == 1) { "Process verification requires exactly one prepared marker, not an inferred latest request" }
    val marker = JSONObject(markers.single().readText())
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    try {
        waitForImageProcess("Process verification document missing") { activity.retainedWorkspaceForTest().snapshot().editor != null }
        val editor = checkNotNull(activity.retainedWorkspaceForTest().snapshot().editor)
        check(editor.state().document.uuid == marker.getString("documentUuid"))
        check(editor.currentRevision() == marker.getLong("revision"))
        val expected = marker.getJSONArray("layers")
        check(editor.state().layers.size == expected.length())
        editor.state().layers.forEachIndexed { index, layer ->
            check(layer.uuid == expected.getJSONObject(index).getString("uuid") && layer.name == expected.getJSONObject(index).getString("name"))
        }
        check(imageProcessHash(imageRequestPixels(editor)) == marker.getString("pixelSha256"))
        check(!editor.state().undo && !editor.state().redo)
        check(!activity.retainedImageRequestsForTest().state.busy && activity.retainedImageRequestsForTest().pendingPickers().isEmpty())
        return "After externally captured real background kill/chooser-return, cold checkpoint uuid/revision/layers/pixels remain exact with empty history and no pending image request. This native verification must be paired with PID/UI/URI evidence; it does not alone prove process restart or interrupted UI."
    } finally {
        instrumentation.runOnMainSync { activity.finish() }
        context.contentResolver.delete(android.net.Uri.parse(marker.getString("photoUri")), null, null)
    }
}

private fun waitForImageProcess(message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
    check(condition()) { message }
}
private fun imageProcessHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
