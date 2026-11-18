package com.pojia.compositor.mobile

import android.app.Instrumentation
import android.view.KeyEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real Scale text/Flip controls distinguish focused completion, preview, publication and history. */
internal fun moveInspectorUiSmoke(instrumentation: Instrumentation): String {
    MoveInspectorFixtureSmoke(instrumentation, "moveInspectorUi").use { fixture ->
        with(fixture) {
            fun unchanged(before: EditorState, owner: CompletableFuture<TransformController.Terminal>, persistent: Boolean) {
                check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && editor.activeTransformCompletion() === owner && !owner.isDone &&
                    canvas.movePersistent == persistent && (persistent || canvas.moveFromFields)) {
                    "Inspector preview changed committed state, owner identity or provenance"
                }
            }
            fun assertNoOrdinaryButtons() {
                editorUiAutomation(instrumentation).clearCache()
                val root = editorUiAutomation(instrumentation).rootInActiveWindow
                check(node(root, "Apply Transform") == null && node(root, "Cancel Transform") == null)
            }
            fun undoRedo(before: EditorState, beforePixels: ByteArray, afterPixels: ByteArray, expected: Transform64) {
                click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers == before.layers && editor.state().redo && pixels().contentEquals(beforePixels)) {
                    "One real UI Undo did not restore the entire Inspector segment"
                }
                click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers.single().transform == expected && pixels().contentEquals(afterPixels))
            }
            val original = Transform64(10.0, 20.0, 80.0, 60.0, 37.0, true, false)
            try {
                install()
                var before = editor.state()
                var beforePixels = pixels()
                var sourceBefore = source()
                check(focusScale().toDouble() == 200.0)
                check(editor.activeTransformCompletion() == null)
                key(KeyEvent.KEYCODE_ENTER); canvasFocused(); settled()
                check(editor.state() == before && pixels().contentEquals(beforePixels) && source() == sourceBefore) {
                    "Untouched Scale focus/Return recalculated the nonuniform placement"
                }
                check(editor.state().layers.single().transform == original)
                stage("untouched-200-return-no-recalculation", original)

                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                check(focusScale().toDouble() == 200.0)
                scaleText("200.0", focus = false)
                var expected = Transform64(10.0, 30.0, 80.0, 40.0, 37.0, true, false)
                preview(expected)
                var owner = checkNotNull(editor.activeTransformCompletion())
                unchanged(before, owner, false); assertNoOrdinaryButtons()
                check(source() == sourceBefore)
                key(KeyEvent.KEYCODE_ENTER); canvasFocused(); settled()
                check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
                check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == expected)
                var afterPixels = pixels()
                undoRedo(before, beforePixels, afterPixels, expected)
                check(source() == sourceBefore)
                stage("actual-text-200-to-200.0-one-segment", expected)

                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                scaleText("50")
                expected = Transform64(40.0, 45.0, 20.0, 10.0, 37.0, true, false)
                preview(expected); owner = checkNotNull(editor.activeTransformCompletion())
                unchanged(before, owner, false)
                val firstSequence = canvas.moveAcceptedSequence
                scaleText("100", focus = false)
                expected = Transform64(30.0, 40.0, 40.0, 20.0, 37.0, true, false)
                preview(expected); unchanged(before, owner, false)
                check(canvas.moveAcceptedSequence > firstSequence)
                scaleText("12.8125", focus = false)
                expected = Transform64(47.4375, 48.71875, 5.125, 2.5625, 37.0, true, false)
                preview(expected); unchanged(before, owner, false)
                check(source() == sourceBefore)
                key(KeyEvent.KEYCODE_ENTER); canvasFocused(); settled()
                check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == expected)
                afterPixels = pixels(); undoRedo(before, beforePixels, afterPixels, expected)
                stage("50-100-fraction-original-basis-one-segment", expected)

                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                click("Flip Horizontal")
                expected = original.copy(flipX = false)
                preview(expected); owner = checkNotNull(editor.activeTransformCompletion())
                unchanged(before, owner, false); assertNoOrdinaryButtons()
                check(source() == sourceBefore)
                key(KeyEvent.KEYCODE_ENTER); settled()
                check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
                check(editor.state().revision == before.revision + 1)
                afterPixels = pixels(); undoRedo(before, beforePixels, afterPixels, expected)
                stage("ordinary-flip-preview-then-enter-one-segment", expected)

                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                click("Flip Horizontal"); preview(original.copy(flipX = false))
                owner = checkNotNull(editor.activeTransformCompletion())
                click("Flip Horizontal"); preview(original)
                unchanged(before, owner, false)
                key(KeyEvent.KEYCODE_ENTER); settled()
                check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == false && it.error == null })
                check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels().contentEquals(beforePixels) && source() == sourceBefore) {
                    "HH no-op changed revision/history/redo or pixels"
                }
                stage("ordinary-HH-noop-preserves-redo", original)

                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                scaleText("50")
                val beforeFlip = Transform64(40.0, 45.0, 20.0, 10.0, 37.0, true, false)
                preview(beforeFlip); owner = checkNotNull(editor.activeTransformCompletion())
                val beforeFlipPixels = pixels()
                unchanged(before, owner, false)
                click("Flip Horizontal")
                expected = beforeFlip.copy(flipX = false)
                preview(expected)
                val successor = checkNotNull(editor.activeTransformCompletion())
                check(successor === owner && !successor.isDone && canvas.moveFromFields) {
                    "Field-to-Flip successor must retain the same unfinished command fence"
                }
                check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == beforeFlip) {
                    "A real field blur must finish Scale before the Flip successor preview"
                }
                check(source() == sourceBefore)
                key(KeyEvent.KEYCODE_ENTER); settled()
                check(successor.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
                check(editor.state().revision == before.revision + 2 && editor.state().layers.single().transform == expected)
                afterPixels = pixels()
                click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers.single().transform == beforeFlip && pixels().contentEquals(beforeFlipPixels))
                click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers == before.layers && pixels().contentEquals(beforePixels))
                click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers.single().transform == expected && pixels().contentEquals(afterPixels))
                stage("actual-field-blur-to-flip-two-segments", expected)

                val handoffOriginal = Transform64(50.0, 60.0, 80.0, 60.0)
                install(box = handoffOriginal)
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                scaleText("400")
                val beforeDrag = Transform64(10.0, 50.0, 160.0, 80.0)
                preview(beforeDrag); owner = checkNotNull(editor.activeTransformCompletion())
                val beforeDragPixels = pixels()
                unchanged(before, owner, false)
                bodyDrag(beforeDrag, 12.0, 7.0); settled()
                expected = Transform64(22.0, 57.0, 160.0, 80.0)
                check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
                val afterHandoff = editor.state()
                stage("scale-to-pointer-actual-before-assertion", expected)
                check(afterHandoff.revision == before.revision + 2 && afterHandoff.layers.single().transform == expected) {
                    "Scale-to-pointer handoff must publish Inspector and actual drag as two segments: " +
                        "revision=${afterHandoff.revision}, expectedRevision=${before.revision + 2}, " +
                        "transform=${afterHandoff.layers.single().transform}, expected=$expected"
                }
                val afterDragPixels = pixels()
                check(source() == sourceBefore)
                click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers.single().transform == beforeDrag && pixels().contentEquals(beforeDragPixels))
                click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers == before.layers && pixels().contentEquals(beforePixels))
                click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers.single().transform == beforeDrag)
                click("Redo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers.single().transform == expected && pixels().contentEquals(afterDragPixels))
                stage("scale-to-real-DOWN-MOVE-UP-two-segments", expected)

                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
                waitUntil("Ctrl+T did not start persistent Inspector fixture") { canvas.movePersistent && editor.hasActiveTransformDraft }
                owner = checkNotNull(editor.activeTransformCompletion())
                scaleText("50"); expected = Transform64(40.0, 45.0, 20.0, 10.0, 37.0, true, false)
                preview(expected); key(KeyEvent.KEYCODE_ENTER); canvasFocused()
                unchanged(before, owner, true)
                click("Flip Horizontal"); expected = expected.copy(flipX = false); preview(expected)
                click("Flip Vertical"); expected = expected.copy(flipY = true); preview(expected)
                unchanged(before, owner, true)
                recreate(expected, owner)
                check(canvas.movePersistent && canvas.moveScalePercent == 50.0)
                scaleText("100"); expected = Transform64(30.0, 40.0, 40.0, 20.0, 37.0, false, true)
                preview(expected); key(KeyEvent.KEYCODE_ENTER); canvasFocused()
                unchanged(before, owner, true)
                check(source() == sourceBefore)
                click("Apply Transform"); settled()
                check(owner.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
                check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == expected)
                afterPixels = pixels(); undoRedo(before, beforePixels, afterPixels, expected)
                stage("persistent-scale-flips-recreate-100-apply-single-undo", expected)

                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)
                waitUntil("Cancel fixture Ctrl+T did not start") { canvas.movePersistent && editor.hasActiveTransformDraft }
                owner = checkNotNull(editor.activeTransformCompletion())
                scaleText("50"); expected = Transform64(40.0, 45.0, 20.0, 10.0, 37.0, true, false)
                preview(expected); key(KeyEvent.KEYCODE_ENTER); canvasFocused()
                click("Flip Horizontal"); expected = expected.copy(flipX = false); preview(expected)
                recreate(expected, owner)
                check(canvas.movePersistent && canvas.moveScalePercent == 50.0)
                scaleText("100"); expected = Transform64(30.0, 40.0, 40.0, 20.0, 37.0, false, false)
                preview(expected); key(KeyEvent.KEYCODE_ENTER); canvasFocused()
                unchanged(before, owner, true)
                click("Cancel Transform"); settled()
                check(owner.get(10, TimeUnit.SECONDS).let { it.cancelled && !it.committed && it.error == null })
                check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels().contentEquals(beforePixels) && source() == sourceBefore)
                stage("persistent-scale-flip-recreate-cancel-exact-redo", original)

                // Drive the public field-session callbacks in a fixed order, independently of AX focus scheduling.
                install()
                before = editor.state(); beforePixels = pixels(); sourceBefore = source()
                val controller = editor.retainedTransformController(instrumentation.targetContext)
                lateinit var fieldA: MoveFieldSession
                lateinit var fieldB: MoveFieldSession
                lateinit var firstOwner: TransformController.Owner
                instrumentation.runOnMainSync {
                    fieldA = canvas.beginMoveFieldSession()
                    check(canvas.editMoveScale(50.0, fieldA))
                    firstOwner = checkNotNull(fieldA.owner)
                    val accepted = checkNotNull(controller.snapshot()?.accepted)
                    fieldB = canvas.beginMoveFieldSession()
                    check(fieldA.completed && !fieldB.completed && fieldB.owner == null && canvas.moveSettling &&
                        controller.isFinishing(firstOwner) && !firstOwner.finished.isDone) {
                        "Beginning B before A's blur must finish A without binding B to its settling owner"
                    }
                    check(!canvas.editMoveScale(100.0, fieldB) && !canvas.editMoveField(MoveField.X, 42.0, fieldB)) {
                        "Settling must reject numeric updates even from the current field stamp"
                    }
                    check(!canvas.editMoveScale(200.0, fieldA)) { "A completed field stamp accepted an edit" }
                    canvas.completeMoveField(fieldA)
                    check(!fieldB.completed && canvas.moveSettling && controller.snapshot()?.owner === firstOwner &&
                        controller.snapshot()?.accepted == accepted && !firstOwner.finished.isDone) {
                        "A duplicate late completion changed B or the settling predecessor"
                    }
                }
                settled()
                val fieldFirst = Transform64(40.0, 45.0, 20.0, 10.0, 37.0, true, false)
                check(firstOwner.finished.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
                check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == fieldFirst)
                val fieldFirstPixels = pixels()
                lateinit var nextOwner: TransformController.Owner
                instrumentation.runOnMainSync {
                    check(!fieldB.completed && canvas.editMoveScale(100.0, fieldB)) {
                        "Beginning B before A's blur lost the current field stamp after A settled"
                    }
                    nextOwner = checkNotNull(fieldB.owner)
                    check(nextOwner !== firstOwner && nextOwner.finished !== firstOwner.finished)
                    val accepted = checkNotNull(controller.snapshot()?.accepted)
                    canvas.completeMoveField(fieldA)
                    check(!fieldB.completed && !canvas.moveSettling && !controller.isFinishing(nextOwner) &&
                        controller.snapshot()?.owner === nextOwner && controller.snapshot()?.accepted == accepted &&
                        !nextOwner.finished.isDone) { "A stale completion finished or rewrote B's new owner" }
                    check(!canvas.editMoveField(MoveField.X, 91.0, fieldA))
                    check(controller.snapshot()?.accepted == accepted)
                }
                expected = Transform64(30.0, 40.0, 40.0, 20.0, 37.0, true, false)
                preview(expected)
                check(editor.state().revision == before.revision + 1 && editor.state().layers.single().transform == fieldFirst &&
                    source() == sourceBefore)
                instrumentation.runOnMainSync { canvas.completeMoveField(fieldB) }
                settled()
                check(nextOwner.finished.get(10, TimeUnit.SECONDS).let { it.committed && it.result?.changed == true && it.error == null })
                check(editor.state().revision == before.revision + 2 && editor.state().layers.single().transform == expected)
                click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers.single().transform == fieldFirst && pixels().contentEquals(fieldFirstPixels))
                click("Undo"); editor.commandDispatcher.barrier().get(10, TimeUnit.SECONDS)
                check(editor.state().layers == before.layers && pixels().contentEquals(beforePixels) && source() == sourceBefore)
                stage("ordered-callback-B-before-A-blur-stale-A-and-settling-rejection", expected)
                // Added coverage: an ordinary uncommitted Scale field retains its owner across actual Activity recreation.
                install()
                val fieldRecreateBefore = editor.state()
                val fieldRecreateBeforePixels = pixels()
                val fieldRecreateSource = source()
                scaleText("50")
                val fieldRecreateHalf = Transform64(40.0, 45.0, 20.0, 10.0, 37.0, true, false)
                preview(fieldRecreateHalf)
                val fieldRecreateOwner = checkNotNull(editor.activeTransformCompletion())
                val fieldRecreatePreviewPixels = pixels()
                unchanged(fieldRecreateBefore, fieldRecreateOwner, false); assertNoOrdinaryButtons()
                check(canvas.moveFromFields && !canvas.movePersistent && canvas.moveScalePercent == 50.0 &&
                    source() == fieldRecreateSource)
                stage("ordinary-field-scale50-uncommitted-before-recreate", fieldRecreateHalf)
                // Do not Return, blur or Apply before this lifecycle transition.
                recreate(fieldRecreateHalf, fieldRecreateOwner)
                unchanged(fieldRecreateBefore, fieldRecreateOwner, false); assertNoOrdinaryButtons()
                check(canvas.moveFromFields && !canvas.movePersistent && canvas.moveScalePercent == 50.0 &&
                    pixels().contentEquals(fieldRecreatePreviewPixels) && source() == fieldRecreateSource) {
                    "Recreation changed ordinary field provenance, accepted pixels or source payload"
                }
                check(focusScale().toDouble() == 50.0)
                unchanged(fieldRecreateBefore, fieldRecreateOwner, false)
                scaleText("100", focus = false)
                val fieldRecreateFull = Transform64(30.0, 40.0, 40.0, 20.0, 37.0, true, false)
                preview(fieldRecreateFull)
                unchanged(fieldRecreateBefore, fieldRecreateOwner, false); assertNoOrdinaryButtons()
                check(canvas.moveScalePercent == 100.0 && source() == fieldRecreateSource)
                key(KeyEvent.KEYCODE_ENTER); canvasFocused(); settled()
                check(fieldRecreateOwner.get(10, TimeUnit.SECONDS).let {
                    it.committed && it.result?.changed == true && it.error == null
                })
                check(editor.state().revision == fieldRecreateBefore.revision + 1 &&
                    editor.state().layers.single().transform == fieldRecreateFull && source() == fieldRecreateSource) {
                    "Ordinary field recreation must retain the original Scale basis and publish exactly one segment"
                }
                val fieldRecreateAfterPixels = pixels()
                undoRedo(fieldRecreateBefore, fieldRecreateBeforePixels, fieldRecreateAfterPixels, fieldRecreateFull)
                check(source() == fieldRecreateSource)
                stage("ordinary-field-scale50-recreate-refocus100-return-single-undo-redo", fieldRecreateFull)
                successful = true
            } catch (error: Throwable) { diagnose(error); throw error }
        }
    }
    return "Real Scale untouched Return versus200→200.0 text edit;50→100→fraction original basis; ordinary Flip preview/Enter andHH no-op redo; actual field-blur→Flip and Scale→DOWN/MOVE/UP each two exact Undo segments; Ctrl+T Scale/H/V retained owner, actual Apply/Cancel recreations and post-rebind100 basis; committed-state isolation and source payload/hash preservation passed. Separate ordered field-session callbacks verify B-before-A-blur, settling rejection and stale-A isolation with two native commits/UI Undo. Mask/GLES and pending-pointer race matrices remain separate acceptance."
}
