package dev.xd.bluetrack.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Xbox-style face button cluster (Y / X / B / A).
 *
 * Canvas colour split (matches every host on the planet):
 *  - Y → `#ffd23f` (yellow, top)
 *  - X → `#3fb6ff` (blue, left)
 *  - B → `#ff4060` (red, right)
 *  - A → `#3fff80` (green, bottom)
 *
 * Press = saturated radial glow + 20 dp outer halo in the button's
 * accent. Release = subtle inset highlight + outlined letter.
 *
 * [onChange] reports the currently-active button label, or null
 * when nothing is pressed. The caller maps to the composite
 * report's button bitfield (Y / X / B / A live at bits 3 / 2 / 1 /
 * 0 of the high byte).
 */
@Composable
fun FaceButtons(
    onChange: (label: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    var active by remember { mutableStateOf<String?>(null) }
    val buttons = listOf(
        FaceButton(label = "Y", accent = Color(0xFFFFD23F), dx = 0, dy = -22),
        FaceButton(label = "X", accent = Color(0xFF3FB6FF), dx = -22, dy = 0),
        FaceButton(label = "B", accent = Color(0xFFFF4060), dx = 22, dy = 0),
        FaceButton(label = "A", accent = Color(0xFF3FFF80), dx = 0, dy = 22),
    )
    Box(modifier = modifier.size(96.dp)) {
        buttons.forEach { btn ->
            val pressed = active == btn.label
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = btn.dx.dp, y = btn.dy.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (pressed) {
                            Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to Color.White,
                                    0.6f to btn.accent,
                                    1f to Color.Black,
                                ),
                            )
                        } else {
                            Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to Color.White.copy(alpha = 0.18f),
                                    0.7f to Color.Black.copy(alpha = 0.35f),
                                ),
                            )
                        },
                    ).border(
                        if (pressed) 2.dp else 1.5.dp,
                        if (pressed) btn.accent else palette.glassBorder,
                        CircleShape,
                    ).pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                active = btn.label
                                onChange(btn.label)
                                val released = tryAwaitRelease()
                                active = null
                                onChange(null)
                                @Suppress("UNUSED_EXPRESSION")
                                released
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = btn.label,
                    color = if (pressed) Color.White else btn.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

private data class FaceButton(
    val label: String,
    val accent: Color,
    val dx: Int,
    val dy: Int,
)
