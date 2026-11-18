package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Frozen Mac nearest bytes through real project decode, stroke adaptation, history and persistence. */
internal fun sampledMaskSpanSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val assets = instrumentation.context.assets
    val directory = File(context.cacheDir, "p113-mask-span-${System.nanoTime()}").apply { check(mkdirs()) }
    val initialClaims = ImageIoAdmission.ledger.snapshot()
    fun mask(editor: NativeEditor, id: Long, width: Int, height: Int): ByteArray {
        val output = ByteBuffer.allocateDirect(width * height)
        editor.maskPixels(id, width, output)
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    val golden = assets.open("p113-mask-golden.json").use { JSONObject(it.reader().readText()) }
    val entry = golden.getJSONObject("entry")
    check(entry.getString("name") == "mask_probe_0079" && entry.getInt("sourceWidth") == 2 && entry.getInt("sourceHeight") == 3)
    val bytes = entry.getJSONArray("expectedGray")
    val expected = ByteArray(15) { i -> bytes.getInt((i / 3) * 9 + i % 3).toByte() }
    val originalMask = byteArrayOf(0, 3, 40, 43, 80, 83)
    try {
        val project = File(directory, "seed.comp")
        copyProjectAssets(assets, "p113-sampled-mask.comp", project)
        NativeEditor.loadProjectCandidate(context, project, 64L * 1024 * 1024, 0).use { editor ->
            val original = editor.state().layers.single()
            val id = original.id
            check(original.pixelWidth == 3 && original.pixelHeight == 5 && original.maskWidth == 2 && original.maskHeight == 3)
            check(original.maskPlacement == null && mask(editor, id, 2, 3).contentEquals(originalMask))
            editor.startStroke(id, 1f, false, mask = true)
            val preview = mask(editor, id, 8, 9)
            for (y in 0 until 9) for (x in 0 until 8) {
                val gray = if (x in 2..4 && y in 2..6) expected[(y - 2) * 3 + x - 2] else 0.toByte()
                check(preview[y * 8 + x] == gray) { "Mask preview differs from Mac at $x,$y" }
            }
            editor.endStroke(true)
            check(editor.state().layers.single() == original && mask(editor, id, 2, 3).contentEquals(originalMask))

            // Growing image pixels adapts the implicit mask to the committed crop.
            editor.startStroke(id, 1f, false)
            editor.samples(doubleArrayOf(0.5, 0.5, 1.0, 0.0))
            editor.endStroke(false)
            val committed = editor.state().layers.single()
            check(committed.pixelWidth == 5 && committed.pixelHeight == 7 && committed.maskWidth == 5 && committed.maskHeight == 7)
            val expectedMask = ByteArray(35) { 255.toByte() }
            for (y in 0 until 5) for (x in 0 until 3) expectedMask[(y + 2) * 5 + x + 2] = expected[y * 3 + x]
            check(mask(editor, id, 5, 7).contentEquals(expectedMask)) { "Committed sampled mask differs from Mac" }
            val checkpoint = File(directory, "roundtrip.checkpoint")
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { restored ->
                check(restored.state().layers.single() == committed && mask(restored, id, 5, 7).contentEquals(expectedMask))
            }
            editor.undo(false)
            check(editor.state().layers.single() == original && mask(editor, id, 2, 3).contentEquals(originalMask))
            editor.undo(true)
            check(editor.state().layers.single() == committed && mask(editor, id, 5, 7).contentEquals(expectedMask))
            val saved = File(directory, "roundtrip.comp")
            editor.captureProjectSnapshot().use { snapshot ->
                NativeProjectWriteControl(64L * 1024 * 1024, 1024 * 1024).use { control ->
                    check(snapshot.write(context, saved, false, control).successful)
                }
            }
            NativeEditor.loadProjectCandidate(context, saved, 64L * 1024 * 1024, 0).use { restored ->
                val layer = restored.state().layers.single()
                check(layer.maskWidth == 5 && layer.maskHeight == 7 && mask(restored, layer.id, 5, 7).contentEquals(expectedMask))
            }
        }
    } finally {
        check(directory.deleteRecursively())
    }
    val finalClaims = ImageIoAdmission.ledger.snapshot()
    check(finalClaims.reservedBytes == initialClaims.reservedBytes && finalClaims.reservations == initialClaims.reservations)
    return "Actual Mac mask_probe_0079 exact; JNI implicit two-axis 2x3->3x5 mask preview/cancel, pixel-stroke growth, commit, UndoRedo, checkpoint and comp save/reopen; claims returned; tolerance=0"
}
