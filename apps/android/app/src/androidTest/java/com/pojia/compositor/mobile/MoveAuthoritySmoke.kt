package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.nio.ByteBuffer

/** Real document/JNI publication, separate claims, authoritative receipts and delivery recovery. */
internal fun moveAuthoritySmoke(instrumentation: Instrumentation): String {
    val context = instrumentation.targetContext
    val ledger = ImageIoAdmission.ledger
    val initial = ledger.snapshot()
    val editor = NativeEditor(16, false)
    var identity = 0L
    var activeOwner: TransformController.Owner? = null
    fun owner(): TransformController.Owner {
        val selection = editor.selection()
        return TransformController.Owner(++identity, 1, 1, identity, false,
            selection.primary, TransformTarget.LAYER).also {
            check(editor.reserveTransformControl(it) {})
            activeOwner = it
        }
    }
    fun retire() {
        activeOwner?.let { editor.retireTransformControl(it); it.finished.complete(TransformController.Terminal(false, true)) }
        activeOwner = null
    }
    fun fixture(red: Byte): Long = editor.importPixels(1, 1,
        ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(red, 0, 0, -1)); rewind() })
    fun begin(input: MovePressInput = MovePressInput(MovePoint(.5, .5)),
        transport: MovePressTransport = DirectMovePressTransport): NativeTransformDraft? =
        editor.beginMovePress(context, owner(), input, transport)
    try {
        singleLayerSelectionCompatibilitySmoke()
        val a = fixture(64); val b = fixture(96)
        editor.selectLayer(a)
        val baseline = editor.state()
        val content = editor.currentContentIdentity()
        val selection = editor.selection()
        val notifications = mutableListOf<Long>()
        editor.committed = { notifications.add(it) }
        val picked = checkNotNull(begin(MovePressInput(MovePoint(.5,.5), autoSelect = true, picksAllowedAtDown = true)))
        check(picked.info().primaryLayerId == b && editor.selection().primary == b)
        check(editor.selection().epoch == selection.epoch + 1)
        check(editor.currentContentIdentity() == content && notifications.single() == editor.currentRevision())
        val pickedRevision = editor.currentRevision()
        picked.close(); retire()
        check(editor.currentRevision() == pickedRevision && editor.currentContentIdentity() == content)
        check(editor.state().undo == baseline.undo && editor.state().redo == baseline.redo)

        // Full multi-member preview/cancel/one-history commit, with ancestor/child deduplication.
        editor.selectLayers(listOf(a,b), a)
        val selected = editor.selection()
        val beforeGroup = editor.state()
        val group = checkNotNull(begin())
        check(group.info().target == TransformTarget.GROUP && group.info().rootCount == 2 && group.info().memberCount == 2)
        check(group.members().map { it.layerId }.toSet() == setOf(a,b))
        group.update(group.info().box.copy(x = 2.0), group.info().sampling)
        val scene = group.captureScene()
        val pixels = ByteBuffer.allocateDirect(16*16*4)
        scene.draw(16,16,Viewport(0.0,0.0,16.0,16.0),pixels)
        check(pixels.get((0*16+2)*4+3) == (-1).toByte() && pixels.get(3) == 0.toByte())
        group.close(); retire()
        check(editor.state().copy(selectedLayerActions = null) == beforeGroup.copy(selectedLayerActions = null) && editor.selection().roots == selected.roots)
        scene.close()
        val commit = checkNotNull(begin())
        commit.update(commit.info().box.copy(x = 3.0), commit.info().sampling)
        val committed = commit.commit(); retire()
        check(committed.changed && editor.currentRevision() == beforeGroup.revision + 1)
        check(editor.state().layers.filter { it.id in setOf(a,b) }.all { it.transform.x == 3.0 })
        editor.undo(false)
        check(editor.state().layers.filter { it.id in setOf(a,b) }.all { it.transform.x == 0.0 })
        check(editor.selection().roots.size == 1)

        val folder = editor.addGroup()
        editor.reparentLayer(a, folder); editor.reparentLayer(b, folder)
        editor.selectLayers(listOf(folder,a), folder)
        val nested = checkNotNull(begin())
        check(nested.info().target == TransformTarget.GROUP && nested.info().rootCount == 2 && nested.info().memberCount == 2)
        nested.close(); retire()
        editor.selectLayer(folder)
        val folderDraft = checkNotNull(begin())
        check(folderDraft.info().rootCount == 1 && folderDraft.info().memberCount == 2)
        folderDraft.close(); retire()
        val empty = editor.addGroup()
        editor.selectLayer(empty)
        check(begin() == null && !editor.hasActiveTransformDraft)
        retire()

        // A matching existing token is reused; neither a second token nor a second retained claim appears.
        editor.selectLayer(a)
        val reused = checkNotNull(begin())
        val token = reused.token
        val active = checkNotNull(activeOwner)
        val held = ledger.snapshot()
        val same = editor.beginMovePress(context, active,
            MovePressInput(MovePoint(.5,.5), autoSelect = true, command = true, shift = true, picksAllowedAtDown = true))
        check(same === reused && same.token == token && editor.selection().roots == listOf(a))
        check(ledger.snapshot().reservedBytes == held.reservedBytes && ledger.snapshot().reservations == held.reservations)
        reused.close(); retire()

        // Reuse, retirement and a successful native ack with lost return form one recovery path.
        // Its historical REUSE receipt must never re-adopt the now-cancelled existing token.
        val beforeRetiredReuse = ledger.snapshot()
        val retiredReuse = checkNotNull(begin())
        val reuseOwner = checkNotNull(activeOwner).also { it.chain.cancelled = true }
        var reuseAcks = 0
        val reuseLostAck = object : MovePressTransport {
            override fun acknowledge(document: Long, instance: Long, operation: Long): Int {
                val status = NativeMovePressBridge.acknowledge(document, instance, operation)
                if (++reuseAcks == 1) throw IllegalStateException("real reuse ack succeeded, delivery lost after cancellation")
                return status
            }
        }
        check(editor.beginMovePress(context, reuseOwner, MovePressInput(MovePoint(.5,.5)), reuseLostAck) == null)
        check(reuseAcks == 2 && !retiredReuse.isActive && !editor.hasActiveTransformDraft && !editor.hasUnresolvedMovePress)
        check(ledger.snapshot().reservedBytes == beforeRetiredReuse.reservedBytes &&
            ledger.snapshot().reservations == beforeRetiredReuse.reservations)
        retire()
        editor.renameLayer(a, "Reuse acknowledgment recovered")

        // A caller can close its existing handle while the fixed reuse receipt is temporarily
        // unreachable. Recovery must observe that managed cancellation instead of freezing forever.
        val beforeClosedReuse = ledger.snapshot()
        val closedReuse = checkNotNull(begin())
        val closedOwner = checkNotNull(activeOwner)
        var queryAvailable = false
        val reuseUnknown = object : MovePressTransport {
            override fun press(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int {
                NativeMovePressBridge.press(document, request, limit, resident, output)
                throw IllegalStateException("real reuse press delivery lost")
            }
            override fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int {
                check(queryAvailable) { "reuse query temporarily unavailable" }
                return NativeMovePressBridge.query(document, instance, operation, output)
            }
        }
        check(runCatching { editor.beginMovePress(context, closedOwner, MovePressInput(MovePoint(.5,.5)), reuseUnknown) }.isFailure)
        check(editor.hasUnresolvedMovePress && closedReuse.isActive)
        closedReuse.close()
        check(!closedReuse.isActive && !editor.hasActiveTransformDraft && editor.hasUnresolvedMovePress)
        queryAvailable = true
        check(editor.recoverMovePress(cancel = true) == null && !editor.hasUnresolvedMovePress)
        check(ledger.snapshot().reservedBytes == beforeClosedReuse.reservedBytes &&
            ledger.snapshot().reservations == beforeClosedReuse.reservations)
        retire()
        editor.renameLayer(a, "Closed reuse recovered")

        editor.createMask(a, true); editor.linkMask(a, false)
        editor.setMaskPlacement(a, Transform64(5.0,6.0,1.0,1.0), 0)
        editor.selectLayers(listOf(a), a, true)
        val mask = checkNotNull(begin())
        check(mask.info().target == TransformTarget.UNLINKED_MASK && mask.info().box.x == 5.0 && mask.info().box.y == 6.0)
        mask.close(); retire()
        // Legacy API still honors an explicit independent-mask request.
        editor.selectLayer(a)
        editor.beginTransformDraft(context, a, TransformTarget.UNLINKED_MASK).use { check(it.info().target == TransformTarget.UNLINKED_MASK) }

        // A real admitted budget failure publishes neither selection nor draft; the receipt is acknowledged.
        val beforeFailure = editor.state(); val beforeSelection = editor.selection()
        val blocker = ledger.reserve((ledger.snapshot().availableBytes - 128).coerceAtLeast(0), "move budget rejection")
        try { check(runCatching { begin() }.exceptionOrNull() is TransformDraftException) }
        finally { retire(); blocker.close() }
        check(editor.state() == beforeFailure && editor.selection() == beforeSelection && !editor.hasActiveTransformDraft)

        // Real publication followed by lost delivery; native is executed exactly once.
        var executions = 0
        val lost = object : MovePressTransport {
            override fun press(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int {
                executions++; NativeMovePressBridge.press(document, request, limit, resident, output)
                throw IllegalStateException("lost JNI return after real publication")
            }
        }
        checkNotNull(begin(transport = lost)).close(); retire()
        check(executions == 1 && !editor.hasUnresolvedMovePress && !editor.hasActiveTransformDraft)

        // Corrupt both the token and memory fields after real press. Only queried native data can be adopted.
        val malformed = object : MovePressTransport {
            override fun press(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int {
                val status = NativeMovePressBridge.press(document, request, limit, resident, output)
                output.putLong(96, output.getLong(96)+1); output.putLong(208, Long.MAX_VALUE)
                return status
            }
        }
        val repaired = checkNotNull(begin(transport = malformed))
        check(repaired.info().token == repaired.token && repaired.memory().editPeakBytes < Long.MAX_VALUE)
        repaired.close(); retire()

        var queryCalls = 0
        val unavailable = object : MovePressTransport {
            override fun press(document: Long, request: ByteBuffer, limit: Long, resident: Long, output: ByteBuffer): Int {
                NativeMovePressBridge.press(document, request, limit, resident, output)
                throw IllegalStateException("lost delivery")
            }
            override fun query(document: Long, instance: Long, operation: Long, output: ByteBuffer): Int {
                if (++queryCalls == 1) throw IllegalStateException("temporary query transport failure")
                return NativeMovePressBridge.query(document, instance, operation, output)
            }
        }
        val beforeUnknown = ledger.snapshot()
        check(runCatching { begin(transport = unavailable) }.isFailure)
        check(editor.hasUnresolvedMovePress && ledger.snapshot().reservations == beforeUnknown.reservations + 2)
        check(editor.tryRegisterPendingEdit() == null)
        check(editor.recoverMovePress(cancel = true) == null)
        retire()
        check(!editor.hasUnresolvedMovePress && !editor.hasActiveTransformDraft)
        check(ledger.snapshot().reservedBytes == beforeUnknown.reservedBytes && ledger.snapshot().reservations == beforeUnknown.reservations)

        var ackCalls = 0
        val lostAck = object : MovePressTransport {
            override fun acknowledge(document: Long, instance: Long, operation: Long): Int {
                val status = NativeMovePressBridge.acknowledge(document, instance, operation)
                if (++ackCalls == 1) throw IllegalStateException("acknowledged but lost JNI return")
                return status
            }
        }
        val acknowledged = checkNotNull(begin(transport = lostAck))
        check(ackCalls == 2 && acknowledged.info().token == acknowledged.token)
        acknowledged.close(); retire()

        // Retirement is visible before classification: cancel the actual published token before ack.
        val retired = owner().also { it.chain.cancelled = true }
        check(editor.beginMovePress(context, retired, MovePressInput(MovePoint(.5,.5))) == null)
        retire()
        check(!editor.hasActiveTransformDraft && !editor.hasUnresolvedMovePress)
        return "PASS: actual JNI authority/Auto Select/multiroot/group/mask/preview/history/budget/reuse/lost-return/malformed-token+memory/query-retry/ack-retry/retired-cancel/reuse+cancel+acklost/reuse+unknown+public-close"
    } finally {
        runCatching { editor.recoverMovePress(cancel = true)?.close() }
        retire(); editor.close()
        check(ledger.snapshot().reservedBytes == initial.reservedBytes && ledger.snapshot().reservations == initial.reservations)
    }
}

/** Legacy zero is an empty selection, never a root ID; selection changes preserve content/history. */
private fun singleLayerSelectionCompatibilitySmoke() {
    NativeEditor(8, false).use { editor ->
        val layer = editor.importPixels(1, 1, ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(64, 0, 0, -1)); rewind()
        })
        editor.addGroup()
        editor.undo(false)
        val before = editor.state()
        val selected = editor.selection()
        check(before.redo && selected.primary == layer && selected.roots == listOf(layer))
        val notifications = mutableListOf<Long>()
        editor.committed = { notifications.add(it) }
        editor.selectLayer(0)
        val cleared = editor.selection()
        val empty = editor.state()
        check(cleared.primary == 0L && cleared.roots.isEmpty() && !cleared.mask)
        check(cleared.revision == selected.revision + 1 && cleared.epoch == selected.epoch + 1 &&
            cleared.generation == selected.generation + 1 && cleared.contentIdentity == selected.contentIdentity)
        check(empty.layers == before.layers && empty.document == before.document.copy(activeLayerId = 0) &&
            empty.undo == before.undo && empty.redo == before.redo && empty.revision == cleared.revision)
        check(notifications == listOf(cleared.revision))
        editor.selectLayer(0)
        check(editor.state() == empty && editor.selection() == cleared && notifications == listOf(cleared.revision))
        val invalid = runCatching { editor.selectLayers(listOf(0L), 0) }.exceptionOrNull()
        check(invalid is TransformDraftException && invalid.status == 1)
        check(editor.state() == empty && editor.selection() == cleared && notifications == listOf(cleared.revision))
        editor.selectLayer(layer)
        val restored = editor.selection()
        val reselected = editor.state()
        check(restored.primary == layer && restored.roots == listOf(layer) && !restored.mask)
        check(restored.revision == cleared.revision + 1 && restored.epoch == cleared.epoch + 1 &&
            restored.generation == cleared.generation + 1 && restored.contentIdentity == selected.contentIdentity)
        check(reselected.layers == before.layers && reselected.document == before.document &&
            reselected.undo == before.undo && reselected.redo == before.redo && reselected.revision == restored.revision)
        check(notifications == listOf(cleared.revision, restored.revision))
        editor.undo(true)
        check(editor.state().layers.size == before.layers.size + 1)
    }
}
