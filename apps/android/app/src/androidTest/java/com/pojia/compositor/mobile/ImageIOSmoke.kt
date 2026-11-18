package com.pojia.compositor.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * Image IO is intentionally split into bounded phases for physical devices.
 * Each selector exercises only its named codecs and reports that scope.
 * Imported fixtures release their leases as soon as their assertions finish.
 */
internal fun imageIOSmoke(context: Context, phase: String = "all"): String {
    require(phase in setOf("all", "png", "jpeg", "pngJpeg", "deep", "tiff", "heic")) { "Unknown image IO phase: $phase" }
    fun wanted(name: String) = phase == "all" || phase == name ||
        (phase == "pngJpeg" && name in setOf("png", "jpeg"))
    val ownedImages = mutableListOf<ImportedImage>()
    fun decode(uri: Uri) = NativeImageIO.decode(context, uri)
    val baseline = ImageIoAdmission.ledger.snapshot()
    fun stage(name: String) {
        android.util.Log.i("CompositorImageIO", "$phase/$name: ${ImageIoAdmission.ledger.snapshot()}")
    }
    val input = File.createTempFile("compositor-image-test-", ".png", context.cacheDir)
    val output = File.createTempFile("compositor-export-test-", ".png", context.cacheDir)
    val deep = File.createTempFile("compositor-deep-test-", ".png", context.cacheDir)
    val tiff = File.createTempFile("compositor-tiff-test-", ".tiff", context.cacheDir)
    val rotatedTiff = File.createTempFile("compositor-tiff-rotated-test-", ".tiff", context.cacheDir)
    try {
        val bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 5) for (x in 0 until 7) {
                bitmap.setPixel(x, y, Color.argb(if (x == 0) 0 else if (x == 1) 127 else 255, x * 30, y * 45, 180))
            }
            input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        stage("decode-fixture")
        val decoded = decode(Uri.fromFile(input)).also { ownedImages.add(it) }
        check(decoded.width == 7 && decoded.height == 5)
        val imported = bytes(decoded.pixels)
        for (offset in imported.indices step 4) {
            val alpha = imported[offset + 3].toInt() and 255
            check((0..2).all { (imported[offset + it].toInt() and 255) <= alpha })
        }
        if (wanted("png")) NativeEditor(documentSize = 7, addDefaultLayers = false).use { editor ->
            editor.importPixels(7, 5, decoded.pixels)
            val before = editor.state()
            NativeImageIO.exportPng(context, Uri.fromFile(output), editor)
            check(editor.state() == before) { "PNG export changed document state" }
            val pixels = decode(Uri.fromFile(output)).use { exported ->
                check(exported.width == 7 && exported.height == 7)
                bytes(exported.pixels)
            }
            for (offset in imported.indices) {
                check(abs((imported[offset].toInt() and 255) - (pixels[offset].toInt() and 255)) <= 1) {
                    "PNG import/export changed alpha or channel order at $offset"
                }
            }
            check(pixels.drop(imported.size).all { it == 0.toByte() }) { "PNG lost transparent canvas pixels" }
            editor.undo(false)
            check(editor.state().layers.isEmpty()) { "Image import was not one undo transaction" }
        }
        if (wanted("png")) NativeEditor(documentSize = 7, addDefaultLayers = false).use { editor ->
            editor.importPixels(7, 5, decoded.pixels)
            val expected = decode(Uri.fromFile(output)).use { bytes(it.pixels) }
            stage("capture-immutable")
            val scene = checkNotNull(editor.captureScene())
            scene.use {
                val revision = scene.metadata()[0]
                stage("edit-with-immutable")
                editor.setAppearanceForTest(editor.state().layers.single().copy(visible = false))
                check(editor.currentRevision() != revision) { "Snapshot export test did not advance the document" }
                NativeImageIO.exportPng(context, Uri.fromFile(output), editor)
                check(decode(Uri.fromFile(output)).use { bytes(it.pixels) }.all { it == 0.toByte() }) {
                    "A fresh export did not reflect the later edit"
                }
                editor.close()
                NativeImageIO.exportPng(context, Uri.fromFile(output), scene)
                val captured = decode(Uri.fromFile(output)).use {
                    check(it.width == 7 && it.height == 7)
                    bytes(it.pixels)
                }
                check(captured.contentEquals(expected)) {
                    "Snapshot export used pixels from a later edit or lost ownership after document close"
                }
                check(scene.metadata()[0] == revision) { "PNG export changed the captured revision" }
            }
        }
        if (wanted("png")) NativeEditor(documentSize = 1, addDefaultLayers = false).use { editor ->
            fun add(color: ByteArray) = editor.importPixels(1, 1,
                ByteBuffer.allocateDirect(4).apply { put(color); rewind() })
            val red = add(byteArrayOf(-1, 0, 0, -1))
            val blue = add(byteArrayOf(0, 0, -1, -1))
            val group = editor.addGroup()
            editor.reparentLayer(red, group); editor.reparentLayer(blue, group)
            editor.setAppearanceForTest(editor.state().layers.first { it.id == group }.copy(opacity = 0.5))
            fun verifyPng(expected: IntArray) {
                val pixels = decode(Uri.fromFile(output)).use { exported ->
                    check(exported.width == 1 && exported.height == 1)
                    bytes(exported.pixels)
                }
                for (channel in 0..3) check(abs((pixels[channel].toInt() and 255) - expected[channel]) <= 1) {
                    "Group PNG export differs from the real desktop golden at channel $channel"
                }
            }
            NativeImageIO.exportPng(context, Uri.fromFile(output), editor)
            verifyPng(intArrayOf(64, 0, 128, 192))
            checkNotNull(editor.captureScene()).use { captured ->
                editor.setAppearanceForTest(editor.state().layers.first { it.id == group }.copy(opacity = 1.0))
                NativeImageIO.exportPng(context, Uri.fromFile(output), editor)
                verifyPng(intArrayOf(0, 0, 255, 255))
                NativeImageIO.exportPng(context, Uri.fromFile(output), captured)
                verifyPng(intArrayOf(64, 0, 128, 192))
            }
        }
        val tiffContract = if (wanted("tiff")) tiffDecoderSmoke() else "TIFF phase deferred"
        if (wanted("jpeg")) jpegExportSmoke(context)
        if (wanted("deep")) {
            write16BitPng(deep)
            png16RoundTripSmoke(context, deep)
            paletteTiffRoundTripSmoke(context)
            uint12TiffRoundTripSmoke(context)
            bigTiffRoundTripSmoke(context)
        }
        if (wanted("tiff")) {
            lowBitTiffRoundTripSmoke(context)
            writeRgbaTiff(tiff)
        }
        fun nativeTiffPixels(file: File, width: Int, height: Int): ByteArray {
            NativeEditor(documentSize = 8, addDefaultLayers = false).use { editor ->
                NativeImageIO.decode(context, Uri.fromFile(file)).use { image ->
                    check(image.width == width && image.height == height) { "TIFF orientation/dimensions changed" }
                    check(image.tiffPages.size == 1 && image.tiffPages[0].sourceBits == 8)
                    val before = editor.state().revision
                    check(image.importInto(editor, fit = false).size == 1)
                    check(editor.state().revision == before + 1)
                }
                val original = checkNotNull(editor.captureScene()).use { scene ->
                    val buffer = ByteBuffer.allocateDirect(width * height * 4)
                    scene.readTile(0, 0, width * 4, buffer)
                    bytes(buffer)
                }
                val exportedTiff = File.createTempFile("compositor-source-pages-", ".tiff", context.cacheDir)
                try {
                    val before = editor.state()
                    editor.captureProjectSnapshot().use { snapshot ->
                        NativeImageIO.exportTiffSourceLayers(context, Uri.fromFile(exportedTiff), snapshot, editor.retainedMemory().totalBytes)
                    }
                    check(editor.state() == before) { "TIFF export changed document state" }
                    NativeImageIO.decode(context, Uri.fromFile(exportedTiff)).use { roundTrip ->
                        check(roundTrip.tiffPages.single().sourceBits == 8) { "TIFF export changed the 8-bit source precision" }
                        check(roundTrip.width == width && roundTrip.height == height)
                    }
                } finally { exportedTiff.delete() }
                editor.undo(false); check(editor.state().layers.isEmpty())
                editor.undo(true); check(editor.state().layers.size == 1)
                return original
            }
        }
        if (wanted("tiff")) {
        val tiffPixels = nativeTiffPixels(tiff, 2, 1)
        check((tiffPixels[0].toInt() and 255) == 128 && (tiffPixels[1].toInt() and 255) == 0 &&
            (tiffPixels[2].toInt() and 255) == 0 && (tiffPixels[3].toInt() and 255) == 128) {
            "TIFF RGBA samples were not imported as premultiplied RGBA: ${tiffPixels.contentToString()}"
        }
        writeRgbaTiff(rotatedTiff, orientation = 6)
        val rotatedPixels = nativeTiffPixels(rotatedTiff, 1, 2)
        check((rotatedPixels[4].toInt() and 255) == 0 && (rotatedPixels[5].toInt() and 255) == 255 &&
            (rotatedPixels[6].toInt() and 255) == 0 && (rotatedPixels[7].toInt() and 255) == 255) {
            "TIFF orientation changed pixel order"
        }
        }
        var heicStatus = "HEIC phase deferred"
        if (wanted("heic")) {
        heicStatus = "HEIC encoder unavailable"
        val heic = File.createTempFile("compositor-heic-test-", ".heic", context.cacheDir)
        try {
            val heicBitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
            try {
                heicBitmap.eraseColor(Color.rgb(12, 34, 56))
                // The HEIC enum is absent from some compile SDK stubs even though the platform encoder exists.
                // Resolve it by name so this smoke remains source-compatible across the supported SDK range.
                val heicFormat = runCatching {
                    java.lang.Enum.valueOf(Bitmap.CompressFormat::class.java, "HEIC")
                }.getOrNull()
                val encoded = heicFormat?.let { format -> heic.outputStream().use { heicBitmap.compress(format, 100, it) } } == true
                if (encoded) {
                    decode(Uri.fromFile(heic)).use { decodedHeic ->
                        check(decodedHeic.width == 4 && decodedHeic.height == 3) { "HEIC dimensions changed" }
                        heicStatus = "HEIC decoded ${decodedHeic.width}x${decodedHeic.height}"
                    }
                }
            } finally { heicBitmap.recycle() }
        } finally { heic.delete() }
        }
        stage("assertions-complete")
        val results = mutableListOf<String>()
        if (wanted("png")) results.add("PNG alpha/channel/transparent pixels, undo, group desktop golden and immutable export across edits/document close passed")
        if (wanted("jpeg")) results.add("JPEG white matte/alpha/snapshot/revision passed")
        if (wanted("deep")) results.add("PNG16, palette ColorMap16, uint12 and BigTIFF import/project/TIFF16/undo-redo precision passed")
        if (wanted("tiff")) results.add("TIFF strips/orientation/alpha/source precision/undo and 9 frozen low-bit JNI import/recovery/source-export cases passed; $tiffContract")
        if (wanted("heic")) results.add(if (heicStatus == "HEIC encoder unavailable")
            "HEIC SKIPPED: encoder unavailable; no decode sample was exercised" else heicStatus)
        return "Image IO phase=$phase: " + results.joinToString("; ")
    } finally {
        ownedImages.asReversed().forEach { it.close() }
        input.delete(); output.delete(); deep.delete(); tiff.delete(); rotatedTiff.delete()
        val after = ImageIoAdmission.ledger.snapshot()
        check(after.reservedBytes == baseline.reservedBytes && after.reservations == baseline.reservations) {
            "Image IO phase=$phase leaked resource claims: $baseline -> $after"
        }
        stage("closed")
    }
}

