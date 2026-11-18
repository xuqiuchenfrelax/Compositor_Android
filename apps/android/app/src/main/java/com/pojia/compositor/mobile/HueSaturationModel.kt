package com.pojia.compositor.mobile

import org.json.JSONArray
import org.json.JSONObject

internal enum class HueRange(val wireName: String) {
    MASTER("Master"), REDS("Reds"), YELLOWS("Yellows"), GREENS("Greens"),
    CYANS("Cyans"), BLUES("Blues"), MAGENTAS("Magentas");

    val defaultBand: HueBandModel get() = when (this) {
        MASTER -> HueBandModel(0.0, 0.0, 360.0, 360.0)
        else -> {
            val center = (ordinal - 1) * 60.0
            HueBandModel(wrapHue(center - 45), wrapHue(center - 15), wrapHue(center + 15), wrapHue(center + 45))
        }
    }
    companion object {
        fun fromWire(value: String): HueRange = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unsupported hue range: $value")
    }
}

internal fun wrapHue(value: Double): Double = ((value % 360) + 360) % 360
internal fun forwardHue(from: Double, to: Double): Double {
    val delta = (to - from) % 360
    return if (delta < 0) delta + 360 else delta
}

internal data class HueRangeAdjustment(val hue: Double = 0.0, val saturation: Double = 0.0, val lightness: Double = 0.0) {
    fun validate() {
        require(hue.isFinite() && hue in -360.0..360.0)
        require(saturation.isFinite() && saturation in -100.0..100.0)
        require(lightness.isFinite() && lightness in -100.0..100.0)
    }
}

internal data class HueBandModel(val falloffStart: Double, val rangeStart: Double, val rangeEnd: Double, val falloffEnd: Double) {
    val handles: List<Double> get() = listOf(falloffStart, rangeStart, rangeEnd, falloffEnd)
    fun weight(hue: Double): Double {
        val span = forwardHue(falloffStart, falloffEnd)
        if (!(span > 0)) return 1.0
        val position = forwardHue(falloffStart, hue)
        if (!(position <= span)) return 0.0
        val rampIn = forwardHue(falloffStart, rangeStart)
        val plateauEnd = forwardHue(falloffStart, rangeEnd)
        if (position < rampIn) return if (rampIn > 0) position / rampIn else 1.0
        if (position <= plateauEnd) return 1.0
        val rampOut = span - plateauEnd
        return if (rampOut > 0) (span - position) / rampOut else 1.0
    }
    fun withHandle(index: Int, degrees: Double): HueBandModel {
        require(index in 0..3 && degrees.isFinite())
        val updated = handles.toMutableList().also { it[index] = wrapHue(degrees) }
        val span = forwardHue(updated[0], updated[3])
        val start = forwardHue(updated[0], updated[1]); val end = forwardHue(updated[0], updated[2])
        return if (span > 1 && span <= 350 && start <= end && end <= span)
            HueBandModel(updated[0], updated[1], updated[2], updated[3]) else this
    }
}

/** Swift encodes dictionaries keyed by ColorRange as alternating key/value arrays. */
internal data class HueSaturationModel(val range: HueRange = HueRange.MASTER, val colorize: Boolean = false,
    val invertRange: Boolean = false,
    val adjustments: Map<HueRange, HueRangeAdjustment> = mapOf(HueRange.MASTER to HueRangeAdjustment()),
    val bands: Map<HueRange, HueBandModel> = HueRange.entries.associateWith { it.defaultBand }) {
    val current: HueRangeAdjustment get() = adjustments[range] ?: HueRangeAdjustment()
    val band: HueBandModel get() = bands[range] ?: range.defaultBand
    fun withCurrent(value: HueRangeAdjustment): HueSaturationModel {
        value.validate()
        return copy(adjustments = adjustments + (range to value))
    }
    fun withBand(value: HueBandModel): HueSaturationModel {
        require(value.handles.all { it.isFinite() })
        return copy(bands = bands + (range to value))
    }
    fun shiftedHue(hue: Double): Double {
        val shifted = (hue + adjustments.entries.sumOf { (key, adjustment) ->
        val weight = if (key == HueRange.MASTER) 1.0 else (bands[key] ?: key.defaultBand).weight(hue).let {
            if (invertRange && key == range) 1 - it else it
        }
            adjustment.hue * weight
        }) % 360
        return if (shifted < 0) shifted + 360 else shifted
    }
    fun toMacJson(): JSONObject {
        val values = JSONArray(); val ranges = JSONArray()
        HueRange.entries.forEach { key ->
            adjustments[key]?.let { value ->
                value.validate()
                values.put(key.wireName).put(JSONObject().put("hue", value.hue).put("saturation", value.saturation).put("lightness", value.lightness))
            }
            bands[key]?.let { value ->
                require(value.handles.all { it.isFinite() })
                ranges.put(key.wireName).put(JSONObject().put("falloffStart", value.falloffStart).put("rangeStart", value.rangeStart)
                    .put("rangeEnd", value.rangeEnd).put("falloffEnd", value.falloffEnd))
            }
        }
        return JSONObject().put("range", range.wireName).put("colorize", colorize).put("invertRange", invertRange)
            .put("adjustments", values).put("bands", ranges)
    }
    companion object {
        fun colorizeStart() = HueSaturationModel(colorize = true, adjustments = mapOf(HueRange.MASTER to HueRangeAdjustment(saturation = 25.0)))
        fun fromMacJson(json: JSONObject): HueSaturationModel {
            fun <T> dictionary(array: JSONArray, decode: (JSONObject) -> T): Map<HueRange, T> {
                require(array.length() % 2 == 0 && array.length() <= HueRange.entries.size * 2)
                val result = linkedMapOf<HueRange, T>()
                for (index in 0 until array.length() step 2) {
                    val key = HueRange.fromWire(array.getString(index))
                    require(!result.containsKey(key))
                    result[key] = decode(array.getJSONObject(index + 1))
                }
                return result
            }
            val adjustments = dictionary(json.getJSONArray("adjustments")) {
                HueRangeAdjustment(it.getDouble("hue"), it.getDouble("saturation"), it.getDouble("lightness")).also(HueRangeAdjustment::validate)
            }
            val bands = dictionary(json.getJSONArray("bands")) {
                HueBandModel(it.getDouble("falloffStart"), it.getDouble("rangeStart"), it.getDouble("rangeEnd"), it.getDouble("falloffEnd"))
                    .also { band -> require(band.handles.all { it.isFinite() }) }
            }
            return HueSaturationModel(HueRange.fromWire(json.getString("range")), json.getBoolean("colorize"),
                json.getBoolean("invertRange"), adjustments, bands)
        }
    }
}
