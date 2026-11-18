package com.pojia.compositor.mobile

import java.io.File

/** JNI transaction, typed metadata, history and checkpoint coverage for the supported adjustment subset. */
internal fun adjustmentLayerNativeSmoke(context: android.content.Context, fixtureAssets: android.content.res.AssetManager): String {
    NativeEditor(32, addDefaultLayers = true).use { editor ->
        val initial = editor.state()
        val raster = initial.layers.firstOrNull { it.assetPresent } ?: error("Expected the default raster layer")
        check(editor.adjustmentStyleOrNull(raster.id) == null) { "Ordinary pixels were reported as an adjustment layer" }
        val invert = AdjustmentLayerModel.create(AdjustmentKind.INVERT)
        val id = editor.commitAdjustmentLayer(null, invert)
        check(id > 0)
        assertAdjustment(editor, id, invert)
        check(editor.state().layers.any { it.id == id })

        val exposure = AdjustmentLayerModel.create(AdjustmentKind.EXPOSURE).withExposure(1.75, .1, 1.2)
        check(editor.commitAdjustmentLayer(id, exposure) == id)
        assertAdjustment(editor, id, exposure)
        editor.undo(false)
        assertAdjustment(editor, id, invert)
        editor.undo(true)
        assertAdjustment(editor, id, exposure)

        val levelRanges = listOf(
            AdjustmentLevelRange(2.0, 1.05, 250.0, 0.0, 255.0),
            AdjustmentLevelRange(1.0, 1.1, 252.0, 3.0, 251.0),
            AdjustmentLevelRange(0.0, 1.0, 255.0, 0.0, 255.0),
            AdjustmentLevelRange(4.0, .95, 248.0, 2.0, 254.0))
        val levels = AdjustmentLayerModel.create(AdjustmentKind.LEVELS).withLevels(2, levelRanges)
        check(editor.commitAdjustmentLayer(id, levels) == id)
        assertAdjustment(editor, id, levels)

        val checkpoint = File.createTempFile("adjustment-layer", ".checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { recovered -> assertAdjustment(recovered, id, levels) }
        } finally { checkpoint.delete() }

        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId -> assertAdjustment(restored, restoredId, levels) }
        editor.undo(false)
        assertAdjustment(editor, id, exposure)
        editor.undo(false)
        assertAdjustment(editor, id, invert)
        editor.undo(false)
        check(editor.state().layers.map { it.id } == initial.layers.map { it.id })
    }
    extendedAdjustmentSmoke(context)
    hueSaturationTransactionSmoke(context)
    gradientMapTransactionSmoke(context)
    grainTransactionSmoke(context)
    addNoiseTransactionSmoke(context)
    desktopAdjustmentPixelSmoke(fixtureAssets, "adjustment-p98-desktop.json", 30)
    desktopAdjustmentPixelSmoke(fixtureAssets, "adjustment-p99-hsv-desktop.json", 24)
    desktopAdjustmentPixelSmoke(fixtureAssets, "adjustment-p100-gradient-desktop.json", 24)
    desktopAdjustmentPixelSmoke(fixtureAssets, "adjustment-p101-grain-desktop.json", 24)
    val grainTimings = grainPreviewTimingSmoke()
    return "nativeAdjustment=Invert+Exposure+Levels+Curves+BlackWhite+ColorBalance+HueSaturation+GradientMap+Grain+AddNoise JNI metadata exact history undo/redo checkpoint recovery typedProjectSaveReopenPixels verified; actualMac102cases26112pixelsZeroTolerance; $grainTimings"
}

private fun assertAdjustment(editor: NativeEditor, id: Long, expected: AdjustmentLayerModel) {
    val actual = checkNotNull(editor.adjustmentStyleOrNull(id)) { "Adjustment metadata was not available after commit" }
    check(equalAdjustmentJson(actual.toMacJson(), expected.toMacJson())) {
        "Adjustment settings did not round-trip: actual=${actual.toMacJson()} expected=${expected.toMacJson()}"
    }
}

