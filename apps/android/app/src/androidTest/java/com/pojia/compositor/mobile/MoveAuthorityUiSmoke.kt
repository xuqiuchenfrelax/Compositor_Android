package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose controls, attached shipping Canvas input, GLES completion and public workspace save. */
internal fun moveAuthorityUiSmoke(instrumentation: Instrumentation, wide: Boolean): String {
    val context = instrumentation.targetContext
    val label = if (wide) "move-authority-wide" else "move-authority-phone"
    MoveInspectorFixtureSmoke(instrumentation, label).use { fixture -> with(fixture) {
        check((activity.resources.configuration.screenWidthDp >= 700) == wide) {
            "Authority selector requires ${if (wide) "wide" else "phone"} layout"
        }
        val automation = editorUiAutomation(instrumentation)
        val ids = mutableMapOf<String, Long>()
        val saveRoot = File(context.cacheDir, "$label-${System.nanoTime()}").apply { check(mkdir()) }
        var pointerTime = 0L
        var pointerDown = 0L
        fun fresh(name: String): AccessibilityNodeInfo? {
            automation.clearCache()
            return node(automation.rootInActiveWindow, name)
        }
        fun countDescription(root: AccessibilityNodeInfo, description: String): Int {
            var count = if (root.contentDescription?.toString() == description) 1 else 0
            for (index in 0 until root.childCount) root.getChild(index)?.let { count += countDescription(it, description) }
            return count
        }
        fun structuralNode(root: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.contentDescription?.toString() == description) return root
            for (index in 0 until root.childCount)
                structuralNode(root.getChild(index), description)?.let { return it }
            return null
        }
        fun rowAncestors(name: String, description: AccessibilityNodeInfo?): JSONArray {
            val trace = JSONArray()
            var ancestor = description
            while (ancestor != null) {
                val current = ancestor
                trace.put(JSONObject().put("description", current.contentDescription?.toString())
                    .put("bounds", Rect().also(current::getBoundsInScreen).toShortString())
                    .put("visible", current.isVisibleToUser)
                    .put("selectCount", countDescription(current, "Select $name"))
                    .put("includeCount", countDescription(current, "Include $name in selection"))
                    .put("visibilityCount", countDescription(current, "Visibility $name"))
                    .put("fixtureRowCount", ids.keys.sumOf { countDescription(current, "Select $it") }))
                if (current.contentDescription?.toString() == "Layer list") break
                ancestor = current.parent
            }
            return trace
        }
        fun rowOwner(name: String, description: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var ancestor: AccessibilityNodeInfo? = description
            while (ancestor != null && ancestor.contentDescription?.toString() != "Layer list") {
                val current = ancestor
                if (countDescription(current, "Select $name") == 1 &&
                    countDescription(current, "Include $name in selection") == 1 &&
                    countDescription(current, "Visibility $name") == 1) {
                    val rows = ids.keys.sumOf { countDescription(current, "Select $it") }
                    check(rows == 1) { "Description resolved to multiple layer rows: $name" }
                    return current
                }
                ancestor = current.parent
            }
            // A partially clipped row can expose its Select leaf before its child
            // checkbox is in the accessibility tree. Keep scrolling until all of
            // the original controls and pixel sample are observable together.
            return null
        }
        data class LayerRowObservation(val description: AccessibilityNodeInfo, val owner: AccessibilityNodeInfo,
            val selectionControl: AccessibilityNodeInfo, val visibilityControl: AccessibilityNodeInfo,
            val ownerBounds: Rect, val listBounds: Rect, val visibleBounds: Rect,
            val sampleX: Int, val sampleY: Int)
        fun row(name: String, accept: (LayerRowObservation) -> Boolean = { true }): LayerRowObservation {
            val layerName = when {
                name.startsWith("Select ") -> name.removePrefix("Select ")
                name.startsWith("Include ") && name.endsWith(" in selection") ->
                    name.removePrefix("Include ").removeSuffix(" in selection")
                name.startsWith("Visibility ") -> name.removePrefix("Visibility ")
                else -> error("Unknown layer control: $name")
            }
            val started = System.nanoTime()
            val deadline = started + TimeUnit.SECONDS.toNanos(15)
            val samples = JSONArray()
            var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            try {
                while (System.nanoTime() < deadline) {
                    val sampleStarted = System.nanoTime()
                    automation.clearCache()
                    val root = automation.rootInActiveWindow
                    val list = node(root, "Layer list")
                    val candidate = structuralNode(root, name)
                    val canonicalSelection = structuralNode(root, "Select $layerName")
                    val descriptionBounds = Rect().also { candidate?.getBoundsInScreen(it) }
                    val canonicalBounds = Rect().also { canonicalSelection?.getBoundsInScreen(it) }
                    val listBounds = Rect().also { list?.getBoundsInScreen(it) }
                    val canonicalIntersection = Rect(canonicalBounds)
                    val canonicalObservable = !canonicalBounds.isEmpty && canonicalIntersection.intersect(listBounds)
                    val descriptionCount = root?.let { countDescription(it, name) } ?: 0
                    val canonicalCount = root?.let { countDescription(it, "Select $layerName") } ?: 0
                    val record = JSONObject().put("startedAtNanos", sampleStarted)
                        .put("descriptionBounds", descriptionBounds.toShortString()).put("listBounds", listBounds.toShortString())
                        .put("descriptionVisible", candidate?.isVisibleToUser == true).put("predicateEvaluated", false)
                        .put("descriptionCount", descriptionCount).put("canonicalSelectCount", canonicalCount)
                        .put("canonicalSelectBounds", canonicalBounds.toShortString()).put("canonicalSelectObservable", canonicalObservable)
                    samples.put(record)
                    record.put("descriptionAncestors", rowAncestors(layerName, candidate))
                        .put("canonicalSelectAncestors", rowAncestors(layerName, canonicalSelection))
                    check(descriptionCount <= 1 && canonicalCount <= 1) { "Layer description is ambiguous: $name" }
                    // Compose can export an offscreen fake Select leaf while omitting its
                    // checkbox. Require measurable row content before checking its structure.
                    val owner = if (canonicalObservable) rowOwner(layerName, checkNotNull(canonicalSelection)) else null
                    record.put("completeRowOwnerAvailable", owner != null)
                    val bounds = Rect().also { owner?.getBoundsInScreen(it) }
                    val visible = Rect(bounds)
                    val intersects = visible.intersect(listBounds)
                    val canonicalContained = !bounds.isEmpty && !canonicalBounds.isEmpty && bounds.contains(canonicalBounds)
                    val selectionControl = owner?.let { checkNotNull(structuralNode(it, "Include $layerName in selection")) {
                        "Actual row selection control is missing: $layerName"
                    } }
                    val visibilityControl = owner?.let { checkNotNull(structuralNode(it, "Visibility $layerName")) {
                        "Actual row visibility control is missing: $layerName"
                    } }
                    val selectionBounds = Rect().also { selectionControl?.getBoundsInScreen(it) }
                    val visibilityBounds = Rect().also { visibilityControl?.getBoundsInScreen(it) }
                    val x = bounds.left + 4; val y = bounds.centerY()
                    val centerInside = !bounds.isEmpty && listBounds.contains(bounds.centerX(), bounds.centerY())
                    val safePixels = intersects && visible.width() > 12 && visible.height() > 6 &&
                        (-1..1).all { dy -> (0..2).all { dx ->
                            visible.contains(x + dx, y + dy) && !selectionBounds.contains(x + dx, y + dy) &&
                                !visibilityBounds.contains(x + dx, y + dy)
                        } }
                    val geometryReady = candidate?.isVisibleToUser == true && owner?.isVisibleToUser == true &&
                        canonicalContained && centerInside && safePixels && selectionControl != null && visibilityControl != null
                    record.put("ownerBounds", bounds.toShortString()).put("visibleBounds", visible.toShortString())
                        .put("intersectsList", intersects)
                        .put("canonicalSelectBounds", canonicalBounds.toShortString()).put("canonicalSelectContained", canonicalContained)
                        .put("ownerVisible", owner?.isVisibleToUser == true)
                        .put("selectionBounds", selectionBounds.toShortString()).put("visibilityBounds", visibilityBounds.toShortString())
                        .put("ownerCenterX", bounds.centerX()).put("ownerCenterY", bounds.centerY())
                        .put("ownerCenterInsideList", centerInside).put("safeNinePixels", safePixels)
                        .put("sampleX", x).put("sampleY", y).put("geometryReady", geometryReady)
                    val current = if (geometryReady) LayerRowObservation(checkNotNull(candidate), checkNotNull(owner),
                        checkNotNull(selectionControl), checkNotNull(visibilityControl), bounds, listBounds, visible, x, y) else null
                    if (current != null) record.put("predicateEvaluated", true)
                    val ready = current != null && accept(current)
                    val completed = System.nanoTime()
                    val accepted = ready && completed <= deadline
                    record.put("completedAtNanos", completed).put("predicateSatisfied", ready).put("accepted", accepted)
                    // Return this exact successful observation; do not call its predicate again.
                    if (accepted) return checkNotNull(current)
                    if (completed >= deadline) break
                    if (!geometryReady) {
                        val scrollBounds = if (bounds.isEmpty) canonicalBounds else bounds
                        if (!scrollBounds.isEmpty && !listBounds.isEmpty) direction =
                            if (scrollBounds.centerY() >= listBounds.centerY()) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        if (list?.actionList?.none { it.id == direction } == true)
                            direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        val scrollStarted = System.nanoTime()
                        if (scrollStarted >= deadline) break
                        record.put("scrollAction", direction).put("scrollStartedAtNanos", scrollStarted)
                        record.put("scrollAccepted", list?.performAction(direction) == true)
                            .put("scrollCompletedAtNanos", System.nanoTime())
                        instrumentation.waitForIdleSync()
                    }
                    if (System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
                }
                error("Layer row readiness failed: $name")
            } finally {
                observation("row-readiness", JSONObject().put("name", name).put("startedAtNanos", started)
                    .put("deadlineNanos", deadline).put("completedAtNanos", System.nanoTime()).put("samples", samples))
            }
        }
        fun pointer(action: Int, x: Float, y: Float, modifiers: Int = 0) {
            pointerTime = maxOf(SystemClock.uptimeMillis(), pointerTime + 1)
            if (action == MotionEvent.ACTION_DOWN) pointerDown = pointerTime
            val buttons = if (action == MotionEvent.ACTION_UP) 0 else MotionEvent.BUTTON_PRIMARY
            val event = MotionEvent.obtain(pointerDown, pointerTime, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
                modifiers, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try {
                instrumentation.runOnMainSync {
                    check(activity.window.decorView.hasWindowFocus() &&
                        activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        "Compositor lost foreground before authority pointer"
                    }
                    val origin = IntArray(2); activity.window.decorView.getLocationOnScreen(origin)
                    event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                    if (action == MotionEvent.ACTION_DOWN) {
                        val hover = MotionEvent.obtain(event).apply { this.action = MotionEvent.ACTION_HOVER_MOVE }
                        try { activity.window.decorView.dispatchGenericMotionEvent(hover) } finally { hover.recycle() }
                    }
                    activity.window.decorView.dispatchTouchEvent(event)
                }
            } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        fun mouseRow(name: String, modifiers: Int) {
            val bounds = row("Select $name").ownerBounds
            // The name lies between the selection and visibility controls.
            val x = bounds.exactCenterX()
            pointer(MotionEvent.ACTION_DOWN, x, bounds.exactCenterY(), modifiers)
            pointer(MotionEvent.ACTION_UP, x, bounds.exactCenterY(), modifiers)
            // Move the mouse into the visible Canvas, away from every layer row. This
            // leaves the normal indication enabled; exact background samples remain required.
            val canvasBounds = Rect()
            instrumentation.runOnMainSync {
                check(canvas.getLocalVisibleRect(canvasBounds))
                val location = IntArray(2); canvas.getLocationOnScreen(location)
                canvasBounds.offset(location[0], location[1])
            }
            val listBounds = Rect().also(checkNotNull(fresh("Layer list"))::getBoundsInScreen)
            val parkX = canvasBounds.exactCenterX(); val parkY = canvasBounds.exactCenterY()
            check(!canvasBounds.isEmpty && canvasBounds.contains(parkX.toInt(), parkY.toInt()) &&
                !listBounds.contains(parkX.toInt(), parkY.toInt()) && !bounds.contains(parkX.toInt(), parkY.toInt())) {
                "Mouse parking point must be in the visible Canvas and outside Layers"
            }
            pointerTime = maxOf(SystemClock.uptimeMillis(), pointerTime + 1)
            val hover = MotionEvent.obtain(pointerDown, pointerTime, MotionEvent.ACTION_HOVER_MOVE, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
                arrayOf(MotionEvent.PointerCoords().apply { this.x = parkX; y = parkY; pressure = 0f; size = 1f }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            var handled = false
            val started = System.nanoTime()
            try {
                instrumentation.runOnMainSync {
                    check(activity.window.decorView.hasWindowFocus() &&
                        activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        "Compositor lost foreground before authority mouse parking"
                    }
                    val origin = IntArray(2); activity.window.decorView.getLocationOnScreen(origin)
                    hover.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                    handled = activity.window.decorView.dispatchGenericMotionEvent(hover)
                }
            } finally { hover.recycle() }
            instrumentation.waitForIdleSync()
            observation("mouse-row-park", JSONObject().put("name", name)
                .put("action", MotionEvent.ACTION_HOVER_MOVE).put("source", InputDevice.SOURCE_MOUSE)
                .put("buttons", 0).put("modifiers", 0).put("eventTime", pointerTime)
                .put("screenX", parkX).put("screenY", parkY).put("handled", handled)
                .put("canvasVisibleBounds", canvasBounds.toShortString()).put("listBounds", listBounds.toShortString())
                .put("startedAtNanos", started).put("completedAtNanos", System.nanoTime()))
        }
        fun clickLayer(name: String) {
            if (name.startsWith("Select ")) {
                mouseRow(name.removePrefix("Select "), 0)
            } else {
                val target = row(name).description
                check(target.isClickable && target.isEnabled &&
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
            }
        }
        fun controlOwner(name: String): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
            automation.clearCache()
            val root = checkNotNull(automation.rootInActiveWindow)
            check(countDescription(root, name) == 1) { "Control description must be unique: $name" }
            val description = checkNotNull(node(root, name))
            var ancestor: AccessibilityNodeInfo? = description
            // A disabled Compose button is still clickable, but has no click action.
            // Its synthetic description leaf does not inherit the owner's disabled bit.
            while (ancestor != null && !ancestor.isClickable) ancestor = ancestor.parent
            val owner = checkNotNull(ancestor) { "Control owner is missing: $name" }
            val bounds = Rect().also(owner::getBoundsInScreen)
            val descriptionBounds = Rect().also(description::getBoundsInScreen)
            check(countDescription(owner, name) == 1 && !bounds.isEmpty && bounds.contains(descriptionBounds)) {
                "Control owner must contain its unique description: $name"
            }
            return description to owner
        }
        fun layerHighlight(name: String, expected: Boolean) {
            var last = JSONObject()
            val samples = JSONArray()
            val screenshot = File(context.filesDir, "p40-$label-row-${System.nanoTime()}.png")
            try {
                // One bounded observation owns checkbox membership, the row action and pixels.
                // The row is a button so an included layer can still be selected on its own.
                row("Select $name") { current ->
                    val sampleStarted = System.nanoTime()
                    val description = current.description; val owner = current.owner
                    val bounds = current.ownerBounds; val visible = current.visibleBounds
                    val selectionControl = current.selectionControl; val visibilityControl = current.visibilityControl
                    val selectionBounds = Rect().also(selectionControl::getBoundsInScreen)
                    val visibilityBounds = Rect().also(visibilityControl::getBoundsInScreen)
                    val x = current.sampleX; val y = current.sampleY
                    check(current.listBounds.contains(bounds.centerX(), bounds.centerY()) &&
                        visible.width() > 12 && visible.height() > 6 &&
                        (-1..1).all { dy -> (0..2).all { dx ->
                            visible.contains(x + dx, y + dy) && !selectionBounds.contains(x + dx, y + dy) &&
                                !visibilityBounds.contains(x + dx, y + dy)
                        } }) { "Layer background sample overlaps a control or clipping edge: $name" }
                    val image = checkNotNull(automation.takeScreenshot()) { "Layer highlight screenshot unavailable" }
                    val colors = mutableListOf<Int>()
                    try {
                        check(visible.right <= image.width && visible.bottom <= image.height)
                        for (dy in -1..1) for (dx in 0..2) colors += image.getPixel(x + dx, y + dy)
                        screenshot.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { image.recycle() }
                    // Exact un-antialiased row padding, from the fixed dark palette. It is
                    // independent of the checkbox fill and semantic checked bit.
                    val expectedColor = if (expected) 0xff464646.toInt() else 0xff303030.toInt()
                    val rowAction = owner.isEnabled && owner.isClickable &&
                        owner.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                    val ready = selectionControl.isCheckable && selectionControl.isChecked == expected &&
                        rowAction && colors.all { it == expectedColor }
                    last = JSONObject().put("name", name).put("expectedSelected", expected)
                        .put("startedAtNanos", sampleStarted).put("completedAtNanos", System.nanoTime())
                        .put("predicateSatisfied", ready)
                        .put("descriptionSelected", description.isSelected).put("descriptionChecked", description.isChecked)
                        .put("ownerSelected", owner.isSelected).put("ownerChecked", owner.isChecked)
                        .put("ownerCheckable", owner.isCheckable).put("ownerClickable", owner.isClickable)
                        .put("ownerSupportsSelectionClick", rowAction)
                        .put("ownerActions", JSONArray(owner.actionList.map { it.id }))
                        .put("selectionCheckboxChecked", selectionControl.isChecked)
                        .put("selectionCheckboxCheckable", selectionControl.isCheckable)
                        .put("ownerBounds", bounds.toShortString()).put("visibleBounds", visible.toShortString())
                        .put("sampleX", x).put("sampleY", y).put("expectedARGB", expectedColor)
                        .put("sampleARGB", JSONArray(colors)).put("screenshot", screenshot.name)
                    samples.put(JSONObject(last.toString()))
                    ready
                }
                check(last.getBoolean("selectionCheckboxCheckable") &&
                    last.getBoolean("selectionCheckboxChecked") == expected &&
                    last.getBoolean("ownerSupportsSelectionClick") &&
                    (0 until last.getJSONArray("sampleARGB").length()).all {
                        last.getJSONArray("sampleARGB").getInt(it) == last.getInt("expectedARGB")
                    }) { "Layer highlight differs: $name" }
            } finally { observation("row-highlight", last.put("samples", samples)) }
        }
        fun selection(names: Set<String>, primary: String?) {
            val roots = names.map { checkNotNull(ids[it]) }.toSet()
            val active = primary?.let { checkNotNull(ids[it]) } ?: 0
            waitUntil("Authority list selection differs: $names / $primary") {
                val native = editor.selection()
                native.roots.toSet() == roots && native.primary == active && canvas.layerId == active &&
                    editor.state().document.activeLayerId == active
            }
            for (name in ids.keys) {
                layerHighlight(name, name in names)
                val expectedTarget = activity.getString(if (name == primary)
                    R.string.layer_panel_editing_target_state else R.string.layer_panel_not_editing_target_state)
                val current = row("Include $name in selection") {
                    it.selectionControl.isCheckable && it.selectionControl.isChecked == (name in names) &&
                        it.owner.stateDescription?.toString() == expectedTarget
                }
                val checkbox = current.selectionControl
                check(checkbox.isCheckable && checkbox.isChecked == (name in names)) { "Layer checkbox differs: $name" }
                check(current.owner.stateDescription?.toString() == expectedTarget) { "Layer editing target differs: $name" }
            }
            observation("selection", JSONObject().put("names", JSONArray(names.toList())).put("primary", primary)
                .put("nativeEpoch", editor.selection().epoch))
        }
        fun gpu(): GpuStats {
            val latch = CountDownLatch(1); var stats: GpuStats? = null
            canvas.debugGpuStats { stats = it; latch.countDown() }
            check(latch.await(10, TimeUnit.SECONDS))
            return checkNotNull(stats)
        }
        fun frame() {
            canvas.requestRender()
            waitUntil("Attached authority GLES scene did not complete") {
                gpu().let { it.currentContext && it.usesTiles && it.uploads > 0 &&
                    it.sceneGeneration == editor.currentSceneGeneration() }
            }
        }
        fun screen(x: Double, y: Double): Pair<Float, Float> {
            stableBounds()
            val location = IntArray(2)
            var overlay: MoveHandleOverlay? = null
            instrumentation.runOnMainSync {
                canvas.getLocationOnScreen(location)
                overlay = canvas.moveOverlay(Transform64(x, y, 1.0, 1.0))
            }
            val topLeft = checkNotNull(overlay).handles[0]
            return location[0] + topLeft.x.toFloat() to location[1] + topLeft.y.toFloat()
        }
        fun displayedPixel(x: Double, y: Double, color: Int) {
            frame()
            val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1); var result = -1
            try {
                PixelCopy.request(canvas, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                check(latch.await(10, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "GLES PixelCopy failed: $result" }
                val (sx, sy) = screen(x, y)
                val location = IntArray(2); instrumentation.runOnMainSync { canvas.getLocationOnScreen(location) }
                val actual = bitmap.getPixel((sx - location[0]).toInt(), (sy - location[1]).toInt())
                check(actual == color) { "Attached GLES pixel ($x,$y): ${actual.toUInt().toString(16)} != ${color.toUInt().toString(16)}" }
                observation("gles-pixel", JSONObject().put("x", x).put("y", y).put("rgba", actual))
            } finally { bitmap.recycle() }
        }
        fun drag(x: Double, y: Double, dx: Double, dy: Double, persistent: Boolean) {
            val (sx, sy) = screen(x, y)
            val scale = canvas.zoomPercent / 100
            pointer(MotionEvent.ACTION_DOWN, sx, sy)
            waitUntil("Authority canvas DOWN was not adopted") { editor.hasActiveTransformDraft && canvas.moveInProgress }
            pointer(MotionEvent.ACTION_MOVE, sx + (dx * scale).toFloat(), sy + (dy * scale).toFloat(), KeyEvent.META_CTRL_ON)
            val expectedX = checkNotNull(canvas.moveBox).x
            waitUntil("Authority preview did not accept MOVE") { canvas.moveAcceptedSequence > 0 && canvas.moveBox != null }
            pointer(MotionEvent.ACTION_UP, sx + ((dx + 15) * scale).toFloat(), sy + ((dy + 15) * scale).toFloat(), KeyEvent.META_CTRL_ON)
            if (!persistent) settled() else check(canvas.movePersistent && editor.hasActiveTransformDraft)
            observation("pointer", JSONObject().put("persistent", persistent).put("boxXBeforeUp", expectedX))
        }
        fun expectedPixels(layers: List<LayerState>): ByteArray {
            val output = ByteArray(256 * 256 * 4)
            val colors = mapOf("Authority Red" to intArrayOf(255, 0, 0), "Authority Blue" to intArrayOf(0, 0, 255),
                "Authority Green" to intArrayOf(0, 255, 0), "Authority Yellow" to intArrayOf(255, 255, 0))
            for (layer in layers) {
                val color = colors[layer.name] ?: continue
                val box = layer.transform
                check(box.rotation == 0.0 && !box.flipX && !box.flipY)
                for (y in box.y.toInt() until (box.y + box.height).toInt())
                    for (x in box.x.toInt() until (box.x + box.width).toInt()) if (x in 0..255 && y in 0..255) {
                        val at = (y * 256 + x) * 4
                        repeat(3) { output[at + it] = color[it].toByte() }; output[at + 3] = -1
                    }
            }
            return output
        }
        fun assertPixels(layers: List<LayerState>) {
            check(pixels().contentEquals(expectedPixels(layers))) { "Full preview pixels differ from independent opaque-rectangle reference" }
        }
        try {
            installAuthority {
                NativeEditor(256, false).also { candidate ->
                    fun layer(name: String, color: ByteArray, box: Transform64): Long {
                        val id = candidate.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply { put(color); rewind() })
                        candidate.renameLayer(id, name); candidate.setTransform(id, box); ids[name] = id
                        return id
                    }
                    val red = layer("Authority Red", byteArrayOf(-1,0,0,-1), Transform64(40.0,64.0,80.0,64.0))
                    layer("Authority Blue", byteArrayOf(0,0,-1,-1), Transform64(80.0,80.0,64.0,64.0))
                    val folder = candidate.addGroup(); candidate.renameLayer(folder, "Authority Folder"); ids["Authority Folder"] = folder
                    val green = layer("Authority Green", byteArrayOf(0,-1,0,-1), Transform64(152.0,160.0,40.0,64.0))
                    val yellow = layer("Authority Yellow", byteArrayOf(-1,-1,0,-1), Transform64(192.0,160.0,40.0,64.0))
                    candidate.reparentLayer(green, folder); candidate.reparentLayer(yellow, folder)
                    candidate.selectLayer(red)
                }
            }
            click("Move Show Controls")
            check(!canvas.moveShowControls)
            if (!wide) {
                click("Layers")
                val minimumHeight = kotlin.math.ceil(96.0 * activity.resources.displayMetrics.density).toInt()
                var viewport = Rect()
                waitUntil("Phone layer list has less than 96dp of scroll viewport") {
                    val list = fresh("Layer list")
                    viewport = Rect().also { list?.getBoundsInScreen(it) }
                    list?.isVisibleToUser == true && viewport.width() > 0 && viewport.height() >= minimumHeight
                }
                observation("phone-layer-list-capacity", JSONObject().put("scrollViewport", viewport.toShortString())
                    .put("minimumHeightPx", minimumHeight).put("minimumHeightDp", 96)
                    .put("density", activity.resources.displayMetrics.density))
            }
            selection(setOf("Authority Red"), "Authority Red")
            val content = editor.currentContentIdentity()
            mouseRow("Authority Green", 0)
            selection(setOf("Authority Green"), "Authority Green")
            mouseRow("Authority Red", KeyEvent.META_SHIFT_ON)
            selection(setOf("Authority Green", "Authority Blue", "Authority Red"), "Authority Red")
            mouseRow("Authority Blue", KeyEvent.META_SHIFT_ON)
            selection(setOf("Authority Green", "Authority Blue"), "Authority Blue")
            mouseRow("Authority Yellow", KeyEvent.META_META_ON)
            selection(setOf("Authority Yellow", "Authority Green", "Authority Blue"), "Authority Yellow")
            mouseRow("Authority Blue", KeyEvent.META_CTRL_ON)
            selection(setOf("Authority Yellow", "Authority Green"), "Authority Yellow")
            val selectedRow = row("Select Authority Yellow").owner
            check(selectedRow.isEnabled && selectedRow.isClickable &&
                selectedRow.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                selectedRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "An included layer must remain accessible for selecting only that layer"
            }
            instrumentation.waitForIdleSync()
            selection(setOf("Authority Yellow"), "Authority Yellow")
            check(editor.currentContentIdentity() == content && !workspace.snapshot().projectDirty)
            clickLayer("Select Authority Red")
            selection(setOf("Authority Red"), "Authority Red")
            clickLayer("Include Authority Red in selection")
            selection(emptySet(), null)
            check(canvas.moveBox == null && !editor.hasActiveTransformDraft)
            val empty = editor.selection()
            check(empty.roots.isEmpty() && empty.primary == 0L && canvas.layerId == 0L &&
                editor.state().document.activeLayerId == 0L)
            val (description, transformControl) = controlOwner("Start Transform")
            val clickAction = transformControl.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            observation("empty-transform-control", JSONObject()
                .put("descriptionEnabled", description.isEnabled).put("descriptionClickable", description.isClickable)
                .put("ownerEnabled", transformControl.isEnabled).put("ownerClickable", transformControl.isClickable)
                .put("ownerVisible", transformControl.isVisibleToUser).put("ownerSupportsClick", clickAction)
                .put("ownerActions", JSONArray(transformControl.actionList.map { it.id }))
                .put("ownerBounds", Rect().also(transformControl::getBoundsInScreen).toShortString()))
            check(transformControl.isVisibleToUser && !transformControl.isEnabled && !clickAction) {
                "Empty selection must disable the actual Transform button and remove its click action"
            }
            if (!wide) click("Layers")

            // Empty selection can still begin an atomic Auto Select press on the topmost blue layer.
            click("Move Auto Select"); check(canvas.moveAutoSelect)
            val beforeAuto = editor.state(); val beforeAutoContent = editor.currentContentIdentity()
            drag(100.0, 100.0, 8.0, 4.0, false)
            val afterAuto = editor.state()
            check(editor.selection().roots == listOf(ids.getValue("Authority Blue")))
            check(afterAuto.revision == beforeAuto.revision + 2 && editor.currentContentIdentity() != beforeAutoContent)
            check(afterAuto.layers.all { layer ->
                val original = beforeAuto.layers.single { it.id == layer.id }
                layer.transform == if (layer.id == ids.getValue("Authority Blue"))
                    original.transform.copy(x = original.transform.x + 8, y = original.transform.y + 4) else original.transform
            })
            assertPixels(afterAuto.layers); displayedPixel(110.0, 110.0, 0xff0000ff.toInt())
            if (!wide) click("Layers")
            selection(setOf("Authority Blue"), "Authority Blue")
            if (!wide) click("Layers")
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == beforeAuto.layers && editor.currentContentIdentity() == beforeAutoContent)
            click("Move Auto Select"); check(!canvas.moveAutoSelect)

            // One selected group moves both descendants; Cancel leaves committed metadata/history unchanged.
            if (!wide) click("Layers")
            clickLayer("Select Authority Folder"); selection(setOf("Authority Folder"), "Authority Folder")
            if (!wide) click("Layers")
            var before = editor.state(); var beforePixels = pixels()
            click("Start Transform")
            drag(190.0, 190.0, 6.0, -3.0, true)
            val groupPreview = before.layers.map { layer -> if (layer.parentId == ids.getValue("Authority Folder"))
                layer.copy(transform = layer.transform.copy(x = layer.transform.x + 6, y = layer.transform.y - 3)) else layer }
            waitUntil("Group preview metadata did not move both children") {
                editor.captureDisplayScene()?.use { scene ->
                    (before.layers.indices).map { scene.layerMetadata(it).transform } == groupPreview.map { it.transform }
                } == true
            }
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null)); assertPixels(groupPreview)
            displayedPixel(197.0, 190.0, 0xff00ff00.toInt())
            click("Cancel Transform"); settled()
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels().contentEquals(beforePixels))

            // Group + another root is one transaction and one history entry, without moving red.
            if (!wide) click("Layers")
            clickLayer("Include Authority Blue in selection")
            selection(setOf("Authority Folder", "Authority Blue"), "Authority Blue")
            if (!wide) click("Layers")
            before = editor.state(); beforePixels = pixels()
            click("Start Transform")
            drag(110.0, 110.0, 7.0, 5.0, true)
            val multiPreview = before.layers.map { layer ->
                if (layer.id == ids.getValue("Authority Blue") || layer.parentId == ids.getValue("Authority Folder"))
                    layer.copy(transform = layer.transform.copy(x = layer.transform.x + 7, y = layer.transform.y + 5)) else layer
            }
            waitUntil("Multi-root preview did not accept full geometry") {
                editor.captureDisplayScene()?.use { scene ->
                    (before.layers.indices).map { scene.layerMetadata(it).transform } == multiPreview.map { it.transform }
                } == true
            }
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null)); assertPixels(multiPreview)
            displayedPixel(147.0, 110.0, 0xff0000ff.toInt())
            click("Apply Transform"); settled()
            check(editor.state().revision == before.revision + 1 && editor.state().layers == multiPreview)
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == before.layers && pixels().contentEquals(beforePixels))
            click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == multiPreview); assertPixels(multiPreview)
            val savedPixels = pixels(); val savedState = editor.state()
            val saved = workspace.withFrozenIo(checkNotNull(workspace.token()), { lease ->
                lease.saveProject(context, File(saveRoot, "result.comp"), false, 64L * 1024 * 1024,
                    lease.memory.totalBytes + 1024 * 1024, label)
            }, {}).get(15, TimeUnit.SECONDS)
            check(saved.successful && saved.baselineUpdated && !workspace.snapshot().projectDirty)
            NativeEditor.loadProjectCandidate(context, File(saveRoot, "result.comp"), 64L * 1024 * 1024, 0).use { cold ->
                fun canonical(state: EditorState) = state.layers.map { layer ->
                    Triple(layer.copy(id = 0, parentId = 0, maskSourceId = 0),
                        state.layers.firstOrNull { it.id == layer.parentId }?.uuid,
                        state.layers.firstOrNull { it.id == layer.maskSourceId }?.uuid)
                }
                val reopenedState = cold.state()
                check(canonical(reopenedState) == canonical(savedState))
                check(reopenedState.layers.firstOrNull { it.id == reopenedState.document.activeLayerId }?.uuid ==
                    savedState.layers.firstOrNull { it.id == savedState.document.activeLayerId }?.uuid)
                val buffer = ByteBuffer.allocateDirect(256 * 256 * 4)
                checkNotNull(cold.captureDisplayScene()).use { it.draw(256,256,Viewport(0.0,0.0,256.0,256.0),buffer) }
                val reopened = ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
                check(reopened.contentEquals(savedPixels))
            }
            observation("saved-cold-reopened", JSONObject().put("pixelsSHA256", moveInspectorSha(savedPixels))
                .put("groupAndRootsMetadataExact", true).put("layout", if (wide) "wide" else "phone"))
            successful = true
            return "PASS: $label real Compose checkbox/Shift/Command/empty selection, Auto Select canvas→highlights, attached GLES preview/cancel, group+multi one-history Apply/Undo/Redo, .comp save+cold reopen"
        } catch (error: Throwable) { diagnose(error); throw error }
        finally { saveRoot.deleteRecursively() }
    } }
}
