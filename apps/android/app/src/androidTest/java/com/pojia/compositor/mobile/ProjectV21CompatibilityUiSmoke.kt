package com.pojia.compositor.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Public OpenDocumentTree import and real editor controls, using source-pinned PC packages. */
internal fun projectV21CompatibilityUiSmoke(instrumentation: Instrumentation, languageArgument: String): String {
    check(BuildConfig.DEBUG)
    val languages = when (languageArgument) {
        "all" -> listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)
        "en" -> listOf(AppLanguage.ENGLISH)
        "zh-CN" -> listOf(AppLanguage.SIMPLIFIED_CHINESE)
        else -> error("projectV21Language must be all, en or zh-CN")
    }
    val context = instrumentation.targetContext
    val automation = editorUiAutomation(instrumentation)
    val previousFlags = automation.serviceInfo.flags
    val previousLanguage = AppLanguages.current(context)
    val token = "p146-${System.nanoTime()}"
    val evidence = File(context.filesDir, "$token-v21-evidence").apply { check(!exists() && mkdir()) }
    val records = JSONArray()
    val workspaces = JSONArray()
    val cleanupErrors = JSONArray()
    val report = JSONObject().put("passed", false).put("cleanupPassed", false).put("token", token)
        .put("inputMethod", "product Open project copy / actual DocumentsUI directory selection")
        .put("editMethod", "actual localized Rename / Undo / Redo controls; AX text input, not IME candidates")
        .put("saveReopenMethod", "product Save project copy / Saved projects")
        .put("defaultWorkspaceUsed", false).put("fullDesktopPixelParity", false)
        .put("requestedLanguages", JSONArray(languages.map { it.tag })).put("completedAccepted", 0).put("completedRejected", 0)
        .put("records", records).put("workspaces", workspaces).put("cleanupErrors", cleanupErrors)
    var activity: MainActivity? = null
    var workspace: AndroidWorkspace? = null
    var phase = "start-private-workspace"
    var primaryFailure: Throwable? = null
    var providerAttempted = false
    var pickerPending = false
    val fixturePackages = linkedSetOf<String>()
    val ownedCopies = linkedSetOf<File>()
    val library = File(context.filesDir, "saved-projects").canonicalFile
    val originalCopies = library.list()?.toSet().orEmpty()
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is Number -> value.toDouble().toString()
        is Boolean -> value.toString()
        is String -> JSONObject.quote(value)
        else -> error("Unexpected JSON value")
    }
    fun save() { File(evidence, "report.json").writeText(report.toString(2)) }
    fun record(name: String, details: JSONObject = JSONObject()) {
        phase = name
        records.put(details.put("phase", name).put("atUptimeMillis", SystemClock.uptimeMillis()))
        save()
    }
    fun <T> main(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }
    fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        error("$phase: $message")
    }
    fun session(action: String): Bundle {
        val received = AtomicReference<Bundle>()
        val latch = CountDownLatch(1)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                received.set((resultData ?: Bundle()).apply { putInt("resultCode", resultCode) }); latch.countDown()
            }
        }
        val active = checkNotNull(activity)
        waitFor("Private workspace must be foreground before provider $action") { main { active.hasWindowFocus() } }
        main { active.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName, V21ProjectProviderSessionActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("token", token); putExtra("action", action); putExtra("receiver", receiver)
        }) }
        check(latch.await(30, TimeUnit.SECONDS)) { "Provider $action did not return" }
        val result = checkNotNull(received.get())
        record("provider-$action", JSONObject().put("passed", result.getBoolean("passed"))
            .put("resultCode", result.getInt("resultCode")).put("error", result.getString("error") ?: JSONObject.NULL)
            .put("source", result.getString("source")?.let(::JSONObject) ?: JSONObject.NULL)
            .put("session", result.getString("session")?.let(::JSONObject) ?: JSONObject.NULL)
            .put("removed", result.getBoolean("removed")))
        check(result.getInt("resultCode") == 0 && result.getBoolean("passed")) { "Provider $action: ${result.getString("error")}" }
        return result
    }
    fun appRoot(): AccessibilityNodeInfo? {
        automation.clearCache()
        return editorPhysicalDisplayRoot(instrumentation)
    }
    fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
    fun label(root: AccessibilityNodeInfo?, text: String) = find(root) {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }
    fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    fun actionable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        repeat(16) {
            val value = current ?: return null
            if (value.isClickable || value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                return value.takeIf { it.isVisibleToUser && it.isEnabled && !bounds(it).isEmpty }
            current = value.parent
        }
        return null
    }
    fun click(text: String) {
        var node: AccessibilityNodeInfo? = null
        waitFor("Missing enabled app action: $text") { node = actionable(label(appRoot(), text)); node != null }
        val target = checkNotNull(node)
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "App click rejected: $text" }
        record("app-click", JSONObject().put("label", text).put("bounds", bounds(target).toShortString()))
        instrumentation.waitForIdleSync()
    }
    fun ui(id: Int) = checkNotNull(activity).getString(id)
    fun openFileAction(id: Int) {
        val text = ui(id)
        if (actionable(label(appRoot(), text)) == null) click(ui(R.string.ui_file))
        click(text)
    }
    fun systemRoot(): AccessibilityNodeInfo? {
        automation.clearCache()
        return automation.rootInActiveWindow?.takeIf {
            it.packageName?.toString() in setOf("com.google.android.documentsui", "com.android.documentsui")
        }
    }
    fun returnedToPrivateApp(): Boolean {
        automation.clearCache()
        val root = automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName } ?: return false
        val active = activity ?: return false
        if (main { active.hasWindowFocus() }) return true
        return main { active.retainedProjectFlowForTest().state.confirmation != null } &&
            label(root, ui(R.string.ui_unsaved_project_changes)) != null &&
            actionable(label(root, ui(R.string.ui_discard_changes_and_open))) != null &&
            actionable(label(root, ui(R.string.ui_cancel))) != null
    }
    fun resource(root: AccessibilityNodeInfo?, id: String) = find(root) {
        it.isVisibleToUser && (it.viewIdResourceName == id || it.viewIdResourceName?.endsWith(":id/$id") == true)
    }
    fun currentPickerDirectory(text: String): Boolean {
        val root = systemRoot()
        return label(resource(root, "toolbar"), text) != null && find(root) {
            it.isVisibleToUser && it.isEnabled && it.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true &&
                it.text?.toString() == text
        } != null
    }
    fun systemSignature(root: AccessibilityNodeInfo?): String {
        val values = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) values.add("${node.viewIdResourceName}:${node.text}:${node.contentDescription}:${bounds(node)}:${node.actionList.map { it.id }}")
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return values.joinToString("\n")
    }
    fun findPickerItem(listID: String, text: String, validEntry: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25_000
        val traversed = mutableSetOf<Pair<String, Int>>()
        var previous = ""; var same = 0; var scrollingFrom: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val list = resource(systemRoot(), listID)
            if (actionable(label(list, text)) != null) return
            val populated = find(list) { it.isVisibleToUser && validEntry(it) } != null
            val signature = if (populated) systemSignature(list) else ""
            if (scrollingFrom != null && signature != scrollingFrom && signature.isNotEmpty()) scrollingFrom = null
            same = if (signature.isNotEmpty() && signature == previous) same + 1 else 0
            previous = signature
            if (same >= 2 && scrollingFrom == null && list != null) {
                val direction = listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    .firstOrNull { action -> list.actionList.any { it.id == action } && (signature to action) !in traversed }
                if (direction != null) {
                    traversed.add(signature to direction)
                    check(list.performAction(direction)) { "Advertised picker list scroll was rejected: $listID" }
                    scrollingFrom = signature; previous = ""; same = 0
                    instrumentation.waitForIdleSync()
                }
            }
            // Roots and children arrive asynchronously. A stable incomplete list is not an absence proof.
            Thread.sleep(25)
        }
        error("Picker item did not become reachable within 25 seconds: $listID / $text; awaitingScroll=${scrollingFrom != null}")
    }
    fun systemClick(name: String, lookup: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?) {
        var target: AccessibilityNodeInfo? = null
        waitFor("System picker action unavailable: $name") {
            target = systemRoot()?.let(lookup)?.let(::actionable); target != null
        }
        check(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "System click rejected: $name" }
        record("picker-click", JSONObject().put("label", name).put("bounds", bounds(checkNotNull(target)).toShortString()))
        instrumentation.waitForIdleSync()
    }
    fun systemTap(name: String, lookup: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?) {
        var previous = ""; var same = 0; var rectangle: Rect? = null
        waitFor("System picker target geometry did not settle: $name") {
            val root = systemRoot(); val node = root?.let(lookup)
            val r = node?.let(::bounds)
            val valid = node != null && node.isVisibleToUser && node.isEnabled && r != null && !r.isEmpty &&
                bounds(checkNotNull(root)).contains(r)
            val signature = if (valid) "${node!!.windowId}:$r" else ""
            same = if (valid && signature == previous) same + 1 else 0
            previous = signature; rectangle = r
            same >= 2
        }
        val current = checkNotNull(systemRoot()?.let(lookup))
        check(bounds(current) == rectangle && current.isVisibleToUser && current.isEnabled)
        val r = checkNotNull(rectangle); val time = SystemClock.uptimeMillis()
        fun touch(action: Int, eventTime: Long) {
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinate = MotionEvent.PointerCoords().apply {
                x = r.exactCenterX(); y = r.exactCenterY(); pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f
            }
            val event = MotionEvent.obtain(time, eventTime, action, 1, arrayOf(pointer), arrayOf(coordinate),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, time)
        val upTime = maxOf(time + 1, SystemClock.uptimeMillis())
        touch(MotionEvent.ACTION_UP, upTime)
        record("picker-tap", JSONObject().put("label", name).put("bounds", r.toShortString())
            .put("inputSource", "touchscreen").put("toolType", "finger").put("downTime", time).put("upTime", upTime))
        instrumentation.waitForIdleSync()
    }
    fun pickPackage(packageName: String) {
        waitFor("DocumentsUI did not open") { resource(systemRoot(), "toolbar") != null }
        var previousInitial = ""; var sameInitial = 0
        waitFor("Initial picker directory did not finish loading") {
            val root = systemRoot(); val list = resource(root, "dir_list")
            val populated = find(list) { it.isVisibleToUser && !it.text.isNullOrBlank() } != null
            val loading = find(root) { it.isVisibleToUser && it.className?.toString()?.endsWith("ProgressBar") == true } != null
            val signature = if (populated && !loading) systemSignature(resource(root, "toolbar")) + systemSignature(list) else ""
            sameInitial = if (signature.isNotEmpty() && signature == previousInitial) sameInitial + 1 else 0
            previousInitial = signature
            sameInitial >= 2
        }
        record("picker-initial-directory-ready", JSONObject().put("populatedDirectory", true).put("stableReadings", sameInitial + 1)
            .put("visibleStateSHA256", sha(previousInitial.toByteArray())))
        val title = V21ProjectDocumentsProvider.title(token)
        if (resource(systemRoot(), "roots_list") == null) systemClick("navigation") { root ->
            find(resource(root, "toolbar")) { it.className?.toString() == "android.widget.ImageButton" && it.isClickable }
        }
        fun rootTitle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = label(resource(root, "roots_list"), title)
        findPickerItem("roots_list", title) { !it.text.isNullOrEmpty() }
        systemTap(title, ::rootTitle)
        waitFor("P146 provider root did not open") { currentPickerDirectory(title) }
        findPickerItem("dir_list", packageName) { it.text?.toString() in fixturePackages }
        systemTap(packageName) { label(it, packageName) }
        waitFor("Fixture directory did not open") { currentPickerDirectory(packageName) }
        systemClick("select project folder") { resource(it, "android:id/button1") }
        waitFor("Folder grant did not resolve") {
            systemRoot() == null || resource(systemRoot(), "alertTitle") != null
        }
        if (systemRoot() != null) systemClick("grant selected project folder") { resource(it, "android:id/button1") }
        waitFor("Picker did not return to app") { systemRoot() == null && returnedToPrivateApp() }
        pickerPending = false
    }
    fun current() = checkNotNull(checkNotNull(workspace).snapshot().editor)
    fun ready(editor: NativeEditor = current()) {
        editor.commandDispatcher.awaitIdleForTest().get(15, TimeUnit.SECONDS)
        val editorField = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
        waitFor("Current document did not bind to the foreground canvas") {
            main {
                fun bound(view: View): Boolean {
                    if (view is CanvasView) return editorField.get(view) === editor && view.isAttachedToWindow &&
                        view.holder.surface.isValid && view.width > 0 && view.height > 0 && view.hasWindowFocus() &&
                        !view.editingInProgress && !view.strokeInProgress
                    if (view is ViewGroup) for (i in 0 until view.childCount) if (bound(view.getChildAt(i))) return true
                    return false
                }
                val active = checkNotNull(activity)
                active.retainedEditorForTest() === editor && bound(active.window.decorView) &&
                    !checkNotNull(workspace).snapshot().resourceGrowthFrozen
            }
        }
    }
    fun sourceHashes(editor: NativeEditor): JSONObject = editor.serialized {
        val result = JSONObject(); val state = editor.state()
        checkNotNull(editor.captureScene()).use { scene ->
            state.layers.forEachIndexed { index, layer ->
                if (layer.assetPresent) {
                    check(layer.pixelWidth in 1..256 && layer.pixelHeight in 1..256 && scene.layerMetadata(index).tileCount == 1)
                    val data = ByteBuffer.allocateDirect(layer.pixelWidth * layer.pixelHeight * 4)
                    scene.readTile(index, 0, layer.pixelWidth * 4, data)
                    val bytes = ByteArray(data.capacity()).also { data.rewind(); data.get(it) }
                    result.put("${layer.uuid.lowercase()}:image", sha(bytes))
                }
                if (layer.hasMask) {
                    check(layer.maskWidth in 1..256 && layer.maskHeight in 1..256)
                    val data = ByteBuffer.allocateDirect(layer.maskWidth * layer.maskHeight)
                    editor.maskPixels(layer.id, layer.maskWidth, data)
                    val bytes = ByteArray(data.capacity()).also { data.rewind(); data.get(it) }
                    result.put("${layer.uuid.lowercase()}:mask", sha(bytes))
                }
            }
        }
        result
    }
    fun rendered(editor: NativeEditor): String = editor.serialized {
        check(editor.canvasWidth.toLong() * editor.canvasHeight in 1L..1_048_576L)
        val data = ByteBuffer.allocateDirect(editor.canvasWidth * editor.canvasHeight * 4)
        check(editor.draw(editor.canvasWidth, editor.canvasHeight, 0.0, 0.0,
            editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), data))
        sha(ByteArray(data.capacity()).also { data.rewind(); data.get(it) })
    }
    fun metadata(editor: NativeEditor): String = editor.serialized {
        val state = editor.state(); val ids = state.layers.associate { it.id to it.uuid }
        val layers = JSONArray()
        state.layers.forEach { layer -> layers.put(JSONObject()
            .put("layer", layer.copy(id = 0, parentId = 0, maskSourceId = 0).toString())
            .put("parentUUID", ids[layer.parentId] ?: "").put("maskSourceUUID", ids[layer.maskSourceId] ?: "")
            .put("line", editor.lineShape(layer.id)?.toString() ?: "")
            .put("text", editor.textStyleOrNull(layer.id)?.toMacJson() ?: JSONObject.NULL)
            .put("adjustment", editor.adjustmentStyleOrNull(layer.id)?.toMacJson() ?: JSONObject.NULL)) }
        canonical(JSONObject().put("document", state.document.copy(activeLayerId = 0).toString())
            .put("activeUUID", ids[state.document.activeLayerId] ?: "").put("width", editor.canvasWidth)
            .put("height", editor.canvasHeight).put("layers", layers))
    }
    fun transform(json: JSONObject) = Transform64(json.getJSONArray("origin").getDouble(0), json.getJSONArray("origin").getDouble(1),
        json.getJSONArray("size").getDouble(0), json.getJSONArray("size").getDouble(1), json.getDouble("rotation"),
        json.getBoolean("flipX"), json.getBoolean("flipY"))
    fun checkImported(fixture: JSONObject, editor: NativeEditor) {
        val expected = fixture.getJSONObject("manifest"); val state = editor.state(); val layers = expected.getJSONArray("layers")
        check(editor.canvasWidth == expected.getInt("width") && editor.canvasHeight == expected.getInt("height"))
        check(state.document.uuid.equals(expected.getString("documentID"), true) && state.document.resolution == expected.getDouble("resolution"))
        check(state.layers.size == layers.length())
        val guides = expected.optJSONArray("guides") ?: JSONArray()
        check(state.document.guides.size == guides.length())
        for (i in 0 until guides.length()) {
            val guide = guides.getJSONObject(i); val actual = state.document.guides[i]
            check(actual.uuid.equals(guide.getString("id"), true) && actual.position == guide.getDouble("position") &&
                actual.horizontal == (guide.getString("axis") == "horizontal"))
        }
        val ids = state.layers.associate { it.uuid.lowercase() to it.id }
        check(state.document.activeLayerId == ids.getValue(expected.getString("activeLayerID").lowercase()))
        for (i in 0 until layers.length()) {
            val source = layers.getJSONObject(i); val layer = state.layers[i]
            check(layer.uuid.equals(source.getString("id"), true) && layer.name == source.getString("name"))
            check(layer.visible == source.getBoolean("isVisible") && layer.opacity == source.optDouble("opacity", 1.0))
            check(source.optString("blendMode", "Normal") == "Normal" && layer.blend == 0)
            check(layer.transform == transform(source.getJSONObject("transform")) && layer.sampling == 0)
            check(layer.parentId == ids[source.optString("parentID").lowercase()].let { it ?: 0L })
            check(layer.maskSourceId == ids[source.optString("maskSourceID").lowercase()].let { it ?: 0L })
            check(layer.isGroup == source.optBoolean("isGroup", false) && layer.assetPresent == source.has("imageFile"))
            check(layer.hasMask == source.has("maskFile"))
            if (layer.hasMask) {
                check(layer.maskEnabled == source.optBoolean("maskEnabled", true) && layer.maskLinked == source.optBoolean("maskLinked", true))
                check(layer.maskPlacement == source.optJSONObject("maskPlacement")?.let(::transform) && layer.maskSampling == 0)
            }
            source.optJSONObject("text")?.let { check(editor.textStyleOrNull(layer.id) == TextLayerModel.fromMacJson(it)) }
            source.optJSONObject("adjustment")?.let { check(canonical(editor.adjustmentStyleOrNull(layer.id)?.toMacJson()) == canonical(AdjustmentLayerModel.fromMacJson(it).toMacJson())) }
            source.optJSONObject("shape")?.let { shape ->
                val actual = checkNotNull(editor.lineShape(layer.id)); check(shape.getString("kind") == "Line")
                val optionalFlags = listOf("lineWidth" to 1, "start" to 2, "end" to 4, "lineStyle" to 8, "lineCap" to 16, "smoothing" to 32)
                check(actual.fieldFlags == optionalFlags.sumOf { (key, flag) -> if (shape.has(key) && !shape.isNull(key)) flag else 0 })
                if (shape.has("smoothing")) check(!shape.getBoolean("smoothing"))
                check(actual.red == shape.getDouble("red") && actual.green == shape.getDouble("green") && actual.blue == shape.getDouble("blue"))
                check(actual.cornerRadius == shape.getDouble("cornerRadius"))
                if (shape.has("lineWidth")) check(actual.lineWidth == shape.getDouble("lineWidth"))
                if (shape.has("start")) check(actual.startX == shape.getJSONArray("start").getDouble(0) && actual.startY == shape.getJSONArray("start").getDouble(1))
                if (shape.has("end")) check(actual.endX == shape.getJSONArray("end").getDouble(0) && actual.endY == shape.getJSONArray("end").getDouble(1))
                if (shape.has("lineStyle")) check(actual.style == listOf("Solid", "Dotted", "Dashed", "Long Dash").indexOf(shape.getString("lineStyle")))
                if (shape.has("lineCap")) check(actual.cap == listOf("Round", "Square").indexOf(shape.getString("lineCap")))
            }
        }
        val actualAssets = sourceHashes(editor); val assets = fixture.getJSONArray("assets")
        check(actualAssets.length() == assets.length())
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i); val key = asset.getString("layerID").lowercase() + if (asset.getBoolean("mask")) ":mask" else ":image"
            check(actualAssets.getString(key) == asset.getString("sha256")) { "PC source asset differs: $key" }
        }
        check(!state.undo && !state.redo && state.revision == 0L)
    }
    fun layerAction(text: String) {
        revealInspectorPanel(instrumentation, InspectorPanel.LAYERS)
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            for (attempt in 0 until 32) {
                if (actionable(label(appRoot(), text)) != null) { click(text); return }
                val root = appRoot()
                val list = find(root) { it.contentDescription?.toString() == ui(R.string.ui_layer_list) && it.actionList.any { a -> a.id == direction } }
                    ?: break
                check(list.performAction(direction)) { "Advertised layer scroll rejected" }
                instrumentation.waitForIdleSync()
            }
        }
        error("Layer action is not reachable: $text")
    }
    fun selectUUID(uuid: String) {
        val editor = current(); val layer = editor.state().layers.single { it.uuid.equals(uuid, true) }
        if (editor.selection().primary == layer.id && editor.state().document.activeLayerId == layer.id && !editor.selection().mask) return
        layerAction(checkNotNull(activity).getString(R.string.ui_select_layer, layer.name))
        waitFor("Layer selection did not settle") { editor.selection().primary == layer.id && editor.state().document.activeLayerId == layer.id && !editor.selection().mask }
        editor.commandDispatcher.awaitIdleForTest().get(15, TimeUnit.SECONDS)
    }
    fun rename(value: String) {
        val editor = current(); val id = editor.state().document.activeLayerId; val revision = editor.currentRevision()
        layerAction(ui(R.string.ui_layer_options)); click(ui(R.string.ui_rename))
        var field: AccessibilityNodeInfo? = null
        waitFor("Rename field is missing") { field = findEditorEditableUiNode(appRoot(), ui(R.string.ui_layer_name)); field != null }
        check(checkNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        click(ui(R.string.ui_save_name))
        waitFor("Rename did not commit") { editor.state().layers.single { it.id == id }.name == value }
        editor.commandDispatcher.awaitIdleForTest().get(15, TimeUnit.SECONDS)
        check(editor.currentRevision() == revision + 1)
        ready(editor)
    }
    fun history(redo: Boolean, expected: String) {
        click(ui(if (redo) R.string.ui_redo else R.string.ui_undo))
        current().commandDispatcher.awaitIdleForTest().get(15, TimeUnit.SECONDS)
        waitFor("History did not restore exact metadata") { metadata(current()) == expected }
        ready()
    }
    fun fileHashes(folder: File): String {
        val result = JSONObject()
        if (folder.exists()) folder.walkTopDown().filter { it.isFile }.forEach {
            check(!Files.isSymbolicLink(it.toPath()))
            check(it.length() <= 32L * 1024 * 1024)
            result.put(it.relativeTo(folder).path, sha(it.readBytes()))
        }
        return canonical(result)
    }
    fun protectedState(): JSONObject {
        val owner = checkNotNull(workspace); val editor = current(); val state = owner.snapshot(); val selection = editor.selection()
        return JSONObject().put("editorIdentity", System.identityHashCode(editor)).put("generation", state.generation)
            .put("sourceIdentity", state.sourceIdentity ?: JSONObject.NULL).put("revision", editor.currentRevision())
            .put("contentIdentity", state.currentContentIdentity).put("projectBaseline", state.projectBaseline ?: JSONObject.NULL)
            .put("contentBaseline", state.projectContentBaseline ?: JSONObject.NULL).put("dirty", state.projectDirty)
            .put("undo", editor.state().undo).put("redo", editor.state().redo).put("metadata", metadata(editor))
            .put("sourceHashes", sourceHashes(editor)).put("renderSHA256", rendered(editor))
            .put("selection", JSONObject().put("primary", selection.primary).put("roots", JSONArray(selection.roots))
                .put("mask", selection.mask).put("epoch", selection.epoch).put("generation", selection.generation))
            .put("recoveryFiles", fileHashes(owner.recoveryDirectoryForTest()))
            .put("stagingEntries", JSONArray(File(context.filesDir, "project-staging").list()?.sorted().orEmpty()))
    }
    fun capture(name: String) {
        val nodes = JSONArray()
        fun walk(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 48 || nodes.length() >= 1500) return
            nodes.put(JSONObject().put("text", node.text ?: JSONObject.NULL).put("description", node.contentDescription ?: JSONObject.NULL)
                .put("bounds", bounds(node).toShortString()).put("enabled", node.isEnabled).put("visible", node.isVisibleToUser))
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(automation.rootInActiveWindow)
        File(evidence, "$name-ui.json").writeText(nodes.toString(2))
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    fun copyOutput(source: File, target: File) {
        check(!target.exists() && target.mkdir())
        source.walkTopDown().forEach { file ->
            check(!Files.isSymbolicLink(file.toPath()))
            val relative = file.relativeTo(source).path
            if (relative.isNotEmpty()) {
                val destination = File(target, relative)
                if (file.isDirectory) check(destination.mkdir()) else {
                    check(file.length() <= 32L * 1024 * 1024); file.copyTo(destination)
                    check(sha(file.readBytes()) == sha(destination.readBytes()))
                }
            }
        }
    }
    fun openPublic(fixture: JSONObject, expectSuccess: Boolean) {
        val editor = current(); val owner = checkNotNull(workspace); val flow = checkNotNull(activity).retainedProjectFlowForTest()
        check(flow.testHooks == null)
        pickerPending = true
        openFileAction(R.string.ui_open_project_copy)
        pickPackage(fixture.getString("package"))
        fun completed() = main {
            !flow.state.busy && (owner.snapshot().editor !== editor || flow.state.errorNotice != null)
        }
        waitFor("Open did not produce confirmation or completion") { main { flow.state.confirmation != null } || completed() }
        if (main { flow.state.confirmation != null }) click(ui(R.string.ui_discard_changes_and_open))
        waitFor("Public import did not finish") { completed() }
        if (expectSuccess) check(owner.snapshot().editor !== editor && main { flow.state.error == null })
        else check(owner.snapshot().editor === editor && main { flow.state.errorNotice?.key == UiNoticeKey.UNSUPPORTED_PROJECT })
        ready()
    }
    fun startPrivateWorkspace(language: AppLanguage) {
        main { AppLanguages.set(context, language) }
        val workspaceToken = "$token-${language.tag}"
        val directory = File(context.filesDir, "debug-workspaces/$workspaceToken").canonicalFile
        check(validatedDebugWorkspaceToken(workspaceToken) == workspaceToken && !directory.exists())
        workspaces.put(JSONObject().put("token", workspaceToken).put("closed", false).put("removed", false))
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, workspaceToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
        workspace = checkNotNull(activity).retainedWorkspaceForTest()
        check(checkNotNull(workspace).recoveryDirectoryForTest().canonicalFile == directory)
        waitFor("Private workspace did not load") { checkNotNull(workspace).snapshot().editor != null }
        check(checkNotNull(activity).resources.configuration.locales[0].language == language.tag.substringBefore('-'))
        waitFor("Private project flow startup maintenance did not finish") {
            checkNotNull(activity).retainedProjectFlowForTest().startupCleanupResult != null
        }
        ready()
        record("${language.tag}/private-workspace-ready", JSONObject().put("token", workspaceToken))
    }
    try {
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        startPrivateWorkspace(languages.first())
        providerAttempted = true
        val prepared = session("prepare")
        val expectedText = checkNotNull(prepared.getString("expected")); val expected = JSONObject(expectedText)
        check(expected.getBoolean("passed") && expected.getInt("sourceCurrent") == 21 && expected.getString("commit").matches(Regex("[0-9a-f]{40}")))
        File(evidence, "fixture-expected.json").writeText(expectedText)
        report.put("expectedSHA256", sha(expectedText.toByteArray())).put("pcCommit", expected.getString("commit"))
        val fixtures = expected.getJSONArray("fixtures"); val positive = (0 until fixtures.length()).map(fixtures::getJSONObject).filter { it.getString("expectedMobile") == "accept" }
        val negative = (0 until fixtures.length()).map(fixtures::getJSONObject).filter { it.getString("expectedMobile") == "reject" }
        for (i in 0 until fixtures.length()) fixturePackages.add(fixtures.getJSONObject(i).getString("package"))
        check(positive.isNotEmpty() && negative.isNotEmpty() && positive.size + negative.size == fixtures.length())
        report.put("expectedAccepted", positive.size * languages.size).put("expectedRejected", negative.size * languages.size)
        for ((languageIndex, language) in languages.withIndex()) {
            if (languageIndex > 0) startPrivateWorkspace(language)
            val workspaceToken = "$token-${language.tag}"
            val directory = File(context.filesDir, "debug-workspaces/$workspaceToken").canonicalFile
            val workspaceRecord = workspaces.getJSONObject(languageIndex)
            ready()
            for (fixture in positive) {
                val name = fixture.getString("name"); record("${language.tag}/$name/import")
                openPublic(fixture, true)
                val editor = current(); checkImported(fixture, editor)
                val originalAssets = canonical(sourceHashes(editor)); val originalRender = rendered(editor)
                val allowed = fixture.getJSONObject("allowedEdit"); selectUUID(allowed.getString("layerID"))
                val originalMetadata = metadata(editor)
                check(editor.state().layers.single { it.uuid.equals(allowed.getString("layerID"), true) }.name == allowed.getString("before"))
                rename(allowed.getString("after")); val editedMetadata = metadata(editor)
                history(false, originalMetadata); history(true, editedMetadata)
                check(canonical(sourceHashes(editor)) == originalAssets && rendered(editor) == originalRender)
                selectUUID(fixture.getJSONObject("manifest").getString("activeLayerID"))
                val expectedMetadata = metadata(editor)
                openFileAction(R.string.ui_save_project_copy)
                click(ui(R.string.ui_save_project_copy_confirm))
                val owner = checkNotNull(workspace); val flow = checkNotNull(activity).retainedProjectFlowForTest()
                waitFor("Save copy did not publish its durable baseline") {
                    val state = owner.snapshot()
                    if (state.sourceIdentity?.startsWith("local:") == true) {
                        val copy = File(library, state.sourceIdentity!!.removePrefix("local:")).canonicalFile
                        check(copy.parentFile == library && copy.name !in originalCopies && copy.isDirectory)
                        ownedCopies.add(copy)
                    }
                    !state.projectDirty && state.sourceIdentity?.startsWith("local:") == true && main { !flow.state.busy && flow.state.error == null }
                }
                val saved = File(library, checkNotNull(owner.snapshot().sourceIdentity).removePrefix("local:")).canonicalFile
                check(saved in ownedCopies)
                val manifest = JSONObject(File(saved, "manifest.json").readText())
                check(manifest.getInt("version") == fixture.getInt("outputVersion"))
                copyOutput(saved, File(evidence, "${language.tag}-$name.comp"))
                File(evidence, "${language.tag}-$name-metadata.json").writeText(expectedMetadata)
                openFileAction(R.string.ui_saved_projects); click(LocalProjectLibrary.label(saved))
                waitFor("Saved copy did not reopen") { owner.snapshot().editor !== editor && main { !flow.state.busy && flow.state.error == null } }
                ready()
                check(metadata(current()) == expectedMetadata && canonical(sourceHashes(current())) == originalAssets && rendered(current()) == originalRender)
                check(!current().state().undo && !current().state().redo && !owner.snapshot().projectDirty)
                capture("${language.tag}-$name-reopened")
                report.put("completedAccepted", report.getInt("completedAccepted") + 1)
                record("${language.tag}/$name/accepted", JSONObject().put("outputVersion", manifest.getInt("version"))
                    .put("metadataExact", true).put("sourceAssetsExact", true).put("renderReopenExact", true)
                    .put("savedPath", saved.path).put("renderSHA256", originalRender))
            }
            val historyLayer = current().state().layers.first { it.parentId == 0L }
            selectUUID(historyLayer.uuid)
            rename(historyLayer.name + " A"); val historyA = metadata(current())
            rename(historyLayer.name + " B"); val historyB = metadata(current())
            history(false, historyA)
            for (fixture in negative) {
                val name = fixture.getString("name"); record("${language.tag}/$name/refusal")
                val owner = checkNotNull(workspace)
                owner.flushAsync().get(20, TimeUnit.SECONDS); ready()
                check(current().state().undo && current().state().redo && owner.snapshot().projectDirty)
                val before = protectedState()
                openPublic(fixture, false)
                val notice = ui(R.string.notice_unsupported_project)
                waitFor("Localized unsupported notice was not actually visible") { label(appRoot(), notice) != null }
                val after = protectedState()
                check(canonical(before) == canonical(after)) { "Rejected ${fixture.getString("package")} changed the original workspace" }
                capture("${language.tag}-$name-rejected")
                File(evidence, "${language.tag}-$name-protection.json").writeText(JSONObject().put("before", before).put("after", after).put("exactlyUnchanged", true).toString(2))
                click(notice)
                history(true, historyB); history(false, historyA)
                report.put("completedRejected", report.getInt("completedRejected") + 1)
                record("${language.tag}/$name/rejected", JSONObject().put("notice", notice).put("visible", true)
                    .put("workspaceAssetsSelectionHistoryExactlyUnchanged", true).put("redoUndoContentVerified", true))
            }
            if (languageIndex < languages.lastIndex) {
                val owner = checkNotNull(workspace)
                main { checkNotNull(activity).finishAndRemoveTask() }
                owner.closeAsync().get(20, TimeUnit.SECONDS); workspaceRecord.put("closed", true)
                check(directory.deleteRecursively()); workspaceRecord.put("removed", !directory.exists())
                activity = null; workspace = null
            }
        }
        val verified = session("verify")
        check(verified.getString("inputSHA256") == prepared.getString("inputSHA256"))
        check(canonical(JSONObject(checkNotNull(verified.getString("source")))) == canonical(JSONObject(checkNotNull(prepared.getString("source")))))
        record("source-fixtures-unchanged")
        check(report.getInt("completedAccepted") == report.getInt("expectedAccepted") && report.getInt("completedRejected") == report.getInt("expectedRejected"))
        report.put("passed", true)
    } catch (failure: Throwable) {
        primaryFailure = failure
        report.put("passed", false).put("failurePhase", phase).put("failure", failure.toString())
        runCatching { capture("failure") }.exceptionOrNull()?.let { failure.addSuppressed(it) }
        throw failure
    } finally {
        fun cleanup(name: String, action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let { cleanupErrors.put(JSONObject().put("action", name).put("error", it.toString())) }
        }
        val active = activity; val owner = workspace
        cleanup("cancel-owned-picker") {
            if (pickerPending && systemRoot() != null) {
                for (attempt in 0 until 4) {
                    val picker = systemRoot() ?: break
                    val before = systemSignature(picker)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    instrumentation.waitForIdleSync()
                    waitFor("Owned picker did not react to Back") {
                        systemRoot() == null || systemSignature(systemRoot()) != before
                    }
                }
                waitFor("Owned picker cancellation did not return to the private activity") {
                    systemRoot() == null && returnedToPrivateApp()
                }
                pickerPending = false
                record("cancel-owned-picker", JSONObject().put("returnedToPrivateActivity", true))
            }
        }
        cleanup("cancel-owned-open-confirmation") {
            if (active != null && main { active.retainedProjectFlowForTest().state.confirmation != null }) {
                check(returnedToPrivateApp()) { "Owned open confirmation is not foreground" }
                click(ui(R.string.ui_cancel))
                waitFor("Owned open confirmation did not dismiss") {
                    main { active.retainedProjectFlowForTest().state.confirmation == null && active.hasWindowFocus() }
                }
                record("cancel-owned-open-confirmation", JSONObject().put("returnedToPrivateActivity", true))
            }
        }
        cleanup("provider-session") { if (providerAttempted) check(session("cleanup").getBoolean("removed")) }
        cleanup("finish-activity") { active?.let { main { it.finishAndRemoveTask() } } }
        var closed = owner == null
        cleanup("close-private-workspace") { owner?.closeAsync()?.get(20, TimeUnit.SECONDS); closed = true }
        if (closed && owner != null) cleanup("remove-private-workspace") {
            val directory = owner.recoveryDirectoryForTest().canonicalFile
            check(directory.parentFile == File(context.filesDir, "debug-workspaces").canonicalFile && directory.name.startsWith(token))
            check(!directory.exists() || directory.deleteRecursively())
            for (i in 0 until workspaces.length()) if (workspaces.getJSONObject(i).getString("token") == directory.name)
                workspaces.getJSONObject(i).put("closed", true).put("removed", !directory.exists())
        }
        ownedCopies.forEach { copy -> cleanup("remove-saved-${copy.name}") {
            check(copy.parentFile == library && copy.name !in originalCopies && !Files.isSymbolicLink(copy.toPath()))
            check(copy.deleteRecursively())
        } }
        cleanup("restore-language") { main { AppLanguages.set(context, previousLanguage) } }
        cleanup("restore-accessibility-flags") { automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags } }
        val clean = cleanupErrors.length() == 0
        report.put("cleanupPassed", clean)
        if (!clean) report.put("passed", false)
        save()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "P146 v21 evidence: files/${evidence.name}/report.json\n") })
        if (!clean) {
            val error = IllegalStateException("P146 cleanup failed: $cleanupErrors")
            primaryFailure?.addSuppressed(error) ?: throw error
        }
    }
    return "P146 public v21 compatibility passed: languages=${languages.joinToString { it.tag }}; actual SAF import, UI rename/Undo/Redo, saved copy/reopen, localized atomic rejection; evidence=files/${evidence.name}/report.json"
}
