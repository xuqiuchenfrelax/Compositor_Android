package com.pojia.compositor.mobile

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.EGL14
import android.os.Looper
import android.os.Handler
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.exp
import kotlin.math.abs

/** GL owns rendering and stroke samples; ordinary edits belong to the retained document dispatcher. */
internal class CanvasView(context: Context, private val editor: NativeEditor,
    private val changed: (EditorState) -> Unit, private val failed: (String) -> Unit,
    private val lease: WorkspaceLease? = null,
    private val noticed: ((UiNoticeKey) -> Unit)? = null) : GLSurfaceView(context) {
    private fun failNotice(key: UiNoticeKey, error: Throwable? = null, diagnostic: String? = null) {
        diagnostic?.let { android.util.Log.w("CompositorCanvas", it) }
        val notice = uiFailure(error, key)
        if (noticed != null) noticed.invoke(notice.key) else failed(notice.text(context))
    }
    var layerId: Long = 0
    @Volatile var strokeSettings = StrokeSettings()
    @Volatile var smearMode = SmearMode.SMUDGE
    @Volatile var healingMode = HealingMode.CONTENT_AWARE
    var brushSize: Float
        get() = strokeSettings.diameter
        set(value) { strokeSettings = strokeSettings.copy(diameter = value) }
    var erasing = false
    var editingMask = false
    var toolMode = CanvasToolMode.BRUSH
        set(value) { onUi { if (field != value) {
            if (field == CanvasToolMode.CROP && value != CanvasToolMode.CROP) cancelCrop()
            if (field == CanvasToolMode.SHAPE && value != CanvasToolMode.SHAPE) cancelShape()
            if (field == CanvasToolMode.GRADIENT && value != CanvasToolMode.GRADIENT) applyGradient()
            moveScrubSession?.let { finishMoveScrub(it, cancelled = true) }
            if (!samplingFinishPending) { cancelSampling(); if (strokeInProgress) cancelStroke() }
            field = value
            if (value == CanvasToolMode.CROP) beginCrop()
            if (value == CanvasToolMode.SHAPE) cancelShape()
            if (value != CanvasToolMode.MARQUEE) cancelMarqueeDraft()
            if (value != CanvasToolMode.LASSO) cancelLassoDraft()
            if (value != CanvasToolMode.CLONE) cancelCloneSourcePick()
        } } }
    var foregroundColor = 0xff000000.toInt()
    var sampledColor: ((Int) -> Unit)? = null
    var levelsSampleContext: AdjustmentEditorDraft? = null
        set(value) {
            if (field != value) { levelsSampleDown = null; levelsSamplePointer = -1 }
            field = value
        }
    val levelsEditing: Boolean get() = levelsSampleContext != null
    var levelsTemporaryHand = false
        set(value) {
            if (field != value) { levelsSampleDown = null; levelsSamplePointer = -1 }
            field = value
        }
    private fun routesLevelsPointer() = levelsEditing && !levelsTemporaryHand &&
        (levelsSampleContext?.levelsSampleMode != null || (toolMode != CanvasToolMode.HAND && toolMode != CanvasToolMode.ZOOM))
    private fun routesAdjustmentPointer() = routesLevelsPointer() ||
        (hasAdjustmentDraft && !levelsEditing && toolMode != CanvasToolMode.HAND && toolMode != CanvasToolMode.ZOOM)
    var levelsSamplePoint: ((Double, Double) -> Unit)? = null
    private var levelsSampleDeliveries = 0L
    internal fun levelsSampleDeliveriesForTest(): Long = levelsSampleDeliveries
    private var levelsSampleDown: Pair<Float, Float>? = null
    private var levelsSamplePointer = -1
    private var levelsSampleViewport = -1L
    private var routingLevelsNavigation = false
    var samplingEnabled = true
        set(value) { field = value; if (!value) cancelSampling() }
    var showsSampleRing = true
        set(value) { field = value; if (!value) sampleRing = null else updateSampleRing() }
    var sampleRing by mutableStateOf<SampleRingState?>(null)
        private set
    private var samplingPointer by mutableStateOf(false)
    private var samplingFinishPending by mutableStateOf(false)
    private var samplingToken = 0L
    private var samplingOriginal = 0xff000000.toInt()
    private var samplingPointX = 0f
    private var samplingPointY = 0f
    private var samplingRevision = -1L
    private data class SampleDelivery(val token: Long, val color: Int?, val x: Float, val y: Float, val finished: Boolean = false)
    private val sampleDeliveryGate = Any()
    private var pendingSampleDelivery: SampleDelivery? = null
    private var sampleDeliveryScheduled = false
    private var sampleUiDeliveries = 0L
    private var maximumSampleUiCallbacks = 0
    private val eyedropper: EyedropperInput = EyedropperInput({ action -> queueEvent(action) }, {
        check(current() && samplingEnabled && !nativeCommandPending && !editor.hasPendingEdits && !editor.hasActiveStrokes) { PENDING_EDIT_MESSAGE }
        NativeColorSampler.capture(context, editor)
    }, { token, color, x, y ->
        synchronized(sampleDeliveryGate) {
            // A transparent/outside point still moves the ring, but retains the last accepted color.
            val retained = color ?: pendingSampleDelivery?.takeIf { it.token == token }?.color
            pendingSampleDelivery = SampleDelivery(token, retained, x, y)
            scheduleSampleDelivery()
        }
    }, { token, error ->
        main.post { deliver { if (samplingToken == token) { cancelSampling(); failNotice(UiNoticeKey.COLOR_SAMPLE_FAILED, error) } } }
    }, { token ->
        synchronized(sampleDeliveryGate) {
            val previous = pendingSampleDelivery?.takeIf { it.token == token }
            pendingSampleDelivery = previous?.copy(finished = true) ?: SampleDelivery(token, null, Float.NaN, Float.NaN, true)
            scheduleSampleDelivery()
        }
    })
    var zoomOut = false
    var viewportChanged: ((Double) -> Unit)? = null
    private var painting = false
    private val main = Handler(Looper.getMainLooper())
    private var bindingRetryScheduled = false
    internal fun retryEditorBinding(invalidate: () -> Unit) {
        if (retired || bindingRetryScheduled) return
        bindingRetryScheduled = true
        main.postDelayed({
            bindingRetryScheduled = false
            if (!retired) invalidate()
        }, 32)
    }
    private val pendingStrokeTokens = mutableSetOf<Long>()
    @Volatile private var nativeFinishPending = false
    val strokeInProgress: Boolean get() = !retired && (painting || nativeFinishPending)
    private val strokeReservations = java.util.concurrent.ConcurrentHashMap<Long, StrokeReservation>()
    private var nativeCommandPending by mutableStateOf(false)
    private val detachCommands = editor.commandDispatcher.observe { value, error, pending, selection ->
        if (current()) {
            val shown = pixelSelection
            val incoming = selection?.selection
            val acceptsSnapshot = incoming == null || shown == null ||
                (incoming.instance == shown.instance && incoming.generation >= shown.generation &&
                    incoming.epoch >= shown.epoch)
            if (value != null && acceptsSnapshot) {
                val latest = value
                if (layerId != latest.document.activeLayerId) editingMask = false
                layerId = latest.document.activeLayerId
                changed(latest)
                if (cropCommitPending) {
                    cropCommitPending = false
                    cropRect = null; cropStart = null; cropHandle = CropHandle.NONE
                    fit()
                }
            }
            nativeCommandPending = pending
            if (selection != null && acceptsSnapshot) {
                pixelSelection = selection.selection
                pixelSelectionRegions = selection.regions
            }
            if (!pending && magicPending) {
                magicPending = false
                magicCancellation = null
            }
            if (error != null) {
                if (cropCommitPending) cropCommitPending = false
                failNotice(UiNoticeKey.EDITOR_COMMAND_FAILED, diagnostic = error)
            }
            requestRender()
        }
    }
    val editingInProgress: Boolean get() = hasAdjustmentDraft || editingOtherThanAdjustmentInProgress
    val editingOtherThanAdjustmentInProgress: Boolean get() = strokeInProgress || (!retired &&
        (nativeCommandPending || samplingFinishPending || moveInProgress || moveContextPending ||
            moveScrubSession != null || distortSessionActive || cropRect != null || shapeDraft != null || gradientDraft != null ||
            lassoWorkingPoints.isNotEmpty() || magicPending))
    val samplingInProgress: Boolean get() = samplingPointer
    val sampleSettlementPending: Boolean get() = samplingFinishPending
    val canChangeTool: Boolean get() = !strokeInProgress && !nativeCommandPending && !moveContextPending && !magicPending && !distortFinalizing
    private val viewEpoch = nextViewEpoch.incrementAndGet()
    private var moveInputEpoch = 0L
    private var moveOwner by mutableStateOf<TransformController.Owner?>(null)
    var moveBox by mutableStateOf<Transform64?>(null)
        private set
    var moveSettling by mutableStateOf(false)
        private set
    var moveContextPending by mutableStateOf(false)
        private set
    private val moveContextSwitch = MoveContextSwitch({ action -> main.post { action() }; Unit },
        { moveContextPending = it }, { failNotice(UiNoticeKey.CONTEXT_SWITCH_FAILED, it) })
    var moveSnap by mutableStateOf<MoveResult?>(null)
        private set
    val moveInProgress: Boolean get() = moveOwner != null
    val movePersistent: Boolean get() = moveOwner?.persistent == true
    val moveFromFields: Boolean get() = moveOwner?.fromFields == true
    val moveRetainsDraft: Boolean get() = moveOwner?.retainsDraft == true
    var moveAcceptedSequence by mutableStateOf(0L)
        private set
    private var moveScaleBasis: MoveScaleBasis? = null
    val moveScalePercent: Double? get() = moveBox?.let { box ->
        moveScaleBasis?.let { MoveInspectorGeometry.scalePercent(box, it) }
    }
    val moveCanFlip: Boolean get() = current() && !moveContextPending && moveGesture == null && moveScrubSession == null &&
        !strokeInProgress && !nativeCommandPending && !samplingFinishPending &&
        (!moveInProgress || moveBox != null) &&
        (!moveSettling || moveOwner?.let(transforms::canQueueInspectorFlip) == true)
    var moveLockRatio by mutableStateOf(true)
    var moveShowControls by mutableStateOf(true)
    var moveAutoSelect by mutableStateOf(false)
    var moveDuplicateArmed by mutableStateOf(false)
    internal var distortSessionActive by mutableStateOf(false)
        private set
    internal var distortSessionReady by mutableStateOf(false)
        private set
    internal var distortFinalizing by mutableStateOf(false)
        private set
    internal val distortHandlePoints: List<Pair<Float, Float>>
        get() = distortCorners.map { distortScreen(it) }
    private var distortSession: NativeDistortSession? = null
    /** Retained as a non-owning lease so Canvas can retire a GL preview even if its queue event is skipped. */
    @Volatile private var distortPreviewLease: DistortPreviewLease? = null
    @Volatile private var adjustmentPreviewLease: DistortPreviewLease? = null
    private data class AdjustmentPreviewRequest(val token: Long, val draft: AdjustmentEditorDraft)
    var hasAdjustmentDraft by mutableStateOf(false)
        private set
    var adjustmentPreviewFailed by mutableStateOf(false)
        private set
    private val adjustmentPreviewGate = Any()
    @Volatile private var adjustmentPreviewToken = 0L
    private var adjustmentPreviewPending: AdjustmentPreviewRequest? = null
    private var adjustmentPreviewScheduled = false
    private var distortCorners by mutableStateOf<List<DistortPoint>>(emptyList())
    private var distortPointerIndex = -1
    internal var cropRect by mutableStateOf<CropRect?>(null)
        private set
    internal var pixelSelection by mutableStateOf<PixelSelection?>(null)
    internal var pixelSelectionRegions by mutableStateOf<List<PixelSelectionRegion>>(emptyList())
    internal var magicTolerance by mutableStateOf(32)
    internal var magicSampleRadius by mutableStateOf(0)
    internal var magicContiguous by mutableStateOf(true)
    internal var magicPending by mutableStateOf(false)
    private var magicCancellation: MagicWandCancellation? = null
    private var magicPointerId: Int? = null
    private var magicDown: Pair<Float, Float>? = null
    private var magicGestureOperation = PixelSelectionOperation.REPLACE
    private var marqueeStart: Pair<Double, Double>? = null
    private var marqueeDraft by mutableStateOf<PixelSelectionRect?>(null)
    private var marqueeShape by mutableStateOf(PixelSelectionShape.RECTANGLE)
    private var marqueeGestureShape by mutableStateOf(PixelSelectionShape.RECTANGLE)
    private var marqueeOperation by mutableStateOf(PixelSelectionOperation.REPLACE)
    private var marqueeGestureOperation by mutableStateOf(PixelSelectionOperation.REPLACE)
    private val lassoWorkingPoints = ArrayList<PixelSelectionPoint>(256)
    private var lassoPreviewVersion by mutableStateOf(0L)
    private var lassoGestureOperation by mutableStateOf(PixelSelectionOperation.REPLACE)
    /** The in-progress selection bounds are presentation state only; native is updated on UP. */
    internal val marqueePreview: PixelSelectionRect? get() = marqueeDraft
    internal val marqueePreviewShape: Int get() = if (marqueeDraft != null) marqueeGestureShape else marqueeShape
    internal fun setMarqueeShape(shape: Int) {
        require(shape == PixelSelectionShape.RECTANGLE || shape == PixelSelectionShape.ELLIPSE)
        if (marqueeStart == null) marqueeShape = shape
        requestRender()
    }
    internal fun setMarqueeOperation(operation: Int) {
        require(operation in PixelSelectionOperation.REPLACE..PixelSelectionOperation.INTERSECT)
        if (marqueeStart == null) marqueeOperation = operation
        requestRender()
    }
    internal val lassoPreview: List<PixelSelectionPoint>?
        get() {
            lassoPreviewVersion
            return lassoWorkingPoints.takeIf { it.isNotEmpty() }
        }
    internal fun cancelLassoDraft() {
        lassoWorkingPoints.clear(); lassoPreviewVersion++; lassoGestureOperation = marqueeOperation; requestRender()
    }
    internal fun configureMagicTolerance(value: Int) { magicTolerance = value.coerceIn(0, 255) }
    internal fun configureMagicSampleRadius(value: Int) { magicSampleRadius = value.coerceIn(0, 2) }
    internal fun configureMagicContiguous(value: Boolean) { magicContiguous = value }
    internal var cropRatioChoice by mutableStateOf(CropRatioChoice.FREE)
    internal var cropCustomRatio by mutableStateOf<Double?>(null)
    private var cropSymmetric = false
    private var cropCommitPending = false
    internal val offsetXForOverlay: Float get() = offsetX.toFloat()
    internal val offsetYForOverlay: Float get() = offsetY.toFloat()
    internal val scaleForOverlay: Float get() = scale.toFloat()
    internal var shapeKind = ShapeKind.RECTANGLE
    internal var shapeCornerRadius = 0.0
    internal var shapeLineWidth = 4.0
    internal var shapeLineStyle = ShapeLineStyle.SOLID
    internal var shapeLineCap = ShapeLineCap.SQUARE
    internal var shapeColor = 0xff000000.toInt()
    internal var shapeDraft by mutableStateOf<ShapeDraft?>(null)
        private set
    private var shapePointerActive = false
    internal var gradientShape = GradientKind.LINEAR
    internal var gradientStyle = GradientStyle.FOREGROUND_TO_TRANSPARENT
    internal var gradientReversed = false
    internal var gradientOpacity = 1.0
    internal var gradientForeground = 0xff000000.toInt()
    internal var gradientBackground = 0xffffffff.toInt()
    internal var gradientDraft by mutableStateOf<GradientDraft?>(null)
        private set
    internal var gradientPending by mutableStateOf(false)
        private set
    private var gradientPointerActive = false
    private var cropStart: Pair<Double, Double>? = null
    private var cropHandle = CropHandle.NONE
    internal var distortFreeCorners = true
    private var distortAttempt = 0L
    private var moveViewportVersion by mutableStateOf(0L)
    internal var keyboardFocusActive = false
    internal var keyboardMoveEnabled = false
    internal var transformShortcut: (() -> Unit)? = null
    internal var clippingShortcutActions: SelectedLayerActions? = null
    internal fun dispatchClippingShortcut(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_G || (!event.isMetaPressed && !event.isCtrlPressed) ||
            !event.isAltPressed || event.isShiftPressed ||
            (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)) return false
        if ((!keyboardFocusActive && !hasFocus()) || !keyboardMoveEnabled || !hasWindowFocus() ||
            !current() || !samplingEnabled || editingInProgress) return false
        // Use the published menu plan; the command retains its revision/selection fence.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            clippingShortcutActions?.takeIf { it.canClipping }?.let {
                val accepted = command(EditorCommand.ToggleSelectedClipping(it))
                if (BuildConfig.DEBUG) android.util.Log.d("CompositorKey",
                    "canvas clipping accepted=$accepted revision=${it.revision} release=${it.releasesClipping}")
            }
        }
        return true
    }
    internal fun dispatchTransformShortcut(event: KeyEvent): Boolean {
        if (distortSessionActive && (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) cancelDistortSession() else applyDistortSession()
            }
            return true
        }
        if (!keyboardMoveEnabled || !hasWindowFocus() || !current() || !samplingEnabled ||
            event.keyCode != KeyEvent.KEYCODE_T || (!event.isMetaPressed && !event.isCtrlPressed) ||
            event.isAltPressed || event.isShiftPressed ||
            (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)) return false
        val action = transformShortcut ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }
    internal fun dispatchMoveKey(event: KeyEvent): Boolean {
        if (!keyboardFocusActive || !keyboardMoveEnabled || !hasWindowFocus() || !current() || !samplingEnabled || toolMode != CanvasToolMode.MOVE ||
            event.isCtrlPressed || event.isMetaPressed || event.isAltPressed ||
            (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)) return false
        val dx = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1.0; KeyEvent.KEYCODE_DPAD_RIGHT -> 1.0; else -> 0.0 }
        val dy = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -1.0; KeyEvent.KEYCODE_DPAD_DOWN -> 1.0; else -> 0.0 }
        if (dx == 0.0 && dy == 0.0) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = if (event.isShiftPressed) 10.0 else 1.0
            val accepted = nudgeMove(dx * step, dy * step)
            if (BuildConfig.DEBUG) android.util.Log.d("CompositorKey", "canvas nudge accepted=$accepted step=$step")
        }
        return true
    }
    private data class MoveGesture(val original: Transform64, val down: MovePoint, val targets: MoveSnapTargets,
        val handle: MoveHandleMode? = null, val pointer: Int = -1, val downTime: Long = 0,
        val viewportVersion: Long = 0, val duplicateAtDown: Boolean = false,
        val downModifiers: Int = 0, val downSampling: Int = 0, val screenOrigin: MovePoint)
    private var moveGesture: MoveGesture? = null
    private class PendingPersistentDuplicate(val gesture: MoveGesture, val selection: NativeSelection,
        val downEvent: MotionEvent,
        var lastPoint: MovePoint, var modifiers: Int) {
        var ended = false
        var cancelled = false
    }
    private var pendingPersistentDuplicate: PendingPersistentDuplicate? = null
    private var moveSampling = 0
    private var locallyUpdatedMove = false
    private var moveFieldSession: MoveFieldSession? = null
    private var moveScrubSession by mutableStateOf<MoveScrubSession?>(null)
    private var lastSettledMoveIdentity: Long? = null
    private val transforms = editor.retainedTransformController(context)
    private var detachTransformObserver: (() -> Unit)? = null
    private fun transformChanged(snapshot: TransformController.Snapshot) {
        if (moveOwner == null && snapshot.owner.fromFields && snapshot.owner.predecessorIdentity != null &&
            snapshot.owner.predecessorIdentity == lastSettledMoveIdentity &&
            snapshot.owner.rootLayerId == layerId && current() && matchesMoveTarget(snapshot.owner)) adoptMove(snapshot)
        if (snapshot.owner === moveOwner && current()) {
            val pending = pendingMovePress
            if (snapshot.phase == TransformController.Phase.ACTIVE && pending?.owner === snapshot.owner) {
                if (pending.epoch != moveInputEpoch || pending.viewportVersion != moveViewportVersion ||
                    editor.selection().instance != pending.instance || toolMode != CanvasToolMode.MOVE) {
                    discardPendingMovePress()
                } else {
                    val selection = editor.selection()
                    layerId = selection.primary; editingMask = selection.mask
                    val state = editor.state()
                    changed(state)
                    moveBox = snapshot.info?.box
                    snapshot.info?.let { moveSampling = it.sampling }
                    pendingMovePress = null
                    val original = snapshot.info?.box
                    if (original != null) {
                        val gesture = MoveGesture(pending.frozenGesture?.original ?: original,
                            pending.frozenGesture?.down ?: pending.down,
                            pending.frozenGesture?.targets ?: MoveGeometry.targets(state, selection.primary,
                                editor.canvasWidth, editor.canvasHeight, selection.roots),
                            pending.handle, pending.pointer, pending.downTime, pending.viewportVersion,
                            pending.duplicateAtDown, pending.frozenGesture?.downModifiers ?: 0,
                            pending.frozenGesture?.downSampling ?: moveSampling, pending.screenOrigin)
                        moveGesture = gesture
                        pending.lastPoint?.let { updateMoveGesture(gesture, it, pending.modifiers) }
                        if (pending.ended) applyMove()
                    }
                }
            }
            val accepted = snapshot.accepted
            if (snapshot.owner.duplicateAtDown && snapshot.phase == TransformController.Phase.ACTIVE &&
                pendingMovePress == null) changed(editor.presentation().first)
            if (accepted != null && accepted.seq >= moveAcceptedSequence) {
                moveAcceptedSequence = accepted.seq; moveBox = accepted.box; moveSampling = accepted.sampling
            } else if (accepted == null && (moveBox == null || !locallyUpdatedMove)) {
                moveBox = snapshot.info?.box
                snapshot.info?.let { moveSampling = it.sampling }
            }
            snapshot.terminal?.let { terminal ->
                // Read the latest state: an observer failure never replays a published native commit.
                try {
                    deliver {
                        if (pendingMovePress?.owner === snapshot.owner) pendingMovePress = null
                        val selection = editor.selection()
                        layerId = selection.primary; editingMask = selection.mask
                        changed(editor.state())
                        terminal.error?.let { failNotice(UiNoticeKey.TRANSFORM_FAILED, it) }
                    }
                } catch (error: Exception) {
                    android.util.Log.e("CompositorMove", "Transform settled; UI refresh failed", error)
                    runCatching { failNotice(UiNoticeKey.TRANSFORM_REFRESH_FAILED, error) }
                } finally {
                    lastSettledMoveIdentity = snapshot.owner.identity
                    moveOwner = null; moveBox = null; moveGesture = null; moveSnap = null; moveSettling = false
                    moveScaleBasis = null; moveAcceptedSequence = 0
                    if (pendingMovePress?.ended != false) parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            requestRender()
        }
        if (snapshot.phase == TransformController.Phase.TERMINAL_WAITING) transformsAcknowledge(snapshot.owner)
    }
    private fun adoptMove(snapshot: TransformController.Snapshot) {
        moveOwner = snapshot.owner; moveInputEpoch = snapshot.owner.inputEpoch
        moveAcceptedSequence = snapshot.accepted?.seq ?: 0
        moveBox = snapshot.accepted?.box ?: snapshot.info?.box
        moveSampling = snapshot.accepted?.sampling ?: snapshot.info?.sampling ?: 0
        moveSettling = transforms.isFinishing(snapshot.owner)
        locallyUpdatedMove = snapshot.accepted != null
        // Committed members stay unchanged throughout the draft, including View recreation.
        val state = editor.state()
        moveScaleBasis = state.layers.firstOrNull { it.id == snapshot.owner.rootLayerId }
            ?.let { MoveInspectorGeometry.basis(it, snapshot.owner.target, selectedGeometry(it, state)?.box) }
    }
    private fun selectedGeometry(layer: LayerState?, state: EditorState): MoveSelectionGeometry? {
        val selection = editor.selection()
        if (layer?.id != selection.primary) return null
        return MoveGeometry.selection(state, selection.roots, selection.primary, editingMask)
    }
    private fun matchesMoveTarget(owner: TransformController.Owner): Boolean {
        val state = editor.state()
        val layer = state.layers.firstOrNull { it.id == layerId && it.id == state.document.activeLayerId }
        return owner.rootLayerId == layerId && owner.target == selectedGeometry(layer, state)?.target
    }
    fun bindMoveTarget() {
        if (!current() || moveOwner != null) return
        // Compose binds the selected layer and mask after construction.
        transforms.snapshot()?.takeIf { it.owner.retainsDraft && matchesMoveTarget(it.owner) }?.let(::adoptMove)
    }
    private fun transformsAcknowledge(owner: TransformController.Owner) {
        transforms.acknowledge(owner)
        if (current()) requestRender()
    }
    private fun point(event: MotionEvent) = MovePoint((event.x.toDouble() - offsetX) / scale,
        (event.y.toDouble() - offsetY) / scale)
    fun snapScreenLines(): Pair<Float?, Float?> = (moveSnap?.snapX?.let { (offsetX + it * scale).toFloat() }) to
        (moveSnap?.snapY?.let { (offsetY + it * scale).toFloat() })
    private fun beginMove(persistent: Boolean = false, fromFields: Boolean = false): Boolean {
        if (!current() || moveInProgress || moveContextPending || strokeInProgress || nativeCommandPending || samplingFinishPending) return false
        val state = editor.state()
        val layer = state.layers.firstOrNull { it.id == layerId && it.id == state.document.activeLayerId }
        val geometry = selectedGeometry(layer, state)
        if (geometry == null) { failNotice(UiNoticeKey.TRANSFORM_SELECT_LAYER); return false }
        val target = geometry.target
        val box = geometry.box
        val activeLayer = checkNotNull(layer)
        var owner: TransformController.Owner? = null
        val start = { owner = transforms.begin(activeLayer.id, target, viewEpoch, viewEpoch, ++moveInputEpoch,
            persistent, fromFields); Unit }
        try {
            if (lease != null) lease.execute(start) else start()
        } catch (error: Exception) { failNotice(UiNoticeKey.TRANSFORM_ADMISSION_FAILED, error); return false }
        if (owner == null) return false
        cancelSampling()
        moveOwner = owner; moveBox = box; moveSettling = false; locallyUpdatedMove = false
        moveAcceptedSequence = 0; moveScaleBasis = MoveInspectorGeometry.basis(activeLayer, target, geometry.box)
        moveSampling = if (target == TransformTarget.UNLINKED_MASK) activeLayer.maskSampling else activeLayer.sampling
        return true
    }
    fun startPersistentMove(): Boolean = if (movePersistent && !moveSettling) true else beginMove(persistent = true)
    fun beginMoveFieldSession(): MoveFieldSession {
        moveFieldSession?.let(::completeMoveField)
        return MoveFieldSession(viewEpoch, moveOwner?.takeIf { it.retainsDraft && !moveSettling })
            .also { moveFieldSession = it }
    }
    fun completeMoveField(session: MoveFieldSession) {
        if (session.viewEpoch != viewEpoch || session.completed || !current()) return
        session.completed = true
        if (moveFieldSession === session) moveFieldSession = null
        val owner = session.owner ?: return
        if (moveOwner === owner && owner.fromFields && !moveSettling) {
            moveSettling = true; transforms.finish(owner)
        }
    }
    fun completeMoveField() {
        val session = moveFieldSession
        if (session != null) completeMoveField(session)
        else if (!movePersistent) {
            val owner = moveOwner ?: return
            if (!moveSettling) { moveSettling = true; transforms.finish(owner) }
        }
    }
    private fun validMoveFieldSession(session: MoveFieldSession?) = session == null ||
        (session === moveFieldSession && session.viewEpoch == viewEpoch && !session.completed && current())
    internal fun beginMoveScrub(field: TransformScrubField): MoveScrubSession? {
        if (Looper.myLooper() != Looper.getMainLooper() || !current() || toolMode != CanvasToolMode.MOVE ||
            moveScrubSession != null || moveContextPending || moveGesture != null || pendingMovePress != null ||
            pendingPersistentDuplicate != null || strokeInProgress || nativeCommandPending || samplingFinishPending) return null
        // Blur can arrive before or after activation. Retire its captured field session exactly once.
        moveFieldSession?.let(::completeMoveField)
        val previous = moveOwner
        if (previous != null && (!previous.retainsDraft || (moveSettling && previous.persistent))) return null
        val token = lease?.commandToken()
        if (lease != null && token == null) return null
        val state = editor.state()
        val selection = editor.selection()
        val layer = state.layers.firstOrNull { it.id == layerId && it.id == selection.primary } ?: return null
        val geometry = selectedGeometry(layer, state) ?: return null
        if (previous != null && (previous.rootLayerId != layerId || previous.target != geometry.target)) return null
        val original = moveBox ?: geometry.box
        val persistent = previous?.persistent == true
        // An ordinary group's completed field starts the next edit at a fresh 100% basis.
        val basis = if (!persistent && geometry.target == TransformTarget.GROUP)
            MoveScaleBasis(original.width, original.height)
        else moveScaleBasis ?: MoveInspectorGeometry.basis(layer, geometry.target, geometry.box)
        val number = scrubNumber(field, original, basis) ?: return null
        val numeric = TransformScrubGeometry.begin(field, number) ?: return null
        val sampling = if (previous != null) moveSampling
            else if (geometry.target == TransformTarget.UNLINKED_MASK) layer.maskSampling else layer.sampling
        val session = MoveScrubSession(viewEpoch, moveInputEpoch, moveViewportVersion, canvasScreenOrigin(), selection, token,
            geometry.target, original, basis, numeric, sampling, persistent, previous.takeIf { persistent })
        moveScrubSession = session
        if (previous != null && !persistent) {
            session.waiting = true; moveSettling = true
            transforms.finishInspectorChain(previous).whenComplete { terminal, error -> main.post {
                if (moveScrubSession !== session || session.completed) return@post
                if (!scrubContextMatches(session) || moveOwner != null || error != null ||
                    terminal?.committed != true || terminal.cancelled || terminal.error != null) {
                    finishMoveScrub(session, cancelled = true); return@post
                }
                // A queued ordinary edit must not silently become this gesture's starting document.
                val result = terminal.result
                val selected = editor.selection()
                if (result == null || selected.revision != result.revision ||
                    selected.generation != result.sceneGeneration || !sameScrubSelection(session, selected)) {
                    finishMoveScrub(session, cancelled = true); return@post
                }
                // Mac's blur commits synchronously. Resolve the new edit's actual box once here:
                // a group returns to upright bounds, and admitted Inspector flips have now settled.
                val settledState = editor.state()
                val settledLayer = settledState.layers.firstOrNull { it.id == selected.primary }
                val settledGeometry = selectedGeometry(settledLayer, settledState)
                if (settledLayer == null || settledGeometry == null || settledGeometry.target != session.target) {
                    finishMoveScrub(session, cancelled = true); return@post
                }
                val settledBasis = MoveInspectorGeometry.basis(settledLayer, session.target, settledGeometry.box)
                val settledNumber = scrubNumber(field, settledGeometry.box, settledBasis)
                val settledNumeric = settledNumber?.let { TransformScrubGeometry.begin(field, it) }
                if (settledNumeric == null) { finishMoveScrub(session, cancelled = true); return@post }
                session.original = settledGeometry.box; session.scaleBasis = settledBasis; session.numeric = settledNumeric
                session.sampling = if (session.target == TransformTarget.UNLINKED_MASK)
                    settledLayer.maskSampling else settledLayer.sampling
                session.expectedRevision = result.revision; session.expectedGeneration = result.sceneGeneration
                session.waiting = false
                session.latestTranslationDp?.let { previewMoveScrub(session, it) }
                if (session.ended) completeMoveScrub(session)
            } }
        }
        return session
    }
    private fun scrubNumber(field: TransformScrubField, box: Transform64, basis: MoveScaleBasis?): Double? = when (field) {
        TransformScrubField.X -> box.x
        TransformScrubField.Y -> box.y
        TransformScrubField.WIDTH -> box.width
        TransformScrubField.HEIGHT -> box.height
        TransformScrubField.SCALE -> basis?.let { MoveInspectorGeometry.scalePercent(box, it) }
        TransformScrubField.ROTATION -> box.rotation
    }
    private fun sameScrubSelection(session: MoveScrubSession, selected: NativeSelection): Boolean =
        selected.instance == session.selection.instance && selected.epoch == session.selection.epoch &&
            selected.primary == session.selection.primary && selected.roots == session.selection.roots &&
            selected.mask == session.selection.mask
    private fun scrubContextMatches(session: MoveScrubSession): Boolean =
        moveScrubSession === session && !session.completed && current() && session.viewEpoch == viewEpoch &&
            session.inputEpoch == moveInputEpoch && session.viewportVersion == moveViewportVersion &&
            layerId == session.selection.primary && editingMask == session.selection.mask &&
            toolMode == CanvasToolMode.MOVE && !moveContextPending
    internal fun updateMoveScrub(session: MoveScrubSession, totalTranslationDp: Double): Boolean {
        if (!scrubContextMatches(session) || session.ended) return false
        if (TransformScrubGeometry.value(session.numeric, totalTranslationDp) == null) return false
        session.latestTranslationDp = totalTranslationDp
        return session.waiting || previewMoveScrub(session, totalTranslationDp)
    }
    private fun previewMoveScrub(session: MoveScrubSession, translationDp: Double): Boolean {
        if (!scrubContextMatches(session) || session.waiting || moveSettling) return false
        val number = TransformScrubGeometry.value(session.numeric, translationDp) ?: return false
        val field = session.numeric.field.moveField
        var updated = if (field == null) {
            MoveInspectorGeometry.scaled(session.original, number, session.scaleBasis ?: return false)
        } else MoveGeometry.field(session.original, field, number, session.target == TransformTarget.GROUP)
        updated ?: return false
        if (moveLockRatio) updated = when (field) {
            MoveField.WIDTH -> updated.copy(height = session.original.height * number / session.original.width)
            MoveField.HEIGHT -> updated.copy(width = session.original.width * number / session.original.height)
            else -> updated
        }
        if (!(if (session.target == TransformTarget.GROUP) MoveGeometry.validGroup(updated) else MoveGeometry.valid(updated))) return false
        if (updated == (moveBox ?: session.original)) return true
        if (session.owner == null) {
            if (moveOwner != null) return false
            var began = false
            val start = {
                val selected = editor.selection()
                if (sameScrubSelection(session, selected) && selected.revision == session.expectedRevision &&
                    selected.generation == session.expectedGeneration) began = beginMove(fromFields = true)
            }
            val admitted = if (lease != null) lease.execute(session.workspaceToken ?: return false, start)
                else { editor.serialized(start); true }
            if (!admitted || !began) return false
            session.owner = moveOwner; session.inputEpoch = moveInputEpoch
        }
        val owner = session.owner ?: return false
        if (moveOwner !== owner || owner.target != session.target || owner.rootLayerId != session.selection.primary ||
            transforms.isFinishing(owner) || !transforms.update(owner, updated, session.sampling)) return false
        locallyUpdatedMove = true; moveBox = updated; moveSnap = null
        moveAcceptedSequence = transforms.snapshot()?.takeIf { it.owner === owner }?.accepted?.seq ?: moveAcceptedSequence
        requestRender()
        return true
    }
    internal fun finishMoveScrub(session: MoveScrubSession, cancelled: Boolean) {
        if (moveScrubSession !== session || session.completed || (session.ended && !cancelled)) return
        session.ended = true
        if (cancelled) {
            val owner = session.owner
            val restore = session.persistent && scrubContextMatches(session) && moveOwner === owner &&
                owner != null && !transforms.isFinishing(owner)
            session.completed = true; moveScrubSession = null
            if (owner != null && moveOwner === owner) {
                if (restore && transforms.update(owner, session.original, session.sampling)) {
                    locallyUpdatedMove = true; moveBox = session.original; moveSnap = null
                    moveAcceptedSequence = transforms.snapshot()?.takeIf { it.owner === owner }?.accepted?.seq ?: moveAcceptedSequence
                    requestRender()
                } else if (!session.persistent) {
                    moveSettling = true; transforms.cancel(owner)
                }
            }
        } else if (!session.waiting) completeMoveScrub(session)
    }
    private fun completeMoveScrub(session: MoveScrubSession) {
        if (moveScrubSession !== session || session.completed) return
        if (!scrubContextMatches(session)) {
            session.ended = false; finishMoveScrub(session, cancelled = true); return
        }
        session.completed = true; moveScrubSession = null
        val owner = session.owner
        if (!session.persistent && owner != null && moveOwner === owner && !moveSettling) {
            moveSettling = true; transforms.finish(owner)
        }
    }
    private class PendingMovePress(val instance: Long, var epoch: Long,
        val viewportVersion: Long, val pointer: Int, val downTime: Long, val down: MovePoint,
        val handle: MoveHandleMode?, val input: MovePressInput, val duplicateAtDown: Boolean,
        val frozenGesture: MoveGesture? = null, val screenOrigin: MovePoint) {
        var owner: TransformController.Owner? = null
        var lastPoint: MovePoint? = null
        var modifiers: Int = 0
        var ended = false
    }
    private var pendingMovePress: PendingMovePress? = null
    private fun discardPendingMovePress() {
        val pending = pendingMovePress ?: return
        pendingMovePress = null; moveContextSwitch.invalidate()
        pending.owner?.let(transforms::cancel)
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    private fun beginInspectorMovePress(event: MotionEvent): Boolean = beginAtomicMovePress(event, true)
    private fun beginAtomicMovePress(event: MotionEvent, fromInspector: Boolean = false,
        forcedDuplicateAtDown: Boolean = false, frozenGesture: MoveGesture? = null): Boolean {
        if (moveScrubSession != null || moveContextPending || pendingMovePress != null || moveGesture != null || event.pointerCount != 1 ||
            !event.x.isFinite() || !event.y.isFinite()) return true
        val previous = moveOwner
        if (fromInspector && previous?.fromFields != true) return false
        if (!fromInspector && previous != null) return false
        val committed = editor.state()
        val selectedLayer = committed.layers.firstOrNull { it.id == committed.document.activeLayerId }
        val geometry = selectedGeometry(selectedLayer, committed)
        val box = moveBox ?: geometry?.box
        val handle = if (frozenGesture != null) frozenGesture.handle
            else if (moveShowControls && box != null) moveOverlay(box).hit(MovePoint(event.x.toDouble(), event.y.toDouble())) else null
        if (event.metaState and KeyEvent.META_META_MASK != 0 && handle is MoveHandleMode.Resize) {
            failNotice(UiNoticeKey.DISTORT_UNAVAILABLE); return true
        }
        val selection = editor.selection()
        val down = point(event)
        val downModifiers = frozenGesture?.downModifiers ?: event.metaState
        val input = MovePressInput(down, moveAutoSelect,
            downModifiers and (KeyEvent.META_META_MASK or KeyEvent.META_CTRL_MASK) != 0,
            downModifiers and KeyEvent.META_SHIFT_MASK != 0,
            picksAllowedAtDown = frozenGesture == null && !fromInspector && handle == null)
        val press = PendingMovePress(selection.instance, moveInputEpoch, moveViewportVersion,
            event.getPointerId(0), event.downTime, down, handle, input,
            handle == null && (forcedDuplicateAtDown || moveDuplicateArmed || event.metaState and KeyEvent.META_ALT_MASK != 0),
            frozenGesture, frozenGesture?.screenOrigin ?: canvasScreenOrigin())
        pendingMovePress = press
        parent?.requestDisallowInterceptTouchEvent(true)
        fun launch() {
            if (!current() || pendingMovePress !== press || toolMode != CanvasToolMode.MOVE ||
                moveViewportVersion != press.viewportVersion || editor.selection().instance != press.instance) {
                discardPendingMovePress(); return
            }
            val selected = editor.selection()
            val state = editor.state()
            val layer = state.layers.firstOrNull { it.id == selected.primary }
            val target = if (selected.roots.size > 1 || layer?.isGroup == true) TransformTarget.GROUP
                else if (selected.mask && layer?.maskLinked == false) TransformTarget.UNLINKED_MASK else TransformTarget.LAYER
            val next = transforms.begin(selected.primary, target, viewEpoch, viewEpoch, ++moveInputEpoch,
                press = input, duplicateAtDown = press.duplicateAtDown,
                duplicateDownSampling = press.frozenGesture?.downSampling)
            if (next == null) { discardPendingMovePress(); return }
            press.epoch = moveInputEpoch; press.owner = next
            moveOwner = next; moveBox = null; moveSettling = false; locallyUpdatedMove = false
            moveAcceptedSequence = 0; moveScaleBasis = null
        }
        if (!fromInspector) launch()
        else {
            val submitted = moveContextSwitch.submit({
                moveSettling = true
                transforms.finishInspectorChain(checkNotNull(previous)).also { receipt -> receipt.whenComplete { terminal, error ->
                    if (error != null || terminal?.committed != true || terminal.cancelled || terminal.error != null)
                        main.post { if (pendingMovePress === press) discardPendingMovePress() }
                } }
            }, { current() && pendingMovePress === press && moveInputEpoch == press.epoch &&
                toolMode == CanvasToolMode.MOVE && moveViewportVersion == press.viewportVersion &&
                editor.selection().instance == press.instance }, ::launch)
            if (!submitted) discardPendingMovePress()
        }
        return true
    }
    private fun pendingMovePointer(event: MotionEvent): Boolean {
        pendingPersistentDuplicate?.let { wait ->
            if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                event.pointerCount != 1 || event.getPointerId(0) != wait.gesture.pointer ||
                event.downTime != wait.gesture.downTime || moveViewportVersion != wait.gesture.viewportVersion) {
                wait.cancelled = true; return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> { wait.lastPoint = point(event); wait.modifiers = event.metaState }
                MotionEvent.ACTION_UP -> wait.ended = true
            }
            return true
        }
        val press = pendingMovePress ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> { discardPendingMovePress(); return true }
            MotionEvent.ACTION_POINTER_DOWN -> { discardPendingMovePress(); return false }
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (event.pointerCount != 1 || event.getPointerId(0) != press.pointer || event.downTime != press.downTime ||
                    !event.x.isFinite() || !event.y.isFinite()) { discardPendingMovePress(); return true }
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    press.lastPoint = point(event); press.modifiers = event.metaState
                } else {
                    press.ended = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
                return true
            }
            else -> return true
        }
    }
    private fun updateMoveGesture(gesture: MoveGesture, pixel: MovePoint, modifiers: Int) {
        val owner = moveOwner ?: return
        if (gesture.duplicateAtDown) transforms.firstDrag(owner)
        val result = when (val handle = gesture.handle) {
            is MoveHandleMode.Resize -> MoveHandleGeometry.resizeSnapped(gesture.original,
                gesture.down, pixel, handle.index, moveLockRatio, modifiers and KeyEvent.META_SHIFT_MASK != 0,
                modifiers and KeyEvent.META_ALT_MASK != 0, modifiers and KeyEvent.META_CTRL_MASK == 0,
                gesture.targets, scale, resources.displayMetrics.density.toDouble(), group = moveOwner?.target == TransformTarget.GROUP)
            MoveHandleMode.Rotate -> MoveResult(MoveHandleGeometry.rotate(gesture.original,
                gesture.down, pixel, modifiers and KeyEvent.META_SHIFT_MASK != 0, group = moveOwner?.target == TransformTarget.GROUP))
            null -> MoveGeometry.drag(gesture.original, gesture.down, pixel,
                modifiers and KeyEvent.META_SHIFT_MASK != 0, modifiers and KeyEvent.META_CTRL_MASK == 0,
                gesture.targets, scale / resources.displayMetrics.density)
        }
        val sampling = if (gesture.duplicateAtDown) gesture.downSampling else moveSampling
        if ((if (owner.target == TransformTarget.GROUP) MoveGeometry.validGroup(result.box) else MoveGeometry.valid(result.box)) && transforms.update(owner, result.box, sampling)) {
            locallyUpdatedMove = true; moveBox = result.box; moveSnap = result
            moveAcceptedSequence = transforms.snapshot()?.takeIf { it.owner === owner }?.accepted?.seq ?: moveAcceptedSequence
            requestRender()
        }
    }
    fun moveOverlay(box: Transform64): MoveHandleOverlay {
        // Observe viewport changes independently of image pixels, including a pan at constant zoom.
        moveViewportVersion
        return MoveHandleGeometry.overlay(box, scale, MovePoint(offsetX, offsetY), resources.displayMetrics.density.toDouble())
    }
    private fun beginMoveGesture(event: MotionEvent): Boolean {
        if (moveSettling || moveGesture != null || moveContextPending || !event.x.isFinite() || !event.y.isFinite()) return false
        if (!moveInProgress) return beginAtomicMovePress(event)
        if (moveBox == null) return false
        if (!movePersistent) return false
        val state = editor.state()
        val layer = state.layers.firstOrNull { it.id == layerId }
        val geometry = selectedGeometry(layer, state) ?: return false
        val target = geometry.target
        if (moveOwner?.let { it.rootLayerId != layerId || it.target != target } == true) return false
        val box = moveBox ?: geometry.box
        val handle = if (moveShowControls || movePersistent) moveOverlay(box).hit(MovePoint(event.x.toDouble(), event.y.toDouble())) else null
        if ((event.metaState and KeyEvent.META_META_MASK != 0) && handle is MoveHandleMode.Resize) {
            failNotice(UiNoticeKey.DISTORT_UNAVAILABLE); return false
        }
        if (!moveInProgress && !beginMove()) return false
        moveGesture = MoveGesture(checkNotNull(moveBox), point(event),
            MoveGeometry.targets(state, layerId, editor.canvasWidth, editor.canvasHeight), handle,
            event.getPointerId(0), event.downTime, moveViewportVersion,
            handle == null && (moveDuplicateArmed || event.metaState and KeyEvent.META_ALT_MASK != 0),
            event.metaState, moveSampling, canvasScreenOrigin())
        return true
    }
    private fun beginPersistentDuplicate(gesture: MoveGesture, event: MotionEvent) {
        val previous = moveOwner ?: return
        val downEvent = MotionEvent.obtain(event).also {
            it.action = MotionEvent.ACTION_DOWN
            it.setLocation((offsetX + gesture.down.x * scale).toFloat(),
                (offsetY + gesture.down.y * scale).toFloat())
        }
        val wait = PendingPersistentDuplicate(gesture, editor.selection(), downEvent, point(event), event.metaState)
        pendingPersistentDuplicate = wait
        moveGesture = null; moveSettling = true
        transforms.finish(previous).whenComplete { terminal, error -> main.post {
            try {
                if (pendingPersistentDuplicate !== wait) return@post
                pendingPersistentDuplicate = null
                if (wait.cancelled || !current() || moveViewportVersion != gesture.viewportVersion ||
                    error != null || terminal?.committed != true || terminal.cancelled || terminal.error != null) return@post
                editor.serialized {
                    val selection = editor.selection()
                    if (selection.instance == wait.selection.instance &&
                        selection.primary == wait.selection.primary &&
                        selection.roots == wait.selection.roots && selection.mask == wait.selection.mask) {
                        beginAtomicMovePress(downEvent, forcedDuplicateAtDown = true, frozenGesture = gesture)
                        pendingMovePress?.let { press ->
                            press.lastPoint = wait.lastPoint; press.modifiers = wait.modifiers; press.ended = wait.ended
                        }
                    }
                }
            } finally { downEvent.recycle() }
        } }
    }
    fun editMoveField(field: MoveField, value: Double, session: MoveFieldSession? = null): Boolean {
        if (!current() || moveScrubSession != null || !validMoveFieldSession(session) || moveSettling || moveGesture != null || moveContextPending) return false
        if (moveInProgress && moveBox == null) return false
        val state = editor.state()
        val layer = state.layers.firstOrNull { it.id == layerId }
        val geometry = selectedGeometry(layer, state) ?: return false
        val target = geometry.target
        if (moveOwner?.let { it.rootLayerId != layerId || it.target != target } == true) return false
        val original = moveBox ?: geometry.box
        var updated = MoveGeometry.field(original, field, value, target == TransformTarget.GROUP) ?: return false
        if (moveLockRatio) updated = when (field) {
            MoveField.WIDTH -> updated.copy(height = original.height * value / original.width)
            MoveField.HEIGHT -> updated.copy(width = original.width * value / original.height)
            else -> updated
        }
        if (!(if (target == TransformTarget.GROUP) MoveGeometry.validGroup(updated) else MoveGeometry.valid(updated))) return false
        if (updated == original) return true
        return previewMoveInspector(updated, session)
    }
    private fun previewMoveInspector(updated: Transform64, session: MoveFieldSession? = null): Boolean {
        if (!moveInProgress && !beginMove(fromFields = true)) return false
        val owner = checkNotNull(moveOwner)
        val accepted = transforms.update(owner, updated, moveSampling)
        if (accepted) {
            session?.owner = owner
            locallyUpdatedMove = true; moveBox = updated; moveSnap = null
            moveAcceptedSequence = transforms.snapshot()?.takeIf { it.owner === owner }?.accepted?.seq ?: moveAcceptedSequence
            requestRender()
        }
        return accepted
    }
    fun editMoveScale(percent: Double, session: MoveFieldSession? = null): Boolean {
        if (!current() || moveScrubSession != null || !validMoveFieldSession(session) || moveSettling || moveGesture != null || moveContextPending) return false
        if (moveInProgress && moveBox == null) return false
        val state = editor.state()
        val layer = state.layers.firstOrNull { it.id == layerId }
        val geometry = selectedGeometry(layer, state) ?: return false
        val target = geometry.target
        if (moveOwner?.let { it.rootLayerId != layerId || it.target != target } == true) return false
        val original = moveBox ?: geometry.box
        val basis = moveScaleBasis ?: MoveInspectorGeometry.basis(checkNotNull(layer), target, geometry.box) ?: return false
        val updated = MoveInspectorGeometry.scaled(original, percent, basis) ?: return false
        if (updated == original) return true
        return previewMoveInspector(updated, session)
    }
    fun flipMove(horizontal: Boolean): Boolean {
        if (!moveCanFlip) return false
        if (moveOwner?.let { !matchesMoveTarget(it) } == true) return false
        // Finish the captured field even when focus notifications follow the button click.
        moveFieldSession?.let(::completeMoveField)
        val owner = moveOwner
        if (moveSettling) return owner != null && transforms.queueInspectorFlip(owner, horizontal)
        if (moveInProgress && moveBox == null) return false
        val state = editor.state()
        val layer = state.layers.firstOrNull { it.id == layerId }
        val geometry = selectedGeometry(layer, state) ?: return false
        val original = moveBox ?: geometry.box
        return previewMoveInspector(MoveInspectorGeometry.flipped(original, horizontal))
    }
    fun nudgeMove(dx: Double, dy: Double): Boolean {
        if (moveScrubSession != null || moveSettling || moveGesture != null || !dx.isFinite() || !dy.isFinite()) return false
        if (moveInProgress && moveBox == null) return false
        val alreadyEditing = moveInProgress
        if (!alreadyEditing && !beginMove()) return false
        val original = checkNotNull(moveBox)
        val updated = original.copy(x = original.x + dx, y = original.y + dy)
        if (!MoveGeometry.valid(updated)) { if (!alreadyEditing) cancelMove(); return false }
        if (!transforms.update(checkNotNull(moveOwner), updated, moveSampling)) { if (!alreadyEditing) cancelMove(); return false }
        locallyUpdatedMove = true; moveBox = updated; moveSnap = null
        if (!alreadyEditing) applyMove() else requestRender()
        return true
    }
    fun applyMove() {
        val owner = moveOwner ?: return
        if (moveSettling) return
        moveSettling = true; moveGesture = null; moveSnap = null
        if (owner.fromFields) transforms.finishInspectorChain(owner) else transforms.finish(owner)
    }
    /** Commit the last accepted draft before changing tool/layer/target, including persistent Transform. */
    fun switchMoveContext(apply: () -> Unit): Boolean {
        if (!current() || !canChangeTool) return false
        moveScrubSession?.let { finishMoveScrub(it, cancelled = it.waiting) }
        val owner = moveOwner
        val epoch = moveInputEpoch
        val root = layerId
        val mask = editingMask
        val mode = toolMode
        val finish = owner?.let { value -> {
            moveSettling = true; moveGesture = null; moveSnap = null
            if (value.fromFields) transforms.finishInspectorChain(value) else transforms.finish(value)
        } }
        return moveContextSwitch.submit(finish,
            { current() && moveInputEpoch == epoch && layerId == root && editingMask == mask && toolMode == mode }, apply)
    }
    fun cancelMove() {
        moveScrubSession?.let { finishMoveScrub(it, cancelled = true) }
        pendingPersistentDuplicate?.cancelled = true
        discardPendingMovePress()
        moveFieldSession?.completed = true; moveFieldSession = null
        moveContextSwitch.invalidate()
        val owner = moveOwner ?: return
        moveSettling = true; moveGesture = null; moveSnap = null
        transforms.cancel(owner)
    }
    internal var cloneSourceArmed by mutableStateOf(false)
        private set
    private var cloneSourceVersion by mutableStateOf(0L)
    private var clonePointer by mutableStateOf<CloneStampPoint?>(null)
    private var cloneStrokeForOverlay: CloneStampStroke? = null
    private var clonePickActive = false
    private var clonePickPointer = -1
    private var clonePickDown: Pair<Float, Float>? = null
    internal val cloneSamplePoint: CloneStampPoint?
        get() {
            cloneSourceVersion
            val cursor = clonePointer ?: return editor.cloneStamp.snapshot().source
            val active = cloneStrokeForOverlay?.takeIf { painting }
            return if (active != null) CloneStampPoint(cursor.x + active.offsetX, cursor.y + active.offsetY)
                else editor.cloneStamp.samplePoint(cursor.x, cursor.y)
        }
    internal fun armCloneSource() {
        if (!current() || strokeInProgress || nativeCommandPending || editor.hasPendingEdits) return
        cloneSourceArmed = !cloneSourceArmed
        clonePickActive = false; clonePickDown = null
    }
    internal fun cancelCloneSourcePick() {
        cloneSourceArmed = false; clonePickActive = false; clonePickDown = null
    }
    private fun cloneSourcePointer(event: MotionEvent): Boolean {
        clonePointer = CloneStampPoint((event.x - offsetX) / scale, (event.y - offsetY) / scale)
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (cloneSourceArmed || event.metaState and KeyEvent.META_ALT_MASK != 0)) {
            clonePickActive = true
            clonePickPointer = event.getPointerId(0)
            clonePickDown = event.x to event.y
            if (strokeInProgress || nativeCommandPending || editor.hasPendingEdits) {
                clonePickDown = null
                failNotice(UiNoticeKey.CLONE_SOURCE_PENDING)
            }
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (!clonePickActive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> clonePickDown?.let { down ->
                if (event.pointerCount != 1 || hypot((event.x - down.first).toDouble(), (event.y - down.second).toDouble()) >
                    android.view.ViewConfiguration.get(context).scaledTouchSlop) clonePickDown = null
            }
            MotionEvent.ACTION_POINTER_DOWN -> clonePickDown = null
            MotionEvent.ACTION_UP -> {
                val point = checkNotNull(clonePointer)
                if (clonePickDown != null && event.getPointerId(event.actionIndex) == clonePickPointer) {
                    if (point.x >= 0 && point.y >= 0 && point.x < editor.canvasWidth && point.y < editor.canvasHeight) {
                        editor.cloneStamp.setSource(point.x, point.y)
                        cloneSourceVersion++; cloneSourceArmed = false
                    } else failNotice(UiNoticeKey.CLONE_SOURCE_BOUNDS)
                }
                clonePickActive = false; clonePickDown = null
                parent?.requestDisallowInterceptTouchEvent(false); performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                clonePickActive = false; clonePickDown = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        requestRender()
        return true
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        PenInput.observe(event)
        if (current() && toolMode == CanvasToolMode.CLONE) {
            clonePointer = CloneStampPoint((event.x - offsetX) / scale, (event.y - offsetY) / scale)
        }
        return super.onHoverEvent(event)
    }
    private data class BrushTarget(val layerId: Long, val mask: Boolean, val clone: Boolean = false,
        val smear: Boolean = false, val healing: Boolean = false)
    private data class InputPoint(val x: Double, val y: Double)
    private var uiStrokeToken = 0L
    // These values belong to the native command fence, so a queued next DOWN sees all earlier results.
    private var strokeInput: StrokeInput? = null
    private var nativeStrokeToken = 0L
    private var failedNativeStrokeToken = -1L
    private var nativeStrokeTarget: BrushTarget? = null
    private var brushAxisAnchor: InputPoint? = null
    private var brushAxisHorizontal: Boolean? = null
    private var brushLastPixel: InputPoint? = null
    private var lastRawStrokeTime: Double? = null
    private var strokeStart = 0L
    @Volatile private var scale = 1.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var previousSpan = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var navigationStartX = 0f
    private var navigationStartY = 0f
    private var navigationMoved = false
    private var handPointer = false
    private var mouseZoomCursorActive = false
    private var mouseZoomPreviousCursor: PointerIcon? = null
    private val mouseWheel = MouseWheelInput({ scale }, { resources.displayMetrics.density.toDouble() },
        { value, x, y -> zoomViewportAt(value, x, y) }, { dx, dy ->
            val x = offsetX + dx; val y = offsetY + dy
            if (x.isFinite() && y.isFinite() && (x != offsetX || y != offsetY)) {
                offsetX = x; offsetY = y; viewport()
            }
        })
    private val mousePan = MousePanInput({ scale }, { resources.displayMetrics.density.toDouble() },
        { value, x, y -> zoomViewportAt(value, x, y) }, { zoomingIn ->
            if (zoomingIn == null) {
                if (mouseZoomCursorActive) pointerIcon = mouseZoomPreviousCursor
                mouseZoomCursorActive = false; mouseZoomPreviousCursor = null
            } else {
                if (!mouseZoomCursorActive) { mouseZoomPreviousCursor = pointerIcon; mouseZoomCursorActive = true }
                pointerIcon = PointerIcon.getSystemIcon(context, if (zoomingIn) PointerIcon.TYPE_ZOOM_IN else PointerIcon.TYPE_ZOOM_OUT)
            }
        }) { dx, dy ->
        val x = offsetX + dx; val y = offsetY + dy
        if (x.isFinite() && y.isFinite()) { offsetX = x; offsetY = y; viewport() }
    }
    @Volatile private var retired = false
    val zoomPercent: Double get() = scale * 100
    private fun onUi(action: () -> Unit) {
        if (!current()) return
        if (Looper.myLooper() == Looper.getMainLooper()) action() else post { if (current()) action() }
    }
    private fun current() = !retired && (lease?.isCurrent() ?: true)
    private fun deliver(action: () -> Unit) {
        if (retired) return
        if (lease != null) lease.execute { if (!retired) action() } else if (!retired) action()
    }
    private fun settleStroke(token: Long, stopPointer: Boolean = false) {
        strokeReservations.remove(token)?.let(editor::finishReservation)
        val settle = {
            pendingStrokeTokens.remove(token)
            nativeFinishPending = pendingStrokeTokens.isNotEmpty()
            if (stopPointer && uiStrokeToken == token) {
                painting = false; parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) settle() else main.post { settle() }
    }
    private fun rejectStrokes(owned: Map<Long, StrokeReservation>) {
        try { editor.cancelReservations(owned.values.toList()) }
        catch (error: Exception) { main.post { deliver { failNotice(UiNoticeKey.STROKE_CANCEL_FAILED, error) } } }
        finally { owned.keys.forEach { settleStroke(it, stopPointer = true) } }
    }
    private fun rejectStroke(token: Long) {
        val reservation = strokeReservations[token]
        if (reservation != null) rejectStrokes(mapOf(token to reservation))
        else settleStroke(token, stopPointer = true)
    }
    private fun requestDeferredRender() {
        post { if (current() && display.hasDeferredFrameReady()) requestRender() }
    }
    internal var previewWaiting by mutableStateOf(false)
        private set
    private fun displayWaitingChanged(@Suppress("UNUSED_PARAMETER") waiting: Boolean) {
        post { if (current()) previewWaiting = display.sceneAdmissionState().deferred }
    }
    private val display = GpuDisplayRenderer(editor, requestFrame = ::requestDeferredRender,
        waitingChanged = ::displayWaitingChanged) {
        message -> post { deliver { failNotice(UiNoticeKey.CANVAS_RENDER_FAILED, diagnostic = message) } }
    }
    internal fun debugDisplaySceneAdmission(): DisplaySceneAdmission.State = display.sceneAdmissionState()
    init {
        detachTransformObserver = transforms.attach(::transformChanged)
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            private var creationPending = false
            private var resizePending = false
            private var nextWidth = 0
            private var nextHeight = 0
            private fun prepare() {
                if (creationPending) { display.onSurfaceCreated(null, null); creationPending = false }
                if (resizePending) { display.onSurfaceChanged(null, nextWidth, nextHeight); resizePending = false }
            }
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                creationPending = true
                render { prepare() }
            }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                nextWidth = width; nextHeight = height; resizePending = true
                render { prepare() }
            }
            private fun render(drawCached: Boolean = false, action: () -> Unit) {
                if (retired) return
                val entered = editor.trySerialized {
                    val work = { if (!retired) editor.renderWithDisplayGrowth(action); Unit }
                    // The command lock is already owned, so lease revalidation is
                    // reentrant and cannot park GL behind a long native command.
                    if (lease != null) lease.execute(work) else work()
                }
                if (!entered && !retired) display.deferForCommands(drawCached && !creationPending && !resizePending)
            }
            override fun onDrawFrame(gl: GL10?) { render(drawCached = true) { prepare(); display.onDrawFrame(gl) } }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    fun command(command: EditorCommand): Boolean {
        if (hasAdjustmentDraft && command !is SubmitAdjustmentLayer) return false
        if (magicPending && command !is EditorCommand.MagicWand && command != EditorCommand.ClearSelection) return false
        if (moveInProgress || moveContextPending) { onUi { failNotice(UiNoticeKey.TRANSFORM_PENDING) }; return false }
        if (samplingFinishPending) { onUi { failNotice(UiNoticeKey.PENDING_EDIT) }; return false }
        cancelSampling()
        return submit { captured -> editor.commandDispatcher.submit(command, lease, captured) }
    }
    /** Instrumentation fixtures can count execution; production submits immutable command values. */
    fun command(action: () -> Unit): Boolean {
        if (hasAdjustmentDraft) return false
        if (magicPending) return false
        if (moveInProgress || moveContextPending) { onUi { failNotice(UiNoticeKey.TRANSFORM_PENDING) }; return false }
        if (samplingFinishPending) { onUi { failNotice(UiNoticeKey.PENDING_EDIT) }; return false }
        cancelSampling()
        return submit { captured -> editor.commandDispatcher.submitAction(lease, captured, action) }
    }
    private fun submit(action: (WorkspaceToken?) -> Boolean): Boolean {
        if (!current()) return false
        val captured = lease?.commandToken()
        if (lease != null && captured == null) return false
        var accepted = false
        if (lease == null) accepted = action(null)
        else lease.execute(checkNotNull(captured)) { accepted = action(captured) }
        if (accepted) nativeCommandPending = true
        else onUi { failNotice(UiNoticeKey.PENDING_EDIT) }
        return accepted
    }
    /** Samples change the rendered preview, not committed layer/history metadata. */
    private fun previewCommand(action: () -> Unit) = enqueueCommand(publishState = false, action)
    private fun enqueueCommand(publishState: Boolean, action: () -> Unit, shouldRun: () -> Boolean = { true },
        rejected: () -> Unit = {}) {
        val captured = lease?.commandToken()
        if (lease != null && captured == null) { rejected(); return }
        queueEvent {
            if (!current()) { rejected(); return@queueEvent }
            var state: EditorState? = null
            var failure: Exception? = null
            try {
                val work = { if (!retired && shouldRun()) {
                    try {
                        action()
                        if (publishState) state = editor.state()
                    } catch (error: Exception) {
                        failure = error
                        // samples() aborts a failed stroke. Refresh within the same command fence.
                        try { state = editor.state() } catch (refresh: Exception) { error.addSuppressed(refresh) }
                    }
                } else { rejected() } }
                if (lease != null) {
                    if (!lease.execute(checkNotNull(captured), work)) rejected()
                } else editor.serialized(work)
            } catch (error: Exception) { failure = error; rejected() }
            val value = state; val error = failure
            if (value != null || error != null) post {
                val delivery = {
                    if (!retired) {
                        if (value != null) changed(editor.state())
                        if (error != null) failNotice(UiNoticeKey.EDITOR_COMMAND_FAILED, error)
                    }
                }
                if (lease != null) lease.execute(checkNotNull(captured), delivery) else deliver(delivery)
            }
            requestRender()
        }
        requestRender()
    }
    private fun strokeCommand(token: Long, publishState: Boolean, begin: Boolean = false, action: () -> Unit) {
        enqueueCommand(publishState, {
            try {
                val reservation = strokeReservations[token]
                if (reservation == null || !editor.withStrokeReservation(reservation, begin, action))
                    rejectStroke(token)
            }
            catch (error: Exception) {
                failedNativeStrokeToken = token
                strokeInput?.cancel(); strokeInput = null; nativeStrokeTarget = null
                clearBrushAxis()
                try { strokeReservations[token]?.let { editor.cancelReservations(listOf(it)) } }
                catch (cancel: Exception) { error.addSuppressed(cancel) }
                settleStroke(token, stopPointer = true)
                throw error
            }
        }, shouldRun = { strokeReservations[token]?.finished?.isDone == false &&
            (begin || (nativeStrokeToken == token && failedNativeStrokeToken != token)) },
            rejected = { rejectStroke(token) })
    }
    private fun clearBrushAxis() {
        brushAxisAnchor = null; brushAxisHorizontal = null; brushLastPixel = null
        lastRawStrokeTime = null
    }
    private fun validateRawSample(sample: StrokeInputSample) {
        require(sample.x.isFinite() && sample.y.isFinite() && sample.pressure.isFinite() &&
            sample.pressure in 0.0..1.0 && sample.time.isFinite() && sample.time >= 0 &&
            (lastRawStrokeTime == null || sample.time >= checkNotNull(lastRawStrokeTime))) { "Invalid stroke input" }
        lastRawStrokeTime = sample.time
    }
    private fun constrained(sample: StrokeInputSample, shift: Boolean): StrokeInputSample {
        if (!shift) {
            brushAxisAnchor = null; brushAxisHorizontal = null
            brushLastPixel = InputPoint(sample.x, sample.y)
            return sample
        }
        val anchor = brushAxisAnchor ?: brushLastPixel ?: InputPoint(sample.x, sample.y)
        if (brushAxisAnchor == null) { brushAxisAnchor = anchor; brushAxisHorizontal = null }
        val dx = sample.x - anchor.x; val dy = sample.y - anchor.y
        if (brushAxisHorizontal == null && hypot(dx, dy) >= 3)
            brushAxisHorizontal = abs(dx) >= abs(dy)
        val result = when (brushAxisHorizontal) {
            true -> sample.copy(y = anchor.y)
            false -> sample.copy(x = anchor.x)
            null -> sample.copy(x = anchor.x, y = anchor.y)
        }
        brushLastPixel = InputPoint(result.x, result.y)
        return result
    }
    fun handleMemoryPressure(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !moveRetainsDraft) cancelMove()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelSampling()
        val bytes = when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> 0
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 16 * 1024 * 1024
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 32 * 1024 * 1024
            else -> 64 * 1024 * 1024
        }
        queueEvent { display.setTextureBudget(bytes) }
        if (bytes > 0) requestRender()
    }
    fun restoreTextureBudget() {
        queueEvent { display.setTextureBudget(64 * 1024 * 1024) }
        requestRender()
    }
    internal fun debugGpuStats(callback: (GpuStats) -> Unit) = queueEvent {
        val hasContext = EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT
        // Instrumentation alone fences completed work; interactive rendering never calls glFinish.
        if (hasContext) GLES30.glFinish()
        callback(GpuStats(display.cacheBytes, display.surfaceBytes, display.textureDeleteCount, display.uploadCount,
            hasContext, display.completedFrameNumber,
            display.completedSceneGeneration, display.completedFrameUsesTiles, display.completedPreviewToken))
    }
    internal fun eyedropperStats(): EyedropperStats = eyedropper.stats()
    internal fun eyedropperUiStats(): Pair<Long, Int> = synchronized(sampleDeliveryGate) {
        sampleUiDeliveries to maximumSampleUiCallbacks
    }
    private fun scheduleSampleDelivery() {
        if (!sampleDeliveryScheduled) {
            sampleDeliveryScheduled = true; maximumSampleUiCallbacks = maxOf(maximumSampleUiCallbacks, 1)
            main.post {
                val value = synchronized(sampleDeliveryGate) {
                    pendingSampleDelivery.also { pendingSampleDelivery = null }
                }
                if (value != null) deliver {
                    if (samplingToken == value.token && eyedropper.isCurrent(value.token)) {
                        if (runCatching { editor.currentRevision() }.getOrNull() != samplingRevision) cancelSampling()
                        else {
                            if (value.x.isFinite() && value.y.isFinite()) { samplingPointX = value.x; samplingPointY = value.y }
                            if (value.color != null) {
                                foregroundColor = value.color
                                // DOWN consumes the accepted palette directly, without waiting for Compose recomposition.
                                strokeSettings = strokeSettings.copy(red = ((value.color ushr 16) and 255) / 255f,
                                    green = ((value.color ushr 8) and 255) / 255f, blue = (value.color and 255) / 255f)
                                sampledColor?.invoke(value.color)
                            }
                            if (value.finished) samplingFinishPending = false
                            updateSampleRing()
                            synchronized(sampleDeliveryGate) { sampleUiDeliveries++ }
                        }
                    }
                }
                synchronized(sampleDeliveryGate) {
                    sampleDeliveryScheduled = false
                    if (pendingSampleDelivery != null) scheduleSampleDelivery()
                }
            }
        }
    }
    private fun updateSampleRing() {
        sampleRing = if (samplingPointer && showsSampleRing)
            SampleRingState(samplingPointX, samplingPointY, samplingOriginal, foregroundColor) else null
    }
    fun cancelSampling() {
        levelsSampleDown = null; levelsSamplePointer = -1
        samplingPointer = false; samplingFinishPending = false; sampleRing = null
        eyedropper.cancel()
        synchronized(sampleDeliveryGate) { pendingSampleDelivery = null }
    }
    private fun beginSampling(event: MotionEvent) {
        samplingOriginal = foregroundColor; samplingRevision = editor.currentRevision()
        samplingPointX = event.x; samplingPointY = event.y; samplingPointer = true
        samplingToken = eyedropper.begin((event.x.toDouble() - offsetX) / scale,
            (event.y.toDouble() - offsetY) / scale, event.x, event.y)
        updateSampleRing()
    }
    fun cancelStroke(cancelTransform: Boolean = true) {
        if (cancelTransform) cancelMove()
        cancelSampling()
        painting = false
        val owned = strokeReservations.toMap()
        enqueueCommand(publishState = true, action = {
            failedNativeStrokeToken = nativeStrokeToken
            strokeInput?.cancel(); strokeInput = null; nativeStrokeTarget = null
            clearBrushAxis()
            try { editor.cancelReservations(owned.values.toList()) }
            finally { owned.keys.forEach { settleStroke(it) } }
        }, rejected = { rejectStrokes(owned) })
    }

    /** Start one real P55 worker request from the selected layer's current quad. */
    fun startDistortSession(): Boolean {
        if (!current() || distortSessionActive || moveInProgress || strokeInProgress || nativeCommandPending) return false
        val state = editor.state()
        val selected = state.layers.firstOrNull { it.id == layerId && it.id == state.document.activeLayerId }
        val geometry = selectedGeometry(selected, state)
        if (geometry == null) { failNotice(UiNoticeKey.DISTORT_SELECT_LAYER); return false }
        val session = try { editor.beginDistortSession(layerId) } catch (error: Throwable) {
            failNotice(UiNoticeKey.DISTORT_FAILED, error); return false
        }
        val box = geometry.box
        val corners = listOf(0, 2, 4, 6).flatMap { index ->
            val point = MoveHandleGeometry.point(box, MoveHandleGeometry.units[index])
            listOf(point.x, point.y)
        }.toDoubleArray()
        distortCorners = List(4) { DistortPoint(corners[it * 2], corners[it * 2 + 1]) }
        val status = runCatching { session.submitQuad(corners) }.getOrElse {
            session.cancel(); failNotice(UiNoticeKey.DISTORT_FAILED, it); return false
        }
        if (status != 0) {
            session.cancel(); failNotice(UiNoticeKey.DISTORT_FAILED, diagnostic = "Distort worker submission failed ($status)"); return false
        }
        distortSession = session; distortSessionActive = true; distortSessionReady = false
        val attempt = ++distortAttempt
        Thread {
            val wait = runCatching { session.wait(15_000L) }.getOrElse { NativeDistortSessionBridge.WAIT_CLOSED }
            main.post {
                if (!current() || distortSession !== session || attempt != distortAttempt) return@post
                if (wait == NativeDistortSessionBridge.WAIT_READY) {
                    val published = runCatching { session.publish() }.getOrElse { -1 }
                    if (published == 0) {
                        distortSessionReady = true
                        publishDistortPreview(session, attempt)
                    }
                    else { failNotice(UiNoticeKey.DISTORT_FAILED, diagnostic = "Distort preview publish failed ($published)"); cancelDistortSession() }
                } else {
                    failNotice(UiNoticeKey.DISTORT_FAILED, diagnostic = "Distort worker stopped ($wait)"); cancelDistortSession()
                }
            }
        }.apply { name = "Compositor-Distort-Wait"; isDaemon = true }.start()
        return true
    }

    /** Render the accepted quad at full resolution off the UI thread before P54 publication. */
    fun applyDistortSession(): Boolean {
        val session = distortSession ?: return false
        if (!distortSessionReady || distortFinalizing) return false
        distortSessionReady = false; distortFinalizing = true
        val status = runCatching { session.submitFinalQuad() }.getOrElse { -1 }
        if (status != 0) {
            distortFinalizing = false; distortSessionReady = true
            failNotice(UiNoticeKey.DISTORT_FAILED, diagnostic = "Distort final submission failed ($status)")
            return false
        }
        val attempt = ++distortAttempt
        Thread {
            val wait = runCatching { session.wait(15_000L) }.getOrElse { NativeDistortSessionBridge.WAIT_CLOSED }
            main.post {
                if (!current() || distortSession !== session || attempt != distortAttempt) return@post
                distortFinalizing = false
                val published = if (wait == NativeDistortSessionBridge.WAIT_READY)
                    runCatching { session.publish() }.getOrElse { -1 } else -1
                if (published != 0) {
                    // Keep the previous displayed preview and allow another Apply or corner drag.
                    distortSessionReady = true
                    failNotice(UiNoticeKey.DISTORT_FAILED,
                        diagnostic = "Distort final render failed (wait=$wait, publish=$published); retry Apply or adjust a corner")
                    return@post
                }
                distortSessionReady = true
                commitPublishedDistortSession(session)
            }
        }.apply { name = "Compositor-Distort-Final"; isDaemon = true }.start()
        return true
    }

    private fun commitPublishedDistortSession(session: NativeDistortSession): Boolean = try {
        session.apply()
        session.seal()
        // Retire the GL snapshot only after commit and native worker
        // teardown succeed; a recoverable Apply failure keeps the preview
        // available while the same session remains active.
        clearDistortPreview()
        distortSession = null; distortSessionActive = false; distortSessionReady = false; distortCorners = emptyList()
        changed(editor.state()); requestRender(); true
    } catch (error: Throwable) {
        failNotice(UiNoticeKey.DISTORT_COMMIT_UNRESOLVED, error); false
    }

    fun cancelDistortSession() {
        val session = distortSession ?: return
        // Keep the session reachable until native cancellation and any P54
        // recovery have completed. Clearing it first would strand an
        // unresolved commit owner and leak its budget claim.
        val failure = runCatching { session.cancel() }.exceptionOrNull()
        if (failure != null) {
            failNotice(UiNoticeKey.DISTORT_CANCEL_FAILED, failure)
            requestRender()
            return
        }
        distortAttempt++
        clearDistortPreview()
        distortSession = null; distortSessionActive = false; distortSessionReady = false; distortFinalizing = false
        distortCorners = emptyList(); distortPointerIndex = -1
        requestRender()
    }
    private fun releasePersistentGesture() {
        moveGesture?.let { gesture ->
            if (transforms.update(checkNotNull(moveOwner), gesture.original, moveSampling)) {
                locallyUpdatedMove = true; moveBox = gesture.original
            }
        }
        moveGesture = null; moveSnap = null; requestRender()
    }
    /** Retained commands own their cleanup; do not queue an empty view edit behind them. */
    private fun retainedCommandWithoutViewEdit(): Boolean =
        (magicPending || nativeCommandPending || editor.hasPendingEdits) &&
            !strokeInProgress && strokeReservations.isEmpty() && pendingStrokeTokens.isEmpty() &&
            !samplingFinishPending && !moveInProgress && !moveContextPending && !moveSettling &&
            moveScrubSession == null && moveFieldSession == null && moveGesture == null &&
            pendingMovePress == null && pendingPersistentDuplicate == null &&
            !distortSessionActive && distortSession == null &&
            cropRect == null && shapeDraft == null && gradientDraft == null

    /** Transient pointers end on pause; an explicit Transform belongs to the retained document. */
    fun pauseInput() {
        cancelCloneSourcePick()
        if (retainedCommandWithoutViewEdit()) {
            magicCancellation?.cancel()
            magicPointerId = null; magicDown = null
            handPointer = false; mousePan.reset()
            cancelSampling()
            parent?.requestDisallowInterceptTouchEvent(false)
            return
        }
        cancelCrop()
        cancelShape()
        applyGradient()
        cancelDistortSession()
        moveScrubSession?.let { finishMoveScrub(it, cancelled = true) }
        pendingPersistentDuplicate?.cancelled = true
        discardPendingMovePress()
        moveFieldSession?.completed = true; moveFieldSession = null
        handPointer = false; mousePan.reset()
        if (movePersistent && !moveSettling) releasePersistentGesture()
        cancelStroke(cancelTransform = !moveRetainsDraft)
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onResume() {
        super.onResume()
        if (current()) requestRender()
    }
    /** Queued strokes observe retirement; accepted ordinary edits remain owned by the document. */
    fun retire() {
        if (retired) return
        // An invalidated ordinary context request must not publish a view-owned edit.
        // A captured canvas press has already requested its separate Inspector commit.
        if (moveContextPending && pendingMovePress == null && !movePersistent) cancelMove()
        val retainedOnly = retainedCommandWithoutViewEdit()
        magicCancellation?.cancel()
        mousePan.reset()
        pauseInput()
        cancelDistortSession()
        synchronized(adjustmentPreviewGate) {
            adjustmentPreviewToken++
            adjustmentPreviewPending = null
        }
        val adjustmentLease = adjustmentPreviewLease
        adjustmentPreviewLease = null
        hasAdjustmentDraft = false
        adjustmentLease?.retire()
        queueEvent { display.retirePreviewScene(adjustmentLease) }
        moveContextSwitch.invalidate()
        detachTransformObserver?.invoke(); detachTransformObserver = null
        transformShortcut = null
        cancelSampling()
        retired = true; painting = false
        display.retireSceneAdmission()
        previewWaiting = false
        detachCommands()
        nativeCommandPending = false
        magicPending = false
        val clearPending = { pendingStrokeTokens.clear(); nativeFinishPending = false }
        if (Looper.myLooper() == Looper.getMainLooper()) clearPending() else main.post { clearPending() }
        // Active view edits still need their ownership cleanup. A retained command
        // with no view edit must not make Activity retirement await the editor gate.
        if (!retainedOnly) editor.serialized {
            failedNativeStrokeToken = nativeStrokeToken
            strokeInput?.cancel(); strokeInput = null; nativeStrokeTarget = null
            clearBrushAxis()
            rejectStrokes(strokeReservations.toMap())
        }
    }
    fun fit() = onUi {
        if (pendingMovePress != null) return@onUi
        mousePan.resetNavigation()
        if (moveGesture != null) cancelMove()
        cancelSampling()
        if (width == 0 || height == 0) return@onUi
        if (painting) cancelStroke()
        scale = (min(width.toDouble() / editor.canvasWidth, height.toDouble() / editor.canvasHeight) * 0.92)
            .coerceIn(0.001, 32.0)
        offsetX = (width - editor.canvasWidth * scale) / 2
        offsetY = (height - editor.canvasHeight * scale) / 2
        viewport()
    }
    fun setZoomPercent(value: Double) {
        require(value.isFinite() && value in 0.1..3200.0) { "Zoom must be between 0.1% and 3200%" }
        onUi { zoomAt(value / 100, width / 2.0, height / 2.0) }
    }
    fun actualPixels() = setZoomPercent(100.0)
    fun zoomBy(factor: Double) {
        require(factor.isFinite() && factor > 0) { "Zoom factor must be positive" }
        onUi { zoomAt((scale * factor).coerceIn(0.001, 32.0), width / 2.0, height / 2.0) }
    }
    private fun zoomAt(value: Double, x: Double, y: Double) {
        if (pendingMovePress != null) return
        mousePan.resetNavigation()
        if (moveGesture != null) cancelMove()
        cancelSampling()
        if (painting) cancelStroke()
        zoomViewportAt(value, x, y)
    }
    /** Viewport math only: mouse navigation must not settle or cancel a pending document draft. */
    private fun zoomViewportAt(value: Double, x: Double, y: Double) {
        val next = value.coerceIn(0.001, 32.0)
        if (!next.isFinite() || next == scale) return
        val factor = next / scale
        val translatedX = x - (x - offsetX) * factor
        val translatedY = y - (y - offsetY) * factor
        if (!next.isFinite() || !translatedX.isFinite() || !translatedY.isFinite()) return
        offsetX = translatedX; offsetY = translatedY
        scale = next; viewport()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pointerOrigin = pendingMovePress?.screenOrigin ?: moveGesture?.screenOrigin
            ?: moveScrubSession?.screenOrigin
            ?: pendingPersistentDuplicate?.takeUnless { it.cancelled }?.gesture?.screenOrigin
        if (oldw > 0 && oldh > 0 && h > 0 && h != oldh && w == oldw && pointerOrigin != null &&
            canvasScreenOrigin() == pointerOrigin) {
            // Hiding the IME can grow the surface during an Inspector-to-pointer handoff.
            // Keep content under the pointer while only the visible height changes.
            viewport(mappingChanged = false)
            return
        }
        discardPendingMovePress()
        if (oldw <= 0 || oldh <= 0) fit()
        else {
            // Opening a panel changes the viewport, not the user's zoom or canvas center.
            offsetX += (w - oldw) / 2.0
            offsetY += (h - oldh) / 2.0
            viewport()
        }
    }
    private fun canvasScreenOrigin(): MovePoint {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return MovePoint(location[0].toDouble(), location[1].toDouble())
    }
    private fun viewport(mappingChanged: Boolean = true) {
        if (mappingChanged) moveViewportVersion++
        val value = Viewport(-offsetX / scale, -offsetY / scale, width / scale, height / scale)
        queueEvent { display.viewport = value }
        val percent = zoomPercent
        post { deliver { viewportChanged?.invoke(percent) } }
        requestRender()
    }
    private fun append(event: MotionEvent, finish: Boolean = false) {
        val token = uiStrokeToken
        val count = event.historySize + 1
        val move = event.actionMasked == MotionEvent.ACTION_MOVE
        val shift = event.metaState and KeyEvent.META_SHIFT_MASK != 0
        val raw = ArrayList<StrokeInputSample>(count)
        for (index in 0 until count) {
            val history = index < event.historySize
            val x = if (history) event.getHistoricalX(0, index) else event.x
            val y = if (history) event.getHistoricalY(0, index) else event.y
            val pressure = if (history) event.getHistoricalPressure(0, index) else event.pressure
            val time = if (history) event.getHistoricalEventTime(index) else event.eventTime
            val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
            val normalizedPressure = normalizedPenPressure(pressure, stylus)
            val sample = StrokeInputSample((x.toDouble() - offsetX) / scale,
                (y.toDouble() - offsetY) / scale, normalizedPressure, (time - strokeStart) / 1000.0)
            raw.add(sample)
        }
        strokeCommand(token, publishState = false) {
            val input = strokeInput ?: return@strokeCommand
            val target = checkNotNull(nativeStrokeTarget)
            val accepted = ArrayList<StrokeInputSample>(count + 1)
            var lastAccepted: StrokeInputSample? = null
            try {
                raw.forEach { sample ->
                    validateRawSample(sample)
                    val point = if (move && !target.clone && !target.smear && !target.healing) constrained(sample, shift) else sample
                    input.append(point)?.let { accepted.add(it); lastAccepted = it }
                }
                if (finish) { input.finish()?.let(accepted::add); strokeInput = null }
            } catch (invalid: IllegalArgumentException) {
                // Preserve native validation/error delivery when possible, including malformed timestamps.
                editor.samples(sampleBatch(raw))
                throw IllegalStateException("Invalid stroke input", invalid)
            }
            if (accepted.isNotEmpty()) editor.samples(sampleBatch(accepted))
            // Mac keeps the final smoothed anchor here; finish's pointer catch-up does not replace it.
            if (!target.clone && !target.smear && !target.healing) lastAccepted?.let { editor.lastBrushCursor = BrushCursor(it.x, it.y, target.layerId, target.mask) }
        }
    }
    private fun sampleBatch(samples: List<StrokeInputSample>): DoubleArray {
        val batch = DoubleArray(samples.size * 4)
        samples.forEachIndexed { index, sample ->
            batch[index * 4] = sample.x; batch[index * 4 + 1] = sample.y
            batch[index * 4 + 2] = sample.pressure; batch[index * 4 + 3] = sample.time
        }
        return batch
    }
    private fun canPanMouse() = !strokeInProgress && !samplingPointer && !samplingFinishPending &&
        moveGesture == null && !moveSettling && !moveContextPending && !nativeCommandPending
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            levelsSampleDown = null; levelsSamplePointer = -1
            mousePan.resetNavigation()
            pendingPersistentDuplicate?.cancelled = true
            if (moveOwner?.duplicateAtDown == true) cancelMove()
            else if (moveGesture?.duplicateAtDown == true && movePersistent) releasePersistentGesture()
        }
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        PenInput.observe(event)
        if (!current()) return false
        if (mouseWheel.route(event, canPanMouse())) return true
        val result = mousePan.route(event, canPanMouse())
        if (result.finishPrimary) {
            val up = MotionEvent.obtain(event)
            try { up.action = MotionEvent.ACTION_UP; toolPointer(up) } finally { up.recycle() }
        }
        return result.consumed || super.onGenericMotionEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        PenInput.observe(event)
        if (BuildConfig.DEBUG && event.actionMasked == MotionEvent.ACTION_DOWN) {
            android.util.Log.d("CompositorInput", "down current=${current()} tool=$toolMode layer=$layerId pending=$nativeCommandPending " +
                "move=$moveInProgress settling=$moveSettling press=${pendingMovePress != null} duplicate=${pendingPersistentDuplicate != null} " +
                "sampling=$samplingFinishPending size=${width}x$height")
        }
        if (!current()) return false
        // Navigation owns the mouse sequence before any tool can consume its MOVE.
        if (mousePan.route(event, canPanMouse()).consumed) return true
        return toolPointer(event)
    }
    private fun toolPointer(event: MotionEvent): Boolean {
        if (routesAdjustmentPointer()) return levelsPointer(event)
        if (distortSessionActive) return distortPointer(event)
        if (toolMode == CanvasToolMode.CROP) return cropPointer(event)
        if (toolMode == CanvasToolMode.SHAPE) return shapePointer(event)
        if (toolMode == CanvasToolMode.GRADIENT) return gradientPointer(event)
        if (toolMode == CanvasToolMode.MARQUEE) return marqueePointer(event)
        if (toolMode == CanvasToolMode.LASSO) return lassoPointer(event)
        if (toolMode == CanvasToolMode.MAGIC) return magicPointer(event)
        if (toolMode == CanvasToolMode.CLONE && cloneSourcePointer(event)) return true
        return ordinaryPointer(event)
    }
    /** A touch tap samples once; cancelled taps and two-finger navigation never calibrate. */
    private fun levelsPointer(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                levelsSampleDown = if (event.pointerCount == 1) event.x to event.y else null
                levelsSamplePointer = event.getPointerId(0)
                levelsSampleViewport = moveViewportVersion
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                levelsSampleDown = null
                navigateLevels(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) navigateLevels(event)
                else levelsSampleDown?.let { down ->
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (event.getPointerId(0) != levelsSamplePointer ||
                        hypot(event.x - down.first, event.y - down.second) > slop) levelsSampleDown = null
                }
            }
            MotionEvent.ACTION_POINTER_UP -> navigateLevels(event)
            MotionEvent.ACTION_UP -> {
                val down = levelsSampleDown
                levelsSampleDown = null
                if (down != null && event.pointerCount == 1 && event.getPointerId(0) == levelsSamplePointer &&
                    levelsSampleViewport == moveViewportVersion) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (hypot(event.x - down.first, event.y - down.second) <= slop) {
                        levelsSamplePoint?.let { sample ->
                            levelsSampleDeliveries++
                            sample((event.x.toDouble() - offsetX) / scale,
                                (event.y.toDouble() - offsetY) / scale)
                        }
                    }
                }
                previousSpan = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                levelsSampleDown = null; previousSpan = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    private fun navigateLevels(event: MotionEvent) {
        routingLevelsNavigation = true
        try { ordinaryPointer(event) } finally { routingLevelsNavigation = false }
    }
    private fun distortScreen(point: DistortPoint): Pair<Float, Float> =
        (offsetX + point.x * scale).toFloat() to (offsetY + point.y * scale).toFloat()
    private fun nearestDistortCorner(x: Float, y: Float): Int {
        val radius = 36f * resources.displayMetrics.density
        var best = -1; var bestDistance = radius * radius
        distortCorners.forEachIndexed { index, point ->
            val screen = distortScreen(point); val dx = x - screen.first; val dy = y - screen.second
            val distance = dx * dx + dy * dy
            if (distance <= bestDistance) { best = index; bestDistance = distance }
        }
        return best
    }
    private fun distortPointer(event: MotionEvent): Boolean {
        if (distortFinalizing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                distortPointerIndex = if (event.pointerCount == 1) nearestDistortCorner(event.x, event.y) else -1
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (distortPointerIndex >= 0 && event.pointerCount == 1) {
                val next = distortCorners.toMutableList()
                val previous = next[distortPointerIndex]
                val moved = DistortPoint(
                    ((event.x - offsetX) / scale).coerceIn(0.0, editor.canvasWidth.toDouble()),
                    ((event.y - offsetY) / scale).coerceIn(0.0, editor.canvasHeight.toDouble()))
                next[distortPointerIndex] = moved
                if (!distortFreeCorners) {
                    // Perspective mode keeps the opposite edge's axis aligned while the
                    // dragged corner changes its two adjacent edges (PhotoCraft behavior).
                    val dx = moved.x - previous.x; val dy = moved.y - previous.y
                    when (distortPointerIndex) {
                        0 -> { next[1] = next[1].copy(y = next[1].y + dy); next[3] = next[3].copy(x = next[3].x + dx) }
                        1 -> { next[0] = next[0].copy(y = next[0].y + dy); next[2] = next[2].copy(x = next[2].x + dx) }
                        2 -> { next[3] = next[3].copy(y = next[3].y + dy); next[1] = next[1].copy(x = next[1].x + dx) }
                        3 -> { next[2] = next[2].copy(y = next[2].y + dy); next[0] = next[0].copy(x = next[0].x + dx) }
                    }
                    next.indices.forEach { index -> next[index] = next[index].copy(
                        x = next[index].x.coerceIn(0.0, editor.canvasWidth.toDouble()),
                        y = next[index].y.coerceIn(0.0, editor.canvasHeight.toDouble())) }
                }
                distortCorners = next
                submitDistortPreview()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                distortPointerIndex = -1; parent?.requestDisallowInterceptTouchEvent(false); performClick()
            }
        }
        return true
    }
    private fun beginCrop() {
        val selection = editor.pixelSelection()
        val selectedBounds = if (selection.active) CropRect(kotlin.math.floor(selection.x), kotlin.math.floor(selection.y),
            kotlin.math.ceil(selection.x + selection.width), kotlin.math.ceil(selection.y + selection.height))
            .clamped(editor.canvasWidth, editor.canvasHeight) else null
        cropRect = selectedBounds?.takeIf { it.width >= 1.0 && it.height >= 1.0 }
            ?: CropRect(0.0, 0.0, editor.canvasWidth.toDouble(), editor.canvasHeight.toDouble())
        cropStart = null; cropHandle = CropHandle.NONE; requestRender()
    }
    internal fun changeCropRatio(choice: CropRatioChoice) {
        cropRatioChoice = choice
        val ratio = choice.value(editor.canvasWidth, editor.canvasHeight, cropCustomRatio) ?: return
        val old = cropRect ?: return
        val n = old.normalized()
        var w = n.width.coerceAtLeast(1.0)
        var h = w / ratio
        if (h > editor.canvasHeight) { h = editor.canvasHeight.toDouble(); w = h * ratio }
        if (w > editor.canvasWidth) { w = editor.canvasWidth.toDouble(); h = w / ratio }
        val left = (n.left + n.right - w) / 2.0
        val top = (n.top + n.bottom - h) / 2.0
        cropRect = CropRect(left, top, left + w, top + h).clamped(editor.canvasWidth, editor.canvasHeight)
        requestRender()
    }
    internal fun changeCustomCropRatio(value: Double?) {
        cropCustomRatio = value
        if (cropRatioChoice == CropRatioChoice.CUSTOM) changeCropRatio(CropRatioChoice.CUSTOM)
    }
    internal fun cancelCrop() {
        if (cropCommitPending) return
        cropStart = null; cropHandle = CropHandle.NONE; cropRect = null; requestRender()
    }
    internal fun applyCrop(): Boolean {
        if (cropCommitPending) return false
        val rect = cropRect?.snapped(editor.canvasWidth, editor.canvasHeight) ?: return false
        if (rect.width < 1.0 || rect.height < 1.0) return false
        cropRect = rect
        cropCommitPending = true
        val accepted = command(EditorCommand.Crop(rect.width.toInt(), rect.height.toInt(), -rect.left, -rect.top))
        if (accepted) {
            requestRender()
        } else cropCommitPending = false
        return accepted
    }
    private fun cropPoint(event: MotionEvent): Pair<Double, Double> =
        ((event.x - offsetX) / scale).coerceIn(0.0, editor.canvasWidth.toDouble()) to
            ((event.y - offsetY) / scale).coerceIn(0.0, editor.canvasHeight.toDouble())
    private fun snapCropRect(value: CropRect): CropRect {
        val n = value.normalized()
        val tolerance = 8.0 / scale.coerceAtLeast(.001)
        val dx = when {
            abs(n.left) <= tolerance -> -n.left
            abs(n.right - editor.canvasWidth) <= tolerance -> editor.canvasWidth - n.right
            else -> 0.0
        }
        val dy = when {
            abs(n.top) <= tolerance -> -n.top
            abs(n.bottom - editor.canvasHeight) <= tolerance -> editor.canvasHeight - n.bottom
            else -> 0.0
        }
        return n.translated(dx, dy, editor.canvasWidth, editor.canvasHeight)
    }
    private fun resizedCrop(old: CropRect, handle: CropHandle, point: Pair<Double, Double>,
        ratio: Double?, symmetric: Boolean): CropRect {
        val n = old.normalized()
        if (symmetric) {
            val centerX = (n.left + n.right) / 2.0
            val centerY = (n.top + n.bottom) / 2.0
            var halfW = abs(point.first - centerX)
            var halfH = abs(point.second - centerY)
            if (ratio != null) {
                if (halfW > halfH * ratio) halfH = halfW / ratio else halfW = halfH * ratio
            }
            return CropRect(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
        }
        if (ratio == null) return when (handle) {
            CropHandle.LEFT -> n.copy(left = point.first)
            CropHandle.RIGHT -> n.copy(right = point.first)
            CropHandle.TOP -> n.copy(top = point.second)
            CropHandle.BOTTOM -> n.copy(bottom = point.second)
            CropHandle.TOP_LEFT -> n.copy(left = point.first, top = point.second)
            CropHandle.TOP_RIGHT -> n.copy(right = point.first, top = point.second)
            CropHandle.BOTTOM_LEFT -> n.copy(left = point.first, bottom = point.second)
            CropHandle.BOTTOM_RIGHT -> n.copy(right = point.first, bottom = point.second)
            else -> n
        }
        val lockedRatio = checkNotNull(ratio)
        fun fromAnchor(anchorX: Double, anchorY: Double): CropRect {
            var w = abs(point.first - anchorX).coerceAtLeast(1.0)
            var h = abs(point.second - anchorY).coerceAtLeast(1.0)
            if (w > h * lockedRatio) h = w / lockedRatio else w = h * lockedRatio
            val left = if (point.first < anchorX) anchorX - w else anchorX
            val top = if (point.second < anchorY) anchorY - h else anchorY
            return CropRect(left, top, left + w, top + h)
        }
        return when (handle) {
            CropHandle.LEFT, CropHandle.RIGHT -> {
                val anchorX = if (handle == CropHandle.LEFT) n.right else n.left
                val w = abs(point.first - anchorX).coerceAtLeast(1.0)
                val h = w / lockedRatio
                val left = if (point.first < anchorX) anchorX - w else anchorX
                CropRect(left, (n.top + n.bottom - h) / 2.0, left + w, (n.top + n.bottom + h) / 2.0)
            }
            CropHandle.TOP, CropHandle.BOTTOM -> {
                val anchorY = if (handle == CropHandle.TOP) n.bottom else n.top
                val h = abs(point.second - anchorY).coerceAtLeast(1.0)
                val w = h * lockedRatio
                val top = if (point.second < anchorY) anchorY - h else anchorY
                CropRect((n.left + n.right - w) / 2.0, top, (n.left + n.right + w) / 2.0, top + h)
            }
            CropHandle.TOP_LEFT -> fromAnchor(n.right, n.bottom)
            CropHandle.TOP_RIGHT -> fromAnchor(n.left, n.bottom)
            CropHandle.BOTTOM_LEFT -> fromAnchor(n.right, n.top)
            CropHandle.BOTTOM_RIGHT -> fromAnchor(n.left, n.top)
            else -> n
        }
    }
    private fun cropPointer(event: MotionEvent): Boolean {
        if (cropCommitPending) return true
        val point = cropPoint(event)
        val radius = 28.0 / scale.coerceAtLeast(.001)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rect = cropRect ?: run { beginCrop(); cropRect!! }
                cropHandle = rect.hitHandle(point.first, point.second, radius)
                cropStart = point
                cropSymmetric = event.metaState and KeyEvent.META_ALT_MASK != 0
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val old = cropRect ?: return true
                val start = cropStart ?: return true
                val ratio = cropRatioChoice.value(editor.canvasWidth, editor.canvasHeight, cropCustomRatio)
                val next = when (cropHandle) {
                    CropHandle.NONE -> cropCreate(start, point, ratio, cropSymmetric)
                    CropHandle.BODY -> {
                        val dx = point.first - start.first; val dy = point.second - start.second
                        cropStart = point
                        old.translated(dx, dy, editor.canvasWidth, editor.canvasHeight)
                    }
                    CropHandle.LEFT, CropHandle.RIGHT, CropHandle.TOP, CropHandle.BOTTOM,
                    CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT ->
                        resizedCrop(old, cropHandle, point, ratio, cropSymmetric)
                }
                cropRect = snapCropRect(next.clamped(editor.canvasWidth, editor.canvasHeight)); requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cropStart = null; cropHandle = CropHandle.NONE; cropSymmetric = false
                parent?.requestDisallowInterceptTouchEvent(false); performClick()
            }
        }
        return true
    }
    private fun marqueePoint(event: MotionEvent): Pair<Double, Double> =
        ((event.x - offsetX) / scale).coerceIn(0.0, editor.canvasWidth.toDouble()) to
            ((event.y - offsetY) / scale).coerceIn(0.0, editor.canvasHeight.toDouble())
    private fun cancelMarqueeDraft() {
        marqueeStart = null; marqueeDraft = null; marqueeGestureShape = marqueeShape
        marqueeGestureOperation = marqueeOperation; requestRender()
    }
    internal fun clearPixelSelection() {
        if (!current()) return
        cancelMarqueeDraft()
        cancelLassoDraft()
        if (magicPending) {
            if (magicCancellation?.cancel(clearSelection = true) == true)
                command(EditorCommand.ClearSelection)
        } else command(EditorCommand.ClearSelection)
        requestRender()
    }
    internal fun fillPixelSelection(color: Int): Boolean {
        if (!current() || pixelSelection?.active != true) return false
        val accepted = command(EditorCommand.FillSelection(color))
        if (accepted) requestRender()
        return accepted
    }
    internal fun cutPixelSelection(): Boolean = fillPixelSelection(0)
    /** Applies a stamped session-only selection edge transform through the retained command gate. */
    internal fun modifyPixelSelection(operation: Int, amount: Int): Boolean {
        if (!current() || pixelSelection?.active != true) return false
        if (operation !in PixelSelectionAmountOperation.EXPAND..PixelSelectionAmountOperation.FEATHER) return false
        if (amount !in 1..PixelSelectionAmountOperation.maximum(operation)) return false
        val accepted = command(EditorCommand.ModifySelection(operation, amount))
        if (accepted) requestRender()
        return accepted
    }
    private fun marqueePointer(event: MotionEvent): Boolean {
        val point = marqueePoint(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                marqueeStart = point
                marqueeGestureShape = marqueeShape
                marqueeGestureOperation = selectionOperationFor(event)
                marqueeDraft = PixelSelectionRect(point.first, point.second, 0.0, 0.0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val start = marqueeStart ?: return true
                marqueeDraft = PixelSelectionRect(start.first, start.second,
                    point.first - start.first, point.second - start.second).normalized()
                requestRender()
            }
            MotionEvent.ACTION_UP -> {
                val draft = marqueeDraft?.normalized()
                val shape = marqueeGestureShape
                val operation = marqueeGestureOperation
                marqueeStart = null; marqueeDraft = null; marqueeGestureShape = marqueeShape
                marqueeGestureOperation = marqueeOperation
                if (draft != null && draft.width >= 1.0 && draft.height >= 1.0) {
                    try {
                        pixelSelection = if (operation == PixelSelectionOperation.REPLACE)
                            editor.setPixelSelection(draft, shape)
                        else editor.combinePixelSelection(draft, shape, operation)
                        pixelSelectionRegions = editor.pixelSelectionRegions().regions
                    }
                    catch (error: Exception) { failNotice(UiNoticeKey.SELECTION_FAILED, error) }
                }
                parent?.requestDisallowInterceptTouchEvent(false); performClick(); requestRender()
            }
            MotionEvent.ACTION_CANCEL -> { cancelMarqueeDraft(); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    private fun lassoPoint(event: MotionEvent): PixelSelectionPoint = PixelSelectionPoint(
        ((event.x - offsetX) / scale).coerceIn(0.0, editor.canvasWidth.toDouble()),
        ((event.y - offsetY) / scale).coerceIn(0.0, editor.canvasHeight.toDouble()))
    private fun appendLassoPoint(point: PixelSelectionPoint) {
        val current = lassoWorkingPoints
        val previous = current.lastOrNull()
        if (previous == null || hypot(point.x - previous.x, point.y - previous.y) >= 1.5 / scale.coerceAtLeast(.001)) {
            if (current.size < 4096) { current += point; lassoPreviewVersion++ }
        }
    }
    private fun lassoPointer(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lassoGestureOperation = selectionOperationFor(event)
                lassoWorkingPoints.clear(); lassoWorkingPoints += lassoPoint(event); lassoPreviewVersion++
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> appendLassoPoint(lassoPoint(event))
            MotionEvent.ACTION_UP -> {
                appendLassoPoint(lassoPoint(event))
                val points = lassoWorkingPoints.toList()
                val operation = lassoGestureOperation
                lassoWorkingPoints.clear(); lassoPreviewVersion++
                if (points.size >= 3) {
                    try {
                        pixelSelection = editor.combinePixelSelectionPolygon(points, operation)
                        pixelSelectionRegions = editor.pixelSelectionRegions().regions
                    } catch (error: Exception) { failNotice(UiNoticeKey.SELECTION_FAILED, error) }
                }
                lassoGestureOperation = marqueeOperation
                parent?.requestDisallowInterceptTouchEvent(false); performClick(); requestRender()
            }
            MotionEvent.ACTION_CANCEL -> { cancelLassoDraft(); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    private fun magicPoint(event: MotionEvent): Pair<Double, Double> =
        (event.x - offsetX) / scale to (event.y - offsetY) / scale
    /** Desktop captures the options-bar mode at the press; Option takes precedence over Shift. */
    private fun selectionOperationFor(event: MotionEvent): Int = when {
        event.metaState and KeyEvent.META_ALT_MASK != 0 -> PixelSelectionOperation.SUBTRACT
        event.metaState and KeyEvent.META_SHIFT_MASK != 0 -> PixelSelectionOperation.ADD
        else -> marqueeOperation
    }
    private fun magicPointer(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!magicPending && !nativeCommandPending) {
                    magicPointerId = event.getPointerId(0)
                    magicDown = event.x to event.y
                    magicGestureOperation = selectionOperationFor(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                magicPointerId = null; magicDown = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val down = magicDown
                if (down != null && hypot((event.x - down.first).toDouble(), (event.y - down.second).toDouble()) >
                    android.view.ViewConfiguration.get(context).scaledTouchSlop) {
                    magicPointerId = null; magicDown = null
                }
            }
            MotionEvent.ACTION_UP -> {
                val accepted = magicPointerId == event.getPointerId(event.actionIndex) && magicDown != null
                magicPointerId = null; magicDown = null
                parent?.requestDisallowInterceptTouchEvent(false)
                val point = magicPoint(event)
                if (accepted && point.first >= 0 && point.second >= 0 &&
                    point.first < editor.canvasWidth && point.second < editor.canvasHeight) {
                    performMagicWand(point.first, point.second, magicGestureOperation)
                    performClick()
                }
            }
        }
        return true
    }
    private fun performMagicWand(x: Double, y: Double, operation: Int) {
        if (!current() || magicPending || nativeCommandPending || strokeInProgress) return
        val expected = runCatching { editor.pixelSelection() }.getOrElse {
            failNotice(UiNoticeKey.SELECTION_FAILED, it); return
        }
        val cancellation = MagicWandCancellation()
        magicCancellation = cancellation
        magicPending = true
        if (!command(EditorCommand.MagicWand(x, y, magicSampleRadius, magicTolerance,
                magicContiguous, operation, expected, cancellation))) {
            magicCancellation = null; magicPending = false
        }
        requestRender()
    }
    internal fun cancelShape() {
        shapeDraft = null; shapePointerActive = false; requestRender()
    }
    private fun shapePoint(event: MotionEvent): Pair<Double, Double> =
        ((event.x - offsetX) / scale).coerceIn(0.0, editor.canvasWidth.toDouble()) to
            ((event.y - offsetY) / scale).coerceIn(0.0, editor.canvasHeight.toDouble())
    private fun shapePointer(event: MotionEvent): Boolean {
        val point = shapePoint(event)
        if (!point.first.isFinite() || !point.second.isFinite()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shapePointerActive = true
                val x = shapePixel(point.first); val y = shapePixel(point.second)
                shapeDraft = ShapeDraft(shapeKind, x, y, x, y, 0.0, 0.0, x, y,
                    shapeLineStyle, shapeLineCap, shapeCornerRadius)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (shapePointerActive) {
                val shift = event.metaState and KeyEvent.META_SHIFT_MASK != 0
                val option = event.metaState and KeyEvent.META_ALT_MASK != 0
                val draft = shapeDraft ?: return true
                shapeDraft = shapeDraft(draft.anchorX, draft.anchorY, point.first, point.second,
                    draft.kind, shift, option, shapeLineWidth, draft.lineStyle, draft.lineCap, draft.cornerRadius)
                requestRender()
            }
            MotionEvent.ACTION_UP -> if (shapePointerActive) {
                // The release can contain a newer position than the last batched MOVE.
                val shift = event.metaState and KeyEvent.META_SHIFT_MASK != 0
                val option = event.metaState and KeyEvent.META_ALT_MASK != 0
                val draft = shapeDraft?.let {
                    shapeDraft(it.anchorX, it.anchorY, point.first, point.second,
                        it.kind, shift, option, shapeLineWidth, it.lineStyle, it.lineCap, it.cornerRadius)
                }
                shapePointerActive = false; shapeDraft = null
                if (draft != null) {
                    val spec = draft.spec(shapeColor, draft.cornerRadius, shapeLineWidth)
                    if (spec != null) command(EditorCommand.CreateShape(spec, draft.x, draft.y))
                    else if (draft.width >= 1.0 && draft.height >= 1.0)
                        failNotice(UiNoticeKey.SHAPE_LIMIT)
                }
                parent?.requestDisallowInterceptTouchEvent(false); performClick(); requestRender()
            }
            MotionEvent.ACTION_CANCEL -> { cancelShape(); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    internal fun cancelGradient() {
        gradientDraft = null
        gradientPointerActive = false
        gradientPending = false
        parent?.requestDisallowInterceptTouchEvent(false)
        requestRender()
    }
    private fun gradientPoint(event: MotionEvent): Pair<Double, Double> =
        ((event.x - offsetX) / scale).coerceIn(0.0, editor.canvasWidth.toDouble()) to
            ((event.y - offsetY) / scale).coerceIn(0.0, editor.canvasHeight.toDouble())
    private fun gradientColor(value: Int): GradientColor = GradientColor(
        ((value ushr 16) and 255) / 255f,
        ((value ushr 8) and 255) / 255f,
        (value and 255) / 255f,
        ((value ushr 24) and 255) / 255f)
    private fun gradientSpec(draft: GradientDraft): GradientSpec = GradientSpec(
        settings = GradientSettings(gradientShape, gradientStyle, gradientReversed,
            gradientOpacity.toFloat().coerceIn(.01f, 1f),
            if (editingMask) GradientTarget.MASK else GradientTarget.PIXELS),
        draft = draft, width = editor.canvasWidth, height = editor.canvasHeight,
        foreground = gradientColor(gradientForeground), background = gradientColor(gradientBackground),
        layerId = layerId)
    internal fun applyGradient(): Boolean {
        if (!gradientPending) return true
        val draft = gradientDraft ?: run { gradientPending = false; return true }
        if (!draft.isMeaningful) { cancelGradient(); return true }
        val accepted = command(EditorCommand.CreateGradient(gradientSpec(draft)))
        if (accepted) {
            gradientDraft = null; gradientPending = false
            requestRender()
        }
        return accepted
    }
    private fun gradientPointer(event: MotionEvent): Boolean {
        val point = gradientPoint(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gradientPointerActive = true
                gradientDraft = GradientDraft(point.first, point.second, point.first, point.second)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (gradientPointerActive) {
                val draft = gradientDraft ?: return true
                var x = point.first; var y = point.second
                if (event.metaState and KeyEvent.META_SHIFT_MASK != 0) {
                    val dx = x - draft.startX; val dy = y - draft.startY
                    val length = hypot(dx, dy)
                    if (length > 0.0) {
                        val angle = kotlin.math.round(kotlin.math.atan2(dy, dx) / (Math.PI / 4.0)) * (Math.PI / 4.0)
                        x = draft.startX + length * kotlin.math.cos(angle)
                        y = draft.startY + length * kotlin.math.sin(angle)
                    }
                }
                gradientDraft = draft.moveEnd(x, y)
                requestRender()
            }
            MotionEvent.ACTION_UP -> if (gradientPointerActive) {
                val draft = gradientDraft
                gradientPointerActive = false
                gradientPending = draft?.isMeaningful == true
                if (draft != null && !draft.isMeaningful) { gradientDraft = null; gradientPending = false }
                parent?.requestDisallowInterceptTouchEvent(false); performClick(); requestRender()
            }
            MotionEvent.ACTION_CANCEL -> cancelGradient()
        }
        return true
    }
    private fun submitDistortPreview() {
        val session = distortSession ?: return
        val corners = distortCorners.flatMap { listOf(it.x, it.y) }.toDoubleArray()
        distortSessionReady = false
        val status = runCatching { session.submitQuad(corners) }.getOrElse { -1 }
        if (status != 0) { failNotice(UiNoticeKey.DISTORT_FAILED, diagnostic = "Distort preview update failed ($status)"); return }
        val attempt = ++distortAttempt
        Thread {
            val wait = runCatching { session.wait(15_000L) }.getOrElse { NativeDistortSessionBridge.WAIT_CLOSED }
            main.post {
                if (!current() || distortSession !== session || attempt != distortAttempt) return@post
                if (wait == NativeDistortSessionBridge.WAIT_READY) {
                    val published = runCatching { session.publish() }.getOrElse { -1 }
                    if (published == 0) { distortSessionReady = true; publishDistortPreview(session, attempt) }
                    else failNotice(UiNoticeKey.DISTORT_FAILED, diagnostic = "Distort preview publish failed ($published)")
                }
            }
        }.apply { name = "Compositor-Distort-Preview"; isDaemon = true }.start()
    }
    private fun publishDistortPreview(session: NativeDistortSession, attempt: Long) {
        val scene = runCatching { session.snapshot() }.getOrElse {
            failNotice(UiNoticeKey.DISTORT_FAILED, it); return
        }
        val lease = DistortPreviewLease(scene)
        val previous = distortPreviewLease
        distortPreviewLease = lease
        previous?.retire()
        queueEvent {
            previous?.let { display.retirePreviewScene(it) }
            if (current() && distortSession === session && attempt == distortAttempt &&
                distortPreviewLease === lease && lease.transferToRenderer()) display.setPreviewScene(lease)
            else {
                if (distortPreviewLease === lease) distortPreviewLease = null
                lease.retire()
            }
        }
        requestRender()
    }
    private fun clearDistortPreview() {
        distortAttempt++
        val lease = distortPreviewLease
        distortPreviewLease = null
        lease?.retire()
        queueEvent { display.retirePreviewScene(lease) }
    }
    /** Capture and present a read-only adjustment draft without touching document history. */
    internal fun updateAdjustmentPreview(draft: AdjustmentEditorDraft?) {
        val layer = draft?.layerId
        hasAdjustmentDraft = draft != null && layer != null && current()
        if (!hasAdjustmentDraft || draft == null || draft.baseRevision < 0) {
            adjustmentPreviewFailed = false
            synchronized(adjustmentPreviewGate) {
                adjustmentPreviewToken++
                adjustmentPreviewPending = null
            }
            val previous = adjustmentPreviewLease
            adjustmentPreviewLease = null
            previous?.retire()
            queueEvent { display.retirePreviewScene(previous) }
            requestRender()
            return
        }
        val schedule = synchronized(adjustmentPreviewGate) {
            val request = AdjustmentPreviewRequest(++adjustmentPreviewToken, draft)
            adjustmentPreviewPending = request
            if (adjustmentPreviewScheduled) false else { adjustmentPreviewScheduled = true; true }
        }
        if (schedule) main.postDelayed(::drainAdjustmentPreview, 16L)
    }
    private fun drainAdjustmentPreview() {
        val request = synchronized(adjustmentPreviewGate) {
            val next = adjustmentPreviewPending
            adjustmentPreviewPending = null
            next
        }
        if (request == null) {
            synchronized(adjustmentPreviewGate) { adjustmentPreviewScheduled = false }
            return
        }
        Thread {
            val scene = runCatching { editor.captureAdjustmentPreviewScene(request.draft.layerId!!,
                request.draft.style, request.draft.baseRevision) }.getOrElse {
                android.util.Log.d("CompositorCanvas", "Adjustment preview unavailable", it)
                null
            } ?: run {
                main.post {
                    if (current() && request.token == adjustmentPreviewToken) adjustmentPreviewFailed = true
                    finishAdjustmentPreviewRequest()
                }
                return@Thread
            }
            val lease = DistortPreviewLease(scene, request.token, request.draft.baseRevision)
            main.post {
                val currentRequest = synchronized(adjustmentPreviewGate) { request.token == adjustmentPreviewToken }
                if (current() && currentRequest) {
                    adjustmentPreviewFailed = false
                    val previous = adjustmentPreviewLease
                    adjustmentPreviewLease = lease
                    previous?.retire()
                    queueEvent {
                        previous?.let { display.retirePreviewScene(it) }
                        if (current() && request.token == adjustmentPreviewToken && lease.transferToRenderer()) display.setPreviewScene(lease)
                        else {
                            if (adjustmentPreviewLease === lease) adjustmentPreviewLease = null
                            lease.retire()
                        }
                    }
                    requestRender()
                } else lease.retire()
                finishAdjustmentPreviewRequest()
            }
        }.apply { name = "Compositor-Adjustment-Preview"; isDaemon = true }.start()
    }
    private fun finishAdjustmentPreviewRequest() {
        val again = synchronized(adjustmentPreviewGate) { adjustmentPreviewPending != null }
        if (again) main.postDelayed(::drainAdjustmentPreview, 16L)
        else synchronized(adjustmentPreviewGate) { adjustmentPreviewScheduled = false }
    }
    internal fun adjustmentPreviewTokenForTest(): Long = if (hasAdjustmentDraft) adjustmentPreviewToken else 0L
    internal fun adjustmentPreviewIdleForTest(): Boolean = synchronized(adjustmentPreviewGate) {
        !adjustmentPreviewScheduled && adjustmentPreviewPending == null
    }
    private fun ordinaryPointer(event: MotionEvent): Boolean {
        // Mouse-generated ordinary events must obey the same Levels editing fence.
        if (routesAdjustmentPointer() && !routingLevelsNavigation)
            return levelsPointer(event)
        // The Distort-specific pointer path is selected by onTouchEvent. Keep
        // this fallback guarded too, so a synthetic/mouse event cannot enter
        // Move or Brush and mutate the document behind the active session.
        if (distortSessionActive) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        if (pendingMovePointer(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (toolMode == CanvasToolMode.MOVE && moveFromFields && beginInspectorMovePress(event)) return true
                if (moveInProgress && (moveSettling || !movePersistent ||
                    (toolMode != CanvasToolMode.MOVE && toolMode != CanvasToolMode.HAND))) return true
                if (samplingFinishPending) { failNotice(UiNoticeKey.PENDING_EDIT); return true }
                if (painting) cancelStroke()
                parent?.requestDisallowInterceptTouchEvent(true)
                strokeStart = event.eventTime
                previousX = event.x; previousY = event.y
                navigationStartX = event.x; navigationStartY = event.y; navigationMoved = false
                handPointer = toolMode == CanvasToolMode.HAND
                val temporarySampling = toolMode == CanvasToolMode.BRUSH &&
                    event.metaState and KeyEvent.META_ALT_MASK != 0 && !strokeInProgress && !editor.hasActiveStrokes
                if (toolMode == CanvasToolMode.MOVE) {
                    if (!beginMoveGesture(event)) parent?.requestDisallowInterceptTouchEvent(false)
                } else if ((toolMode == CanvasToolMode.EYEDROPPER || temporarySampling) && !samplingEnabled) return true
                if ((toolMode == CanvasToolMode.EYEDROPPER || temporarySampling) && !nativeCommandPending) {
                    beginSampling(event)
                } else if (toolMode == CanvasToolMode.BRUSH || toolMode == CanvasToolMode.CLONE || toolMode == CanvasToolMode.SMEAR || toolMode == CanvasToolMode.HEALING) {
                    if (nativeCommandPending) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        failNotice(UiNoticeKey.PENDING_EDIT)
                        return true
                    }
                    val clone = toolMode == CanvasToolMode.CLONE
                    val smear = toolMode == CanvasToolMode.SMEAR
                    val healing = toolMode == CanvasToolMode.HEALING
                    val capturedHealingMode = healingMode
                    val healingSeed = if (healing) newHealingSeed() else 0L
                    val capturedSmearMode = smearMode
                    val id = layerId
                    val settings = if (clone || smear || healing) strokeSettings.copy(smoothing = 0f, pressureSize = false, pressureOpacity = false) else strokeSettings
                    val mask = editingMask
                    val pixel = InputPoint((event.x.toDouble() - offsetX) / scale, (event.y.toDouble() - offsetY) / scale)
                    if (clone && (!pixel.x.isFinite() || !pixel.y.isFinite())) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        failNotice(UiNoticeKey.CLONE_POSITION)
                        return true
                    }
                    val cloneStroke = if (clone) editor.cloneStamp.capture(pixel.x, pixel.y) else null
                    val refusal = when {
                        id == 0L -> UiNoticeKey.PAINT_SELECT_LAYER
                        clone && mask -> UiNoticeKey.CLONE_PIXELS_ONLY
                        smear && mask -> UiNoticeKey.SMEAR_MASK_UNSUPPORTED
                        healing && mask -> UiNoticeKey.HEALING_PIXELS_ONLY
                        clone && cloneStroke == null -> UiNoticeKey.CLONE_SOURCE_REQUIRED
                        else -> null
                    }
                    if (refusal != null) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        failNotice(refusal)
                        return true
                    }
                    var reservation: StrokeReservation? = null
                    val reserve = {
                        // Production strokes must match the current document selection before admission.
                        if (!retired && (lease == null || editor.state().document.activeLayerId == id))
                            reservation = editor.reserveStroke()
                        Unit
                    }
                    if (lease != null) lease.execute(reserve) else editor.serialized(reserve)
                    if (reservation == null) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        failNotice(UiNoticeKey.PENDING_EDIT, diagnostic = "Stroke admission rejected for layer $id")
                        return true
                    }
                    painting = true
                    val erase = erasing || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                    val token = ++uiStrokeToken
                    strokeReservations[token] = checkNotNull(reservation)
                    pendingStrokeTokens.add(token); nativeFinishPending = true
                    val zoom = scale
                    val target = BrushTarget(id, mask, clone, smear, healing)
                    val shift = !clone && !smear && !healing && event.metaState and KeyEvent.META_SHIFT_MASK != 0
                    cloneStrokeForOverlay = cloneStroke
                    val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                    val pressure = normalizedPenPressure(event.pressure, stylus)
                    strokeCommand(token, publishState = true, begin = true) {
                        val allowance = StrokeAdmissionPolicy.forContext(context, editor.retainedMemory())
                        if (cloneStroke != null) {
                            editor.startCloneStroke(id, settings, cloneStroke.offsetX, cloneStroke.offsetY,
                                cloneStroke.sampleAllLayers, allowance)
                            editor.cloneStamp.started(cloneStroke)
                            main.post { if (current()) cloneSourceVersion++ }
                        } else if (healing) editor.startHealingStroke(id, settings, capturedHealingMode, healingSeed, allowance)
                        else if (smear) editor.startSmearStroke(id, settings, capturedSmearMode, allowance)
                        else editor.startStroke(id, settings, erase, mask, allowance)
                        nativeStrokeToken = token; nativeStrokeTarget = target
                        val input = StrokeInput(settings, zoom)
                        strokeInput = input
                        brushAxisAnchor = if (shift) pixel else null
                        brushAxisHorizontal = null; brushLastPixel = pixel; lastRawStrokeTime = null
                        val previous = editor.lastBrushCursor?.takeIf { it.layerId == id && it.mask == mask && shift }
                        if (previous != null) {
                            val start = StrokeInputSample(previous.x, previous.y, pressure, 0.0)
                            input.append(start)
                            editor.samples(sampleBatch(listOf(start)))
                        }
                    }
                    append(event)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handPointer = false
                if (moveRetainsDraft) { if (!moveSettling) releasePersistentGesture() } else cancelMove()
                cancelSampling()
                if (painting) cancelStroke()
                navigationMoved = true
                if (event.pointerCount >= 2) {
                    previousSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    previousX = (event.getX(0) + event.getX(1)) / 2
                    previousY = (event.getY(0) + event.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val x = (event.getX(0) + event.getX(1)) / 2
                    val y = (event.getY(0) + event.getY(1)) / 2
                    val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    if (previousSpan > 0) {
                        val newScale = (scale * span / previousSpan).coerceIn(0.001, 32.0)
                        val factor = newScale / scale
                        offsetX = x - (previousX - offsetX) * factor
                        offsetY = y - (previousY - offsetY) * factor
                        scale = newScale; viewport()
                    }
                    previousX = x; previousY = y; previousSpan = span
                } else if (moveGesture != null && !moveSettling) {
                    val gesture = checkNotNull(moveGesture)
                    if (event.pointerCount != 1 || event.getPointerId(0) != gesture.pointer ||
                        event.downTime != gesture.downTime || moveViewportVersion != gesture.viewportVersion) cancelMove()
                    else if (gesture.duplicateAtDown && movePersistent) beginPersistentDuplicate(gesture, event)
                    else updateMoveGesture(gesture, point(event), event.metaState)
                } else if (samplingPointer) {
                    eyedropper.move(samplingToken, (event.x.toDouble() - offsetX) / scale,
                        (event.y.toDouble() - offsetY) / scale, event.x, event.y)
                } else if (painting) append(event)
                else if (handPointer) {
                    offsetX += event.x - previousX; offsetY += event.y - previousY
                    previousX = event.x; previousY = event.y; viewport()
                } else if (toolMode == CanvasToolMode.ZOOM) {
                    if (abs(event.y - navigationStartY) > 4 || abs(event.x - navigationStartX) > 4) navigationMoved = true
                    if (navigationMoved) zoomAt(scale * exp((previousY - event.y) / 160.0),
                        navigationStartX.toDouble(), navigationStartY.toDouble())
                    previousX = event.x; previousY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (moveGesture != null) {
                    val gesture = checkNotNull(moveGesture)
                    if (event.pointerCount != 1 || event.getPointerId(0) != gesture.pointer ||
                        event.downTime != gesture.downTime || moveViewportVersion != gesture.viewportVersion) {
                        cancelMove(); return true
                    }
                    // UP flushes the last accepted DRAG; its coordinate never recomputes the transform.
                    if (movePersistent) { moveGesture = null; moveSnap = null; requestRender() } else applyMove()
                } else if (samplingPointer) {
                    samplingPointer = false; samplingFinishPending = true; sampleRing = null
                    eyedropper.finish(samplingToken)
                } else if (painting) {
                    append(event, finish = true)
                    if (painting) {
                        painting = false
                        val token = uiStrokeToken
                        strokeCommand(token, publishState = true) {
                            editor.endReservedStroke(false); nativeStrokeTarget = null; clearBrushAxis()
                            settleStroke(token)
                        }
                    }
                }
                else if (toolMode == CanvasToolMode.ZOOM && !navigationMoved && previousSpan == 0f)
                    zoomAt(scale * if (zoomOut || event.metaState and KeyEvent.META_ALT_ON != 0) 0.5 else 2.0,
                        event.x.toDouble(), event.y.toDouble())
                previousSpan = 0f
                handPointer = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { handPointer = false; cancelMove(); cancelSampling(); cancelStroke(); previousSpan = 0f; parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_POINTER_UP -> {
                previousSpan = 0f
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) { previousX = event.getX(remaining); previousY = event.getY(remaining) }
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    companion object { private val nextViewEpoch = java.util.concurrent.atomic.AtomicLong() }
}

/** A field completion can only settle the document edit it actually changed. */
internal class MoveFieldSession internal constructor(val viewEpoch: Long,
    internal var owner: TransformController.Owner? = null) {
    internal var completed = false
}

internal data class Viewport(val x: Double, val y: Double, val width: Double, val height: Double) {
    constructor(x: Float, y: Float, width: Float, height: Float) : this(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
}
internal data class DistortPoint(val x: Double, val y: Double)
internal data class GpuStats(val bytes: Int, val surfaceBytes: Int, val deletes: Long, val uploads: Long,
    val currentContext: Boolean, val completedFrames: Long, val sceneGeneration: Long, val usesTiles: Boolean,
    val previewToken: Long = 0L)

/** CPU composition is a correctness reference. GLES only displays its viewport-sized output. */
internal class CpuReferenceRenderer(private val editor: NativeEditor, private val failed: (String) -> Unit) : GLSurfaceView.Renderer {
    var viewport = Viewport(0.0, 0.0, NativeEditor.SIZE.toDouble(), NativeEditor.SIZE.toDouble())
    private var pixels: ByteBuffer? = null
    private var width = 0
    private var height = 0
    private var texture = 0
    private var program = 0
    /** True only after the current texture storage was allocated successfully. */
    private var storageReady = false
    private var lastGeneration = -1L
    private var lastSceneIdentity = -1L
    private var lastViewport: Viewport? = null
    private var hasPixels = false
    val gpuBytes: Int get() = if (texture != 0) width * height * 4 else 0
    private val positions = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vertex = shader(GLES30.GL_VERTEX_SHADER, """
            #version 300 es
            layout(location=0) in vec2 position;
            layout(location=1) in vec2 uv;
            out vec2 coordinate;
            void main() { gl_Position=vec4(position,0,1); coordinate=uv; }
        """.trimIndent())
        val fragment = shader(GLES30.GL_FRAGMENT_SHADER, """
            #version 300 es
            precision highp float;
            in vec2 coordinate;
            uniform sampler2D image;
            out vec4 color;
            void main() {
                vec4 source=texture(image,coordinate);
                float checker=mod(floor(gl_FragCoord.x/16.0)+floor(gl_FragCoord.y/16.0),2.0);
                vec3 background=mix(vec3(0.19),vec3(0.24),checker);
                color=vec4(source.rgb+background*(1.0-source.a),1.0);
            }
        """.trimIndent())
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex); GLES30.glAttachShader(program, fragment); GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment)
        val status = IntArray(1); GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES30.glGetProgramInfoLog(program) }
        val textures = IntArray(1); GLES30.glGenTextures(1, textures, 0); texture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "CPU display texture creation failed" }
        // A new context has no texture storage, even when its surface size is unchanged.
        width = 0; height = 0; pixels = null; storageReady = false
        lastGeneration = -1; lastViewport = null; hasPixels = false
    }
    override fun onSurfaceChanged(gl: GL10?, surfaceWidth: Int, surfaceHeight: Int) {
        configureSurface(surfaceWidth, surfaceHeight, 4 * 1024 * 1024)
    }
    fun configureSurface(surfaceWidth: Int, surfaceHeight: Int, budgetBytes: Int) {
        check(budgetBytes >= 4) { "CPU display cannot fit one RGBA pixel" }
        check(surfaceWidth > 0 && surfaceHeight > 0) { "CPU display surface must have positive dimensions" }
        // Retain the usual 1024-long-edge quality, then scale down to the actual storage budget.
        val maximumPixels = budgetBytes / 4
        val ratio = minOf(1.0, 1024.0 / maxOf(surfaceWidth, surfaceHeight),
            sqrt(maximumPixels.toDouble() / (surfaceWidth.toDouble() * surfaceHeight)))
        var newWidth = maxOf(1, (surfaceWidth * ratio).toInt())
        var newHeight = maxOf(1, (surfaceHeight * ratio).toInt())
        // Clamping a very thin surface's short edge to one must still respect the budget.
        if (newWidth.toLong() * newHeight > maximumPixels) {
            if (newWidth >= newHeight) newWidth = maxOf(1, maximumPixels / newHeight)
            else newHeight = maxOf(1, maximumPixels / newWidth)
        }
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "CPU display viewport failed" }
        if (storageReady && width == newWidth && height == newHeight && pixels != null) return
        val candidate = ByteBuffer.allocateDirect(newWidth * newHeight * 4)
        // Keep the old GL object untouched until allocation succeeds, but clear
        // its CPU metadata on failure so the next same-size frame retries.
        width = 0; height = 0; pixels = null; storageReady = false
        lastGeneration = -1; lastViewport = null; hasPixels = false
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, newWidth, newHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "CPU display texture allocation failed" }
        width = newWidth; height = newHeight; pixels = candidate; storageReady = true
    }
    override fun onDrawFrame(gl: GL10?) {
        editor.captureDisplayScene()?.use { drawScene(it, gl) }
    }
    fun drawScene(scene: NativeScene, gl: GL10?): Boolean {
        val buffer = pixels ?: return false
        try {
            val generation = scene.generation()
            val view = viewport
            if (generation != lastGeneration || scene.cacheIdentity != lastSceneIdentity || view != lastViewport) {
                scene.draw(width, height, view, buffer)
                buffer.position(0)
                hasPixels = false
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "CPU display texture upload failed" }
                lastGeneration = generation; lastSceneIdentity = scene.cacheIdentity; lastViewport = view; hasPixels = true
            }
            return drawCached()
        } catch (error: Exception) { failed(error.message ?: "Canvas render failed"); return false }
    }
    /** A draft scene can share the committed document generation; force a fresh CPU upload. */
    fun invalidateSceneCache() {
        lastGeneration = -1L
        lastViewport = null
        hasPixels = false
    }
    fun drawCached(): Boolean {
        if (!hasPixels) return false
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0.1f, 0.1f, 0.12f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "image"), 0)
        positions.position(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, positions)
        positions.position(2); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, positions)
        GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "CPU display submission failed" }
        return true
    }
    fun release() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GLES30.glDeleteProgram(program)
        texture = 0; program = 0; pixels = null; storageReady = false; hasPixels = false
    }
    private fun shader(type: Int, source: String): Int {
        val value = GLES30.glCreateShader(type)
        GLES30.glShaderSource(value, source); GLES30.glCompileShader(value)
        val status = IntArray(1); GLES30.glGetShaderiv(value, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES30.glGetShaderInfoLog(value) }
        return value
    }
}
