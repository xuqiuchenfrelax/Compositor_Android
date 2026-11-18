package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Real SAF chooser, publication, recreation and provider receipt review use the retained shipping owner. */
internal fun projectExportUiSmoke(instrumentation: Instrumentation, killPrepare: Boolean = false): String {
    val context = instrumentation.targetContext
    fun status(message: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$message\n") })
    val publicationEnteredAt = AtomicLong(0)
    val publicationReturnedAt = AtomicLong(0)
    val publicationReleased = AtomicBoolean(false)
    fun timing(message: String) {
        runCatching { status("Project export timing: elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}ms $message") }
    }
    val receipts = ProjectExportReceipts(context)
    val reviewId = UUID.randomUUID().toString()
    receipts.write(ProjectExportReceipt(reviewId, ExportDocumentsProvider.tree("ok"), 77,
        "Compositor-$reviewId.comp", "UNKNOWN", emptyList()))
    instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    fun findMatching(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) findMatching(node.getChild(index), predicate)?.let { return it }
        return null
    }
    fun available(label: String): AccessibilityNodeInfo? = findMatching(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString()?.equals(label, true) == true
    }
    fun resource(id: String): AccessibilityNodeInfo? = findMatching(instrumentation.uiAutomation.rootInActiveWindow) {
        it.viewIdResourceName == id || it.viewIdResourceName?.endsWith(":id/$id") == true
    }
    fun exportUiFailure(label: String, lookup: () -> AccessibilityNodeInfo?, captured: AccessibilityNodeInfo?, enabled: Boolean?, clickable: Boolean?,
        actionResult: Boolean?, error: Exception) {
        // Preserve the failed action before finally dismisses the real picker; never retry its action.
        val prefix = "p20-export-failure07-${System.nanoTime()}"
        val record = JSONObject().put("schema", 1).put("label", label)
            .put("elapsedRealtimeMillis", android.os.SystemClock.elapsedRealtime())
            .put("enabled", enabled ?: JSONObject.NULL).put("clickable", clickable ?: JSONObject.NULL)
            .put("actionResult", actionResult ?: JSONObject.NULL)
            .put("nullMeaning", "Original short-circuit predicate did not evaluate this value")
            .put("error", error.toString()).put("scope", "Failure-time diagnosis only; no retry, refresh action or timeout change")
        fun node(value: AccessibilityNodeInfo): JSONObject {
            val bounds = android.graphics.Rect().also { value.getBoundsInScreen(it) }
            return JSONObject().put("windowId", value.windowId).put("package", value.packageName ?: JSONObject.NULL)
                .put("class", value.className ?: JSONObject.NULL).put("resourceId", value.viewIdResourceName ?: JSONObject.NULL)
                .put("text", value.text ?: JSONObject.NULL).put("description", value.contentDescription ?: JSONObject.NULL)
                .put("enabled", value.isEnabled).put("clickable", value.isClickable).put("visible", value.isVisibleToUser)
                .put("focused", value.isFocused).put("selected", value.isSelected).put("bounds", bounds.toShortString())
                .put("actions", JSONArray(value.actionList.map {
                    JSONObject().put("id", it.id).put("label", it.label ?: JSONObject.NULL).put("name", it.toString())
                })).put("supportsActionClick", value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        }
        fun capture(name: String, action: () -> Any) {
            runCatching { record.put(name, action()) }.onFailure { record.put("${name}Error", it.toString()) }
        }
        capture("capturedNode") { captured?.let { node(it) } ?: JSONObject.NULL }
        capture("freshLookupAncestors") {
            val ancestors = JSONArray()
            var value = lookup()
            record.put("freshLookupMatchesCapturedWindow", if (value != null && captured != null) value.windowId == captured.windowId else JSONObject.NULL)
            while (value != null && ancestors.length() < 128) { ancestors.put(node(value)); value = value.parent }
            ancestors
        }
        capture("freshActiveWindow") {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            record.put("activePackage", root?.packageName ?: JSONObject.NULL)
            var count = 0
            fun tree(value: AccessibilityNodeInfo, depth: Int): JSONObject {
                val description = node(value)
                count++
                val children = JSONArray()
                for (index in 0 until value.childCount) {
                    if (depth >= 128 || count >= 8192) { description.put("diagnosticTruncated", true); break }
                    value.getChild(index)?.let { children.put(tree(it, depth + 1)) }
                }
                return description.put("children", children)
            }
            val result = root?.let { tree(it, 0) } ?: JSONObject.NULL
            record.put("freshTreeNodeCount", count)
            result
        }
        capture("windows") {
            JSONArray(instrumentation.uiAutomation.windows.map {
                JSONObject().put("id", it.id).put("type", it.type).put("layer", it.layer)
                    .put("active", it.isActive).put("focused", it.isFocused)
                    .put("package", it.root?.packageName ?: JSONObject.NULL)
            })
        }
        capture("ownState") {
            lateinit var value: JSONObject
            instrumentation.runOnMainSync {
                val workspace = activity.retainedWorkspaceForTest()
                val snapshot = workspace.snapshot()
                val owner = activity.retainedProjectFlowForTest()
                val flow = owner.state
                val modelEditor = activity.retainedEditorForTest()
                val canvases = JSONArray()
                fun collect(view: android.view.View) {
                    if (view is CanvasView) {
                        val boundEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
                            .get(view) as NativeEditor
                        canvases.put(JSONObject().put("identity", System.identityHashCode(view))
                            .put("boundEditorIdentity", System.identityHashCode(boundEditor))
                            .put("matchesModel", boundEditor === modelEditor).put("matchesWorkspace", boundEditor === snapshot.editor)
                            .put("width", view.width).put("height", view.height).put("attached", view.isAttachedToWindow)
                            .put("surfaceValid", view.holder.surface.isValid).put("shown", view.isShown)
                            .put("visibility", view.visibility).put("windowVisibility", view.windowVisibility)
                            .put("windowFocus", view.hasWindowFocus()).put("editing", view.editingInProgress)
                            .put("stroke", view.strokeInProgress))
                    }
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.window.decorView)
                fun editor(editor: NativeEditor?): Any = editor?.let {
                    JSONObject().put("identity", System.identityHashCode(it)).put("width", it.canvasWidth)
                        .put("height", it.canvasHeight).put("pendingEditCount", it.pendingEditCount)
                        .put("strokeReservations", it.strokeReservations().size).put("nativeState", it.state().toString())
                } ?: JSONObject.NULL
                value = JSONObject().put("activityIdentity", System.identityHashCode(activity))
                    .put("lifecycle", activity.lifecycle.currentState.name).put("finishing", activity.isFinishing)
                    .put("destroyed", activity.isDestroyed).put("windowFocus", activity.hasWindowFocus())
                    .put("workspaceIdentity", System.identityHashCode(workspace)).put("generation", snapshot.generation)
                    .put("loading", snapshot.loading).put("frozen", snapshot.resourceGrowthFrozen)
                    .put("currentRevision", snapshot.currentRevision).put("savedRevision", snapshot.savedRevision)
                    .put("editorIdentity", snapshot.editor?.let { System.identityHashCode(it) } ?: JSONObject.NULL)
                    .put("modelMatchesWorkspace", activity.retainedEditorForTest() === snapshot.editor)
                    .put("workspaceEditor", editor(snapshot.editor)).put("modelEditor", editor(modelEditor)).put("canvases", canvases)
                    .put("ownerIdentity", System.identityHashCode(owner)).put("phase", flow.phase.name)
                    .put("busy", flow.busy).put("requestId", flow.requestId).put("canCancel", flow.canCancel)
                    .put("reviewId", reviewId).put("exportHistoryCount", flow.exportHistory?.size ?: JSONObject.NULL)
                    .put("reviewIndex", flow.exportHistory?.indexOfFirst { it.id == reviewId } ?: JSONObject.NULL)
                    .put("exportHistory", flow.exportHistory?.let { records -> JSONArray(records.map {
                        JSONObject().put("id", it.id).put("finalName", it.finalName).put("phase", it.phase)
                    }) } ?: JSONObject.NULL)
                    .put("exportResult", runCatching { flow.exportResult?.toString() }.getOrNull() ?: JSONObject.NULL)
                    .put("exportResultError", runCatching { flow.exportResult?.error }.getOrNull() ?: JSONObject.NULL)
                    .put("publicationEnteredAtMillis", publicationEnteredAt.get())
                    .put("publicationReturnedAtMillis", publicationReturnedAt.get())
                    .put("publicationAwaitReleased", if (publicationReturnedAt.get() != 0L) publicationReleased.get() else JSONObject.NULL)
                    .put("flowError", flow.error ?: JSONObject.NULL)
                    .put("workspaceError", snapshot.recoveryError ?: snapshot.saveError ?: JSONObject.NULL)
            }
            value
        }
        capture("screenshot") {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.filesDir, "$prefix.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            "$prefix.png"
        }
        runCatching {
            File(context.filesDir, "$prefix.json").writeText(record.toString(2))
            status("Project export failure-time diagnostic: files/$prefix.json")
        }.onFailure { android.util.Log.e("CompositorExportUi", "Could not preserve picker action diagnosis", it) }
    }
    // A globally idle UI can wait out the fixture's own held publication; a main FIFO fence keeps that worker blocked.
    fun waitForFixtureUiIdle() {
        if (publicationEnteredAt.get() != 0L && publicationReturnedAt.get() == 0L) {
            timing("held publication main FIFO begin")
            instrumentation.runOnMainSync {}
            timing("held publication main FIFO end")
        } else {
            timing("ordinary global idle begin")
            instrumentation.waitForIdleSync()
            timing("ordinary global idle end")
        }
    }
    fun waitFor(message: String, condition: () -> Boolean) {
        timing("waitFor begin: $message")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var ready = condition()
        while (!ready && System.nanoTime() < deadline) {
            Thread.sleep(20)
            ready = condition()
        }
        try { check(ready) { message } }
        catch (error: Exception) {
            runCatching { exportUiFailure(message, { null }, null, null, null, null, error) }
                .onFailure { android.util.Log.e("CompositorExportUi", "Could not capture timeout diagnosis", it) }
            throw error
        }
        timing("waitFor condition ready, idle begin: $message")
        waitForFixtureUiIdle()
        timing("waitFor idle end: $message")
    }
    fun clickNode(label: String, systemTap: Boolean = false, lookup: () -> AccessibilityNodeInfo?) {
        var found: AccessibilityNodeInfo? = null
        waitFor("Missing lifecycle/picker UI: $label") { found = lookup(); found != null }
        var button: AccessibilityNodeInfo? = found
        var enabled: Boolean? = null; var actionResult: Boolean? = null
        try {
            var ready = checkNotNull(button)
            while (!ready.isClickable) { ready = checkNotNull(ready.parent); button = ready }
            enabled = ready.isEnabled
            val success = enabled == true && run {
                actionResult = if (systemTap) {
                    // DocumentsUI adapter rows route physical taps through their parent list.
                    // Use one system gesture directly, never an ACTION_CLICK failure fallback.
                    check(ready.packageName?.toString() == "com.google.android.documentsui" && ready.isVisibleToUser)
                    val bounds = android.graphics.Rect().also(ready::getBoundsInScreen)
                    val activeRoot = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                    check(activeRoot.windowId == ready.windowId && activeRoot.packageName == ready.packageName)
                    val window = android.graphics.Rect().also(activeRoot::getBoundsInScreen)
                    check(bounds.intersect(window) && !bounds.isEmpty)
                    val down = android.os.SystemClock.uptimeMillis()
                    fun touch(action: Int, time: Long): Boolean {
                        val event = android.view.MotionEvent.obtain(down, time, action,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        }
                        return try { instrumentation.uiAutomation.injectInputEvent(event, true) }
                        finally { event.recycle() }
                    }
                    val acceptedDown = touch(android.view.MotionEvent.ACTION_DOWN, down)
                    val acceptedUp = touch(android.view.MotionEvent.ACTION_UP, maxOf(down + 1, android.os.SystemClock.uptimeMillis()))
                    acceptedDown && acceptedUp
                } else ready.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                actionResult == true
            }
            check(success) { "Disabled picker action: $label" }
        } catch (error: Exception) {
            runCatching { exportUiFailure(label, lookup, button, enabled, button?.isClickable, actionResult, error) }
                .onFailure { android.util.Log.e("CompositorExportUi", "Could not capture click diagnosis", it) }
            throw error
        }
        waitForFixtureUiIdle()
    }
    fun click(label: String) {
        revealEditorControl(instrumentation, label)
        clickNode(label) { available(label) }
    }
    fun recreate() {
        timing("recreate begin")
        val old = activity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { old.recreate() }
        timing("recreate requested, monitor wait begins")
        activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        instrumentation.removeMonitor(monitor)
        timing("recreate monitor ready, idle begin")
        waitForFixtureUiIdle()
        timing("recreate idle end")
    }
    fun shell(command: String) {
        timing("shell begin: $command")
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                val output = it.readBytes().toString(Charsets.UTF_8)
                if (output.isNotBlank()) status("$command: ${output.trim()}")
            }
        }
        timing("shell end: $command")
    }
    fun exportRootTitleVisible(): Boolean {
        val automation = instrumentation.uiAutomation
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return false
        val providerPackage = "com.google.android.documentsui"
        if (root.packageName?.toString() != providerPackage) return false
        fun current(node: AccessibilityNodeInfo) = node.windowId == root.windowId &&
            node.packageName?.toString() == providerPackage && node.isVisibleToUser
        val list = findMatching(root) { current(it) && it.viewIdResourceName == "$providerPackage:id/roots_list" }
            ?: return false
        val window = android.graphics.Rect().also(root::getBoundsInScreen)
        return findMatching(list) {
            val bounds = android.graphics.Rect().also(it::getBoundsInScreen)
            current(it) && it.viewIdResourceName == "android:id/title" && it.text?.toString() == "Export project test" &&
                !bounds.isEmpty && bounds.intersect(window) && !bounds.isEmpty
        } != null
    }
    fun tapExportProviderRoot() {
        val automation = instrumentation.uiAutomation
        val providerPackage = "com.google.android.documentsui"
        val label = "Export project test"
        val trace = JSONObject().put("scope", "One system tap after fresh, complete, stable DocumentsUI root geometry")
        val traceFile = File(context.filesDir, "p36-export-root-tap-${System.nanoTime()}.json")
        data class Target(val row: AccessibilityNodeInfo, val signature: String, val x: Float, val y: Float,
            val geometry: JSONObject)
        fun lookup(): Target? {
            automation.clearCache()
            val reading = JSONObject().put("elapsedRealtimeNanos", android.os.SystemClock.elapsedRealtimeNanos())
            trace.put("lastLookup", reading)
            fun missing(reason: String): Target? { reading.put("rejected", reason); return null }
            val root = automation.rootInActiveWindow ?: return missing("No active root")
            if (root.packageName?.toString() != providerPackage) return missing("Wrong active package")
            fun sameWindow(node: AccessibilityNodeInfo) = node.windowId == root.windowId &&
                node.packageName?.toString() == providerPackage && node.isVisibleToUser && node.isEnabled
            val list = findMatching(root) {
                sameWindow(it) && it.viewIdResourceName == "$providerPackage:id/roots_list"
            } ?: return missing("Root list unavailable")
            val matches = mutableListOf<AccessibilityNodeInfo>()
            fun titles(node: AccessibilityNodeInfo) {
                if (sameWindow(node) && node.viewIdResourceName == "android:id/title" && node.text?.toString() == label)
                    matches.add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::titles)
            }
            titles(list)
            if (matches.size != 1) return missing("Expected one root title, found ${matches.size}")
            val title = matches.single()
            var row = title
            var parent = row.parent
            var depth = 0
            while (parent != null && parent != list && depth++ < 128) { row = parent; parent = row.parent }
            if (parent != list || !sameWindow(row)) return missing("Title has no current direct list row")
            var drawer: AccessibilityNodeInfo? = list.parent
            depth = 0
            while (drawer != null && drawer.viewIdResourceName != "$providerPackage:id/drawer_roots" && depth++ < 128)
                drawer = drawer.parent
            val currentDrawer = drawer?.takeIf { sameWindow(it) } ?: return missing("Current drawer unavailable")
            val window = android.graphics.Rect().also(root::getBoundsInScreen)
            val bounds = linkedMapOf<String, android.graphics.Rect>()
            val signatures = mutableListOf(root.windowId.toString())
            var complete = !window.isEmpty
            for ((name, node) in listOf("title" to title, "row" to row, "list" to list, "drawer" to currentDrawer)) {
                val screen = android.graphics.Rect().also(node::getBoundsInScreen)
                val local = android.graphics.Rect().also(node::getBoundsInParent)
                reading.put(name, JSONObject().put("windowId", node.windowId).put("package", node.packageName)
                    .put("class", node.className).put("resourceId", node.viewIdResourceName ?: JSONObject.NULL)
                    .put("text", node.text ?: JSONObject.NULL).put("clickable", node.isClickable)
                    .put("enabled", node.isEnabled).put("visible", node.isVisibleToUser)
                    .put("screen", screen.toShortString()).put("parent", local.toShortString()))
                bounds[name] = screen
                signatures.add("$name:${screen.toShortString()}:${local.toShortString()}")
                complete = complete && sameWindow(node) && !screen.isEmpty && !local.isEmpty && window.contains(screen) &&
                    screen.width() == local.width() && screen.height() == local.height()
            }
            reading.put("window", window.toShortString())
            val titleBounds = checkNotNull(bounds["title"])
            val rowBounds = checkNotNull(bounds["row"])
            val listBounds = checkNotNull(bounds["list"])
            val drawerBounds = checkNotNull(bounds["drawer"])
            if (!complete || !rowBounds.contains(titleBounds) || !listBounds.contains(rowBounds) || !drawerBounds.contains(listBounds))
                return missing("Root geometry is clipped, incomplete or outside its current hierarchy")
            // The title center is inside its direct adapter row, never a parent list's center.
            val x = titleBounds.exactCenterX(); val y = titleBounds.exactCenterY()
            reading.put("tapX", x).put("tapY", y)
            return Target(row, signatures.joinToString("|"), x, y, reading)
        }
        var stable: Target? = null
        var previous: String? = null
        var equalReadings = 0
        var downAccepted: Boolean? = null
        var upAccepted: Boolean? = null
        try {
            waitFor("Missing lifecycle/picker UI: $label") {
                val candidate = lookup()
                equalReadings = if (candidate != null && candidate.signature == previous) equalReadings + 1 else 0
                previous = candidate?.signature
                stable = candidate
                candidate != null && equalReadings >= 2
            }
            val expected = checkNotNull(stable)
            val current = checkNotNull(lookup()) { "Export root geometry disappeared before its single tap" }
            check(current.signature == expected.signature) { "Export root geometry changed before its single tap" }
            trace.put("tapGeometry", current.geometry).put("signature", current.signature)
                .put("stableReadings", equalReadings + 1)
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, time: Long): Boolean {
                val event = android.view.MotionEvent.obtain(down, time, action, current.x, current.y, 0).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                return try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            }
            trace.put("downEventTime", down).put("beforeDownNanos", android.os.SystemClock.elapsedRealtimeNanos())
            downAccepted = touch(android.view.MotionEvent.ACTION_DOWN, down)
            trace.put("downAccepted", downAccepted).put("afterDownNanos", android.os.SystemClock.elapsedRealtimeNanos())
            val up = maxOf(down + 1, android.os.SystemClock.uptimeMillis())
            trace.put("upEventTime", up)
            upAccepted = touch(android.view.MotionEvent.ACTION_UP, up)
            trace.put("upAccepted", upAccepted).put("afterUpNanos", android.os.SystemClock.elapsedRealtimeNanos())
            check(downAccepted == true && upAccepted == true) { "Export root single system tap was not accepted" }
            waitForFixtureUiIdle()
        } catch (error: Exception) {
            trace.put("error", error.toString())
            runCatching { exportUiFailure(label, { lookup()?.row }, stable?.row, stable?.row?.isEnabled,
                stable?.row?.isClickable, if (downAccepted != null && upAccepted != null) downAccepted == true && upAccepted == true else null, error) }
                .onFailure { android.util.Log.e("CompositorExportUi", "Could not preserve root tap diagnosis", it) }
            throw error
        } finally {
            trace.put("downAccepted", downAccepted ?: JSONObject.NULL).put("upAccepted", upAccepted ?: JSONObject.NULL)
            runCatching { traceFile.writeText(trace.toString(2)); status("Project export root tap trace: files/${traceFile.name}") }
                .onFailure { android.util.Log.e("CompositorExportUi", "Could not preserve root tap trace", it) }
        }
    }
    fun chooseExportTree() {
        fun exportProviderSelected(): Boolean {
            instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
            val providerPackage = "com.google.android.documentsui"
            if (root.packageName?.toString() != providerPackage) return false
            fun visible(node: AccessibilityNodeInfo) = node.isVisibleToUser &&
                node.windowId == root.windowId && node.packageName?.toString() == providerPackage
            // Animated breadcrumbs may expose both roots; never treat the first ID match as unique.
            val breadcrumb = findMatching(root) {
                visible(it) && it.viewIdResourceName == "$providerPackage:id/breadcrumb_text" &&
                    it.text?.toString() == "Export project test"
            }
            val toolbar = findMatching(root) {
                visible(it) && it.viewIdResourceName == "$providerPackage:id/toolbar"
            }
            val title = findMatching(toolbar) { visible(it) && it.text?.toString() == "Export project test" }
            val header = findMatching(root) {
                visible(it) && it.viewIdResourceName == "$providerPackage:id/header_title" &&
                    it.text?.toString() == "Files from Export project test"
            }
            return breadcrumb != null && title != null && header != null
        }
        // The test provider is an ordinary DocumentsProvider advertised to the real system picker.
        waitFor("DocumentsUI did not become active") { resource("toolbar") != null && resource("android:id/button1") != null }
        val providerSelected = exportProviderSelected()
        if (!providerSelected) {
            if (!exportRootTitleVisible()) clickNode("DocumentsUI toolbar navigation") {
                findMatching(resource("toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
            }
            tapExportProviderRoot()
        }
        waitFor("Export provider root did not become active") { exportProviderSelected() }
        clickNode("DocumentsUI folder selection") { resource("android:id/button1")?.takeIf { it.isEnabled } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (resource("alertTitle") == null && System.nanoTime() < deadline &&
            activity.retainedProjectFlowForTest().state.phase == ProjectFlowPhase.CHOOSING) Thread.sleep(20)
        resource("alertTitle")?.let { clickNode("DocumentsUI grant confirmation") { resource("android:id/button1") } }
    }
    fun revealExportRecord() {
        val label = "Compositor-$reviewId.comp"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val trace = File(context.filesDir, "p28-export-record-scroll-${System.nanoTime()}.jsonl")
        var captured: AccessibilityNodeInfo? = null
        var lastActionResult: Boolean? = null
        fun exact(root: AccessibilityNodeInfo?) = findMatching(root) {
            it.text?.toString() == label || it.contentDescription?.toString() == label
        }
        fun dialogList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root?.packageName?.toString() != context.packageName ||
                findMatching(root) { it.text?.toString() == "Export records" && it.isVisibleToUser } == null) return null
            return findMatching(root) {
                val bounds = android.graphics.Rect().also { bounds -> it.getBoundsInScreen(bounds) }
                it.isScrollable && it.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0
            }
        }
        fun signature(root: AccessibilityNodeInfo?): String {
            if (root == null) return ""
            val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
            return "${root.text}|${root.contentDescription}|$bounds;" +
                (0 until root.childCount).joinToString("") { signature(root.getChild(it)) }
        }
        fun modelContainsRecord() = activity.retainedProjectFlowForTest().state.exportHistory?.any { it.id == reviewId } == true
        fun record(event: String, values: JSONObject = JSONObject()) {
            values.put("event", event).put("elapsedRealtimeMillis", android.os.SystemClock.elapsedRealtime())
                .put("reviewId", reviewId).put("label", label)
            trace.appendText(values.toString() + "\n")
        }
        try {
            // Model readiness and actual dialog semantics share the original one 20-second deadline.
            while (System.nanoTime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (modelContainsRecord() && (exact(root) != null || dialogList(root) != null)) break
                Thread.sleep(20)
            }
            check(modelContainsRecord()) { "Interrupted receipt was not visible" }
            check(System.nanoTime() < deadline) { "Export records accessibility did not become ready" }
            for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                for (attempt in 0 until 32) {
                    check(System.nanoTime() < deadline) { "Export record did not become visible within 20 seconds" }
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    exact(root)?.let { captured = it; record("exactRecordVisible"); return }
                    val list = dialogList(root) ?: break
                    captured = list
                    if (list.actionList.none { it.id == action }) break
                    val before = signature(list)
                    lastActionResult = list.performAction(action)
                    record("scroll", JSONObject().put("action", action).put("accepted", lastActionResult)
                        .put("before", before).put("directionAttempt", attempt))
                    check(lastActionResult == true) { "Export records rejected advertised scroll action $action" }
                    // Do not repeat an action while its prior accessibility viewport is still pending.
                    val actionDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500))
                    var lastSignature = before
                    var lastChangeAt = System.nanoTime()
                    var moved = false
                    var settled = false
                    while (System.nanoTime() < actionDeadline) {
                        val current = instrumentation.uiAutomation.rootInActiveWindow
                        exact(current)?.let { captured = it; record("exactRecordVisible"); return }
                        val currentSignature = signature(dialogList(current))
                        val now = System.nanoTime()
                        if (currentSignature != before) moved = true
                        if (currentSignature != lastSignature) {
                            lastSignature = currentSignature; lastChangeAt = now
                        } else if (moved && now - lastChangeAt >= TimeUnit.MILLISECONDS.toNanos(100)) {
                            settled = true; break
                        }
                        Thread.sleep(20)
                    }
                    record("scrollReadback", JSONObject().put("after", signature(dialogList(instrumentation.uiAutomation.rootInActiveWindow)))
                        .put("moved", moved).put("settled", settled))
                    if (!moved) { record("directionNoMovement", JSONObject().put("action", action)); break }
                    check(settled) { "Export records scroll did not settle within its bounded observation" }
                }
            }
            while (System.nanoTime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                exact(root)?.let { captured = it; record("exactRecordVisible"); return }
                Thread.sleep(20)
            }
            error("Export record exact file name was not visible within 20 seconds")
        } catch (error: Exception) {
            runCatching { record("failure", JSONObject().put("error", error.toString())) }
            runCatching { exportUiFailure("Export record exact file name: $label", { available(label) }, captured,
                captured?.isEnabled, captured?.isClickable, lastActionResult, error) }
                .onFailure { android.util.Log.e("CompositorExportUi", "Could not capture export record visibility diagnosis", it) }
            throw error
        }
    }
    waitFor("Export editor did not open") { activity.retainedEditorForTest() != null }
    val workspace = activity.retainedWorkspaceForTest()
    val owner = activity.retainedProjectFlowForTest()
    val copying = CountDownLatch(1); val copyResume = CountDownLatch(1)
    val publishing = CountDownLatch(1); val publishResume = CountDownLatch(1)
    try {
        waitFor("Export recovery notice missing") { owner.exportRecoveryNotice != null }
        click("Export records")
        revealExportRecord()
        try { check(available("Compositor-$reviewId.comp") != null) }
        catch (error: Exception) {
            val label = "Compositor-$reviewId.comp"
            runCatching { exportUiFailure("Export record final exact assertion: $label", { available(label) },
                null, null, null, null, error) }
                .onFailure { android.util.Log.e("CompositorExportUi", "Could not capture final export record assertion", it) }
            throw error
        }
        // Forgetting a receipt is explicitly metadata-only and never invokes provider cleanup.
        instrumentation.runOnMainSync { owner.forgetExportRecord(reviewId) }
        waitFor("Receipt review did not remove local record") { owner.state.exportHistory?.none { it.id == reviewId } == true }
        click("Close")
        instrumentation.runOnMainSync { owner.dismissExportRecoveryNotice() }
        check(workspace.installCandidate("export-ui", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        waitFor("Small export editor did not bind") {
            var ready = false
            instrumentation.runOnMainSync {
                fun boundCanvas(view: android.view.View): Boolean {
                    if (view is CanvasView) {
                        val boundEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }.get(view)
                        if (boundEditor === editor && view.isAttachedToWindow && view.holder.surface.isValid) return true
                    }
                    return view is android.view.ViewGroup && (0 until view.childCount).any { boundCanvas(view.getChildAt(it)) }
                }
                ready = activity.retainedEditorForTest() === editor && workspace.snapshot().editor === editor &&
                    boundCanvas(activity.window.decorView)
            }
            ready
        }
        editor.state().layers.forEachIndexed { index, layer -> editor.renameLayer(layer.id, "Export UI $index") }
        val captured = editor.currentRevision(); val before = workspace.snapshot()
        val exportSuggestedName = ProjectCopyNames.suggested(
            ProjectNamePreferences.projectName(context, before.sourceIdentity), ImageDocumentSource.decode(before.sourceIdentity)?.name)
        val sourceBefore = if (killPrepare) exportProcessSourceHashes(context) else emptyMap()
        instrumentation.runOnMainSync {
            owner.testHooks = ProjectFlowHooks(exportProgress = { message, canCancel, cancellation ->
                if (message.startsWith("Exporting") && copying.count > 0) {
                    copying.countDown()
                    if (killPrepare) {
                        val receipt = receipts.readAll().single { it.phase == "WRITING" && it.revision == captured &&
                            it.tree == ExportDocumentsProvider.tree("ok") }
                        val marker = org.json.JSONObject().put("receiptId", receipt.id).put("revision", captured)
                            .put("finalName", receipt.finalName).put("source", org.json.JSONObject(sourceBefore))
                        java.io.File(context.filesDir.canonicalFile, "p14-export-kill-ready.json").outputStream().use {
                            it.write(marker.toString().toByteArray()); it.fd.sync()
                        }
                        status("Export process-kill preparation: durable WRITING receipt ${receipt.id}, ordinary provider root/images created; waiting for external force-stop")
                        check(copyResume.await(60, TimeUnit.SECONDS)) { "External process kill did not arrive" }
                    }
                    while (!copyResume.await(25, TimeUnit.MILLISECONDS)) cancellation.transport.check()
                }
                if (!canCancel) {
                    publicationEnteredAt.set(android.os.SystemClock.elapsedRealtime())
                    timing("publication hook entered; await45s begins")
                    publishing.countDown()
                    val resumed = publishResume.await(45, TimeUnit.SECONDS)
                    publicationReleased.set(resumed)
                    publicationReturnedAt.set(android.os.SystemClock.elapsedRealtime())
                    timing("publication hook await returned=$resumed; entered=${publicationEnteredAt.get()}ms returned=${publicationReturnedAt.get()}ms")
                    check(resumed)
                }
            })
        }
        click("Export project copy")
        waitFor("Export chooser did not start") { owner.state.phase == ProjectFlowPhase.CHOOSING }
        val pickerId = owner.state.requestId
        recreate()
        check(activity.retainedProjectFlowForTest() === owner && owner.state.requestId == pickerId)
        chooseExportTree()
        check(copying.await(10, TimeUnit.SECONDS)) { "Real export picker result was lost" }
        if (killPrepare) {
            check(CountDownLatch(1).await(60, TimeUnit.SECONDS)) { "External force-stop must end the preparation process" }
        }
        waitFor("Export copying UI missing") { owner.state.phase == ProjectFlowPhase.EXPORTING && owner.state.canCancel }
        val exportId = owner.state.requestId
        recreate()
        check(activity.retainedProjectFlowForTest() === owner && owner.state.requestId == exportId && workspace.snapshot().resourceGrowthFrozen)
        copyResume.countDown()
        check(publishing.await(10, TimeUnit.SECONDS))
        waitFor("Publication gate UI missing") { owner.state.phase == ProjectFlowPhase.PUBLISHING && !owner.state.canCancel }
        recreate()
        var accepted = true
        instrumentation.runOnMainSync { accepted = owner.cancel() }
        check(!accepted)
        shell("input keyevent KEYCODE_HOME")
        waitFor("HOME did not become active before returning") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("launcher") == true
        }
        shell("am start -n com.pojia.compositor.mobile/.MainActivity --task ${activity.taskId} -f 0x00020000")
        waitFor("Publication dialog did not return") { available("Publishing project copy…") != null }
        check(owner.state.requestId == exportId)
        publishResume.countDown()
        waitFor("Verified export result missing") { owner.state.exportResult != null && !workspace.snapshot().resourceGrowthFrozen }
        val result = checkNotNull(owner.state.exportResult)
        check(result.commit == ProjectExportCommit.COMMITTED_VERIFIED && result.capturedRevision == captured && !result.cancelled)
        check(result.displayName == ProjectExportPackageNames.finalName(checkNotNull(result.receiptId), exportSuggestedName))
        check(workspace.snapshot().editor === editor && editor.currentRevision() == captured &&
            workspace.snapshot().projectBaseline == before.projectBaseline && workspace.snapshot().sourceIdentity == before.sourceIdentity)
        recreate()
        waitFor("Verified export receipt did not reappear after recreation") {
            owner.state.exportResult == result && available("Project export") != null
        }
        click("Close")
        waitFor("Verified receipt acknowledgement did not finish") { !owner.state.busy }
        status("Export UI: real DocumentsUI copy verified; picker/copy/publication/result survive recreation and HOME; baseline/source preserved")

        // A revision/generation captured by an actual chooser cannot export a replacement workspace.
        instrumentation.runOnMainSync { owner.testHooks = null }
        click("Export project copy")
        waitFor("Stale export chooser did not start") { owner.state.phase == ProjectFlowPhase.CHOOSING }
        check(workspace.installCandidate("export-stale", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed)
        val replacement = workspace.snapshot().editor
        chooseExportTree()
        waitFor("Stale export chooser did not terminate") { owner.state.phase == ProjectFlowPhase.IDLE }
        check(owner.state.exportResult == null && owner.state.error?.contains("changed") == true && workspace.snapshot().editor === replacement)
        instrumentation.runOnMainSync { owner.clearError() }
        val nextEditor = checkNotNull(replacement)
        nextEditor.state().layers.forEachIndexed { index, layer -> nextEditor.renameLayer(layer.id, "Cancel UI $index") }
        val cancelEntered = CountDownLatch(1); val cancelExit = CountDownLatch(1)
        instrumentation.runOnMainSync {
            owner.testHooks = ProjectFlowHooks(exportProgress = { message, _, cancellation ->
                if (message.startsWith("Exporting")) {
                    cancelEntered.countDown()
                    try { while (true) { cancellation.transport.check(); Thread.sleep(20) } }
                    finally { cancelExit.countDown() }
                }
            })
        }
        click("Export project copy"); chooseExportTree()
        check(cancelEntered.await(10, TimeUnit.SECONDS))
        waitFor("Cancellable export UI missing") { owner.state.phase == ProjectFlowPhase.EXPORTING }
        recreate(); click("Cancel")
        check(cancelExit.await(10, TimeUnit.SECONDS))
        waitFor("Cancelled export result missing") { owner.state.exportResult != null && !workspace.snapshot().resourceGrowthFrozen }
        check(owner.state.exportResult!!.cancelled && owner.state.exportResult!!.commit == ProjectExportCommit.NOT_COMMITTED &&
            owner.state.exportResult!!.cleanup == ProjectExportCleanup.RETAINED)
        click("Close")
        return "Real DocumentsUI grants persistent RW tree access. Retained chooser/copy/publication/result survive Activity recreation and HOME/return without duplicate export; late cancellation is rejected; stale workspace picker is rejected; recreated pre-publication cancellation retains safe directories. Startup uncertain receipt is visible and can be forgotten without deleting external files."
    } finally {
        copyResume.countDown(); publishResume.countDown()
        runCatching { receipts.remove(reviewId) }
        if (owner.state.phase == ProjectFlowPhase.CHOOSING) shell("input keyevent KEYCODE_BACK")
        instrumentation.runOnMainSync { owner.testHooks = null; activity.finish() }
        waitForFixtureUiIdle()
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
    }
}
