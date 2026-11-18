package com.pojia.compositor.mobile

import android.content.Context
import java.io.File
import java.nio.ByteBuffer

/** Exercises the packaged JNI writer/loader, not only native checkpoint recovery. */
internal fun typedProjectRoundTripSmoke(context: Context, editor: NativeEditor, layerId: Long,
    verify: (NativeEditor, Long) -> Unit) {
    val uuid = editor.state().layers.single { it.id == layerId }.uuid
    fun rendered(value: NativeEditor): ByteArray {
        val bytes = ByteBuffer.allocateDirect(value.canvasWidth * value.canvasHeight * 4)
        check(value.draw(value.canvasWidth, value.canvasHeight, 0.0, 0.0,
            value.canvasWidth.toDouble(), value.canvasHeight.toDouble(), bytes))
        return ByteArray(bytes.capacity()).also { bytes.rewind(); bytes.get(it) }
    }
    val before = rendered(editor)
    val root = File(context.cacheDir, "p97-typed-roundtrip-${java.util.UUID.randomUUID()}")
    check(root.mkdir())
    try {
        val target = File(root, "typed.comp")
        editor.captureProjectSnapshot().use { snapshot ->
            NativeProjectWriteControl(128L * 1024 * 1024, 0).use { control ->
                check(snapshot.write(context, target, false, control).successful)
            }
        }
        NativeEditor.loadProjectCandidate(context, target, 128L * 1024 * 1024, 0).use { restored ->
            val restoredId = restored.state().layers.single { it.uuid == uuid }.id
            verify(restored, restoredId)
            check(rendered(restored).contentEquals(before)) { "Typed project pixels changed after native package reload" }
        }
    } finally { check(root.deleteRecursively()) }
}
