package com.pojia.compositor.mobile

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Values are captured at submission; retained edits never keep a screen's mutable state. */
internal sealed interface EditorCommand {
    fun apply(editor: NativeEditor)
    data object AddLayer : EditorCommand { override fun apply(editor: NativeEditor) { editor.addLayer() } }
    data object AddGroup : EditorCommand { override fun apply(editor: NativeEditor) { editor.addGroup() } }
    data class Undo(val redo: Boolean) : EditorCommand { override fun apply(editor: NativeEditor) = editor.undo(redo) }
    data class SelectLayer(val id: Long) : EditorCommand { override fun apply(editor: NativeEditor) = editor.selectLayer(id) }
    class SelectLayers(roots: Collection<Long>, private val primary: Long, private val mask: Boolean = false) : EditorCommand {
        private val roots = roots.toList()
        override fun apply(editor: NativeEditor) = editor.selectLayers(roots, primary, mask)
    }
    data class RenameLayer(val id: Long, val name: String) : EditorCommand { override fun apply(editor: NativeEditor) = editor.renameLayer(id, name) }
    data class DeleteLayer(val id: Long) : EditorCommand { override fun apply(editor: NativeEditor) = editor.deleteLayer(id) }
    data class MoveLayer(val id: Long, val index: Int) : EditorCommand { override fun apply(editor: NativeEditor) = editor.moveLayer(id, index) }
    data class ReparentLayer(val id: Long, val parentId: Long) : EditorCommand { override fun apply(editor: NativeEditor) = editor.reparentLayer(id, parentId) }
    data class ClipLayer(val id: Long, val sourceId: Long) : EditorCommand { override fun apply(editor: NativeEditor) = editor.clipLayer(id, sourceId) }
    data class SetOpacity(val id: Long, val value: Double) : EditorCommand { override fun apply(editor: NativeEditor) = editor.setOpacity(id, value) }
    data class SetVisible(val id: Long, val value: Boolean) : EditorCommand { override fun apply(editor: NativeEditor) = editor.setVisible(id, value) }
    data class ToggleSelectedVisibility(val actions: SelectedLayerActions) : EditorCommand {
        override fun apply(editor: NativeEditor) = editor.toggleSelectedVisibility(actions)
    }
    data class ToggleSelectedClipping(val actions: SelectedLayerActions) : EditorCommand {
        override fun apply(editor: NativeEditor) = editor.toggleSelectedClipping(actions)
    }
    /** The dispatcher reads a fresh state on its worker after this nonmutating action. */
    data object RefreshSelectedLayerActions : EditorCommand { override fun apply(editor: NativeEditor) = Unit }
    data class FillSelection(val argb: Int, val layer: Long = 0L) : EditorCommand {
        override fun apply(editor: NativeEditor) { editor.fillPixelSelection(argb, layer) }
    }
    /** Applies a bounded Expand/Contract/Feather transform to the current selection. */
    data class ModifySelection(val operation: Int, val amount: Int) : EditorCommand {
        override fun apply(editor: NativeEditor) { editor.modifyPixelSelection(operation, amount) }
    }
    data object ClearSelection : EditorCommand {
        override fun apply(editor: NativeEditor) { editor.setPixelSelection(null) }
    }
    data class MagicWand(val x: Double, val y: Double, val radius: Int, val tolerance: Int,
        val contiguous: Boolean, val operation: Int, val expected: PixelSelection,
        val cancellation: MagicWandCancellation) : EditorCommand {
        override fun apply(editor: NativeEditor) {
            editor.applyMagicWand(x, y, radius, tolerance, contiguous, operation, expected, cancellation)
        }
    }
    data class CreateMask(val id: Long, val white: Boolean) : EditorCommand { override fun apply(editor: NativeEditor) = editor.createMask(id, white) }
    data class DeleteMask(val id: Long) : EditorCommand { override fun apply(editor: NativeEditor) = editor.deleteMask(id) }
    data class EnableMask(val id: Long, val enabled: Boolean) : EditorCommand { override fun apply(editor: NativeEditor) = editor.enableMask(id, enabled) }
    data class LinkMask(val id: Long, val linked: Boolean) : EditorCommand { override fun apply(editor: NativeEditor) = editor.linkMask(id, linked) }
    data class SetResolution(val value: Double) : EditorCommand { override fun apply(editor: NativeEditor) = editor.setResolution(value) }
    data class Crop(val width: Int, val height: Int, val offsetX: Double, val offsetY: Double) : EditorCommand {
        override fun apply(editor: NativeEditor) = editor.crop(width, height, offsetX, offsetY)
    }
    data class AlignLayers(val alignment: LayerAlignment, val roots: List<Long>) : EditorCommand {
        override fun apply(editor: NativeEditor) {
            val changes = LayerAlignmentGeometry.changes(editor.state(), roots, alignment, null, editor.canvasWidth, editor.canvasHeight,
                editor.pixelSelection().takeIf { it.active && it.width > 0 && it.height > 0 }?.let {
                    MoveBounds(it.x, it.y, it.x + it.width, it.y + it.height)
                })
            if (changes.isNotEmpty()) editor.setTransforms(changes)
        }
    }
    data class DistributeLayers(val distribution: LayerDistribution, val roots: List<Long>) : EditorCommand {
        override fun apply(editor: NativeEditor) {
            val changes = LayerAlignmentGeometry.changes(editor.state(), roots, null, distribution, 0, 0)
            if (changes.isNotEmpty()) editor.setTransforms(changes)
        }
    }
    data class CreateShape(val spec: ShapeSpec, val x: Double, val y: Double) : EditorCommand {
        override fun apply(editor: NativeEditor) { editor.createShape(spec, x, y) }
    }
    data class CreateGradient(val spec: GradientSpec) : EditorCommand {
        override fun apply(editor: NativeEditor) { editor.createGradient(spec) }
    }
    data class PutGuide(val value: GuideState) : EditorCommand { override fun apply(editor: NativeEditor) = editor.putGuide(value) }
    data class RemoveGuide(val uuid: String) : EditorCommand { override fun apply(editor: NativeEditor) = editor.removeGuide(uuid) }
    class SetGuides(values: List<GuideState>) : EditorCommand {
        private val values = values.toList()
        override fun apply(editor: NativeEditor) = editor.setGuides(values)
    }
}