/** JSON key order and 1 versus 1.0 do not change the Mac settings value. */
internal fun equalAdjustmentJson(first: Any?, second: Any?): Boolean = when {
    first is org.json.JSONObject && second is org.json.JSONObject -> {
        val keys = first.keys().asSequence().toSet()
        keys == second.keys().asSequence().toSet() && keys.all { equalAdjustmentJson(first.get(it), second.get(it)) }
    }
    first is org.json.JSONArray && second is org.json.JSONArray -> first.length() == second.length() &&
        (0 until first.length()).all { equalAdjustmentJson(first.get(it), second.get(it)) }
    first is Number && second is Number -> first.toDouble() == second.toDouble()
    else -> first == second
}

private fun extendedAdjustmentSmoke(context: android.content.Context) {
    val curves = AdjustmentLayerModel.create(AdjustmentKind.CURVES).withCurves(2, listOf(
        listOf(AdjustmentCurvePoint(0.0, 6.0), AdjustmentCurvePoint(37.5, 84.25), AdjustmentCurvePoint(160.0, 132.0), AdjustmentCurvePoint(255.0, 250.0)),
        listOf(AdjustmentCurvePoint(0.0, 0.0), AdjustmentCurvePoint(255.0, 255.0)),
        listOf(AdjustmentCurvePoint(0.0, 2.0), AdjustmentCurvePoint(127.0, 110.0), AdjustmentCurvePoint(255.0, 255.0)),
        listOf(AdjustmentCurvePoint(0.0, 0.0), AdjustmentCurvePoint(255.0, 255.0))))
    val bw = curves.withKind(AdjustmentKind.BLACK_WHITE).withBlackWhite(listOf(-80.0, 150.5, 60.0, 20.0, 245.0, 80.0, 220.0, 38.0), true)
    val balance = bw.withKind(AdjustmentKind.COLOR_BALANCE).withColorBalance(listOf(-21.0, 38.5, 12.0, 45.0, -30.0, 11.0, 7.0, 8.0, -42.0), false)
    check(balance.withKind(AdjustmentKind.CURVES).curves == curves.curves) { "Switching kind discarded inactive curve settings" }
    check(balance.withKind(AdjustmentKind.BLACK_WHITE).blackWhite == bw.blackWhite) { "Switching kind discarded inactive B&W settings" }
    val identity = listOf(AdjustmentCurvePoint(0.0, 0.0), AdjustmentCurvePoint(255.0, 255.0))
    for (x in 0..255) check(kotlin.math.abs(adjustmentCurveValue(identity, x.toDouble()) - x) < 1e-10)
    check(runCatching { curves.withCurves(values = listOf(identity)) }.isFailure)
    check(runCatching { bw.withBlackWhite(values = bw.blackWhite.toMutableList().also { it[0] = 301.0 }) }.isFailure)
    check(runCatching { balance.withColorBalance(values = balance.colorBalance.toMutableList().also { it[0] = Double.NaN }) }.isFailure)
    NativeEditor(32, addDefaultLayers = true).use { editor ->
        val id = editor.commitAdjustmentLayer(null, curves)
        assertAdjustment(editor, id, curves)
        editor.commitAdjustmentLayer(id, bw)
        assertAdjustment(editor, id, bw)
        editor.commitAdjustmentLayer(id, balance)
        assertAdjustment(editor, id, balance)
        editor.undo(false); assertAdjustment(editor, id, bw)
        editor.undo(false); assertAdjustment(editor, id, curves)
        editor.undo(true); assertAdjustment(editor, id, bw)
        editor.undo(true); assertAdjustment(editor, id, balance)
        val checkpoint = File.createTempFile("adjustment-extended", ".checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { assertAdjustment(it, id, balance) }
        } finally { checkpoint.delete() }
        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId -> assertAdjustment(restored, restoredId, balance) }
    }
}

