package com.pojia.compositor.mobile

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.pow

/** Mouse navigation owns its own sequence; it never starts an ordinary tool pointer. */
internal class MousePanInput(private val currentScale: () -> Double = { 1.0 },
    private val density: () -> Double = { 1.0 },
    private val zoomAt: (Double, Double, Double) -> Unit = { _, _, _ -> },
    private val zoomCursor: (Boolean?) -> Unit = {},
    private val translate: (Double, Double) -> Unit) {
    internal data class Result(val consumed: Boolean, val finishPrimary: Boolean = false)
    private data class Point(val x: Double, val y: Double)
    private var primary = false
    private var middle = false
    private var last: Point? = null
    private data class Zoom(val anchor: Point, val scale: Double, val density: Double, var lastY: Double,
        var zoomingIn: Boolean = true)
    private var zoom: Zoom? = null

    private fun clearMotion() { last = null; zoom = null; zoomCursor(null) }
    fun resetNavigation() { middle = false; clearMotion() }
    fun reset() { resetNavigation(); primary = false }

    fun route(event: MotionEvent, canPan: Boolean): Result {
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.pointerCount == 1 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (!mouse) {
            // A second pointer or a different device cannot continue the mouse navigation drag.
            val cancelledNavigation = middle && event.actionMasked in setOf(MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL)
            if (event.actionMasked != MotionEvent.ACTION_HOVER_MOVE) reset()
            return Result(cancelledNavigation)
        }
        val primaryHeld = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
        val middleHeld = event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
        fun start() {
            middle = true
            clearMotion()
            val anchor = point(event)
            if (!canPan || anchor == null) return
            if (event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK) != 0) {
                val scale = currentScale(); val units = density()
                if (scale.isFinite() && scale > 0 && units.isFinite() && units > 0) {
                    zoom = Zoom(anchor, scale, units, anchor.y)
                    zoomCursor(true)
                }
            } else last = anchor
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (middleHeld && !primaryHeld) { if (!middle) start(); return Result(true) }
                if (middle) return Result(true)
                primary = primaryHeld
                return Result(!primary)
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (!primary && !primaryHeld && !middle) start()
                    return Result(true)
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (event.actionButton == MotionEvent.BUTTON_TERTIARY) {
                    resetNavigation()
                    return Result(true)
                }
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    // With another button held, Android sends a generic release instead of touch UP.
                    val finish = primary && !primaryHeld
                    if (finish) primary = false
                    return Result(true, finishPrimary = finish)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (middle) {
                    val previous = last
                    val next = point(event)
                    val initial = zoom
                    if (!middleHeld || primaryHeld || !canPan || next == null) clearMotion()
                    else if (initial != null) {
                        if (next.y != initial.lastY) {
                            val zoomingIn = next.y < initial.lastY
                            if (zoomingIn != initial.zoomingIn) {
                                initial.zoomingIn = zoomingIn
                                zoomCursor(zoomingIn)
                            }
                            initial.lastY = next.y
                        }
                        // Absolute from DOWN, in dp: clamp recovery must not compound earlier moves.
                        val target = initial.scale * 2.0.pow((initial.anchor.y - next.y) / (100 * initial.density))
                        if (target.isFinite()) zoomAt(target.coerceIn(0.001, 32.0), initial.anchor.x, initial.anchor.y)
                    } else if (previous != null) {
                        val dx = next.x - previous.x; val dy = next.y - previous.y
                        if (dx.isFinite() && dy.isFinite()) translate(dx, dy)
                        last = next
                    }
                    return Result(true)
                }
                // An orphan/middle-only MOVE must not paint or drag the selected Hand/Zoom tool.
                return Result(!primary || !primaryHeld)
            }
            MotionEvent.ACTION_UP -> {
                val ordinary = primary
                reset()
                return Result(!ordinary)
            }
            MotionEvent.ACTION_CANCEL -> {
                val ordinary = primary
                reset()
                return Result(!ordinary)
            }
        }
        return Result(false)
    }

    private fun point(event: MotionEvent): Point? =
        if (event.x.isFinite() && event.y.isFinite()) Point(event.x.toDouble(), event.y.toDouble()) else null
}
