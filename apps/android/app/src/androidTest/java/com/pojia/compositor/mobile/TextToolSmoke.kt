package com.pojia.compositor.mobile

/** Contract checks for IME state and Mac-compatible typed text JSON/run semantics. */
internal fun textToolSmoke(): String {
    val initial = TextDraft()
    val marked = TextToolContract.update(initial, "中\uD83D\uDE80e\u0301", 1, 3, 0, 1)
    check(marked.text == "中\uD83D\uDE80e\u0301")
    check(marked.selectionStart == 1 && marked.selectionEnd == 3)
    check(marked.hasComposition && marked.composingStart == 0 && marked.composingEnd == 1)
    val replaced = marked.replace("中文", 2..2)
    check(replaced.text == "中文" && replaced.selectionStart == 2 && !replaced.hasComposition)
    check(TextToolContract.sanitize("a\u0000b") == "a\uFFFDb")
    check(TextStyle(fontFamily = "Noto Sans CJK", fontSizeSp = 48f, alignment = TextAlignment.CENTER).alignment == TextAlignment.CENTER)
    val style = TextLayerModel(content = "A中\uD83D\uDE80B", fontName = "Helvetica", fontSize = 48.0,
        alignment = TextParagraphAlignment.CENTER, boxWidth = 160.0, boxHeight = 96.0,
        colorRuns = listOf(TextColorRun(2, 2, 1.0, 0.0, 0.0)),
        fontRuns = listOf(TextFontRun(1, 3, "Noto Sans CJK"))).validate()
    val json = style.toMacJson()
    check(json.getJSONArray("boxSize").getDouble(0) == 160.0 && json.getJSONArray("boxSize").getDouble(1) == 96.0)
    check(TextLayerModel.fromMacJson(json) == style)
    listOf("", "Unknown Face", "Face\nName", "x".repeat(201)).forEach { baseFontName ->
        val baseFontStyle = TextLayerModel(content = "A", fontName = baseFontName).validate()
        check(baseFontStyle.toMacJson().getString("fontName") == baseFontName)
        check(TextLayerModel.fromMacJson(baseFontStyle.toMacJson()).fontName == baseFontName)
    }
    check(runCatching {
        TextLayerModel(content = "A", fontRuns = listOf(TextFontRun(0, 1, ""))).validate()
    }.isFailure)
    val replacement = style.replacingContent("A中\uD83D\uDE80C")
    // Mac LayerTextStyle.replaceCharacters inherits the preceding UTF-16 unit:
    // replacing the final base-style B after the colored/font-run emoji extends both runs.
    check(replacement.fontRuns == listOf(TextFontRun(1, 4, "Noto Sans CJK")))
    check(replacement.colorRuns == listOf(TextColorRun(2, 3, 1.0, 0.0, 0.0)))
    check(style.replacingContent(style.content) == style)
    val selected = style.setFont("Serif", 1, 4)
    check(selected.fontRuns?.single()?.let { it.location == 1 && it.length == 3 && it.fontName == "Serif" } == true)
    val composing = TextEditorDraft(style).update(androidx.compose.ui.text.input.TextFieldValue(
        "中\uD83D\uDE80e\u0301", selection = androidx.compose.ui.text.TextRange(1, 3),
        composition = androidx.compose.ui.text.TextRange(0, 1)))
    check(composing.selectionStart == 1 && composing.selectionEnd == 3 && composing.hasComposition)
    val blurValue = composing.textFieldValue().copy(selection = androidx.compose.ui.text.TextRange(3), composition = null)
    val heldSelection = composing.update(blurValue, retainSelection = true)
    check(heldSelection.selectionStart == 1 && heldSelection.selectionEnd == 3 && !heldSelection.hasComposition)
    check(composing.update(blurValue).let { it.selectionStart == 3 && it.selectionEnd == 3 })
    val reversed = composing.copy(selectionStart = 3, selectionEnd = 1).update(blurValue, retainSelection = true)
    check(reversed.selectionStart == 3 && reversed.selectionEnd == 1)
    check(composing.update(androidx.compose.ui.text.input.TextFieldValue("new", androidx.compose.ui.text.TextRange(2)),
        retainSelection = true).let { it.style.content == "new" && it.selectionStart == 2 && it.selectionEnd == 2 })
    val saveScope = object : androidx.compose.runtime.saveable.SaverScope {
        override fun canBeSaved(value: Any): Boolean = value is String || value is Number || value is List<*>
    }
    val savedDraft = checkNotNull(textEditorDraftSaver.run {
        with(saveScope) { save(composing.copy(layerId = 42, pointX = 18.5, pointY = 24.25)) }
    })
    val restoredDraft = checkNotNull(textEditorDraftSaver.restore(savedDraft))
    check(restoredDraft.style == composing.style && restoredDraft.layerId == 42L &&
        restoredDraft.selectionStart == 1 && restoredDraft.selectionEnd == 3 &&
        restoredDraft.composingStart == 0 && restoredDraft.composingEnd == 1 &&
        restoredDraft.pointX == 18.5 && restoredDraft.pointY == 24.25)
    check(restoredDraft.submissionId == composing.submissionId && !restoredDraft.commitInFlight)
    check(composing.copy(style = composing.style.copy(leading = 36.0)).submissionId == composing.submissionId)
    check(TextEditorDraft(style).submissionId != TextEditorDraft(style).submissionId)
    val legacyDraft = checkNotNull(textEditorDraftSaver.restore((savedDraft as List<*>).take(8)))
    check(legacyDraft.style == composing.style && legacyDraft.submissionId.isNotBlank())
    val nineFieldDraft = checkNotNull(textEditorDraftSaver.restore((savedDraft as List<*>).take(9)))
    check(nineFieldDraft.submissionId == composing.submissionId && nineFieldDraft.styleInputs.isEmpty())
    for ((field, invalid) in listOf(TextStyleField.FONT to "x".repeat(201), TextStyleField.COLOR to "#1234567",
        TextStyleField.SIZE to "NaN", TextStyleField.LEADING to "-1", TextStyleField.TRACKING to "1001",
        TextStyleField.BOX_WIDTH to "0", TextStyleField.BOX_HEIGHT to "30001")) {
        val invalidDraft = TextEditorDraft(style).editStyleField(field, invalid)
        check(invalidDraft.hasInvalidStyleInput && invalidDraft.invalidStyleField(field) && invalidDraft.style == style)
        val saved = checkNotNull(textEditorDraftSaver.run { with(saveScope) { save(invalidDraft) } })
        val restored = checkNotNull(textEditorDraftSaver.restore(saved))
        check(restored == invalidDraft && restored.hasInvalidStyleInput)
        check(!restored.style.toMacJson().has("styleInputs")) { "UI-only field state leaked into a project" }
    }
    val invalidBox = TextEditorDraft(style).editStyleField(TextStyleField.BOX_WIDTH, "0")
    check(!invalidBox.withTextBox(null, null).hasInvalidStyleInput)
    check(invalidBox.withTextBox(320.0, 160.0).styleInputs.isEmpty())
    val severalErrors = TextEditorDraft(style).editStyleField(TextStyleField.FONT, "")
        .editStyleField(TextStyleField.COLOR, "#GGGGGG").editStyleField(TextStyleField.SIZE, "0")
    val repairedFont = severalErrors.editStyleField(TextStyleField.FONT, "serif")
    val repairedColor = repairedFont.editStyleField(TextStyleField.COLOR, "#336699")
    check(repairedFont.hasInvalidStyleInput && repairedColor.hasInvalidStyleInput)
    check(!repairedColor.editStyleField(TextStyleField.SIZE, "24").hasInvalidStyleInput)
    val inFlightSaved = checkNotNull(textEditorDraftSaver.run {
        with(saveScope) { save(composing.copy(commitInFlight = true)) }
    })
    check(checkNotNull(textEditorDraftSaver.restore(inFlightSaved)).let {
        it.submissionId == composing.submissionId && !it.commitInFlight
    }) { "Transient busy state must be recovered from the document owner, not persisted forever" }
    val adjustmentDraft = AdjustmentEditorDraft(AdjustmentLayerModel.create(AdjustmentKind.INVERT))
    val savedAdjustment = checkNotNull(adjustmentEditorDraftSaver.run {
        with(saveScope) { save(adjustmentDraft.copy(commitInFlight = true)) }
    })
    check(checkNotNull(adjustmentEditorDraftSaver.restore(savedAdjustment)).let {
        it.submissionId == adjustmentDraft.submissionId && !it.commitInFlight
    })
    check(equalAdjustmentJson(checkNotNull(adjustmentEditorDraftSaver.restore((savedAdjustment as List<*>).take(2))).style.toMacJson(),
        adjustmentDraft.style.toMacJson()))
    check(runCatching { TextLayerRasterizer.render(style, maximumRasterBytes = 1) }.isFailure)
    TextLayerRasterizer.render(TextLayerModel(content = "中\uD83D\uDE80\nA", fontSize = 18.0,
        boxWidth = 80.0, boxHeight = 64.0, red = 1.0)).use { raster ->
        check(raster.width == 80 && raster.height == 64 && raster.rowBytes == 320)
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            val offset = y * raster.rowBytes + x * 4
            val buffer = raster.pixels.buffer
            val alpha = buffer.get(offset + 3).toInt() and 255
            check((buffer.get(offset).toInt() and 255) <= alpha)
            check((buffer.get(offset + 1).toInt() and 255) <= alpha)
            check((buffer.get(offset + 2).toInt() and 255) <= alpha)
        }
    }
    // A nonempty centered/right-aligned point layer must contain visible glyphs,
    // rather than drawing them in the temporary 100000-pixel measuring container.
    for (alignment in TextParagraphAlignment.entries) {
        TextLayerRasterizer.render(TextLayerModel(content = "中文🚀\nABC", fontSize = 24.0, alignment = alignment)).use { raster ->
            check((0 until raster.width * raster.height).count { (raster.pixels.buffer.get(it * 4 + 3).toInt() and 255) > 0 } > 20) {
                "Nonempty $alignment point text produced a blank pixel cache"
            }
        }
    }
    check(runCatching { style.withTextBox(30_000.0, 30_000.0) }.isFailure)
    check(style.withTextBox(null, null).boxWidth == null)
    return "UTF16=verified composing=retained MacStyleJSON=roundtrip draftSaver=recreation-ready CGSizeArray=verified runs=preserved raster=rgba-premultiplied budget=preallocation-guarded"
}
