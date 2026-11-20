package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.net.Uri
import android.view.KeyEvent
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

internal fun duplicateNudgeSmoke(instrumentation: Instrumentation, selector: String): String = when (selector) {
    "duplicateNudgeUi" -> {
        duplicateNudgeDraftSmoke(instrumentation)
        "Alt+Arrow shifts a copy by one pixel; Shift shifts it by ten. Exact undo/redo pixels and persistent draft handoff."
    }
    "duplicateNudgeMaskUi" -> {
        duplicateNudgeMaskSmoke(instrumentation)
        "Copy moves layer pixels and preserves high quality mask metadata. Project reload and PNG export preserve rendered pixels."
    }
    "duplicateNudgeGroupUi" -> {
        duplicateNudgeGroupSmoke(instrumentation)
        "Copy preserves nested group topology and shifts only visible drawable descendants."
    }
    else -> error("Unknown duplicate nudge selector: $selector")
}

private fun duplicateNudgeDraftSmoke(instrumentation: Instrumentation) {
    MoveInspectorFixtureSmoke(instrumentation, "duplicateNudge").use { fixture -> with(fixture) {
        try {
            install()
            canvasFocused()
            val baseline = editor.state()
            val original = baseline.layers.single()
            val beforePixels = pixels()
            key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_ALT_ON)
            waitUntil("Alt+Right did not publish a copy") { editor.state().layers.size == 2 }
            settled()
            val first = editor.state()
            val copy = first.layers.single { it.id != original.id }
            check(first.layers.single { it.id == original.id } == original)
            check(copy.transform == original.transform.copy(x = original.transform.x + 1.0))
            check(editor.selection().primary == copy.id && first.revision == baseline.revision + 1)
            val afterPixels = pixels()
            check(!afterPixels.contentEquals(beforePixels))
            stage("alt-right", copy.transform)
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == baseline.layers && pixels().contentEquals(beforePixels))
            click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == first.layers && pixels().contentEquals(afterPixels))
            canvasFocused()
            val primary = editor.state().layers.single { it.id == editor.selection().primary }
            key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
            waitUntil("Ctrl+T did not start a persistent transform") { canvas.movePersistent }
            canvasFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val draft = primary.transform.copy(y = primary.transform.y + 1.0)
            waitUntil("Plain Down did not update the transform draft") {
                canvas.moveBox == draft && editor.hasActiveTransformDraft
            }
            val beforeHandoff = editor.state()
            check(beforeHandoff.layers == first.layers)
            key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)
            waitUntil("Alt+Shift+Up did not publish a copy") { editor.state().layers.size == 3 }
            settled()
            val afterHandoff = editor.state()
            val second = afterHandoff.layers.single { layer -> first.layers.none { it.id == layer.id } }
            val moved = first.layers.map { if (it.id == primary.id) it.copy(transform = draft) else it }
            check(afterHandoff.layers.filter { it.id != second.id } == moved)
            check(second.transform == draft.copy(y = draft.y - 10.0))
            check(afterHandoff.revision == beforeHandoff.revision + 2)
            stage("alt-shift-up-after-draft", second.transform)
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == moved) { "Undo of the copy also reverted its predecessor draft" }
            click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
            check(editor.state().layers == first.layers && pixels().contentEquals(afterPixels))
            successful = true
        } catch (error: Throwable) {
            diagnose(error)
            throw error
        }
    } }
}

