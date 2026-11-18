package com.pojia.compositor.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** The synchronous native import must finish inside use; pixels must never escape the IO owner. */
internal class ImportedImage(val width: Int, val height: Int, private val storage: ImagePixelBuffer?,
    private val reservation: ResourceBudgetLedger.Reservation,
    private val tiff: NativeTiffCodec.Prepared? = null) : AutoCloseable {
    val pixels: ByteBuffer get() = checkNotNull(storage) { "TIFF pages require the native deep import" }.buffer
    val tiffPages: List<NativeTiffCodec.PageInfo> get() = tiff?.pages.orEmpty()
    internal fun sourceFirstPixel16(page: Int): IntArray = checkNotNull(tiff).sourceFirstPixel16(page)
    internal fun workingPixel16(page: Int, x: Int, y: Int): IntArray = checkNotNull(tiff).workingPixel16(page, x, y)
    fun importInto(editor: NativeEditor, fit: Boolean): LongArray = tiff?.let { editor.importTiff(it, fit) }
        ?: longArrayOf(editor.importPixels(width, height, pixels, fit))
    override fun close() {
        tiff?.close()
        storage?.close()
        reservation.close()
    }
}

/** Copies only RGBA pixels; destination padding stays untouched and source positions are preserved. */
internal fun copyImageRows(source: ByteBuffer, sourceRowBytes: Int, destination: ByteBuffer,
    destinationRowBytes: Int, width: Int, height: Int) {
    require(width > 0 && height > 0)
    val rowBytes = Math.toIntExact(ImageMemoryBudget.rgba8RowBytes(width))
    require(sourceRowBytes >= rowBytes && destinationRowBytes >= rowBytes)
    require(source.capacity().toLong() >= sourceRowBytes.toLong() * height &&
        destination.capacity().toLong() >= destinationRowBytes.toLong() * height)
    val input = source.duplicate().apply { clear() }
    val output = destination.duplicate().apply { clear() }
    for (row in 0 until height) {
        input.limit(input.capacity())
        input.position(row * sourceRowBytes)
        input.limit(row * sourceRowBytes + rowBytes)
        output.position(row * destinationRowBytes)
        output.put(input)
    }
}

