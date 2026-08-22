package com.example.eps_sgtracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A self-contained HSV color picker: a saturation/value square for the currently selected hue,
 * plus a hue slider strip. Compose has no built-in color picker widget, so this is a minimal
 * custom one (Canvas + raw pointer-event tracking, same awaitEachGesture/awaitFirstDown pattern
 * already used for the globe's rotate/pan/zoom gestures in Satellite3DView.kt) rather than
 * pulling in a third-party dependency just for this.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit,
    onReset: () -> Unit
) {
    val initialHsv = remember(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (initialColor.red * 255f).toInt(),
            (initialColor.green * 255f).toInt(),
            (initialColor.blue * 255f).toInt(),
            hsv
        )
        hsv
    }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var saturation by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2]) }
    // Deliberately a separate piece of state from hue/saturation/value, not derived from them on
    // every recomposition - it needs to hold exactly what the user is typing (including
    // momentarily-invalid, in-progress input like "3F1") without being clobbered mid-keystroke.
    // It's only ever pushed *from* hue/saturation/value (when the square or slider changes them);
    // the text field's own onValueChange pushes the other way, into hue/saturation/value, once
    // the typed text forms a complete valid hex color - never back into itself.
    var hexText by remember { mutableStateOf(colorHex(Color.hsv(hue, saturation, value))) }
    val currentColor = Color.hsv(hue, saturation, value)

    fun updateFromHsv(newHue: Float, newSaturation: Float, newValue: Float) {
        hue = newHue
        saturation = newSaturation
        value = newValue
        hexText = colorHex(Color.hsv(newHue, newSaturation, newValue))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a color") },
        text = {
            // Scrollable: AlertDialog bounds its text slot without scrolling it, and this content
            // (180dp saturation square + hue slider + hex field + reset row) already overflows on a
            // small phone at a large font scale, clipping the bottom controls.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SaturationValueSquare(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onSaturationValueChange = { s, v -> updateFromHsv(hue, s, v) }
                )
                HueSlider(hue = hue, onHueChange = { h -> updateFromHsv(h, saturation, value) })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { typed ->
                            val cleaned = typed.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                                .uppercase()
                                .take(6)
                            hexText = cleaned
                            if (Regex("^[0-9A-F]{6}$").matches(cleaned)) {
                                val rgb = cleaned.toInt(16)
                                val hsv = FloatArray(3)
                                android.graphics.Color.RGBToHSV((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, hsv)
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2]
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        modifier = Modifier.width(140.dp)
                    )
                }
                // Its own row rather than sharing the confirm/dismiss button row below: three text
                // buttons ("Reset to Default" / "Cancel" / "Save") crowded into that one row could
                // overflow or wrap unpredictably on a narrow phone or a larger system font-scale
                // setting, since AlertDialog's action row isn't scrollable. Keeping that row to the
                // conventional two buttons (which the framework already sizes/wraps correctly) and
                // folding this one into the scrollable content column avoids the crowding entirely.
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onReset) { Text("Reset to Default") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun colorHex(color: Color): String = "%06X".format(color.toArgb() and 0xFFFFFF)

@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit
) {
    val hueColor = Color.hsv(hue, 1f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(hueColor)
            // White (full desaturation) fading out left-to-right reveals the pure hue on the
            // right; black fading in top-to-bottom darkens toward the bottom. Stacked
            // `.background()` calls draw in order, later ones on top - the standard Compose
            // technique for layering gradient overlays.
            .background(Brush.horizontalGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black)))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSaturationValueChange(
                        (down.position.x / size.width.toFloat()).coerceIn(0f, 1f),
                        1f - (down.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                    )
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        onSaturationValueChange(
                            (change.position.x / size.width.toFloat()).coerceIn(0f, 1f),
                            1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        )
                        change.consume()
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val indicator = Offset(saturation * size.width, (1f - value) * size.height)
            drawCircle(color = Color.White, radius = 9.dp.toPx(), center = indicator, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.Black, radius = 7.dp.toPx(), center = indicator, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
private fun HueSlider(hue: Float, onHueChange: (Float) -> Unit) {
    // Stops at exactly the 60-degree hue keyframes (red/yellow/green/cyan/blue/magenta/red) -
    // linear RGB interpolation between adjacent stops here exactly reproduces Color.hsv(hue,1,1)
    // for every hue in between, since each RGB channel is piecewise-linear across a hue segment.
    val rainbow = remember {
        Brush.horizontalGradient(
            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(rainbow)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onHueChange((down.position.x / size.width.toFloat() * 360f).coerceIn(0f, 360f))
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        onHueChange((change.position.x / size.width.toFloat() * 360f).coerceIn(0f, 360f))
                        change.consume()
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = (hue / 360f) * size.width
            drawLine(color = Color.White, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 3.dp.toPx())
            drawLine(color = Color.Black, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }
}
