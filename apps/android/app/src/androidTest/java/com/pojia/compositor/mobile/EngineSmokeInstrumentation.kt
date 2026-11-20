package com.pojia.compositor.mobile

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.nio.ByteBuffer

/** Exercises the actual JNI ABI, native transactions, pixels, and idempotent lifetime on device. */
class EngineSmokeInstrumentation : Instrumentation() {
    private var included: Set<String>? = null
    private var benchmarkLabel = "validation"
    private var evidenceLabel: String? = null
    private var lockLayerNavigationTask = false
    private var hslPhase = "all"
    private var landscapeImeLanguage = "en"
    private var shortInspectorLanguage = "en"
    private var projectV21Language = "all"
    private var projectSourceVersion = 21
    private var sustainedSeconds = 0
    private var denseLayers = 8
    private var p104FixtureNames = listOf("static-group-mask.comp", "line-dashed-square.comp", "typed-text-chinese.comp",
        "adjustment-hsv.comp", "adjustment-levels.comp", "adjustment-curves.comp", "adjustment-exposure.comp",
        "adjustment-gradient-map.comp", "adjustment-grain.comp", "adjustment-invert.comp",
        "adjustment-black-white.comp", "adjustment-color-balance.comp")
    private var p104AnimatedFixtureName = "animated.comp"
    private var p104RejectedFixtureNames = emptyList<String>()
    private var p104FixtureDirectory = "p104-fixtures"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        included = arguments?.getString("include")?.split(',')?.map { it.trim() }?.toSet()
        benchmarkLabel = arguments?.getString("benchmarkLabel") ?: "validation"
        evidenceLabel = arguments?.getString("evidenceLabel")
        lockLayerNavigationTask = arguments?.getString("lockLayerNavigationTask") == "true"
        hslPhase = arguments?.getString("hslPhase") ?: "all"
        landscapeImeLanguage = arguments?.getString("landscapeImeLanguage") ?: "en"
        shortInspectorLanguage = arguments?.getString("shortInspectorLanguage") ?: "en"
        projectV21Language = arguments?.getString("projectV21Language") ?: "all"
        projectSourceVersion = arguments?.getString("projectSourceVersion")?.toInt() ?: 21
        sustainedSeconds = arguments?.getString("sustainedSeconds")?.toInt() ?: 0
        denseLayers = arguments?.getString("denseLayers")?.toInt() ?: 8
        arguments?.getString("p104Fixtures")?.let { p104FixtureNames = it.split(',').map { name -> name.trim() } }
        p104AnimatedFixtureName = arguments?.getString("p104AnimatedFixture") ?: "animated.comp"
        p104RejectedFixtureNames = arguments?.getString("p104RejectedFixtures")?.split(',')?.map { it.trim() } ?: emptyList()
        p104FixtureDirectory = arguments?.getString("p104FixtureDirectory") ?: "p104-fixtures"
        require(sustainedSeconds in 0..1200 && denseLayers in 1..8)
        start()
    }
    override fun onStart() {
        val report = Bundle()
        try {
            val focusedDuplicateNudge = included?.singleOrNull()?.takeIf {
                it in setOf("duplicateNudgeUi", "duplicateNudgeMaskUi", "duplicateNudgeGroupUi")
            }
            if (focusedDuplicateNudge != null) {
                val result = duplicateNudgeSmoke(this, focusedDuplicateNudge)
                report.putString(focusedDuplicateNudge, result)
                report.putString("stream", "Focused smoke passed: $focusedDuplicateNudge\n$result\n")
                finish(Activity.RESULT_OK, report)
                return
            }
            included?.let { selection ->
                check(selection.isNotEmpty() && selection.all { it in setOf("projectV21Ui", "shortInspectorUi", "landscapeImeUi", "selectedLayerActions", "selectedLayerActionsUi", "flattenedTiff", "levelsSample", "levelsSampleInput", "levelsHandles", "levelsAuto", "levelsInput", "exposureInput", "adjustmentCreate", "adjustmentPreview", "adjustmentPreviewInteraction", "adjustmentWorkflow", "vignette", "layerAlignment", "gpuHomeReturn", "textInputUiOnly", "textBoxUiOnly", "sampledMaskSpan", "addNoiseUiOnly", "cpuDisplayFailure", "samplingScratch", "p104ProjectInterop", "distortSceneOwnership", "typedLayerRecreation", "toolCanvasWorkflow", "typedLayerSubmission", "selectionGestureCanvas", "adjustmentLayerNative", "displayPublicationAdmission", "layerFilters", "textTool", "textLayerNative", "typedLayerUiOnly", "gpuPolygonClipOnly", "devicePerformanceOnly", "paletteAccessibilityOnly", "localizationValidationUiOnly", "localizationHealingUiOnly", "hslOpacityGpuOnly", "healingNativeOnly", "healingUiOnly", "sourceOverGpuOnly", "smearUiOnly", "localizationUiOnly", "smearNativeOnly", "selectionFillTransformNativeOnly", "selectionSoftStrokeNativeOnly", "selectionAmountNativeOnly", "lineShapeNativeOnly", "lineShapeUiOnly", "pressureNativeOnly", "pressureUiOnly", "cloneSelectionNativeOnly", "cloneSelectionUiOnly", "cloneStampUiOnly", "cloneStampNativeOnly", "magicWandNativeOnly", "displayCommandGateOnly", "selectionAmountContract", "editorInitialization", "editorRecreationHeldLock", "workspaceFlushAsync", "scrubGeometry", "scrubUiPhone", "scrubUiWide", "displaySceneAdmission", "groupScaleGeometry", "groupScaleCanvas", "groupScaleUiPhone", "groupScaleUiWide", "duplicateNative", "duplicateController", "duplicateCanvas", "duplicateUiPhone", "duplicateUiWide", "duplicateWorkspaceIo", "moveAuthorityUiPhone", "moveAuthorityUiWide", "moveAuthority", "moveAuthorityWorkspace", "moveAuthorityCanvas", "workspaceContentIdentity", "contentIdentity", "maskIntegerSampling", "folderMaskOwner", "folderMaskIntersection", "folderMaskRotation", "folderMaskMinified", "folderMaskEdge", "folderMaskChildEdge", "folderMaskChildTexel", "folderMaskSolidFringe", "folderMaskMinifiedChild", "folderMaskChildMidpoint", "offsetRasterMask", "rasterMaskPhase", "rasterMaskCorner", "rasterMaskNearFull", "rasterMaskRotation", "rasterMaskCardinal", "rasterMaskHalfEdge", "rasterMaskHalfRow", "checkpointTransformWorkspace", "checkpointTransform", "moveInspectorMask", "moveInspectorPixels", "transformInspectorController", "moveInspectorGeometry", "moveInspectorUi", "movePersistent", "moveHandles", "layerMiddleScrollWide", "layerMiddleScrollNarrow", "mouseWheelInput", "mouseZoomInput", "mousePanInput", "moveSwitch", "moveGeometry", "moveCanvas", "transformController", "transformDraft", "eyedropper", "eyedropperCanvas", "eyedropperUi", "imageProcessPrepare", "imageProcessVerify", "imageRequest", "imageDocumentOpen", "projectCopyName", "saveAndOpen", "saveCurrentAndOpen", "saveCurrentAndNew", "preferenceFailure", "fileShortcutsUi", "imagePickerUi", "imageBudget", "resourceLedger", "imageIo", "imageIoPng", "imageIoJpeg", "imageIoPngJpeg", "imageIoDeep", "imageIoTiff", "imageIoHeic", "relationships", "groups", "precision", "recovery", "recoveryAdmission", "immutableCheckpoint", "classifiedCheckpoint", "checkpointSnapshot", "snapshotAdmission", "commandQueue", "lifecycle", "metadataUi", "lod", "projectCandidate", "workspaceReplacement", "admissionPolicy", "projectTransport", "projectSave", "admission", "projectWorkflowUi", "projectFlowLifecycle", "ciOpacity", "tileViews", "gpuRaster", "strokeGrid", "strokeGridUi", "metadataBulk", "previewInput", "projectExport", "projectExportUi", "structureUi", "projectExportKillPrepare", "projectExportProcessRecovery", "strokePipeline", "strokePipelineValidation", "strokeDiagnostic", "strokeDiagnosticValidation", "blendGpu", "groupBlendGolden", "divideGolden", "brushSettings", "shape", "cropGeometry", "gradientGeometry", "workspaceUi", "canvasSmoothing", "shiftLine", "newCanvasLifecycle", "newCanvasUi", "pendingCommand", "retainedCommand", "cropRatioUiOnly") }) {
                    "Supported include values are projectV21Ui,shortInspectorUi,landscapeImeUi,adjustmentCreate,cpuDisplayFailure,samplingScratch,p104ProjectInterop,adjustmentLayerNative,typedLayerUiOnly,workspaceContentIdentity,contentIdentity,maskIntegerSampling,checkpointTransformWorkspace,checkpointTransform,moveInspectorMask,moveInspectorPixels,transformInspectorController,moveInspectorGeometry,moveInspectorUi,mouseWheelInput,mouseZoomInput,mousePanInput,transformDraft,eyedropper,eyedropperCanvas,eyedropperUi,imageIo,imageIoPng,imageIoJpeg,imageIoPngJpeg,imageIoDeep,imageIoTiff,imageIoHeic,relationships,groups,precision,recovery,recoveryAdmission,immutableCheckpoint,classifiedCheckpoint,checkpointSnapshot,snapshotAdmission,commandQueue,lifecycle,metadataUi,lod,projectCandidate,workspaceReplacement,admissionPolicy,projectTransport,projectSave,admission,projectWorkflowUi,projectFlowLifecycle,ciOpacity,tileViews,gpuRaster,strokeGrid,strokeGridUi,metadataBulk,previewInput,projectExport,projectExportUi,structureUi,projectExportKillPrepare,projectExportProcessRecovery,selectionAmountContract,shape,cropGeometry,cropRatioUiOnly,gradientGeometry,textTool; omit include to run all checks"
                }
                if ("projectV21Ui" in selection) {
                    check(selection.size == 1) { "Public project compatibility checks must be isolated" }
                    val result = projectV21CompatibilityUiSmoke(this, projectV21Language, projectSourceVersion)
                    report.putString("projectV21Ui", result)
                    report.putString("stream", "Focused smoke passed: projectV21Ui\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("shortInspectorUi" in selection) {
                    check(selection.size == 1) { "Short Inspector UI checks must be isolated" }
                    val result = shortInspectorUiSmoke(this, shortInspectorLanguage)
                    report.putString("shortInspectorUi", result)
                    report.putString("stream", "Focused smoke passed: shortInspectorUi\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("landscapeImeUi" in selection) {
                    check(selection.size == 1) { "Landscape IME UI checks must be isolated" }
                    val result = landscapeImeUiSmoke(this, landscapeImeLanguage)
                    report.putString("landscapeImeUi", result)
                    report.putString("stream", "Focused smoke passed: landscapeImeUi\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("selectedLayerActions" in selection) {
                    check(selection.size == 1)
                    report.putString("selectedLayerActions", selectedLayerActionsSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("selectedLayerActionsUi" in selection) {
                    check(selection.size == 1)
                    report.putString("selectedLayerActionsUi", selectedLayerActionsUiSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("levelsSample" in selection) {
                    check(selection.size == 1)
                    report.putString("levelsSample", levelsSampleSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("levelsSampleInput" in selection) {
                    check(selection.size == 1)
                    report.putString("levelsSampleInput", levelsSampleInputFenceSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("levelsHandles" in selection) {
                    check(selection.size == 1)
                    report.putString("levelsHandles", levelsHandlesSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("levelsAuto" in selection) {
                    check(selection.size == 1)
                    report.putString("levelsAuto", levelsAutoSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("levelsInput" in selection) {
                    check(selection.size == 1)
                    report.putString("levelsInput", levelsInputSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("exposureInput" in selection) {
                    check(selection.size == 1)
                    report.putString("exposureInput", exposureInputSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("adjustmentCreate" in selection) {
                    check(selection.size == 1)
                    report.putString("adjustmentCreate", adjustmentCreateSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("adjustmentPreview" in selection) {
                    check(selection.size == 1)
                    report.putString("adjustmentPreview", adjustmentPreviewSmoke())
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("adjustmentPreviewInteraction" in selection) {
                    check(selection.size == 1)
                    val result = adjustmentPreviewInteractionSmoke(this)
                    report.putString("adjustmentPreviewInteraction", result)
                    report.putString("stream", "Focused smoke passed: adjustmentPreviewInteraction\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("adjustmentWorkflow" in selection) {
                    check(selection.size == 1)
                    val result = adjustmentWorkflowSmoke(this)
                    report.putString("adjustmentWorkflow", result)
                    report.putString("stream", "Focused smoke passed: adjustmentWorkflow\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("vignette" in selection) {
                    check(selection.size == 1)
                    val result = vignetteSmoke(this)
                    report.putString("vignette", result)
                    report.putString("stream", "Focused smoke passed: vignette\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("layerAlignment" in selection) {
                    check(selection.size == 1) { "Layer alignment UI checks must be isolated" }
                    val result = layerAlignmentSmoke(this)
                    report.putString("layerAlignment", result)
                    report.putString("stream", "Focused smoke passed: layerAlignment\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("cpuDisplayFailure" in selection) {
                    check(selection.size == 1)
                    val result = cpuDisplayFailureSmoke()
                    report.putString("cpuDisplayFailure", result)
                    report.putString("stream", "Focused smoke passed: cpuDisplayFailure\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("sampledMaskSpan" in selection) {
                    check(selection.size == 1) { "Sampled mask span checks must be isolated" }
                    val result = sampledMaskSpanSmoke(this)
                    report.putString("sampledMaskSpan", result)
                    report.putString("stream", "Focused smoke passed: sampledMaskSpan\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("samplingScratch" in selection) {
                    check(selection.size == 1) { "Sampling scratch checks must be isolated" }
                    val result = samplingScratchSmoke(this)
                    report.putString("samplingScratch", result)
                    report.putString("stream", "Focused smoke passed: samplingScratch\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("p104ProjectInterop" in selection) {
                    check(selection.size == 1) { "P104 project interop checks must be isolated" }
                    val result = p104ProjectInteropSmoke(this, p104FixtureNames, p104AnimatedFixtureName, p104RejectedFixtureNames, p104FixtureDirectory)
                    report.putString("p104ProjectInterop", result)
                    report.putString("stream", "Focused smoke passed: p104ProjectInterop\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("distortSceneOwnership" in selection) {
                    check(selection.size == 1)
                    val result = distortSceneOwnershipSmoke(this)
                    report.putString("distortSceneOwnership", result)
                    report.putString("stream", "Focused smoke passed: distortSceneOwnership\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("gpuHomeReturn" in selection) {
                    check(selection.size == 1)
                    report.putString("gpuHomeReturn", gpuHomeReturnSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("typedLayerRecreation" in selection) {
                    check(selection.size == 1)
                    val result = typedLayerRecreationSmoke(this)
                    report.putString("typedLayerRecreation", result)
                    report.putString("stream", "Focused smoke passed: typedLayerRecreation\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("toolCanvasWorkflow" in selection) {
                    check(selection.size == 1)
                    val result = toolCanvasWorkflowSmoke(this)
                    report.putString("toolCanvasWorkflow", result)
                    report.putString("stream", "Focused smoke passed: toolCanvasWorkflow\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("typedLayerSubmission" in selection || "selectionGestureCanvas" in selection) {
                    check(selection.size == 1) { "P103 regression checks must be isolated" }
                    val name = selection.single()
                    val result = if (name == "typedLayerSubmission") typedLayerSubmissionSmoke()
                        else selectionGestureCanvasSmoke(this)
                    report.putString(name, result)
                    report.putString("stream", "Focused smoke passed: $name\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("adjustmentLayerNative" in selection) {
                    check(selection.size == 1) { "Adjustment layer checks must be isolated" }
                    report.putString("adjustmentLayerNative", adjustmentLayerNativeSmoke(targetContext, context.assets))
                    report.putString("stream", "Focused smoke passed: adjustmentLayerNative\n${report.getString("adjustmentLayerNative")}\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("layerFilters" in selection) {
                    check(selection.size == 1) { "Layer filter checks must be isolated" }
                    val result = layerFilterSmoke(this)
                    report.putString("layerFilters", result)
                    report.putString("stream", "Focused smoke passed: layerFilters\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("textTool" in selection) {
                    check(selection.size == 1) { "Text tool checks must be isolated" }
                    val result = textToolSmoke()
                    report.putString("textTool", result)
                    report.putString("stream", "Focused smoke passed: textTool\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("textLayerNative" in selection) {
                    check(selection.size == 1) { "Native text checks must be isolated" }
                    report.putString("textLayerNative", textLayerNativeSmoke(targetContext))
                    report.putString("stream", "Focused smoke passed: textLayerNative\n${report.getString("textLayerNative")}\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("typedLayerUiOnly" in selection || "addNoiseUiOnly" in selection || "textBoxUiOnly" in selection || "textInputUiOnly" in selection) {
                    check(selection.size == 1) { "Typed layer UI checks must be isolated" }
                    val name = selection.single()
                    val result = typedLayerUiSmoke(this, noiseOnly = name == "addNoiseUiOnly", textBoxOnly = name == "textBoxUiOnly", textInputOnly = name == "textInputUiOnly")
                    report.putString(name, result)
                    report.putString("stream", "Focused smoke passed: $name\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("gpuPolygonClipOnly" in selection) {
                    check(selection.size == 1)
                    report.putString("gpuPolygonClip", gpuPolygonClipSmoke())
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("paletteAccessibilityOnly" in selection) {
                    check(selection.size == 1)
                    report.putString("paletteAccessibility", paletteAccessibilitySmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("devicePerformanceOnly" in selection) {
                    check(selection.size == 1)
                    NativeEditor(addDefaultLayers = false).use { editor ->
                        report.putString("devicePerformance", gpuSmoke(editor, independentGolden = {
                            devicePerformanceSmoke(this, benchmarkLabel, sustainedSeconds, denseLayers)
                        }))
                    }
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("hslOpacityGpuOnly" in selection) {
                    check(selection.size == 1)
                    NativeEditor(addDefaultLayers = false).use { editor ->
                        report.putString("hslOpacityGpu", gpuSmoke(editor, independentGolden = { recreate ->
                            hslOpacityGpuSmoke(this, recreate, hslPhase)
                        }))
                    }
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("healingNativeOnly" in selection || "healingUiOnly" in selection) {
                    check(selection.size == 1)
                    val ui = "healingUiOnly" in selection
                    val result = if (ui) healingUiSmoke(this) else healingNativeSmoke(this)
                    report.putString(if (ui) "healingUi" else "healingNative", result)
                    report.putString("stream", "Focused smoke passed: ${selection.single()}\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("sourceOverGpuOnly" in selection) {
                    check(selection.size == 1)
                    NativeEditor(addDefaultLayers = false).use { editor ->
                        report.putString("sourceOverGpu", gpuSmoke(editor, independentGolden = { recreate -> sourceOverGpuSmoke(this, recreate) }))
                    }
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("smearUiOnly" in selection) {
                    check(selection.size == 1)
                    report.putString("smearUi", smearUiSmoke(this))
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("localizationValidationUiOnly" in selection) {
                    check(selection.size == 1)
                    val result = localizationUiSmoke(this, retainedNoticeKey = UiNoticeKey.DOCUMENT_RESOLUTION_INVALID)
                    report.putString("localizationValidationUi", result)
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("localizationUiOnly" in selection || "localizationHealingUiOnly" in selection || "smearNativeOnly" in selection) {
                    check(selection.size == 1) { "Localization and Smear checks must be isolated" }
                    val localization = "smearNativeOnly" !in selection
                    val result = if (localization) localizationUiSmoke(this,
                        if ("localizationHealingUiOnly" in selection) EditorTool.HEALING else EditorTool.BRUSH) else smearNativeSmoke(this)
                    report.putString(if ("localizationHealingUiOnly" in selection) "localizationHealingUi" else if (localization) "localizationUi" else "smearNative", result)
                    report.putString("stream", "Focused smoke passed: ${selection.single()}\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("selectionFillTransformNativeOnly" in selection) {
                    check(selection.size == 1) { "Bounded transformed Fill JNI checks must be isolated" }
                    val result = selectionFillTransformNativeSmoke(this)
                    report.putString("selectionFillTransformNative", result)
                    report.putString("stream", "Focused smoke passed: selectionFillTransformNativeOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("selectionSoftStrokeNativeOnly" in selection) {
                    check(selection.size == 1) { "Soft selection stroke JNI checks must be isolated" }
                    val result = selectionSoftStrokeNativeSmoke()
                    report.putString("selectionSoftStrokeNative", result)
                    report.putString("stream", "Focused smoke passed: selectionSoftStrokeNativeOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("selectionAmountNativeOnly" in selection) {
                    check(selection.size == 1) { "Selection amount JNI checks must be isolated" }
                    val result = selectionAmountNativeSmoke()
                    report.putString("selectionAmountNative", result)
                    report.putString("stream", "Focused smoke passed: selectionAmountNativeOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("pressureNativeOnly" in selection) {
                    check(selection.size == 1) { "Brush pressure checks must be isolated" }
                    val result = brushPressureNativeSmoke(this)
                    report.putString("pressureNative", result)
                    report.putString("stream", "Focused smoke passed: pressureNativeOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("pressureUiOnly" in selection) {
                    check(selection.size == 1) { "Brush pressure checks must be isolated" }
                    val result = brushPressureUiSmoke(this)
                    report.putString("pressureUi", result)
                    report.putString("stream", "Focused smoke passed: pressureUiOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("cloneSelectionNativeOnly" in selection) {
                    check(selection.size == 1) { "Clone selection checks must be isolated" }
                    val result = cloneSelectionNativeSmoke(this)
                    report.putString("cloneSelectionNative", result)
                    report.putString("stream", "Focused smoke passed: cloneSelectionNativeOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("cloneSelectionUiOnly" in selection) {
                    check(selection.size == 1) { "Clone selection checks must be isolated" }
                    val result = cloneSelectionUiSmoke(this)
                    report.putString("cloneSelectionUi", result)
                    report.putString("stream", "Focused smoke passed: cloneSelectionUiOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("cloneStampUiOnly" in selection) {
                    check(selection.size == 1) { "Clone Stamp UI checks must be isolated" }
                    val result = cloneStampUiSmoke(this)
                    report.putString("cloneStampUi", result)
                    report.putString("stream", "Focused smoke passed: cloneStampUiOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("cloneStampNativeOnly" in selection) {
                    check(selection.size == 1) { "Native Clone Stamp checks must be isolated" }
                    val result = cloneStampNativeSmoke(this)
                    report.putString("cloneStampNative", result)
                    report.putString("stream", "Focused smoke passed: cloneStampNativeOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("magicWandNativeOnly" in selection) {
                    check(selection.size == 1) { "Native Magic Wand checks must be isolated" }
                    val result = magicWandNativeSmoke(this)
                    report.putString("magicWandNative", result)
                    report.putString("stream", "Focused smoke passed: magicWandNativeOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("displayCommandGateOnly" in selection) {
                    check(selection.size == 1) { "Display command gate checks must be isolated" }
                    val result = displayCommandGateSmoke(this)
                    report.putString("displayCommandGate", result)
                    report.putString("stream", "Focused smoke passed: displayCommandGateOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("selectionAmountContract" in selection) {
                    check(selection.size == 1) { "Selection amount contract checks must be isolated" }
                    val result = selectionAmountContractSmoke()
                    report.putString("selectionAmountContract", result)
                    report.putString("stream", "Focused smoke passed: selectionAmountContract\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("strokePipeline" in selection || "strokePipelineValidation" in selection) {
                    check(selection.size == 1) { "Stroke pipeline runs must be isolated from correctness/GPU suites" }
                    val selected = selection.single()
                    report.putString(selected, strokePipelineSmoke(this, selected == "strokePipeline", benchmarkLabel))
                }
                if ("strokeDiagnostic" in selection || "strokeDiagnosticValidation" in selection) {
                    check(selection.size == 1) { "Diagnostic runs must be isolated" }
                    val selected = selection.single()
                    report.putString(selected, strokePipelineDiagnostic(this, selected == "strokeDiagnostic", benchmarkLabel))
                }
                if ("blendGpu" in selection) NativeEditor(addDefaultLayers = false).use { editor ->
                    report.putString("blendGpu", gpuSmoke(editor, independentGolden = { recreate -> blendGpuSmoke(this, recreate) }))
                }
                if ("groupBlendGolden" in selection) NativeEditor(addDefaultLayers = false).use { editor ->
                    report.putString("groupBlendGolden", gpuSmoke(editor, independentGolden = { recreate -> groupBlendGoldenSmoke(this, recreate, evidenceLabel) }))
                }
                if ("divideGolden" in selection) NativeEditor(addDefaultLayers = false).use { editor ->
                    check(selection.size == 1) { "Divide holdout runs must be isolated from other suites" }
                    report.putString("divideGolden", gpuSmoke(editor, independentGolden = { recreate -> divideGoldenSmoke(this, recreate, evidenceLabel ?: "divide-01") }))
                }
                if ("layerMiddleScrollWide" in selection || "layerMiddleScrollNarrow" in selection) {
                    check(selection.size == 1) { "Layer mouse UI layout checks must be isolated" }
                    val key = selection.single()
                    report.putString(key, layerMouseScrollSmoke(this, key == "layerMiddleScrollWide", lockLayerNavigationTask))
                }
                if ("moveAuthorityUiPhone" in selection) report.putString("moveAuthorityUiPhone", moveAuthorityUiSmoke(this, false))
                if ("scrubGeometry" in selection) report.putString("scrubGeometry", transformScrubGeometrySmoke())
                if ("scrubUiPhone" in selection) report.putString("scrubUiPhone", phoneScrubTransactionAndFocus(this))
                if ("scrubUiWide" in selection) report.putString("scrubUiWide", wideMouseScrubPersistentCancel(this))
                if ("displayPublicationAdmission" in selection) report.putString("displayPublicationAdmission", displayPublicationAdmissionSmoke(this))
                if ("displaySceneAdmission" in selection) {
                    check(selection.size == 1) { "Display scene admission checks must be isolated" }
                    report.putString("displaySceneAdmission", displaySceneAdmissionSmoke(this))
                }
                if ("groupScaleGeometry" in selection) report.putString("groupScaleGeometry", groupScaleGeometrySmoke())
                if ("groupScaleCanvas" in selection) report.putString("groupScaleCanvas", groupScaleCanvasSmoke(this))
                if ("groupScaleUiPhone" in selection) report.putString("groupScaleUiPhone", groupScaleUiSmoke(this, false))
                if ("groupScaleUiWide" in selection) report.putString("groupScaleUiWide", groupScaleUiSmoke(this, true))
                if ("duplicateNative" in selection) report.putString("duplicateNative", duplicateNativeSmoke(this))
                if ("duplicateController" in selection) report.putString("duplicateController", duplicateControllerSmoke(this))
                if ("duplicateCanvas" in selection) report.putString("duplicateCanvas", duplicateCanvasSmoke(this))
                if ("duplicateUiPhone" in selection) report.putString("duplicateUiPhone", duplicateUiSmoke(this, false))
                if ("duplicateUiWide" in selection) report.putString("duplicateUiWide", duplicateUiSmoke(this, true))
                if ("duplicateWorkspaceIo" in selection) report.putString("duplicateWorkspaceIo", duplicateWorkspaceIoSmoke(this))
                if ("moveAuthorityUiWide" in selection) report.putString("moveAuthorityUiWide", moveAuthorityUiSmoke(this, true))
                if ("moveAuthority" in selection) report.putString("moveAuthority", moveAuthoritySmoke(this))
                if ("moveAuthorityWorkspace" in selection) report.putString("moveAuthorityWorkspace", moveAuthorityWorkspaceSmoke(this))
                if ("moveAuthorityCanvas" in selection) report.putString("moveAuthorityCanvas", moveAuthorityCanvasSmoke(this))
                if ("workspaceContentIdentity" in selection) report.putString("workspaceContentIdentity", workspaceContentIdentitySmoke(this))
                if ("contentIdentity" in selection) report.putString("contentIdentity", contentIdentitySmoke(this))
                if ("mouseWheelInput" in selection) report.putString("mouseWheelInput", mouseWheelInputSmoke(this))
                if ("mouseZoomInput" in selection) report.putString("mouseZoomInput", mouseZoomInputSmoke(this))
                if ("mousePanInput" in selection) report.putString("mousePanInput", mousePanInputSmoke(this))
                if ("checkpointTransformWorkspace" in selection) {
                    check(selection.size == 1) { "Workspace checkpoint/transform accounting checks must be isolated" }
                    report.putString("checkpointTransformWorkspace", checkpointTransformWorkspaceSmoke(this))
                }
                if ("checkpointTransform" in selection) {
                    check(selection.size == 1) { "Checkpoint/transform accounting checks must be isolated" }
                    report.putString("checkpointTransform", checkpointTransformSmoke(this))
                }
                if ("transformInspectorController" in selection) report.putString("transformInspectorController", transformInspectorControllerSmoke(this))
                if ("moveInspectorGeometry" in selection) report.putString("moveInspectorGeometry", moveInspectorGeometrySmoke())
                if ("moveInspectorUi" in selection) report.putString("moveInspectorUi", moveInspectorUiSmoke(this))
                if ("moveInspectorMask" in selection) report.putString("moveInspectorMask", moveInspectorMaskSmoke(this))
                if ("moveInspectorPixels" in selection) report.putString("moveInspectorPixels", moveInspectorPixelsSmoke(this))
                if ("moveGeometry" in selection) report.putString("moveGeometry", moveGeometrySmoke())
                if ("moveHandles" in selection) report.putString("moveHandles", moveHandleGeometrySmoke())
                if ("movePersistent" in selection) report.putString("movePersistent", movePersistentSmoke(this))
                if ("moveSwitch" in selection) report.putString("moveSwitch", moveSwitchSmoke(this))
                if ("moveCanvas" in selection) report.putString("moveCanvas", moveCanvasSmoke(this))
                if ("transformController" in selection) report.putString("transformController", transformControllerFoundationSmoke(this))
                if ("transformDraft" in selection) report.putString("transformDraft", transformDraftSmoke(this))
                if ("eyedropper" in selection) report.putString("eyedropper", eyedropperSmoke(this))
                if ("eyedropperCanvas" in selection) report.putString("eyedropperCanvas", eyedropperCanvasSmoke(this))
                if ("eyedropperUi" in selection) report.putString("eyedropperUi", eyedropperUiSmoke(this))
                if ("brushSettings" in selection) report.putString("brushSettings", brushSettingsSmoke(this))
                if ("lineShapeNativeOnly" in selection) report.putString("lineShapeNative", lineShapeNativeSmoke(this))
                if ("lineShapeUiOnly" in selection) report.putString("lineShapeUi", lineShapeUiSmoke(this))
                if ("shape" in selection) report.putString("shape", shapeSmoke(this))
                if ("cropGeometry" in selection) report.putString("cropGeometry", cropGeometrySmoke(this))
                if ("cropRatioUiOnly" in selection) {
                    check(selection.size == 1) { "Crop ratio UI checks must be isolated" }
                    val result = cropRatioUiSmoke(this)
                    report.putString("cropRatioUiOnly", result)
                    report.putString("stream", "Focused smoke passed: cropRatioUiOnly\n$result\n")
                    finish(Activity.RESULT_OK, report)
                    return
                }
                if ("gradientGeometry" in selection) report.putString("gradientGeometry", gradientGeometrySmoke(this))
                if ("resourceLedger" in selection) report.putString("resourceLedger", ResourceBudgetLedgerSmoke.run())
                if ("imageBudget" in selection) report.putString("imageBudget", imageBudgetSmoke(targetContext))
                if ("imageDocumentOpen" in selection) report.putString("imageDocumentOpen", imageDocumentOpenSmoke(this))
                if ("projectCopyName" in selection) report.putString("projectCopyName", projectCopyNameSmoke(this))
                if ("preferenceFailure" in selection) report.putString("preferenceFailure", preferenceFailureSmoke(this))
                if ("saveCurrentAndNew" in selection) report.putString("saveCurrentAndNew", saveCurrentAndNewSmoke(this))
                if ("saveCurrentAndOpen" in selection) report.putString("saveCurrentAndOpen", saveCurrentAndOpenSmoke(this))
                if ("saveAndOpen" in selection) report.putString("saveAndOpen", saveAndOpenSmoke(this))
                if ("fileShortcutsUi" in selection) report.putString("fileShortcutsUi", projectWorkflowUiSmoke(this, fileShortcuts = true))
                if ("imageRequest" in selection) report.putString("imageRequest", imageRequestSmoke(this))
                if ("imagePickerUi" in selection) report.putString("imagePickerUi", imagePickerUiSmoke(this))
                if ("imageProcessPrepare" in selection) {
                    check(selection.size == 1) { "Process preparation must be isolated" }
                    report.putString("imageProcessPrepare", imageProcessPrepareSmoke(this))
                }
                if ("imageProcessVerify" in selection) {
                    check(selection.size == 1) { "Process verification must be isolated" }
                    report.putString("imageProcessVerify", imageProcessVerifySmoke(this))
                }
                if ("canvasSmoothing" in selection) report.putString("canvasSmoothing", canvasSmoothingSmoke(this))
                if ("shiftLine" in selection) report.putString("shiftLine", shiftLineSmoke(this))
                if ("pendingCommand" in selection) report.putString("pendingCommand", pendingCommandSmoke(this))
                if ("editorInitialization" in selection) report.putString("editorInitialization", editorInitializationSmoke(this))
                if ("editorRecreationHeldLock" in selection) report.putString("editorRecreationHeldLock", editorRecreationHeldLockSmoke(this))
                if ("workspaceFlushAsync" in selection) {
                    check(selection.size == 1) { "Workspace flush lifecycle checks must be isolated" }
                    report.putString("workspaceFlushAsync", workspaceFlushAsyncSmoke(this))
                }
                if ("retainedCommand" in selection) {
                    check(selection.size == 1) { "Retained command lifecycle runs must be isolated" }
                    report.putString("retainedCommand", retainedCommandSmoke(this))
                }
                if ("newCanvasLifecycle" in selection) report.putString("newCanvasLifecycle", newCanvasLifecycleSmoke(this))
                if ("newCanvasUi" in selection) report.putString("newCanvasUi", newCanvasUiSmoke(this))
                if ("metadataBulk" in selection) report.putString("metadataBulk", metadataBulkSmoke(this))
                if ("projectExportKillPrepare" in selection) report.putString("projectExportKillPrepare", projectExportUiSmoke(this, true))
                if ("projectExportProcessRecovery" in selection) report.putString("projectExportProcessRecovery", projectExportProcessRecoverySmoke(this))
                if ("projectExport" in selection) report.putString("projectExport", projectExportSmoke(this))
                if ("flattenedTiff" in selection) {
                    check(selection.size == 1) { "Flattened TIFF runs in isolation" }
                    report.putString("flattenedTiff", flattenedTiffSmoke(targetContext))
                }
                if ("imageIo" in selection) {
                    report.putString("imageIo", imageIOSmoke(targetContext))
                }
                for ((selector, phase) in listOf(
                    "imageIoPng" to "png",
                    "imageIoJpeg" to "jpeg",
                    "imageIoPngJpeg" to "pngJpeg",
                    "imageIoDeep" to "deep",
                    "imageIoTiff" to "tiff",
                    "imageIoHeic" to "heic")) {
                    if (selector in selection) {
                        check(selection.size == 1) { "$selector must be run in isolation" }
                        val result = imageIOSmoke(targetContext, phase)
                        report.putString(selector, result)
                    }
                }
                if ("relationships" in selection) NativeEditor(addDefaultLayers = false).use {
                    report.putString("relationships", gpuSmoke(it, relationshipsOnly = true))
                }
                if ("groups" in selection) NativeEditor(addDefaultLayers = false).use {
                    report.putString("groups", gpuSmoke(it, groupsOnly = true))
                }
                if ("precision" in selection) {
                    val model = precisionMetadataSmoke(targetContext)
                    val gpu = NativeEditor(addDefaultLayers = false).use { gpuSmoke(it, precisionOnly = true) }
                    report.putString("precision", "$model\n$gpu")
                }
                if ("recovery" in selection) report.putString("recovery", recoverySmoke(targetContext))
                if ("recoveryAdmission" in selection) report.putString("recoveryAdmission", recoveryAdmissionSmoke(targetContext))
                if ("immutableCheckpoint" in selection) report.putString("immutableCheckpoint", immutableCheckpointSmoke(this))
                if ("classifiedCheckpoint" in selection) report.putString("classifiedCheckpoint", classifiedCheckpointSmoke(this))
                if ("checkpointSnapshot" in selection) report.putString("checkpointSnapshot", checkpointSnapshotSmoke(this))
                if ("snapshotAdmission" in selection) report.putString("snapshotAdmission", snapshotAdmissionSmoke(this))
                if ("commandQueue" in selection) report.putString("commandQueue", commandQueueSynchronizationSmoke(this))
                if ("lifecycle" in selection) report.putString("lifecycle", activityRecoverySmoke(this) + "\n" + gpuLifecycleSmoke(this))
                if ("metadataUi" in selection) report.putString("metadataUi", metadataUiSmoke(this))
                if ("lod" in selection) NativeEditor(addDefaultLayers = false).use { report.putString("lod", gpuSmoke(it, lodOnly = true)) }
                if ("projectCandidate" in selection) report.putString("projectCandidate", projectCandidateSmoke(this))
                if ("workspaceReplacement" in selection) report.putString("workspaceReplacement", workspaceReplacementSmoke(this))
                if ("admissionPolicy" in selection) report.putString("admissionPolicy", ProjectAdmissionPolicySmoke.run())
                if ("projectTransport" in selection) report.putString("projectTransport", projectTransportSmoke(this))
                if ("projectSave" in selection) report.putString("projectSave", projectSaveSmoke(this))
                if ("projectWorkflowUi" in selection) report.putString("projectWorkflowUi", projectWorkflowUiSmoke(this))
                if ("tileViews" in selection) NativeEditor(8, addDefaultLayers = false).use {
                    report.putString("tileViews", gpuSmoke(it, independentGolden = { recreate -> tileViewGpuSmoke(this, recreate) }))
                }
                if ("gpuRaster" in selection) NativeEditor().use { report.putString("gpuRaster", gpuSmoke(it)) }
                if ("ciOpacity" in selection) NativeEditor(8, addDefaultLayers = false).use {
                    report.putString("ciOpacity", gpuSmoke(it, independentGolden = { ciOpacityGoldenSmoke(this) }))
                }
                if ("folderMaskOwner" in selection) report.putString("folderMaskOwner", folderMaskOwnerSmoke(this, FolderMaskFixture.OWNER))
                if ("folderMaskRotation" in selection) report.putString("folderMaskRotation", folderMaskOwnerSmoke(this, FolderMaskFixture.ROTATION))
                if ("folderMaskMinified" in selection) report.putString("folderMaskMinified", folderMaskOwnerSmoke(this, FolderMaskFixture.MINIFIED))
                if ("folderMaskEdge" in selection) report.putString("folderMaskEdge", folderMaskOwnerSmoke(this, FolderMaskFixture.EDGE))
                if ("folderMaskChildEdge" in selection) report.putString("folderMaskChildEdge", folderMaskOwnerSmoke(this, FolderMaskFixture.CHILD_EDGE))
                if ("folderMaskChildTexel" in selection) report.putString("folderMaskChildTexel", folderMaskOwnerSmoke(this, FolderMaskFixture.CHILD_TEXEL))
                if ("folderMaskSolidFringe" in selection) report.putString("folderMaskSolidFringe", folderMaskOwnerSmoke(this, FolderMaskFixture.SOLID_FRINGE))
                if ("folderMaskMinifiedChild" in selection) report.putString("folderMaskMinifiedChild", folderMaskOwnerSmoke(this, FolderMaskFixture.MINIFIED_CHILD))
                if ("folderMaskChildMidpoint" in selection) report.putString("folderMaskChildMidpoint", folderMaskOwnerSmoke(this, FolderMaskFixture.CHILD_MIDPOINT))
                if ("folderMaskIntersection" in selection) report.putString("folderMaskIntersection", folderMaskOwnerSmoke(this, FolderMaskFixture.INTERSECTION))
                if ("offsetRasterMask" in selection) report.putString("offsetRasterMask", folderMaskOwnerSmoke(this, FolderMaskFixture.OFFSET_RASTER_MASK))
                if ("rasterMaskPhase" in selection) report.putString("rasterMaskPhase", folderMaskOwnerSmoke(this, FolderMaskFixture.RASTER_MASK_PHASE))
                if ("rasterMaskCorner" in selection) report.putString("rasterMaskCorner", folderMaskOwnerSmoke(this, FolderMaskFixture.RASTER_MASK_CORNER))
                if ("rasterMaskNearFull" in selection) report.putString("rasterMaskNearFull", folderMaskOwnerSmoke(this, FolderMaskFixture.RASTER_MASK_NEAR_FULL))
                if ("rasterMaskRotation" in selection) report.putString("rasterMaskRotation", folderMaskOwnerSmoke(this, FolderMaskFixture.RASTER_MASK_ROTATION))
                if ("rasterMaskCardinal" in selection) report.putString("rasterMaskCardinal", folderMaskOwnerSmoke(this, FolderMaskFixture.RASTER_MASK_CARDINAL))
                if ("rasterMaskHalfEdge" in selection) report.putString("rasterMaskHalfEdge", folderMaskOwnerSmoke(this, FolderMaskFixture.RASTER_MASK_HALF_EDGE))
                if ("rasterMaskHalfRow" in selection) report.putString("rasterMaskHalfRow", folderMaskOwnerSmoke(this, FolderMaskFixture.RASTER_MASK_HALF_ROW))
                if ("maskIntegerSampling" in selection) {
                    check(selection.size == 1) { "Mask integer sampling/ledger checks must be isolated" }
                    NativeEditor(8, addDefaultLayers = false).use {
                        report.putString("maskIntegerSampling", gpuSmoke(it, independentGolden = { maskIntegerSamplingSmoke(this) }))
                    }
                }
                if ("projectFlowLifecycle" in selection) report.putString("projectFlowLifecycle", projectFlowLifecycleSmoke(this))
                if ("projectExportUi" in selection) report.putString("projectExportUi", projectExportUiSmoke(this))
                // The stroke UI mutates revision/dirty state; run after the clean-project picker fixture.
                if ("strokeGridUi" in selection) report.putString("strokeGridUi", strokeGridUiSmoke(this))
                if ("strokeGrid" in selection) {
                    val graphics = NativeEditor(8, addDefaultLayers = false).use {
                        gpuSmoke(it, independentGolden = { recreate -> strokeGridGpuSmoke(this, recreate) })
                    }
                    report.putString("strokeGrid", graphics + "\n" + strokeGridUiSmoke(this))
                }
                if ("previewInput" in selection) report.putString("previewInput", previewInputSmoke(this))
                if ("workspaceUi" in selection) report.putString("workspaceUi", workspaceUiSmoke(this))
                if ("structureUi" in selection) report.putString("structureUi", structureUiSmoke(this))
                if ("admission" in selection) report.putString("admission", admissionSmoke(this))
                report.putString("stream", "Focused smoke passed: ${selection.joinToString()}\n" +
                    selection.joinToString("\n") { report.getString(it).orEmpty() } + "\n")
                finish(Activity.RESULT_OK, report)
                return
            }
            NativeEditor().use { editor ->
                val layer = editor.state().layers.last().id
                val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
                fun image(): ByteArray {
                    check(editor.draw(32, 32, 0f, 0f, 2048f, 2048f, pixels))
                    return ByteArray(pixels.capacity()).also { pixels.position(0); pixels.get(it) }
                }
                val original = image()
                editor.startStroke(layer, 300f, false)
                editor.samples(doubleArrayOf(1024.0, 1024.0, 1.0, 0.0, 1200.0, 1024.0, 1.0, 0.1))
                editor.endStroke(true)
                check(image().contentEquals(original)) { "Cancelled stroke changed pixels" }
                editor.startStroke(layer, 300f, false)
                editor.samples(doubleArrayOf(1024.0, 1024.0, 1.0, 0.0, 1200.0, 1024.0, 1.0, 0.1))
                editor.endStroke(false)
                val painted = image()
                check(!painted.contentEquals(original)) { "Committed stroke did not change pixels" }
                editor.undo(false)
                check(image().contentEquals(original)) { "Undo did not restore pixels" }
                editor.undo(true)
                check(image().contentEquals(painted)) { "Redo did not restore pixels" }
                report.putString("gpu", gpuSmoke(editor))
                editor.close(); editor.close()
                check(!editor.draw(32, 32, 0f, 0f, 2048f, 2048f, pixels)) { "Draw accessed a destroyed document" }
                check(editor.state().layers.isEmpty())
            }
            report.putString("recovery", recoverySmoke(targetContext))
            report.putString("metadataBulk", metadataBulkSmoke(this))
            report.putString("projectExport", projectExportSmoke(this))
            report.putString("imageIo", imageIOSmoke(targetContext))
            report.putString("precision", precisionMetadataSmoke(targetContext))
            report.putString("projectCandidate", projectCandidateSmoke(this))
            report.putString("workspaceReplacement", workspaceReplacementSmoke(this))
            report.putString("admissionPolicy", ProjectAdmissionPolicySmoke.run())
            report.putString("projectTransport", projectTransportSmoke(this))
            report.putString("projectSave", projectSaveSmoke(this))
            report.putString("admission", admissionSmoke(this))
            report.putString("projectWorkflowUi", projectWorkflowUiSmoke(this))
            report.putString("tileViews", NativeEditor(8, addDefaultLayers = false).use { gpuSmoke(it, independentGolden = { recreate -> tileViewGpuSmoke(this, recreate) }) })
            report.putString("ciOpacity", NativeEditor(8, addDefaultLayers = false).use { gpuSmoke(it, independentGolden = { ciOpacityGoldenSmoke(this) }) })
            report.putString("projectFlowLifecycle", projectFlowLifecycleSmoke(this))
            report.putString("projectExportUi", projectExportUiSmoke(this))
            report.putString("strokeGrid", NativeEditor(8, addDefaultLayers = false).use { gpuSmoke(it,
                independentGolden = { recreate -> strokeGridGpuSmoke(this, recreate) }) } + "\n" + strokeGridUiSmoke(this))
            report.putString("previewInput", previewInputSmoke(this))
            report.putString("recreation", activityRecoverySmoke(this))
            report.putString("gpuLifecycle", gpuLifecycleSmoke(this))
            report.putString("structureUi", structureUiSmoke(this))
            report.putString("stream", "JNI smoke passed: paint, cancel, undo, redo, render, close, use-after-close guards\n${report.getString("gpu")}\n${report.getString("recovery")}\n${report.getString("imageIo")}\n${report.getString("precision")}\n${report.getString("projectCandidate")}\n${report.getString("recreation")}\n${report.getString("gpuLifecycle")}\n${report.getString("structureUi")}\n")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            android.util.Log.e("CompositorSmoke", "Original instrumentation failure", error)
            report.putString("stream", "JNI smoke failed: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, report)
        }
    }
}
