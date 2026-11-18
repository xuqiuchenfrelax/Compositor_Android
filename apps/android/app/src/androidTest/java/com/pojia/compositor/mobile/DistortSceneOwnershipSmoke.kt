package com.pojia.compositor.mobile

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer

/** Exercises the real Distort registry -> raw-scene JNI ownership boundary without an Activity. */
internal fun distortSceneOwnershipSmoke(instrumentation: Instrumentation): String {
    val evidence = File(instrumentation.targetContext.filesDir, "p103-distort-scene-${System.nanoTime()}.txt")
    val editor = NativeEditor(32, addDefaultLayers = false)
    var session: NativeDistortSession? = null
    val scenes = mutableListOf<NativeScene>()
    var lease: DistortPreviewLease? = null
    var borrowed = false
    try {
        val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
        repeat(32 * 32) { pixels.put(220.toByte()).put(30.toByte()).put(40.toByte()).put(255.toByte()) }
        pixels.rewind()
        editor.importPixels(32, 32, pixels)
        val baseline = editor.resourceLedger.snapshot()
        val worker = editor.beginDistortSession(editor.state().document.activeLayerId)
        session = worker
        check(worker.submitQuad(doubleArrayOf(0.0, 0.0, 32.0, 0.0, 32.0, 32.0, 0.0, 32.0)) == 0)
        check(worker.wait(10_000) == NativeDistortSessionBridge.WAIT_READY)
        check(worker.publish() == 0)
        fun snapshotToken(): Long {
            val record = NativeDistortSessionBridge.record(NativeDistortSessionBridge.SNAPSHOT_BYTES)
            check(editor.distortSnapshot(worker.token, record) == 0)
            return NativeDistortSessionBridge.snapshot(record)[0].also { check(it > 0L) }
        }
        fun nativeReserved(): Long {
            val record = NativeDistortSessionBridge.record(NativeDistortSessionBridge.STATS_BYTES)
            check(NativeDistortSessionBridge.stats(worker.token, record) == 0)
            return NativeDistortSessionBridge.stats(record)[1]
        }
        fun inspect(scene: NativeScene): ByteArray {
            val info = scene.metadata()
            check(info[1] == 32L && info[2] == 32L && info[3] == 1L)
            check(scene.generation() > 0L)
            scene.layerMetadata(0)
            scene.layerStructure(0)
            scene.rasterViewMetadata(0)
            val tiles = scene.tileViewsMetadata(0)
            check(tiles.isNotEmpty())
            scene.tileMetadata(0, 0)
            scene.tileViewMetadata(0, 0)
            scene.unmaskedDrawPlan()
            scene.normalDrawPlan()
            val output = ByteBuffer.allocateDirect(32 * 32 * 4)
            scene.draw(32, 32, Viewport(0.0, 0.0, 32.0, 32.0), output)
            return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
        }

        check(NativeDistortSessionBridge.takeScene(0) == 0L)
        check(NativeDistortSessionBridge.takeScene(-1) == 0L)
        val reservedBeforeFailure = nativeReserved()
        val discarded = snapshotToken()
        check(NativeDistortSessionBridge.destroyScene(discarded) == 0)
        check(runCatching { NativeScene.takeDistortSnapshot(discarded) }.isFailure)
        check(nativeReserved() == reservedBeforeFailure) { "Rejected scene transfer leaked a native snapshot claim" }

        val token = snapshotToken()
        val transferred = NativeScene.takeDistortSnapshot(token).also(scenes::add)
        check(NativeDistortSessionBridge.takeScene(token) == 0L) { "A scene token transferred twice" }
        check(NativeDistortSessionBridge.destroyScene(token) != 0) { "Registry retained ownership after transfer" }
        val original = inspect(transferred)
        check((original[0].toInt() and 255) == 220 && (original[3].toInt() and 255) == 255)

        val preview = worker.snapshot().also(scenes::add)
        val previewLease = DistortPreviewLease(preview)
        lease = previewLease
        check(previewLease.transferToRenderer())
        val frame = checkNotNull(previewLease.acquire())
        borrowed = true
        previewLease.retire()
        check(previewLease.acquire() == null) { "Retired preview started a new frame" }
        check(inspect(frame).contentEquals(original)) { "Retirement closed an active preview borrow" }
        worker.close()
        session = null
        check(inspect(transferred).contentEquals(original)) { "Session teardown invalidated its transferred scene" }
        check(inspect(frame).contentEquals(original)) { "Session teardown invalidated an active preview borrow" }
        previewLease.releaseBorrow()
        borrowed = false
        previewLease.rendererReleased()
        previewLease.retire()
        preview.close()
        preview.close()
        transferred.close()
        transferred.close()
        check(editor.resourceLedger.snapshot().let {
            it.reservedBytes == baseline.reservedBytes && it.reservations == baseline.reservations
        }) { "Distort teardown retained an arena reservation" }
        val commitProof = distortSessionCommitSmoke()
        evidence.writeText("PASS: actual JNI metadata/layer/tile/plans/render; opaque token transferred once to raw NativeScene owner; stale token rejected; failed transfer restored native claim; session teardown preserves captured scenes; active borrow survives retirement; repeated scene close and lease retirement; arena reservation restored. $commitProof. No Activity or user workspace opened.\n")
        return "Distort scene JNI ownership passed; evidence ${evidence.absolutePath}"
    } catch (failure: Throwable) {
        evidence.writeText(failure.stackTraceToString())
        throw failure
    } finally {
        if (borrowed) lease?.releaseBorrow()
        lease?.retire()
        scenes.forEach { it.close() }
        session?.close()
        editor.close()
    }
}
