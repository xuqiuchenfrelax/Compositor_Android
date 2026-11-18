package com.pojia.compositor.mobile

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun writeBigTiff16(file: File, bigEndian: Boolean) {
    // Independent BigTIFF IFD with inline 16-bit BitsPerSample and a 64-bit strip offset.
    val entries = 10
    val pixelOffset = 16 + 8 + entries * 20 + 8
    val data = ByteBuffer.allocate(pixelOffset + 6).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
    data.put(0, if (bigEndian) 'M'.code.toByte() else 'I'.code.toByte())
    data.put(1, data.get(0)); data.putShort(2, 43); data.putShort(4, 8); data.putShort(6, 0); data.putLong(8, 16)
    data.putLong(16, entries.toLong())
    fun entry(index: Int, tag: Int, type: Int, count: Long, value: Long) {
        val offset = 24 + index * 20
        data.putShort(offset, tag.toShort()); data.putShort(offset + 2, type.toShort()); data.putLong(offset + 4, count)
        when (type) {
            3 -> data.putShort(offset + 12, value.toShort())
            4 -> data.putInt(offset + 12, value.toInt())
            16 -> data.putLong(offset + 12, value)
            else -> error("Unexpected BigTIFF field type")
        }
    }
    entry(0, 256, 4, 1, 1); entry(1, 257, 4, 1, 1)
    entry(2, 258, 3, 3, 16); data.putShort(24 + 2 * 20 + 14, 16); data.putShort(24 + 2 * 20 + 16, 16)
    entry(3, 259, 3, 1, 1); entry(4, 262, 3, 1, 2)
    entry(5, 273, 16, 1, pixelOffset.toLong()); entry(6, 277, 3, 1, 3)
    entry(7, 278, 4, 1, 1); entry(8, 279, 16, 1, 6); entry(9, 284, 3, 1, 1)
    data.putShort(pixelOffset, 0x1234); data.putShort(pixelOffset + 2, 0x4567); data.putShort(pixelOffset + 4, 0x789a.toShort())
    file.writeBytes(data.array())
}