/** Independent actual Mac outputs frozen before the Android build, compared through the JNI renderer. */
private fun desktopAdjustmentPixelSmoke(assets: android.content.res.AssetManager, asset: String, count: Int) {
    val fixtures = org.json.JSONArray(assets.open(asset).bufferedReader().use { it.readText() })
    check(fixtures.length() == count)
    repeat(fixtures.length()) { index ->
        val fixture = fixtures.getJSONObject(index)
        val input = fixture.getJSONArray("input")
        val expected = fixture.getJSONArray("expected")
        check(input.length() == 16 * 16 * 4 && expected.length() == input.length())
        val buffer = java.nio.ByteBuffer.allocateDirect(input.length())
        repeat(input.length()) { buffer.put(input.getInt(it).toByte()) }; buffer.rewind()
        val supplied = fixture.getJSONObject("settings")
        val kind = AdjustmentKind.fromWire(supplied.getString("kind"))
        val settings = AdjustmentLayerModel.create(kind).toMacJson()
        supplied.keys().forEach { key -> settings.put(key, supplied.get(key)) }
        NativeEditor(16, addDefaultLayers = false).use { editor ->
            editor.importPixels(16, 16, buffer)
            editor.commitAdjustmentLayer(null, AdjustmentLayerModel.fromMacJson(settings))
            val actual = java.nio.ByteBuffer.allocateDirect(input.length())
            check(editor.draw(16, 16, 0.0, 0.0, 16.0, 16.0, actual))
            actual.rewind()
            repeat(expected.length()) { byte ->
                val value = actual.get().toInt() and 255
                check(value == expected.getInt(byte)) {
                    "Actual Mac adjustment mismatch: fixture=${fixture.getString("id")} byte=$byte native=$value desktop=${expected.getInt(byte)}"
                }
            }
        }
    }
}

private fun hueSaturationTransactionSmoke(context: android.content.Context) {
    check(roundedHueSlider(-1.5) == -2.0 && roundedHueSlider(-0.5) == -1.0 &&
        roundedHueSlider(0.5) == 1.0 && roundedHueSlider(1.5) == 2.0)
    val master = HueSaturationModel().withCurrent(HueRangeAdjustment(15.0, 20.0, -8.0))
    val ranged = master.copy(range = HueRange.REDS).withCurrent(HueRangeAdjustment(-25.0, -18.0, 4.0))
        .let { it.withBand(it.band.withHandle(0, 320.0)) }.copy(invertRange = true)
    check(ranged.band.falloffStart == 320.0)
    check(ranged.band.withHandle(1, 100.0) == ranged.band) { "Invalid band movement changed handle order" }
    val decoded = HueSaturationModel.fromMacJson(ranged.toMacJson())
    check(decoded == ranged) { "Swift alternating hue dictionaries did not roundtrip" }
    check(decoded.copy(range = HueRange.BLUES).current == HueRangeAdjustment())
    check(decoded.copy(range = HueRange.BLUES, bands = emptyMap()).band == HueRange.BLUES.defaultBand)
    val legacy = AdjustmentLayerModel.create(AdjustmentKind.HUE_SATURATION).toMacJson()
        .put("hue", 45.0).put("saturation", 30.0).put("lightness", -12.0).put("colorize", true)
    check(AdjustmentLayerModel.fromMacJson(legacy).hsv.let { it.colorize && it.current == HueRangeAdjustment(45.0, 30.0, -12.0) })
    val style = AdjustmentLayerModel.create(AdjustmentKind.HUE_SATURATION).withHueSaturation(ranged)
    val tinted = style.withHueSaturation(HueSaturationModel.colorizeStart())
    check(style.withKind(AdjustmentKind.INVERT).withKind(AdjustmentKind.HUE_SATURATION).hsv == ranged)
    NativeEditor(32, addDefaultLayers = true).use { editor ->
        val baseline = editor.retainedMemory().totalBytes
        val id = editor.commitAdjustmentLayer(null, style)
        assertAdjustment(editor, id, style)
        check(editor.retainedMemory().totalBytes >= baseline + 33L * 33 * 33 * 4 * 4) { "Native HSV cube omitted from retained memory" }
        editor.commitAdjustmentLayer(id, tinted); assertAdjustment(editor, id, tinted)
        editor.undo(false); assertAdjustment(editor, id, style)
        editor.undo(true); assertAdjustment(editor, id, tinted)
        val checkpoint = File.createTempFile("adjustment-hsv", ".checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { assertAdjustment(it, id, tinted) }
        } finally { checkpoint.delete() }
        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId -> assertAdjustment(restored, restoredId, tinted) }
    }
}