internal class StrokeReservation internal constructor(internal val finished: CompletableFuture<Unit>)
internal data class SelectionSnapshot(val selection: PixelSelection, val regions: List<PixelSelectionRegion>)
internal data class CommandCompletion(val sequence: Long, val state: EditorState?, val error: String?,
    val selection: SelectionSnapshot? = null)

/** Ordinary edits belong to the document. GL stroke reservations fence both directions of admission. */
internal class RetainedCommandDispatcher(private val editor: NativeEditor) {
    private val main = Handler(Looper.getMainLooper())
    private class Observer(val callback: (EditorState?, String?, Boolean, SelectionSnapshot?) -> Unit, var sequence: Long = -1,
        var errorSequence: Long = -1)
    private val observers = mutableMapOf<Long, Observer>()
    private var nextObserver = 0L
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(128))
    @Volatile private var ownerThread: Thread? = null
    private var tail = CompletableFuture.completedFuture(Unit)
    private var nextCommand = 0L
    @Volatile internal var latestCompletion: CommandCompletion? = null
        private set
    private val errors = linkedMapOf<Long, String>()

    fun observe(observer: (EditorState?, String?, Boolean, SelectionSnapshot?) -> Unit): () -> Unit {
        val id = synchronized(observers) { (++nextObserver).also { observers[it] = Observer(observer) } }
        publish()
        return { synchronized(observers) { observers.remove(id) }; Unit }
    }
    private fun publish() {
        main.post {
            // Resolve the latest completion and current listeners at delivery, including a newly attached screen.
            val pending = editor.hasPendingEdits
            val completion = latestCompletion
            val pendingErrors = synchronized(errors) { errors.toList() }
            val consumed = mutableSetOf<Long>()
            val ids = synchronized(observers) { observers.keys.toList() }
            ids.forEach { id ->
                val delivery = synchronized(observers) {
                    observers[id]?.let { observer ->
                        val fresh = completion != null && completion.sequence > observer.sequence
                        if (fresh) observer.sequence = checkNotNull(completion).sequence
                        val failures = pendingErrors.filter { it.first > observer.errorSequence }
                        failures.lastOrNull()?.let { observer.errorSequence = it.first }
                        Triple(observer.callback, if (fresh) completion else null, failures)
                    }
                }
                delivery?.let { (callback, result, failures) ->
                    callback(result?.state, failures.firstOrNull()?.second, pending, result?.selection)
                    failures.drop(1).forEach { callback(null, it.second, pending, null) }
                    failures.forEach { consumed.add(it.first) }
                }
            }
            // Delivery to a current observer consumes an error. Success never erases an unread failure.
            synchronized(errors) { consumed.forEach { errors.remove(it) } }
        }
    }
    internal fun submit(command: EditorCommand, lease: WorkspaceLease?, captured: WorkspaceToken?): Boolean =
        submitAction(lease, captured) { command.apply(editor) }

    /** Compatibility for instrumentation fixtures; shipping call sites submit immutable EditorCommand values. */
    internal fun submitAction(lease: WorkspaceLease?, captured: WorkspaceToken?, action: () -> Unit): Boolean = editor.serialized {
        if (synchronized(errors) { errors.size } + editor.pendingEditCount >= 64) return@serialized false
        val ticket = editor.tryRegisterPendingEdit() ?: return@serialized false
        val strokes = editor.strokeReservations()
        val transform = editor.activeTransformCompletion()
        val completion = CompletableFuture<Unit>()
        val sequence = ++nextCommand
        try {
            worker.execute {
                ownerThread = Thread.currentThread()
                var state: EditorState? = null
                var error: String? = null
                var selection: SelectionSnapshot? = null
                try {
                    // No editor/workspace lock is held while the earlier GL stroke completes or cancels.
                    strokes.forEach { it.finished.get() }
                    transform?.get()
                    val work = {
                        try { action() }
                        catch (failure: Throwable) { error = failure.message ?: "Editor command failed" }
                        state = editor.state()
                        selection = SelectionSnapshot(editor.pixelSelection(), editor.pixelSelectionRegions().regions)
                    }
                    if (lease == null) editor.serialized(work)
                    else check(lease.executeAccepted(checkNotNull(captured), ticket, work)) { "Accepted command workspace changed" }
                } catch (failure: Throwable) { error = failure.message ?: "Editor command failed" }
                finally {
                    latestCompletion = CommandCompletion(sequence, state, error, selection)
                    error?.let { synchronized(errors) { errors[sequence] = it } }
                    ticket.settle()
                    publish()
                    completion.complete(Unit)
                }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            ticket.settle()
            return@serialized false
        }
        tail = completion
        publish()
        true
    }
    /** A barrier includes strokes already accepted, and runs without holding document or workspace locks. */
    internal fun awaitIdleForTest(): Future<*> = barrier()
    internal fun barrier(): CompletableFuture<Void> {
        return editor.serialized {
            val edits = editor.strokeReservations().map { it.finished } + listOfNotNull(editor.activeTransformCompletion())
            CompletableFuture.allOf(tail, *edits.toTypedArray())
        }
    }
    internal fun enqueueBarrierForTest(action: () -> Unit): Future<*> = editor.serialized {
        val completion = CompletableFuture<Unit>()
        worker.execute {
            ownerThread = Thread.currentThread()
            try { action(); completion.complete(Unit) }
            catch (failure: Throwable) { completion.completeExceptionally(failure) }
        }
        tail = completion
        completion
    }
    internal fun isOwnerThread(): Boolean = Thread.currentThread() === ownerThread
    internal fun shutdown() { worker.shutdown() }
}
