package com.pojia.compositor.mobile

import androidx.compose.ui.platform.LocalContext

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun PaletteControls(foreground: Color, background: Color, enabled: Boolean,
    choose: (Boolean) -> Unit, swap: () -> Unit, defaults: () -> Unit) {
    val ui = LocalContext.current
    Box(Modifier.size(52.dp)) {
        Box(Modifier.offset(16.dp, 16.dp).size(34.dp).semantics { contentDescription = ui.getString(R.string.ui_background_color) }
            .clickable(enabled = enabled) { choose(true) }.padding(4.dp)
            .background(background).border(1.dp, Color.White.copy(alpha = .65f)))
        Box(Modifier.offset(2.dp, 2.dp).size(34.dp).semantics { contentDescription = ui.getString(R.string.ui_foreground_color) }
            .clickable(enabled = enabled) { choose(false) }.padding(4.dp)
            .background(foreground).border(1.dp, Color.White.copy(alpha = .65f)))
    }
    TextButton(onClick = swap, enabled = enabled, modifier = Modifier.size(48.dp)
        .semantics { contentDescription = ui.getString(R.string.ui_swap_colors_x) }, contentPadding = PaddingValues(0.dp)) { Text("⇄", fontSize = 19.sp) }
    TextButton(onClick = defaults, enabled = enabled, modifier = Modifier.size(48.dp)
        .semantics { contentDescription = ui.getString(R.string.ui_default_colors_d) }, contentPadding = PaddingValues(0.dp)) { Text("◩", fontSize = 18.sp) }
}

