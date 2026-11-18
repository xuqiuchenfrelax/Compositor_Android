package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Selection-only revision is recoverable without turning unchanged content dirty. */
internal fun moveAuthorityWorkspaceSmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val folder = File(context.cacheDir, "move-authority-workspace-${UUID.randomUUID()}")
    val captures = AtomicInteger()
    val workspace = AndroidWorkspace(folder, recoveryHooks = RecoveryPublicationHooks(beforeCheckpoint = { kind, _, _ ->
        if (kind == RecoverySaveKind.AUTOSAVE) captures.incrementAndGet()
    }))
    val callbacks = ConcurrentLinkedQueue<() -> Unit>()
    var controller: TransformController? = null
    fun waitFor(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        check(condition()) { message }
    }
    try {
        waitFor("Workspace did not open") { !workspace.snapshot().loading }
        check(workspace.installCandidate("authority-selection", DirtyDisposition.DISCARD) {
            NativeEditor(8, false).also { editor ->
                val a = editor.importPixels(1,1,ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(32,0,0,-1)); rewind() })
                editor.importPixels(1,1,ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(64,0,0,-1)); rewind() })
                editor.selectLayer(a)
            }
        }.result.get(10,TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        val before = workspace.snapshot()
        val target = editor.state().layers.last().id
        val saved = before.recoverySavedRevision
        val pointer = File(folder,"active.json").readBytes()
        val originalCaptures = captures.get()
        val control = TransformController(editor, context, { callbacks.add(it) }, {})
        controller = control
        val owner = checkNotNull(control.begin(editor.selection().primary, TransformTarget.LAYER, 1,1,1,
            press = MovePressInput(MovePoint(.5,.5), autoSelect = true, picksAllowedAtDown = true)))
        waitFor("Auto Select draft did not become active") { control.snapshot()?.phase == TransformController.Phase.ACTIVE }
        check(editor.selection().primary == target && editor.currentRevision() > before.currentRevision)
        check(workspace.snapshot().currentRevision == editor.currentRevision() && !workspace.snapshot().projectDirty)
        check(workspace.snapshot().currentContentIdentity == before.currentContentIdentity)
        checkNotNull(workspace.scheduledAutosaveForTest()).get(10,TimeUnit.SECONDS)
        check(captures.get() == originalCaptures && workspace.snapshot().recoverySavedRevision == saved)
        check(File(folder,"active.json").readBytes().contentEquals(pointer))
        control.cancel(owner)
        waitFor("Cancel did not reach main acknowledgment") { control.snapshot()?.phase == TransformController.Phase.TERMINAL_WAITING }
        check(!owner.finished.isDone && !workspace.snapshot().projectDirty)
        check(captures.get() == originalCaptures)
        instrumentation.runOnMainSync { while (true) (callbacks.poll() ?: break).invoke() }
        check(owner.finished.get(10,TimeUnit.SECONDS).cancelled)
        waitFor("Selection revision was not saved after cancel/ack") { workspace.snapshot().recoverySavedRevision == editor.currentRevision() }
        val recovered = checkNotNull(WorkspaceRecoveryStore(folder).read())
        check(recovered.projectClean == true && !workspace.snapshot().projectDirty)
        val copy = File(context.cacheDir,"move-authority-cold-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            File(folder,"active.json").copyTo(File(copy,"active.json"))
            val checkpoint = WorkspaceRecoveryStore(folder).checkpoint(recovered)
            checkpoint.copyTo(File(copy,checkpoint.name))
            val cold = AndroidWorkspace(copy)
            try {
                waitFor("Cold recovery did not open") { !cold.snapshot().loading }
                check(cold.snapshot().recoveryError == null && !cold.snapshot().projectDirty)
                check(checkNotNull(cold.snapshot().editor).selection().primary == target)
            } finally { cold.closeAsync().get(10,TimeUnit.SECONDS) }
        } finally { copy.deleteRecursively() }
        currentEditorPresentationSmoke(instrumentation)
        return "PASS: Auto Select→cancel keeps content clean, updates workspace revision, defers autosave hook until main ack, saves selection and cold-recovers clean"
    } finally {
        controller?.close()
        instrumentation.runOnMainSync { while (true) (callbacks.poll() ?: break).invoke() }
        workspace.closeAsync().get(10,TimeUnit.SECONDS)
        folder.deleteRecursively()
    }
}