private fun bigTiffRoundTripSmoke(context: Context) {
    val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "compositor-bigtiff-roundtrip-").toFile()
    val baseline = ImageIoAdmission.ledger.snapshot()
    try {
        for (bigEndian in listOf(false, true)) {
            val input = File(root, if (bigEndian) "big-endian.tiff" else "little-endian.tiff")
            writeBigTiff16(input, bigEndian)
            NativeEditor(documentSize = 1, addDefaultLayers = false).use { editor ->
                val working = NativeImageIO.decode(context, Uri.fromFile(input)).use { image ->
                    check(image.width == 1 && image.height == 1 && image.tiffPages.single().sourceBits == 16)
                    check(image.sourceFirstPixel16(0).contentEquals(intArrayOf(0x1234, 0x4567, 0x789a))) {
                        "BigTIFF $bigEndian source samples changed"
                    }
                    val pixel = image.workingPixel16(0, 0, 0)
                    check(pixel[3] == 65535 && (0..2).any { pixel[it] % 257 != 0 })
                    val revision = editor.currentRevision()
                    check(image.importInto(editor, false).size == 1 && editor.currentRevision() == revision + 1)
                    pixel
                }
                editor.undo(false); check(editor.state().layers.isEmpty())
                editor.undo(true); check(editor.state().layers.size == 1)
                val project = File(root, if (bigEndian) "big.comp" else "little.comp")
                editor.captureProjectSnapshot().use { snapshot ->
                    NativeProjectWriteControl(128L * 1024 * 1024, editor.retainedMemory().totalBytes).use { control ->
                        check(snapshot.write(context, project, false, control).successful)
                    }
                }
                NativeEditor.loadProjectCandidate(context, project, 128L * 1024 * 1024, 0).use { restored ->
                    val output = File(root, if (bigEndian) "big-output.tiff" else "little-output.tiff")
                    restored.captureProjectSnapshot().use { snapshot ->
                        NativeImageIO.exportTiffSourceLayers(context, Uri.fromFile(output), snapshot, restored.retainedMemory().totalBytes)
                    }
                    NativeImageIO.decode(context, Uri.fromFile(output)).use { image ->
                        check(image.tiffPages.single().sourceBits == 16 && image.sourceFirstPixel16(0).contentEquals(working)) {
                            "BigTIFF $bigEndian project/TIFF16 roundtrip lost working samples"
                        }
                    }
                }
            }
            val malformed = File(root, if (bigEndian) "big-truncated.tiff" else "little-truncated.tiff")
            malformed.writeBytes(input.readBytes().copyOf(16))
            check(runCatching { NativeImageIO.decode(context, Uri.fromFile(malformed)).close() }.isFailure) {
                "Truncated BigTIFF $bigEndian was accepted"
            }
        }
        check(ImageIoAdmission.ledger.snapshot().let {
            it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations
        }) { "BigTIFF import/export leaked its resource claim" }
    } finally { root.deleteRecursively() }
}

