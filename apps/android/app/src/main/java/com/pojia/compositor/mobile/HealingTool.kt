package com.pojia.compositor.mobile

/** Values match the native ABI; a random seed is captured once per admitted stroke. */
internal enum class HealingMode(val nativeValue: Int, val labelResource: Int) {
    CONTENT_AWARE(0, R.string.ui_healing_content_aware),
    CREATE_TEXTURE(1, R.string.ui_healing_create_texture),
    PROXIMITY_MATCH(2, R.string.ui_healing_proximity_match);
}

internal fun newHealingSeed(): Long = java.util.concurrent.ThreadLocalRandom.current().nextInt().toLong() and 0xffffffffL

internal fun checkHealingStatus(status: Int) {
    if (status == 0) return
    val key = when (status) {
        3 -> UiNoticeKey.MEMORY_LIMIT
        5 -> UiNoticeKey.HEALING_REGION_LIMIT
        else -> UiNoticeKey.HEALING_FAILED
    }
    throw UiNoticeException(UiNotice(key), "Healing native command failed ($status)")
}
