package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Actual Compose clicks cover saved copies, dirty confirmation, cancellation and workspace replacement. */
internal fun projectWorkflowUiSmoke(instrumentation: Instrumentation, fileShortcuts: Boolean = false): String {
    val context = instrumentation.targetContext
    val before = LocalProjectLibrary.projects(context).map { it.name }.toSet()
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val automation = editorUiAutomation(instrumentation)
    val owner = activity.retainedProjectFlowForTest()
    val traces = mutableListOf<EditorUiTrace>()
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { message }
        instrumentation.waitForIdleSync()
    }
    fun findButton(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.isEnabled && target.isVisibleToUser) return target
                target = target.parent
            }
        }
        for (index in 0 until node.childCount) findButton(node.getChild(index), label)?.let { return it }
        return null
    }
    fun click(label: String) {
        val trace = EditorUiTrace(label) { editorUiOwnState(instrumentation, activity) }.also(traces::add)
        revealEditorControl(instrumentation, label, trace)
        var clicked = false
        waitFor("Missing UI action: $label") {
            if (!clicked) {
                automation.clearCache()
                val active = automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }
                clicked = clickEditorUiNode(findButton(active, label), trace)
            }
            clicked
        }
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
        check(editor.draw(32, 32, 0.0, 0.0, editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble(), buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    try {
        waitFor("Editor did not open") { activity.retainedEditorForTest() != null }
        val workspace = activity.retainedWorkspaceForTest()
        val installed = workspace.installCandidate("ui-test", DirtyDisposition.DISCARD) { NativeEditor(8) }
            .result.get(10, TimeUnit.SECONDS)
        check(installed.installed)
        waitFor("Test document did not become active") {
            var ready = false
            instrumentation.runOnMainSync {
                val current = workspace.snapshot().editor
                fun boundCanvas(view: View): Boolean {
                    if (view is CanvasView) {
                        val bound = CanvasView::class.java.getDeclaredField("editor").apply { isAccessible = true }
                            .get(view) as NativeEditor
                        if (bound === current && view.isAttachedToWindow && view.holder.surface.isValid &&
                            view.width > 0 && view.height > 0) return true
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) {
                        if (boundCanvas(view.getChildAt(index))) return true
                    }
                    return false
                }
                ready = current != null && activity.retainedEditorForTest() === current &&
                    boundCanvas(activity.window.decorView)
            }
            ready
        }
        val editor = checkNotNull(activity.retainedEditorForTest())
        val original = pixels(editor)
        val uuid = editor.state().document.uuid
        if (fileShortcuts) {
            val shortcutRevision = editor.currentRevision()
            val groups = arrayListOf<android.view.KeyboardShortcutGroup>()
            instrumentation.runOnMainSync { activity.onProvideKeyboardShortcuts(groups, null, -1) }
            val help = groups.last { it.label.toString() == context.getString(R.string.ui_file) }.items
            check(help.size == 7 && help.map { it.keycode }.toSet() == setOf(
                android.view.KeyEvent.KEYCODE_N, android.view.KeyEvent.KEYCODE_O,
                android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_E, android.view.KeyEvent.KEYCODE_W))
            check(help.filter { it.keycode == android.view.KeyEvent.KEYCODE_S }.map { it.modifiers }.toSet() == setOf(
                android.view.KeyEvent.META_CTRL_ON,
                android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON,
                android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_ALT_ON))
            fun key(code: Int, modifiers: Int, repeat: Int = 0) {
                val time = android.os.SystemClock.uptimeMillis()
                instrumentation.sendKeySync(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, code, repeat, modifiers))
                instrumentation.sendKeySync(android.view.KeyEvent(time, time + 1, android.view.KeyEvent.ACTION_UP, code, 0, modifiers))
                instrumentation.waitForIdleSync()
            }
            fun windowPackage() = automation.rootInActiveWindow?.packageName?.toString()
            fun editorWindowActive(): Boolean {
                var focused = false
                instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                return focused && windowPackage() == context.packageName
            }
            fun pickerWindowActive() = windowPackage()?.let { it != context.packageName } == true
            key(android.view.KeyEvent.KEYCODE_N, android.view.KeyEvent.META_CTRL_ON)
            waitFor("Ctrl+N did not open New canvas") { owner.state.newCanvas != null }
            click("Cancel")
            waitFor("New canvas cancellation did not return keyboard control") { !owner.state.busy && editorWindowActive() }
            // Exercise the shortcut advertised by Android's keyboard help. System Meta shortcuts
            // may be consumed before Android dispatches them to the application.
            key(android.view.KeyEvent.KEYCODE_O, android.view.KeyEvent.META_CTRL_ON)
            waitFor("Ctrl+O did not start the project picker") {
                owner.state.phase == ProjectFlowPhase.CHOOSING && pickerWindowActive()
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("Project picker cancellation did not return") {
                !owner.state.busy && editorWindowActive()
            }
            val images = activity.retainedImageRequestsForTest()
            for (code in listOf(android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_W)) {
                val modifiers = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_ALT_ON
                key(code, modifiers)
                waitFor("Export As ${android.view.KeyEvent.keyCodeToString(code)} shortcut did not open format choice") {
                    images.exportChoiceGeneration == workspace.snapshot().generation
                }
                check(!images.state.busy && owner.state.projectCopy == null)
                click("Cancel")
                waitFor("Export As cancellation did not return keyboard control") {
                    images.exportChoiceGeneration == null && editorWindowActive()
                }
                key(code, modifiers, repeat = 1)
                check(images.exportChoiceGeneration == null && !images.state.busy)
            }
            key(android.view.KeyEvent.KEYCODE_E, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON)
            waitFor("Ctrl+Shift+E did not start PNG export") {
                images.state.request?.purpose == ImageRequestPurpose.EXPORT_PNG && pickerWindowActive()
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("PNG picker cancellation did not return") {
                !images.state.busy && editorWindowActive()
            }
            check(workspace.snapshot().editor === editor && editor.currentRevision() == shortcutRevision)
            key(android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.META_CTRL_ON)
            waitFor("Ctrl+S did not request an initial save name") { owner.state.projectCopy != null }
            click("Cancel")
            waitFor("Save cancellation did not return keyboard control") { !owner.state.busy && editorWindowActive() }
            key(android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON)
            waitFor("Ctrl+Shift+S did not open copy naming") { owner.state.projectCopy != null }
            val nameRequest = owner.state.projectCopy
            key(android.view.KeyEvent.KEYCODE_N, android.view.KeyEvent.META_CTRL_ON)
            check(owner.state.newCanvas == null && owner.state.projectCopy == nameRequest)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Keyboard paths passed: New, Open, Export As and alias, PNG, Save, Save Copy, repeat and modal guards.\n")
            })
        } else click("Save project copy")
        click(context.getString(R.string.ui_save_project_copy_confirm))
        waitFor("Save did not publish a project baseline") {
            !workspace.snapshot().resourceGrowthFrozen && !workspace.snapshot().projectDirty &&
                LocalProjectLibrary.projects(context).any { it.name !in before }
        }
        val saved = LocalProjectLibrary.projects(context).single { it.name !in before }
        if (fileShortcuts) {
            check(workspace.snapshot().sourceIdentity == "local:${saved.name}")
            check(workspace.snapshot().editor === editor && pixels(editor).contentEquals(original))
            return "Keyboard New, Open, Export As and alias, PNG, Save and Save Copy; picker cancellation, repeat/modal guards and saved baseline with unchanged document pixels. Saved-library continuation is covered by projectWorkflowUi."
        }
        val token = checkNotNull(workspace.token(editor))
        check(workspace.execute(token) { editor.addLayer() })
        waitFor("Edit was not marked dirty") { workspace.snapshot().projectDirty }
        val editedRevision = editor.currentRevision()
        click("Saved projects"); click(LocalProjectLibrary.label(saved))
        waitFor("Dirty-open confirmation did not appear") { owner.state.confirmation != null && owner.state.savedProjects == null }
        click("Cancel")
        waitFor("Dirty-open cancellation did not finish") { !owner.state.busy }
        check(workspace.snapshot().editor === editor && editor.currentRevision() == editedRevision)
        click("Saved projects"); click(LocalProjectLibrary.label(saved))
        click("Discard changes and open")
        waitFor("Saved copy was not installed") {
            workspace.snapshot().editor !== editor && !workspace.snapshot().resourceGrowthFrozen
        }
        val reopened = checkNotNull(workspace.snapshot().editor)
        check(reopened.state().document.uuid == uuid && pixels(reopened).contentEquals(original))
        check(!workspace.snapshot().projectDirty)
        val copiesBeforeContinuation = LocalProjectLibrary.projects(context).toSet()
        val nextToken = checkNotNull(workspace.token(reopened))
        check(workspace.execute(nextToken) { reopened.addLayer() })
        waitFor("Copy-and-open edit was not marked dirty") { workspace.snapshot().projectDirty }
        click("Saved projects"); click(LocalProjectLibrary.label(saved))
        click(context.getString(R.string.ui_save_copy_and_open))
        waitFor("Copy-and-open naming did not appear") { owner.state.projectCopy != null }
        click("Cancel")
        waitFor("Copy naming cancellation did not restore dirty-open confirmation") {
            owner.state.projectCopy == null && owner.state.confirmation != null
        }
        check(workspace.snapshot().editor === reopened && workspace.snapshot().projectDirty)
        // Cancelling the name panel returns to the original unsaved confirmation.
        click(context.getString(R.string.ui_save_copy_and_open))
        click(context.getString(R.string.ui_save_project_copy_confirm))
        waitFor("Save then open did not install the target") {
            workspace.snapshot().editor !== reopened && !workspace.snapshot().resourceGrowthFrozen &&
                !activity.retainedProjectFlowForTest().state.busy
        }
        check(!workspace.snapshot().projectDirty && workspace.snapshot().sourceIdentity == "local:${saved.name}")
        check(pixels(checkNotNull(workspace.snapshot().editor)).contentEquals(original))
        check((LocalProjectLibrary.projects(context).toSet() - copiesBeforeContinuation).size == 1)
        val regularCopies = LocalProjectLibrary.projects(context).toSet()
        val beforeRegular = checkNotNull(workspace.snapshot().editor)
        val regularToken = checkNotNull(workspace.token(beforeRegular))
        check(workspace.execute(regularToken) { beforeRegular.addLayer() })
        waitFor("Save-and-open edit was not marked dirty") { workspace.snapshot().projectDirty }
        val expectedLayers = beforeRegular.state().layers.size
        click("Saved projects"); click(LocalProjectLibrary.label(saved))
        click(context.getString(R.string.ui_save_and_open))
        waitFor("Regular save then self-open did not install the updated source") {
            workspace.snapshot().editor !== beforeRegular && !workspace.snapshot().resourceGrowthFrozen &&
                !activity.retainedProjectFlowForTest().state.busy
        }
        val regularOpened = checkNotNull(workspace.snapshot().editor)
        check(!workspace.snapshot().projectDirty && regularOpened.state().layers.size == expectedLayers)
        check(LocalProjectLibrary.projects(context).toSet() == regularCopies)
        check(pixels(regularOpened).contentEquals(original))
        val buttonToken = checkNotNull(workspace.token(regularOpened))
        check(workspace.execute(buttonToken) { regularOpened.addLayer() })
        click(context.getString(R.string.ui_save_project))
        waitFor("Save project button did not confirm the current baseline") {
            !workspace.snapshot().projectDirty && !workspace.snapshot().resourceGrowthFrozen &&
                !activity.retainedProjectFlowForTest().state.busy
        }
        check(LocalProjectLibrary.projects(context).toSet() == regularCopies)
        click("Saved projects"); click(LocalProjectLibrary.label(saved))
        waitFor("Regular button save did not read back") {
            workspace.snapshot().editor !== regularOpened && !activity.retainedProjectFlowForTest().state.busy
        }
        check(checkNotNull(workspace.snapshot().editor).state().layers.size == expectedLayers + 1)
        return "Actual UI (fileShortcuts=$fileShortcuts): save copy, dirty cancel, discard/reopen, save-and-open name cancellation/return and confirmed save-before-replacement; regular save-and-self-open and Save button readback without implicit copies; target UUID/pixels preserved. SAF picker and external export are separate gates"
    } catch (error: Exception) {
        captureEditorUiFailure(instrumentation, activity, error, traces)
        throw error
    } finally {
        instrumentation.runOnMainSync { activity.finish() }
        LocalProjectLibrary.projects(context).filter { it.name !in before }.forEach { it.deleteRecursively() }
    }
}