private fun uint12TiffRoundTripSmoke(context: Context) {
    val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "compositor-uint12-roundtrip-").toFile()
    val baseline = ImageIoAdmission.ledger.snapshot()
    try {
        val input = File(root, "uint12.tiff")
        writeUint12Tiff(input)
        NativeEditor(documentSize = 1, addDefaultLayers = false).use { editor ->
            val working = NativeImageIO.decode(context, Uri.fromFile(input)).use { image ->
                check(image.width == 1 && image.height == 1 && image.tiffPages.single().sourceBits == 16)
                // Actual ImageIO expands source 0x089/0x456/0x789 to these 16-bit
                // samples. No intermediate 8-bit pixels are permitted here.
                check(image.sourceFirstPixel16(0).contentEquals(intArrayOf(0x0891, 0x4564, 0x7897))) {
                    "Unsigned12 TIFF did not preserve all source bits"
                }
                val pixel = image.workingPixel16(0, 0, 0)
                check(pixel[3] == 65535 && (0..2).any { pixel[it] % 257 != 0 })
                val before = editor.state().revision
                check(image.importInto(editor, false).size == 1 && editor.state().revision == before + 1)
                pixel
            }
            editor.undo(false); check(editor.state().layers.isEmpty())
            editor.undo(true); check(editor.state().layers.size == 1)
            val project = File(root, "uint12.comp")
            editor.captureProjectSnapshot().use { snapshot ->
                NativeProjectWriteControl(128L * 1024 * 1024, editor.retainedMemory().totalBytes).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                }
            }
            NativeEditor.loadProjectCandidate(context, project, 128L * 1024 * 1024, 0).use { restored ->
                val output = File(root, "uint12-rgb16.tiff")
                restored.captureProjectSnapshot().use { snapshot ->
                    NativeImageIO.exportTiffSourceLayers(context, Uri.fromFile(output), snapshot, restored.retainedMemory().totalBytes)
                }
                NativeImageIO.decode(context, Uri.fromFile(output)).use { image ->
                    check(image.tiffPages.single().sourceBits == 16 && image.sourceFirstPixel16(0).contentEquals(working)) {
                        "Unsigned12 TIFF/project/TIFF16 roundtrip changed stored working samples"
                    }
                }
            }
        }
        check(ImageIoAdmission.ledger.snapshot().let {
            it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations
        }) { "Unsigned12 TIFF import/export leaked its resource claim" }
    } finally { root.deleteRecursively() }
}

