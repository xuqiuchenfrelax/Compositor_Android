package com.pojia.compositor.mobile

import java.nio.ByteOrder

/** Strict single-image TIFF subset. The caller budgets encoded input before invoking this reader. */
internal object TiffDecoder {
    const val MAX_ENCODED_BYTES = 64L * 1024 * 1024
    internal data class Decoded(val width: Int, val height: Int, val rgba: ByteArray)

    /** No pixel storage is allocated before [beforeAllocate]; its exception aborts decoding. */
    fun decode(bytes: ByteArray, beforeAllocate: (width: Int, height: Int, rgbaBytes: Long) -> Unit = { _, _, _ -> }): Decoded {
        require(bytes.size.toLong() <= MAX_ENCODED_BYTES) { "TIFF input exceeds the 64 MB mobile limit" }
        require(bytes.size >= 8) { "TIFF header is truncated" }
        val order = when {
            bytes[0] == 73.toByte() && bytes[1] == 73.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 77.toByte() && bytes[1] == 77.toByte() -> ByteOrder.BIG_ENDIAN
            else -> error("Unsupported TIFF byte order")
        }
        require(u16(bytes, 2, order) == 42) { "Unsupported TIFF variant" }
        val ifdLong = u32(bytes, 4, order)
        require(ifdLong >= 8 && ifdLong + 2 <= bytes.size) { "TIFF directory offset is invalid" }
        val ifd = ifdLong.toInt()
        val entries = u16(bytes, ifd, order)
        require(entries in 1..256) { "TIFF directory is too large or empty" }
        val directoryEnd = ifdLong + 2L + entries * 12L + 4L
        require(directoryEnd <= bytes.size) { "TIFF directory is truncated" }
        require(u32(bytes, ifd + 2 + entries * 12, order) == 0L) { "Multi-page TIFF import is not available on Android yet" }
        val metadataRanges = mutableListOf(0L until 8L, ifdLong until directoryEnd)
        val tags = HashMap<Int, Field>()
        repeat(entries) { index ->
            val cursor = ifd + 2 + index * 12
            val tag = u16(bytes, cursor, order)
            require(tag !in tags) { "TIFF directory contains duplicate tags" }
            val type = u16(bytes, cursor + 2, order)
            val count = u32(bytes, cursor + 4, order)
            require(count in 1L..1_000_000L) { "TIFF tag count is invalid" }
            val byteCount = count * typeSize(type)
            val offset = if (byteCount <= 4) (cursor + 8).toLong() else u32(bytes, cursor + 8, order)
            require(offset >= 0 && offset + byteCount <= bytes.size) { "TIFF tag payload is truncated" }
            if (byteCount > 4) metadataRanges.add(offset until offset + byteCount)
            tags[tag] = Field(type, count, offset.toInt(), order, bytes)
        }
        // These tags change image interpretation. Without a color-management/alternate-image
        // implementation, ignoring them would silently turn a different image into sRGB RGBA8.
        require(listOf(290, 291, 301, 318, 319, 320, 330, 34675, 529, 530, 531, 532).none { it in tags }) {
            "TIFF color profiles, transfer functions, or alternate images are not supported"
        }
        require(listOf(322, 323, 324, 325).none { it in tags }) { "Tiled TIFF import is not available on Android yet" }
        require((tags[254]?.scalar() ?: 0L) == 0L) { "TIFF subfile images are not supported" }
        require((tags[255]?.scalar() ?: 1L) == 1L) { "TIFF subfile images are not supported" }
        val width = tags.required(256).scalar().also { require(it in 1L..30_000L) { "Invalid TIFF width" } }.toInt()
        val height = tags.required(257).scalar().also { require(it in 1L..30_000L) { "Invalid TIFF height" } }.toInt()
        require(width.toLong() * height <= 16_777_216L) { "This development version imports up to 16 million pixels" }
        require((tags[259]?.scalar() ?: 1L) == 1L) { "Compressed TIFF import is not available on Android yet" }
        require((tags[317]?.scalar() ?: 1L) == 1L) { "TIFF predictors are not supported" }
        val photometric = tags.required(262).scalar()
        require(photometric == 1L || photometric == 2L) { "This TIFF color model is not supported" }
        val samples = (tags[277]?.scalar() ?: 1L).also {
            require(if (photometric == 1L) it == 1L else it == 3L || it == 4L) { "Unsupported TIFF channel count" }
        }.toInt()
        val bits = tags.required(258)
        require(bits.count == samples.toLong() && (0 until samples).all { bits.number(it) == 8L }) {
            "Only 8-bit TIFF samples are supported; high precision input is not reduced"
        }
        tags[339]?.let { format ->
            require(format.count == samples.toLong() && (0 until samples).all { format.number(it) == 1L }) {
                "Only unsigned integer TIFF samples are supported"
            }
        }
        require((tags[266]?.scalar() ?: 1L) == 1L) { "Reversed TIFF fill order is not supported" }
        require((tags[284]?.scalar() ?: 1L) == 1L) { "Planar TIFF import is not available on Android yet" }
        val alphaKind = if (samples == 4) tags.required(338).scalar().also {
            require(it == 1L || it == 2L) { "TIFF fourth sample must explicitly be associated or unassociated alpha" }
        } else {
            require(338 !in tags) { "Unexpected TIFF extra samples" }; 0L
        }
        val orientation = (tags[274]?.scalar() ?: 1L).also {
            require(it == 1L || it == 3L || it == 6L || it == 8L) { "This TIFF orientation is not supported" }
        }.toInt()
        // The TIFF default is UINT_MAX; one strip may legitimately declare more rows than the image.
        val rowsPerStrip = (tags[278]?.scalar() ?: 0xFFFF_FFFFL).also {
            require(it > 0) { "Invalid TIFF rows-per-strip" }
        }.coerceAtMost(height.toLong()).toInt()
        val stripCount = (height + rowsPerStrip - 1) / rowsPerStrip
        val offsets = tags.required(273)
        val counts = tags.required(279)
        require(offsets.count == stripCount.toLong() && counts.count == offsets.count) { "TIFF strip metadata is inconsistent" }
        val rowBytes = width * samples
        val stripOffsets = IntArray(stripCount)
        val stripRanges = ArrayList<LongRange>(stripCount)
        repeat(stripCount) { index ->
            val offset = offsets.number(index)
            val length = counts.number(index)
            val expected = rowBytes.toLong() * minOf(rowsPerStrip, height - index * rowsPerStrip)
            require(length == expected && offset >= 8L && offset + length <= bytes.size) { "TIFF strip extent is invalid" }
            val range = offset until offset + length
            require(metadataRanges.none { range.first <= it.last && it.first <= range.last }) { "TIFF strip overlaps metadata" }
            stripOffsets[index] = offset.toInt()
            stripRanges.add(range)
        }
        val sorted = stripRanges.sortedBy { it.first }
        require(sorted.zipWithNext().none { (left, right) -> left.last >= right.first }) { "TIFF strips overlap" }
        // Validate associated pixels before admission so malformed alpha never requests output storage.
        if (alphaKind == 1L) for (range in stripRanges) {
            var offset = range.first.toInt()
            while (offset <= range.last) {
                val alpha = bytes[offset + 3].toInt() and 255
                require((0..2).all { (bytes[offset + it].toInt() and 255) <= alpha }) { "Invalid associated TIFF alpha" }
                offset += 4
            }
        }
        val rotated = orientation == 6 || orientation == 8
        val outputWidth = if (rotated) height else width
        val outputHeight = if (rotated) width else height
        val outputSize = width.toLong() * height * 4L
        beforeAllocate(outputWidth, outputHeight, outputSize)
        val output = ByteArray(outputSize.toInt())
        for (sy in 0 until height) for (sx in 0 until width) {
            val source = stripOffsets[sy / rowsPerStrip] + (sy % rowsPerStrip) * rowBytes + sx * samples
            val alpha = if (samples == 4) bytes[source + 3].toInt() and 255 else 255
            val dx: Int
            val dy: Int
            when (orientation) {
                3 -> { dx = width - 1 - sx; dy = height - 1 - sy }
                6 -> { dx = height - 1 - sy; dy = sx }
                8 -> { dx = sy; dy = width - 1 - sx }
                else -> { dx = sx; dy = sy }
            }
            val destination = (dy * outputWidth + dx) * 4
            for (channel in 0..2) {
                val raw = bytes[source + if (samples == 1) 0 else channel].toInt() and 255
                output[destination + channel] = (if (alphaKind == 2L) (raw * alpha + 127) / 255 else raw).toByte()
            }
            output[destination + 3] = alpha.toByte()
        }
        return Decoded(outputWidth, outputHeight, output)
    }

