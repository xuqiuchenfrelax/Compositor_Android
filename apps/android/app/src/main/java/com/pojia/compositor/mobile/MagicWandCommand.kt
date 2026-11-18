package com.pojia.compositor.mobile

/** Cancellation and native publication have a single ordering point. Tracing holds no gate. */
internal class MagicWandCancellation {
    @Volatile var cancelled: Boolean = false
        private set
    private var clearRequested = false
    private var finished = false
    /** True means the worker has settled and the caller must enqueue a separate clear. */
    @Synchronized fun cancel(clearSelection: Boolean = false): Boolean {
        cancelled = true
        clearRequested = clearRequested || clearSelection
        return finished
    }
    @Synchronized fun finish(clear: () -> Unit) {
        try { if (clearRequested) clear() }
        finally { finished = true }
    }
    @Synchronized fun commitIfActive(commit: () -> Unit) {
        if (!cancelled) commit()
    }
}

internal fun decodeWandResult(packed: IntArray, width: Int, height: Int): NativeWandResult {
    check(packed.size >= 2 && packed[0].toLong() in 0..width.toLong() * height) {
        "Invalid native Magic Wand result"
    }
    val loops = packed[1]
    check(loops in 0..4096 && packed.size >= 2 + loops) { "Invalid native Magic Wand loop count" }
    if (loops == 0) {
        check(packed.size == 2 && packed[0] == 0) { "Invalid empty Magic Wand result" }
        return NativeWandResult(0, emptyList())
    }
    check(packed[0] > 0)
    var points = 0
    repeat(loops) {
        val count = packed[2 + it]
        check(count in 3..4096) { "Invalid native Magic Wand boundary" }
        points += count
        check(points <= 65536)
    }
    check(points + (if (loops > 1) 2 * loops - 1 else 0) <= 65536 &&
        packed.size == 2 + loops + points * 2) { "Invalid native Magic Wand point count" }
    var cursor = 2 + loops
    val result = List(loops) { loop ->
        val polygon = List(packed[2 + loop]) {
            val x = packed[cursor++]; val y = packed[cursor++]
            check(x in 0..width && y in 0..height) { "Magic Wand point outside canvas" }
            PixelSelectionPoint(x.toDouble(), y.toDouble())
        }
        NativeWandLoop(polygon, 0.0)
    }
    return NativeWandResult(packed[0].toLong(), result)
}