private fun paletteTiffRoundTripSmoke(context: Context) {
    val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "compositor-palette-roundtrip-").toFile()
    val baseline = ImageIoAdmission.ledger.snapshot()
    try {
        val input = File(root, "palette8.tiff")
        writePaletteTiff(input)
        NativeEditor(documentSize = 1, addDefaultLayers = false).use { editor ->
            val working = NativeImageIO.decode(context, Uri.fromFile(input)).use { image ->
                // TIFF ColorMap entries are 16-bit even when their indices are 8-bit.
                // This preserves the source colors; Mac ImageIO quantizes its palette
                // table to 8-bit, so this check does not claim desktop pixel parity.
                check(image.width == 1 && image.height == 1)
                check(image.tiffPages.single().sourceBits == 16)
                check(image.sourceFirstPixel16(0).contentEquals(intArrayOf(13, 824, 1635))) {
                    "Palette TIFF ColorMap was truncated to 8-bit"
                }
                val pixel = image.workingPixel16(0, 0, 0)
                check(pixel[3] == 65535 && (0..2).any { pixel[it] % 257 != 0 }) {
                    "Palette TIFF working pixels lost their low bits"
                }
                check(image.importInto(editor, false).size == 1)
                pixel
            }
            editor.undo(false); check(editor.state().layers.isEmpty())
            editor.undo(true); check(editor.state().layers.size == 1)
            val project = File(root, "palette.comp")
            editor.captureProjectSnapshot().use { snapshot ->
                NativeProjectWriteControl(128L * 1024 * 1024, editor.retainedMemory().totalBytes).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                }
            }
            NativeEditor.loadProjectCandidate(context, project, 128L * 1024 * 1024, 0).use { restored ->
                val output = File(root, "palette-rgb16.tiff")
                restored.captureProjectSnapshot().use { snapshot ->
                    NativeImageIO.exportTiffSourceLayers(context, Uri.fromFile(output), snapshot, restored.retainedMemory().totalBytes)
                }
                NativeImageIO.decode(context, Uri.fromFile(output)).use { image ->
                    check(image.tiffPages.single().sourceBits == 16)
                    check(image.sourceFirstPixel16(0).contentEquals(working)) {
                        "Palette TIFF/project/TIFF16 roundtrip changed stored working samples"
                    }
                }
            }
        }
        check(ImageIoAdmission.ledger.snapshot().let {
            it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations
        }) { "Palette TIFF import/export leaked its resource claim" }
    } finally { root.deleteRecursively() }
}