private fun duplicateNudgeMaskSmoke(instrumentation: Instrumentation) {
    MoveInspectorFixtureSmoke(instrumentation, "duplicateNudge-mask").use { fixture -> with(fixture) {
        try {
            installAuthority {
                NativeEditor(256, false).also { candidate ->
                    val id = candidate.importPixels(4, 4, ByteBuffer.allocateDirect(64).apply {
                        put(moveInspectorColorPixels(4, 4)); rewind()
                    })
                    candidate.setTransform(id, Transform64(20.0, 24.0, 24.0, 28.0))
                    candidate.createMask(id, white = true)
                    candidate.linkMask(id, false)
                    candidate.setMaskPlacement(id, Transform64(17.0, 19.0, 40.0, 42.0), sampling = 2)
                    candidate.selectLayers(listOf(id), id, mask = true)
                }
            }
            canvasFocused()
            check(canvas.editingMask && editor.selection().mask)
            val original = editor.state().layers.single()
            check(original.maskSampling == 2 && pixels().any { it != 0.toByte() })
            key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_ALT_ON)
            waitUntil("Alt+Left on the mask did not publish a copy") { editor.state().layers.size == 2 }
            settled()
            val layers = editor.state().layers
            val copy = layers.single { it.id != original.id }
            check(layers.single { it.id == original.id } == original)
            check(copy.transform == original.transform.copy(x = original.transform.x - 1.0))
            check(copy.hasMask && !copy.maskLinked && copy.maskPlacement == original.maskPlacement &&
                copy.maskSampling == original.maskSampling)
            check(editor.selection().primary == copy.id && !editor.selection().mask)
            stage("unlinked-mask-original-and-copy", copy.transform)
            verifyPlacedMaskFiles(instrumentation, editor, pixels())
            stage("project-roundtrip-and-png", copy.transform)
            successful = true
        } catch (error: Throwable) {
            diagnose(error)
            throw error
        }
    } }
}

private fun verifyPlacedMaskFiles(instrumentation: Instrumentation, editor: NativeEditor, expected: ByteArray) {
    val context = instrumentation.targetContext
    val directory = File(context.cacheDir, "placed-mask-${System.nanoTime()}").also { check(it.mkdir()) }
    val limit = 64L * 1024 * 1024
    val saved = editor.state()
    try {
        val project = File(directory, "copy.comp")
        editor.captureProjectSnapshot().use { snapshot ->
            NativeProjectWriteControl(limit, editor.retainedMemory().totalBytes).use { control ->
                val result = snapshot.write(context, project, false, control)
                check(result.successful && result.capturedRevision == saved.revision)
            }
        }
        NativeEditor.loadProjectCandidate(context, project, limit, 0).use { restored ->
            val layers = restored.state().layers.associateBy { it.uuid }
            check(layers.size == saved.layers.size)
            saved.layers.forEach { original ->
                val loaded = layers.getValue(original.uuid)
                check(loaded.transform == original.transform && loaded.hasMask && !loaded.maskLinked &&
                    loaded.maskPlacement == original.maskPlacement && loaded.maskSampling == 2)
            }
            val uri = Uri.fromFile(File(directory, "composite.png"))
            NativeImageIO.exportPng(context, uri, restored)
            NativeImageIO.decode(context, uri).use { decoded ->
                check(decoded.width == 256 && decoded.height == 256)
                val bytes = ByteArray(expected.size).also { decoded.pixels.duplicate().get(it) }
                check(bytes.contentEquals(expected)) { "Reloaded project PNG differs from the canvas render" }
            }
        }
        check(editor.state() == saved)
    } finally { directory.deleteRecursively() }
}

private fun duplicateNudgeGroupSmoke(instrumentation: Instrumentation) {
    MoveInspectorFixtureSmoke(instrumentation, "duplicateNudge-group").use { fixture -> with(fixture) {
        try {
            installAuthority { NativeEditor(256, false).also { installGroupScaleFixture(it, grouped = true) } }
            canvasFocused()
            val before = editor.state().layers
            val beforeIds = before.map { it.id }.toSet()
            key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_ALT_ON)
            waitUntil("Alt+Down did not publish the group copy") { editor.state().layers.size == before.size * 2 }
            settled()
            val after = editor.state().layers
            check(after.filter { it.id in beforeIds } == before)
            val copies = after.filter { it.id !in beforeIds }
            val mapped = before.associate { original ->
                val name = if (original.parentId == 0L) "${original.name} copy" else original.name
                original.id to copies.single { it.name == name }
            }
            before.forEach { original ->
                val copy = mapped.getValue(original.id)
                check(copy.parentId == (mapped[original.parentId]?.id ?: 0L))
                val expected = if (original.isGroup || !original.visible) original.transform
                    else original.transform.copy(y = original.transform.y + 1.0)
                check(groupScaleBoxEquals(copy.transform, expected)) { "Unexpected group copy geometry: ${copy.name}" }
            }
            val root = copies.single { it.parentId == 0L }
            check(editor.selection().roots == listOf(root.id) && editor.selection().primary == root.id)
            stage("nested-group-copy", root.transform)
            successful = true
        } catch (error: Throwable) {
            diagnose(error)
            throw error
        }
    } }
}
