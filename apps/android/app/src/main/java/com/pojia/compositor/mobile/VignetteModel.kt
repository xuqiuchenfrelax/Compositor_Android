package com.pojia.compositor.mobile

import org.json.JSONObject

/** PC v18 VignetteLayerSettings; inactive filter fields are preserved by AdjustmentLayerModel. */
internal data class VignetteModel(val amount: Double = 35.0,
    val color: AdjustmentColorModel = AdjustmentColorModel(0.0, 0.0, 0.0),
    val midpoint: Double = 50.0, val roundness: Double = 100.0,
    val feather: Double = 60.0, val highlights: Double = 25.0) {
    init {
        require(listOf(amount, midpoint, feather, highlights).all { it.isFinite() && it in 0.0..100.0 })
        require(roundness.isFinite() && roundness in -100.0..100.0)
    }
    fun toMacJson(): JSONObject = JSONObject().put("amount", amount).put("color", color.toMacJson())
        .put("midpoint", midpoint).put("roundness", roundness).put("feather", feather).put("highlights", highlights)

    companion object {
        fun fromMacJson(json: JSONObject): VignetteModel {
            require(json.keys().asSequence().toSet() == setOf("amount", "color", "midpoint", "roundness", "feather", "highlights"))
            require(listOf("amount", "midpoint", "roundness", "feather", "highlights").all { json.get(it) is Number })
            return VignetteModel(json.getDouble("amount"), AdjustmentColorModel.fromMacJson(json.getJSONObject("color")),
                json.getDouble("midpoint"), json.getDouble("roundness"), json.getDouble("feather"), json.getDouble("highlights"))
        }
        // Synthesized Swift Codable requires every nonoptional stored field, even those with defaults.
        fun defaultFilterSettings(): JSONObject = JSONObject().put("progressiveFrom", 0).put("progressiveTo", 20)
            .put("progressiveRadial", false).put("progressiveInverted", false).put("bloomAmount", 40).put("bloomRadius", 24)
            .put("rgbSplitDistance", 10).put("rgbSplitBlur", 0).put("rgbSplitAngle", 0).put("rgbSplitRadial", false).put("rgbSplitReach", 1000)
    }
}
