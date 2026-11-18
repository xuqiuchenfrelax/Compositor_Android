package com.pojia.compositor.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.TypefaceSpan
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A temporary canonical cache raster. Bytes are premultiplied RGBA, ready for cp_document_commit_text_layer. */
internal class TextLayerRaster(val width: Int, val height: Int, val rowBytes: Int, val pixels: ImagePixelBuffer) : AutoCloseable {
    override fun close() = pixels.close()
}

/**
 * Android cache renderer for typed text. It retains the Mac-authored style JSON separately; this renderer supplies
 * only the required compositor cache. Font resolution is Android's Typeface family resolver, so a Mac PostScript
 * face can fall back to Android's default; exact cross-platform glyph metrics are not claimed.
 */
internal object TextLayerRasterizer {
    private const val MAX_SIDE = 30_000
    private const val MAX_PIXELS = 200_000_000L

    fun render(style: TextLayerModel, maximumRasterBytes: Long = Long.MAX_VALUE): TextLayerRaster {
        style.validate()
        val text = SpannableString(style.content)
        val baseFace = Typeface.create(style.fontName, Typeface.NORMAL)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = baseFace
            textSize = style.fontSize.toFloat()
            color = argb(style.red, style.green, style.blue)
            letterSpacing = if (style.fontSize > 0) (style.tracking / style.fontSize).toFloat() else 0f
        }
        text.setSpan(FloatSizeSpan(style.fontSize.toFloat()), 0, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(LetterSpacingSpan(paint.letterSpacing), 0, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (run in style.fontRuns) if (run.length > 0) {
            text.setSpan(TypefaceSpan(run.fontName), run.location, run.location + run.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (run in style.colorRuns) if (run.length > 0) {
            text.setSpan(ForegroundColorSpan(argb(run.red, run.green, run.blue)), run.location, run.location + run.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val fixedWidth = style.boxWidth?.let { side(it) }
        val lineHeight = ceil(TextLayerLayoutContract.lineHeight(style)).toInt().coerceAtLeast(1)
        val measureWidth = fixedWidth?.minus((TextLayerLayoutContract.PADDING_PX * 2).toInt())
            ?: TextLayerLayoutContract.POINT_TEXT_MEASURE_WIDTH_PX.toInt()
        val alignment = when (style.alignment) {
            TextParagraphAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextParagraphAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextParagraphAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        text.setSpan(FixedLineHeightSpan(lineHeight, style.fontSize.toFloat()), 0, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        fun layout(width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(alignment).setIncludePad(false).setLineSpacing(0f, 1f)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
        val measured = layout(measureWidth)
        val contentWidth = if (fixedWidth != null) measureWidth else max(1, measured.lineCount.let { count ->
            (0 until count).maxOfOrNull { ceil(measured.getLineWidth(it)).toInt() } ?: 1
        })
        // Point text is measured with an unbounded width, then drawn in its
        // actual container. Center/right alignment in the measuring container
        // would put the glyphs outside the small canonical bitmap.
        val width = fixedWidth ?: side(max(16.0, ceil(contentWidth + TextLayerLayoutContract.PADDING_PX * 2 + style.fontSize * .1)))
        val layout = if (fixedWidth != null) measured else layout(width - (TextLayerLayoutContract.PADDING_PX * 2).toInt())
        val height = style.boxHeight?.let(::side) ?: side(max(16.0,
            ceil(max(layout.height, lineHeight).toDouble() + TextLayerLayoutContract.PADDING_PX * 2)))
        val pixelCount = Math.multiplyExact(width.toLong(), height.toLong())
        require(pixelCount <= MAX_PIXELS) { "Text layer exceeds the pixel surface limit" }
        // Bitmap ARGB storage and the direct premultiplied RGBA publication buffer coexist until JNI returns.
        val peakRasterBytes = Math.multiplyExact(pixelCount, 8L)
        require(peakRasterBytes <= maximumRasterBytes) { "Text layer exceeds the available transient memory budget" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                save()
                translate(TextLayerLayoutContract.PADDING_PX.toFloat(), TextLayerLayoutContract.PADDING_PX.toFloat())
                layout.draw(this)
                restore()
            }
            val rowBytes = Math.multiplyExact(width, 4)
            val packedBytes = Math.multiplyExact(rowBytes, height)
            val transferBytes = if (bitmap.rowBytes == rowBytes) 0L else Math.multiplyExact(bitmap.rowBytes.toLong(), height.toLong())
            val actualPeak = Math.addExact(bitmap.allocationByteCount.toLong(), Math.addExact(transferBytes, packedBytes.toLong()))
            require(actualPeak <= maximumRasterBytes) { "Text bitmap row padding exceeds the transient memory budget" }
            val pixels = ImagePixelBuffer(packedBytes)
            try {
                copyPremultipliedRGBA(bitmap, pixels.buffer, width, height, rowBytes, maximumRasterBytes)
                return TextLayerRaster(width, height, rowBytes, pixels)
            } catch (error: Throwable) {
                pixels.close()
                throw error
            }
        } finally { bitmap.recycle() }
    }

    private fun copyPremultipliedRGBA(bitmap: Bitmap, output: ByteBuffer, width: Int, height: Int,
        rowBytes: Int, maximumRasterBytes: Long) {
        // getPixels() unpremultiplies channels. Multiplying those rounded values
        // again loses antialiased glyph samples; copy Android's original RGBA storage.
        if (bitmap.rowBytes == rowBytes) {
            bitmap.copyPixelsToBuffer(output)
            output.rewind()
            return
        }
        val transferBytes = Math.multiplyExact(bitmap.rowBytes.toLong(), height.toLong())
        val peak = Math.addExact(bitmap.allocationByteCount.toLong(), Math.addExact(transferBytes, output.capacity().toLong()))
        require(peak <= maximumRasterBytes) { "Text bitmap row padding exceeds the transient memory budget" }
        ImagePixelBuffer(Math.toIntExact(transferBytes)).use { transfer ->
            bitmap.copyPixelsToBuffer(transfer.buffer)
            copyImageRows(transfer.buffer, bitmap.rowBytes, output, rowBytes, width, height)
        }
        output.rewind()
    }

    private fun side(value: Double): Int {
        require(value.isFinite() && value in 1.0..MAX_SIDE.toDouble()) { "Text layer side exceeds the pixel limit" }
        return ceil(value).toInt()
    }

    private fun argb(red: Double, green: Double, blue: Double): Int =
        android.graphics.Color.rgb((red * 255).toInt().coerceIn(0, 255), (green * 255).toInt().coerceIn(0, 255),
            (blue * 255).toInt().coerceIn(0, 255))

    private class LetterSpacingSpan(private val spacing: Float) : android.text.style.MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) { textPaint.letterSpacing = spacing }
        override fun updateDrawState(textPaint: TextPaint) { textPaint.letterSpacing = spacing }
    }

    private class FloatSizeSpan(private val size: Float) : android.text.style.MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) { textPaint.textSize = size }
        override fun updateDrawState(textPaint: TextPaint) { textPaint.textSize = size }
    }

    private class FixedLineHeightSpan(private val requestedHeight: Int, private val nominalSize: Float) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            val fontHeight = fm.descent - fm.ascent
            val height = max(requestedHeight, fontHeight)
            val below = ((height - nominalSize.coerceAtMost(height.toFloat())) / 2).toInt()
            fm.ascent = -(height - below)
            fm.descent = below
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }
}