@Composable
internal fun EditorColorPicker(initial: Color, background: Boolean, title: String? = null, finish: (Color?) -> Unit) {
    val ui = LocalContext.current
    val initialHsv = remember(initial) { FloatArray(3).also { AndroidColor.RGBToHSV((initial.red*255).toInt(),
        (initial.green*255).toInt(), (initial.blue*255).toInt(), it) } }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var saturation by remember { mutableStateOf(initialHsv[1]) }
    var brightness by remember { mutableStateOf(initialHsv[2]) }
    val color = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    fun applyRgb(value: Color) {
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV((value.red*255).toInt(), (value.green*255).toInt(), (value.blue*255).toInt(), hsv)
        if (hsv[1] > 0) hue = hsv[0]
        saturation = hsv[1]; brightness = hsv[2]
    }
    var hex by remember { mutableStateOf("") }
    LaunchedEffect(color) { hex = String.format(Locale.ROOT, "%02X%02X%02X", (color.red*255).toInt(), (color.green*255).toInt(), (color.blue*255).toInt()) }
    AlertDialog(onDismissRequest = { finish(null) }, title = { Text(title ?: if (background) ui.getString(R.string.ui_background_color) else ui.getString(R.string.ui_foreground_color)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column { Text(ui.getString(R.string.ui_new_209), fontSize = 11.sp); Box(Modifier.size(56.dp, 32.dp).background(color).border(1.dp, Color.Gray)) }
                Column { Text(ui.getString(R.string.ui_current), fontSize = 11.sp); Box(Modifier.size(56.dp, 32.dp).background(initial).border(1.dp, Color.Gray)) }
            }
            // Match the Mac picker: a large saturation/brightness field with a vertical hue
            // strip beside it. Keeping both gestures in the same row makes the picker usable
            // on a phone in portrait without introducing a second scrolling surface.
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                Box(Modifier.weight(1f).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    .pointerInput(Unit) {
                        detectTapGestures { point -> saturation=(point.x/size.width).coerceIn(0f,1f); brightness=1-(point.y/size.height).coerceIn(0f,1f) }
                    }.pointerInput(Unit) {
                        detectDragGestures { change, _ -> change.consume(); saturation=(change.position.x/size.width).coerceIn(0f,1f); brightness=1-(change.position.y/size.height).coerceIn(0f,1f) }
                    }.semantics {
                        contentDescription = ui.getString(R.string.ui_color_saturation_and_brightness)
                        stateDescription = ui.getString(R.string.ui_color_saturation_brightness_value,
                            saturation * 100, brightness * 100)
                        customActions = listOf(
                            CustomAccessibilityAction(ui.getString(R.string.ui_increase_saturation)) {
                                val next = (saturation + .01f).coerceIn(0f, 1f)
                                val changed = next != saturation; saturation = next; changed
                            },
                            CustomAccessibilityAction(ui.getString(R.string.ui_decrease_saturation)) {
                                val next = (saturation - .01f).coerceIn(0f, 1f)
                                val changed = next != saturation; saturation = next; changed
                            },
                            CustomAccessibilityAction(ui.getString(R.string.ui_increase_brightness)) {
                                val next = (brightness + .01f).coerceIn(0f, 1f)
                                val changed = next != brightness; brightness = next; changed
                            },
                            CustomAccessibilityAction(ui.getString(R.string.ui_decrease_brightness)) {
                                val next = (brightness - .01f).coerceIn(0f, 1f)
                                val changed = next != brightness; brightness = next; changed
                            })
                    }) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    Canvas(Modifier.fillMaxSize()) {
                        val position = Offset(saturation * size.width, (1 - brightness) * size.height)
                        drawCircle(Color.Black, 5.dp.toPx(), position)
                        drawCircle(Color.White, 3.dp.toPx(), position)
                    }
                }
                val hueStops = listOf(360f, 300f, 240f, 180f, 120f, 60f, 0f)
                    .map { Color(AndroidColor.HSVToColor(floatArrayOf(it, 1f, 1f))) }
                Box(Modifier.width(24.dp).fillMaxHeight().background(Brush.verticalGradient(hueStops))
                    .pointerInput(Unit) {
                        detectTapGestures { point -> hue = (1f - (point.y / size.height).coerceIn(0f, 1f)) * 360f }
                    }.pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume(); hue = (1f - (change.position.y / size.height).coerceIn(0f, 1f)) * 360f
                        }
                    }.semantics {
                        contentDescription = ui.getString(R.string.ui_color_hue)
                        stateDescription = ui.getString(R.string.ui_color_hue_value, hue)
                        progressBarRangeInfo = ProgressBarRangeInfo(hue, 0f..360f, 359)
                        setProgress(ui.getString(R.string.ui_set_color_hue)) { requested ->
                            if (!requested.isFinite()) false else {
                                val next = requested.coerceIn(0f, 360f)
                                val changed = next != hue; hue = next; changed
                            }
                        }
                        customActions = listOf(
                            CustomAccessibilityAction(ui.getString(R.string.ui_increase_hue)) {
                                val next = (hue + 1f).coerceIn(0f, 360f)
                                val changed = next != hue; hue = next; changed
                            },
                            CustomAccessibilityAction(ui.getString(R.string.ui_decrease_hue)) {
                                val next = (hue - 1f).coerceIn(0f, 360f)
                                val changed = next != hue; hue = next; changed
                            })
                    }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val y = ((1f - hue / 360f).coerceIn(0f, 1f)) * size.height
                        drawRect(Color.Black, Offset(0f, y - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), style = Stroke(1.dp.toPx()))
                        drawLine(Color.White, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                    }
                }
            }
            NumericControl("R", color.red*255.0, 0.0..255.0, "", true) { applyRgb(Color((it/255).toFloat(), color.green, color.blue)) }
            NumericControl("G", color.green*255.0, 0.0..255.0, "", true) { applyRgb(Color(color.red, (it/255).toFloat(), color.blue)) }
            NumericControl("B", color.blue*255.0, 0.0..255.0, "", true) { applyRgb(Color(color.red, color.green, (it/255).toFloat())) }
            OutlinedTextField(value = hex, onValueChange = { hex = it.take(7) }, label = { Text(ui.getString(R.string.ui_hex)) }, singleLine = true,
                modifier = Modifier.semantics { contentDescription = ui.getString(R.string.ui_hex_color) })
        } }, confirmButton = { TextButton(onClick = {
            val digits = hex.removePrefix("#")
            val parsed = if (digits.matches(Regex("[0-9a-fA-F]{6}"))) Color((0xFF000000L or digits.toLong(16)).toInt()) else color
            finish(parsed)
        }) { Text(ui.getString(R.string.ui_ok)) } }, dismissButton = { TextButton(onClick = { finish(null) }) { Text(ui.getString(R.string.ui_cancel)) } })
}
