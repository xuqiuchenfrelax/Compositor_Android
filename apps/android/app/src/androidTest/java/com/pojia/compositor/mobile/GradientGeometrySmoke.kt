package com.pojia.compositor.mobile

import android.app.Instrumentation
import kotlin.math.abs

/** Pure geometry/color checks for the Mac Gradient tool contract. */
internal fun gradientGeometrySmoke(@Suppress("UNUSED_PARAMETER") instrumentation: Instrumentation): String {
    val line = GradientDraft(10.0, 20.0, 110.0, 20.0)
    check(line.isMeaningful)
    check(abs(line.linearParameter(60.0, 20.0) - .5) < 1e-9)
    check(line.linearParameter(160.0, 20.0) > 1.0)
    check(abs(GradientDraft(4.0, 4.0, 4.0, 54.0).radialParameter(4.0, 29.0) - .5) < 1e-9)
    check(!GradientDraft(4.0, 4.0, 4.2, 4.2).isMeaningful)

    val foreground = GradientColor(1f, 0f, 0f, 1f)
    val background = GradientColor(0f, 0f, 1f, 1f)
    val settings = GradientSettings(GradientShape.LINEAR, GradientStyle.FOREGROUND_TO_BACKGROUND)
    val middle = settings.sample(line, 60.0, 20.0, foreground, background)
    check(abs(middle.red - .5f) < .001f && abs(middle.blue - .5f) < .001f)
    val reversed = settings.copy(reversed = true).sample(line, 10.0, 20.0, foreground, background)
    check(reversed.blue > .99f && reversed.red < .01f)
    val transparent = foreground.mix(GradientColor(0f, 0f, 0f, 0f), .5f)
    check(transparent.alpha in .49f.. .51f && transparent.red > .99f)
    check(abs(settings.maskSample(line, 60.0, 20.0, 1f, 0f) - .5f) < .001f)
    val transparentMask = settings.copy(style = GradientStyle.FOREGROUND_TO_TRANSPARENT)
    check(abs(transparentMask.maskSample(line, 60.0, 20.0, 1f, 0f) - .5f) < .001f)
    check(transparentMask.maskSample(line, 110.0, 20.0, 1f, 0f) < .001f)
    return "Gradient geometry smoke passed: linear/radial handles, extension, click cancel, reverse, premultiplied transparency, mask grayscale"
}
