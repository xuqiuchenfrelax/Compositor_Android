package com.pojia.compositor.mobile

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ceil
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("RestrictedApi")
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguages.wrap(newBase))
    }
    private lateinit var model: WorkspaceViewModel
    private val workspace get() = model.workspace
    private val workspaceState get() = model.state
    private var canvas: CanvasView? = null
    private var typedLayerTestAccess: Pair<NativeEditor, TypedLayerTestAccess>? = null
    private var resumed = false
    private val imageLaunchers = mutableMapOf<String, ActivityResultLauncher<*>>()
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        PenInput.observe(event)
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        PenInput.observe(event)
        return super.dispatchGenericMotionEvent(event)
    }
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val trace = BuildConfig.DEBUG && event.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_G)
        if (trace) android.util.Log.d("CompositorKey", "activity action=${event.action} key=${event.keyCode} " +
            "meta=${event.metaState} source=${event.source} focus=${currentFocus?.javaClass?.simpleName}")
        // Canvas arrows precede Android's directional focus navigation; fields retain their own keys.
        val handled = (resumed && (canvas?.dispatchClippingShortcut(event) == true ||
            canvas?.dispatchTransformShortcut(event) == true ||
            canvas?.dispatchMoveKey(event) == true)) || super.dispatchKeyEvent(event)
        if (trace) android.util.Log.d("CompositorKey", "activity handled=$handled")
        return handled
    }
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyShortcutEvent(event: android.view.KeyEvent): Boolean =
        (resumed && (canvas?.dispatchClippingShortcut(event) == true ||
            canvas?.dispatchTransformShortcut(event) == true)) || super.dispatchKeyShortcutEvent(event)
    override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>,
        menu: android.view.Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (::model.isInitialized && workspaceState.editor != null) {
            // Android reserves Meta combinations for system commands; Ctrl maps Mac's Command here.
            val ctrl = android.view.KeyEvent.META_CTRL_ON
            val shortcuts = mutableListOf(
                android.view.KeyboardShortcutInfo(getString(R.string.ui_transform), android.view.KeyEvent.KEYCODE_T, ctrl),
                android.view.KeyboardShortcutInfo(getString(R.string.ui_undo), android.view.KeyEvent.KEYCODE_Z, ctrl),
                android.view.KeyboardShortcutInfo(getString(R.string.ui_redo), android.view.KeyEvent.KEYCODE_Z,
                    ctrl or android.view.KeyEvent.META_SHIFT_ON),
                android.view.KeyboardShortcutInfo(getString(R.string.ui_toggle_clipping_mask), android.view.KeyEvent.KEYCODE_G,
                    ctrl or android.view.KeyEvent.META_ALT_ON),
                android.view.KeyboardShortcutInfo(getString(R.string.ui_fit), android.view.KeyEvent.KEYCODE_0, ctrl),
                android.view.KeyboardShortcutInfo(getString(R.string.ui_actual_pixels), android.view.KeyEvent.KEYCODE_1, ctrl))
            val toolKeys = mapOf(
                EditorTool.MOVE to android.view.KeyEvent.KEYCODE_V,
                EditorTool.DISTORT to android.view.KeyEvent.KEYCODE_D,
                EditorTool.MARQUEE to android.view.KeyEvent.KEYCODE_M,
                EditorTool.LASSO to android.view.KeyEvent.KEYCODE_L,
                EditorTool.MAGIC to android.view.KeyEvent.KEYCODE_W,
                EditorTool.CROP to android.view.KeyEvent.KEYCODE_C,
                EditorTool.BRUSH to android.view.KeyEvent.KEYCODE_B,
                EditorTool.HEALING to android.view.KeyEvent.KEYCODE_J,
                EditorTool.CLONE to android.view.KeyEvent.KEYCODE_S,
                EditorTool.SMEAR to android.view.KeyEvent.KEYCODE_R,
                EditorTool.GRADIENT to android.view.KeyEvent.KEYCODE_G,
                EditorTool.SHAPE to android.view.KeyEvent.KEYCODE_U,
                EditorTool.TYPE to android.view.KeyEvent.KEYCODE_T,
                EditorTool.EYEDROPPER to android.view.KeyEvent.KEYCODE_I,
                EditorTool.HAND to android.view.KeyEvent.KEYCODE_H,
                EditorTool.ZOOM to android.view.KeyEvent.KEYCODE_Z)
            for ((tool, keyCode) in toolKeys) if (tool.available) {
                shortcuts += android.view.KeyboardShortcutInfo(getString(tool.titleResource()), keyCode,
                    if (tool == EditorTool.DISTORT) android.view.KeyEvent.META_SHIFT_ON else 0)
            }
            data.add(android.view.KeyboardShortcutGroup("Compositor", shortcuts))
            data.add(fileKeyboardShortcutGroup())
        }
    }
    private fun fileKeyboardShortcutGroup(): android.view.KeyboardShortcutGroup {
        val shortcuts = FileShortcut.entries.map { shortcut ->
            val label = when (shortcut) {
                FileShortcut.NEW_CANVAS -> R.string.ui_new_canvas
                FileShortcut.OPEN_PROJECT -> R.string.ui_open_project_copy
                FileShortcut.SAVE -> R.string.ui_save_project
                FileShortcut.SAVE_COPY -> R.string.ui_save_project_copy
                FileShortcut.EXPORT_PNG -> R.string.ui_export_png
                FileShortcut.EXPORT_AS, FileShortcut.EXPORT_AS_ALIAS -> R.string.ui_export_as
            }
            val modifiers = android.view.KeyEvent.META_CTRL_ON or
                (if (shortcut.shift) android.view.KeyEvent.META_SHIFT_ON else 0) or
                (if (shortcut.alt) android.view.KeyEvent.META_ALT_ON else 0)
            android.view.KeyboardShortcutInfo(getString(label),
                android.view.KeyEvent.keyCodeFromString("KEYCODE_${shortcut.key.uppercaseChar()}"), modifiers)
        }
        return android.view.KeyboardShortcutGroup(getString(R.string.ui_file), shortcuts)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[WorkspaceViewModel::class.java]
        model.images.bind(this, ::registerImagePicker, ::launchImagePicker)
        setContent {
            // Register outside the editor key: pending results survive replacement and configuration changes.
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                model.projects.pickerResult(uri)
            }
            val imageDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                model.projects.imagePickerResult(uri)
            }
            val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                model.projects.exportPickerResult(uri)
            }
            CompositorTheme {
                Surface(Modifier.fillMaxSize().imePadding()) {
                    val state = workspaceState
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(); Text(getString(R.string.ui_opening_document), Modifier.padding(16.dp))
                            }
                        }
                        state.editor != null -> key(state.editor) {
                            EditorScreen(state.editor, workspace, state, model.projects, model.images, { picker.launch(null) }, { exportPicker.launch(null) }, { imageDocumentPicker.launch(arrayOf("image/*")) }) {
                                canvas = it
                                if (!resumed) it.onPause()
                            }
                        }
                        else -> AlertDialog(onDismissRequest = {}, title = { Text(getString(R.string.ui_document_recovery)) },
                            text = { Text(getString(R.string.ui_recovery_prompt, state.localizedRecoveryError(this@MainActivity).orEmpty())) },
                            confirmButton = { TextButton(onClick = { workspace.startNewDocument() }) { Text(getString(R.string.ui_start_new_document)) } },
                            dismissButton = { TextButton(onClick = { finish() }) { Text(getString(R.string.ui_close)) } })
                    }
                }
                model.images.state.localizedError(this@MainActivity)?.let { message ->
                    AlertDialog(onDismissRequest = { model.images.clearError() }, title = { Text(getString(R.string.ui_image_operation)) },
                        text = { Text(message) }, confirmButton = { TextButton(onClick = { model.images.clearError() }) { Text(getString(R.string.ui_ok)) } })
                }
            }
        }
    }
    private fun registerImagePicker(request: ImagePickerRequest) {
        if (request.key in imageLaunchers) return
        val callback: (Uri?) -> Unit = { uri ->
            model.images.pickerResult(request.id, request.purpose, uri)
            imageLaunchers.remove(request.key)?.unregister()
        }
        val launcher = when (request.purpose) {
            ImageRequestPurpose.IMPORT -> activityResultRegistry.register(request.key, ActivityResultContracts.PickVisualMedia(), callback)
            ImageRequestPurpose.IMPORT_FILE -> activityResultRegistry.register(request.key, ActivityResultContracts.OpenDocument(), callback)
            ImageRequestPurpose.EXPORT_PNG -> activityResultRegistry.register(request.key, ActivityResultContracts.CreateDocument("image/png"), callback)
            ImageRequestPurpose.EXPORT_TIFF, ImageRequestPurpose.EXPORT_TIFF_FLATTENED ->
                activityResultRegistry.register(request.key, ActivityResultContracts.CreateDocument("image/tiff"), callback)
            ImageRequestPurpose.EXPORT_JPEG -> activityResultRegistry.register(request.key, ActivityResultContracts.CreateDocument("image/jpeg"), callback)
        }
        // Registration can deliver a saved result before returning the launcher.
        if (model.images.pendingPickers().any { it.key == request.key }) imageLaunchers[request.key] = launcher
        else launcher.unregister()
    }
    @Suppress("UNCHECKED_CAST")
    private fun launchImagePicker(request: ImagePickerRequest) {
        registerImagePicker(request)
        try {
            when (request.purpose) {
                ImageRequestPurpose.IMPORT -> (imageLaunchers[request.key] as ActivityResultLauncher<PickVisualMediaRequest>)
                    .launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                ImageRequestPurpose.IMPORT_FILE -> (imageLaunchers[request.key] as ActivityResultLauncher<Array<String>>)
                    .launch(arrayOf("image/*"))
                ImageRequestPurpose.EXPORT_PNG -> (imageLaunchers[request.key] as ActivityResultLauncher<String>).launch(request.outputName ?: "Untitled.png")
                ImageRequestPurpose.EXPORT_TIFF -> (imageLaunchers[request.key] as ActivityResultLauncher<String>).launch(request.outputName ?: "Untitled-source-layers.tiff")
                ImageRequestPurpose.EXPORT_TIFF_FLATTENED -> (imageLaunchers[request.key] as ActivityResultLauncher<String>).launch(request.outputName ?: "Untitled.tiff")
                ImageRequestPurpose.EXPORT_JPEG -> (imageLaunchers[request.key] as ActivityResultLauncher<String>).launch(request.outputName ?: "Untitled.jpeg")
            }
        } catch (failure: Exception) {
            imageLaunchers.remove(request.key)?.unregister()
            throw failure
        }
    }
    override fun onPause() {
        resumed = false
        canvas?.pauseInput(); canvas?.onPause()
        // Final destruction already schedules the writer's cancel/save/close operation.
        if (!isFinishing && ::model.isInitialized && workspaceState.editor != null &&
            !model.projects.state.running && !workspace.snapshot().resourceGrowthFrozen && canvas?.moveRetainsDraft != true) workspace.flushAsync()
        super.onPause()
    }
    override fun onResume() { super.onResume(); resumed = true; canvas?.onResume(); canvas?.restoreTextureBudget() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); canvas?.handleMemoryPressure(level) }
    override fun onDestroy() {
        if (::model.isInitialized) model.images.unbind(this)
        imageLaunchers.values.forEach { it.unregister() }; imageLaunchers.clear()
        canvas?.retire()
        canvas = null
        super.onDestroy()
    }
    internal fun retainedEditorForTest(): NativeEditor? = if (::model.isInitialized) model.state.editor else null
    internal fun retainedWorkspaceForTest(): AndroidWorkspace = model.workspace
    internal fun retainedProjectFlowForTest(): ProjectFlowController = model.projects
    internal fun retainedImageRequestsForTest(): ImageRequestOwner = model.images
    internal fun typedLayerTestAccessForTest(): TypedLayerTestAccess? {
        check(BuildConfig.DEBUG && validatedDebugWorkspaceToken(intent.getStringExtra(DEBUG_WORKSPACE_TOKEN_EXTRA)) != null)
        return typedLayerTestAccess?.second
    }
    internal fun bindTypedLayerTestAccessForTest(editor: NativeEditor, access: TypedLayerTestAccess) {
        if (BuildConfig.DEBUG && validatedDebugWorkspaceToken(intent.getStringExtra(DEBUG_WORKSPACE_TOKEN_EXTRA)) != null)
            typedLayerTestAccess = editor to access
    }
    internal fun clearTypedLayerTestAccessForTest(editor: NativeEditor) {
        if (typedLayerTestAccess?.first === editor) typedLayerTestAccess = null
    }
}

