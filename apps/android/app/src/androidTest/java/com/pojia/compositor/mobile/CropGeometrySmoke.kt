package com.pojia.compositor.mobile

import android.app.Instrumentation
import kotlin.math.abs

/** Pure geometry checks for Mac-compatible crop creation and ratio constraints. */
internal fun cropGeometrySmoke(@Suppress("UNUSED_PARAMETER") instrumentation: Instrumentation): String {
    val square = cropCreate(10.0 to 20.0, 70.0 to 40.0, 1.0, false)
    check(abs(square.width - square.height) < .001)
    check(square.left == 10.0 && square.top == 20.0)
    val centered = cropCreate(50.0 to 50.0, 70.0 to 60.0, null, true)
    check(abs((centered.left + centered.right) / 2.0 - 50.0) < .001)
    check(abs((centered.top + centered.bottom) / 2.0 - 50.0) < .001)
    val minimum = CropRect(4.4, 5.4, 4.4, 5.4).snapped(100, 100)
    check(minimum.width == 1.0 && minimum.height == 1.0)
    check(CropRatioChoice.ORIGINAL.value(1600, 900)!! > 1.7)
    check(abs(CropRatioChoice.CINEMATIC_239.value(1600, 900)!! - 2.39) < .0001)
    check(abs(parseCropRatio("4:5")!! - .8) < .0001)
    check(abs(parseCropRatio("3,2:1")!! - 3.2) < .0001)
    check(abs(parseCropRatio(" 16 : 9 ")!! - 16.0 / 9.0) < .0001)
    check(parseCropRatio("0:5") == null)
    check(parseCropRatio("4:0") == null)
    check(parseCropRatio("4") == null)
    check(parseCropRatio("-4:5") == null)
    check(parseCropRatio("NaN:1") == null)
    check(parseCropRatio("1000:1") == null)
    return "Crop geometry smoke passed: presets, custom ratio parser, fixed ratio, symmetric option, minimum 1px snap"
}
