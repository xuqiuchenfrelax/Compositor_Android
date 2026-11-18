package com.pojia.compositor.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * Android mirror of the Mac v16 `LayerTextStyle` JSON object in ProjectStore.
 * Offsets are UTF-16 code units, matching NSString/NSRange and Android's IME.
 * `fontSize`, `tracking`, `leading`, and `boxSize` are layer pixels (not dp/sp).
 */
internal data class TextLayerModel(
    val content: String = "Text",
    val fontName: String = "Helvetica",
    val fontSize: Double = 72.0,
    val red: Double = 0.0,
    val green: Double = 0.0,
    val blue: Double = 0.0,
    val alignment: TextParagraphAlignment = TextParagraphAlignment.LEFT,
    val tracking: Double = 0.0,
    /** Baseline-to-baseline pixels; zero means automatic 120% leading. */
    val leading: Double = 0.0,
    val boxWidth: Double? = null,
    val boxHeight: Double? = null,
    val colorRuns: List<TextColorRun> = emptyList(),
    val fontRuns: List<TextFontRun> = emptyList(),
) {
    /** One-edit replacement using Mac's UTF-16 run convention; inserted units inherit the preceding unit. */
    fun replacingContent(value: String): TextLayerModel {
        val clean = TextToolContract.sanitize(value)
        if (clean == content) return this
        if (colorRuns.isEmpty() && fontRuns.isEmpty()) return copy(content = clean).validate()
        val old = content
        val next = clean
        var prefix = 0
        while (prefix < old.length && prefix < next.length && old[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < next.length - prefix && old[old.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        val oldEnd = old.length - suffix
        val newEnd = next.length - suffix
        val colorUnits = expandColors(old.length)
        val fontUnits = expandFonts(old.length)
        val inheritedColor = colorUnits.getOrNull(prefix - 1) ?: colorUnits.getOrNull(prefix)
            ?: doubleArrayOf(red, green, blue)
        val inheritedFont = fontUnits.getOrNull(prefix - 1) ?: fontUnits.getOrNull(prefix) ?: fontName
        colorUnits.subList(prefix, oldEnd).clear()
        colorUnits.addAll(prefix, List(newEnd - prefix) { inheritedColor })
        fontUnits.subList(prefix, oldEnd).clear()
        fontUnits.addAll(prefix, List(newEnd - prefix) { inheritedFont })
        val updated = copy(content = clean,
            colorRuns = encodeColors(colorUnits), fontRuns = encodeFonts(fontUnits))
        return updated.validate()
    }

    /** Fixed paragraph dimensions are layer pixels; point text has neither side. */
    fun withTextBox(width: Double?, height: Double?): TextLayerModel =
        copy(boxWidth = width, boxHeight = height).validate()

    fun setFont(name: String, selectionStart: Int, selectionEnd: Int): TextLayerModel {
        if (name.isEmpty() || name.length > 200 || name.any(Char::isTextNewline)) return this
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, content.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, content.length)
        if (start == end || (start == 0 && end == content.length)) return copy(fontName = name, fontRuns = emptyList()).validate()
        val units = expandFonts(content.length)
        for (i in start until end) units[i] = name
        return copy(fontRuns = encodeFonts(units)).validate()
    }

    fun setColor(r: Double, g: Double, b: Double, selectionStart: Int, selectionEnd: Int): TextLayerModel {
        require(listOf(r, g, b).all { it.isFinite() && it in 0.0..1.0 })
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, content.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, content.length)
        if (start == end || (start == 0 && end == content.length))
            return copy(red = r, green = g, blue = b, colorRuns = emptyList()).validate()
        val units = expandColors(content.length)
        for (i in start until end) units[i] = doubleArrayOf(r, g, b)
        return copy(colorRuns = encodeColors(units)).validate()
    }

    private fun expandColors(length: Int): MutableList<DoubleArray> {
        val values = MutableList(length) { doubleArrayOf(red, green, blue) }
        colorRuns.forEach { run -> for (i in run.location until run.location + run.length) values[i] = doubleArrayOf(run.red, run.green, run.blue) }
        return values
    }

    private fun expandFonts(length: Int): MutableList<String> {
        val values = MutableList(length) { fontName }
        fontRuns.forEach { run -> for (i in run.location until run.location + run.length) values[i] = run.fontName }
        return values
    }

    private fun encodeColors(units: List<DoubleArray>): List<TextColorRun> {
        val out = mutableListOf<TextColorRun>()
        units.forEachIndexed { index, c ->
            if (c[0] == red && c[1] == green && c[2] == blue) return@forEachIndexed
            val last = out.lastOrNull()
            if (last != null && last.location + last.length == index && last.red == c[0] && last.green == c[1] && last.blue == c[2])
                out[out.lastIndex] = last.copy(length = last.length + 1)
            else out += TextColorRun(index, 1, c[0], c[1], c[2])
        }
        return out
    }

    private fun encodeFonts(units: List<String>): List<TextFontRun> {
        if (units.isNotEmpty() && units.all { it == fontName }) return emptyList()
        val out = mutableListOf<TextFontRun>()
        units.forEachIndexed { index, name ->
            if (name == fontName) return@forEachIndexed
            val last = out.lastOrNull()
            if (last != null && last.location + last.length == index && last.fontName == name)
                out[out.lastIndex] = last.copy(length = last.length + 1)
            else out += TextFontRun(index, 1, name)
        }
        return out
    }

    fun validate(): TextLayerModel {
        require(content.length <= 100_000)
        require(fontSize.isFinite() && fontSize in 1.0..2000.0)
        require(listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 })
        require(tracking.isFinite() && tracking in -100.0..1000.0)
        require(leading.isFinite() && leading in 0.0..5000.0)
        require((boxWidth == null) == (boxHeight == null))
        if (boxWidth != null && boxHeight != null) {
            require(boxWidth.isFinite() && boxHeight.isFinite())
            require(boxWidth in 16.0..30_000.0 && boxHeight in 16.0..30_000.0)
            require(boxWidth * boxHeight <= 200_000_000.0)
        }
        validateRuns(colorRuns.map { it.location to it.length }, content.length)
        validateRuns(fontRuns.map { it.location to it.length }, content.length)
        require(colorRuns.all { listOf(it.red, it.green, it.blue).all { c -> c.isFinite() && c in 0.0..1.0 } })
        require(fontRuns.all { it.fontName.isNotEmpty() && it.fontName.length <= 200 && it.fontName.none(Char::isTextNewline) })
        return this
    }

    /** Mac's absent/invalid generated pixel cache is handled by the native layer loader. */
    fun toMacJson(): JSONObject {
        validate()
        return JSONObject().put("content", content).put("fontName", fontName).put("fontSize", fontSize)
            .put("red", red).put("green", green).put("blue", blue)
            .put("alignment", alignment.macValue).put("tracking", tracking).put("leading", leading)
            .also { json ->
                // The Mac schema is verified against Swift JSONEncoder: CGSize encodes as [width, height].
                if (boxWidth != null && boxHeight != null) json.put("boxSize", JSONArray().put(boxWidth).put(boxHeight))
                if (colorRuns.isNotEmpty()) json.put("colorRuns", JSONArray().apply {
                    colorRuns.forEach { put(JSONObject().put("location", it.location).put("length", it.length)
                        .put("red", it.red).put("green", it.green).put("blue", it.blue)) }
                })
                if (fontRuns.isNotEmpty()) json.put("fontRuns", JSONArray().apply {
                    fontRuns.forEach { put(JSONObject().put("location", it.location).put("length", it.length).put("fontName", it.fontName)) }
                })
            }
    }

    companion object {
        fun fromMacJson(json: JSONObject): TextLayerModel {
            val box = json.optJSONArray("boxSize")
            val colors = json.optJSONArray("colorRuns")
            val fonts = json.optJSONArray("fontRuns")
            return TextLayerModel(
                content = json.optString("content", "Text"), fontName = json.optString("fontName", "Helvetica"),
                fontSize = json.optDouble("fontSize", 72.0), red = json.optDouble("red", 0.0),
                green = json.optDouble("green", 0.0), blue = json.optDouble("blue", 0.0),
                alignment = TextParagraphAlignment.fromMac(json.optString("alignment", "Left")),
                tracking = json.optDouble("tracking", 0.0), leading = json.optDouble("leading", 0.0),
                boxWidth = box?.takeIf { it.length() == 2 }?.optDouble(0),
                boxHeight = box?.takeIf { it.length() == 2 }?.optDouble(1),
                colorRuns = colors?.let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let {
                    TextColorRun(it.getInt("location"), it.getInt("length"), it.getDouble("red"), it.getDouble("green"), it.getDouble("blue"))
                } } } ?: emptyList(),
                fontRuns = fonts?.let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let {
                    TextFontRun(it.getInt("location"), it.getInt("length"), it.getString("fontName"))
                } } } ?: emptyList(),
            ).validate()
        }

        private fun validateRuns(runs: List<Pair<Int, Int>>, utf16Length: Int) {
            var end = 0
            for ((start, length) in runs) {
                require(start >= end && length > 0 && start <= Int.MAX_VALUE - length)
                end = start + length
            }
            require(runs.isEmpty() || end <= utf16Length)
        }
    }
}

