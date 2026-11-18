package com.pojia.compositor.mobile

import org.json.JSONObject

/** Straight sRGB endpoints, using the Mac Codable field names and Double precision. */
internal data class AdjustmentColorModel(val red: Double, val green: Double, val blue: Double) {
    init { require(listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }) }
    fun toMacJson(): JSONObject = JSONObject().put("red", red).put("green", green).put("blue", blue)

    companion object {
        fun fromMacJson(json: JSONObject): AdjustmentColorModel {
            require(json.keys().asSequence().toSet() == setOf("red", "green", "blue"))
            require(listOf("red", "green", "blue").all { json.get(it) is Number })
            return AdjustmentColorModel(json.getDouble("red"), json.getDouble("green"), json.getDouble("blue"))
        }
    }
}

internal data class GradientMapModel(
    val shadows: AdjustmentColorModel = AdjustmentColorModel(0.0, 0.0, 0.0),
    val highlights: AdjustmentColorModel = AdjustmentColorModel(1.0, 1.0, 1.0),
    val reversed: Boolean = false,
) {
    fun toMacJson(): JSONObject = JSONObject().put("shadows", shadows.toMacJson())
        .put("highlights", highlights.toMacJson()).put("reversed", reversed)

    companion object {
        fun fromMacJson(json: JSONObject): GradientMapModel {
            require(json.keys().asSequence().toSet() == setOf("shadows", "highlights", "reversed"))
            require(json.get("reversed") is Boolean)
            return GradientMapModel(AdjustmentColorModel.fromMacJson(json.getJSONObject("shadows")),
                AdjustmentColorModel.fromMacJson(json.getJSONObject("highlights")), json.getBoolean("reversed"))
        }
    }
}
