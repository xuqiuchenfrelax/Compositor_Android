package com.pojia.compositor.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure decoder cases: runnable on a host JVM or by the Android smoke instrumentation. */
internal fun tiffDecoderSmoke(): String {
    var accepted = 0
    var rejected = 0
    val expectedRgba = intArrayOf(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255,
        128, 64, 0, 128, 0, 0, 0, 0, 255, 255, 255, 255)
    val orders = mapOf(1 to listOf(0, 1, 2, 3, 4, 5), 3 to listOf(5, 4, 3, 2, 1, 0),
        6 to listOf(3, 0, 4, 1, 5, 2), 8 to listOf(2, 5, 1, 4, 0, 3))
    for (bigEndian in listOf(false, true)) for ((orientation, pixels) in orders) for (rows in listOf(1, 2)) {
        val fixture = TiffSmokeFixture(bigEndian, orientation, rows)
        var calls = 0
        val decoded = TiffDecoder.decode(fixture.bytes) { width, height, size ->
            check(width == if (orientation in listOf(6, 8)) 2 else 3)
            check(height == if (orientation in listOf(6, 8)) 3 else 2)
            check(size == 24L); calls++
        }
        check(calls == 1)
        val expected = pixels.flatMap { expectedRgba.slice(it * 4 until it * 4 + 4) }.map { it.toByte() }.toByteArray()
        check(decoded.rgba.contentEquals(expected)) { "TIFF endian/strip/orientation mismatch" }
        accepted++
    }
    // Associated alpha must not be multiplied a second time; nonzero color under alpha=0 is invalid.
    val associated = TiffSmokeFixture(alpha = 1)
    expectedRgba.forEachIndexed { i, value -> associated.bytes[associated.pixelStart + i] = value.toByte() }
    check(TiffDecoder.decode(associated.bytes).rgba.contentEquals(expectedRgba.map { it.toByte() }.toByteArray()))
    accepted++
    for (channels in listOf(1, 3)) for (bigEndian in listOf(false, true)) {
        val fixture = TiffSmokeFixture(bigEndian = bigEndian, channels = channels)
        val decoded = TiffDecoder.decode(fixture.bytes)
        val expected = (0 until 6).flatMap { pixel ->
            if (channels == 1) listOf(pixel * 20, pixel * 20, pixel * 20, 255)
            else listOf(pixel * 20, pixel * 20 + 1, pixel * 20 + 2, 255)
        }.map { it.toByte() }.toByteArray()
        check(decoded.rgba.contentEquals(expected)); accepted++
    }
    // Valid unsigned UINT_MAX RowsPerStrip is one strip, not an integer overflow or refusal.
    val largeRows = TiffSmokeFixture(rows = 2)
    largeRows.value(278, 0xFFFF_FFFFL)
    check(TiffDecoder.decode(largeRows.bytes).rgba.contentEquals(expectedRgba.map { it.toByte() }.toByteArray())); accepted++

    fun reject(label: String, fixture: TiffSmokeFixture = TiffSmokeFixture(), mutate: (TiffSmokeFixture) -> Unit) {
        mutate(fixture)
        var admitted = false
        val result = runCatching { TiffDecoder.decode(fixture.bytes) { _, _, _ -> admitted = true } }
        check(result.isFailure && !admitted) { "TIFF accepted or admitted malformed $label" }; rejected++
    }
    reject("magic") { it.put16(2, 43) }
    reject("IFD wrap") { it.put32(4, 0xFFFF_FFFFL) }
    reject("IFD at EOF") { it.put32(4, it.bytes.size.toLong()) }
    reject("directory extent") { it.put16(8, 256) }
    reject("zero entries") { it.put16(8, 0) }
    reject("next page") { it.put32(it.nextIfd, 8) }
    reject("duplicate tag") { it.put16(it.entry(257), 256) }
    reject("zero count") { it.put32(it.entry(258) + 4, 0) }
    reject("huge count") { it.put32(it.entry(258) + 4, 0xFFFF_FFFFL) }
    reject("payload extent") { it.put32(it.entry(258) + 8, it.bytes.size.toLong() - 1) }
    reject("unknown type") { it.put16(it.entry(258) + 2, 13) }
    reject("wrong numeric type") { it.put16(it.entry(256) + 2, 11) }
    reject("multiple scalar values") { it.put32(it.entry(259) + 4, 2) }
    reject("zero width") { it.value(256, 0) }
    reject("dimension limit") { it.value(256, 30001) }
    reject("pixel budget") { it.value(256, 30000); it.value(257, 30000) }
    reject("depth") { it.put16(it.payload(258), 16) }
    reject("bit count") { it.put32(it.entry(258) + 4, 3) }
    reject("compression") { it.value(259, 5) }
    reject("photometric") { it.value(262, 5) }
    reject("RGB channel count") { it.value(277, 1) }
    reject("planar") { it.value(284, 2) }
    reject("orientation") { it.value(274, 2) }
    reject("zero strip rows") { it.value(278, 0) }
    reject("extra strip") { it.put32(it.entry(273) + 4, 3) }
    reject("missing strip") { it.put32(it.entry(273) + 4, 1) }
    reject("strip count mismatch") { it.put32(it.entry(279) + 4, 1) }
    reject("strip extent") { it.put32(it.payload(273), it.bytes.size.toLong() - 1) }
    reject("strip extent wrapping") { it.put32(it.payload(273), 0xFFFF_FFFFL) }
    reject("strip too short") { it.put32(it.payload(279), 11) }
    reject("strip too long") { it.put32(it.payload(279), 13) }
    reject("metadata overlap") { it.put32(it.payload(273), 8) }
    reject("strip overlap") { it.put32(it.payload(273) + 4, it.pixelStart.toLong()) }
    reject("unspecified alpha") { it.value(338, 0) }
    reject("unknown alpha") { it.value(338, 3) }
    reject("missing alpha") { it.put16(it.entry(338), 65001) }
    reject("associated alpha exceeds coverage", TiffSmokeFixture(alpha = 1)) { }
    reject("predictor") { it.put16(it.entry(65000), 317) }
    reject("gray response") { it.put16(it.entry(65000), 291) }
    reject("profile") { it.put16(it.entry(65000), 34675) }
    reject("transfer function") { it.put16(it.entry(65000), 301) }
    reject("white point") { it.put16(it.entry(65000), 318) }
    reject("primaries") { it.put16(it.entry(65000), 319) }
    reject("tiles") { it.put16(it.entry(65000), 324) }
    reject("subIFD") { it.put16(it.entry(65000), 330) }
    reject("signed samples", TiffSmokeFixture(channels = 1)) { it.metadata(339, 3, 2) }
    reject("float samples", TiffSmokeFixture(channels = 1)) { it.metadata(339, 3, 3) }
    reject("sample format count") { it.metadata(339, 3, 1) }
    reject("fill order") { it.metadata(266, 3, 2) }
    reject("subfile type") { it.metadata(254, 4, 1) }
    reject("legacy subfile type") { it.metadata(255, 3, 2) }
    reject("RGB extra sample", TiffSmokeFixture(channels = 3)) { it.metadata(338, 3, 1) }
    val unsignedGray = TiffSmokeFixture(channels = 1)
    unsignedGray.metadata(339, 3, 1)
    check(TiffDecoder.decode(unsignedGray.bytes).rgba[4].toInt() == 20); accepted++
    val marker = IllegalStateException("admission denied")
    val admissionFailure = runCatching { TiffDecoder.decode(TiffSmokeFixture().bytes) { _, _, _ -> throw marker } }
    check(admissionFailure.exceptionOrNull() === marker)
    for (length in 0 until 8) {
        check(runCatching { TiffDecoder.decode(ByteArray(length)) }.isFailure); rejected++
    }
    return "TIFF decoder passed: $accepted valid endian/strip/orientation/alpha/gray/RGB cases; $rejected malformed cases rejected before output allocation; admission denial preserved"
}

