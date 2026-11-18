package com.pojia.compositor.mobile

/**
 * Fast contract check for the Android options bar's Select → Modify controls. It intentionally
 * does not touch a device surface: the native pixel transform is exercised by its own JNI suite,
 * while this check locks the UI operation IDs and the Mac-compatible amount bounds used before a
 * command is admitted to the retained dispatcher.
 */
internal fun selectionAmountContractSmoke(): String {
    check(PixelSelectionAmountOperation.EXPAND == 0)
    check(PixelSelectionAmountOperation.CONTRACT == 1)
    check(PixelSelectionAmountOperation.FEATHER == 2)
    check(PixelSelectionAmountOperation.title(PixelSelectionAmountOperation.EXPAND) == "Expand")
    check(PixelSelectionAmountOperation.title(PixelSelectionAmountOperation.CONTRACT) == "Contract")
    check(PixelSelectionAmountOperation.title(PixelSelectionAmountOperation.FEATHER) == "Feather")
    check(PixelSelectionAmountOperation.maximum(PixelSelectionAmountOperation.EXPAND) == 500)
    check(PixelSelectionAmountOperation.maximum(PixelSelectionAmountOperation.CONTRACT) == 500)
    check(PixelSelectionAmountOperation.maximum(PixelSelectionAmountOperation.FEATHER) == 250)
    return "selection amount UI contract passed: Expand/Contract 1..500 px; Feather 1..250 px"
}