/** Exercise the exact delayed presentation closure after real replacement and close. */
private fun currentEditorPresentationSmoke(instrumentation: Instrumentation) {
    val folder = File(instrumentation.targetContext.cacheDir, "presentation-lifetime-${UUID.randomUUID()}")
    val workspace = AndroidWorkspace(folder)
    var closed = false
    try {
        val openingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (workspace.snapshot().loading && System.nanoTime() < openingDeadline) Thread.sleep(10)
        check(!workspace.snapshot().loading) { "Presentation workspace did not open" }
        check(workspace.installCandidate("presentation-first", DirtyDisposition.DISCARD) {
            NativeEditor(8, false).also { value ->
                val layer = value.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
                    put(byteArrayOf(32, 0, 0, -1)); rewind()
                })
                value.createMask(layer, true)
            }
        }.result.get(10, TimeUnit.SECONDS).installed)
        val editor = checkNotNull(workspace.snapshot().editor)
        val lease = checkNotNull(workspace.lease(editor))
        val presented = editor.selection()
        val beforeTarget = editor.state()
        editor.selectLayers(presented.roots, presented.primary, true)
        val maskSelection = editor.selection()
        check(maskSelection.mask && maskSelection.revision == presented.revision &&
            maskSelection.contentIdentity == presented.contentIdentity && maskSelection.epoch > presented.epoch)
        check(editor.state().copy(selectedLayerActions = null) == beforeTarget.copy(selectedLayerActions = null))
        check(withCurrentEditorSelection(lease, presented) { bound ->
            check(bound == maskSelection && bound !== presented && bound.mask)
        })
        check(withCurrentEditorSelection(lease, maskSelection) { bound -> check(bound === maskSelection) })
        editor.selectLayers(presented.roots, presented.primary, false)
        check(editor.state().copy(selectedLayerActions = null) == beforeTarget.copy(selectedLayerActions = null))
        check(withCurrentEditorSelection(lease, maskSelection) { bound ->
            check(!bound.mask && bound.revision == presented.revision && bound.epoch > maskSelection.epoch)
        })
        var publications = 0
        val delayedEffect = {
            withCurrentEditorPresentation(lease) { state, selection ->
                check(state.revision == selection.revision && state.document.activeLayerId == selection.primary)
                publications++
            }
        }
        instrumentation.runOnMainSync { check(delayedEffect()) }
        check(publications == 1)
        val marker = IllegalStateException("presentation callback failure")
        check(runCatching { withCurrentEditorPresentation(lease) { _, _ -> throw marker } }.exceptionOrNull() === marker)
        val frozenRead = workspace.withFrozenIo(checkNotNull(workspace.token(editor)), work = {
            check(workspace.snapshot().resourceGrowthFrozen)
            check(!lease.execute { error("Frozen workspace admitted a mutation") })
            check(withCurrentEditorPresentation(lease) { state, selection ->
                check(state.revision == selection.revision)
            })
            true
        }, done = {})
        check(frozenRead.get(10, TimeUnit.SECONDS))
        check(workspace.installCandidate("presentation-replacement", DirtyDisposition.DISCARD) {
            NativeEditor(8, false)
        }.result.get(10, TimeUnit.SECONDS).installed)
        check(runCatching { editor.selection() }.exceptionOrNull() is IllegalStateException)
        check(workspace.lease(editor) == null)
        instrumentation.runOnMainSync {
            check(!delayedEffect())
            check(!withCurrentEditor(lease) { error("Retired factory/update ran") })
            check(!withCurrentEditorSelection(lease, presented) { error("Retired selection bound") })
        }
        check(publications == 1)
        val current = checkNotNull(workspace.snapshot().editor)
        val currentLease = checkNotNull(workspace.lease(current))
        check(withCurrentEditorPresentation(currentLease) { state, selection ->
            check(state.revision == selection.revision)
        })
        workspace.closeAsync().get(10, TimeUnit.SECONDS)
        closed = true
        instrumentation.runOnMainSync {
            check(!withCurrentEditorPresentation(currentLease) { _, _ -> error("Closed editor published") })
            check(!withCurrentEditor(currentLease) { error("Closed factory/update ran") })
            check(!withCurrentEditorSelection(currentLease, presented) { error("Closed selection bound") })
            check(!delayedEffect())
        }
        check(publications == 1 && workspace.lease(current) == null)
    } finally {
        if (!closed) workspace.closeAsync().get(10, TimeUnit.SECONDS)
        folder.deleteRecursively()
    }
}
