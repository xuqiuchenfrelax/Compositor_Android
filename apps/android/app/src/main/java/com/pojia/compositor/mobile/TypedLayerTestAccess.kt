package com.pojia.compositor.mobile

/** Only an isolated debug Activity installs this bridge; callbacks use its production draft/commit paths. */
internal class TypedLayerTestAccess(
    val text: () -> TextEditorDraft?,
    val beginText: () -> Unit,
    val editText: () -> Unit,
    val updateText: (TextEditorDraft) -> Unit,
    val applyText: () -> Unit,
    val cancelText: () -> Unit,
    val adjustment: () -> AdjustmentEditorDraft?,
    val beginAdjustment: (AdjustmentKind) -> Unit,
    val editAdjustment: () -> Unit,
    val updateAdjustment: (AdjustmentEditorDraft) -> Unit,
    val applyAdjustment: () -> Unit,
    val cancelAdjustment: () -> Unit,
    val busy: () -> Boolean,
)