private fun png16RoundTripSmoke(context: Context, input: File) {
    val expected = intArrayOf(0x1234, 0x4567, 0x787a, 65535)
    val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "compositor-png16-roundtrip-").toFile()
    val baseline = ImageIoAdmission.ledger.snapshot()
    try {
        NativeEditor(documentSize = 1, addDefaultLayers = false).use { editor ->
            NativeImageIO.decode(context, Uri.fromFile(input)).use { image ->
                check(image.tiffPages.single().sourceBits == 16)
                check(image.workingPixel16(0, 0, 0).contentEquals(expected)) { "PNG16 import lost low bits" }
                check(image.importInto(editor, false).size == 1)
            }
            editor.undo(false); check(editor.state().layers.isEmpty())
            editor.undo(true); check(editor.state().layers.size == 1)
            val project = File(root, "deep.comp")
            editor.captureProjectSnapshot().use { snapshot ->
                NativeProjectWriteControl(128L * 1024 * 1024, editor.retainedMemory().totalBytes).use { control ->
                    check(snapshot.write(context, project, false, control).successful)
                }
            }
            NativeEditor.loadProjectCandidate(context, project, 128L * 1024 * 1024, 0).use { restored ->
                val tiff = File(root, "deep.tiff")
                restored.captureProjectSnapshot().use { snapshot ->
                    NativeImageIO.exportTiffSourceLayers(context, Uri.fromFile(tiff), snapshot, restored.retainedMemory().totalBytes)
                }
                NativeImageIO.decode(context, Uri.fromFile(tiff)).use { image ->
                    check(image.tiffPages.single().sourceBits == 16)
                    val actual = image.sourceFirstPixel16(0)
                    check(actual.contentEquals(expected)) { "PNG16/project/TIFF16 source roundtrip lost low bits: ${actual.contentToString()}" }
                    // Re-reading the serialized sRGB ICC introduces a small LCMS conversion
                    // delta; storage precision is checked separately against raw TIFF samples.
                    val working = image.workingPixel16(0, 0, 0)
                    check(expected.indices.all { abs(working[it] - expected[it]) <= 4 })
                }
            }
        }
        // A valid header followed by a bad IDAT CRC must fail and release the same lease.
        val broken = File(root, "crc.png")
        broken.writeBytes(input.readBytes().also { it[45] = (it[45].toInt() xor 1).toByte() })
        check(runCatching { NativeImageIO.decode(context, Uri.fromFile(broken)).close() }.isFailure)
        val after = ImageIoAdmission.ledger.snapshot()
        check(after.reservedBytes == baseline.reservedBytes && after.reservations == baseline.reservations)
        check(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("compositor-png16-import-") })
    } finally { root.deleteRecursively() }
}

