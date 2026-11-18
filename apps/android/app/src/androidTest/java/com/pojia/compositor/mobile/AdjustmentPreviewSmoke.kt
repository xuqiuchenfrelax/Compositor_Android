package com.pojia.compositor.mobile

import java.nio.ByteBuffer

/** JNI/ledger proof for the read-only adjustment preview scene. */
internal fun adjustmentPreviewSmoke(): String {
    NativeEditor(32, addDefaultLayers = false).use { editor ->
        val input = ByteBuffer.allocateDirect(32 * 32 * 4)
        repeat(32 * 32) { input.put(byteArrayOf(10, 20, 30, -1)) }
        input.rewind()
        editor.importPixels(32, 32, input)
        val layer = editor.state().document.activeLayerId
        check(layer > 0)
        val original = AdjustmentLayerModel.create(AdjustmentKind.EXPOSURE)
        val committed = editor.commitAdjustmentLayer(null, original)
        val before = editor.state().revision
        val draft = original.withExposure(value = 1.25)
        val baseline = editor.resourceLedger.snapshot()
        // Outstanding reservations must be admitted before any native preview allocation.
        editor.resourceLedger.reserve(baseline.availableBytes, "preview pressure test").use {
            check(runCatching { editor.captureAdjustmentPreviewScene(committed, draft, before) }
                .exceptionOrNull() is ResourceBudgetExceededException)
        }
        check(editor.resourceLedger.snapshot().reservedBytes == baseline.reservedBytes)
        val preview = editor.captureAdjustmentPreviewScene(committed, draft, before)
        val previewPixels = ByteBuffer.allocateDirect(32 * 32 * 4)
        preview.draw(32, 32, Viewport(0.0, 0.0, 32.0, 32.0), previewPixels)
        check(editor.state().revision == before)
        check(equalAdjustmentJson(checkNotNull(editor.adjustmentStyleOrNull(committed)).toMacJson(), original.toMacJson()))
        // An independent preview can remain retained while a command publishes.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            executor.submit<Long> { editor.commitAdjustmentLayer(committed, draft) }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            preview.close()
            executor.shutdownNow()
        }
        check(runCatching { editor.captureAdjustmentPreviewScene(committed, original, before) }
            .exceptionOrNull() is TransformDraftException) { "Stale preview request was accepted" }
        val committedScene = editor.captureScene()
        val committedPixels = ByteBuffer.allocateDirect(32 * 32 * 4)
        committedScene!!.use { it.draw(32, 32, Viewport(0.0, 0.0, 32.0, 32.0), committedPixels) }
        previewPixels.rewind(); committedPixels.rewind()
        repeat(32 * 32 * 4) { check(previewPixels.get() == committedPixels.get()) }
        val settled = editor.resourceLedger.snapshot()
        check(settled.reservations == baseline.reservations) { "Preview resource claim leaked" }
    }
    return "Read-only adjustment preview JNI, memory lease, revision fence and pixel parity passed"
}