/** Replacement and native destruction use this same editor gate. A retired screen
 * must neither read its closed document nor publish a delayed state into the UI. */
internal fun withCurrentEditor(lease: WorkspaceLease, action: () -> Unit): Boolean = lease.token.editor.serialized {
    if (!lease.isCurrent()) false else { action(); true }
}

/** Compose presents this selection as an observed input. Revalidate it under the
 * editor gate so a delayed binding cannot restore an older target or selected roots. */
internal fun withCurrentEditorSelection(lease: WorkspaceLease, presented: NativeSelection,
    bind: (NativeSelection) -> Unit): Boolean = withCurrentEditor(lease) {
    val current = lease.token.editor.presentationSelection()
    bind(if (current == presented) presented else current)
}

internal fun withCurrentEditorPresentation(lease: WorkspaceLease,
    publish: (EditorState, NativeSelection) -> Unit): Boolean = withCurrentEditor(lease) {
    val editor = lease.token.editor
    val (state, selection) = editor.presentation()
    publish(state, selection)
}

internal data class EditorScreenInitial(val lease: WorkspaceLease, val state: EditorState,
    val selection: NativeSelection, val typedStyles: TypedLayerStyleSnapshot)

/** Rotation may arrive during a retained edit; native acquisition belongs off the UI thread. */
internal suspend fun loadEditorScreenInitial(editor: NativeEditor, workspace: AndroidWorkspace): EditorScreenInitial? =
    withContext(Dispatchers.Default) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (workspace.snapshot().editor !== editor) return@withContext null
            var loaded: EditorScreenInitial? = null
            val acquired = editor.trySerialized {
                loaded = workspace.lease(editor)?.let { lease ->
                    val (state, selection) = editor.presentation()
                    EditorScreenInitial(lease, state, selection, editor.typedLayerStylesForPresentation(state))
                }
            }
            if (acquired) return@withContext loaded
            delay(32)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

internal data class EditorScreenPresentation(val state: EditorState, val selection: NativeSelection,
    val duplicate: DraftLayerPresentation?, val typedStyles: TypedLayerStyleSnapshot)

internal suspend fun loadEditorScreenPresentation(lease: WorkspaceLease): EditorScreenPresentation? =
    withContext(Dispatchers.Default) {
        var result: EditorScreenPresentation? = null
        withCurrentEditorPresentation(lease) { state, selection ->
            val editor = lease.token.editor
            result = EditorScreenPresentation(state, selection, editor.duplicateLayerPresentation(),
                editor.typedLayerStylesForPresentation(state))
        }
        result
    }