private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.capacity()).also {
    buffer.duplicate().apply { clear(); get(it) }
}

internal fun write16BitPng(file: File) {
    val png = ByteArrayOutputStream()
    DataOutputStream(png).use { output ->
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size); output.write(name); output.write(data)
            val crc = CRC32().apply { update(name); update(data) }
            output.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(1); it.writeInt(1); it.write(byteArrayOf(16, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(byteArrayOf(0, 0x12, 0x34, 0x45, 0x67, 0x78, 0x7a, -1, -1)) }
        chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
    }
    file.writeBytes(png.toByteArray())
}

internal fun writeRgbaTiff(file: File, orientation: Int = 1) {
    // Little-endian, one uncompressed 8-bit RGBA strip.  The first pixel is unassociated
    // half-alpha red, which verifies both channel order and the mobile premultiplication rule.
    val ifdOffset = 8
    val entryCount = 12
    val bitsOffset = ifdOffset + 2 + entryCount * 12 + 4
    val pixelsOffset = bitsOffset + 8
    val bytes = ByteArray(pixelsOffset + 8)
    fun put16(offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte() }
    fun put32(offset: Int, value: Int) {
        put16(offset, value); put16(offset + 2, value ushr 16)
    }
    bytes[0] = 'I'.code.toByte(); bytes[1] = 'I'.code.toByte(); put16(2, 42); put32(4, ifdOffset)
    put16(ifdOffset, entryCount)
    fun entry(index: Int, tag: Int, type: Int, count: Int, value: Int) {
        val offset = ifdOffset + 2 + index * 12
        put16(offset, tag); put16(offset + 2, type); put32(offset + 4, count); put32(offset + 8, value)
    }
    entry(0, 256, 4, 1, 2); entry(1, 257, 4, 1, 1)
    entry(2, 258, 3, 4, bitsOffset); entry(3, 259, 3, 1, 1)
    entry(4, 262, 3, 1, 2); entry(5, 273, 4, 1, pixelsOffset)
    entry(6, 274, 3, 1, orientation); entry(7, 277, 3, 1, 4); entry(8, 278, 4, 1, 1)
    entry(9, 279, 4, 1, 8); entry(10, 284, 3, 1, 1); entry(11, 338, 3, 1, 2)
    put32(ifdOffset + 2 + entryCount * 12, 0)
    for (index in 0 until 4) put16(bitsOffset + index * 2, 8)
    bytes[pixelsOffset] = 255.toByte(); bytes[pixelsOffset + 1] = 0; bytes[pixelsOffset + 2] = 0; bytes[pixelsOffset + 3] = 128.toByte()
    bytes[pixelsOffset + 4] = 0; bytes[pixelsOffset + 5] = 255.toByte(); bytes[pixelsOffset + 6] = 0; bytes[pixelsOffset + 7] = 255.toByte()
    file.writeBytes(bytes)
}

