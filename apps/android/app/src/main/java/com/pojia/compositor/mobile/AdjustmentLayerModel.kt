package com.pojia.compositor.mobile

import org.json.JSONArray
import org.json.JSONObject

internal enum class AdjustmentKind(val wireName: String) {
    INVERT("Invert"), EXPOSURE("Exposure"), LEVELS("Levels"), CURVES("Curves"),
    BLACK_WHITE("Black & White"), COLOR_BALANCE("Color Balance"), HUE_SATURATION("Hue/Saturation"), GRADIENT_MAP("Gradient Map"), GRAIN("Grain"),
    ADD_NOISE("Add Noise"), VIGNETTE("Vignette");

    val isEditable: Boolean get() = this != INVERT

    companion object {
        // Match PC AdjustmentKind.imageAdjustments and filters, restricted to implemented kinds.
        val imageAdjustments = listOf(CURVES, LEVELS, HUE_SATURATION, BLACK_WHITE, COLOR_BALANCE,
            EXPOSURE, GRADIENT_MAP, GRAIN, INVERT)
        val filters = listOf(ADD_NOISE, VIGNETTE)
        fun fromWire(value: String) = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unsupported adjustment kind: $value")
    }
}

internal data class AdjustmentLevelRange(val black: Double = 0.0, val gamma: Double = 1.0,
    val white: Double = 255.0, val outputBlack: Double = 0.0, val outputWhite: Double = 255.0) {
    fun validate() {
        require(black.isFinite() && black in 0.0..254.0)
        require(white.isFinite() && white in black + 1.0..255.0)
        require(gamma.isFinite() && gamma in .1..9.99)
        require(outputBlack.isFinite() && outputBlack in 0.0..255.0)
        require(outputWhite.isFinite() && outputWhite in 0.0..255.0)
    }
}

internal data class AdjustmentCurvePoint(val x: Double, val y: Double)

internal object AdjustmentFields {
    val blackWhite = listOf("reds", "yellows", "greens", "cyans", "blues", "magentas", "tintHue", "tintSaturation")
    val blackWhiteDefaults = listOf(40.0, 60.0, 40.0, 60.0, 20.0, 80.0, 40.0, 20.0)
    val colorBalance = listOf("shadowCyanRed", "shadowMagentaGreen", "shadowYellowBlue",
        "midCyanRed", "midMagentaGreen", "midYellowBlue", "highlightCyanRed", "highlightMagentaGreen", "highlightYellowBlue")
}

