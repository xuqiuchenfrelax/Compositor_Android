package com.pojia.compositor.mobile

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal fun moveHandleGeometrySmoke(): String {
    fun near(a: Double, b: Double) = abs(a - b) < 1e-8
    fun same(a: MovePoint, b: MovePoint) = hypot(a.x - b.x, a.y - b.y) < 1e-8
    fun center(box: Transform64) = MovePoint(box.x + box.width / 2, box.y + box.height / 2)
    val box = Transform64(31.0, -19.0, 200.0, 100.0, 37.0, flipX = true)
    for (index in MoveHandleGeometry.units.indices) {
        val unit = MoveHandleGeometry.units[index]
        val opposite = MovePoint(1 - unit.x, 1 - unit.y)
        val handle = MoveHandleGeometry.point(box, unit)
        // Grabbing anywhere within the hit radius must not jump to the pointer.
        val down = MovePoint(handle.x + 4, handle.y - 3)
        for (locked in listOf(false, true)) for (shift in listOf(false, true)) for (alt in listOf(false, true)) {
            val noMotion = MoveHandleGeometry.resize(box, down, down, index, locked, shift, alt, rounded = false)
            check(near(noMotion.x, box.x) && near(noMotion.y, box.y))
            check(near(noMotion.width, box.width) && near(noMotion.height, box.height))
            val changed = MoveHandleGeometry.resize(box, down, MovePoint(down.x + 34, down.y + 17),
                index, locked, shift, alt, rounded = false)
            check(MoveGeometry.valid(changed))
            check(changed.flipX && !changed.flipY)
            if (alt) check(same(center(box), center(changed)))
            else check(same(MoveHandleGeometry.point(box, opposite), MoveHandleGeometry.point(changed, opposite)))
            if (locked != shift) check(near(changed.width / changed.height, 2.0))
        }
    }
    val plain = Transform64(0.0, 0.0, 100.0, 50.0)
    val down = MovePoint(100.0, 50.0)
    check(MoveHandleGeometry.resize(plain, down, MovePoint(150.0, 50.0), 4, true, true) ==
        plain.copy(width = 150.0))
    // Proportional corner resizing projects the pointer onto the original diagonal, rather than max-axis scaling.
    val proportional = MoveHandleGeometry.resize(plain, down, MovePoint(150.0, 50.0), 4, true, false)
    check(proportional == plain.copy(width = 140.0, height = 70.0))
    val flip = MoveHandleGeometry.resize(plain, down, MovePoint(-25.0, -15.0), 4, false, false)
    check(flip == Transform64(-25.0, -15.0, 25.0, 15.0, flipX = true, flipY = true))
    val preflipped = plain.copy(flipX = true, flipY = true)
    check(MoveHandleGeometry.resize(preflipped, down, MovePoint(-25.0, -15.0), 4, false, false) ==
        flip.copy(flipX = false, flipY = false))
    check(MoveHandleGeometry.resize(plain, down, MovePoint(0.0, 0.0), 4, false, false) ==
        plain.copy(width = 1.0, height = 1.0))
    // Mac uses the signed projection even across the diagonal: it reaches the minimum ratio, then flips.
    val ratioFlip = MoveHandleGeometry.resize(plain, down, MovePoint(-25.0, -15.0), 4, true, false)
    check(ratioFlip == Transform64(-2.0, -1.0, 2.0, 1.0, flipX = true, flipY = true))
    val centered = MoveHandleGeometry.resize(plain, down, MovePoint(150.0, 75.0), 4, false, false, alt = true)
    check(centered == Transform64(-50.0, -25.0, 200.0, 100.0))
    val centeredFlip = MoveHandleGeometry.resize(plain, MovePoint(100.0, 25.0), MovePoint(25.0, 25.0),
        3, false, false, alt = true)
    check(centeredFlip == Transform64(25.0, 0.0, 50.0, 50.0, flipX = true))
    // The grabbed side's inactive axis is unaffected without ratio lock.
    check(MoveHandleGeometry.resize(plain, MovePoint(100.0, 25.0), MovePoint(150.0, 900.0),
        3, false, false) == plain.copy(width = 150.0))
    check(MoveHandleGeometry.resize(plain, down, MovePoint(300_001.0, 50.0), 4, false, false) == plain)
    check(MoveHandleGeometry.resize(plain, down, MovePoint(Double.NaN, 50.0), 4, false, false) == plain)
    val fractional = Transform64(-.5, .5, 100.0, 50.0, -12.5)
    check(MoveHandleGeometry.rotate(fractional, MovePoint(200.0, 25.5), MovePoint(200.0, 25.5), false) ==
        fractional.copy(x = -1.0, y = 1.0, rotation = -13.0))
    check(MoveHandleGeometry.rotate(plain, MovePoint(100.0, 25.0), MovePoint(50.0, 75.0), false) ==
        plain.copy(rotation = 90.0))
    val rotationBase = plain.copy(rotation = 352.5, flipX = true)
    check(MoveHandleGeometry.rotate(rotationBase, MovePoint(100.0, 25.0), MovePoint(100.0, 25.0), true) ==
        rotationBase.copy(rotation = 360.0))
    val negativeBase = plain.copy(rotation = -7.5)
    check(MoveHandleGeometry.rotate(negativeBase, MovePoint(100.0, 25.0), MovePoint(100.0, 25.0), true) ==
        negativeBase.copy(rotation = -15.0))
    check(MoveHandleGeometry.rotate(plain, down, MovePoint(Double.POSITIVE_INFINITY, Double.NaN), false) == plain)

    // Rotated edges remain draggable between their small midpoint handles, at every zoom and display density.
    val wide = Transform64(100.0, 100.0, 400.0, 300.0, 37.0)
    for (zoom in listOf(.5, 1.0, 3.0)) for (density in listOf(1.0, 2.0)) {
        val overlay = MoveHandleGeometry.overlay(wide, zoom * density, MovePoint(57.0, -30.0), density)
        for (index in overlay.handles.indices) {
            check(overlay.hit(overlay.handles[index]) == MoveHandleMode.Resize(index))
        }
        check(overlay.hit(overlay.rotationHandle) == MoveHandleMode.Rotate)
        check(near(hypot(overlay.rotationHandle.x - overlay.handles[1].x,
            overlay.rotationHandle.y - overlay.handles[1].y), 28 * density))
        for ((a, b, expected) in listOf(Triple(0, 2, 1), Triple(2, 4, 3), Triple(4, 6, 5), Triple(6, 0, 7))) {
            val quarter = MovePoint(overlay.handles[a].x * .75 + overlay.handles[b].x * .25,
                overlay.handles[a].y * .75 + overlay.handles[b].y * .25)
            check(overlay.hit(quarter) == MoveHandleMode.Resize(expected))
        }
        check(overlay.hit(MovePoint(57 + 300 * zoom * density, -30 + 250 * zoom * density)) == null)
        val radians = 37 * Math.PI / 180
        val top = overlay.handles[1]
        val offEdge = MovePoint(top.x - sin(radians) * 10.01 * density, top.y + cos(radians) * 10.01 * density)
        check(overlay.hit(offEdge) == null)
    }
    val tiny = MoveHandleGeometry.overlay(Transform64(0.0, 0.0, 1.0, 1.0), 1.0, MovePoint(0.0, 0.0))
    check(tiny.hit(MovePoint(.5, .5)) == MoveHandleMode.Resize(0))
    check(tiny.hit(MovePoint(Double.NaN, .5)) == null)
    val snapBox = Transform64(10.0, 10.0, 100.0, 100.0)
    val sideDown = MovePoint(110.0, 60.0)
    val targets = MoveSnapTargets(listOf(0.0, 150.0, 300.0), listOf(0.0, 150.0, 200.0))
    val sideSnap = MoveHandleGeometry.resizeSnapped(snapBox, sideDown, MovePoint(147.0, 60.0),
        3, false, false, false, true, targets, 2.0)
    check(sideSnap == MoveResult(snapBox.copy(width = 140.0), snapX = 150.0))
    check(MoveHandleGeometry.resizeSnapped(snapBox, sideDown, MovePoint(130.0, 60.0),
        3, false, false, false, true, targets, 2.0) == MoveResult(snapBox.copy(width = 120.0)))
    check(MoveHandleGeometry.resizeSnapped(snapBox, sideDown, MovePoint(147.0, 60.0),
        3, false, false, false, false, targets, 2.0) == MoveResult(snapBox.copy(width = 137.0)))
    val cornerDown = MovePoint(110.0, 110.0)
    check(MoveHandleGeometry.resizeSnapped(snapBox, cornerDown, MovePoint(147.0, 148.0),
        4, false, false, false, true, targets, 2.0) ==
        MoveResult(snapBox.copy(width = 140.0, height = 140.0), snapX = 150.0, snapY = 150.0))
    // Only one guide wins for proportional scaling, with the other dimension following the ratio.
    check(MoveHandleGeometry.resizeSnapped(snapBox, cornerDown, MovePoint(146.0, 148.0),
        4, true, false, false, true, targets, 2.0) ==
        MoveResult(snapBox.copy(width = 140.0, height = 140.0), snapX = 150.0))
    check(MoveHandleGeometry.resizeSnapped(snapBox, cornerDown, MovePoint(146.0, 148.0),
        4, false, true, false, true, targets.copy(xs = listOf(152.0)), 2.0) ==
        MoveResult(snapBox.copy(width = 140.0, height = 140.0), snapY = 150.0))
    check(MoveHandleGeometry.resizeSnapped(snapBox, sideDown, MovePoint(147.0, 60.0),
        3, false, false, true, true, targets, 2.0) ==
        MoveResult(snapBox.copy(x = -30.0, width = 180.0), snapX = 150.0))
    check(MoveHandleGeometry.resizeSnapped(snapBox, sideDown, MovePoint(-3.0, 60.0),
        3, false, false, false, true, targets, 2.0) ==
        MoveResult(snapBox.copy(x = 0.0, width = 10.0, flipX = true), snapX = 0.0))
    // A full-turn upright transform snaps, while a turned edge is deliberately left free as on Mac.
    check(MoveHandleGeometry.resizeSnapped(snapBox.copy(rotation = 360.0), sideDown, MovePoint(147.0, 60.0),
        3, false, false, false, true, targets, 4.0, screenScale = 2.0) ==
        sideSnap.copy(box = sideSnap.box.copy(rotation = 360.0)))
    val turned = snapBox.copy(rotation = 20.0)
    check(MoveHandleGeometry.resizeSnapped(turned, sideDown, MovePoint(147.0, 60.0),
        3, false, false, false, true, targets, 2.0) ==
        MoveResult(MoveHandleGeometry.resize(turned, sideDown, MovePoint(147.0, 60.0), 3, false, false)))
    return "Mac handles: rotated eight points and full-edge hits; fixed screen radius/rotation reach; original-grab offset; opposite/Alt-center anchor; Shift XOR ratio projection; positive-size cross-anchor flips; invalid candidate rejection; whole-pixel/degree and signed 15-degree rounding; upright resize snapping, nearest proportional guide and center/flip derivatives."
}