private fun writePaletteTiff(file: File) {
    // Independent classic TIFF producer, one pixel with an 8-bit index into a
    // complete three-channel ColorMap. None of these first colors is 8-bit exact.
    val entryCount = 11
    val mapOffset = 8 + 2 + entryCount * 12 + 4
    val pixelOffset = mapOffset + 3 * 256 * 2
    val bytes = ByteArray(pixelOffset + 1)
    fun put16(offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte() }
    fun put32(offset: Int, value: Int) { put16(offset, value); put16(offset + 2, value ushr 16) }
    bytes[0] = 'I'.code.toByte(); bytes[1] = 'I'.code.toByte(); put16(2, 42); put32(4, 8)
    put16(8, entryCount)
    fun entry(index: Int, tag: Int, type: Int, count: Int, value: Int) {
        val offset = 10 + index * 12
        put16(offset, tag); put16(offset + 2, type); put32(offset + 4, count); put32(offset + 8, value)
    }
    entry(0, 256, 4, 1, 1); entry(1, 257, 4, 1, 1); entry(2, 258, 3, 1, 8)
    entry(3, 259, 3, 1, 1); entry(4, 262, 3, 1, 3); entry(5, 273, 4, 1, pixelOffset)
    entry(6, 274, 3, 1, 1); entry(7, 277, 3, 1, 1); entry(8, 278, 4, 1, 1)
    entry(9, 279, 4, 1, 1); entry(10, 320, 3, 3 * 256, mapOffset)
    for (channel in 0..2) for (index in 0 until 256) {
        put16(mapOffset + (channel * 256 + index) * 2, (index * 251 + channel * 811 + 13) and 65535)
    }
    file.writeBytes(bytes)
}

private fun writeUint12Tiff(file: File) {
    // Independently authored TIFF: three unsigned12 samples, MSB-first, with
    // four padding bits at the end of its single odd-length packed row.
    val entryCount = 11
    val bitsOffset = 8 + 2 + entryCount * 12 + 4
    val pixelOffset = bitsOffset + 6
    val bytes = ByteArray(pixelOffset + 5)
    fun put16(offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte() }
    fun put32(offset: Int, value: Int) { put16(offset, value); put16(offset + 2, value ushr 16) }
    bytes[0] = 'I'.code.toByte(); bytes[1] = 'I'.code.toByte(); put16(2, 42); put32(4, 8)
    put16(8, entryCount)
    fun entry(index: Int, tag: Int, type: Int, count: Int, value: Int) {
        val offset = 10 + index * 12
        put16(offset, tag); put16(offset + 2, type); put32(offset + 4, count); put32(offset + 8, value)
    }
    entry(0, 256, 4, 1, 1); entry(1, 257, 4, 1, 1); entry(2, 258, 3, 3, bitsOffset)
    entry(3, 259, 3, 1, 1); entry(4, 262, 3, 1, 2); entry(5, 273, 4, 1, pixelOffset)
    entry(6, 274, 3, 1, 1); entry(7, 277, 3, 1, 3); entry(8, 278, 4, 1, 1)
    entry(9, 279, 4, 1, 5); entry(10, 284, 3, 1, 1)
    for (channel in 0..2) put16(bitsOffset + channel * 2, 12)
    byteArrayOf(0x08, 0x94.toByte(), 0x56, 0x78, 0x90.toByte()).copyInto(bytes, pixelOffset)
    file.writeBytes(bytes)
}