private class TiffSmokeFixture(bigEndian: Boolean = false, orientation: Int = 1, rows: Int = 1,
    channels: Int = 4, alpha: Int = 2) {
    private val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    private val tags = mutableListOf(256, 257, 258, 259, 262, 273, 274, 277, 278, 279, 284).apply {
        if (channels == 4) add(338)
        add(65000) // FLOAT metadata: exactly four bytes, inline, even in a big-endian IFD.
    }
    val nextIfd = 10 + tags.size * 12
    private val bitsStart = nextIfd + 4
    private val offsetsStart = bitsStart + channels * 2
    private val countsStart = offsetsStart + 8
    val pixelStart = countsStart + 8
    val bytes = ByteArray(pixelStart + 6 * channels)
    private val buffer = ByteBuffer.wrap(bytes).order(order)
    init {
        bytes[0] = if (bigEndian) 77 else 73; bytes[1] = bytes[0]
        put16(2, 42); put32(4, 8); put16(8, tags.size)
        fun field(tag: Int, type: Int, count: Int, value: Long) {
            val at = entry(tag)
            put16(at, tag); put16(at + 2, type); put32(at + 4, count.toLong())
            if (type == 3 && count == 1) put16(at + 8, value.toInt()) else put32(at + 8, value)
        }
        field(256, 4, 1, 3); field(257, 4, 1, 2)
        field(258, 3, channels, if (channels == 1) 8 else bitsStart.toLong())
        if (channels > 1) repeat(channels) { put16(bitsStart + it * 2, 8) }
        field(259, 3, 1, 1); field(262, 3, 1, if (channels == 1) 1 else 2)
        val strips = 2 / rows
        field(273, 4, strips, if (strips == 1) pixelStart.toLong() else offsetsStart.toLong())
        field(274, 3, 1, orientation.toLong()); field(277, 3, 1, channels.toLong())
        field(278, 4, 1, rows.toLong())
        field(279, 4, strips, if (strips == 1) (6 * channels).toLong() else countsStart.toLong())
        field(284, 3, 1, 1)
        if (channels == 4) field(338, 3, 1, alpha.toLong())
        field(65000, 11, 1, 0x3F80_0000L)
        if (strips == 2) {
            put32(offsetsStart, pixelStart.toLong()); put32(offsetsStart + 4, (pixelStart + 3 * channels).toLong())
            put32(countsStart, (3 * channels).toLong()); put32(countsStart + 4, (3 * channels).toLong())
        }
        val pixels = if (channels == 4) intArrayOf(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255,
            255, 128, 0, 128, 255, 255, 255, 0, 255, 255, 255, 255)
        else IntArray(6 * channels) { i -> i / channels * 20 + i % channels }
        pixels.forEachIndexed { i, value -> bytes[pixelStart + i] = value.toByte() }
    }
    fun entry(tag: Int): Int = 10 + tags.indexOf(tag).also { check(it >= 0) } * 12
    fun put16(at: Int, value: Int) { buffer.putShort(at, value.toShort()) }
    fun put32(at: Int, value: Long) { buffer.putInt(at, value.toInt()) }
    fun value(tag: Int, value: Long) {
        val at = entry(tag)
        if (buffer.getShort(at + 2).toInt() == 3) put16(at + 8, value.toInt()) else put32(at + 8, value)
    }
    fun metadata(tag: Int, type: Int, value: Long) {
        val at = entry(65000)
        put16(at, tag); put16(at + 2, type); put32(at + 4, 1); put32(at + 8, 0)
        if (type == 3) put16(at + 8, value.toInt()) else put32(at + 8, value)
    }
    fun payload(tag: Int): Int = buffer.getInt(entry(tag) + 8)
}