private fun gradientMapTransactionSmoke(context: android.content.Context) {
    val defaults = AdjustmentLayerModel.create(AdjustmentKind.GRADIENT_MAP)
    check(defaults.gradientMap == GradientMapModel())
    check(!defaults.toMacJson().has("gradientMapSettings")) { "Missing optional settings must remain absent" }
    val nullSettings = defaults.toMacJson().put("gradientMapSettings", org.json.JSONObject.NULL)
    check(AdjustmentLayerModel.fromMacJson(nullSettings).gradientMap == GradientMapModel())
    check(AdjustmentLayerModel.fromMacJson(nullSettings).toMacJson().isNull("gradientMapSettings"))
    val endpoint = GradientMapModel(AdjustmentColorModel(.012345678901, .5, .875), AdjustmentColorModel(.95, .125, .75), true)
    val style = defaults.withGradientMap(endpoint)
    check(style.withKind(AdjustmentKind.INVERT).withKind(AdjustmentKind.GRADIENT_MAP).gradientMap == endpoint)
    check(GradientMapModel.fromMacJson(endpoint.toMacJson()) == endpoint)
    check(runCatching { AdjustmentColorModel(-.001, 0.0, 0.0) }.isFailure)
    check(runCatching { AdjustmentColorModel(0.0, 1.001, 0.0) }.isFailure)
    check(runCatching { AdjustmentColorModel(0.0, 0.0, Double.NaN) }.isFailure)
    check(runCatching { AdjustmentLayerModel.fromMacJson(style.toMacJson().put("gradientMapSettings", "bad")) }.isFailure)
    check(runCatching { GradientMapModel.fromMacJson(endpoint.toMacJson().put("reversed", "true")) }.isFailure)
    check(runCatching { AdjustmentColorModel.fromMacJson(endpoint.shadows.toMacJson().put("red", "0.5")) }.isFailure)
    check(runCatching { AdjustmentColorModel.fromMacJson(endpoint.shadows.toMacJson().put("future", 0)) }.isFailure)
    val edited = style.withGradientMap(endpoint.copy(reversed = false, highlights = AdjustmentColorModel(.0625, .875, .25)))
    NativeEditor(32, addDefaultLayers = true).use { editor ->
        val id = editor.commitAdjustmentLayer(null, style)
        assertAdjustment(editor, id, style)
        editor.commitAdjustmentLayer(id, edited); assertAdjustment(editor, id, edited)
        editor.undo(false); assertAdjustment(editor, id, style)
        editor.undo(true); assertAdjustment(editor, id, edited)
        val checkpoint = File.createTempFile("adjustment-gradient", ".checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { assertAdjustment(it, id, edited) }
        } finally { checkpoint.delete() }
        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId -> assertAdjustment(restored, restoredId, edited) }
    }
}