/** Lossy readback is sampled inside uniform blocks, away from chroma/quantization boundaries. */
private fun jpegExportSmoke(context: Context) {
    val file = File.createTempFile("compositor-jpeg-test-", ".jpg", context.cacheDir)
    val beforeLedger = ImageIoAdmission.ledger.snapshot()
    val beforeBuffers = ImagePixelBuffer.liveBytes
    fun verify(background: IntArray = intArrayOf(255, 255, 255)) {
        val header = file.inputStream().use { byteArrayOf(it.read().toByte(), it.read().toByte()) }
        check(header.contentEquals(byteArrayOf(-1, -40))) { "JPEG exporter did not write a JPEG" }
        NativeImageIO.decode(context, Uri.fromFile(file)).use { image ->
            check(image.width == 48 && image.height == 48) { "JPEG export dimensions changed" }
            val rgba = bytes(image.pixels)
            val half = intArrayOf(128 + (background[0] * 127 + 127) / 255,
                (background[1] * 127 + 127) / 255, (background[2] * 127 + 127) / 255)
            for ((x, expected) in listOf(8 to background, 24 to half, 40 to intArrayOf(0, 0, 255))) {
                val offset = (24 * 48 + x) * 4
                for (channel in 0..2) check(abs((rgba[offset + channel].toInt() and 255) - expected[channel]) <= 4) {
                    "JPEG background/alpha differs at x=$x channel=$channel"
                }
                check((rgba[offset + 3].toInt() and 255) == 255) { "JPEG readback is not opaque" }
            }
        }
    }
    try {
        NativeEditor(48, addDefaultLayers = false).use { editor ->
            ImagePixelBuffer(48 * 48 * 4).use { storage ->
                for (y in 0 until 48) for (x in 0 until 48) storage.buffer.put(when {
                    x < 16 -> byteArrayOf(0, 0, 0, 0)
                    x < 32 -> byteArrayOf(-128, 0, 0, -128)
                    else -> byteArrayOf(0, 0, -1, -1)
                })
                storage.buffer.rewind()
                editor.importPixels(48, 48, storage.buffer)
            }
            val before = editor.state()
            checkNotNull(editor.captureScene()).use { scene ->
                NativeImageIO.exportJpeg(context, Uri.fromFile(file), scene)
                verify()
                NativeImageIO.exportJpeg(context, Uri.fromFile(file), scene,
                    options = JpegExportOptions(quality = 100, backgroundRgb = 0x00ff00))
                verify(intArrayOf(0, 255, 0))
                check(runCatching { JpegExportOptions(quality = 101) }.isFailure)
                check(runCatching { JpegExportOptions(backgroundRgb = -1) }.isFailure)
                check(editor.state() == before) { "JPEG export changed document revision/history" }
                editor.setAppearanceForTest(before.layers.single().copy(visible = false))
                editor.close()
                NativeImageIO.exportJpeg(context, Uri.fromFile(file), scene)
                verify()
                check(scene.metadata()[0] == before.revision) { "JPEG export changed its captured scene" }
            }
        }
    } finally { file.delete() }
    check(ImagePixelBuffer.liveBytes == beforeBuffers)
    check(ImageIoAdmission.ledger.snapshot().let {
        it.reservations == beforeLedger.reservations && it.reservedBytes == beforeLedger.reservedBytes
    }) { "JPEG export leaked its resource claim" }
}
