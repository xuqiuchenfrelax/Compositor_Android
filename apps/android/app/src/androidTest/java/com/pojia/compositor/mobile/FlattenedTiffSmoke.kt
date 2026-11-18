package com.pojia.compositor.mobile

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** Real JNI/codec exports, with authored landmarks as well as a full scene comparison. */
internal fun flattenedTiffSmoke(context: Context): String {
    val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "p142-tiff-").toFile()
    val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
    val baseline = ImageIoAdmission.ledger.snapshot()
    val buffers = ImagePixelBuffer.liveBytes
    val output = File(root, "canvas.tiff")
    val sentinel = byteArrayOf(29, 17, 43, 59)
    fun temporaryFiles() = context.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("compositor-tiff-flattened-") }.map { it.name }.toSet()
    val stagingBefore = temporaryFiles()
    fun readback(expected: ByteArray, resolution: Double) {
        NativeImageIO.decode(context, Uri.fromFile(output)).use { decoded ->
            check(decoded.width == 8 && decoded.height == 8 && decoded.tiffPages.size == 1) {
                "Flattened TIFF did not contain one full-canvas page"
            }
            val page = decoded.tiffPages.single()
            check(page.sourceBits == 8 && page.sourceIccBytes > 0 && page.resolutionUnit == 2 &&
                kotlin.math.abs(page.xResolution - resolution) < 0.001 &&
                kotlin.math.abs(page.yResolution - resolution) < 0.001) {
                "Flattened TIFF lost its 8-bit/sRGB/DPI metadata: $page"
            }
            for (y in 0 until 8) for (x in 0 until 8) {
                val actual = decoded.workingPixel16(0, x, y)
                for (c in 0..3) check((actual[c] + 128) / 257 == (expected[(y * 8 + x) * 4 + c].toInt() and 255)) {
                    "Flattened TIFF differs from captured RGBA8 at ($x,$y), channel $c: ${actual[c]}"
                }
            }
        }
    }
    fun render(scene: NativeScene): ByteArray {
        val pixels = ByteBuffer.allocateDirect(8 * 8 * 4)
        scene.draw(8, 8, Viewport(0.0, 0.0, 8.0, 8.0), pixels)
        return ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
    }
    try {
        NativeEditor.createWithLedgerForTest(8, ledger, false).use { editor ->
            fun add(rgba: ByteArray): Long = editor.importPixels(2, 2,
                ByteBuffer.allocateDirect(16).apply { repeat(4) { put(rgba) }; rewind() })
            val red = add(byteArrayOf(64, 32, 16, -128))
            editor.setTransform(red, Transform64(1.0, 2.0, 2.0, 2.0))
            val blue = add(byteArrayOf(0, 0, -1, -1))
            editor.setTransform(blue, Transform64(5.0, 1.0, 2.0, 2.0))
            editor.createMask(blue, false)
            editor.setResolution(144.0)
            val beforeAdjustment = checkNotNull(editor.captureScene()).use(::render)
            editor.commitAdjustmentLayer(null, AdjustmentLayerModel.create(AdjustmentKind.INVERT))
            val unchanged = editor.state()
            val identity = editor.currentContentIdentity()
            val generation = editor.currentSceneGeneration()
            checkNotNull(editor.captureScene()).use { scene ->
                val expected = render(scene)
                val landmark = (2 * 8 + 1) * 4
                check((expected[landmark + 3].toInt() and 255) == 128 && expected[3] == 0.toByte()) {
                    "Fixture did not retain placed partial alpha and transparent canvas"
                }
                check((0..2).any { expected[landmark + it] != beforeAdjustment[landmark + it] }) {
                    "Fixture adjustment did not affect its composite"
                }
                check((0..3).all { expected[(1 * 8 + 5) * 4 + it] == 0.toByte() }) {
                    "Fixture mask did not hide its source pixels"
                }
                val during = ledger.snapshot()
                output.writeBytes(sentinel)
                ledger.reserve(during.availableBytes, "competing TIFF owner").use {
                    check(runCatching {
                        NativeImageIO.exportTiffFlattened(context, Uri.fromFile(output), scene, 144.0, ledger = ledger)
                    }.exceptionOrNull() is ResourceBudgetExceededException) {
                        "Flattened TIFF bypassed an occupied ledger"
                    }
                }
                check(output.readBytes().contentEquals(sentinel)) { "TIFF budget refusal opened the destination" }
                val cancelled = runCatching {
                    NativeImageIO.exportTiffFlattened(context, Uri.fromFile(output), scene, 144.0,
                        ledger = ledger, checkCancelled = { throw CancellationException("before TIFF encode") })
                }.exceptionOrNull()
                check(cancelled is CancellationException && output.readBytes().contentEquals(sentinel))
                // Two Kotlin checks, then native pre-render, post-render, encoder
                // entry and its first scanline. The sixth callback crosses JNI
                // while both native raster/codec allocations are owned.
                var nativeCancellationChecks = 0
                val nativeCancellation = CancellationException("inside native TIFF scanline")
                val rowCancelled = runCatching {
                    NativeImageIO.exportTiffFlattened(context, Uri.fromFile(output), scene, 144.0,
                        ledger = ledger, checkCancelled = {
                            nativeCancellationChecks += 1
                            if (nativeCancellationChecks == 6) throw nativeCancellation
                        })
                }.exceptionOrNull()
                check(nativeCancellationChecks == 6 && rowCancelled === nativeCancellation) {
                    "TIFF JNI cancellation did not preserve the original Kotlin exception: $rowCancelled"
                }
                check(output.readBytes().contentEquals(sentinel) && temporaryFiles() == stagingBefore) {
                    "TIFF cancellation inside native encoding touched its destination or leaked staging"
                }
                check(ledger.snapshot().let { it.reservedBytes == during.reservedBytes && it.reservations == during.reservations }) {
                    "TIFF cancellation inside native encoding leaked its budget claim"
                }
                var reachedWrite = false
                check(runCatching {
                    NativeImageIO.exportTiffFlattened(context, Uri.fromFile(output), scene, 144.0,
                        ledger = ledger, beforeWrite = { reachedWrite = true; throw CancellationException("after TIFF encode") })
                }.exceptionOrNull() is CancellationException)
                check(reachedWrite && output.readBytes().contentEquals(sentinel)) {
                    "Cancellation after encoding wrote the TIFF destination"
                }
                val missing = File(root, "missing/canvas.tiff")
                check(runCatching {
                    NativeImageIO.exportTiffFlattened(context, Uri.fromFile(missing), scene, 144.0, ledger = ledger)
                }.isFailure && !missing.exists()) { "Invalid TIFF provider unexpectedly succeeded" }
                check(ledger.snapshot().let { it.reservedBytes == during.reservedBytes && it.reservations == during.reservations }) {
                    "TIFF refusal/cancellation/provider failure leaked a claim"
                }
                check(temporaryFiles() == stagingBefore) { "TIFF failure leaked staging output" }
                NativeImageIO.exportTiffFlattened(context, Uri.fromFile(output), scene, 144.0, ledger = ledger)
                readback(expected, 144.0)
                check(editor.state() == unchanged && editor.currentContentIdentity() == identity &&
                    editor.currentSceneGeneration() == generation) { "TIFF export changed history, selection or document identity" }
                val capturedRevision = scene.metadata()[0]
                editor.setVisible(red, false)
                check(editor.currentRevision() != capturedRevision)
                checkNotNull(editor.captureScene()).use { current ->
                    check(render(current).all { it == 0.toByte() }) { "Later hidden-layer fixture was not transparent" }
                }
                editor.close()
                NativeImageIO.exportTiffFlattened(context, Uri.fromFile(output), scene, 144.0, ledger = ledger)
                readback(expected, 144.0)
                check(scene.metadata()[0] == capturedRevision) { "TIFF export mutated its captured scene" }
            }
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
        // Deep source precision cannot be silently reduced by the new RGBA8 action.
        val deepFile = File(root, "deep.png")
        write16BitPng(deepFile)
        NativeEditor(8, addDefaultLayers = false).use { editor ->
            NativeImageIO.decode(context, Uri.fromFile(deepFile)).use { it.importInto(editor, false) }
            val state = editor.state()
            checkNotNull(editor.captureScene()).use { scene ->
                output.writeBytes(sentinel)
                check(runCatching {
                    NativeImageIO.exportTiffFlattened(context, Uri.fromFile(output), scene, 72.0)
                }.isFailure) { "Flattened RGBA8 TIFF silently accepted deep source pixels" }
                check(output.readBytes().contentEquals(sentinel) && editor.state() == state)
            }
        }
        return "Flattened TIFF: full-canvas single page, placed/masked/inverted composite, exact working RGBA8, alpha/sRGB/144 DPI, occupied-budget refusal/retry, cancellation before/during/after encoding with the same Kotlin exception across JNI, provider failure, deep rejection, unchanged document/history and immutable scene after edit/close passed."
    } finally {
        root.deleteRecursively()
        check(temporaryFiles() == stagingBefore) { "Flattened TIFF leaked a staging file" }
        check(ImagePixelBuffer.liveBytes == buffers)
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
        check(ImageIoAdmission.ledger.snapshot().let { it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations }) {
            "Flattened TIFF smoke leaked a shared resource claim"
        }
    }
}
