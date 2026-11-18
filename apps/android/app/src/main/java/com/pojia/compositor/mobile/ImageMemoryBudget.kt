package com.pojia.compositor.mobile

/** Conservative overlap for the existing whole-image ARGB_8888 codec paths, before any allocation. */
internal data class ImageMemoryEstimate(val bitmapBytes: Long, val transferBytes: Long,
    val repackBytes: Long, val codecScratchBytes: Long, val nativeImportBytes: Long) {
    val peakBytes: Long = listOf(bitmapBytes, transferBytes, repackBytes, codecScratchBytes, nativeImportBytes)
        .fold(0L) { total, bytes ->
            require(bytes >= 0)
            Math.addExact(total, bytes)
        }
    fun reserve(ledger: ResourceBudgetLedger, label: String): ResourceBudgetLedger.Reservation =
        ledger.reserve(peakBytes, label)
}

/** Scratch and native allowances are caller supplied; platform codec internals are not measured here. */
internal object ImageMemoryBudget {
    fun rgba8RowBytes(width: Int): Long {
        require(width > 0)
        return Math.multiplyExact(width.toLong(), 4L)
    }

    private fun layout(width: Int, height: Int, bitmapRowBytes: Long): Pair<Long, Long> {
        require(height > 0)
        val packedRowBytes = rgba8RowBytes(width)
        require(bitmapRowBytes >= packedRowBytes && bitmapRowBytes % 4 == 0L)
        return Math.multiplyExact(bitmapRowBytes, height.toLong()) to
            Math.multiplyExact(packedRowBytes, height.toLong())
    }

    /** Header estimate, not a platform stride guarantee. Resize to actual rowBytes before transfer allocation. */
    fun estimatedImport8Bit(width: Int, height: Int, codecScratchBytes: Long,
        nativeImportBytes: Long): ImageMemoryEstimate {
        val row = rgba8RowBytes(width)
        val aligned = Math.multiplyExact(Math.addExact(row, 63) / 64, 64)
        return import8Bit(width, height, aligned, codecScratchBytes, nativeImportBytes)
    }

    fun import8Bit(width: Int, height: Int, bitmapRowBytes: Long,
        codecScratchBytes: Long, nativeImportBytes: Long): ImageMemoryEstimate {
        val (bitmap, packed) = layout(width, height, bitmapRowBytes)
        return ImageMemoryEstimate(bitmap, bitmap, if (bitmap != packed) packed else 0,
            codecScratchBytes, nativeImportBytes)
    }

    fun export8Bit(width: Int, height: Int, bitmapRowBytes: Long,
        codecScratchBytes: Long): ImageMemoryEstimate {
        val (bitmap, packed) = layout(width, height, bitmapRowBytes)
        return ImageMemoryEstimate(bitmap, packed, if (bitmap != packed) bitmap else 0,
            codecScratchBytes, 0)
    }

    /** Tile payload only. Layer/history metadata, allocation overhead and codec scratch are additional. */
    fun tiledRasterBytes8Bit(width: Int, height: Int): Long {
        require(width > 0 && height > 0)
        val columns = (width.toLong() + 255) / 256
        val rows = (height.toLong() + 255) / 256
        return Math.multiplyExact(Math.multiplyExact(columns, rows), 256L * 256 * 4)
    }
}
