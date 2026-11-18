package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real decoder, workspace installation and disk recovery; system picker UI is a separate gate. */
internal fun imageDocumentOpenSmoke(instrumentation: Instrumentation): String {
    val prefix = "image-document-${UUID.randomUUID()}-"
    val preferences = mutableSetOf<String>()
    val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            synchronized(preferences) { preferences.add(isolated) }
            return super.getSharedPreferences(isolated, mode)
        }
    }
    val root = File(context.cacheDir, prefix).apply { check(mkdir()) }
    val image = File(root, "中文.原图.png")
    val invalid = File(root, "broken.png").apply { writeText("not an image") }
    val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK, Color.YELLOW)
    Bitmap.createBitmap(colors, 3, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
        try { image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    val ledgerBefore = ImageIoAdmission.ledger.snapshot()
    var workspace = AndroidWorkspace(File(root, "workspace"))
    var flow: ProjectFlowController? = null
    var images: ImageRequestOwner? = null
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
    }
    try {
        waitFor("Initial image document workspace not ready") { workspace.snapshot().editor != null }
        val old = checkNotNull(workspace.snapshot().editor)
        old.addLayer()
        val before = old.state()
        val generation = workspace.snapshot().generation
        main { flow = ProjectFlowController(context, workspace) }
        val controller = checkNotNull(flow)
        main {
            check(controller.chooseImageDocument(old))
            controller.pickerResult(Uri.fromFile(image)) // Wrong picker purpose cannot consume this result.
            check(controller.state.phase == ProjectFlowPhase.CHOOSING)
            controller.imagePickerResult(Uri.fromFile(image))
            check(controller.state.confirmation?.imageDocument == true)
            controller.cancelConfirmation()
        }
        check(workspace.snapshot().editor === old && old.state() == before)
        main {
            check(controller.chooseImageDocument(old))
            controller.imagePickerResult(Uri.fromFile(invalid))
            check(controller.state.confirmation?.imageDocument == true)
            controller.discardAndOpen()
        }
        waitFor("Invalid image open remained active") { !controller.state.busy }
        check(controller.state.error != null && workspace.snapshot().editor === old)
        check(workspace.snapshot().generation == generation && old.state() == before)
        check(!workspace.snapshot().resourceGrowthFrozen)
        main {
            controller.clearError()
            check(controller.chooseImageDocument(old))
            controller.imagePickerResult(Uri.fromFile(image))
            controller.discardAndOpen()
        }
        waitFor("Valid image open remained active") { !controller.state.busy }
        check(controller.state.error == null) { controller.state.error.orEmpty() }
        val installed = checkNotNull(workspace.snapshot().editor)
        check(installed !== old && installed.canvasWidth == 3 && installed.canvasHeight == 2)
        check(installed.state().layers.size == 1)
        check(workspace.snapshot().projectBaseline == null && workspace.snapshot().projectDirty)
        check(ImageDocumentSource.decode(workspace.snapshot().sourceIdentity)?.name == image.name)
        val pixels = ByteBuffer.allocateDirect(24)
        check(installed.draw(3, 2, 0f, 0f, 3f, 2f, pixels))
        val expected = colors.flatMap { listOf(Color.red(it).toByte(), Color.green(it).toByte(), Color.blue(it).toByte(), (-1).toByte()) }.toByteArray()
        check(ByteArray(24).also { pixels.rewind(); pixels.get(it) }.contentEquals(expected))
        main {
            controller.chooseSaveCopy(installed)
            check(controller.state.projectCopy?.name == "中文.原图.comp")
            controller.cancelSaveCopy()
            check(workspace.snapshot().projectDirty)
            check(ImageDocumentSource.decode(workspace.snapshot().sourceIdentity)?.name == image.name)
            ImageExportPreferences.save(context, ImageExportFormat.TIFF)
            images = ImageRequestOwner(context, workspace).also { owner ->
                owner.bind(Any(), {}, {})
                check(owner.startingExportFormat() == ImageExportFormat.PNG)
                val request = checkNotNull(owner.begin(ImageRequestPurpose.EXPORT_JPEG, installed))
                check(request.outputName == "中文.原图.jpeg")
                owner.pickerResult(request.id, request.purpose, null)
                owner.close()
            }
            controller.close()
        }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        workspace = AndroidWorkspace(File(root, "workspace"))
        waitFor("Image origin disk recovery not ready") { workspace.snapshot().editor != null }
        check(workspace.snapshot().recovered && workspace.snapshot().projectDirty)
        check(checkNotNull(workspace.snapshot().editor).let { it.canvasWidth == 3 && it.canvasHeight == 2 })
        check(ImageDocumentSource.decode(workspace.snapshot().sourceIdentity)?.name == image.name)
        main {
            images = ImageRequestOwner(context, workspace).also { owner ->
                check(owner.startingExportFormat() == ImageExportFormat.PNG)
                owner.close()
            }
        }
        return "Image document: wrong-purpose picker rejection, unsaved cancellation, corrupt decode preserves document/generation, rectangular native pixels, dirty source origin, export name/format precedence and real workspace disk reopen passed. No system picker or actual process-kill claim."
    } finally {
        main { images?.close(); flow?.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        root.deleteRecursively()
        synchronized(preferences) { preferences.forEach { instrumentation.targetContext.deleteSharedPreferences(it) } }
        val after = ImageIoAdmission.ledger.snapshot()
        check(after.reservedBytes == ledgerBefore.reservedBytes && after.reservations == ledgerBefore.reservations) {
            "Image document flow leaked ledger claims"
        }
    }
}
