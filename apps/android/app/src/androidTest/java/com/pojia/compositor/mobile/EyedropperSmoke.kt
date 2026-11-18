package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal fun eyedropperFixture(editor: NativeEditor, opaqueRed: Boolean = false): Long {
    val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
    repeat(64) {
        repeat(64) { x ->
            pixels.put(when (x / 16) {
                0 -> if (opaqueRed) byteArrayOf(-1, 0, 0, -1) else byteArrayOf(64, 0, 0, -128)
                1 -> byteArrayOf(0, 0, -1, -1)
                2 -> byteArrayOf(0, 0, 0, 0)
                else -> byteArrayOf(0, -1, 0, -1)
            })
        }
    }
    pixels.rewind()
    return editor.importPixels(64, 64, pixels).also { editor.selectLayer(it) }
}

internal fun eyedropperSmoke(instrumentation: Instrumentation): String {
    val ledger = ImageIoAdmission.ledger
    val baseline = ledger.snapshot()
    val editor = NativeEditor(64, false)
    var sampler: NativeColorSampler? = null
    try {
        eyedropperFixture(editor)
        val before = editor.state()
        val captured = NativeColorSampler.capture(instrumentation.targetContext, editor); sampler = captured
        check(captured.sample(8.99, 8.99) == 0xff800000.toInt()) { "Premultiplied sample was not unpremultiplied/rounded to opaque sRGB8" }
        check(captured.sample(16.0, 8.0) == 0xff0000ff.toInt())
        check(captured.sample(15.999, 8.0) == 0xff800000.toInt()) { "Sampling did not floor document coordinates" }
        check(captured.sample(40.0, 8.0) == null && captured.sample(-.01, 8.0) == null && captured.sample(64.0, 8.0) == null)
        check(runCatching { captured.sample(Double.NaN, 8.0) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { editor.freezeResourceGrowth() }.isFailure) { "Retained color scene escaped growth freeze" }
        check(editor.state() == before) { "Readonly sampling changed history, revision or metadata" }
        editor.close()
        check(captured.sample(56.0, 8.0) == 0xff00ff00.toInt()) { "Immutable sampler did not survive document destruction" }
        captured.close(); sampler = null
        check(ledger.snapshot().reservedBytes == baseline.reservedBytes && ledger.snapshot().reservations == baseline.reservations)
        NativeEditor(64, false).use { refused ->
            ledger.reserve(ledger.snapshot().availableBytes, "eyedropper competing owner").use {
                check(runCatching { NativeColorSampler.capture(instrumentation.targetContext, refused) }.exceptionOrNull() is ResourceBudgetExceededException)
            }
            val available = ledger.snapshot().availableBytes
            check(available > NativeColorSampler.SCENE_ALLOWANCE)
            ledger.reserve(available - NativeColorSampler.SCENE_ALLOWANCE, "eyedropper sampler headroom refusal").use {
                val held = ledger.snapshot()
                check(runCatching { NativeColorSampler.capture(instrumentation.targetContext, refused) }.exceptionOrNull() is ResourceBudgetExceededException)
                check(ledger.snapshot().reservedBytes == held.reservedBytes && ledger.snapshot().reservations == held.reservations) {
                    "A sampler refusal after scene capture retained its scene lease"
                }
                refused.freezeResourceGrowth(); refused.releaseResourceGrowth()
            }
            refused.freezeResourceGrowth(); refused.releaseResourceGrowth()
        }
        // Hold the scheduling boundary: DOWN plus 10,000 absolute moves still use one pending callback.
        val queue = ArrayDeque<() -> Unit>()
        val calls = mutableListOf<Pair<Double, Double>>()
        val delivered = mutableListOf<Int?>()
        var closes = 0
        val input = EyedropperInput({ queue.addLast(it) }, { object : ColorSamplingSession {
            override fun sample(x: Double, y: Double): Int? { calls.add(x to y); return if (x == 10000.0) 0xff0000ff.toInt() else 0xff800000.toInt() }
            override fun close() { closes++ }
        } }, { _, color, _, _ -> delivered.add(color) }, { _, error -> throw error })
        val token = input.begin(0.0, 1.0, 0f, 1f)
        repeat(10000) { input.move(token, (it + 1).toDouble(), 1.0, it.toFloat(), 1f) }
        input.finish(token)
        check(queue.size == 1 && input.stats().maximumPendingRunnables == 1)
        queue.removeFirst().invoke()
        check(calls == listOf(0.0 to 1.0, 10000.0 to 1.0) && delivered.last() == 0xff0000ff.toInt() && closes == 1)
        check(input.stats().coalescedPoints == 9999L && input.stats().sampledPoints == 2L)
        val cancelled = input.begin(4.0, 1.0, 4f, 1f); input.cancel()
        queue.removeFirst().invoke()
        check(!input.isCurrent(cancelled) && closes == 1 && calls.size == 2)
        val closeFence = eyedropperCloseFenceSmoke(instrumentation)
        return "ABI12 retained immutable color sampler: floor coordinates, alpha unpremultiply opaque RGBA128, transparent/outside no color, invalid finite guard, no history/revision, growth guard, document-independent lifetime and shared-ledger refusal/release passed. Held mailbox10000 moves: DOWN+finalMOVE2 samples, coalesced9999, pendingGLmax1; UP has no sample. $closeFence No real allocator/JNI fault or physical performance claim."
    } finally {
        sampler?.close(); editor.close()
        check(ledger.snapshot().reservedBytes == baseline.reservedBytes)
    }
}

/** The close wrapper makes an incorrect lock order fail within one second instead of hanging instrumentation. */
private fun eyedropperCloseFenceSmoke(instrumentation: Instrumentation): String {
    val ledger = ImageIoAdmission.ledger
    val baseline = ledger.snapshot()
    val editor = NativeEditor(64, false)
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    val commandWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    val fence = java.util.concurrent.locks.ReentrantLock()
    val sampled = CountDownLatch(1); val commandEntered = CountDownLatch(1)
    val closeEntered = CountDownLatch(1); val cancelFinished = CountDownLatch(1)
    val closeFinished = CountDownLatch(1); val releaseCommand = CountDownLatch(1)
    val closeTimedOut = java.util.concurrent.atomic.AtomicBoolean()
    val actual = java.util.concurrent.atomic.AtomicReference<NativeColorSampler?>()
    val failures = CopyOnWriteArrayList<Exception>()
    var command: java.util.concurrent.Future<*>? = null
    val input = EyedropperInput({ worker.execute(it) }, {
        val delegate = NativeColorSampler.capture(instrumentation.targetContext, editor)
        actual.set(delegate)
        object : ColorSamplingSession {
            override fun sample(x: Double, y: Double) = delegate.sample(x, y)
            override fun close() {
                closeEntered.countDown()
                val acquired = fence.tryLock(2, TimeUnit.SECONDS)
                if (!acquired) closeTimedOut.set(true)
                check(acquired) { "Color close retained the input owner while waiting for command fence" }
                try { delegate.close() } finally { fence.unlock(); closeFinished.countDown() }
            }
        }
    }, { _, _, _, _ -> sampled.countDown() }, { _, error -> failures.add(error) })
    try {
        eyedropperFixture(editor)
        val token = input.begin(8.0, 8.0, 8f, 8f)
        check(sampled.await(10, TimeUnit.SECONDS))
        command = commandWorker.submit {
            editor.serialized {
                fence.lock()
                try {
                    commandEntered.countDown()
                    check(closeEntered.await(10, TimeUnit.SECONDS))
                    input.cancel()
                    cancelFinished.countDown()
                    check(releaseCommand.await(10, TimeUnit.SECONDS))
                } finally { fence.unlock() }
            }
        }
        check(commandEntered.await(10, TimeUnit.SECONDS))
        input.finish(token)
        check(cancelFinished.await(500, TimeUnit.MILLISECONDS)) { "Command-owned cancellation waited for the finishing owner's close fence" }
        check(closeFinished.count == 1L && !closeTimedOut.get()) { "Cancellation only returned after close timed out or released" }
        releaseCommand.countDown()
        check(closeFinished.await(10, TimeUnit.SECONDS)); command.get(10, TimeUnit.SECONDS)
        check(failures.isEmpty() && !closeTimedOut.get() && input.stats().samplerCreates == 1L && input.stats().samplerCloses == 1L)
        val live = ledger.snapshot()
        check(live.reservedBytes == Math.addExact(baseline.reservedBytes, editor.installedDocumentBytes()) &&
            live.reservations == baseline.reservations + 1)
        return "Actual native sampler+scene command-fence close/cancel race passed using a bounded close wrapper; owner detaches before scene release."
    } finally {
        releaseCommand.countDown()
        input.cancel()
        runCatching { command?.get(10, TimeUnit.SECONDS) }
        worker.shutdown(); commandWorker.shutdown()
        check(worker.awaitTermination(10, TimeUnit.SECONDS) && commandWorker.awaitTermination(10, TimeUnit.SECONDS))
        actual.get()?.close(); editor.close()
        check(ledger.snapshot().reservedBytes == baseline.reservedBytes && ledger.snapshot().reservations == baseline.reservations)
    }
}

/** Real Canvas MotionEvents exercise the GL owner, main mailbox, cancellation and sampled Brush pixels. */
internal fun eyedropperCanvasSmoke(instrumentation: Instrumentation): String {
    val ledger = ImageIoAdmission.ledger
    val baseline = ledger.snapshot()
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val editor = NativeEditor(64, false)
    val reference = NativeEditor(64, false)
    lateinit var view: CanvasView
    lateinit var overlay: FrameLayout
    var attached = false
    val failures = CopyOnWriteArrayList<String>()
    val colors = CopyOnWriteArrayList<Int>()
    fun checkDocumentOwners() = editor.serialized {
        val live = ledger.snapshot()
        val editorBytes = editor.installedDocumentBytes()
        val referenceBytes = reference.installedDocumentBytes()
        val expected = Math.addExact(baseline.reservedBytes, Math.addExact(editorBytes, referenceBytes))
        check(live.reservedBytes == expected && live.reservations == baseline.reservations + 2) {
            "Sampling retained resources beyond its two live documents: baseline=$baseline; live=$live; " +
                "expectedBytes=$expected; expectedReservations=${baseline.reservations + 2}; " +
                "editorBytes=$editorBytes; referenceBytes=$referenceBytes"
        }
    }
    fun drain() {
        val latch = CountDownLatch(1); view.queueEvent { latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(20); instrumentation.waitForIdleSync() }
        check(predicate()) { message }
    }
    try {
        val id = eyedropperFixture(editor); eyedropperFixture(reference)
        instrumentation.runOnMainSync {
            overlay = FrameLayout(activity)
            view = CanvasView(activity, editor, {}, { failures.add(it) }).apply {
                layerId = id; toolMode = CanvasToolMode.EYEDROPPER
                sampledColor = { color -> colors.add(color) }
            }
            overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attached = true
        }
        waitUntil("Eyedropper canvas did not attach") { view.width > 0 && view.height > 0 && view.holder.surface.isValid }
        waitUntil("Host workspace did not finish opening") { activity.retainedEditorForTest() != null && !activity.retainedWorkspaceForTest().snapshot().loading }
        activity.retainedWorkspaceForTest().flushAsync().get(10, TimeUnit.SECONDS)
        // The actual test canvas owns a separate document; the host only supplies its window.
        activity.retainedWorkspaceForTest().closeAsync().get(10, TimeUnit.SECONDS)
        drain()
        checkDocumentOwners()
        val scale = min(view.width / 64.0, view.height / 64.0) * .92
        val offsetX = (view.width - 64 * scale) / 2; val offsetY = (view.height - 64 * scale) / 2
        var start = SystemClock.uptimeMillis()
        fun pointer(action: Int, x: Double, y: Double = 8.0, meta: Int = 0) =
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, (offsetX + x * scale).toFloat(), (offsetY + y * scale).toFloat(), meta)
        fun dispatch(action: Int, x: Double, y: Double = 8.0, meta: Int = 0, await: Boolean = true) {
            val event = pointer(action, x, y, meta)
            try { instrumentation.runOnMainSync { check(view.dispatchTouchEvent(event)) } } finally { event.recycle() }
            if (await) drain()
        }
        var state = editor.state()
        dispatch(MotionEvent.ACTION_DOWN, 8.0)
        check(view.foregroundColor == 0xff800000.toInt() && view.sampleRing?.original == 0xff000000.toInt())
        instrumentation.runOnMainSync { view.showsSampleRing = false }; check(view.sampleRing == null)
        instrumentation.runOnMainSync { view.showsSampleRing = true }; check(view.sampleRing?.sampled == 0xff800000.toInt())
        dispatch(MotionEvent.ACTION_MOVE, 24.0, await = false)
        dispatch(MotionEvent.ACTION_UP, 56.0, await = false); drain()
        waitUntil("Final MOVE was lost at UP") { view.foregroundColor == 0xff0000ff.toInt() }
        check(view.sampleRing == null && view.eyedropperStats().samplerCreates == view.eyedropperStats().samplerCloses)
        check(editor.state() == state && failures.isEmpty())
        fun gpuStats(): GpuStats {
            var result: GpuStats? = null
            val ready = CountDownLatch(1)
            view.debugGpuStats { result = it; ready.countDown() }
            check(ready.await(10, TimeUnit.SECONDS)); return checkNotNull(result)
        }
        waitUntil("Actual GPU tile display did not warm") { gpuStats().uploads > 0 }
        val warmGpu = gpuStats()
        val statsBefore = view.eyedropperStats()
        val workloadStart = System.nanoTime()
        start = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            val down = pointer(MotionEvent.ACTION_DOWN, 8.0)
            try { check(view.dispatchTouchEvent(down)) } finally { down.recycle() }
            repeat(4096) { index ->
                val move = pointer(MotionEvent.ACTION_MOVE, if (index == 4095) 24.0 else (index % 64) + .25)
                try { check(view.dispatchTouchEvent(move)) } finally { move.recycle() }
            }
            val up = pointer(MotionEvent.ACTION_UP, 56.0)
            try { check(view.dispatchTouchEvent(up)) } finally { up.recycle() }
        }
        drain(); waitUntil("Mailbox workload lost last MOVE") { view.foregroundColor == 0xff0000ff.toInt() && !view.sampleSettlementPending }
        val workloadNanos = System.nanoTime() - workloadStart
        val statsAfter = view.eyedropperStats(); val gpuAfter = gpuStats()
        check(statsAfter.submittedPoints - statsBefore.submittedPoints == 4097L &&
            statsAfter.samplerCreates - statsBefore.samplerCreates == 1L &&
            statsAfter.samplerCloses - statsBefore.samplerCloses == 1L && statsAfter.maximumPendingRunnables == 1 &&
            view.eyedropperUiStats().second == 1)
        check(gpuAfter.uploads == warmGpu.uploads && gpuAfter.bytes == warmGpu.bytes && gpuAfter.surfaceBytes == warmGpu.surfaceBytes) {
            "Readonly sampling uploaded pixels or expanded GPU storage"
        }
        check(editor.state() == state)
        checkDocumentOwners()
        start = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, 40.0); dispatch(MotionEvent.ACTION_MOVE, -1.0); dispatch(MotionEvent.ACTION_UP, 8.0)
        check(view.foregroundColor == 0xff0000ff.toInt()) { "Transparent/outside/UP changed palette" }
        start = SystemClock.uptimeMillis(); dispatch(MotionEvent.ACTION_DOWN, 24.0)
        val twoPointers = MotionEvent.obtain(start, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
            Array(2) { index -> MotionEvent.PointerProperties().apply { this.id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } },
            Array(2) { index -> MotionEvent.PointerCoords().apply {
                x = (offsetX + (24 + index * 4) * scale).toFloat(); y = (offsetY + 8 * scale).toFloat(); pressure = 1f; size = 1f
            } }, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { instrumentation.runOnMainSync { check(view.dispatchTouchEvent(twoPointers)) } } finally { twoPointers.recycle() }
        check(!view.samplingInProgress && view.sampleRing == null &&
            view.eyedropperStats().samplerCreates == view.eyedropperStats().samplerCloses)
        dispatch(MotionEvent.ACTION_CANCEL, 24.0)
        // Hold native work through UP: the next Brush DOWN must refuse, then use the acknowledged foreground.
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.BRUSH }
        val quickEntered = CountDownLatch(1); val quickRelease = CountDownLatch(1)
        view.queueEvent { quickEntered.countDown(); check(quickRelease.await(10, TimeUnit.SECONDS)) }
        check(quickEntered.await(10, TimeUnit.SECONDS))
        val quickBefore = editor.state()
        try {
            start = SystemClock.uptimeMillis()
            dispatch(MotionEvent.ACTION_DOWN, 56.0, meta = KeyEvent.META_ALT_ON, await = false)
            dispatch(MotionEvent.ACTION_MOVE, 56.0, meta = KeyEvent.META_ALT_ON, await = false)
            dispatch(MotionEvent.ACTION_UP, 8.0, meta = KeyEvent.META_ALT_ON, await = false)
            check(view.sampleSettlementPending)
            instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.EYEDROPPER; view.toolMode = CanvasToolMode.BRUSH }
            dispatch(MotionEvent.ACTION_DOWN, 40.0, 40.0, await = false)
            check(!view.strokeInProgress && editor.state() == quickBefore && failures == listOf(PENDING_EDIT_MESSAGE))
            failures.clear()
        } finally { quickRelease.countDown() }
        drain(); waitUntil("Quick Alt terminal color was lost") {
            !view.sampleSettlementPending && view.foregroundColor == 0xff00ff00.toInt() && view.strokeSettings.green == 1f && view.strokeSettings.red == 0f
        }
        check(editor.state() == quickBefore)
        // Temporary Alt sampling keeps Brush selected; selected mask painting remains the existing black/white policy.
        editor.createMask(id, true)
        reference.createMask(reference.state().layers.single().id, true)
        state = editor.state()
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.BRUSH; view.editingMask = true }
        start = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, 56.0, meta = KeyEvent.META_ALT_ON)
        dispatch(MotionEvent.ACTION_UP, 8.0, meta = KeyEvent.META_ALT_ON)
        check(view.foregroundColor == 0xff00ff00.toInt() && view.toolMode == CanvasToolMode.BRUSH &&
            !view.strokeSettings.maskPaintWhite && editor.state() == state && state.layers.single().hasMask)
        instrumentation.runOnMainSync { view.editingMask = false; view.strokeSettings = view.strokeSettings.copy(diameter = 8f, hardness = 1f, opacity = 1f) }
        // Real configured Brush consumes the opaque sampled foreground on transparent source pixels.
        start = SystemClock.uptimeMillis()
        val brushDown = pointer(MotionEvent.ACTION_DOWN, 40.0, 40.0)
        val brushUp = pointer(MotionEvent.ACTION_UP, 40.0, 40.0)
        val x = (brushDown.x - offsetX) / scale; val y = (brushDown.y - offsetY) / scale
        try { instrumentation.runOnMainSync { check(view.dispatchTouchEvent(brushDown)); check(view.dispatchTouchEvent(brushUp)) } }
        finally { brushDown.recycle(); brushUp.recycle() }
        drain()
        val refId = reference.state().layers.single().id
        reference.startStroke(refId, view.strokeSettings, false)
        reference.samples(doubleArrayOf(x, y, 1.0, 0.0, x, y, 1.0, .001)); reference.endStroke(false)
        fun pixels(document: NativeEditor): ByteArray {
            val buffer = ByteBuffer.allocateDirect(64 * 64 * 4)
            document.draw(64, 64, 0.0, 0.0, 64.0, 64.0, buffer)
            return ByteArray(buffer.capacity()).also { buffer.rewind(); buffer.get(it) }
        }
        val painted = pixels(editor)
        check((painted[(40 * 64 + 40) * 4 + 1].toInt() and 255) == 255 && (painted[(40 * 64 + 40) * 4 + 3].toInt() and 255) == 255)
        check(painted.contentEquals(pixels(reference))) { "Sampled Brush did not produce independent expected pixels" }
        editor.undo(false); check(editor.state().layers == state.layers)
        check(pixels(editor)[(40 * 64 + 40) * 4 + 3] == 0.toByte())
        editor.undo(true); check(pixels(editor).contentEquals(painted))
        // A held GL queue cannot make retire depend on a cleanup callback that detach may discard.
        instrumentation.runOnMainSync { view.toolMode = CanvasToolMode.EYEDROPPER }
        start = SystemClock.uptimeMillis(); dispatch(MotionEvent.ACTION_DOWN, 8.0)
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        view.queueEvent { held.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        check(held.await(10, TimeUnit.SECONDS))
        val colorCount = colors.size
        dispatch(MotionEvent.ACTION_MOVE, 24.0, await = false)
        try {
            instrumentation.runOnMainSync { view.retire() }
            checkDocumentOwners()
            check(view.eyedropperStats().samplerCreates == view.eyedropperStats().samplerCloses)
        } finally { release.countDown() }
        drain()
        instrumentation.runOnMainSync { overlay.removeView(view) }
        instrumentation.waitForIdleSync()
        check(colors.size == colorCount && failures.isEmpty()) { "Retired sample changed palette" }
        return "Real Canvas DOWN/MOVE/UP(no resample), ring original/current/toggle, transparent/outside, Alt Brush+mask foreground, heldGL quickUP→nextBrush refusal→terminalcolor acknowledgment, sampled green→real Brush pixels/one undo/redo and heldGL retire owner release/stale palette passed. Workload4096MOVE+DOWN submitted4097 sampled=${statsAfter.sampledPoints - statsBefore.sampledPoints} coalesced=${statsAfter.coalescedPoints - statsBefore.coalescedPoints} GLpendingMax1 UIpendingMax1 samplerCreateClose1 uploadDelta0 GPUstorageDelta0 observedElapsedNanos=$workloadNanos. No physical performance FPS or full picker parity claim."
    } finally {
        instrumentation.runOnMainSync { if (attached) { view.retire(); overlay.removeView(view) }; activity.finish() }
        editor.close(); reference.close(); instrumentation.waitForIdleSync()
        activity.retainedWorkspaceForTest().closeAsync().get(10, TimeUnit.SECONDS)
        check(ledger.snapshot().reservedBytes == baseline.reservedBytes && ledger.snapshot().reservations == baseline.reservations)
    }
}
