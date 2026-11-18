package com.pojia.compositor.mobile

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Native load/edit/history -> actual UI local save/reopen. No UI editing, SAF picker, or real IME claim. */
internal fun p104ProjectInteropSmoke(instrumentation: Instrumentation, fixtureNames: List<String>,
    animatedFixtureName: String, rejectedFixtureNames: List<String> = emptyList(), fixtureDirectory: String = "p104-fixtures"): String {
    check(BuildConfig.DEBUG && fixtureNames.isNotEmpty() && fixtureNames.size <= 16)
    val context = instrumentation.targetContext
    fun validName(name: String) = name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,119}\\.comp"))
    check(fixtureNames.distinct().size == fixtureNames.size && fixtureNames.all(::validName) && validName(animatedFixtureName))
    val highQualityFixtureName = "high-quality.comp"
    check(animatedFixtureName !in fixtureNames && highQualityFixtureName !in fixtureNames &&
        animatedFixtureName != highQualityFixtureName)
    check(rejectedFixtureNames.size <= 16 && rejectedFixtureNames.distinct().size == rejectedFixtureNames.size &&
        rejectedFixtureNames.all { validName(it) && it !in fixtureNames && it != animatedFixtureName && it != highQualityFixtureName })
    check(fixtureDirectory.matches(Regex("[a-zA-Z0-9-]{1,100}")))
    val fixtureRoot = File(context.filesDir, fixtureDirectory).canonicalFile
    check(fixtureRoot.parentFile == context.filesDir.canonicalFile)
    val token = "p104-interop-${System.nanoTime()}"
    val recoveryRoot = File(context.filesDir, "debug-workspaces/$token").canonicalFile
    check(validatedDebugWorkspaceToken(token) == token && !recoveryRoot.exists())
    val evidenceRoot = File(context.filesDir, "p104-interop-evidence/$token").canonicalFile
    check(!evidenceRoot.exists() && evidenceRoot.mkdirs())
    val records = JSONArray()
    val createdCopies = linkedMapOf<File, String>()
    val libraryRoot = File(context.filesDir, "saved-projects").canonicalFile
    val originalLibraryNames = libraryRoot.list()?.toSet().orEmpty()
    var activity: MainActivity? = null
    var workspace: AndroidWorkspace? = null
    var failure: Throwable? = null
    var passedFixtures = 0
    var complete = false
    var phase = "launch"
    fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    fun checkpoint(name: String, details: JSONObject = JSONObject()) {
        phase = name
        records.put(details.put("phase", name).put("atUptimeMillis", SystemClock.uptimeMillis()))
        File(evidenceRoot, "result.json").writeText(JSONObject().put("workspace", token).put("display", 0)
            .put("passed", complete).put("passedFixtures", passedFixtures).put("input", "accessibilityClick")
            .put("loadMethod", "nativeCandidate").put("editMethod", "nativeLayerRenameUndoRedo")
            .put("saveReopenMethod", "localizedAccessibilityClick").put("pureUiEditingCovered", false)
            .put("samplingScope", "nearestOnly").put("renderValidation", "androidCpuInternalReopenEquality")
            .put("highQualitySamplingSupported", false)
            .put("safPickerCovered", false).put("realImeCovered", false).put("fullDesktopParity", false)
            .put("records", records).toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "P104 interop: $name; evidence=$evidenceRoot\n") })
    }
    fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(30)
            instrumentation.waitForIdleSync()
        }
        error("$phase: $message")
    }
    fun editor() = checkNotNull(checkNotNull(workspace).snapshot().editor)
    fun canvas(view: View): CanvasView? {
        if (view is CanvasView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            canvas(view.getChildAt(index))?.let { return it }
        return null
    }
    val canvasEditor = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
    var readinessAttempt = 0
    fun ready(expected: NativeEditor) {
        val attempt = ++readinessAttempt
        val readyPhase = phase
        val transitions = JSONArray()
        var previousSnapshot = ""
        var lastSnapshot = JSONObject()
        fun property(view: CanvasView, name: String): Any {
            return runCatching {
                val getterName = "get" + name.replaceFirstChar { it.uppercase() }
                val getter = CanvasView::class.java.declaredMethods.firstOrNull {
                    it.parameterCount == 0 && (it.name == getterName || it.name.startsWith(getterName + "$"))
                }
                val raw = if (getter != null) {
                    getter.isAccessible = true
                    getter.invoke(view)
                } else {
                    val field = CanvasView::class.java.declaredFields.first {
                        it.name == name || it.name == name + "\$delegate"
                    }.apply { isAccessible = true }
                    val stored = field.get(view)
                    if (stored is androidx.compose.runtime.State<*>) stored.value else stored
                }
                when (raw) {
                    null -> "absent"
                    is Boolean, is Number -> raw
                    is Collection<*> -> "count=${raw.size}"
                    else -> "present"
                }
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        }
        fun snapshot(): JSONObject = main {
            val currentActivity = checkNotNull(activity)
            val decor = currentActivity.window.decorView
            val view = canvas(decor)
            val actualEditor = view?.let { canvasEditor.get(it) }
            val state = checkNotNull(workspace).snapshot()
            JSONObject().put("canvasExists", view != null)
                .put("editorMatches", actualEditor === expected)
                .put("expectedEditorIdentity", System.identityHashCode(expected))
                .put("canvasEditorIdentity", actualEditor?.let { System.identityHashCode(it) } ?: 0)
                .put("workspaceEditorMatches", state.editor === expected)
                .put("attached", view?.isAttachedToWindow ?: false)
                .put("surfaceValid", view?.holder?.surface?.isValid ?: false)
                .put("canvasWindowFocus", view?.hasWindowFocus() ?: false)
                .put("displayId", view?.display?.displayId ?: -1)
                .put("editing", view?.editingInProgress ?: false)
                .put("canvasWidth", view?.width ?: 0).put("canvasHeight", view?.height ?: 0)
                .put("canvasVisibility", view?.visibility ?: -1).put("canvasShown", view?.isShown ?: false)
                .put("activityTaskId", currentActivity.taskId).put("activityFinishing", currentActivity.isFinishing)
                .put("activityDestroyed", currentActivity.isDestroyed)
                .put("activityWindowFocus", currentActivity.hasWindowFocus())
                .put("decorAttached", decor.isAttachedToWindow).put("decorWindowVisibility", decor.windowVisibility)
                .put("decorDisplayId", decor.display?.displayId ?: -1)
                .put("windowFlags", currentActivity.window.attributes.flags)
                .put("windowType", currentActivity.window.attributes.type)
                .put("workspaceLoading", state.loading).put("resourceGrowthFrozen", state.resourceGrowthFrozen)
                .put("workspaceGeneration", state.generation)
                .put("editingConditions", JSONObject().apply {
                    if (view != null) listOf("retired", "strokeInProgress", "painting", "nativeFinishPending",
                        "nativeCommandPending", "samplingFinishPending", "moveInProgress", "moveContextPending",
                        "moveScrubSession", "distortSessionActive", "cropRect", "shapeDraft", "gradientDraft",
                        "lassoWorkingPoints", "magicPending").forEach { put(it, property(view, it)) }
                })
        }
        try {
            waitFor("Current editor did not attach on display0") {
                lastSnapshot = snapshot()
                val encoded = lastSnapshot.toString()
                if (encoded != previousSnapshot && transitions.length() < 64) {
                    transitions.put(JSONObject(encoded).put("atUptimeMillis", SystemClock.uptimeMillis()))
                    previousSnapshot = encoded
                }
                lastSnapshot.getBoolean("canvasExists") && lastSnapshot.getBoolean("editorMatches") &&
                    lastSnapshot.getBoolean("attached") && lastSnapshot.getBoolean("surfaceValid") &&
                    lastSnapshot.getBoolean("canvasWindowFocus") &&
                    lastSnapshot.getInt("displayId") == Display.DEFAULT_DISPLAY && !lastSnapshot.getBoolean("editing")
            }
        } catch (error: Throwable) {
            val diagnosticErrors = JSONArray()
            runCatching {
                editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
                    try { File(evidenceRoot, "readiness-$attempt-failed.png").outputStream().use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { bitmap.recycle() }
                }
            }.exceptionOrNull()?.let { diagnosticErrors.put(it.toString()) }
            val ownWindows = JSONArray()
            runCatching {
                editorPhysicalDisplayRoot(instrumentation)
                editorUiAutomation(instrumentation).windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
                    .filter { it.root?.packageName?.toString() == context.packageName }.forEach { window ->
                        ownWindows.put(JSONObject().put("id", window.id).put("type", window.type)
                            .put("displayId", window.displayId).put("layer", window.layer)
                            .put("active", window.isActive).put("focused", window.isFocused)
                            .put("bounds", Rect().also(window::getBoundsInScreen).toShortString()))
                    }
            }.exceptionOrNull()?.let { diagnosticErrors.put(it.toString()) }
            val diagnostic = JSONObject().put("phase", readyPhase).put("attempt", attempt)
                .put("lastSnapshot", lastSnapshot).put("transitions", transitions)
                .put("ownAccessibilityWindows", ownWindows).put("diagnosticErrors", diagnosticErrors)
            runCatching {
                File(evidenceRoot, "readiness-$attempt-failed.json").writeText(diagnostic.toString(2))
                checkpoint("$readyPhase/readiness-failed", diagnostic)
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw IllegalStateException("$readyPhase: readiness failed: $lastSnapshot; evidence=$evidenceRoot", error)
        }
    }
    fun clickable(label: String): AccessibilityNodeInfo? {
        var current = findEditorUiNode(editorPhysicalDisplayRoot(instrumentation), label)
        repeat(16) {
            val value = current ?: return null
            if (value.isClickable || value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                val bounds = Rect().also(value::getBoundsInScreen)
                return value.takeIf { it.isVisibleToUser && it.isEnabled && !bounds.isEmpty }
            }
            current = value.parent
        }
        return null
    }
    fun click(label: String, reveal: Boolean = true) {
        if (reveal) revealEditorControl(instrumentation, label)
        var target: AccessibilityNodeInfo? = null
        waitFor("UI action missing: $label") {
            editorUiAutomation(instrumentation).clearCache()
            target = clickable(label)
            if (target != null) true else {
                findEditorUiNode(editorPhysicalDisplayRoot(instrumentation), label)
                    ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                false
            }
        }
        check(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "UI click rejected: $label" }
        instrumentation.waitForIdleSync()
    }
    fun pixels(value: NativeEditor): ByteArray = value.serialized {
        check(value.canvasWidth.toLong() * value.canvasHeight in 1L..1_048_576L) { "Interop fixtures must stay small" }
        val bytes = ByteBuffer.allocateDirect(value.canvasWidth * value.canvasHeight * 4)
        check(value.draw(value.canvasWidth, value.canvasHeight, 0.0, 0.0,
            value.canvasWidth.toDouble(), value.canvasHeight.toDouble(), bytes))
        ByteArray(bytes.capacity()).also { bytes.rewind(); bytes.get(it) }
    }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
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
        else -> error("Unsupported metadata value: ${value.javaClass.name}")
    }
    fun metadata(value: NativeEditor): String = value.serialized {
        val state = value.state()
        val ids = state.layers.associate { it.id to it.uuid }
        val layers = JSONArray()
        state.layers.forEach { layer ->
            layers.put(JSONObject().put("layer", layer.copy(id = 0, parentId = 0, maskSourceId = 0).toString())
                .put("parentUuid", ids[layer.parentId] ?: "").put("maskSourceUuid", ids[layer.maskSourceId] ?: "")
                .put("line", value.lineShape(layer.id)?.toString() ?: "")
                .put("text", value.textStyleOrNull(layer.id)?.toMacJson() ?: JSONObject.NULL)
                .put("adjustment", value.adjustmentStyleOrNull(layer.id)?.toMacJson() ?: JSONObject.NULL))
        }
        canonical(JSONObject().put("width", value.canvasWidth).put("height", value.canvasHeight)
            .put("document", state.document.copy(activeLayerId = 0).toString())
            .put("activeUuid", ids[state.document.activeLayerId] ?: "").put("layers", layers))
    }
    fun checkedFixture(name: String): File = File(fixtureRoot, name).also {
        check(it.canonicalFile.parentFile == fixtureRoot && !Files.isSymbolicLink(it.toPath()) &&
            it.isDirectory && File(it, "manifest.json").isFile) { "Missing or unsafe oracle fixture: $name" }
    }
    fun copyPackage(source: File, destination: File) {
        check(!destination.exists() && destination.mkdir())
        var totalBytes = 0L
        source.walkTopDown().forEach { entry ->
            check(!Files.isSymbolicLink(entry.toPath()) && (entry.canonicalFile == source.canonicalFile ||
                entry.canonicalPath.startsWith(source.canonicalPath + File.separator)))
            val relative = entry.relativeTo(source)
            if (relative.path.isNotEmpty()) {
                val output = File(destination, relative.path)
                if (entry.isDirectory) check(output.mkdir()) else {
                    check(entry.isFile)
                    totalBytes += entry.length()
                    check(totalBytes <= 32L * 1024 * 1024) { "Oracle fixture exceeds test evidence budget" }
                    entry.copyTo(output, overwrite = false)
                    check(digest(entry.readBytes()) == digest(output.readBytes()))
                }
            }
        }
    }
    fun rejectCandidate(fixture: File, caseName: String) {
        checkpoint("$caseName/reject")
        val currentWorkspace = checkNotNull(workspace)
        val current = editor()
        ready(current)
        val before = currentWorkspace.snapshot()
        check(before.editor === current)
        val beforeMetadata = metadata(current)
        val beforePixels = pixels(current)
        val admission = ProjectAdmissionPolicy.forContext(context, current.retainedMemory())
        val loaderFailure = AtomicReference<ProjectImportException?>()
        val result = currentWorkspace.installCandidate("p104-rejected:$caseName", DirtyDisposition.DISCARD) {
            try {
                NativeEditor.loadProjectCandidate(context, fixture, admission.limitBytes, admission.residentBytes)
            } catch (error: ProjectImportException) {
                loaderFailure.set(error)
                throw error
            }
        }.result.get(25, TimeUnit.SECONDS)
        check(!result.installed && loaderFailure.get()?.status == 5) {
            "$caseName candidate was not rejected as unsupported: installed=${result.installed}; " +
                "loader=${loaderFailure.get()}; reason=${result.reason}"
        }
        val after = currentWorkspace.snapshot()
        check(after.editor === current && after.generation == before.generation &&
            after.sourceIdentity == before.sourceIdentity && after.currentRevision == before.currentRevision &&
            after.currentContentIdentity == before.currentContentIdentity &&
            after.projectBaseline == before.projectBaseline && after.projectContentBaseline == before.projectContentBaseline) {
            "$caseName rejected candidate changed current workspace"
        }
        check(metadata(current) == beforeMetadata && pixels(current).contentEquals(beforePixels)) {
            "$caseName rejected candidate changed current document metadata or pixels"
        }
        ready(current)
        checkpoint("$caseName/reject-passed", JSONObject().put("method", "workspace.installCandidate")
            .put("nativeStatus", checkNotNull(loaderFailure.get()).status).put("installed", result.installed)
            .put("workspaceUnchanged", true).put("pixelsSha256", digest(beforePixels))
            .put("manifestSha256", digest(File(fixture, "manifest.json").readBytes())))
    }
    try {
        fixtureNames.forEach(::checkedFixture)
        val animatedFixture = checkedFixture(animatedFixtureName)
        val highQualityFixture = checkedFixture(highQualityFixtureName)
        val rejectedFixtures = rejectedFixtureNames.map(::checkedFixture)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(DEBUG_WORKSPACE_TOKEN_EXTRA, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as MainActivity
        workspace = checkNotNull(activity).retainedWorkspaceForTest()
        check(checkNotNull(workspace).recoveryDirectoryForTest().canonicalFile == recoveryRoot) {
            "Refusing fixture load outside unique recovery workspace"
        }
        waitFor("Initial editor unavailable") { checkNotNull(workspace).snapshot().editor != null }
        fixtureNames.forEach { fixtureName ->
            checkpoint("$fixtureName/load")
            val currentWorkspace = checkNotNull(workspace)
            val previous = editor()
            val admission = ProjectAdmissionPolicy.forContext(context, previous.retainedMemory())
            val fixture = checkedFixture(fixtureName)
            val installed = currentWorkspace.installCandidate("p104-fixture:$fixtureName", DirtyDisposition.DISCARD) {
                NativeEditor.loadProjectCandidate(context, fixture, admission.limitBytes, admission.residentBytes)
            }.result.get(25, TimeUnit.SECONDS)
            check(installed.installed) { installed.reason ?: "Fixture load refused" }
            val loaded = editor()
            ready(loaded)
            check(loaded.state().layers.all { it.sampling == 0 && (!it.hasMask || it.maskSampling == 0) }) {
                "Success suite must use nearest sampling for layer pixels and masks: $fixtureName"
            }
            // Exercise rejection against a small, nonempty desktop fixture, within the 1MP evidence budget.
            if (fixtureName == fixtureNames.first()) rejectCandidate(highQualityFixture, "high-quality")
            val originalMetadata = metadata(loaded)
            val originalPixels = pixels(loaded)
            val renamedLayer = loaded.state().layers.firstOrNull { it.parentId == 0L }
                ?: error("Fixture has no root layer to rename: $fixtureName")
            val editedName = renamedLayer.name + " · P104互操作"
            loaded.renameLayer(renamedLayer.id, editedName)
            check(loaded.state().layers.single { it.id == renamedLayer.id }.name == editedName)
            val editedMetadata = metadata(loaded)
            check(editedMetadata != originalMetadata && pixels(loaded).contentEquals(originalPixels))
            loaded.undo(redo = false)
            check(metadata(loaded) == originalMetadata && pixels(loaded).contentEquals(originalPixels)) {
                "Native rename undo did not restore original fixture: $fixtureName"
            }
            loaded.undo(redo = true)
            check(metadata(loaded) == editedMetadata && pixels(loaded).contentEquals(originalPixels)) {
                "Native rename redo did not restore edit: $fixtureName"
            }
            waitFor("Edited fixture did not become dirty") { currentWorkspace.snapshot().projectDirty }
            checkpoint("$fixtureName/native-edit-undo-redo-passed", JSONObject()
                .put("method", "nativeLayerRenameUndoRedo").put("layerUuid", renamedLayer.uuid)
                .put("originalName", renamedLayer.name).put("editedName", editedName)
                .put("pixelPreservingEdit", true).put("pureUiEditingCovered", false))
            val beforeMetadata = metadata(loaded)
            val beforePixels = pixels(loaded)
            File(evidenceRoot, "$fixtureName.before-metadata.json").writeText(beforeMetadata)
            val flow = main { checkNotNull(activity).retainedProjectFlowForTest() }
            click(context.getString(R.string.ui_save_project_copy))
            click(context.getString(R.string.ui_save_project_copy_confirm))
            waitFor("UI local save did not commit") {
                val snapshot = currentWorkspace.snapshot()
                if (snapshot.sourceIdentity?.startsWith("local:") == true) {
                    val name = checkNotNull(snapshot.sourceIdentity).removePrefix("local:")
                    val exact = File(libraryRoot, name).canonicalFile
                    check(exact.parentFile == libraryRoot && name !in originalLibraryNames && exact.isDirectory)
                    createdCopies.putIfAbsent(exact, fixtureName)
                }
                main { !flow.state.busy && flow.state.error == null } &&
                    snapshot.sourceIdentity?.startsWith("local:") == true && !snapshot.projectDirty
            }
            val savedName = checkNotNull(currentWorkspace.snapshot().sourceIdentity).removePrefix("local:")
            val saved = File(libraryRoot, savedName).canonicalFile
            check(createdCopies[saved] == fixtureName)
            val savedManifest = JSONObject(File(saved, "manifest.json").readText())
            check(savedManifest.getInt("version") == 17) { "UI save did not use schema17" }
            check(savedManifest.getString("documentID").equals(loaded.state().document.uuid, ignoreCase = true))
            copyPackage(saved, File(evidenceRoot, fixtureName))
            click(context.getString(R.string.ui_saved_projects))
            waitFor("Saved-project list unavailable") { main { flow.state.savedProjects?.contains(saved) == true } }
            click(LocalProjectLibrary.label(saved), reveal = false)
            waitFor("UI saved copy did not replace editor") {
                currentWorkspace.snapshot().editor?.let { it !== loaded } == true && main { !flow.state.busy && flow.state.error == null }
            }
            val reopened = editor()
            ready(reopened)
            check(metadata(reopened) == beforeMetadata) { "Supported metadata changed after UI save/reopen: $fixtureName" }
            check(pixels(reopened).contentEquals(beforePixels)) { "Rendered bytes changed after UI save/reopen: $fixtureName" }
            editorUiAutomation(instrumentation).takeScreenshot()?.let { bitmap ->
                try { File(evidenceRoot, "$fixtureName.reopened.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
            }
            passedFixtures++
            checkpoint("$fixtureName/ui-save-reopen-passed", JSONObject().put("savedExactPath", saved.path)
                .put("manifestSha256", digest(File(saved, "manifest.json").readBytes()))
                .put("pixelsSha256", digest(beforePixels)).put("version", 17))
        }
        rejectCandidate(animatedFixture, "animation")
        rejectedFixtures.forEach { rejectCandidate(it, it.name.removeSuffix(".comp")) }
        complete = true
        checkpoint("all-fixtures-passed")
        return "display=0;workspace=$token;fixtures=$passedFixtures;native load+rename+Undo/Redo;schema17 nearest-sampling supported subset UI local Save copy+Saved projects Reopen exact edited supported metadata/Android CPU rendered bytes; original HQ and animated candidates rejected status5 through workspace installation with current document unchanged; no pure UI edit/SAF/IME; evidence=$evidenceRoot"
    } catch (error: Throwable) {
        failure = error
        runCatching { checkpoint("failed:$phase", JSONObject().put("error", error.toString())) }
        throw error
    } finally {
        val cleanupFailures = mutableListOf<Throwable>()
        runCatching { activity?.let { current -> main { current.finishAndRemoveTask() } } }
            .exceptionOrNull()?.let(cleanupFailures::add)
        runCatching { workspace?.closeAsync()?.get(15, TimeUnit.SECONDS) }.exceptionOrNull()?.let(cleanupFailures::add)
        createdCopies.forEach { (exact, fixtureName) ->
            runCatching {
                check(exact.parentFile == libraryRoot && exact.name !in originalLibraryNames &&
                    !Files.isSymbolicLink(exact.toPath()))
                // Only the exact package identified by this workspace's successful local-save receipt is owned.
                check(exact.deleteRecursively()) { "Could not remove own UI saved copy: $exact" }
                records.put(JSONObject().put("phase", "cleanup-owned-copy").put("fixture", fixtureName).put("path", exact.path))
            }.exceptionOrNull()?.let(cleanupFailures::add)
        }
        if (cleanupFailures.isNotEmpty()) complete = false
        runCatching { checkpoint("cleanup", JSONObject().put("errors", JSONArray(cleanupFailures.map { it.toString() }))) }
            .exceptionOrNull()?.let(cleanupFailures::add)
        if (failure != null) cleanupFailures.forEach { failure!!.addSuppressed(it) }
        else if (cleanupFailures.isNotEmpty()) throw cleanupFailures.first().also { first ->
            cleanupFailures.drop(1).forEach(first::addSuppressed)
        }
    }
}