@Composable
private fun EditorScreen(editor: NativeEditor, workspace: AndroidWorkspace, workspaceState: WorkspaceState,
    projects: ProjectFlowController, images: ImageRequestOwner, launchPicker: () -> Unit, launchExportPicker: () -> Unit, launchImagePicker: () -> Unit, attach: (CanvasView) -> Unit) {
    val ui = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val landscapeIme = landscape && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var loaded by remember(editor, workspace) { mutableStateOf<EditorScreenInitial?>(null) }
    var openingError by remember(editor, workspace) { mutableStateOf<String?>(null) }
    var openingAttempt by remember(editor, workspace) { mutableStateOf(0) }
    LaunchedEffect(editor, workspace, openingAttempt) {
        openingError = null
        try {
            val initial = loadEditorScreenInitial(editor, workspace)
            if (initial != null && initial.lease.isCurrent()) loaded = initial
            else if (workspace.snapshot().editor === editor) openingError = ui.getString(R.string.ui_the_document_is_no_longer_available)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("CompositorEditor", "Editor initialization failed", failure)
            openingError = ui.getString(R.string.ui_could_not_open_the_editor)
        }
    }
    val initial = loaded
    if (initial == null) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = ui.getString(R.string.ui_editor_loading) }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (openingError == null) {
                    CircularProgressIndicator()
                    Text(ui.getString(R.string.ui_preparing_editor), Modifier.padding(16.dp))
                } else {
                    Text(checkNotNull(openingError), Modifier.padding(16.dp))
                    TextButton(onClick = { openingAttempt++ }) { Text(ui.getString(R.string.ui_retry)) }
                }
            }
        }
        return
    }
    val lease = initial.lease
    var state by remember(editor) { mutableStateOf(initial.state) }
    var authority by remember(editor) { mutableStateOf(initial.selection) }
    var typedStyles by remember(editor) { mutableStateOf(initial.typedStyles) }
    var bindingRevision by remember(editor) { mutableStateOf(0) }
    val initialBindingRetryScheduled = remember(editor) { booleanArrayOf(false) }
    val layerSelection = remember(editor) { LayerSelectionInteraction() }
    val selected = state.document.activeLayerId
    var tool by rememberSaveable(stateSaver = editorToolSaver) { mutableStateOf(EditorTool.IDLE) }
    var erasing by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable(stateSaver = strokeSettingsSaver) { mutableStateOf(StrokeSettings()) }
    var cloneSettings by rememberSaveable(stateSaver = strokeSettingsSaver) { mutableStateOf(StrokeSettings(hardness = 0f)) }
    var smearSettings by rememberSaveable(stateSaver = strokeSettingsSaver) { mutableStateOf(StrokeSettings(hardness = 0f, opacity = .5f)) }
    var smearModeName by rememberSaveable { mutableStateOf(SmearMode.SMUDGE.name) }
    val smearMode = SmearMode.valueOf(smearModeName)
    var healingSettings by rememberSaveable(stateSaver = strokeSettingsSaver) { mutableStateOf(StrokeSettings(hardness = 0f)) }
    var healingModeName by rememberSaveable { mutableStateOf(HealingMode.CONTENT_AWARE.name) }
    val healingMode = HealingMode.valueOf(healingModeName)
    var cloneAligned by remember(editor) { mutableStateOf(editor.cloneStamp.snapshot().aligned) }
    var cloneAllLayers by remember(editor) { mutableStateOf(editor.cloneStamp.snapshot().sampleAllLayers) }
    var foreground by rememberSaveable(stateSaver = editorColorSaver) { mutableStateOf(Color.Black) }
    var background by rememberSaveable(stateSaver = editorColorSaver) { mutableStateOf(Color.White) }
    var colorTarget by remember { mutableStateOf<Boolean?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    var spaceHeld by remember { mutableStateOf(false) }
    var showsSampleRing by rememberSaveable { mutableStateOf(true) }
    var moveLockRatio by rememberSaveable { mutableStateOf(true) }
    var moveShowControls by rememberSaveable { mutableStateOf(true) }
    var moveAutoSelect by rememberSaveable { mutableStateOf(false) }
    var moveDuplicateArmed by rememberSaveable { mutableStateOf(false) }
    // Distort shares Move's controller lifetime; Compose only persists the selected mode,
    // never a native draft, so recreation cannot resurrect stale work.
    var distortFreeCorners by rememberSaveable { mutableStateOf(true) }
    var shapeKind by rememberSaveable(stateSaver = shapeKindSaver) { mutableStateOf(ShapeKind.RECTANGLE) }
    var shapeLineWidth by rememberSaveable { mutableStateOf(4.0) }
    var shapeCornerRadius by rememberSaveable { mutableStateOf(0.0) }
    var shapeLineStyle by rememberSaveable(stateSaver = shapeLineStyleSaver) { mutableStateOf(ShapeLineStyle.SOLID) }
    var shapeLineCap by rememberSaveable(stateSaver = shapeLineCapSaver) { mutableStateOf(ShapeLineCap.SQUARE) }
    var gradientKind by remember { mutableStateOf(GradientKind.LINEAR) }
    var gradientStyle by remember { mutableStateOf(GradientStyle.FOREGROUND_TO_TRANSPARENT) }
    var gradientReversed by remember { mutableStateOf(false) }
    var gradientOpacity by remember { mutableStateOf(1.0) }
    var cropRatioChoice by rememberSaveable { mutableStateOf(CropRatioChoice.FREE) }
    var customCropRatioText by rememberSaveable { mutableStateOf("4:5") }
    var marqueeShape by rememberSaveable { mutableStateOf(PixelSelectionShape.RECTANGLE) }
    var marqueeOperation by rememberSaveable { mutableStateOf(PixelSelectionOperation.REPLACE) }
    var magicTolerance by rememberSaveable { mutableStateOf(32) }
    var magicSampleRadius by rememberSaveable { mutableStateOf(0) }
    var magicContiguous by rememberSaveable { mutableStateOf(true) }
    var collapsedGroups by remember(editor) { mutableStateOf<Set<Long>>(emptySet()) }
    var mappedDuplicateGeneration by remember(editor) { mutableStateOf(-1L) }
    var pendingDigit by remember { mutableStateOf<Int?>(null) }
    var pendingDigitTime by remember { mutableStateOf(0L) }
    var fileOpen by rememberSaveable { mutableStateOf(false) }
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val persistedDock = remember(context) { InspectorDockPreferences.load(context) }
    var inspectorPanelName by rememberSaveable { mutableStateOf(persistedDock.panelName) }
    var inspectorCollapsed by rememberSaveable { mutableStateOf(persistedDock.collapsed) }
    LaunchedEffect(inspectorPanelName, inspectorCollapsed) {
        InspectorDockPreferences.save(context, inspectorPanelName, inspectorCollapsed)
    }
    val inspectorPanel = remember(inspectorPanelName) {
        runCatching { InspectorPanel.valueOf(inspectorPanelName) }.getOrDefault(InspectorPanel.LAYERS)
    }
    var diagnostics by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var errorNoticeName by rememberSaveable { mutableStateOf<String?>(null) }
    var canvas by remember { mutableStateOf<CanvasView?>(null) }
    var zoomPercent by remember { mutableStateOf(100.0) }
    val keyboardFocus = remember { FocusRequester() }
    var canvasHasInputFocus by remember { mutableStateOf(false) }
    DisposableEffect(canvas) { val attached = canvas; onDispose { attached?.retire() } }
    val editingMask = authority.mask
    val selectedLayer = state.layers.firstOrNull { it.id == selected }
    // Read with the state on the loader worker, never from Compose using a potentially retired ID.
    val selectedStyles = typedStyles.takeIf { it.layerId == selected && it.revision == state.revision }
    val selectedTextStyle = selectedStyles?.text
    val selectedAdjustmentStyle = selectedStyles?.adjustment
    val selectedAdjustmentEditable = selectedLayer?.let {
        it.blend == 0 && (it.opacity == 0.0 || it.opacity == 1.0)
    } == true
    var textDraft by rememberSaveable(editor, stateSaver = textEditorDraftSaver) { mutableStateOf<TextEditorDraft?>(null) }
    var adjustmentDraft by rememberSaveable(editor, stateSaver = adjustmentEditorDraftSaver) {
        mutableStateOf<AdjustmentEditorDraft?>(null)
    }
    var adjustmentCreationId by rememberSaveable(editor) { mutableStateOf<String?>(null) }
    var levelsSampleRequest by remember(editor) { mutableStateOf<LevelsSampleRequest?>(null) }
    val levelsSamplingBusy = levelsSampleRequest != null
    val typedSubmissions = remember(editor) { typedLayerSubmissionOwner(editor) }
    val typedCommitInFlight = typedSubmissions.busy
    // A Levels histogram always reads an immutable input scene, never the edited draft output.
    val levelsSource = adjustmentDraft?.takeIf { it.style.kind == AdjustmentKind.LEVELS }
    val levelsSourceId = levelsSource?.submissionId
    val levelsBeforeLayer = levelsSource?.layerId ?: 0L
    var levelsHistogramAttempt by remember(editor, levelsSourceId) { mutableIntStateOf(0) }
    var levelsHistogram by remember(editor, levelsSourceId, levelsBeforeLayer, state.revision, levelsHistogramAttempt) {
        mutableStateOf(LevelsHistogramState.Loading)
    }
    LaunchedEffect(editor, levelsSourceId, levelsBeforeLayer, state.revision, levelsHistogramAttempt) {
        if (levelsSourceId == null) return@LaunchedEffect
        val expectedRevision = state.revision
        levelsHistogram = try {
            val bins = LevelsHistogramLoader.capture(editor, levelsBeforeLayer, expectedRevision)
            currentCoroutineContext().ensureActive()
            LevelsHistogramState.ready(bins)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("CompositorLevels", "Histogram source could not be prepared", failure)
            LevelsHistogramState.Failed
        }
    }
    LaunchedEffect(editor, levelsSampleRequest, levelsSourceId, levelsSource?.levelsSampleMode) {
        val request = levelsSampleRequest ?: return@LaunchedEffect
        if (request.draft.submissionId != levelsSourceId || request.draft.levelsSampleMode != levelsSource?.levelsSampleMode) {
            levelsSampleRequest = null
            return@LaunchedEffect
        }
        try {
            val rgb = LevelsHistogramLoader.sample(editor, request.draft.layerId ?: 0L,
                request.revision, request.x, request.y)
            currentCoroutineContext().ensureActive()
            if (rgb != null && lease.isCurrent() && !typedSubmissions.busy &&
                state.revision == request.revision && adjustmentDraft == request.draft)
                adjustmentDraft = request.draft.withLevelsSample(rgb)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("CompositorLevels", "Original point could not be sampled", failure)
            if (lease.isCurrent() && adjustmentDraft?.submissionId == request.draft.submissionId)
                errorNoticeName = UiNoticeKey.COLOR_SAMPLE_FAILED.name
        } finally {
            if (levelsSampleRequest === request) levelsSampleRequest = null
        }
    }
    LaunchedEffect(editor, canvas, adjustmentDraft, state.revision, state.document.activeLayerId,
        typedStyles.revision, typedStyles.layerId, typedCommitInFlight) {
        val view = canvas
        val draft = adjustmentDraft
        val committedTargetMatches = draft?.layerId?.let { id ->
            id == state.document.activeLayerId && typedStyles.layerId == id && typedStyles.revision == state.revision
        } == true
        if (draft != null && !typedCommitInFlight && (draft.baseRevision != state.revision || !committedTargetMatches)) {
            adjustmentDraft = null
            view?.updateAdjustmentPreview(null)
        } else if (view != null) {
            view.updateAdjustmentPreview(draft?.takeUnless { typedCommitInFlight })
        }
    }
    var composedTypedReceipt by remember(editor) { mutableStateOf<String?>(null) }
    // Publishing a receipt cannot re-enable handlers from the previous composition.
    SideEffect {
        val id = composedTypedReceipt
        if (id != null && lease.isCurrent() && typedStyles.revision == state.revision &&
            typedStyles.layerId == state.document.activeLayerId)
            typedSubmissions.consumePresentation(id, state.revision)
    }
    DisposableEffect(editor, typedSubmissions) {
        // Reattach after rotation. A command refused by the workspace never enters apply().
        val detach = editor.commandDispatcher.observe { _, _, pending, _ ->
            if (!pending) typedSubmissions.finishUnexecuted()
        }
        onDispose { detach() }
    }
    LaunchedEffect(typedSubmissions.outcome, typedSubmissions.presentationPendingId,
        textDraft?.submissionId, adjustmentDraft?.submissionId, adjustmentCreationId) {
        val outcome = typedSubmissions.outcomeFor(typedSubmissions.presentationPendingId)
            ?: typedSubmissions.outcomeFor(adjustmentCreationId)
            ?: typedSubmissions.outcomeFor(textDraft?.submissionId)
            ?: typedSubmissions.outcomeFor(adjustmentDraft?.submissionId) ?: return@LaunchedEffect
        if (!lease.isCurrent()) return@LaunchedEffect
        if (outcome.published) {
            // Keep the shared typed gate until publication is visible in this screen. A failed
            // refresh is retryable without resubmitting the already committed native command.
            while (lease.isCurrent()) {
                try {
                    val presentation = loadEditorScreenPresentation(lease) ?: return@LaunchedEffect
                    if (!lease.isCurrent()) return@LaunchedEffect
                    if (outcome.publishedRevision?.let { presentation.state.revision < it } != true) {
                        if (presentation.state.revision >= state.revision && presentation.selection.generation >= authority.generation) {
                            state = presentation.state
                            authority = presentation.selection
                            typedStyles = presentation.typedStyles
                        }
                        if (typedStyles.revision == state.revision && typedStyles.layerId == state.document.activeLayerId &&
                            outcome.publishedRevision?.let { state.revision < it } != true) {
                            when (outcome.kind) {
                                TypedLayerSubmissionKind.TEXT -> if (textDraft?.submissionId == outcome.id) textDraft = null
                                TypedLayerSubmissionKind.ADJUSTMENT -> if (adjustmentDraft?.submissionId == outcome.id) adjustmentDraft = null
                                TypedLayerSubmissionKind.ADJUSTMENT_CREATE -> {
                                    val created = presentation.state.layers.firstOrNull { it.id == outcome.publishedLayerId }
                                    val initial = outcome.createdAdjustmentStyle
                                    if (created != null && initial != null) {
                                        val expanded = collapsedGroups.toMutableSet()
                                        var parent = created.parentId
                                        while (parent != 0L) {
                                            expanded -= parent
                                            parent = presentation.state.layers.firstOrNull { it.id == parent }?.parentId ?: 0L
                                        }
                                        collapsedGroups = expanded
                                        if (initial.kind.isEditable) {
                                            val editId = "${outcome.id}:edit"
                                            if (adjustmentDraft?.submissionId != editId)
                                                adjustmentDraft = AdjustmentEditorDraft(initial, created.id, editId,
                                                    baseRevision = presentation.state.revision)
                                        }
                                    } else {
                                        errorNoticeName = null
                                        error = ui.getString(R.string.ui_adjustment_created_editor_unavailable)
                                    }
                                    if (adjustmentCreationId == outcome.id) adjustmentCreationId = null
                                }
                            }
                            composedTypedReceipt = outcome.id
                            canvas?.requestRender()
                            if (outcome.failure != null) {
                                error = null
                                errorNoticeName = UiNoticeKey.EDITOR_REFRESH_FAILED.name
                            }
                            break
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (lease.isCurrent()) {
                        error = null
                        errorNoticeName = UiNoticeKey.EDITOR_REFRESH_FAILED.name
                    }
                }
                delay(250)
            }
        } else if (outcome.failure != null) {
            errorNoticeName = null
            error = ui.getString(when (outcome.kind) {
                TypedLayerSubmissionKind.TEXT -> R.string.ui_text_commit_failed
                TypedLayerSubmissionKind.ADJUSTMENT -> R.string.ui_adjustment_commit_failed
                TypedLayerSubmissionKind.ADJUSTMENT_CREATE -> R.string.ui_adjustment_create_failed
            })
            if (outcome.kind == TypedLayerSubmissionKind.ADJUSTMENT && adjustmentDraft?.submissionId == outcome.id)
                canvas?.updateAdjustmentPreview(adjustmentDraft)
            if (adjustmentCreationId == outcome.id) adjustmentCreationId = null
        }
    }
    fun beginNewText() {
        if (typedSubmissions.busy || !lease.isCurrent()) return
        val style = TextLayerModel(content = "", red = foreground.red.toDouble(), green = foreground.green.toDouble(),
            blue = foreground.blue.toDouble())
        textDraft = TextEditorDraft(style, pointX = editor.canvasWidth / 2.0, pointY = editor.canvasHeight / 2.0)
    }
    fun beginEditText() {
        if (typedSubmissions.busy || !lease.isCurrent()) return
        val current = typedStyles
        if (current.layerId != state.document.activeLayerId || current.revision != state.revision) return
        current.text?.let { textDraft = TextEditorDraft(it, layerId = current.layerId) }
    }
    fun commitText(style: TextLayerModel) {
        val draft = textDraft ?: return
        val view = canvas ?: return
        if (draft.hasComposition || draft.hasInvalidStyleInput || draft.style != style || view.editingInProgress || projects.state.busy ||
            workspaceState.resourceGrowthFrozen || !lease.isCurrent()) return
        if (!typedSubmissions.begin(draft.submissionId, TypedLayerSubmissionKind.TEXT)) return
        try {
            if (!view.command(SubmitTextLayer(draft, style, typedSubmissions)))
                typedSubmissions.finish(draft.submissionId, false, "Text command was not accepted")
        } catch (failure: Exception) {
            typedSubmissions.finish(draft.submissionId, false, failure.message ?: "Text command was not accepted")
        }
    }
    fun commitAdjustment(style: AdjustmentLayerModel) {
        val draft = adjustmentDraft ?: return
        val view = canvas ?: return
        if (levelsSamplingBusy || draft.hasInvalidExposureInput || draft.hasInvalidLevelsInput || draft.style != style ||
            draft.baseRevision != state.revision || view.editingOtherThanAdjustmentInProgress || projects.state.busy ||
            workspaceState.resourceGrowthFrozen || !lease.isCurrent()) return
        if (!typedSubmissions.begin(draft.submissionId, TypedLayerSubmissionKind.ADJUSTMENT)) return
        view.updateAdjustmentPreview(null)
        try {
            if (!view.command(SubmitAdjustmentLayer(draft, style, typedSubmissions)))
                typedSubmissions.finish(draft.submissionId, false, "Adjustment command was not accepted")
        } catch (failure: Exception) {
            typedSubmissions.finish(draft.submissionId, false, failure.message ?: "Adjustment command was not accepted")
        }
    }
    fun beginNewAdjustment(kind: AdjustmentKind) {
        val view = canvas ?: return
        if (typedSubmissions.busy || adjustmentDraft != null || levelsSampleRequest != null ||
            view.editingInProgress || view.samplingInProgress || projects.state.busy ||
            workspaceState.resourceGrowthFrozen || !lease.isCurrent()) return
        val style = AdjustmentLayerModel.create(kind).let { model ->
            if (kind == AdjustmentKind.GRADIENT_MAP) model.withGradientMap(GradientMapModel(
                AdjustmentColorModel(foreground.red.toDouble(), foreground.green.toDouble(), foreground.blue.toDouble()),
                AdjustmentColorModel(background.red.toDouble(), background.green.toDouble(), background.blue.toDouble())))
            else if (kind == AdjustmentKind.GRAIN) model.withGrain(GrainModel(seed = kotlin.random.Random.nextLong(0L, 4_294_967_296L)))
            else if (kind == AdjustmentKind.ADD_NOISE) model.withNoise(seed = kotlin.random.Random.nextLong(0L, 4_294_967_296L))
            else model
        }
        val id = java.util.UUID.randomUUID().toString()
        if (!typedSubmissions.begin(id, TypedLayerSubmissionKind.ADJUSTMENT_CREATE)) return
        adjustmentCreationId = id
        try {
            if (!view.command(CreateAdjustmentLayer(id, style, typedSubmissions)))
                typedSubmissions.finish(id, false, "Adjustment creation command was not accepted")
        } catch (failure: Exception) {
            typedSubmissions.finish(id, false, failure.message ?: "Adjustment creation command was not accepted")
        }
    }
    fun beginEditAdjustment() {
        if (typedSubmissions.busy || canvas?.editingInProgress == true || canvas?.samplingInProgress == true || !lease.isCurrent()) return
        val current = typedStyles
        if (current.layerId != state.document.activeLayerId || current.revision != state.revision) return
        current.adjustment?.takeIf { it.kind.isEditable }?.let {
            adjustmentDraft = AdjustmentEditorDraft(it, current.layerId, baseRevision = current.revision)
        }
    }
    fun cancelAdjustment() {
        if (typedSubmissions.busy) return
        // Release the input fence immediately, including Cancel followed by another
        // command in the same event. Compose will retire the saved draft afterward.
        canvas?.updateAdjustmentPreview(null)
        levelsSampleRequest = null
        adjustmentDraft = null
    }
    if (BuildConfig.DEBUG) {
        val activity = ui as? MainActivity
        if (activity != null && validatedDebugWorkspaceToken(activity.intent.getStringExtra(DEBUG_WORKSPACE_TOKEN_EXTRA)) != null) {
            val access = TypedLayerTestAccess(
                text = { textDraft }, beginText = ::beginNewText, editText = ::beginEditText,
                updateText = { if (!typedSubmissions.busy && textDraft?.submissionId == it.submissionId) textDraft = it },
                applyText = { textDraft?.let { commitText(it.style) } },
                cancelText = { if (!typedSubmissions.busy) textDraft = null },
                adjustment = { adjustmentDraft }, beginAdjustment = ::beginNewAdjustment, editAdjustment = ::beginEditAdjustment,
                updateAdjustment = { if (!typedSubmissions.busy && adjustmentDraft?.submissionId == it.submissionId) adjustmentDraft = it },
                applyAdjustment = { adjustmentDraft?.let { commitAdjustment(it.style) } },
                cancelAdjustment = ::cancelAdjustment,
                busy = { typedSubmissions.busy })
            // Refresh callbacks after every composition so tests exercise the current production closures.
            SideEffect { activity.bindTypedLayerTestAccessForTest(editor, access) }
            DisposableEffect(editor, activity) {
                onDispose { activity.clearTypedLayerTestAccessForTest(editor) }
            }
        }
    }
    val maskTarget = editingMask && selectedLayer?.hasMask == true
    val moveGeometry = MoveGeometry.selection(state, authority.roots, authority.primary, maskTarget)
    val moveTarget = moveGeometry?.target
    val moveBox = canvas?.moveBox ?: moveGeometry?.box
    val moveScale = canvas?.moveScalePercent ?: moveBox?.let { box ->
        moveTarget?.let { target -> selectedLayer?.let { MoveInspectorGeometry.basis(it, target, moveGeometry?.box) } }
            ?.let { MoveInspectorGeometry.scalePercent(box, it) }
    }
    val enabled = canvas != null && !projects.state.busy && !workspaceState.resourceGrowthFrozen
    val startTransform: () -> Unit = {
        val view = canvas
        if (enabled && adjustmentDraft == null && moveTarget != null && view != null && !view.movePersistent) {
            view.switchMoveContext { tool = EditorTool.MOVE; view.startPersistentMove() }
        }
    }
    val startDistort: () -> Unit = {
        val view = canvas
        if (enabled && adjustmentDraft == null && moveTarget != null && view != null && !view.distortSessionActive) {
            view.switchMoveContext { tool = EditorTool.DISTORT; view.startDistortSession() }
        }
    }
    var presentationRevision by remember(editor) { mutableStateOf(0L) }
    val update: (EditorState) -> Unit = { presentationRevision++ }
    val selectModified: (Long, Boolean, Boolean) -> Unit = { id, shift, toggle ->
        if (enabled && canvas?.sampleSettlementPending != true) {
            val selection = layerSelection.select(displayedLayers(state.layers, collapsedGroups).map { it.id },
                authority.roots, authority.primary, id, shift, toggle)
            if (selection.roots.toSet() != authority.roots.toSet() || selection.primary != authority.primary || authority.mask) {
                val view = canvas
                val apply = { view?.command(EditorCommand.SelectLayers(selection.roots, selection.primary)); Unit }
                if (view?.moveInProgress == true || view?.moveContextPending == true) view.switchMoveContext(apply)
                else { view?.cancelStroke(); apply() }
            }
        }
    }
    val select: (Long) -> Unit = { id -> selectModified(id, false, false) }
    val setTool: (EditorTool) -> Unit = { value ->
        if (adjustmentDraft != null && value != EditorTool.HAND && value != EditorTool.ZOOM) Unit
        else if (enabled && value.available && value == tool && value == EditorTool.TYPE) {
            inspectorPanelName = InspectorPanel.TYPE.name
            inspectorCollapsed = false
            layersOpen = true
            if (textDraft == null) {
                if (selectedTextStyle != null) beginEditText() else beginNewText()
            }
        } else if (enabled && value.available && value != tool) {
            if (tool == EditorTool.DISTORT && value != EditorTool.DISTORT) canvas?.cancelDistortSession()
            canvas?.switchMoveContext {
                tool = value
                if (value == EditorTool.TYPE) {
                    inspectorPanelName = InspectorPanel.TYPE.name
                    inspectorCollapsed = false
                    layersOpen = true
                    if (textDraft == null) {
                        if (selectedTextStyle != null) beginEditText() else beginNewText()
                    }
                }
            }
        }
    }
    val setMaskTarget: (Boolean) -> Unit = { value ->
        if (enabled && value != maskTarget) {
            val view = canvas
            val apply = { view?.command(EditorCommand.SelectLayers(authority.roots, selected, value)); Unit }
            if (view?.moveInProgress == true || view?.moveContextPending == true) view.switchMoveContext(apply)
            else { view?.cancelStroke(); apply() }
        }
    }
    fun chooseBrush(erase: Boolean) {
        if (enabled && (tool != EditorTool.BRUSH || erasing != erase)) {
            canvas?.switchMoveContext { tool = EditorTool.BRUSH; erasing = erase }
        }
    }
    fun swapColors() { canvas?.cancelSampling(); val old = foreground; foreground = background; background = old }
    fun defaultColors() { canvas?.cancelSampling(); foreground = Color.Black; background = Color.White }
    fun stepZoom(step: Int) {
        val current = (canvas?.zoomPercent ?: zoomPercent) / 100.0
        val levels = listOf(.125, 1.0/6, .25, 1.0/3, .5, 2.0/3, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 12.0, 16.0)
        val tolerance = maxOf(.000000001, kotlin.math.abs(current) * .000000001)
        val target = if (step > 0) levels.firstOrNull { it > current + tolerance } else levels.lastOrNull { it < current - tolerance }
        target?.let { canvas?.setZoomPercent(it * 100) }
    }
    LaunchedEffect(editor, workspaceState.currentRevision, presentationRevision) {
        try {
            val presentation = loadEditorScreenPresentation(lease)
            if (presentation != null && lease.isCurrent() && presentation.state.revision >= state.revision &&
                presentation.selection.generation >= authority.generation) {
                val latest = presentation.state
                val selection = presentation.selection
                val draft = presentation.duplicate
                if (draft != null && draft.generation != mappedDuplicateGeneration) {
                    val byId = latest.layers.associateBy { it.id }
                    val next = collapsedGroups.toMutableSet()
                    draft.mapping.forEach { mapping ->
                        if (mapping.sourceId in collapsedGroups && byId[mapping.copyId]?.isGroup == true)
                            next += mapping.copyId
                    }
                    fun reveal(id: Long) {
                        var parent = byId[id]?.parentId ?: 0L
                        while (parent != 0L) { next -= parent; parent = byId[parent]?.parentId ?: 0L }
                    }
                    draft.mapping.filter { it.root }.forEach { reveal(it.sourceId); reveal(it.copyId) }
                    collapsedGroups = next
                    mappedDuplicateGeneration = draft.generation
                }
                if (draft == null) { mappedDuplicateGeneration = -1; collapsedGroups = collapsedGroups.intersect(latest.layers.map { it.id }.toSet()) }
                state = latest; authority = selection; typedStyles = presentation.typedStyles
                canvas?.requestRender()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("CompositorEditor", "Editor refresh failed", failure)
            if (lease.isCurrent()) { error = null; errorNoticeName = UiNoticeKey.EDITOR_REFRESH_FAILED.name }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) canvas?.cancelSampling() }
    LaunchedEffect(canvas, workspaceState.resourceGrowthFrozen) {
        if (!workspaceState.resourceGrowthFrozen) canvas?.requestRender()
    }
    LaunchedEffect(canvas, marqueeShape) { canvas?.setMarqueeShape(marqueeShape) }
    LaunchedEffect(canvas, marqueeOperation) { canvas?.setMarqueeOperation(marqueeOperation) }
    LaunchedEffect(canvas, colorTarget) {
        if (canvas != null && !fieldFocused && colorTarget == null) keyboardFocus.requestFocus()
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
        .onPreviewKeyEvent { event ->
            if (BuildConfig.DEBUG && event.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp,
                Key.DirectionDown, Key.Enter, Key.Escape)) android.util.Log.d("CompositorKey",
                "editor type=${event.type} key=${event.key} field=$fieldFocused enabled=$enabled tool=$tool " +
                    "editing=${canvas?.editingInProgress} move=${canvas?.moveInProgress} shift=${event.isShiftPressed}")
            val clearSelectionKey = event.key == Key.Escape ||
                (event.key == Key.D && (event.isMetaPressed || event.isCtrlPressed) &&
                    !event.isShiftPressed && !event.isAltPressed)
            if (!fieldFocused && colorTarget == null && tool == EditorTool.MAGIC &&
                canvas?.magicPending == true && clearSelectionKey && event.type == KeyEventType.KeyDown) {
                canvas?.clearPixelSelection(); true
            }
            else if (fieldFocused || colorTarget != null || !enabled) false
            else if (canvas?.editingInProgress == true && event.key != Key.Escape &&
                !(adjustmentDraft != null && canvas?.editingOtherThanAdjustmentInProgress == false &&
                    (event.key in setOf(Key.H, Key.Z, Key.Spacebar) && !event.isCtrlPressed && !event.isMetaPressed ||
                        (event.isCtrlPressed || event.isMetaPressed) && event.key in setOf(Key.Zero, Key.One, Key.Equals, Key.Plus, Key.Minus))) &&
                !(canvas?.moveInProgress == true && event.key in setOf(Key.Enter, Key.NumPadEnter)) &&
                !(tool == EditorTool.CROP && event.key in setOf(Key.Enter, Key.NumPadEnter)) &&
                !(tool == EditorTool.GRADIENT && event.key in setOf(Key.Enter, Key.NumPadEnter)) &&
                !(canvas?.moveInProgress == true && canvas?.canChangeTool == true &&
                    event.key in setOf(Key.B, Key.E, Key.H, Key.I, Key.Z, Key.A, Key.V)) &&
                !(canvas?.movePersistent == true && event.key == Key.Spacebar) &&
                !(canvas?.movePersistent == true && (event.isMetaPressed || event.isCtrlPressed) &&
                    event.key in setOf(Key.Zero, Key.One, Key.Equals, Key.Plus, Key.Minus)) &&
                !(canvas?.sampleSettlementPending == true && event.key in setOf(Key.B, Key.E, Key.H, Key.I, Key.Z, Key.A))) false
            else if (event.key == Key.Spacebar) { spaceHeld = event.type == KeyEventType.KeyDown; true }
            else if (event.type != KeyEventType.KeyDown) false
            else if (event.isMetaPressed || event.isCtrlPressed) {
                val fileShortcut = FileShortcut.resolve(when (event.key) {
                    Key.N -> 'n'; Key.O -> 'o'; Key.S -> 's'; Key.E -> 'e'; Key.W -> 'w'; else -> '\u0000'
                }, event.isCtrlPressed, event.isMetaPressed, event.isShiftPressed, event.isAltPressed)
                if (fileShortcut != null) {
                    if (canvas?.editingInProgress == true || images.state.busy || images.exportChoiceGeneration != null) false
                    else {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            canvas?.cancelSampling()
                            when (fileShortcut) {
                                FileShortcut.NEW_CANVAS -> projects.chooseNewCanvas(editor)
                                FileShortcut.OPEN_PROJECT -> if (projects.chooseProject(editor)) {
                                    try { launchPicker() }
                                    catch (failure: Exception) { projects.pickerLaunchFailed(failure.message ?: ui.getString(R.string.ui_the_project_picker_could_not_be_opened)) }
                                }
                                FileShortcut.SAVE -> projects.save(editor)
                                FileShortcut.SAVE_COPY -> projects.chooseSaveCopy(editor)
                                FileShortcut.EXPORT_PNG -> images.begin(ImageRequestPurpose.EXPORT_PNG, editor)
                                FileShortcut.EXPORT_AS, FileShortcut.EXPORT_AS_ALIAS -> images.chooseExport(editor)
                            }
                        }
                        true
                    }
                }
                else if (event.key == Key.G && event.isAltPressed && !event.isShiftPressed) {
                    canvas?.dispatchClippingShortcut(event.nativeKeyEvent) == true
                }
                else if (event.key == Key.T && !event.isShiftPressed && !event.isAltPressed) { startTransform(); true }
                else if (event.key == Key.Z) { canvas?.command(EditorCommand.Undo(event.isShiftPressed)); true }
                else if (event.key == Key.Zero) { canvas?.fit(); true }
                else if (event.key == Key.One) { canvas?.actualPixels(); true }
                else if (event.key == Key.Equals || event.key == Key.Plus) { stepZoom(1); true }
                else if (event.key == Key.Minus) { stepZoom(-1); true }
                else if (event.key == Key.D && !event.isShiftPressed && !event.isAltPressed &&
                    (tool == EditorTool.MARQUEE || tool == EditorTool.LASSO || tool == EditorTool.MAGIC)) {
                    canvas?.clearPixelSelection(); true
                }
                else false
            } else if (event.isAltPressed) false else when (event.key) {
                Key.B -> { chooseBrush(false); true }
                Key.E -> { chooseBrush(true); true }
                Key.H -> { setTool(EditorTool.HAND); true }
                Key.Z -> { setTool(EditorTool.ZOOM); true }
                Key.A -> { setTool(EditorTool.IDLE); true }
                Key.I -> { setTool(EditorTool.EYEDROPPER); true }
                Key.V -> { setTool(EditorTool.MOVE); true }
                Key.C -> { setTool(EditorTool.CROP); true }
                Key.U -> {
                    if (event.isShiftPressed) {
                        canvas?.cancelShape()
                        shapeKind = ShapeKind.entries[(shapeKind.ordinal + 1) % ShapeKind.entries.size]
                    }
                    setTool(EditorTool.SHAPE); true
                }
                Key.G -> { setTool(EditorTool.GRADIENT); true }
                Key.M -> { setTool(EditorTool.MARQUEE); true }
                Key.L -> { setTool(EditorTool.LASSO); true }
                Key.W -> { setTool(EditorTool.MAGIC); true }
                Key.S -> { setTool(EditorTool.CLONE); true }
                Key.R -> { setTool(EditorTool.SMEAR); true }
                Key.J -> { setTool(EditorTool.HEALING); true }
                Key.T -> { setTool(EditorTool.TYPE); true }
                Key.Delete -> if (tool == EditorTool.MARQUEE || tool == EditorTool.LASSO || tool == EditorTool.MAGIC) { canvas?.cutPixelSelection(); true } else false
                Key.Enter, Key.NumPadEnter -> { if (canvas?.moveInProgress == true) { canvas?.applyMove(); true } else if (tool == EditorTool.CROP) { canvas?.applyCrop(); true } else if (tool == EditorTool.GRADIENT) { canvas?.applyGradient(); true } else false }
                Key.X -> { swapColors(); true }
                Key.D -> if (event.isShiftPressed) { setTool(EditorTool.DISTORT); true } else { defaultColors(); true }
                Key.Tab -> if (event.isShiftPressed) false else when (tool) {
                    EditorTool.SHAPE -> {
                        canvas?.cancelShape()
                        shapeKind = ShapeKind.entries[(shapeKind.ordinal + 1) % ShapeKind.entries.size]
                        true
                    }
                    EditorTool.BRUSH -> { erasing = !erasing; true }
                    EditorTool.MARQUEE -> {
                        marqueeShape = if (marqueeShape == PixelSelectionShape.RECTANGLE)
                            PixelSelectionShape.ELLIPSE else PixelSelectionShape.RECTANGLE
                        canvas?.setMarqueeShape(marqueeShape)
                        true
                    }
                    EditorTool.GRADIENT -> {
                        gradientKind = GradientKind.entries[(gradientKind.ordinal + 1) % GradientKind.entries.size]
                        true
                    }
                    EditorTool.CLONE -> {
                        cloneAllLayers = !cloneAllLayers
                        editor.cloneStamp.setSampleAllLayers(cloneAllLayers)
                        true
                    }
                    EditorTool.SMEAR -> {
                        smearModeName = SmearMode.entries[(smearMode.ordinal + 1) % SmearMode.entries.size].name
                        true
                    }
                    EditorTool.HEALING -> {
                        healingModeName = HealingMode.entries[(healingMode.ordinal + 1) % HealingMode.entries.size].name
                        true
                    }
                    else -> false
                }
                Key.Escape -> {
                    if (adjustmentDraft != null) cancelAdjustment()
                    else if (canvas?.cloneSourceArmed == true) canvas?.cancelCloneSourcePick()
                    else if (canvas?.distortSessionActive == true) canvas?.cancelDistortSession()
                    else if (tool == EditorTool.CROP) canvas?.cancelCrop()
                    else if (canvas?.shapeDraft != null) canvas?.cancelShape()
                    else if (tool == EditorTool.GRADIENT && canvas?.gradientDraft != null) canvas?.cancelGradient()
                    else if (tool == EditorTool.MARQUEE || tool == EditorTool.LASSO || tool == EditorTool.MAGIC) canvas?.clearPixelSelection()
                    else canvas?.cancelStroke()
                    true
                }
                Key.LeftBracket, Key.RightBracket -> {
                    if (tool == EditorTool.BRUSH || tool == EditorTool.CLONE || tool == EditorTool.SMEAR || tool == EditorTool.HEALING) {
                        val activeSettings = if (tool == EditorTool.CLONE) cloneSettings else if (tool == EditorTool.SMEAR) smearSettings else if (tool == EditorTool.HEALING) healingSettings else settings
                        val direction = if (event.key == Key.RightBracket) 1 else -1
                        val changed = if (event.isShiftPressed) activeSettings.copy(hardness = ((if (direction > 0) floor(activeSettings.hardness * 4 + .001f) + 1 else ceil(activeSettings.hardness * 4 - .001f) - 1).coerceIn(0f, 4f)) / 4)
                            else activeSettings.copy(diameter = (if (direction > 0) maxOf(activeSettings.diameter + 1, floor(activeSettings.diameter * 1.2f + .5f))
                                else minOf(activeSettings.diameter - 1, floor(activeSettings.diameter / 1.2f + .5f))).coerceIn(1f, 2000f))
                        if (tool == EditorTool.CLONE) cloneSettings = changed else if (tool == EditorTool.SMEAR) smearSettings = changed else if (tool == EditorTool.HEALING) healingSettings = changed else settings = changed
                        true
                    } else false
                }
                Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine, Key.Zero -> {
                    if (tool == EditorTool.BRUSH || tool == EditorTool.CLONE || tool == EditorTool.SMEAR || tool == EditorTool.HEALING) {
                        val activeSettings = if (tool == EditorTool.CLONE) cloneSettings else if (tool == EditorTool.SMEAR) smearSettings else if (tool == EditorTool.HEALING) healingSettings else settings
                        val keys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine, Key.Zero)
                        val digit = (keys.indexOf(event.key) + 1) % 10
                        val now = SystemClock.uptimeMillis()
                        val prefix = pendingDigit
                        val percent = if (prefix != null && now - pendingDigitTime < 600) {
                            pendingDigit = null; maxOf(1, prefix * 10 + digit)
                        } else { pendingDigit = digit; pendingDigitTime = now; if (digit == 0) 100 else digit * 10 }
                        if (tool == EditorTool.CLONE) cloneSettings = activeSettings.copy(opacity = percent / 100f)
                        else if (tool == EditorTool.SMEAR) smearSettings = activeSettings.copy(opacity = percent / 100f)
                        else if (tool == EditorTool.HEALING) healingSettings = activeSettings.copy(opacity = percent / 100f)
                        else settings = activeSettings.copy(opacity = percent / 100f)
                        true
                    } else false
                }
                else -> false
            }
        }) {
        val wide = maxWidth >= 700.dp
        val sideInspector = wide || (landscape && layersOpen)
        val sideInspectorWidth = if (wide) 300.dp else minOf(300.dp, maxWidth * .45f)
        val filePanelMaxHeight = maxHeight * .32f
        BackHandler(adjustmentDraft != null || canvas?.cloneSourceArmed == true || canvas?.moveInProgress == true || canvas?.distortSessionActive == true || canvas?.cropRect != null || canvas?.shapeDraft != null || canvas?.gradientDraft != null || canvas?.samplingInProgress == true || fileOpen || colorTarget != null || diagnostics || (!wide && layersOpen)) {
            when {
                adjustmentDraft != null -> cancelAdjustment()
                canvas?.cloneSourceArmed == true -> canvas?.cancelCloneSourcePick()
                canvas?.moveInProgress == true -> canvas?.cancelMove()
                canvas?.distortSessionActive == true -> canvas?.cancelDistortSession()
                canvas?.cropRect != null -> canvas?.cancelCrop()
                canvas?.shapeDraft != null -> canvas?.cancelShape()
                canvas?.gradientDraft != null -> canvas?.cancelGradient()
                canvas?.samplingInProgress == true -> canvas?.cancelSampling()
                colorTarget != null -> colorTarget = null
                diagnostics -> diagnostics = false
                fileOpen -> fileOpen = false
                else -> layersOpen = false
            }
        }
        Column(Modifier.fillMaxSize()) {
            WorkspaceToolbar(fileOpen, layersOpen, !wide, workspaceState.projectDirty, state.undo, state.redo,
                enabled && canvas?.editingInProgress != true,
                newCanvas = { if (canvas?.editingInProgress != true) { canvas?.cancelSampling(); projects.chooseNewCanvas(editor) } },
                file = { if (canvas?.editingInProgress != true) { canvas?.cancelSampling(); fileOpen = !fileOpen } },
                layers = { if (enabled) {
                    val visible = !layersOpen
                    canvas?.switchMoveContext {
                        layersOpen = visible
                        fieldFocused = false
                        if (colorTarget == null) keyboardFocus.requestFocus()
                    }
                } },
                undo = { if (canvas?.editingInProgress != true) canvas?.command(EditorCommand.Undo(false)) },
                redo = { if (canvas?.editingInProgress != true) canvas?.command(EditorCommand.Undo(true)) },
                fit = { canvas?.fit() }, actual = { canvas?.actualPixels() }, zoom = ::stepZoom)
            // Action hosts remain composed when the File panel closes, preserving picker and dialog lifetimes.
            Column(Modifier.fillMaxWidth().heightIn(max = filePanelMaxHeight)
                .verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface)) {
                if (fileOpen) Text(ui.getString(R.string.ui_file), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                ImageFileActions(editor, canvas, workspace, images, showActions = fileOpen, otherOperationBusy = projects.state.busy)
                ProjectFileActions(editor, canvas, workspaceState, projects, launchPicker, launchExportPicker, launchImagePicker, showActions = fileOpen)
                DocumentSettings(editor, state.document, canvas, showActions = fileOpen) { error = null; errorNoticeName = it.name }
                if (fileOpen) AppLanguageSettings(enabled = enabled && canvas?.editingInProgress != true && !projects.state.busy)
                if (fileOpen && BuildConfig.DEBUG) TextButton(onClick = { diagnostics = true }) { Text(ui.getString(R.string.ui_diagnostics)) }
                if (fileOpen) HorizontalDivider()
            }
            ToolHeader(tool, erasing, if (tool == EditorTool.CLONE) cloneSettings else if (tool == EditorTool.SMEAR) smearSettings else if (tool == EditorTool.HEALING) healingSettings else settings, foreground, maskTarget, selectedLayer?.hasMask == true,
                selectedLayer?.isGroup != true, enabled, zoomPercent,
                setErasing = { canvas?.cancelStroke(); erasing = it }, setSettings = { if (tool == EditorTool.CLONE) cloneSettings = it else if (tool == EditorTool.SMEAR) smearSettings = it else if (tool == EditorTool.HEALING) healingSettings = it else settings = it },
                smearMode = smearMode, setSmearMode = { canvas?.cancelStroke(); smearModeName = it.name },
                healingMode = healingMode, setHealingMode = { canvas?.cancelStroke(); healingModeName = it.name },
                setMaskTarget = setMaskTarget, setZoom = { canvas?.setZoomPercent(it) },
                chooseForeground = { canvas?.cancelStroke(); colorTarget = false }, fieldFocus = { fieldFocused = it },
                fieldComplete = {
                    fieldFocused = false
                    if (canvas != null && colorTarget == null) keyboardFocus.requestFocus()
                }, singleRow = landscape,
                showsSampleRing = showsSampleRing, setSampleRing = { showsSampleRing = it },
                moveBox = moveBox, moveEditable = enabled && moveTarget != null &&
                    (canvas?.editingInProgress != true || canvas?.moveInProgress == true),
                movePending = canvas?.moveInProgress == true, moveSettling = canvas?.moveSettling == true,
                moveSwitchPending = canvas?.moveContextPending == true,
                setMoveField = { field, value, session -> canvas?.editMoveField(field, value, session) == true },
                cancelMove = { canvas?.cancelMove() },
                startTransform = if (tool == EditorTool.DISTORT) startDistort else startTransform,
                applyTransform = { canvas?.applyMove() }, lockRatio = moveLockRatio,
                setLockRatio = { moveLockRatio = it }, showControls = moveShowControls,
                setShowControls = { moveShowControls = it },
                autoSelect = moveAutoSelect, setAutoSelect = { moveAutoSelect = it },
                duplicateArmed = moveDuplicateArmed, setDuplicateArmed = { moveDuplicateArmed = it },
                movePersistent = canvas?.movePersistent == true, moveScalePercent = moveScale,
                beginMoveField = { canvas?.beginMoveFieldSession() },
                beginMoveScrub = { field -> canvas?.beginMoveScrub(field) },
                updateMoveScrub = { session, translation -> canvas?.updateMoveScrub(session, translation) },
                finishMoveScrub = { session, cancelled ->
                    canvas?.finishMoveScrub(session, cancelled)
                    fieldFocused = false
                    if (canvas != null && colorTarget == null) keyboardFocus.requestFocus()
                },
                finishMoveField = { session, returnToCanvas ->
                    if (session != null) canvas?.completeMoveField(session)
                    if (returnToCanvas) {
                        fieldFocused = false
                        if (canvas != null && colorTarget == null) keyboardFocus.requestFocus()
                    }
                }, setMoveScale = { value, session -> canvas?.editMoveScale(value, session) == true },
                canAlignLayers = authority.roots.isNotEmpty() && !typedCommitInFlight && canvas?.distortSessionActive != true,
                canDistributeLayers = authority.roots.size > 2 && !typedCommitInFlight && canvas?.distortSessionActive != true,
                alignLayers = { alignment ->
                    val view = canvas
                    view?.switchMoveContext {
                        view.command(EditorCommand.AlignLayers(alignment, editor.selection().roots.toList()))
                    }
                },
                distributeLayers = { distribution ->
                    val view = canvas
                    view?.switchMoveContext {
                        view.command(EditorCommand.DistributeLayers(distribution, editor.selection().roots.toList()))
                    }
                },
                canFlipMove = canvas?.moveCanFlip == true, flipMove = { horizontal ->
                    if (canvas?.flipMove(horizontal) == true) {
                        fieldFocused = false
                        if (colorTarget == null) keyboardFocus.requestFocus()
                    }
                }, distortFreeCorners = distortFreeCorners,
                setDistortFreeCorners = { distortFreeCorners = it },
                distortActive = canvas?.distortSessionActive == true,
                distortReady = canvas?.distortSessionReady == true,
                applyDistort = { canvas?.applyDistortSession() },
                cancelDistort = { canvas?.cancelDistortSession() },
                applyCrop = { canvas?.applyCrop() }, cancelCrop = { canvas?.cancelCrop() },
                cropRatioChoice = cropRatioChoice,
                setCropRatioChoice = { cropRatioChoice = it; canvas?.changeCropRatio(it) },
                customCropRatioText = customCropRatioText,
                setCustomCropRatio = { text ->
                    customCropRatioText = text
                    canvas?.changeCustomCropRatio(parseCropRatio(text))
                },
                shapeKind = shapeKind, setShapeKind = { canvas?.cancelShape(); shapeKind = it },
                shapeLineWidth = shapeLineWidth, setShapeLineWidth = { shapeLineWidth = it.coerceIn(1.0, 5000.0) },
                shapeLineStyle = shapeLineStyle, setShapeLineStyle = { shapeLineStyle = it },
                shapeLineCap = shapeLineCap, setShapeLineCap = { shapeLineCap = it },
                shapeCornerRadius = shapeCornerRadius, setShapeCornerRadius = { shapeCornerRadius = it.coerceIn(0.0, 5000.0) },
                gradientKind = gradientKind, setGradientKind = { gradientKind = it },
                gradientStyle = gradientStyle, setGradientStyle = { gradientStyle = it },
                gradientReversed = gradientReversed, setGradientReversed = { gradientReversed = it },
                gradientOpacity = gradientOpacity, setGradientOpacity = { gradientOpacity = it.coerceIn(.01, 1.0) },
                gradientPending = canvas?.gradientPending == true,
                applyGradient = { canvas?.applyGradient() }, cancelGradient = { canvas?.cancelGradient() },
                marqueeActive = canvas?.pixelSelection?.active == true,
                marqueeShape = marqueeShape,
                setMarqueeShape = { value -> marqueeShape = value; canvas?.setMarqueeShape(value) },
                marqueeOperation = marqueeOperation,
                setMarqueeOperation = { value -> marqueeOperation = value; canvas?.setMarqueeOperation(value) },
                fillSelection = { canvas?.fillPixelSelection(foreground.toArgb()) },
                cutSelection = { canvas?.cutPixelSelection() },
                clearSelection = { canvas?.clearPixelSelection() },
                modifySelection = { operation, amount -> canvas?.modifyPixelSelection(operation, amount) },
                // Amount modifiers currently admit one replacement rectangle or
                // ellipse. Polygon offsets need a topology-preserving path
                // representation before admission.
                selectionAmountSupported = { operation ->
                    val region = canvas?.pixelSelectionRegions?.singleOrNull()
                    operation in PixelSelectionAmountOperation.EXPAND..PixelSelectionAmountOperation.FEATHER &&
                        region != null &&
                        region.operation == PixelSelectionOperation.REPLACE &&
                        (region.shape == PixelSelectionShape.RECTANGLE || region.shape == PixelSelectionShape.ELLIPSE)
                },
                selectionPending = canvas?.magicPending == true,
                magicTolerance = magicTolerance,
                setMagicTolerance = { magicTolerance = it.coerceIn(0, 255); canvas?.configureMagicTolerance(magicTolerance) },
                magicSampleRadius = magicSampleRadius,
                setMagicSampleRadius = { magicSampleRadius = it.coerceIn(0, 2); canvas?.configureMagicSampleRadius(magicSampleRadius) },
                magicContiguous = magicContiguous,
                setMagicContiguous = { magicContiguous = it; canvas?.configureMagicContiguous(it) },
                cloneAligned = cloneAligned, cloneAllLayers = cloneAllLayers,
                cloneSourceArmed = canvas?.cloneSourceArmed == true,
                cloneSourceEnabled = enabled && canvas?.editingInProgress != true, penSeen = PenInput.seen,
                armCloneSource = { canvas?.armCloneSource() },
                setCloneAligned = { cloneAligned = it; editor.cloneStamp.setAligned(it) },
                setCloneAllLayers = { cloneAllLayers = it; editor.cloneStamp.setSampleAllLayers(it) })
            HorizontalDivider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ToolRail(tool, erasing, enabled && canvas?.canChangeTool == true, setTool) {
                    PaletteControls(foreground, background, enabled && !maskTarget,
                        choose = { canvas?.cancelStroke(); colorTarget = it }, swap = ::swapColors, defaults = ::defaultColors)
                }
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().focusRequester(keyboardFocus)
                            .onFocusChanged {
                                canvasHasInputFocus = it.isFocused
                                canvas?.keyboardFocusActive = it.isFocused
                                if (!it.hasFocus) spaceHeld = false
                            }.focusable()
                            .semantics { contentDescription = ui.getString(R.string.ui_canvas) }) {
                        val presentedSelection = authority
                        AndroidView<android.widget.FrameLayout>(
                            factory = { context -> android.widget.FrameLayout(context) },
                            update = { host ->
                                val currentBindingRevision = bindingRevision
                                var attached: CanvasView? = null
                                val initialCanvas = host.childCount == 0
                                val bind = {
                                withCurrentEditorSelection(lease, presentedSelection) { selection ->
                                    val view = (host.getChildAt(0) as? CanvasView) ?: CanvasView(host.context, editor, update,
                                        { message -> (host.context as MainActivity).runOnUiThread { errorNoticeName = null; error = message } }, lease,
                                        noticed = { key -> (host.context as MainActivity).runOnUiThread { error = null; errorNoticeName = key.name } }).also { created ->
                                        created.viewportChanged = { zoomPercent = it }
                                        host.addView(created, android.widget.FrameLayout.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                                        canvas = created
                                        attached = created
                                    }
                                    // A replacement Canvas can attach while Compose already owns
                                    // focus, so requestFocus need not emit another focus event.
                                    view.keyboardFocusActive = canvasHasInputFocus
                                    view.layerId = selection.primary
                                    view.pixelSelection = editor.pixelSelection()
                                    view.pixelSelectionRegions = editor.pixelSelectionRegions().regions
                                    view.strokeSettings = if (tool == EditorTool.CLONE) cloneSettings
                                        else if (tool == EditorTool.SMEAR) smearSettings
                                        else if (tool == EditorTool.HEALING) healingSettings
                                        else settings.copy(red = foreground.red, green = foreground.green, blue = foreground.blue)
                                    view.smearMode = smearMode
                                    view.healingMode = healingMode
                                    view.erasing = erasing
                                    view.editingMask = selection.mask
                                    view.bindMoveTarget()
                                    view.foregroundColor = foreground.toArgb()
                                    view.sampledColor = { foreground = Color(it) }
                                    view.levelsSampleContext = levelsSource?.copy(commitInFlight = typedCommitInFlight)
                                    view.levelsTemporaryHand = spaceHeld
                                    view.levelsSamplePoint = if (enabled && !typedCommitInFlight && !levelsSamplingBusy &&
                                        levelsSource?.levelsSampleMode != null) { { x, y ->
                                        val draft = adjustmentDraft
                                        if (draft?.style?.kind == AdjustmentKind.LEVELS && draft.levelsSampleMode != null &&
                                            !typedSubmissions.busy && levelsSampleRequest == null && !view.editingOtherThanAdjustmentInProgress)
                                            levelsSampleRequest = LevelsSampleRequest(draft, state.revision, x, y)
                                    } } else null
                                    view.samplingEnabled = enabled
                                    view.keyboardMoveEnabled = enabled && adjustmentDraft == null && !fieldFocused && colorTarget == null && !diagnostics
                                    view.transformShortcut = startTransform
                                    view.clippingShortcutActions = state.selectedLayerActions
                                    view.showsSampleRing = showsSampleRing
                                    view.moveLockRatio = moveLockRatio; view.moveShowControls = moveShowControls; view.moveAutoSelect = moveAutoSelect
                                    view.moveDuplicateArmed = moveDuplicateArmed
                                    view.distortFreeCorners = distortFreeCorners
                                    view.cropRatioChoice = cropRatioChoice
                                    view.cropCustomRatio = parseCropRatio(customCropRatioText)
                                    view.shapeKind = shapeKind
                                    view.shapeLineWidth = shapeLineWidth
                                    view.shapeLineStyle = shapeLineStyle
                                    view.shapeLineCap = shapeLineCap
                                    view.shapeCornerRadius = shapeCornerRadius
                                    view.shapeColor = foreground.toArgb()
                                    view.gradientShape = gradientKind
                                    view.gradientStyle = gradientStyle
                                    view.gradientReversed = gradientReversed
                                    view.gradientOpacity = gradientOpacity
                                    view.gradientForeground = foreground.toArgb()
                                    view.gradientBackground = background.toArgb()
                                    view.magicTolerance = magicTolerance
                                    view.magicSampleRadius = magicSampleRadius
                                    view.magicContiguous = magicContiguous
                                    view.toolMode = if (spaceHeld) CanvasToolMode.HAND
                                    else if (levelsSource != null && tool != EditorTool.HAND && tool != EditorTool.ZOOM) CanvasToolMode.IDLE
                                    else when (tool) {
                                        EditorTool.BRUSH -> CanvasToolMode.BRUSH
                                        EditorTool.CLONE -> CanvasToolMode.CLONE
                                        EditorTool.SMEAR -> CanvasToolMode.SMEAR
                                        EditorTool.HEALING -> CanvasToolMode.HEALING
                                        EditorTool.EYEDROPPER -> CanvasToolMode.EYEDROPPER
                                        EditorTool.MOVE, EditorTool.DISTORT -> CanvasToolMode.MOVE
                                        EditorTool.HAND -> CanvasToolMode.HAND
                                        EditorTool.ZOOM -> CanvasToolMode.ZOOM
                                        EditorTool.CROP -> CanvasToolMode.CROP
                                        EditorTool.SHAPE -> CanvasToolMode.SHAPE
                                        EditorTool.GRADIENT -> CanvasToolMode.GRADIENT
                                        EditorTool.MARQUEE -> CanvasToolMode.MARQUEE
                                        EditorTool.LASSO -> CanvasToolMode.LASSO
                                        EditorTool.MAGIC -> CanvasToolMode.MAGIC
                                        else -> CanvasToolMode.IDLE
                                    }
                                }
                                Unit
                                }
                                // Queued commands may be waiting for another owner; a free gate can
                                // still present the retained document on its newly recreated screen.
                                val bound = if (initialCanvas) editor.trySerialized(bind) else editor.tryUiUpdate(bind)
                                attached?.let(attach)
                                if (!bound) {
                                    val invalidate = { bindingRevision = maxOf(bindingRevision, currentBindingRevision) + 1 }
                                    val view = host.getChildAt(0) as? CanvasView
                                    if (view != null) view.retryEditorBinding(invalidate)
                                    else if (!initialBindingRetryScheduled[0]) {
                                        initialBindingRetryScheduled[0] = true
                                        host.postDelayed({
                                            initialBindingRetryScheduled[0] = false
                                            if (host.isAttachedToWindow && lease.isCurrent()) invalidate()
                                        }, 32)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (tool == EditorTool.CLONE) {
                            val source = canvas?.cloneSamplePoint
                            if (source != null && source.x.isFinite() && source.y.isFinite()) Canvas(
                                Modifier.fillMaxSize().semantics { contentDescription = ui.getString(R.string.ui_clone_stamp_source) }) {
                                val scale = canvas?.scaleForOverlay ?: 1f
                                val point = Offset((canvas?.offsetXForOverlay ?: 0f) + (source.x * scale).toFloat(),
                                    (canvas?.offsetYForOverlay ?: 0f) + (source.y * scale).toFloat())
                                val radius = 8.dp.toPx()
                                drawCircle(Color.Black, radius, point, style = Stroke(3.dp.toPx()))
                                drawCircle(Color.White, radius, point, style = Stroke(1.dp.toPx()))
                                drawLine(Color.White, point - Offset(radius + 4.dp.toPx(), 0f), point + Offset(radius + 4.dp.toPx(), 0f), 1.dp.toPx())
                                drawLine(Color.White, point - Offset(0f, radius + 4.dp.toPx()), point + Offset(0f, radius + 4.dp.toPx()), 1.dp.toPx())
                            }
                        }
                        SampleRing(canvas?.sampleRing)
                        MoveSnapLines(canvas)
                        if (tool == EditorTool.MOVE || tool == EditorTool.DISTORT) MoveControls(canvas, moveBox)
                        if (tool == EditorTool.CROP && canvas?.cropRect != null) {
                            val rect = canvas?.cropRect!!.normalized()
                            Canvas(Modifier.fillMaxSize().semantics { contentDescription = ui.getString(R.string.ui_crop_preview) }) {
                                val left = (canvas?.offsetXForOverlay ?: 0f) + (rect.left * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                val top = (canvas?.offsetYForOverlay ?: 0f) + (rect.top * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                val right = (canvas?.offsetXForOverlay ?: 0f) + (rect.right * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                val bottom = (canvas?.offsetYForOverlay ?: 0f) + (rect.bottom * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                drawRect(Color.Black.copy(alpha = .45f), topLeft = Offset.Zero, size = Size(size.width, top))
                                drawRect(Color.Black.copy(alpha = .45f), topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
                                drawRect(Color.Black.copy(alpha = .45f), topLeft = Offset(0f, top), size = Size(left, bottom - top))
                                drawRect(Color.Black.copy(alpha = .45f), topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
                                drawRect(primaryColor, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Stroke(2f))
                                listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach {
                                    drawCircle(Color.White, 14f, it); drawCircle(primaryColor, 12f, it, style = Stroke(3f))
                                }
                            }
                        }
                        if (tool == EditorTool.SHAPE && canvas?.shapeDraft != null) {
                            val draft = canvas?.shapeDraft!!.withLineWidth(shapeLineWidth)
                            Canvas(Modifier.fillMaxSize().semantics { contentDescription = ui.getString(R.string.ui_shape_preview) }) {
                                val left = (canvas?.offsetXForOverlay ?: 0f) + (draft.x * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                val top = (canvas?.offsetYForOverlay ?: 0f) + (draft.y * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                val right = left + (draft.width * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                val bottom = top + (draft.height * (canvas?.scaleForOverlay ?: 1f)).toFloat()
                                val color = foreground
                                if (draft.kind == ShapeKind.LINE) {
                                    val scale = canvas?.scaleForOverlay ?: 1f
                                    val originX = canvas?.offsetXForOverlay ?: 0f
                                    val originY = canvas?.offsetYForOverlay ?: 0f
                                    val shift = lineGridShift(shapeLineWidth)
                                    val thickness = (shapeLineWidth * scale).toFloat()
                                    clipRect(left, top, left + draft.width.toInt() * scale, top + draft.height.toInt() * scale) {
                                        val dashes = Path()
                                        val dots = Path()
                                        shapeLinePieces(draft.anchorX + shift, draft.anchorY + shift,
                                            draft.endX + shift, draft.endY + shift, shapeLineWidth,
                                            draft.lineStyle, draft.lineCap).forEach { piece ->
                                            val start = Offset(originX + (piece.startX * scale).toFloat(), originY + (piece.startY * scale).toFloat())
                                            val end = Offset(originX + (piece.endX * scale).toFloat(), originY + (piece.endY * scale).toFloat())
                                            if (piece.dot) dots.addOval(Rect(start.x - thickness / 2, start.y - thickness / 2,
                                                start.x + thickness / 2, start.y + thickness / 2))
                                            else { dashes.moveTo(start.x, start.y); dashes.lineTo(end.x, end.y) }
                                        }
                                        drawPath(dashes, color, style = Stroke(thickness,
                                            cap = if (draft.lineCap == ShapeLineCap.ROUND) StrokeCap.Round else StrokeCap.Butt))
                                        drawPath(dots, color)
                                    }
                                }
                                else if (draft.kind == ShapeKind.ELLIPSE) drawOval(color, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2f))
                                else drawRect(color, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2f))
                            }
                        }
                        if (tool == EditorTool.GRADIENT && canvas?.gradientDraft != null) {
                            val draft = canvas?.gradientDraft!!
                            Canvas(Modifier.fillMaxSize().semantics { contentDescription = ui.getString(R.string.ui_gradient_preview) }) {
                                val scale = canvas?.scaleForOverlay ?: 1f
                                val originX = canvas?.offsetXForOverlay ?: 0f
                                val originY = canvas?.offsetYForOverlay ?: 0f
                                val start = Offset(originX + (draft.startX * scale).toFloat(), originY + (draft.startY * scale).toFloat())
                                val end = Offset(originX + (draft.endX * scale).toFloat(), originY + (draft.endY * scale).toFloat())
                                drawLine(primaryColor, start, end, 2f)
                                drawCircle(Color.White, 9f, start); drawCircle(primaryColor, 8f, start, style = Stroke(2f))
                                if (gradientKind == GradientKind.RADIAL) drawCircle(primaryColor, (draft.length * scale).toFloat(), start, style = Stroke(2f))
                                drawCircle(Color.White, 9f, end); drawCircle(primaryColor, 8f, end, style = Stroke(2f))
                            }
                        }
                        if (tool == EditorTool.DISTORT && canvas?.distortSessionActive == true) {
                            Canvas(Modifier.fillMaxSize()) {
                                val points = canvas?.distortHandlePoints.orEmpty().map { (x, y) -> Offset(x, y) }
                                if (points.size == 4) points.forEachIndexed { index, point ->
                                    drawLine(primaryColor, point, points[(index + 1) % 4], 2f)
                                }
                                points.forEach { point ->
                                    drawCircle(Color.White, 12f, point)
                                    drawCircle(primaryColor, 10f, point, style = Stroke(3f))
                                }
                            }
                        }
                        val marqueePreview = if (tool == EditorTool.MARQUEE) canvas?.marqueePreview else null
                        val lassoPreview = if (tool == EditorTool.LASSO) canvas?.lassoPreview else null
                        val selectionRegions = when {
                            marqueePreview != null -> listOf(PixelSelectionRegion(marqueePreview.x, marqueePreview.y,
                                marqueePreview.width, marqueePreview.height, canvas?.marqueePreviewShape ?: PixelSelectionShape.RECTANGLE,
                                PixelSelectionOperation.REPLACE))
                            lassoPreview != null && lassoPreview.size >= 2 -> {
                                val left = lassoPreview.minOf { it.x }; val top = lassoPreview.minOf { it.y }
                                val right = lassoPreview.maxOf { it.x }; val bottom = lassoPreview.maxOf { it.y }
                                listOf(PixelSelectionRegion(left, top, right - left, bottom - top,
                                    PixelSelectionShape.POLYGON, marqueeOperation, lassoPreview))
                            }
                            tool == EditorTool.MARQUEE || tool == EditorTool.LASSO || tool == EditorTool.MAGIC || tool == EditorTool.CLONE ->
                                canvas?.pixelSelectionRegions?.takeIf { it.isNotEmpty() }
                                    ?: canvas?.pixelSelection?.takeIf { it.active }?.let {
                                        listOf(PixelSelectionRegion(it.x, it.y, it.width, it.height, it.shape,
                                            PixelSelectionOperation.REPLACE))
                                    }.orEmpty()
                            else -> emptyList()
                        }
                        val selectionEdges = remember(selectionRegions) {
                            selectionRegions.map { region -> selectionOutlineEdges(region.polygon) }
                        }
                        if (selectionRegions.isNotEmpty()) {
                            Canvas(Modifier.fillMaxSize().semantics {
                                    contentDescription = when (tool) {
                                        EditorTool.LASSO -> ui.getString(R.string.ui_lasso_selection)
                                        EditorTool.MAGIC -> ui.getString(R.string.ui_magic_wand_selection)
                                        EditorTool.CLONE -> ui.getString(R.string.ui_clone_selection)
                                        else -> ui.getString(R.string.ui_marquee_selection)
                                    }
                            }) {
                                val scale = canvas?.scaleForOverlay ?: 1f
                                val originX = canvas?.offsetXForOverlay ?: 0f
                                val originY = canvas?.offsetYForOverlay ?: 0f
                                selectionRegions.forEachIndexed { regionIndex, region ->
                                    val left = originX + (region.x * scale).toFloat()
                                    val top = originY + (region.y * scale).toFloat()
                                    val size = Size((region.width * scale).toFloat(), (region.height * scale).toFloat())
                                    val color = if (region.operation == PixelSelectionOperation.SUBTRACT)
                                        Color(0xffff9f43) else primaryColor
                                    when (region.shape) {
                                        PixelSelectionShape.ELLIPSE -> drawOval(color, Offset(left, top), size, style = Stroke(2f))
                                        PixelSelectionShape.POLYGON -> if (region.polygon.size >= 2) {
                                            selectionEdges[regionIndex].forEach { index ->
                                                val point = region.polygon[index]
                                                val next = region.polygon[(index + 1) % region.polygon.size]
                                                drawLine(color, Offset(originX + (point.x * scale).toFloat(), originY + (point.y * scale).toFloat()),
                                                    Offset(originX + (next.x * scale).toFloat(), originY + (next.y * scale).toFloat()), 2f)
                                            }
                                        } else drawRect(color, Offset(left, top), size, style = Stroke(2f))
                                        else -> drawRect(color, Offset(left, top), size, style = Stroke(2f))
                                    }
                                }
                            }
                        }
                        if (canvas?.previewWaiting == true) {
                            Text(ui.getString(R.string.ui_waiting_for_memory_to_update_preview),
                                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                                    .background(MaterialTheme.colorScheme.surface).padding(8.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        }
                        if (sideInspector) {
                            VerticalDivider()
                            InspectorDock(inspectorPanel, { inspectorPanelName = it.name }, state, selected, select,
                                { action -> if (enabled) canvas?.command(action) }, maskTarget, setMaskTarget,
                                Modifier.width(if (wide && inspectorCollapsed) 56.dp else sideInspectorWidth).fillMaxHeight(),
                                enabled = enabled && !typedCommitInFlight && canvas?.moveInProgress != true && canvas?.moveContextPending != true && canvas?.magicPending != true,
                                selectionEnabled = enabled && adjustmentDraft == null && canvas?.canChangeTool == true,
                                targetEnabled = enabled && adjustmentDraft == null && canvas?.canChangeTool == true,
                                selectedRoots = authority.roots.toSet(), selectModified = selectModified,
                                collapsedGroups = collapsedGroups,
                                setGroupCollapsed = { id, collapsed ->
                                    collapsedGroups = if (collapsed) collapsedGroups + id else collapsedGroups - id
                                }, canvasWidth = editor.canvasWidth, canvasHeight = editor.canvasHeight,
                                fit = { canvas?.fit() }, actual = { canvas?.actualPixels() }, zoom = ::stepZoom,
                                brushSettings = settings, setBrushSettings = { settings = it },
                                foreground = foreground, background = background,
                                chooseColor = { colorTarget = it }, swapColors = ::swapColors,
                                defaultColors = ::defaultColors,
                                setForeground = { foreground = it }, setBackground = { background = it },
                                collapsed = wide && inspectorCollapsed,
                                setCollapsed = { if (wide) inspectorCollapsed = it else layersOpen = !it },
                                textDraft = textDraft?.copy(commitInFlight = typedCommitInFlight), selectedText = selectedTextStyle != null,
                                beginNewText = ::beginNewText, beginEditText = ::beginEditText,
                                updateTextDraft = { if (!typedSubmissions.busy && textDraft?.submissionId == it.submissionId) textDraft = it },
                                commitText = ::commitText, cancelText = { if (!typedSubmissions.busy) textDraft = null },
                                adjustmentDraft = adjustmentDraft?.takeUnless { !it.style.kind.isEditable && typedCommitInFlight }
                                    ?.copy(commitInFlight = typedCommitInFlight), selectedAdjustment = selectedAdjustmentStyle?.kind?.isEditable == true,
                                selectedAdjustmentEditable = selectedAdjustmentEditable,
                                beginNewAdjustment = ::beginNewAdjustment, beginEditAdjustment = ::beginEditAdjustment,
                                updateAdjustmentDraft = { if (!typedSubmissions.busy && adjustmentDraft?.submissionId == it.submissionId) adjustmentDraft = it }, commitAdjustment = ::commitAdjustment,
                                cancelAdjustment = ::cancelAdjustment,
                                levelsHistogram = levelsHistogram,
                                levelsSamplingBusy = levelsSamplingBusy, adjustmentCreationBusy = adjustmentCreationId != null,
                                adjustmentPreviewFailed = canvas?.adjustmentPreviewFailed == true,
                                retryLevelsHistogram = { if (!typedSubmissions.busy) ++levelsHistogramAttempt })
                        }
                    }
                    if (!wide && !landscape && layersOpen) {
                        HorizontalDivider()
                        InspectorDock(inspectorPanel, { inspectorPanelName = it.name }, state, selected, select,
                            { action -> if (enabled) canvas?.command(action) }, maskTarget, setMaskTarget,
                            Modifier.fillMaxWidth().height(260.dp),
                            enabled = enabled && !typedCommitInFlight && canvas?.moveInProgress != true && canvas?.moveContextPending != true && canvas?.magicPending != true,
                            selectionEnabled = enabled && adjustmentDraft == null && canvas?.canChangeTool == true,
                            targetEnabled = enabled && adjustmentDraft == null && canvas?.canChangeTool == true,
                                selectedRoots = authority.roots.toSet(), selectModified = selectModified,
                                collapsedGroups = collapsedGroups,
                                setGroupCollapsed = { id, collapsed ->
                                    collapsedGroups = if (collapsed) collapsedGroups + id else collapsedGroups - id
                            }, canvasWidth = editor.canvasWidth, canvasHeight = editor.canvasHeight,
                            fit = { canvas?.fit() }, actual = { canvas?.actualPixels() }, zoom = ::stepZoom,
                            brushSettings = settings, setBrushSettings = { settings = it },
                            foreground = foreground, background = background,
                            chooseColor = { colorTarget = it }, swapColors = ::swapColors,
                            defaultColors = ::defaultColors,
                            setForeground = { foreground = it }, setBackground = { background = it },
                            collapsed = false, setCollapsed = {},
                            textDraft = textDraft?.copy(commitInFlight = typedCommitInFlight), selectedText = selectedTextStyle != null,
                            beginNewText = ::beginNewText, beginEditText = ::beginEditText,
                            updateTextDraft = { if (!typedSubmissions.busy && textDraft?.submissionId == it.submissionId) textDraft = it },
                            commitText = ::commitText, cancelText = { if (!typedSubmissions.busy) textDraft = null },
                            adjustmentDraft = adjustmentDraft?.takeUnless { !it.style.kind.isEditable && typedCommitInFlight }
                                    ?.copy(commitInFlight = typedCommitInFlight), selectedAdjustment = selectedAdjustmentStyle?.kind?.isEditable == true,
                            selectedAdjustmentEditable = selectedAdjustmentEditable,
                            beginNewAdjustment = ::beginNewAdjustment, beginEditAdjustment = ::beginEditAdjustment,
                            updateAdjustmentDraft = { if (!typedSubmissions.busy && adjustmentDraft?.submissionId == it.submissionId) adjustmentDraft = it }, commitAdjustment = ::commitAdjustment,
                            cancelAdjustment = ::cancelAdjustment,
                                levelsHistogram = levelsHistogram,
                                levelsSamplingBusy = levelsSamplingBusy, adjustmentCreationBusy = adjustmentCreationId != null,
                                adjustmentPreviewFailed = canvas?.adjustmentPreviewFailed == true,
                                retryLevelsHistogram = { if (!typedSubmissions.busy) ++levelsHistogramAttempt })
                    }
                }
            }
            // The status strip is useful when the full editor is visible, but in a
            // resized landscape IME window it would consume the last Canvas pixels.
            if (!landscapeIme) {
                HorizontalDivider()
                WorkspaceStatus(editor.canvasWidth, editor.canvasHeight, zoomPercent, tool, erasing, maskTarget, projects.state.running)
            }
        }
    }
    colorTarget?.let { target ->
        EditorColorPicker(if (target) background else foreground, target) { chosen ->
            if (chosen != null) { if (target) background = chosen else foreground = chosen }
            colorTarget = null
        }
    }
    val problem = errorNoticeName?.let { UiNotice(UiNoticeKey.valueOf(it)).text(ui) } ?: error ?: workspaceState.localizedSaveError(ui)
    problem?.let { message ->
        var dismissed by remember(message) { mutableStateOf(false) }
        if (!dismissed) AlertDialog(onDismissRequest = { error = null; errorNoticeName = null; dismissed = true }, title = { Text("Compositor") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null; errorNoticeName = null; dismissed = true }) { Text(ui.getString(R.string.ui_ok)) } })
    }
    if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text(ui.getString(R.string.ui_diagnostics)) },
        text = { Column {
            Text(if (workspaceState.savedRevision == state.revision) ui.getString(R.string.ui_recovery_copy_is_up_to_date) else ui.getString(R.string.ui_updating_recovery_copy))
            Text(if (workspaceState.projectDirty) ui.getString(R.string.ui_unsaved_project_changes) else ui.getString(R.string.ui_project_is_up_to_date))
            Text(ui.getString(R.string.ui_raster_memory, state.bytes / 1048576.0))
            Text(ui.getString(R.string.ui_desktop_tools_shown_as_unavailable_are_not_implemented_in_this_build))
        } }, confirmButton = { TextButton(onClick = { diagnostics = false }) { Text(ui.getString(R.string.ui_close)) } })
}
