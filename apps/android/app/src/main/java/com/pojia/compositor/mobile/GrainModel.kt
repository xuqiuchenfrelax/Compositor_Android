package com.pojia.compositor.mobile

import org.json.JSONObject

/** The seed is an unsigned Mac UInt32, represented exactly by a Kotlin Long. */
internal data class GrainModel(val amount: Double = 25.0, val size: Double = 1.5,
    val roughness: Double = 50.0, val seed: Long = 0L) {
    init {
        require(amount.isFinite() && amount in 0.0..100.0)
        require(size.isFinite() && size in .5..20.0)
        require(roughness.isFinite() && roughness in 0.0..100.0)
        require(seed in 0L..4_294_967_295L)
    }
    fun toMacJson(): JSONObject = JSONObject().put("amount", amount).put("size", size)
        .put("roughness", roughness).put("seed", seed)

    companion object {
        fun fromMacJson(json: JSONObject): GrainModel {
            require(json.keys().asSequence().toSet() == setOf("amount", "size", "roughness", "seed"))
            require(listOf("amount", "size", "roughness").all { json.get(it) is Number })
            val seed = json.get("seed")
            require(seed is Number)
            val numericSeed = seed.toDouble()
            require(numericSeed.isFinite() && numericSeed in 0.0..4_294_967_295.0 && kotlin.math.floor(numericSeed) == numericSeed)
            return GrainModel(json.getDouble("amount"), json.getDouble("size"), json.getDouble("roughness"), numericSeed.toLong())
        }
    }
}
