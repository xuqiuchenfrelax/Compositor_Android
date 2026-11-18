package com.pojia.compositor.mobile

/** Fixtures may reset several fields deliberately; product controls patch one named field. */
internal fun NativeEditor.setAppearanceForTest(value: LayerState) {
    setOpacity(value.id, value.opacity)
    setVisible(value.id, value.visible)
    setBlend(value.id, value.blend)
}
