package com.pojia.compositor.mobile

/** Values are the native mode ABI, independent of translated labels. */
internal enum class SmearMode(val nativeValue: Int, val labelResource: Int) {
    SMUDGE(0, R.string.ui_smudge), LIQUIFY(1, R.string.ui_liquify);
}
