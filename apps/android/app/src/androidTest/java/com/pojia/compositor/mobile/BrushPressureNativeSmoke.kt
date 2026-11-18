package com.pojia.compositor.mobile

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Real JNI pressure dynamics against constant-tip controls and independent center channels. */
internal fun brushPressureNativeSmoke(instrumentation: Instrumentation): String {
    val side = 32
    val evidence = JSONArray()
    check(!StrokeSettings().pressureSize && !StrokeSettings().pressureOpacity) { "Pressure options must default off" }
    val transparent = ByteArray(side * side * 4)
    val red = ByteArray(transparent.size).also { bytes ->
        for (offset in bytes.indices step 4) { bytes[offset] = -1; bytes[offset + 3] = -1 }
    }
    fun pixels(editor: NativeEditor): ByteArray {
        val output = ByteBuffer.allocateDirect(transparent.size)
        check(editor.draw(side, side, 0.0, 0.0, side.toDouble(), side.toDouble(), output))
        return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
    }
    fun channel(bytes: ByteArray, x: Int, y: Int, c: Int) = bytes[(y * side + x) * 4 + c].toInt() and 255
    fun claims(editor: NativeEditor, ledger: ResourceBudgetLedger, label: String) {
        val state = ledger.snapshot()
        check(state.reservations == 1 && state.residentBytes == 0L && state.reservedBytes == editor.installedDocumentBytes()) {
            "$label left transient brush claims: $state"
        }
    }
    fun fixture(initial: ByteArray = transparent, action: (NativeEditor, Long, ResourceBudgetLedger) -> Unit) {
        val ledger = ResourceBudgetLedger(128L * 1024 * 1024)
        NativeEditor.createWithLedgerForTest(side, ledger, addDefaultLayers = false).use { editor ->
            val id = editor.importPixels(side, side, ByteBuffer.allocateDirect(initial.size).apply { put(initial); rewind() })
            try { action(editor, id, ledger) } finally { editor.endStroke(true) }
        }
        check(ledger.snapshot().reservedBytes == 0L && ledger.snapshot().reservations == 0)
    }
    fun sample(editor: NativeEditor, pressure: Double) = editor.samples(doubleArrayOf(16.5, 16.5, pressure, 0.0))
    fun dab(settings: StrokeSettings, pressure: Double, history: Boolean): ByteArray {
        var output = ByteArray(0)
        fixture { editor, id, ledger ->
            val before = editor.state()
            val callbacks = mutableListOf<Long>()
            editor.committed = { callbacks.add(it) }
            if (history) {
                editor.startStroke(id, settings); sample(editor, pressure); editor.endStroke(true)
                check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && pixels(editor).contentEquals(transparent) && callbacks.isEmpty())
                claims(editor, ledger, "Pressure cancel")
            }
            editor.startStroke(id, settings); sample(editor, pressure); editor.endStroke(false)
            output = pixels(editor)
            val changed = !settings.pressureOpacity || pressure > 0
            if (changed) {
                check(!output.contentEquals(transparent) && callbacks == listOf(before.revision + 1))
                if (history) {
                    editor.undo(false)
                    check(pixels(editor).contentEquals(transparent)) { "Pressure stroke Undo left coverage" }
                    claims(editor, ledger, "Pressure Undo")
                    editor.undo(true)
                    check(pixels(editor).contentEquals(output)) { "Pressure stroke Redo changed coverage" }
                    check(callbacks == listOf(before.revision + 1, before.revision + 2, before.revision + 3))
                }
            } else {
                check(output.contentEquals(transparent) && editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && callbacks.isEmpty()) {
                    "Zero pressure with opacity dynamics must not publish an invisible stroke"
                }
                if (history) {
                    // The sole import must be removed by one Undo: no hidden stroke entry.
                    editor.undo(false)
                    check(editor.state().layers.isEmpty())
                    editor.undo(true)
                    check(editor.state().layers == before.layers && pixels(editor).contentEquals(transparent))
                }
            }
            claims(editor, ledger, "Pressure dab")
            editor.committed = null
        }
        return output
    }
    for (hardness in listOf(0f, 1f)) {
        val base = StrokeSettings(diameter = 16f, hardness = hardness, opacity = .5f, green = 1f)
        val full = dab(base, 1.0, false)
        for (size in listOf(false, true)) for (opacity in listOf(false, true)) for (pressure in listOf(0.0, .25, 1.0)) {
            val label = "size=$size opacity=$opacity pressure=$pressure hardness=$hardness"
            val settings = base.copy(pressureSize = size, pressureOpacity = opacity)
            val actual = dab(settings, pressure, true)
            val expectedAlpha = if (opacity) (255 * pressure * .5).roundToInt() else 128
            check(channel(actual, 16, 16, 3) == expectedAlpha && channel(actual, 16, 16, 1) == expectedAlpha &&
                channel(actual, 16, 16, 0) == 0 && channel(actual, 16, 16, 2) == 0) {
                "$label has wrong independent center RGBA: green=${channel(actual, 16, 16, 1)}, alpha=${channel(actual, 16, 16, 3)}"
            }
            if (!size && !opacity || pressure == 1.0)
                check(actual.contentEquals(full)) { "$label changed pressure-independent pixels" }
            var maximumReferenceError = 0
            if (!opacity || pressure > 0) {
                val effectiveDiameter = if (size) max(1.0, base.diameter * pressure).toFloat() else base.diameter
                val effectiveOpacity = if (opacity) (base.opacity * pressure).toFloat() else base.opacity
                // Single-point reference only: changing diameter would change a moving
                // soft stroke's dab spacing. Coverage is quantized before global opacity,
                // so moving a constant strength into opacity permits one channel LSB.
                val reference = dab(base.copy(diameter = effectiveDiameter, opacity = effectiveOpacity), 1.0, false)
                for (index in actual.indices) maximumReferenceError = max(maximumReferenceError,
                    abs((actual[index].toInt() and 255) - (reference[index].toInt() and 255)))
                check(maximumReferenceError <= 1) { "$label differs from constant-tip control by $maximumReferenceError" }
            }
            val nonzero = (0 until side * side).count { (actual[it * 4 + 3].toInt() and 255) != 0 }
            if (size && pressure == 0.0 && !opacity) check(nonzero == 1) { "Size pressure zero did not keep the one-pixel tip" }
            if (size && pressure == .25 && !opacity) {
                check(channel(actual, 20, 16, 3) == 0 && channel(full, 20, 16, 3) > 0) {
                    "$label did not narrow the brush footprint"
                }
            }
            if (opacity && pressure == 0.0) check(nonzero == 0)
            evidence.put(JSONObject().put("pressureSize", size).put("pressureOpacity", opacity).put("pressure", pressure)
                .put("hardness", hardness).put("centerAlpha", expectedAlpha).put("nonzeroPixels", nonzero)
                .put("maximumReferenceChannelError", maximumReferenceError))
        }
    }

    // A layer without an asset must remain asset-free after zero-strength input.
    val blankLedger = ResourceBudgetLedger(128L * 1024 * 1024)
    NativeEditor.createWithLedgerForTest(side, blankLedger, addDefaultLayers = false).use { editor ->
        editor.addLayer()
        val before = editor.state()
        val layer = before.layers.single()
        check(!layer.assetPresent)
        val callbacks = mutableListOf<Long>()
        editor.committed = { callbacks.add(it) }
        for (size in listOf(false, true)) {
            editor.startStroke(layer.id, StrokeSettings(diameter = 16f, pressureSize = size, pressureOpacity = true))
            sample(editor, 0.0); editor.endStroke(false)
            check(editor.state().copy(selectedLayerActions = null) == before.copy(selectedLayerActions = null) && !editor.state().layers.single().assetPresent && callbacks.isEmpty()) {
                "Zero-strength stroke allocated or published a blank asset"
            }
            claims(editor, blankLedger, "Zero strength on asset-free layer")
        }
        editor.undo(false)
        check(editor.state().layers.isEmpty()) { "Zero-strength stroke inserted an invisible history entry" }
        editor.undo(true)
        check(editor.state().layers == before.layers && !editor.state().layers.single().assetPresent)
        editor.committed = null
    }
    check(blankLedger.snapshot().reservedBytes == 0L && blankLedger.snapshot().reservations == 0)

    // Pressure opacity applies to erasing image pixels and to either mask color;
    // the Eraser switch does not invert the explicitly selected mask paint color.
    fixture(red) { editor, id, ledger ->
        val settings = StrokeSettings(diameter = 16f, hardness = 1f, opacity = .5f, pressureOpacity = true)
        val before = editor.currentRevision()
        editor.startStroke(id, settings, erasing = true); sample(editor, .25); editor.endStroke(false)
        val erased = pixels(editor)
        check(channel(erased, 16, 16, 0) == 223 && channel(erased, 16, 16, 3) == 223)
        check(channel(erased, 0, 0, 3) == 255 && editor.currentRevision() == before + 1)
        editor.undo(false); check(pixels(editor).contentEquals(red))
        editor.undo(true); check(pixels(editor).contentEquals(erased))
        claims(editor, ledger, "Pressure Eraser")
    }
    for (white in listOf(false, true)) for (erasing in listOf(false, true)) {
        fixture(red) { editor, id, ledger ->
            editor.createMask(id, !white)
            fun mask(): ByteArray {
                val output = ByteBuffer.allocateDirect(side * side)
                editor.maskPixels(id, side, output)
                return ByteArray(output.capacity()).also { output.rewind(); output.get(it) }
            }
            val before = mask()
            val settings = StrokeSettings(diameter = 16f, hardness = 1f, opacity = .5f,
                maskPaintWhite = white, pressureSize = true, pressureOpacity = true)
            val revision = editor.currentRevision()
            editor.startStroke(id, settings, erasing = erasing, mask = true); sample(editor, .25); editor.endStroke(true)
            check(mask().contentEquals(before) && editor.currentRevision() == revision)
            editor.startStroke(id, settings, erasing = erasing, mask = true); sample(editor, .25); editor.endStroke(false)
            val painted = mask()
            check((painted[16 * side + 16].toInt() and 255) == if (white) 32 else 223)
            check(painted[16 * side + 20] == before[16 * side + 20])
            editor.undo(false); check(mask().contentEquals(before))
            editor.undo(true); check(mask().contentEquals(painted))
            claims(editor, ledger, "Pressure mask white=$white erasing=$erasing")
        }
    }

    // Passing Brush options through a shared settings value must not activate
    // pressure in Clone Stamp, which has separate Mac tool semantics.
    var cloneReference: ByteArray? = null
    val source = red.copyOf().also { bytes ->
        for (y in 0 until side) for (x in 0 until 16) {
            bytes[(y * side + x) * 4] = 0
            bytes[(y * side + x) * 4 + 1] = -1
        }
    }
    for (enabled in listOf(false, true)) for (pressure in listOf(0.0, .25, 1.0)) fixture(source) { editor, id, ledger ->
        editor.startCloneStroke(id, StrokeSettings(diameter = 8f, hardness = 1f, opacity = .25f,
            pressureSize = enabled, pressureOpacity = enabled), -16.0, 0.0, false)
        editor.samples(doubleArrayOf(24.5, 16.5, pressure, 0.0)); editor.endStroke(false)
        val copied = pixels(editor)
        val reference = cloneReference
        if (reference == null) cloneReference = copied else check(copied.contentEquals(reference)) {
            "Clone inherited Brush pressure dynamics: enabled=$enabled pressure=$pressure"
        }
        check(channel(copied, 24, 16, 0) == 191 && channel(copied, 24, 16, 1) == 64 && channel(copied, 24, 16, 3) == 255)
        claims(editor, ledger, "Clone ignores Brush pressure")
    }
    val file = File(instrumentation.targetContext.filesDir, "p76-pressure-native.json")
    file.writeText(JSONObject().put("scope", "Real JNI single-dab dynamics, Eraser/masks, synthetic pressure and Clone isolation; no physical stylus or performance claim")
        .put("cases", evidence).toString(2))
    return "Real JNI four pressure toggles × pressure0/0.25/1 × hard/soft tips passed center channels, single-dab constant-tip controls, one-pixel size floor, zero-strength no-op, cancel/Undo/Redo and claims. Eraser and four mask color/Eraser combinations passed; Clone ignored both Brush pressure options at all three pressures. Evidence ${file.name}. No physical stylus, moving-soft-stroke Mac pixel parity or performance claim."
}
