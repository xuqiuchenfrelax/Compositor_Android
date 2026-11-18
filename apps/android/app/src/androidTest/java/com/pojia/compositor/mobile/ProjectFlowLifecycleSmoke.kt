package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Real DocumentsUI results and Activity recreation exercise the retained owner, not a replacement controller. */
internal fun projectFlowLifecycleSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    fun status(message: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$message\n") })
    fun stage(message: String) {
        runCatching { status("Project flow timing: elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}ms $message") }
    }
    val loadingAwaitEnteredAt = AtomicLong()
    val loadingAwaitReturnedAt = AtomicLong()
    fun fixtureUiFence() {
        val pendingLoading = loadingAwaitEnteredAt.get() != 0L && loadingAwaitReturnedAt.get() == 0L
        stage("fixture UI fence before: pendingLoading=$pendingLoading")
        if (pendingLoading) instrumentation.runOnMainSync {}
        else instrumentation.waitForIdleSync()
        stage("fixture UI fence after: pendingLoading=$pendingLoading")
    }
    status("Project flow: queued replacement/startup maintenance")
    startupMaintenanceReplacementSmoke(instrumentation)
    val stagingRoot = File(context.filesDir.canonicalFile, "project-staging")
    val stagingStorage = ProjectStagingStorage(stagingRoot)
    val startupActive = stagingStorage.create()
    val startupOrphan = File(stagingRoot, "${UUID.randomUUID()}.comp").apply {
        check(mkdir()); File(this, "partial.png").writeText("cold startup orphan")
    }
    status("Project flow: enabling system picker resource ids")
    instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
    status("Project flow: starting MainActivity")
    var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    status("Project flow: MainActivity started")
    fun waitFor(message: String, condition: () -> Boolean) {
        stage("waitFor begin: $message")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        stage("waitFor final condition before: $message")
        check(condition()) { message }
        stage("waitFor final condition ready, idle before: $message")
        fixtureUiFence()
        stage("waitFor idle after: $message")
    }
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
    fun pickerActionFailure(label: String, lookup: () -> AccessibilityNodeInfo?, captured: AccessibilityNodeInfo?, enabled: Boolean?, clickable: Boolean?,
        actionResult: Boolean?, error: Exception) {
        // Preserve the failed action before finally dismisses the real picker; never retry its action.
        val prefix = "p20-picker-failure05-${System.nanoTime()}"
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
                value = JSONObject().put("activityIdentity", System.identityHashCode(activity))
                    .put("lifecycle", activity.lifecycle.currentState.name).put("finishing", activity.isFinishing)
                    .put("destroyed", activity.isDestroyed).put("windowFocus", activity.hasWindowFocus())
                    .put("workspaceIdentity", System.identityHashCode(workspace)).put("generation", snapshot.generation)
                    .put("loading", snapshot.loading).put("frozen", snapshot.resourceGrowthFrozen)
                    .put("currentRevision", snapshot.currentRevision).put("savedRevision", snapshot.savedRevision)
                    .put("editorIdentity", snapshot.editor?.let { System.identityHashCode(it) } ?: JSONObject.NULL)
                    .put("modelMatchesWorkspace", activity.retainedEditorForTest() === snapshot.editor)
                    .put("ownerIdentity", System.identityHashCode(owner)).put("phase", flow.phase.name)
                    .put("busy", flow.busy).put("requestId", flow.requestId).put("canCancel", flow.canCancel)
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
            status("Project flow failure-time diagnostic: files/$prefix.json")
        }.onFailure { android.util.Log.e("CompositorPicker", "Could not preserve picker action diagnosis", it) }
    }
    fun clickNode(label: String, lookup: () -> AccessibilityNodeInfo?) {
        var button: AccessibilityNodeInfo? = null
        var observedDisabled = false
        fun describeReadiness() {
            fun canvasCount(view: android.view.View): Int = when (view) {
                is CanvasView -> if (view.isAttachedToWindow) 1 else 0
                is android.view.ViewGroup -> (0 until view.childCount).sumOf { canvasCount(view.getChildAt(it)) }
                else -> 0
            }
            instrumentation.runOnMainSync {
                val snapshot = activity.retainedWorkspaceForTest().snapshot()
                val flow = activity.retainedProjectFlowForTest().state
                status("Picker readiness: $label enabled=${button?.isEnabled} clickable=${button?.isClickable} " +
                    "phase=${flow.phase} busy=${flow.busy} frozen=${snapshot.resourceGrowthFrozen} loading=${snapshot.loading} " +
                    "editorBound=${activity.retainedEditorForTest() === snapshot.editor} attachedCanvas=${canvasCount(activity.window.decorView)}")
            }
        }
        // A newly bound editor can precede its Compose canvas attachment and enabled accessibility node.
        try {
            waitFor("Lifecycle/picker action did not become enabled: $label") {
                button = lookup()
                while (button != null && button?.isClickable != true) button = button?.parent
                val ready = button?.isClickable == true && button?.isEnabled == true
                if (button != null && !ready && !observedDisabled) {
                    observedDisabled = true; describeReadiness()
                }
                ready
            }
        } catch (error: Exception) {
            describeReadiness(); throw error
        }
        if (observedDisabled) describeReadiness()
        var enabled: Boolean? = null; var clickable: Boolean? = null; var actionResult: Boolean? = null
        try {
            val ready = checkNotNull(button)
            enabled = ready.isEnabled
            val success = enabled == true && run {
                clickable = ready.isClickable
                clickable == true && run {
                    actionResult = ready.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    actionResult == true
                }
            }
            check(success) { "Disabled picker action: $label" }
        } catch (error: Exception) {
            runCatching { pickerActionFailure(label, lookup, button, enabled, clickable, actionResult, error) }
                .onFailure { android.util.Log.e("CompositorPicker", "Could not capture picker action diagnosis", it) }
            throw error
        }
        fixtureUiFence()
    }
    fun click(label: String) {
        val trace = EditorUiTrace(label) {
            editorUiOwnState(instrumentation, activity)
                .put("workspaceState", activity.retainedWorkspaceForTest().snapshot().toString())
                .put("sourceIdentity", activity.retainedWorkspaceForTest().snapshot().sourceIdentity ?: JSONObject.NULL)
        }
        try {
            revealEditorControl(instrumentation, label, trace)
            clickNode(label) { available(label) }
        } catch (error: Exception) {
            captureEditorUiFailure(instrumentation, activity, error, listOf(trace))
            throw error
        }
    }
    fun recreate() {
        val old = activity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { old.recreate() }
        activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity)
        instrumentation.removeMonitor(monitor)
        fixtureUiFence()
    }
    fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                val output = it.readBytes().toString(Charsets.UTF_8)
                if (output.isNotBlank()) status("$command: ${output.trim()}")
            }
        }
    }
    fun editableRootTitleVisible(): Boolean {
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
            current(it) && it.viewIdResourceName == "android:id/title" && it.text?.toString() == "Editable project test" &&
                !bounds.isEmpty && bounds.intersect(window) && !bounds.isEmpty
        } != null
    }
    fun tapEditableProviderRoot() {
        val automation = instrumentation.uiAutomation
        val providerPackage = "com.google.android.documentsui"
        val label = "Editable project test"
        val trace = JSONObject().put("scope", "One system tap after fresh, complete, stable DocumentsUI root geometry")
        val traceFile = File(context.filesDir, "p38-editable-root-tap-${System.nanoTime()}.json")
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
            val signatures = mutableListOf(root.windowId.toString(), window.toShortString())
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
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            trace.put("readinessBudgetSeconds", 20).put("readinessStartedNanos", android.os.SystemClock.elapsedRealtimeNanos())
            // Keep geometry observation and its final fresh validation inside the same readiness budget.
            while (System.nanoTime() < deadline) {
                val candidate = lookup()
                equalReadings = if (candidate != null && candidate.signature == previous) equalReadings + 1 else 0
                previous = candidate?.signature
                if (candidate != null && equalReadings >= 2 && System.nanoTime() < deadline) {
                    val verified = lookup()
                    if (verified != null && verified.signature == candidate.signature && System.nanoTime() < deadline) {
                        stable = verified
                        break
                    }
                    // Geometry is still settling: continue observation within the original deadline, without input.
                    previous = verified?.signature
                    equalReadings = 0
                }
                Thread.sleep(20)
            }
            val current = checkNotNull(stable) { "Missing lifecycle/picker UI: $label" }
            trace.put("readinessEndedNanos", android.os.SystemClock.elapsedRealtimeNanos())
                .put("tapGeometry", current.geometry).put("signature", current.signature)
                .put("stableReadings", equalReadings + 2)
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
            check(downAccepted == true && upAccepted == true) { "Editable root single system tap was not accepted" }
            fixtureUiFence()
        } catch (error: Exception) {
            trace.put("error", error.toString())
            runCatching { pickerActionFailure(label, { lookup()?.row }, stable?.row, stable?.row?.isEnabled,
                stable?.row?.isClickable, if (downAccepted != null && upAccepted != null) downAccepted == true && upAccepted == true else null, error) }
                .onFailure { android.util.Log.e("CompositorPicker", "Could not preserve root tap diagnosis", it) }
            throw error
        } finally {
            trace.put("downAccepted", downAccepted ?: JSONObject.NULL).put("upAccepted", upAccepted ?: JSONObject.NULL)
            runCatching { traceFile.writeText(trace.toString(2)); status("Project lifecycle root tap trace: files/${traceFile.name}") }
                .onFailure { android.util.Log.e("CompositorPicker", "Could not preserve root tap trace", it) }
        }
    }
    fun chooseEditableTree() {
        // The test provider is an ordinary DocumentsProvider advertised to the real system picker.
        waitFor("DocumentsUI did not become active") { resource("toolbar") != null && resource("android:id/button1") != null }
        val providerSelected = resource("breadcrumb_text")?.text?.toString() == "Editable project test"
        if (!providerSelected) {
            if (!editableRootTitleVisible()) clickNode("DocumentsUI toolbar navigation") {
                findMatching(resource("toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
            }
            tapEditableProviderRoot()
        }
        waitFor("Editable provider root did not become active") { resource("breadcrumb_text")?.text?.toString() == "Editable project test" }
        clickNode("DocumentsUI folder selection") { resource("android:id/button1")?.takeIf { it.isEnabled } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (resource("alertTitle") == null && System.nanoTime() < deadline &&
            activity.retainedProjectFlowForTest().state.phase == ProjectFlowPhase.CHOOSING) Thread.sleep(20)
        resource("alertTitle")?.let { clickNode("DocumentsUI grant confirmation") { resource("android:id/button1") } }
    }
    waitFor("Lifecycle editor did not open") { activity.retainedEditorForTest() != null }
    val workspace = activity.retainedWorkspaceForTest()
    val owner = activity.retainedProjectFlowForTest()
    val copyEntered = CountDownLatch(1); val copyResume = CountDownLatch(1)
    val loadEntered = CountDownLatch(1); val loadResume = CountDownLatch(1)
    val copies = AtomicInteger()
    try {
        waitFor("Cold-start retained owner did not sweep staging") { owner.startupCleanupResult != null }
        val cleanup = checkNotNull(owner.startupCleanupResult).getOrThrow()
        check(cleanup.removed >= 1 && cleanup.active >= 1 && !startupOrphan.exists() && startupActive.directory.isDirectory)
        check(!workspace.snapshot().resourceGrowthFrozen && !workspace.snapshot().projectDirty)
        val onceOrphan = File(stagingRoot, "${UUID.randomUUID()}.comp").apply { check(mkdir()) }
        instrumentation.runOnMainSync { repeat(3) { owner.workspaceChanged(workspace.snapshot()) } }
        check(onceOrphan.exists()) { "Startup sweep repeated for the retained owner" }
        onceOrphan.delete(); startupActive.close()
        check(workspace.installCandidate("picker-lifecycle", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed)
        waitFor("Small picker document did not bind") { activity.retainedEditorForTest() === workspace.snapshot().editor }
        val oldEditor = checkNotNull(workspace.snapshot().editor)
        instrumentation.runOnMainSync {
            owner.testHooks = ProjectFlowHooks(stage = { uri, cancellation, progress ->
                copies.incrementAndGet(); copyEntered.countDown()
                while (!copyResume.await(25, TimeUnit.MILLISECONDS)) cancellation.check()
                cancellation.check()
                ProjectTransport.forContext(context).stage(uri, cancellation, progress)
            }, beforeCandidateLoad = {
                stage("loading hook entered, await45 before")
                loadingAwaitEnteredAt.set(android.os.SystemClock.elapsedRealtime())
                loadEntered.countDown()
                val resumed = try { loadResume.await(45, TimeUnit.SECONDS) }
                finally { loadingAwaitReturnedAt.set(android.os.SystemClock.elapsedRealtime()) }
                stage("loading hook await45 after: resumed=$resumed")
                check(resumed)
            })
        }
        click("Open project copy")
        status("Project flow: real picker launched")
        waitFor("Actual picker did not start") { owner.state.phase == ProjectFlowPhase.CHOOSING }
        val pickerId = owner.state.requestId
        recreate() // MainActivity is stopped under DocumentsUI; the pending request belongs to its retained ViewModel.
        status("Project flow: stopped MainActivity recreated")
        check(activity.retainedProjectFlowForTest() === owner && owner.state.requestId == pickerId && owner.state.phase == ProjectFlowPhase.CHOOSING)
        chooseEditableTree()
        status("Project flow: actual provider tree returned")
        check(copyEntered.await(10, TimeUnit.SECONDS)) { "Real picker result was lost across recreation" }
        waitFor("Copy phase did not reach retained UI") { owner.state.phase == ProjectFlowPhase.COPYING }
        val importId = owner.state.requestId
        recreate()
        status("Project flow: copying MainActivity recreated")
        check(activity.retainedProjectFlowForTest() === owner && owner.state.requestId == importId && copies.get() == 1)
        check(workspace.snapshot().editor === oldEditor && workspace.snapshot().resourceGrowthFrozen)
        check(owner.state.canCancel) { "Provider copy must remain cancellable after recreation" }
        copyResume.countDown()
        check(loadEntered.await(10, TimeUnit.SECONDS))
        val loadingStarted = android.os.SystemClock.elapsedRealtime()
        waitFor("Native load phase did not disable cancel") { owner.state.phase == ProjectFlowPhase.LOADING && !owner.state.canCancel }
        stage("loading recreate before")
        recreate()
        stage("loading recreate after")
        status("Project flow: loading MainActivity recreated")
        check(activity.retainedProjectFlowForTest() === owner && owner.state.requestId == importId)
        var acceptedCancel = true
        stage("loading cancel check before")
        instrumentation.runOnMainSync { acceptedCancel = owner.cancel() }
        stage("loading cancel check after: accepted=$acceptedCancel")
        check(!acceptedCancel) { "UI advertised native decoder interruption" }
        // A real home/return transition does not start a new copy or cancel the current native phase.
        val transitionFile = File(context.filesDir, "p40-project-home-return-${System.nanoTime()}.json")
        val transition = JSONObject().put("schema", 1).put("scope", "New execution only; no old failure operand reconstruction")
            .put("expectedOwnerIdentity", System.identityHashCode(owner)).put("expectedRequestId", importId)
        val observations = JSONArray(); val commands = JSONArray()
        data class TransitionObservation(val record: JSONObject, val homeReady: Boolean,
            val loadingVisible: Boolean, val completedAtNanos: Long)
        fun transitionShell(command: String): String {
            val started = System.nanoTime()
            val output = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            commands.put(JSONObject().put("command", command).put("startedAtNanos", started)
                .put("completedAtNanos", System.nanoTime()).put("output", output))
            if (output.isNotBlank()) status("$command: ${output.trim()}")
            return output
        }
        fun preserveTransition() {
            transition.put("commands", commands).put("observations", observations)
            transitionFile.writeText(transition.toString(2))
            status("Project flow home/return evidence: files/${transitionFile.name}")
        }
        var homePackage: String? = null
        fun observeTransition(label: String, inspectLoading: Boolean): TransitionObservation {
            val started = System.nanoTime()
            val record = JSONObject().put("stage", label).put("startedAtNanos", started)
            var stopped = false
            instrumentation.runOnMainSync {
                val lifecycle = activity.lifecycle.currentState
                stopped = !lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
                    !activity.isFinishing && !activity.isDestroyed
                val flow = owner.state
                val manager = activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                record.put("activityIdentity", System.identityHashCode(activity)).put("taskId", activity.taskId)
                    .put("lifecycle", lifecycle.name).put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
                    .put("windowFocus", activity.hasWindowFocus()).put("windowVisibility", activity.window.decorView.windowVisibility)
                    .put("ownerIdentity", System.identityHashCode(activity.retainedProjectFlowForTest()))
                    .put("sameOwner", activity.retainedProjectFlowForTest() === owner).put("requestId", flow.requestId)
                    .put("phase", flow.phase.name).put("canCancel", flow.canCancel).put("error", flow.error ?: JSONObject.NULL)
                    .put("accessibilityEnabled", manager.isEnabled).put("touchExplorationEnabled", manager.isTouchExplorationEnabled)
                // Read the pinned Compose testing flag without changing its service or forcing it on.
                fun compose(view: android.view.View): android.view.View? {
                    if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
                        compose(view.getChildAt(index))?.let { return it }
                    return null
                }
                runCatching {
                    val root = checkNotNull(compose(activity.window.decorView))
                    val delegate = root.javaClass.getDeclaredField("composeAccessibilityDelegate")
                        .apply { isAccessible = true }.get(root)
                    val getter = checkNotNull(delegate).javaClass.methods.single {
                        it.name.startsWith("getAccessibilityForceEnabledForTesting") && it.parameterCount == 0
                    }
                    record.put("composeAccessibilityForceEnabled", getter.invoke(delegate))
                }.onFailure { record.put("composeAccessibilityObservationError", it.toString()) }
            }
            // One root and one predicate sample: preserve the exact case-insensitive loading label lookup.
            // Do not clear AX caches, force Compose semantics, or retry a lookup after the deadline.
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val rootPackage = root?.packageName?.toString()
            val rootVisible = root?.isVisibleToUser == true
            val loading = if (inspectLoading) findMatching(root) {
                it.text?.toString()?.equals("Opening project…", true) == true ||
                    it.contentDescription?.toString()?.equals("Opening project…", true) == true
            } else null
            val homeReady = stopped && rootPackage == homePackage && rootVisible
            val completed = System.nanoTime()
            record.put("rootPackage", rootPackage ?: JSONObject.NULL).put("rootWindowId", root?.windowId ?: JSONObject.NULL)
                .put("rootVisible", rootVisible).put("loadingLookupEvaluated", inspectLoading)
                .put("loadingFound", if (inspectLoading) loading != null else JSONObject.NULL)
                .put("loadingWindowId", loading?.windowId ?: JSONObject.NULL)
                .put("homeReady", homeReady).put("completedAtNanos", completed)
            observations.put(record)
            return TransitionObservation(record, homeReady, loading != null, completed)
        }
        try {
            val resolved = transitionShell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lineSequence().map { it.trim() }.lastOrNull { it.contains('/') && !it.contains(' ') }
            homePackage = checkNotNull(resolved) { "Default HOME activity did not resolve" }.substringBefore('/')
            transition.put("homeComponent", resolved).put("homePackage", homePackage)
            val service = instrumentation.uiAutomation.serviceInfo
            transition.put("automationService", JSONObject().put("flags", service.flags).put("eventTypes", service.eventTypes)
                .put("feedbackType", service.feedbackType).put("notificationTimeout", service.notificationTimeout))
            val transitionStarted = System.nanoTime()
            val transitionDeadline = transitionStarted + TimeUnit.SECONDS.toNanos(20)
            transition.put("startedAtNanos", transitionStarted).put("deadlineNanos", transitionDeadline)
                .put("budgetSeconds", 20)
            fun awaitTransition(label: String, inspectLoading: Boolean, accepts: (TransitionObservation) -> Boolean) {
                stage("waitFor begin: $label (shared HOME/return deadline)")
                var last: TransitionObservation? = null
                while (System.nanoTime() < transitionDeadline) {
                    val sample = observeTransition(label, inspectLoading)
                    last = sample
                    if (sample.completedAtNanos <= transitionDeadline && accepts(sample)) {
                        transition.put("lastAcceptedStage", label).put("lastAcceptedObservation", sample.record)
                        stage("waitFor observed ready: $label")
                        return
                    }
                    if (System.nanoTime() < transitionDeadline) Thread.sleep(20)
                }
                transition.put("failedStage", label).put("lastObservationAtFailure", last?.record ?: JSONObject.NULL)
                    .put("failureAtNanos", System.nanoTime())
                error("$label (shared HOME/return 20s deadline; last=${last?.record}; evidence=files/${transitionFile.name})")
            }
            stage("loading HOME before")
            transitionShell("input keyevent KEYCODE_HOME")
            stage("loading HOME shell returned; waiting actual stopped Activity and visible HOME root")
            awaitTransition("HOME did not finish before retained task return", false) { it.homeReady }
            stage("loading HOME observed complete, single am start before")
            check(System.nanoTime() < transitionDeadline) { "Shared HOME/return deadline expired before the single return request" }
            transitionShell("am start -n com.pojia.compositor.mobile/.MainActivity --task ${activity.taskId} -f 0x00020000")
            stage("loading am start after")
            status("Project flow: returning to retained loading task ${activity.taskId}")
            awaitTransition("Retained loading dialog did not return", true) { it.loadingVisible }
            fixtureUiFence()
            transition.put("passed", true).put("completedAtNanos", System.nanoTime())
            preserveTransition()
        } catch (error: Exception) {
            transition.put("passed", false).put("error", error.toString())
            runCatching { preserveTransition() }.onFailure { if (it !== error) error.addSuppressed(it) }
            // This is failure-only evidence before finally releases the native loading hook.
            runCatching { pickerActionFailure("HOME/return loading transition", { available("Opening project…") },
                null, null, null, null, error) }.onFailure { if (it !== error) error.addSuppressed(it) }
            throw error
        }
        finally { status("Project flow: loading gate elapsed=${android.os.SystemClock.elapsedRealtime() - loadingStarted}ms phase=${owner.state.phase} error=${owner.state.error}") }
        check(copies.get() == 1 && owner.state.requestId == importId)
        loadResume.countDown()
        waitFor("Retained completion did not finish") { !owner.state.busy && !workspace.snapshot().resourceGrowthFrozen }
        check(workspace.snapshot().editor !== oldEditor && !workspace.snapshot().projectDirty && owner.state.error == null)
        check(workspace.snapshot().editor!!.state().document.uuid == "A2000000-0000-4000-8000-000000000001")
        check(copies.get() == 1)

        // A real picker result captured before replacement cannot install into the replacement document.
        instrumentation.runOnMainSync { owner.testHooks = null }
        click("Open project copy")
        waitFor("Second real picker did not start") { owner.state.phase == ProjectFlowPhase.CHOOSING }
        check(workspace.installCandidate("picker-stale", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS).installed)
        val replacement = checkNotNull(workspace.snapshot().editor)
        chooseEditableTree()
        waitFor("Stale picker result did not terminate") { owner.state.phase == ProjectFlowPhase.IDLE }
        check(workspace.snapshot().editor === replacement && owner.state.error?.contains("changed") == true && copies.get() == 1)
        instrumentation.runOnMainSync { owner.clearError() }

        // Cancel while the actual retained UI is recreated during provider-copy admission.
        val cancelledEntered = CountDownLatch(1); val cancelledExit = CountDownLatch(1)
        val cancellationCopies = AtomicInteger()
        instrumentation.runOnMainSync {
            owner.testHooks = ProjectFlowHooks(stage = { _, cancellation, _ ->
                cancellationCopies.incrementAndGet(); cancelledEntered.countDown()
                try { while (true) { cancellation.check(); Thread.sleep(20) } }
                finally { cancelledExit.countDown() }
                @Suppress("UNREACHABLE_CODE") error("Cancelled source returned")
            })
        }
        click("Open project copy"); chooseEditableTree()
        check(cancelledEntered.await(10, TimeUnit.SECONDS))
        waitFor("Cancellable copy UI missing") { owner.state.phase == ProjectFlowPhase.COPYING }
        recreate()
        click("Cancel")
        check(cancelledExit.await(10, TimeUnit.SECONDS))
        waitFor("Cancelled retained flow stayed busy") { !owner.state.busy && !workspace.snapshot().resourceGrowthFrozen }
        check(workspace.snapshot().editor === replacement && owner.state.error == null && cancellationCopies.get() == 1)
        return "Cold-start owner sweeps staging once without document freeze/baseline changes and skips active leases. Real DocumentsUI: pending picker survives stopped Activity recreation; copy/loading survive recreation and home/return without duplicate work; native cancel disabled; late stale picker rejected; recreated copy cancellation releases admission"
    } finally {
        copyResume.countDown(); loadResume.countDown()
        startupActive.close(); startupOrphan.deleteRecursively()
        if (owner.state.phase == ProjectFlowPhase.CHOOSING) shell("input keyevent KEYCODE_BACK")
        instrumentation.runOnMainSync { owner.testHooks = null; activity.finish() }
        fixtureUiFence()
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
    }
}

/** A replacement queued first must not retire unrelated private staging maintenance. */
private fun startupMaintenanceReplacementSmoke(instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val root = File(context.filesDir.canonicalFile, "project-staging").apply { mkdirs() }
    val orphan = File(root, "${UUID.randomUUID()}.comp").apply { check(mkdir()) }
    val scratch = File(context.cacheDir.canonicalFile, "maintenance-workspace-${System.nanoTime()}")
    val workspace = AndroidWorkspace(scratch)
    val entered = CountDownLatch(1); val resume = CountDownLatch(1)
    var owner: ProjectFlowController? = null
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (workspace.snapshot().loading && System.nanoTime() < deadline) Thread.sleep(20)
        check(workspace.snapshot().editor != null)
        val ready = workspace.snapshot()
        workspace.runIo(checkNotNull(workspace.token()), { entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }) {}
        check(entered.await(10, TimeUnit.SECONDS))
        val replacement = workspace.installCandidate("maintenance-replacement", DirtyDisposition.DISCARD) { NativeEditor(8) }
        instrumentation.runOnMainSync {
            owner = ProjectFlowController(context, workspace)
            checkNotNull(owner).workspaceChanged(ready)
        }
        check(workspace.snapshot().generation == ready.generation && orphan.exists() && !workspace.snapshot().resourceGrowthFrozen)
        resume.countDown()
        check(replacement.result.get(10, TimeUnit.SECONDS).installed)
        val resultDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (owner!!.startupCleanupResult == null && System.nanoTime() < resultDeadline) Thread.sleep(20)
        check(checkNotNull(owner!!.startupCleanupResult).getOrThrow().removed >= 1 && !orphan.exists())
        check(workspace.snapshot().generation != ready.generation && !workspace.snapshot().resourceGrowthFrozen)
        // Block main delivery until a queued replacement retires the completed save's originating generation.
        // The owner must clear its own busy state even though the save callback's token is now stale.
        instrumentation.runOnMainSync {
            val retained = checkNotNull(owner)
            retained.saveCopy(checkNotNull(workspace.snapshot().editor))
            check(retained.state.busy)
            val next = workspace.installCandidate("maintenance-save-retired", DirtyDisposition.DISCARD) { NativeEditor(8) }
            check(next.result.get(10, TimeUnit.SECONDS).installed)
            check(retained.state.busy)
        }
        instrumentation.waitForIdleSync()
        check(!owner!!.state.busy && owner!!.state.error == null && !workspace.snapshot().resourceGrowthFrozen)
        check(workspace.snapshot().sourceIdentity == "maintenance-save-retired")
    } finally {
        resume.countDown()
        instrumentation.runOnMainSync { owner?.close() }
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        orphan.deleteRecursively(); scratch.deleteRecursively()
    }
}
