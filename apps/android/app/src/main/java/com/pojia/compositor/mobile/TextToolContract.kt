package com.pojia.compositor.mobile

/**
 * Android-side text editing contract.  Android's TextFieldValue and IME APIs
 * use UTF-16 offsets, so the draft deliberately stores selection and composing
 * ranges in UTF-16 code units.  This keeps marked Chinese input, emoji and
 * combining characters intact. Persistent styles use TextLayerModel, which
 * mirrors Mac LayerTextStyle and is committed through NativeEditor.
 */
internal enum class TextAlignment { LEFT, CENTER, RIGHT }

internal data class TextStyle(
    val fontFamily: String = "system",
    val fontSizeSp: Float = 32f,
    val colorArgb: Int = 0xff000000.toInt(),
    val alignment: TextAlignment = TextAlignment.LEFT,
    val lineSpacing: Float = 1f,
) {
    init {
        require(fontFamily.isNotBlank() && fontFamily.toByteArray(Charsets.UTF_8).size <= TextToolContract.MAX_FONT_BYTES)
        require(fontSizeSp.isFinite() && fontSizeSp in 1f..512f)
        require(lineSpacing.isFinite() && lineSpacing in 0.5f..4f)
    }
}

internal data class TextDraft(
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val composingStart: Int = -1,
    val composingEnd: Int = -1,
    val style: TextStyle = TextStyle(),
) {
    init {
        require(text.toByteArray(Charsets.UTF_8).size <= TextToolContract.MAX_TEXT_BYTES)
        require(selectionStart in 0..text.length && selectionEnd in 0..text.length)
        require(composingStart in -1..text.length && composingEnd in -1..text.length)
        require((composingStart < 0) == (composingEnd < 0) || composingStart <= composingEnd)
    }

    val hasComposition: Boolean get() = composingStart >= 0 && composingEnd >= composingStart

    fun replace(value: String, selection: IntRange = value.length..value.length,
        composing: IntRange? = null): TextDraft {
        val clean = TextToolContract.sanitize(value)
        val nextSelection = selection.first.coerceIn(0, clean.length)..selection.last.coerceIn(0, clean.length)
        val nextComposing = composing?.let { it.first.coerceIn(0, clean.length)..it.last.coerceIn(0, clean.length) }
        return copy(text = clean,
            selectionStart = nextSelection.first, selectionEnd = nextSelection.last,
            composingStart = nextComposing?.first ?: -1, composingEnd = nextComposing?.last ?: -1)
    }
}

internal object TextToolContract {
    const val MAX_TEXT_BYTES = 256 * 1024
    const val MAX_FONT_BYTES = 1024

    /** Remove only the native-project-invalid NUL; preserve all other Unicode. */
    fun sanitize(value: String): String = value.replace('\u0000', '\uFFFD')

    /** Apply a TextFieldValue-shaped update without collapsing UTF-16 offsets. */
    fun update(current: TextDraft, value: String, selectionStart: Int, selectionEnd: Int,
        composingStart: Int = -1, composingEnd: Int = -1): TextDraft {
        val clean = sanitize(value)
        require(selectionStart in 0..clean.length && selectionEnd in 0..clean.length)
        require(composingStart in -1..clean.length && composingEnd in -1..clean.length)
        return current.copy(text = clean, selectionStart = selectionStart, selectionEnd = selectionEnd,
            composingStart = composingStart, composingEnd = composingEnd)
    }
}