/** Retains the exact Mac JSON object and changes only the active kind's settings. */
internal data class AdjustmentLayerModel private constructor(
    val kind: AdjustmentKind,
    val exposure: Double,
    val offset: Double,
    val exposureGamma: Double,
    val channel: Int,
    val ranges: List<AdjustmentLevelRange>,
    val curveChannel: Int,
    val curves: List<List<AdjustmentCurvePoint>>,
    val blackWhite: List<Double>,
    val tint: Boolean,
    val colorBalance: List<Double>,
    val preserveLuminosity: Boolean,
    val hsv: HueSaturationModel,
    val gradientMap: GradientMapModel,
    val grain: GrainModel,
    val noiseAmount: Double,
    val noiseGaussian: Boolean,
    val noiseMonochromatic: Boolean,
    val noiseSeed: Long,
    val vignette: VignetteModel,
    private val wire: JSONObject,
) {
    fun toMacJson(): JSONObject = JSONObject(wire.toString())

    fun withVignette(value: VignetteModel): AdjustmentLayerModel {
        val next = toMacJson()
        val filters = next.optJSONObject("filterLayerSettings") ?: VignetteModel.defaultFilterSettings()
        filters.put("vignette", value.toMacJson())
        return fromMacJson(next.put("filterLayerSettings", filters))
    }

    fun withKind(value: AdjustmentKind): AdjustmentLayerModel = fromMacJson(toMacJson().put("kind", value.wireName))

    fun withGrain(value: GrainModel): AdjustmentLayerModel =
        fromMacJson(toMacJson().put("grainSettings", value.toMacJson()))

    fun withNoise(amount: Double = noiseAmount, gaussian: Boolean = noiseGaussian,
        monochromatic: Boolean = noiseMonochromatic, seed: Long = noiseSeed): AdjustmentLayerModel {
        require(amount.isFinite() && amount in .1..400.0)
        require(seed in 0L..4_294_967_295L)
        return fromMacJson(toMacJson()
            .put("noiseAmount", amount)
            .put("noiseGaussian", gaussian)
            .put("noiseMonochromatic", monochromatic)
            .put("noiseSeed", seed))
    }

    fun withGradientMap(value: GradientMapModel): AdjustmentLayerModel =
        fromMacJson(toMacJson().put("gradientMapSettings", value.toMacJson()))

    fun withHueSaturation(value: HueSaturationModel): AdjustmentLayerModel =
        fromMacJson(toMacJson().put("hsvSettings", value.toMacJson()))

    fun withCurves(channelValue: Int = curveChannel, values: List<List<AdjustmentCurvePoint>> = curves): AdjustmentLayerModel {
        val arrays = JSONArray()
        values.forEach { points -> arrays.put(JSONArray().also { array ->
            points.forEach { array.put(JSONObject().put("x", it.x).put("y", it.y)) }
        }) }
        require(channelValue in 0..3)
        return fromMacJson(toMacJson().put("curves", JSONObject().put("channel", channels[channelValue]).put("channels", arrays)))
    }

    fun withBlackWhite(values: List<Double> = blackWhite, tintValue: Boolean = tint): AdjustmentLayerModel {
        require(values.size == AdjustmentFields.blackWhite.size)
        val settings = JSONObject().put("tint", tintValue)
        AdjustmentFields.blackWhite.forEachIndexed { index, key -> settings.put(key, values[index]) }
        return fromMacJson(toMacJson().put("blackWhiteSettings", settings))
    }

    fun withColorBalance(values: List<Double> = colorBalance, preserve: Boolean = preserveLuminosity): AdjustmentLayerModel {
        require(values.size == AdjustmentFields.colorBalance.size)
        val settings = JSONObject().put("preserveLuminosity", preserve)
        AdjustmentFields.colorBalance.forEachIndexed { index, key -> settings.put(key, values[index]) }
        return fromMacJson(toMacJson().put("colorBalanceSettings", settings))
    }

    fun withExposure(value: Double = exposure, offsetValue: Double = offset,
        gammaValue: Double = exposureGamma): AdjustmentLayerModel {
        require(value.isFinite() && value in -20.0..20.0)
        require(offsetValue.isFinite() && offsetValue in -0.5..0.5)
        require(gammaValue.isFinite() && gammaValue in .01..9.99)
        val next = toMacJson()
        next.put("exposureSettings", JSONObject().put("exposure", value).put("offset", offsetValue).put("gamma", gammaValue))
        return fromMacJson(next)
    }

    fun withLevels(channelValue: Int = channel, rangeValues: List<AdjustmentLevelRange> = ranges): AdjustmentLayerModel {
        require(channelValue in 0..3 && rangeValues.size == 4)
        rangeValues.forEach(AdjustmentLevelRange::validate)
        val next = toMacJson()
        val channelNames = arrayOf("RGB", "Red", "Green", "Blue")
        val rangeArray = JSONArray()
        rangeValues.forEach { range ->
            rangeArray.put(JSONObject().put("black", range.black).put("gamma", range.gamma).put("white", range.white)
                .put("outputBlack", range.outputBlack).put("outputWhite", range.outputWhite))
        }
        next.put("levels", JSONObject().put("channel", channelNames[channelValue]).put("ranges", rangeArray))
        return fromMacJson(next)
    }

    companion object {
        private val channels = arrayOf("RGB", "Red", "Green", "Blue")

        fun create(kind: AdjustmentKind): AdjustmentLayerModel {
            val identityRanges = JSONArray()
            repeat(4) { identityRanges.put(JSONObject().put("black", 0).put("gamma", 1).put("white", 255)
                .put("outputBlack", 0).put("outputWhite", 255)) }
            val identityCurves = JSONArray()
            repeat(4) { identityCurves.put(JSONArray().put(JSONObject().put("x", 0).put("y", 0))
                .put(JSONObject().put("x", 255).put("y", 255))) }
            val json = JSONObject().put("kind", kind.wireName).put("hue", 0).put("saturation", 0)
                .put("lightness", 0).put("colorize", false)
                .put("curves", JSONObject().put("channel", "RGB").put("channels", identityCurves))
            if (kind == AdjustmentKind.EXPOSURE)
                json.put("exposureSettings", JSONObject().put("exposure", 0.0).put("offset", 0.0).put("gamma", 1.0))
            if (kind == AdjustmentKind.ADD_NOISE)
                json.put("noiseAmount", 10.0).put("noiseGaussian", false)
                    .put("noiseMonochromatic", false).put("noiseSeed", 0L)
            json.put("levels", JSONObject().put("channel", "RGB").put("ranges", identityRanges))
            return fromMacJson(json)
        }

        fun fromMacJson(json: JSONObject): AdjustmentLayerModel {
            val kind = AdjustmentKind.fromWire(json.getString("kind"))
            var exposure = 0.0; var offset = 0.0; var exposureGamma = 1.0
            json.optJSONObject("exposureSettings")?.let {
                exposure = it.getDouble("exposure"); offset = it.getDouble("offset"); exposureGamma = it.getDouble("gamma")
            }
            var channel = 0
            var ranges = List(4) { AdjustmentLevelRange() }
            json.optJSONObject("levels")?.let { levels ->
                channel = channels.indexOf(levels.getString("channel"))
                require(channel >= 0)
                val values = levels.getJSONArray("ranges")
                require(values.length() == 4)
                ranges = List(4) { index -> values.getJSONObject(index).let { value ->
                    AdjustmentLevelRange(value.getDouble("black"), value.getDouble("gamma"), value.getDouble("white"),
                        value.getDouble("outputBlack"), value.getDouble("outputWhite")).also(AdjustmentLevelRange::validate)
                } }
            }
            require(exposure.isFinite() && exposure in -20.0..20.0 && offset.isFinite() && offset in -0.5..0.5 &&
                exposureGamma.isFinite() && exposureGamma in .01..9.99)
            val curve = json.getJSONObject("curves")
            val curveChannel = channels.indexOf(curve.getString("channel"))
            require(curveChannel >= 0)
            val curveArrays = curve.getJSONArray("channels")
            require(curveArrays.length() == 4)
            val curves = List(4) { index ->
                val points = curveArrays.getJSONArray(index)
                require(points.length() in 2..32)
                List(points.length()) { i -> points.getJSONObject(i).let { AdjustmentCurvePoint(it.getDouble("x"), it.getDouble("y")) } }.also { list ->
                    require(list.first().x == 0.0 && list.last().x == 255.0)
                    require(list.all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..255.0 && it.y in 0.0..255.0 })
                    require(list.zipWithNext().all { (a, b) -> a.x < b.x })
                }
            }
            val bw = json.optJSONObject("blackWhiteSettings")
            val blackWhite = AdjustmentFields.blackWhite.mapIndexed { i, key -> bw?.getDouble(key) ?: AdjustmentFields.blackWhiteDefaults[i] }
            blackWhite.forEachIndexed { i, v -> require(v.isFinite() && v in when (i) { 6 -> 0.0..360.0; 7 -> 0.0..100.0; else -> -200.0..300.0 }) }
            val cb = json.optJSONObject("colorBalanceSettings")
            val colorBalance = AdjustmentFields.colorBalance.map { cb?.getDouble(it) ?: 0.0 }
            require(colorBalance.all { it.isFinite() && it in -100.0..100.0 })
            val hsv = json.optJSONObject("hsvSettings")?.let(HueSaturationModel::fromMacJson)
                ?: HueSaturationModel(colorize = json.optBoolean("colorize", false), adjustments = mapOf(HueRange.MASTER to
                    HueRangeAdjustment(json.optDouble("hue", 0.0), json.optDouble("saturation", 0.0), json.optDouble("lightness", 0.0)).also(HueRangeAdjustment::validate)))
            val gradientMap = json.optJSONObject("gradientMapSettings")?.let(GradientMapModel::fromMacJson) ?: GradientMapModel()
            require(!json.has("gradientMapSettings") || json.isNull("gradientMapSettings") || json.optJSONObject("gradientMapSettings") != null)
            val grain = json.optJSONObject("grainSettings")?.let(GrainModel::fromMacJson) ?: GrainModel()
            require(!json.has("grainSettings") || json.isNull("grainSettings") || json.optJSONObject("grainSettings") != null)
            val noiseAmount = json.optDouble("noiseAmount", 10.0)
            val noiseGaussian = json.optBoolean("noiseGaussian", false)
            val noiseMonochromatic = json.optBoolean("noiseMonochromatic", false)
            val noiseSeedValue = json.opt("noiseSeed")
            val noiseSeed = if (noiseSeedValue == null || noiseSeedValue == JSONObject.NULL) 0L else {
                require(noiseSeedValue is Number)
                val value = noiseSeedValue.toDouble()
                require(value.isFinite() && value in 0.0..4_294_967_295.0 && kotlin.math.floor(value) == value)
                value.toLong()
            }
            require(noiseAmount.isFinite() && noiseAmount in .1..400.0)
            return AdjustmentLayerModel(kind, exposure, offset, exposureGamma, channel, ranges, curveChannel, curves,
                blackWhite, bw?.getBoolean("tint") ?: false, colorBalance, cb?.getBoolean("preserveLuminosity") ?: true,
                hsv, gradientMap, grain, noiseAmount, noiseGaussian, noiseMonochromatic, noiseSeed,
                json.optJSONObject("filterLayerSettings")?.optJSONObject("vignette")?.let(VignetteModel::fromMacJson) ?: VignetteModel(), JSONObject(json.toString()))
        }
    }
}