private fun grainTransactionSmoke(context: android.content.Context) {
    val defaults = AdjustmentLayerModel.create(AdjustmentKind.GRAIN)
    check(defaults.grain == GrainModel() && !defaults.toMacJson().has("grainSettings"))
    val explicitNull = defaults.toMacJson().put("grainSettings", org.json.JSONObject.NULL)
    check(AdjustmentLayerModel.fromMacJson(explicitNull).grain == GrainModel())
    check(AdjustmentLayerModel.fromMacJson(explicitNull).toMacJson().isNull("grainSettings"))
    val grain = GrainModel(37.125, .75, 84.0625, 4_294_967_295L)
    check(GrainModel.fromMacJson(grain.toMacJson()) == grain)
    check(GrainModel.fromMacJson(grain.toMacJson().put("seed", 4_294_967_295.0)).seed == grain.seed)
    for (badSeed in listOf(-1, 4_294_967_296L, 1.5, "1", true, org.json.JSONObject.NULL))
        check(runCatching { GrainModel.fromMacJson(grain.toMacJson().put("seed", badSeed)) }.isFailure)
    check(runCatching { GrainModel(amount = 100.001) }.isFailure)
    check(runCatching { GrainModel(size = .4999) }.isFailure)
    check(runCatching { GrainModel(roughness = Double.POSITIVE_INFINITY) }.isFailure)
    check(runCatching { GrainModel.fromMacJson(grain.toMacJson().put("future", 1)) }.isFailure)
    check(runCatching { GrainModel.fromMacJson(grain.toMacJson().put("amount", "25")) }.isFailure)
    check(roundedGrainSlider(.5, 0) == 1.0 && roundedGrainSlider(24.5, 0) == 25.0 && roundedGrainSlider(1.25, 1) == 1.3)
    val style = defaults.withGrain(grain)
    check(style.withKind(AdjustmentKind.INVERT).withKind(AdjustmentKind.GRAIN).grain == grain)
    val edited = style.withGrain(grain.copy(amount = 63.875, size = 12.345, roughness = 1.5))
    NativeEditor(32, addDefaultLayers = true).use { editor ->
        val id = editor.commitAdjustmentLayer(null, style)
        assertAdjustment(editor, id, style)
        editor.commitAdjustmentLayer(id, edited); assertAdjustment(editor, id, edited)
        editor.undo(false); assertAdjustment(editor, id, style)
        editor.undo(true); assertAdjustment(editor, id, edited)
        val checkpoint = File.createTempFile("adjustment-grain", ".checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { assertAdjustment(it, id, edited) }
        } finally { checkpoint.delete() }
        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId -> assertAdjustment(restored, restoredId, edited) }
    }
}

/** Small deterministic preview sample; a timing observation, not a sustained-frame-rate gate. */
private fun grainPreviewTimingSmoke(): String {
    val side = 256
    val buffer = java.nio.ByteBuffer.allocateDirect(side * side * 4)
    repeat(side * side) { i -> buffer.put((32 + i % 160).toByte()).put(96.toByte()).put(128.toByte()).put(255.toByte()) }
    buffer.rewind()
    NativeEditor(side, addDefaultLayers = false).use { editor ->
        editor.importPixels(side, side, buffer)
        editor.commitAdjustmentLayer(null, AdjustmentLayerModel.create(AdjustmentKind.GRAIN).withGrain(GrainModel(seed = 4_294_967_295L)))
        val out = java.nio.ByteBuffer.allocateDirect(side * side * 4)
        val elapsed = List(4) {
            out.clear()
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), out))
            (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }
        return "grainPreview256RGBA8Ms=" + elapsed.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) } + ";coldFirst+3WarmCPUreferenceOnly"
    }
}

private fun addNoiseTransactionSmoke(context: android.content.Context) {
    val defaults = AdjustmentLayerModel.create(AdjustmentKind.ADD_NOISE)
    check(defaults.noiseAmount == 10.0 && !defaults.noiseGaussian && !defaults.noiseMonochromatic && defaults.noiseSeed == 0L)
    val edited = defaults.withNoise(amount = 87.5, gaussian = true, monochromatic = true, seed = 4_294_967_295L)
    check(AdjustmentLayerModel.fromMacJson(edited.toMacJson()).toMacJson().let { equalAdjustmentJson(it, edited.toMacJson()) })
    check(runCatching { defaults.withNoise(amount = .09) }.isFailure)
    check(runCatching { defaults.withNoise(seed = 4_294_967_296L) }.isFailure)
    NativeEditor(32, addDefaultLayers = true).use { editor ->
        val id = editor.commitAdjustmentLayer(null, edited)
        assertAdjustment(editor, id, edited)
        editor.undo(false)
        check(editor.adjustmentStyleOrNull(id) == null)
        editor.undo(true)
        assertAdjustment(editor, id, edited)
        val checkpoint = File.createTempFile("adjustment-noise", ".checkpoint")
        try {
            editor.save(checkpoint)
            NativeEditor.load(checkpoint).use { restored -> assertAdjustment(restored, id, edited) }
        } finally { checkpoint.delete() }
        typedProjectRoundTripSmoke(context, editor, id) { restored, restoredId -> assertAdjustment(restored, restoredId, edited) }
    }
}
