package com.pojia.compositor.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Debug
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal fun imageBudgetSmoke(context: Context): String {
    val beforeBuffers = ImagePixelBuffer.liveBytes
    val ledger = ResourceBudgetLedger(64L * 1024 * 1024)
    val lowBudget = ResourceBudgetLedger(1)
    val input = File.createTempFile("compositor-budget-input-", ".png", context.cacheDir)
    val output = File.createTempFile("compositor-budget-output-", ".png", context.cacheDir)
    val broken = File.createTempFile("compositor-budget-broken-", ".png", context.cacheDir)
    val tiff = File.createTempFile("compositor-budget-tiff-", ".tiff", context.cacheDir)
    val sentinel = byteArrayOf(10, 20, 30, 40)
    try {
        Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            try {
                bitmap.eraseColor(Color.rgb(255, 64, 32))
                input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        val refused = runCatching { NativeImageIO.decode(context, Uri.fromFile(input), ledger = lowBudget).close() }.exceptionOrNull()
        check(generateSequence(refused) { it.cause }.any { it is ResourceBudgetExceededException }) {
            "Low-budget decode did not reject during header admission: $refused"
        }
        check(lowBudget.snapshot().reservedBytes == 0L && ImagePixelBuffer.liveBytes == beforeBuffers)

        writeRgbaTiff(tiff)
        val refusedTiff = runCatching { NativeImageIO.decode(context, Uri.fromFile(tiff), ledger = lowBudget).close() }.exceptionOrNull()
        check(refusedTiff is ResourceBudgetExceededException) { "TIFF encoded input bypassed admission: $refusedTiff" }
        check(lowBudget.snapshot().reservedBytes == 0L && ImagePixelBuffer.liveBytes == beforeBuffers)
        // Admit encoded bytes, but refuse pixel expansion before the decoder allocates RGBA.
        val headerOnly = ResourceBudgetLedger(ImageIoAdmission.CODEC_ALLOWANCE + tiff.length())
        check(runCatching { NativeImageIO.decode(context, Uri.fromFile(tiff), ledger = headerOnly).close() }
            .exceptionOrNull() is ResourceBudgetExceededException)
        check(headerOnly.snapshot().reservedBytes == 0L && ImagePixelBuffer.liveBytes == beforeBuffers)

        write16BitPng(broken)
        check(runCatching { NativeImageIO.decode(context, Uri.fromFile(broken), ledger = lowBudget).close() }
            .exceptionOrNull() is ResourceBudgetExceededException)
        check(lowBudget.snapshot().reservedBytes == 0L && ImagePixelBuffer.liveBytes == beforeBuffers)

        // A decoded deep image must retain its live pages, not the whole decode
        // envelope. Publication acquires a separate bounded claim on this ledger.
        for (deepInput in listOf(tiff, broken)) {
            val deepLedger = ResourceBudgetLedger(64L * 1024 * 1024)
            NativeEditor.createWithLedgerForTest(3, deepLedger, false).use { editor ->
                NativeImageIO.decode(context, Uri.fromFile(deepInput), ledger = deepLedger).use { decoded ->
                    check(deepLedger.snapshot().availableBytes > 0L) { "Deep decode kept its peak envelope" }
                    val unchanged = editor.state()
                    val identity = editor.currentContentIdentity()
                    deepLedger.reserve(deepLedger.snapshot().availableBytes, "competing image owner").use {
                        check(runCatching { decoded.importInto(editor, false) }.exceptionOrNull() is ResourceBudgetExceededException)
                        check(editor.state() == unchanged && editor.currentContentIdentity() == identity) {
                            "Rejected deep publication mutated the document"
                        }
                    }
                    check(decoded.importInto(editor, false).size == 1) { "Deep publication could not retry after admission" }
                    editor.undo(false)
                    check(editor.state().layers.isEmpty())
                    editor.undo(true)
                    check(editor.state().layers.size == 1)
                }
            }
            check(deepLedger.snapshot().reservedBytes == 0L && deepLedger.snapshot().reservations == 0)
        }

        val image = NativeImageIO.decode(context, Uri.fromFile(input), ledger = ledger)
        try {
            check(image.width == 3 && image.height == 2)
            check(ledger.snapshot().reservations == 1 && ledger.snapshot().reservedBytes > 0)
            val data = ByteArray(24).also { image.pixels.duplicate().get(it) }
            for (pixel in 0 until 6) check((0..3).all { channel ->
                (data[pixel * 4 + channel].toInt() and 255) == intArrayOf(255, 64, 32, 255)[channel]
            }) { "Budgeted decode changed original RGBA pixels" }
            val foreign = AtomicReference<Throwable?>()
            val thread = Thread { foreign.set(runCatching { image.pixels }.exceptionOrNull()) }
            thread.start(); thread.join(10_000)
            check(!thread.isAlive && foreign.get() is IllegalStateException)
            NativeEditor(3, addDefaultLayers = false).use { editor ->
                editor.importPixels(image.width, image.height, image.pixels)
                val state = editor.state()
                checkNotNull(editor.captureScene()).use { scene ->
                    output.writeBytes(sentinel)
                    check(runCatching { NativeImageIO.exportPng(context, Uri.fromFile(output), scene, ledger = lowBudget) }
                        .exceptionOrNull() is ResourceBudgetExceededException)
                    check(output.readBytes().contentEquals(sentinel)) { "Budget refusal touched the destination" }
                    check(runCatching { NativeImageIO.exportJpeg(context, Uri.fromFile(output), scene, ledger = lowBudget) }
                        .exceptionOrNull() is ResourceBudgetExceededException)
                    check(output.readBytes().contentEquals(sentinel)) { "JPEG budget refusal touched the destination" }
                    val during = ledger.snapshot()
                    val liveDuring = ImagePixelBuffer.liveBytes
                    val failed = runCatching { NativeImageIO.exportPng(context,
                        Uri.fromFile(File(context.cacheDir, "missing-budget-parent-${System.nanoTime()}/output.png")),
                        scene, ledger = ledger) }.exceptionOrNull()
                    check(failed?.message?.contains("incomplete data") == true)
                    check(ledger.snapshot().reservedBytes == during.reservedBytes &&
                        ledger.snapshot().reservations == during.reservations && ImagePixelBuffer.liveBytes == liveDuring)
                    val failedJpeg = runCatching { NativeImageIO.exportJpeg(context,
                        Uri.fromFile(File(context.cacheDir, "missing-budget-parent-${System.nanoTime()}/output.jpg")),
                        scene, ledger = ledger) }.exceptionOrNull()
                    check(failedJpeg?.message?.contains("incomplete data") == true)
                    check(ledger.snapshot().reservedBytes == during.reservedBytes &&
                        ledger.snapshot().reservations == during.reservations && ImagePixelBuffer.liveBytes == liveDuring)
                    NativeImageIO.exportPng(context, Uri.fromFile(output), scene, ledger = ledger)
                    NativeImageIO.decode(context, Uri.fromFile(output), ledger = ledger).use { exported ->
                        check(exported.width == 3 && exported.height == 3)
                        val rgba = ByteArray(36).also { exported.pixels.duplicate().get(it) }
                        check(rgba.take(24).toByteArray().contentEquals(data) && rgba.drop(24).all { it == 0.toByte() })
                    }
                }
                check(editor.state() == state) { "Refused or failed PNG export changed the document" }
            }
        } finally { image.close(); image.close() }
        check(runCatching { image.pixels }.exceptionOrNull() is IllegalStateException)
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0 && ImagePixelBuffer.liveBytes == beforeBuffers)

        broken.writeBytes(input.readBytes().take(33).toByteArray())
        check(runCatching { NativeImageIO.decode(context, Uri.fromFile(broken), ledger = ledger).close() }.isFailure)
        check(ledger.snapshot().reservedBytes == 0L && ImagePixelBuffer.liveBytes == beforeBuffers)
        paddedRows()
        check(ImagePixelBuffer.liveBytes == beforeBuffers)
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val highWater = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: "VmHWM unavailable"
        return "Image admission passed: PNG/TIFF admission refusal, deep decode/publication lease handoff with atomic refusal/retry, and JPEG destination protection before owned buffers/destination write; original RGBA and dimensions, " +
            "immutable export/transparent canvas; invalid codec/destination and scoped/cross-thread/double close release; " +
            "synthetic padded rows exact. Ledger peak=${ledger.snapshot().peakReservedBytes}; sampled processPssKiB=${memory.totalPss}; $highWater. " +
            "Process sampling is not a per-job peak or full unified-memory/physical-device guarantee."
    } finally { input.delete(); output.delete(); broken.delete(); tiff.delete() }
}

private fun paddedRows() {
    ImagePixelBuffer(32).use { padded -> ImagePixelBuffer(24).use { packed -> ImagePixelBuffer(40).use { roundTrip ->
        val expected = ByteArray(24) { (it + 1).toByte() }
        padded.buffer.put(ByteArray(32) { 99 })
        for (row in 0..1) {
            padded.buffer.position(row * 16)
            padded.buffer.put(expected, row * 12, 12)
        }
        padded.buffer.position(7); padded.buffer.limit(8)
        copyImageRows(padded.buffer, 16, packed.buffer, 12, 3, 2)
        check(padded.buffer.position() == 7 && padded.buffer.limit() == 8)
        check(ByteArray(24).also { packed.buffer.duplicate().get(it) }.contentEquals(expected))
        copyImageRows(packed.buffer, 12, roundTrip.buffer, 20, 3, 2)
        val actual = ByteArray(40).also { roundTrip.buffer.duplicate().get(it) }
        for (row in 0..1) {
            check(actual.copyOfRange(row * 20, row * 20 + 12).contentEquals(expected.copyOfRange(row * 12, row * 12 + 12)))
            check(actual.copyOfRange(row * 20 + 12, (row + 1) * 20).all { it == 0.toByte() })
        }
    } } }
}
