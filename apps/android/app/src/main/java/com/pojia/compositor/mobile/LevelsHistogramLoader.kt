package com.pojia.compositor.mobile

import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LevelsSampleRequest(val draft: AdjustmentEditorDraft, val revision: Long,
    val x: Double, val y: Double, val sequence: Long = System.nanoTime())

/** One bounded native traversal per document, including across Activity recreation. */
internal object LevelsHistogramLoader {
    private val locks = WeakHashMap<NativeEditor, Mutex>()
    private fun lock(editor: NativeEditor): Mutex = synchronized(locks) { locks.getOrPut(editor) { Mutex() } }

    suspend fun capture(editor: NativeEditor, beforeLayerId: Long, revision: Long): DoubleArray =
        lock(editor).withLock {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                checkNotNull(editor.captureScene()) { "Levels source is unavailable" }.use { scene ->
                    check(scene.metadata()[0] == revision) { "Levels source changed before capture" }
                    // Native work is bounded. Cancellation drops its result and releases the scene;
                    // a newer request waits without capturing or retaining another scene.
                    scene.levelsHistogram(beforeLayerId)
                }
            }
        }

    suspend fun sample(editor: NativeEditor, beforeLayerId: Long, revision: Long,
        x: Double, y: Double): List<Double>? = lock(editor).withLock {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            checkNotNull(editor.captureScene()) { "Levels source is unavailable" }.use { scene ->
                check(scene.metadata()[0] == revision) { "Levels source changed before capture" }
                val result = scene.levelsSample(beforeLayerId, x, y)
                currentCoroutineContext().ensureActive()
                check(editor.currentRevision() == revision) { "Levels source changed during sampling" }
                result
            }
        }
    }
}