    private data class Field(val type: Int, val count: Long, val offset: Int, val order: ByteOrder, val bytes: ByteArray) {
        fun scalar(): Long { require(count == 1L) { "TIFF scalar tag has multiple values" }; return number(0) }
        fun number(index: Int): Long {
            require(type == 3 || type == 4) { "TIFF integer tag must use SHORT or LONG" }
            require(index >= 0 && index.toLong() < count) { "TIFF tag index is invalid" }
            return if (type == 3) u16(bytes, offset + index * 2, order).toLong() else u32(bytes, offset + index * 4, order)
        }
    }
    private fun Map<Int, Field>.required(tag: Int): Field = get(tag) ?: error("TIFF tag $tag is missing")
    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> error("Unsupported TIFF field type $type")
    }
    private fun u16(bytes: ByteArray, offset: Int, order: ByteOrder): Int = if (order == ByteOrder.LITTLE_ENDIAN)
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    else ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    private fun u32(bytes: ByteArray, offset: Int, order: ByteOrder): Long = if (order == ByteOrder.LITTLE_ENDIAN)
        u16(bytes, offset, order).toLong() or (u16(bytes, offset + 2, order).toLong() shl 16)
    else (u16(bytes, offset, order).toLong() shl 16) or u16(bytes, offset + 2, order).toLong()
}