/** Platform codecs run on the workspace IO worker; source pixels remain at their original size. */
internal object NativeImageIO {
    private const val MAX_PIXELS = ImageExportSize.MAX_PIXELS
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun decode(context: Context, uri: Uri, residentBytes: Long = 0,
        ledger: ResourceBudgetLedger = ImageIoAdmission.ledger,
        nativeMetadataBytes: Long = ImageIoAdmission.NATIVE_METADATA_ALLOWANCE): ImportedImage {
        require(residentBytes >= 0 && nativeMetadataBytes >= 0)
        val header = ByteArray(26)
        val count = context.contentResolver.openInputStream(uri)?.use { input ->
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read <= 0) break
                count += read
            }
            count
        } ?: error("The selected image could not be opened")
        if (count >= 25 && pngSignature.indices.all { header[it] == pngSignature[it] }) {
            if ((header[24].toInt() and 255) == 16)
                return decodePng16(context, uri, header, residentBytes, ledger, nativeMetadataBytes)
        }
        val littleTiff = count >= 4 && (header[0].toInt() and 255) == 'I'.code && (header[1].toInt() and 255) == 'I'.code &&
            (header[2].toInt() and 255) in 42..43 && (header[3].toInt() and 255) == 0
        val bigEndianTiff = count >= 4 && (header[0].toInt() and 255) == 'M'.code && (header[1].toInt() and 255) == 'M'.code &&
            (header[2].toInt() and 255) == 0 && (header[3].toInt() and 255) in 42..43
        if (littleTiff || bigEndianTiff) {
            return decodeTiff(context, uri, residentBytes, ledger, nativeMetadataBytes)
        }
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        var reservation: ResourceBudgetLedger.Reservation? = null
        var bitmap: Bitmap? = null
        var transfer: ImagePixelBuffer? = null
        var packed: ImagePixelBuffer? = null
        try {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                check(info.mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif")) {
                    "This development version imports PNG, JPEG, WebP, HEIC, and HEIF images"
                }
                check(info.size.width in 1..30000 && info.size.height in 1..30000 &&
                    info.size.width.toLong() * info.size.height <= MAX_PIXELS) {
                    "This development version imports images up to 16 million pixels"
                }
                check(info.colorSpace == null || info.colorSpace == srgb) {
                    "Wide color image import requires color management support"
                }
                check(reservation == null) { "Image header was delivered more than once" }
                val nativeBytes = Math.addExact(ImageMemoryBudget.tiledRasterBytes8Bit(info.size.width, info.size.height),
                    nativeMetadataBytes)
                val estimate = ImageMemoryBudget.estimatedImport8Bit(info.size.width, info.size.height,
                    ImageIoAdmission.CODEC_ALLOWANCE, nativeBytes)
                reservation = ImageIoAdmission.reserve(context, ledger, estimate.peakBytes, residentBytes, "image import")
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetColorSpace(srgb)
            }
            bitmap = decoded
            check(decoded.config == Bitmap.Config.ARGB_8888 && (!decoded.hasAlpha() || decoded.isPremultiplied)) {
                "The decoded image requires an unsupported pixel format"
            }
            check(Build.VERSION.SDK_INT < 34 || !decoded.hasGainmap()) { "HDR image import requires high precision support" }
            val nativeBytes = Math.addExact(ImageMemoryBudget.tiledRasterBytes8Bit(decoded.width, decoded.height),
                nativeMetadataBytes)
            val estimate = ImageMemoryBudget.import8Bit(decoded.width, decoded.height, decoded.rowBytes.toLong(),
                ImageIoAdmission.CODEC_ALLOWANCE, nativeBytes)
            ImageIoAdmission.resize(context, checkNotNull(reservation), estimate.peakBytes, residentBytes)
            // Software ARGB_8888 storage on Android is premultiplied RGBA bytes.
            transfer = ImagePixelBuffer(Math.toIntExact(Math.multiplyExact(decoded.rowBytes.toLong(), decoded.height.toLong())))
            val pixels = transfer.buffer
            decoded.copyPixelsToBuffer(pixels)
            pixels.rewind()
            if (decoded.rowBytes == decoded.width * 4) {
                val image = ImportedImage(decoded.width, decoded.height, transfer, checkNotNull(reservation))
                transfer = null; reservation = null
                return image
            }
            packed = ImagePixelBuffer(Math.toIntExact(Math.multiplyExact(decoded.width.toLong() * 4, decoded.height.toLong())))
            val output = packed.buffer
            copyImageRows(pixels, decoded.rowBytes, output, decoded.width * 4, decoded.width, decoded.height)
            output.rewind()
            val image = ImportedImage(decoded.width, decoded.height, packed, checkNotNull(reservation))
            packed = null; reservation = null
            return image
        } finally {
            bitmap?.recycle()
            transfer?.close()
            packed?.close()
            reservation?.close()
        }
    }

    private fun decodePng16(context: Context, uri: Uri, header: ByteArray, residentBytes: Long,
        ledger: ResourceBudgetLedger, nativeMetadataBytes: Long): ImportedImage {
        fun dimension(offset: Int): Int = ByteBuffer.wrap(header, offset, 4).int
        val width = dimension(16); val height = dimension(20)
        check(width in 1..30000 && height in 1..30000 && width.toLong() * height <= MAX_PIXELS) {
            "This development version imports images up to 16 million pixels"
        }
        // Wrap the untouched PNG in a private one-layer package. This delegates complete
        // CRC/chunk/color-profile validation and PNG16 decoding to the shared project codec.
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "compositor-png16-import-").toFile()
        var reservation: ResourceBudgetLedger.Reservation? = null
        var prepared: NativeTiffCodec.Prepared? = null
        try {
            reservation = ImageIoAdmission.reserveAvailable(context, ledger, "PNG16 native deep import")
            val claim = checkNotNull(reservation)
            val available = claim.bytes - residentBytes - nativeMetadataBytes
            val minimum = width.toLong() * height * 8 + 1024 * 1024
            if (available <= minimum) throw ResourceBudgetExceededException(minimum + 1, available.coerceAtLeast(0), "PNG16 native deep import")
            val images = File(directory, "images"); check(images.mkdir())
            val id = "AB000000-0000-4000-8000-000000000001"
            copyBoundedImage(context, uri, File(images, "$id.png"), "PNG16")
            File(directory, "manifest.json").writeText("""{"format":"com.compositor.project","version":13,"colorSpace":"sRGB","documentID":"AB000000-0000-4000-8000-000000000000","width":$width,"height":$height,"layers":[{"id":"$id","name":"PNG","isVisible":true,"imageFile":"$id.png","opacity":1,"blendMode":"Normal","transform":{"origin":[0,0],"size":[$width,$height],"rotation":0,"flipX":false,"flipY":false,"sampling":"Nearest"}}]}""")
            prepared = NativeTiffCodec.preparePngPackage(directory, available)
            claim.resize(Math.addExact(Math.addExact(residentBytes, nativeMetadataBytes), checkNotNull(prepared).retainedBytes))
            val first = checkNotNull(prepared).pages.single()
            val image = ImportedImage(first.width, first.height, null, claim, prepared)
            prepared = null; reservation = null
            return image
        } finally {
            prepared?.close(); reservation?.close(); directory.deleteRecursively()
        }
    }

    private fun copyBoundedImage(context: Context, uri: Uri, destination: File, format: String) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                val chunk = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (read == 0) continue
                    size = Math.addExact(size, read.toLong())
                    check(size <= TiffDecoder.MAX_ENCODED_BYTES) { "$format input exceeds the 64 MB mobile limit" }
                    output.write(chunk, 0, read)
                }
            }
        } ?: error("The selected image could not be opened")
    }

    private fun decodeTiff(context: Context, uri: Uri, residentBytes: Long,
        ledger: ResourceBudgetLedger, nativeMetadataBytes: Long): ImportedImage {
        // Stage a bounded stream on disk so a malicious provider cannot allocate its claimed
        // encoded length before admission. The native decoder uses a bounded admitted working set.
        val staging = File.createTempFile("compositor-tiff-import-", ".tiff", context.cacheDir)
        var reservation: ResourceBudgetLedger.Reservation? = null
        var prepared: NativeTiffCodec.Prepared? = null
        try {
            reservation = ImageIoAdmission.reserveAvailable(context, ledger, "TIFF native deep import")
            val claim = checkNotNull(reservation)
            val available = claim.bytes - residentBytes - nativeMetadataBytes
            val overhead = 32L * 1024 * 1024
            if (available <= overhead) throw ResourceBudgetExceededException(overhead + 1, available.coerceAtLeast(0), "TIFF native deep import")
            copyBoundedImage(context, uri, staging, "TIFF")
            if (available <= overhead + staging.length())
                throw ResourceBudgetExceededException(overhead + staging.length() + 1, available, "TIFF native deep import")
            prepared = NativeTiffCodec.prepare(staging, available)
            claim.resize(Math.addExact(Math.addExact(residentBytes, nativeMetadataBytes), checkNotNull(prepared).retainedBytes))
            val first = checkNotNull(prepared).pages.first()
            val image = ImportedImage(first.width, first.height, null, claim, prepared)
            prepared = null
            reservation = null
            return image
        } finally {
            prepared?.close()
            reservation?.close()
            staging.delete()
        }
    }

    /** Renders the immutable canvas to one RGBA8 TIFF page with sRGB, alpha and DPI.
     * Encoding is cancellable; provider writing begins only after beforeWrite accepts it. */
    fun exportTiffFlattened(context: Context, uri: Uri, scene: NativeScene, resolution: Double,
        residentBytes: Long = 0, ledger: ResourceBudgetLedger = scene.resourceLedger,
        size: ImageExportSize? = null, checkCancelled: () -> Unit = {}, beforeWrite: () -> Unit = {}) {
        require(residentBytes >= 0 && resolution.isFinite() && resolution in 1.0..9600.0)
        checkCancelled()
        val metadata = scene.metadata()
        val width = size?.width ?: Math.toIntExact(metadata[1])
        val height = size?.height ?: Math.toIntExact(metadata[2])
        check(ImageExportSize.isSupported(width, height)) {
            "This development version exports up to 16 million pixels"
        }
        val peakBytes = scene.estimateFlattenedTiffMemory(resolution, size)
        ImageIoAdmission.reserve(context, ledger, peakBytes, residentBytes, "flattened TIFF export").use {
            checkCancelled()
            val staging = File.createTempFile("compositor-tiff-flattened-", ".tiff", context.cacheDir)
            try {
                scene.writeFlattenedTiff(staging, resolution, peakBytes, checkCancelled, size)
                checkCancelled()
                beforeWrite()
                // The owner has entered WRITING. Providers cannot promise rollback after open.
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        staging.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        output.flush()
                    } ?: error("The TIFF destination could not be opened")
                } catch (failure: Exception) {
                    throw IllegalStateException("TIFF export failed; the destination may contain incomplete data", failure)
                }
            } finally { staging.delete() }
        }
    }

    /** Source layer pixels as separate pages, retaining 8/16-bit precision.
     * This deliberately does not render masks, placement, blending or effects. */
    fun exportTiffSourceLayers(context: Context, uri: Uri, snapshot: NativeProjectSnapshot, residentBytes: Long) {
        ImageIoAdmission.reserveAvailable(context, ImageIoAdmission.ledger, "TIFF source layer export").use { claim ->
            val available = claim.bytes - residentBytes - ImageIoAdmission.NATIVE_METADATA_ALLOWANCE
            check(available > 0) { "TIFF export exceeds available memory" }
            val staging = File.createTempFile("compositor-tiff-export-", ".tiff", context.cacheDir)
            try {
                snapshot.writeTiffSourceLayers(staging, available)
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    staging.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                    output.flush()
                } ?: error("The TIFF destination could not be opened")
            } finally { staging.delete() }
        }
    }

    /** PNG is a flattened derivative and never changes the document's saved revision. */
    fun exportPng(context: Context, uri: Uri, editor: NativeEditor) {
        val scene = editor.captureScene() ?: error("The document is closed")
        scene.use { exportPng(context, uri, it, editor.retainedMemory().totalBytes) }
    }

    /** The caller owns this immutable scene for the entire render and encode operation. */
    fun exportPng(context: Context, uri: Uri, scene: NativeScene, residentBytes: Long = 0,
        ledger: ResourceBudgetLedger = ImageIoAdmission.ledger, size: ImageExportSize? = null) {
        require(residentBytes >= 0)
        val metadata = scene.metadata()
        val width = size?.width ?: Math.toIntExact(metadata[1])
        val height = size?.height ?: Math.toIntExact(metadata[2])
        check(width in 1..30000 && height in 1..30000) { "Invalid export dimensions" }
        check(width.toLong() * height <= MAX_PIXELS) { "This development version exports up to 16 million pixels" }
        val rowBytes = ImageMemoryBudget.rgba8RowBytes(width)
        val estimatedRowBytes = ((rowBytes + 63) / 64) * 64
        val estimate = ImageMemoryBudget.export8Bit(width, height, estimatedRowBytes, ImageIoAdmission.CODEC_ALLOWANCE)
        val reservation = ImageIoAdmission.reserve(context, ledger, estimate.peakBytes, residentBytes, "PNG export")
        var transfer: ImagePixelBuffer? = null
        var bitmap: Bitmap? = null
        var staging: File? = null
        try {
            transfer = ImagePixelBuffer(Math.toIntExact(rowBytes * height))
            val pixels = transfer.buffer
            scene.draw(width, height, Viewport(0.0, 0.0, metadata[1].toDouble(), metadata[2].toDouble()), pixels)
            val encoded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = encoded
            val actual = ImageMemoryBudget.export8Bit(width, height, encoded.rowBytes.toLong(), ImageIoAdmission.CODEC_ALLOWANCE)
            ImageIoAdmission.resize(context, reservation, actual.peakBytes, residentBytes)
            val workingFile = File.createTempFile("compositor-export-", ".png", context.cacheDir)
            staging = workingFile
            pixels.rewind()
            if (encoded.rowBytes == width * 4) {
                encoded.copyPixelsFromBuffer(pixels)
            } else {
                ImagePixelBuffer(Math.toIntExact(encoded.rowBytes.toLong() * height)).use { storage ->
                    val padded = storage.buffer
                    copyImageRows(pixels, width * 4, padded, encoded.rowBytes, width, height)
                    padded.rewind()
                    encoded.copyPixelsFromBuffer(padded)
                }
            }
            FileOutputStream(workingFile).use { output ->
                check(encoded.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
                output.fd.sync()
            }
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    workingFile.inputStream().use { it.copyTo(output) }
                } ?: error("The export destination could not be opened")
            } catch (failure: Exception) {
                throw IllegalStateException("PNG export failed; the destination may contain incomplete data", failure)
            }
        } finally {
            bitmap?.recycle()
            transfer?.close()
            staging?.delete()
            reservation.close()
        }
    }

    /** JPEG follows the Mac exporter: transparent pixels are composited over the chosen background. */
    fun exportJpeg(context: Context, uri: Uri, scene: NativeScene, residentBytes: Long = 0,
        ledger: ResourceBudgetLedger = ImageIoAdmission.ledger, options: JpegExportOptions = JpegExportOptions(),
        size: ImageExportSize? = null) {
        require(residentBytes >= 0)
        val metadata = scene.metadata()
        val width = size?.width ?: Math.toIntExact(metadata[1])
        val height = size?.height ?: Math.toIntExact(metadata[2])
        check(width in 1..30000 && height in 1..30000) { "Invalid export dimensions" }
        check(width.toLong() * height <= MAX_PIXELS) { "This development version exports up to 16 million pixels" }
        val rowBytes = ImageMemoryBudget.rgba8RowBytes(width)
        val estimatedRowBytes = ((rowBytes + 63) / 64) * 64
        val estimate = ImageMemoryBudget.export8Bit(width, height, estimatedRowBytes, ImageIoAdmission.CODEC_ALLOWANCE)
        val reservation = ImageIoAdmission.reserve(context, ledger, estimate.peakBytes, residentBytes, "JPEG export")
        var transfer: ImagePixelBuffer? = null
        var bitmap: Bitmap? = null
        var staging: File? = null
        try {
            transfer = ImagePixelBuffer(Math.toIntExact(rowBytes * height))
            val pixels = transfer.buffer
            scene.draw(width, height, Viewport(0.0, 0.0, metadata[1].toDouble(), metadata[2].toDouble()), pixels)
            // NativeScene returns premultiplied RGBA bytes. Composite in place before JPEG encoding.
            val flat = pixels.duplicate().apply { clear() }
            val matteRed = options.backgroundRgb ushr 16 and 255
            val matteGreen = options.backgroundRgb ushr 8 and 255
            val matteBlue = options.backgroundRgb and 255
            while (flat.remaining() >= 4) {
                val r = flat.get().toInt() and 255
                val g = flat.get().toInt() and 255
                val b = flat.get().toInt() and 255
                val a = flat.get().toInt() and 255
                flat.position(flat.position() - 4)
                flat.put((r + (matteRed * (255 - a) + 127) / 255).coerceAtMost(255).toByte())
                flat.put((g + (matteGreen * (255 - a) + 127) / 255).coerceAtMost(255).toByte())
                flat.put((b + (matteBlue * (255 - a) + 127) / 255).coerceAtMost(255).toByte())
                flat.put(255.toByte())
            }
            val encoded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = encoded
            val actual = ImageMemoryBudget.export8Bit(width, height, encoded.rowBytes.toLong(), ImageIoAdmission.CODEC_ALLOWANCE)
            ImageIoAdmission.resize(context, reservation, actual.peakBytes, residentBytes)
            pixels.rewind()
            if (encoded.rowBytes == width * 4) {
                encoded.copyPixelsFromBuffer(pixels)
            } else {
                ImagePixelBuffer(Math.toIntExact(encoded.rowBytes.toLong() * height)).use { storage ->
                    val padded = storage.buffer
                    copyImageRows(pixels, width * 4, padded, encoded.rowBytes, width, height)
                    padded.rewind(); encoded.copyPixelsFromBuffer(padded)
                }
            }
            val workingFile = File.createTempFile("compositor-export-", ".jpg", context.cacheDir)
            staging = workingFile
            FileOutputStream(workingFile).use { output ->
                check(encoded.compress(Bitmap.CompressFormat.JPEG, options.quality, output)) { "JPEG encoding failed" }
                output.fd.sync()
            }
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    workingFile.inputStream().use { it.copyTo(output) }
                } ?: error("The export destination could not be opened")
            } catch (failure: Exception) {
                throw IllegalStateException("JPEG export failed; the destination may contain incomplete data", failure)
            }
        } finally {
            bitmap?.recycle(); transfer?.close(); staging?.delete(); reservation.close()
        }
    }
}
