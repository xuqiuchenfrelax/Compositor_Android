package com.pojia.compositor.mobile

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.exp

/** Android wheel units map explicitly to discrete Mac deltas; fractional units keep their precision. */
internal class MouseWheelInput(private val currentScale: () -> Double,
    private val density: () -> Double,
    private val zoomAt: (Double, Double, Double) -> Unit,
    private val translate: (Double, Double) -> Unit) {
    fun route(event: MotionEvent, canNavigate: Boolean): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_MOUSE) || event.pointerCount != 1 ||
            event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return false
        if (!canNavigate) return true
        val x = event.x.toDouble(); val y = event.y.toDouble()
        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble()
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble()
        if (!x.isFinite() || !y.isFinite() || !horizontal.isFinite() || !vertical.isFinite()) return true
        if (event.metaState and (KeyEvent.META_META_MASK or KeyEvent.META_ALT_MASK) != 0) {
            if (vertical == 0.0) return true
            val scale = currentScale()
            if (!scale.isFinite() || scale <= 0) return true
            val target = scale * exp(-vertical * 0.015)
            if (target.isFinite()) zoomAt(target.coerceIn(0.001, 32.0), x, y)
        } else {
            if (horizontal == 0.0 && vertical == 0.0) return true
            val units = density()
            if (!units.isFinite() || units <= 0) return true
            // Positive axes move the image right/down; no Shift swap or inferred precise-device mode.
            val dx = horizontal * 12 * units; val dy = vertical * 12 * units
            if (dx.isFinite() && dy.isFinite()) translate(dx, dy)
        }
        return true
    }
}
