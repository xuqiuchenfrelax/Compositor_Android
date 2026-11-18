package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Real independently queued canvases fence retained edits, including native-finish and close windows. */
internal fun retainedStrokeFenceSmoke(instrumentation: Instrumentation, activity: MainActivity, root: File): String {
    val releases = mutableListOf<CountDownLatch>()
    val workspaces = mutableListOf<AndroidWorkspace>()
    val attached = mutableListOf<Pair<CanvasView, FrameLayout>>()
    val settings = StrokeSettings(diameter = 8f, hardness = .8f, red = .18f, green = .45f, blue = .95f)
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val buffer = ByteBuffer.allocateDirect(64 * 64 * 4)
        check(editor.draw(64, 64, 0.0, 0.0, 64.0, 64.0, buffer))
        return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
    }
    fun drain(view: CanvasView) {
        val reached = CountDownLatch(1)
        view.queueEvent { reached.countDown() }
        check(reached.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    fun idle(editor: NativeEditor) {
        editor.commandDispatcher.awaitIdleForTest().get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }
    fun holdGl(view: CanvasView): CountDownLatch {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        releases += release
        view.queueEvent { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        check(entered.await(10, TimeUnit.SECONDS))
        return release
    }
    fun holdOwner(editor: NativeEditor): CountDownLatch {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        releases += release
        editor.commandDispatcher.enqueueBarrierForTest { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        check(entered.await(10, TimeUnit.SECONDS))
        return release
    }
    fun workspace(name: String): AndroidWorkspace {
        val workspace = AndroidWorkspace(File(root, name)); workspaces += workspace
        waitUntil("$name workspace did not open") { !workspace.snapshot().loading }
        check(workspace.installCandidate(name, DirtyDisposition.DISCARD) {
            NativeEditor(64, addDefaultLayers = false).also { it.addLayer() }
        }.result.get(10, TimeUnit.SECONDS).installed)
        return workspace
    }
    fun attach(editor: NativeEditor, workspace: AndroidWorkspace, states: CopyOnWriteArrayList<EditorState>,
        errors: CopyOnWriteArrayList<String>): CanvasView {
        lateinit var view: CanvasView; lateinit var overlay: FrameLayout
        main {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, { states += it }, { errors += it }, checkNotNull(workspace.lease(editor)))
            view.layerId = editor.state().document.activeLayerId; view.strokeSettings = settings
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        attached += view to overlay
        waitUntil("$view canvas did not attach") { view.holder.surface.isValid && view.width > 0 && view.height > 0 }
        drain(view)
        return view
    }
    fun retire(view: CanvasView) {
        val overlay = attached.first { it.first === view }.second
        main { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) }
    }
    class Pointer(private val view: CanvasView) {
        private val scale = min(view.width / 64.0, view.height / 64.0) * .92
        private val offsetX = (view.width - 64 * scale) / 2
        private val offsetY = (view.height - 64 * scale) / 2
        private var down = SystemClock.uptimeMillis()
        fun reset() { down = SystemClock.uptimeMillis() }
        fun send(action: Int, offset: Long, x: Double, y: Double, pressure: Float = .8f): DoubleArray {
            val event = MotionEvent.obtain(down, down + offset, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    this.x = (offsetX + x * scale).toFloat(); this.y = (offsetY + y * scale).toFloat()
                    this.pressure = pressure; size = 1f
                }), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            // The native reference reads actual platform floats, not the intended construction coordinates.
            val sample = doubleArrayOf((event.x.toDouble() - offsetX) / scale,
                (event.y.toDouble() - offsetY) / scale, event.pressure.toDouble(), (event.eventTime - down) / 1000.0)
            try { main { check(view.dispatchTouchEvent(event)) } } finally { event.recycle() }
            return sample
        }
    }
    try {
        val strokeWorkspace = workspace("stroke-fence")
        val editor = checkNotNull(strokeWorkspace.snapshot().editor)
        val states = CopyOnWriteArrayList<EditorState>(); val errors = CopyOnWriteArrayList<String>()
        val oldStates = CopyOnWriteArrayList<EditorState>(); val oldErrors = CopyOnWriteArrayList<String>()
        val old = attach(editor, strokeWorkspace, oldStates, oldErrors)
        val fresh = attach(editor, strokeWorkspace, states, errors)
        val oldPointer = Pointer(old); val pointer = Pointer(fresh)
        val baseline = editor.state(); val blank = pixels(editor)
        val reference = NativeEditor(64, addDefaultLayers = false)
        try {
            reference.addLayer(); val referenceId = reference.state().layers.single().id
            val oldHeld = holdGl(old)
            oldPointer.send(MotionEvent.ACTION_DOWN, 0, 12.0, 12.0)
            check(editor.strokeReservations().size == 1 && editor.currentRevision() == baseline.revision)
            val start = pointer.send(MotionEvent.ACTION_DOWN, 0, 10.0, 10.0); drain(fresh)
            reference.startStroke(referenceId, settings); reference.samples(start)
            check(editor.strokeReservations().size == 2 && pixels(editor).contentEquals(pixels(reference)))
            check(!pixels(editor).contentEquals(blank))
            val oldCallbackCount = oldStates.size
            // Old cancel remains queued, and immediate retirement must cancel only its stale reservation.
            main { old.cancelStroke(); old.retire() }
            check(editor.strokeReservations().size == 1 && pixels(editor).contentEquals(pixels(reference)))
            oldHeld.countDown(); drain(old)
            check(oldStates.size == oldCallbackCount && oldErrors.isEmpty())
            val moved = pointer.send(MotionEvent.ACTION_MOVE, 10, 40.0, 10.0); drain(fresh); reference.samples(moved)
            val ended = pointer.send(MotionEvent.ACTION_UP, 20, 40.0, 24.0); drain(fresh)
            reference.samples(ended); reference.endStroke(false); idle(editor)
            check(pixels(editor).contentEquals(pixels(reference)) && editor.currentRevision() == baseline.revision + 1)
            check(editor.strokeReservations().isEmpty() && editor.pendingEditCount == 0 && errors.isEmpty())

            // Generic work accepted during a live stroke cannot run until UP has reached native finish.
            val committed = editor.state(); val committedPixels = pixels(editor)
            pointer.reset()
            val secondStart = pointer.send(MotionEvent.ACTION_DOWN, 0, 20.0, 36.0); drain(fresh)
            reference.startStroke(referenceId, settings); reference.samples(secondStart)
            val finishHeld = holdGl(fresh)
            main { check(fresh.command(EditorCommand.AddLayer)); check(fresh.command(EditorCommand.SelectLayer(baseline.layers.single().id))) }
            check(editor.pendingEditCount == 2 && editor.state().layers == committed.layers && editor.currentRevision() == committed.revision)
            val secondEnd = pointer.send(MotionEvent.ACTION_UP, 20, 44.0, 36.0)
            check(fresh.strokeInProgress && editor.strokeReservations().size == 1 && editor.pendingEditCount == 2)
            check(editor.currentRevision() == committed.revision && pixels(editor).contentEquals(pixels(reference)))
            finishHeld.countDown(); drain(fresh); idle(editor)
            reference.samples(secondEnd); reference.endStroke(false)
            check(editor.state().layers.size == committed.layers.size + 1 &&
                editor.state().document.activeLayerId == baseline.layers.single().id)
            check(pixels(editor).contentEquals(pixels(reference)) && !pixels(editor).contentEquals(committedPixels))
            check(editor.strokeReservations().isEmpty() && editor.pendingEditCount == 0 && errors.isEmpty())

            val beforeUndo = editor.state(); val beforeUndoPixels = pixels(editor)
            pointer.reset(); pointer.send(MotionEvent.ACTION_DOWN, 0, 12.0, 56.0); drain(fresh)
            val undoHeld = holdGl(fresh)
            main { check(fresh.command(EditorCommand.Undo(false))) }
            pointer.send(MotionEvent.ACTION_UP, 10, 24.0, 56.0)
            check(editor.pendingEditCount == 1 && editor.currentRevision() == beforeUndo.revision && fresh.strokeInProgress)
            undoHeld.countDown(); drain(fresh); idle(editor)
            check(editor.state().layers == beforeUndo.layers && editor.state().document == beforeUndo.document && editor.state().redo)
            check(pixels(editor).contentEquals(beforeUndoPixels) && editor.strokeReservations().isEmpty() && errors.isEmpty())

            // A pending retained command rejects DOWN; releasing it permits the next real stroke.
            val ownerHeld = holdOwner(editor); val beforePending = editor.state(); val beforePendingPixels = pixels(editor)
            main { check(fresh.command(EditorCommand.RenameLayer(baseline.layers.single().id, "Pending admission"))) }
            pointer.reset(); pointer.send(MotionEvent.ACTION_DOWN, 0, 28.0, 50.0); drain(fresh)
            pointer.send(MotionEvent.ACTION_UP, 10, 36.0, 50.0); drain(fresh)
            check(editor.strokeReservations().isEmpty() && editor.pendingEditCount == 1 && editor.state() == beforePending)
            check(pixels(editor).contentEquals(beforePendingPixels))
            check(errors == listOf(activity.getString(R.string.notice_pending_edit))) {
                "Pending command DOWN expected exactly one localized pending notice; observed=" + errors.toList()
            }
            errors.clear()
            ownerHeld.countDown(); idle(editor)
            pointer.reset()
            val admittedStart = pointer.send(MotionEvent.ACTION_DOWN, 0, 28.0, 50.0); drain(fresh)
            reference.startStroke(referenceId, settings); reference.samples(admittedStart)
            val admittedEnd = pointer.send(MotionEvent.ACTION_UP, 10, 36.0, 50.0); drain(fresh); idle(editor)
            reference.samples(admittedEnd); reference.endStroke(false)
            check(pixels(editor).contentEquals(pixels(reference)) && !pixels(editor).contentEquals(beforePendingPixels))
            check(errors.isEmpty()) { "Admitted stroke produced an unexpected notice: ${errors.toList()}" }

            // Even after pending tickets settle, an old UI layer ID must never paint an obsolete target.
            val originalLayerId = baseline.layers.single().id
            val otherId = editor.state().layers.single { it.id != originalLayerId }.id
            main { check(fresh.command(EditorCommand.SelectLayer(otherId))) }; idle(editor)
            val beforeStale = editor.state(); val stalePixels = pixels(editor)
            check(beforeStale.document.activeLayerId == otherId && fresh.layerId == otherId)
            // The retained observer refreshes the target; inject the obsolete UI value explicitly.
            main { fresh.layerId = originalLayerId }
            check(fresh.layerId != beforeStale.document.activeLayerId)
            pointer.reset(); pointer.send(MotionEvent.ACTION_DOWN, 0, 16.0, 16.0); drain(fresh)
            pointer.send(MotionEvent.ACTION_UP, 10, 24.0, 16.0); drain(fresh)
            check(editor.state() == beforeStale && pixels(editor).contentEquals(stalePixels) && editor.strokeReservations().isEmpty())
            check(errors == listOf(activity.getString(R.string.notice_pending_edit))) {
                "Stale layer DOWN expected exactly one localized pending notice; observed=" + errors.toList()
            }
            errors.clear()
            main { check(fresh.command(EditorCommand.SelectLayer(baseline.layers.single().id))); fresh.layerId = baseline.layers.single().id }; idle(editor)

            val beforeMalformed = editor.state(); val malformedPixels = pixels(editor)
            pointer.reset(); pointer.send(MotionEvent.ACTION_DOWN, 0, 32.0, 32.0); drain(fresh)
            // Device pressure NaN now falls back to 1, matching Mac. Keep this
            // abort/fence fixture malformed through geometry, which stays invalid.
            val malformed = pointer.send(MotionEvent.ACTION_MOVE, 10, Double.NaN, 32.0)
            check(malformed[0].isNaN()) { "Platform changed the malformed-coordinate fixture" }
            drain(fresh); idle(editor)
            check(errors.size == 1 && errors.single() == activity.getString(R.string.notice_editor_command_failed)) {
                "Malformed stroke expected one localized command failure=" +
                    activity.getString(R.string.notice_editor_command_failed) + "; observed=" + errors.toList()
            }
            check(editor.strokeReservations().isEmpty() && editor.pendingEditCount == 0 && !fresh.strokeInProgress)
            check(editor.state().layers == beforeMalformed.layers && editor.currentRevision() == beforeMalformed.revision &&
                pixels(editor).contentEquals(malformedPixels))
            pointer.send(MotionEvent.ACTION_UP, 20, 36.0, 32.0); drain(fresh)
            check(errors.size == 1 && pixels(editor).contentEquals(malformedPixels))
        } finally { reference.close() }
        retire(old); retire(fresh)

        // Neither flush nor direct close needs Activity destruction or a running GL queue to release strokes.
        for (close in listOf(false, true)) {
            val name = if (close) "stroke-close" else "stroke-flush"
            val workspace = workspace(name); val editor = checkNotNull(workspace.snapshot().editor)
            val states = CopyOnWriteArrayList<EditorState>(); val errors = CopyOnWriteArrayList<String>()
            val view = attach(editor, workspace, states, errors); val pointer = Pointer(view)
            val baseline = editor.state(); val blank = pixels(editor)
            pointer.send(MotionEvent.ACTION_DOWN, 0, 12.0, 12.0); drain(view)
            check(!pixels(editor).contentEquals(blank) && editor.strokeReservations().size == 1)
            val glHeld = holdGl(view)
            main { check(view.command(EditorCommand.SetOpacity(baseline.layers.single().id, .5))) }
            check(editor.pendingEditCount == 1 && editor.currentRevision() == baseline.revision)
            val operation: Future<*> = if (close) workspace.closeAsync() else workspace.flushAsync()
            operation.get(10, TimeUnit.SECONDS)
            check(editor.pendingEditCount == 0 && editor.strokeReservations().isEmpty())
            val store = WorkspaceRecoveryStore(File(root, name)); val target = checkNotNull(store.read())
            NativeEditor.load(store.checkpoint(target)).use { recovered ->
                check(recovered.state().layers.single().opacity == .5 && recovered.currentRevision() == baseline.revision + 1)
                check(!recovered.state().undo && !recovered.state().redo && pixels(recovered).contentEquals(blank))
            }
            if (!close) check(editor.state().layers.single().opacity == .5 && pixels(editor).contentEquals(blank))
            glHeld.countDown(); drain(view); retire(view)
            check(errors.isEmpty())
        }

        // Queue admission is bounded at 64, while a close barrier drains those accepted commands in order.
        val boundedWorkspace = workspace("bounded-close"); val bounded = checkNotNull(boundedWorkspace.snapshot().editor)
        val boundedStates = CopyOnWriteArrayList<EditorState>(); val boundedErrors = CopyOnWriteArrayList<String>()
        val boundedView = attach(bounded, boundedWorkspace, boundedStates, boundedErrors)
        val boundedBefore = bounded.state(); val boundedHeld = holdOwner(bounded)
        main {
            repeat(64) { index -> check(boundedView.command(EditorCommand.RenameLayer(boundedBefore.layers.single().id, "Bounded $index"))) }
            check(!boundedView.command(EditorCommand.AddLayer))
        }
        check(bounded.pendingEditCount == 64 && bounded.state() == boundedBefore)
        val idleBarrier = bounded.commandDispatcher.awaitIdleForTest()
        val closing = boundedWorkspace.closeAsync()
        check(!closing.cancel(false) && !closing.cancel(true) && !closing.isCancelled)
        main { check(!boundedView.command(EditorCommand.AddLayer)) }
        check(!closing.isDone && !idleBarrier.isDone && bounded.pendingEditCount == 64)
        retire(boundedView); boundedHeld.countDown(); closing.get(10, TimeUnit.SECONDS); idleBarrier.get(10, TimeUnit.SECONDS)
        check(bounded.pendingEditCount == 0 && boundedErrors.toList() == listOf(activity.getString(R.string.notice_pending_edit)))
        val boundedStore = WorkspaceRecoveryStore(File(root, "bounded-close"))
        NativeEditor.load(boundedStore.checkpoint(checkNotNull(boundedStore.read()))).use { recovered ->
            check(recovered.currentRevision() == boundedBefore.revision + 64 && recovered.state().layers.single().name == "Bounded 63")
            check(pixels(recovered).all { it == 0.toByte() })
        }

        // An unread failure survives detachment and a later success, then clears after one live delivery.
        val replayWorkspace = workspace("observer-replay"); val replay = checkNotNull(replayWorkspace.snapshot().editor)
        val retiredStates = CopyOnWriteArrayList<EditorState>(); val retiredErrors = CopyOnWriteArrayList<String>()
        val retiredView = attach(replay, replayWorkspace, retiredStates, retiredErrors)
        val replayBefore = replay.state(); val replayHeld = holdOwner(replay)
        val beforeDetach = retiredStates.size
        main {
            check(retiredView.command { error("Unread retained failure") })
            check(retiredView.command(EditorCommand.RenameLayer(replayBefore.layers.single().id, "Latest replay")))
        }
        retire(retiredView); replayHeld.countDown(); idle(replay)
        check(retiredStates.size == beforeDetach && retiredErrors.isEmpty() && replay.pendingEditCount == 0)
        val replayStates = CopyOnWriteArrayList<EditorState>(); val replayErrors = CopyOnWriteArrayList<String>()
        val replayView = attach(replay, replayWorkspace, replayStates, replayErrors)
        waitUntil("New observer did not replay latest state and unread failure once") { replayStates.isNotEmpty() && replayErrors.size == 1 }
        check(replayStates.all { it == replay.state() } &&
            replayErrors.single() == activity.getString(R.string.notice_editor_command_failed))
        check(replay.state().revision == replayBefore.revision + 1 && replay.state().layers.single().name == "Latest replay")
        retire(replayView)
        val clearedStates = CopyOnWriteArrayList<EditorState>(); val clearedErrors = CopyOnWriteArrayList<String>()
        val clearedView = attach(replay, replayWorkspace, clearedStates, clearedErrors)
        check(clearedStates.isNotEmpty() && clearedStates.all { it == replay.state() } && clearedErrors.isEmpty())
        retire(clearedView)

        // Close and ordinary typed acceptance race without a lock seam: either outcome has an exact checkpoint oracle.
        val raceWorkspace = workspace("accept-close"); val race = checkNotNull(raceWorkspace.snapshot().editor)
        val raceView = attach(race, raceWorkspace, CopyOnWriteArrayList(), CopyOnWriteArrayList())
        val raceBefore = race.state(); val raceHeld = holdOwner(race); val startClose = CountDownLatch(1)
        val closeSubmitted = CountDownLatch(1); var raceClose: Future<*>? = null
        val closer = Thread {
            check(startClose.await(10, TimeUnit.SECONDS)); raceClose = raceWorkspace.closeAsync(); closeSubmitted.countDown()
        }.apply { start() }
        var accepted = false
        main { startClose.countDown(); accepted = raceView.command(EditorCommand.RenameLayer(raceBefore.layers.single().id, "Accepted before close")) }
        check(closeSubmitted.await(10, TimeUnit.SECONDS)); closer.join(10_000); check(!closer.isAlive)
        main { check(!raceView.command(EditorCommand.AddLayer)) }
        check(race.pendingEditCount == (if (accepted) 1 else 0))
        check(!checkNotNull(raceClose).isDone)
        check(!checkNotNull(raceClose).cancel(true) && !checkNotNull(raceClose).isCancelled)
        retire(raceView); raceHeld.countDown(); checkNotNull(raceClose).get(10, TimeUnit.SECONDS)
        val raceStore = WorkspaceRecoveryStore(File(root, "accept-close"))
        NativeEditor.load(raceStore.checkpoint(checkNotNull(raceStore.read()))).use { recovered ->
            check(recovered.currentRevision() == raceBefore.revision + (if (accepted) 1 else 0))
            check(recovered.state().layers.single().name == (if (accepted) "Accepted before close" else raceBefore.layers.single().name))
            check(pixels(recovered).all { it == 0.toByte() })
        }
        return "Real old/new Canvas stroke ownership stayed isolated; independent native pixels matched after old queued cancel/retirement and native UP finish. Generic/stale-target DOWN rejected, next DOWN painted, NaN aborted once with tokens released. Held-GL flush/direct close cancelled reservations and saved accepted edits; 64-command admission and acceptance/close race drained exact checkpoints. New observers replayed latest state plus unread errors once, without rollback or consumed-error replay."
    } finally {
        releases.forEach { it.countDown() }
        attached.forEach { (view, overlay) -> main { view.retire(); (overlay.parent as? ViewGroup)?.removeView(overlay) } }
        workspaces.forEach { it.closeAsync().get(10, TimeUnit.SECONDS) }
    }
}
