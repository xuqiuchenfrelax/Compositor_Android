package com.pojia.compositor.mobile

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Real TIFF JNI import/export over frozen, independently produced libtiff inputs. */
internal fun lowBitTiffRoundTripSmoke(context: Context) {
    val assets = context.createPackageContext("${context.packageName}.test", 0).assets
    val provenance = assets.open("lowbit_tiff/provenance.json").bufferedReader().use { JSONObject(it.readText()) }
    val fixtures = provenance.getJSONArray("fixtures")
    check(fixtures.length() == 9) { "Incomplete low-bit TIFF fixture inventory" }
    val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "compositor-lowbit-tiff-").toFile()
    val baseline = ImageIoAdmission.ledger.snapshot()
    try {
        repeat(fixtures.length()) { index ->
            val fixture = fixtures.getJSONObject(index)
            val name = fixture.getString("name")
            val bits = fixture.getInt("bits")
            val photo = fixture.getInt("photometric")
            val palette = photo == 3
            val storageBits = if (palette) 16 else 8
            val bytes = assets.open("lowbit_tiff/$name").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(bytes.size == fixture.getInt("bytes") && digest == fixture.getString("sha256")) {
                "Low-bit TIFF fixture provenance mismatch: $name"
            }
            val input = File(root, name).apply { writeBytes(bytes) }
            val expected = NativeEditor(documentSize = 8, addDefaultLayers = false).use { editor ->
                val working = NativeImageIO.decode(context, Uri.fromFile(input)).use { image ->
                    check(image.width == 5 && image.height == 3 && image.tiffPages.single().sourceBits == storageBits) {
                        "Low-bit TIFF changed dimensions or expanded storage depth: $name"
                    }
                    val firstSource = if (palette) intArrayOf(13, 164, 315) else intArrayOf(0)
                    check(image.sourceFirstPixel16(0).contentEquals(firstSource)) {
                        "Low-bit TIFF lost exact gray or ColorMap16 source samples: $name"
                    }
                    val pixels = Array(15) { pixel -> image.workingPixel16(0, pixel % 5, pixel / 5) }
                    check(pixels.all { it[3] == 65535 }) { "Low-bit TIFF lost opaque alpha: $name" }
                    if (palette) check(pixels.any { pixel -> (0..2).any { pixel[it] % 257 != 0 } }) {
                        "Low-bit TIFF palette was quantized before publication: $name"
                    }
                    val revision = editor.currentRevision()
                    check(image.importInto(editor, fit = false).size == 1 && editor.currentRevision() == revision + 1) {
                        "Low-bit TIFF import must publish exactly once: $name"
                    }
                    // Match the production JNI's 8-bit publication rounding exactly.
                    pixels.map { pixel -> if (storageBits == 8) IntArray(4) { ((pixel[it] + 128) / 257) * 257 } else pixel }
                }
                editor.undo(false)
                check(editor.state().layers.isEmpty()) { "Low-bit TIFF import Undo failed: $name" }
                editor.undo(true)
                check(editor.state().layers.size == 1) { "Low-bit TIFF import Redo failed: $name" }
                val checkpoint = File(root, "$name.checkpoint")
                editor.save(checkpoint)
                NativeEditor.load(checkpoint).use { restored ->
                    val output = File(root, "$name-output.tiff")
                    val before = restored.state()
                    restored.captureProjectSnapshot().use { snapshot ->
                        NativeImageIO.exportTiffSourceLayers(context, Uri.fromFile(output), snapshot, restored.retainedMemory().totalBytes)
                    }
                    check(restored.state() == before) { "Source TIFF export mutated recovered document: $name" }
                    NativeImageIO.decode(context, Uri.fromFile(output)).use { exported ->
                        check(exported.width == 5 && exported.height == 3 && exported.tiffPages.single().sourceBits == storageBits)
                        val sourceFirst = if (storageBits == 8) working[0].map { it / 257 }.toIntArray() else working[0]
                        check(exported.sourceFirstPixel16(0).contentEquals(sourceFirst)) {
                            "Low-bit TIFF recovery/source export changed stored first pixel: $name"
                        }
                        repeat(15) { pixel ->
                            check(exported.workingPixel16(0, pixel % 5, pixel / 5).contentEquals(working[pixel])) {
                                "Low-bit TIFF recovery/export changed working pixel $pixel: $name"
                            }
                        }
                    }
                }
                working
            }
            check(expected.size == 15)
        }
        check(ImageIoAdmission.ledger.snapshot().let {
            it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations
        }) { "Low-bit TIFF import/recovery/export leaked resource claims" }
    } finally { root.deleteRecursively() }
}