internal enum class TextParagraphAlignment(val macValue: String) {
    LEFT("Left"), CENTER("Center"), RIGHT("Right");
    companion object {
        fun fromMac(value: String): TextParagraphAlignment = values().firstOrNull { it.macValue == value }
            ?: error("Unsupported Mac text alignment: $value")
    }
}

internal data class TextColorRun(val location: Int, val length: Int, val red: Double, val green: Double, val blue: Double)
internal data class TextFontRun(val location: Int, val length: Int, val fontName: String)

/** Rendering rules copied from TypeTool.textBoxSize/textImage; renderer supplies the native font metrics. */
internal object TextLayerLayoutContract {
    const val PADDING_PX = 12.0
    const val MIN_BOX_SIDE_PX = 16.0
    const val POINT_TEXT_MEASURE_WIDTH_PX = 100_000.0
    fun lineHeight(style: TextLayerModel): Double = if (style.leading > 0.0) style.leading else style.fontSize * 1.2
    fun textContainerSize(style: TextLayerModel): Pair<Double, Double>? = style.boxWidth?.let { width ->
        width - PADDING_PX * 2 to (style.boxHeight!! - PADDING_PX * 2)
    }
    /** The canonical pixel cache is mandatory on disk even though source text stays typed and authoritative. */
    const val PROJECT_CACHE_REQUIREMENT = "Render canonical text pixels; persist LayerTextStyle plus imageFile; never replace typed text with pixels."
}

/** Explicit handoff boundary for the root NativeEditor integration. */
internal interface TextLayerEditActions {
    /** Submit a complete style draft. Implementations must preserve it as typed text and create one undo item. */
    fun commitTextLayer(layerId: Long?, style: TextLayerModel)
    /** Cancel the open draft and restore the pre-edit layer without producing history. */
    fun cancelTextLayer()
}

/** Swift Character.isNewline set for font-face validation. */
internal fun Char.isTextNewline(): Boolean = this in "\n\r\u000B\u000C\u0085\u2028\u2029"
