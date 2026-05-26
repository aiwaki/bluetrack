package dev.xd.bluetrack.ui.gamepad

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.scale
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
    /**
     * Press / release callback. Reports the *actual* button
     * label on both press (`pressed = true`) and release
     * (`pressed = false`), so the caller never has to guess
     * which face button just went up — required to clear the
     * latched bit in `TranslationEngine`. Codex review on PR
     * #55 caught the earlier `null`-on-release variant that
     * left face buttons stuck.
     */
    onChange: (label: String, pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var active by remember { mutableStateOf<String?>(null) }
    // Container 108 dp + buttons offset 30 dp from centre. With
    // 36 dp circles the previous 22 dp offset gave centre-to-
    // centre distance sqrt(22² + 22²) = 31 dp on adjacent
    // diagonals — less than the 36 dp sum of radii, so adjacent
    // buttons clipped into each other. 30 dp clears the diagonal
    // (centre-to-centre ≈ 42 dp).
    val buttons = listOf(
        FaceButton(label = "Y", accent = Color(0xFFFFD23F), dx = 0, dy = -30),
        FaceButton(label = "X", accent = Color(0xFF3FB6FF), dx = -30, dy = 0),
        FaceButton(label = "B", accent = Color(0xFFFF4060), dx = 30, dy = 0),
        FaceButton(label = "A", accent = Color(0xFF3FFF80), dx = 0, dy = 30),
    )
    Box(modifier = modifier.size(108.dp)) {
        buttons.forEach { btn ->
            val pressed = active == btn.label
            // Press feedback: spring scale 1.0 → 0.88 → 1.0. Down
            // is faster (StiffnessMedium) for a snappy "hit", up
            // settles slower (StiffnessLow + LowBouncy) for a
            // tactile rebound. Reads like a real button cap.
            val pressScale by animateFloatAsState(
                targetValue = if (pressed) 0.88f else 1f,
                animationSpec = if (pressed) {
                    spring(stiffness = Spring.StiffnessMedium)
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow,
                    )
                },
                label = "face-button-press-${btn.label}",
            )
            // Outer hit-area box (42 dp) wraps the visual 36 dp
            // circle so taps just outside the disc still register.
            // 42 dp leaves a few dp gap before the next button's
            // hit zone at the 30 dp offset diagonal distance.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = btn.dx.dp, y = btn.dy.dp)
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                active = btn.label
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                )
                                onChange(btn.label, true)
                                val released = tryAwaitRelease()
                                active = null
                                onChange(btn.label, false)
                                @Suppress("UNUSED_EXPRESSION")
                                released
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .scale(pressScale)
                        .clip(CircleShape)
                        .background(
                            if (pressed) {
                                Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to palette.fg0,
                                        0.6f to btn.accent,
                                        1f to btn.accent.copy(alpha = 0.55f),
                                    ),
                                )
                            } else {
                                // Theme-aware idle fill — earlier
                                // Color.White / Color.Black radial
                                // washed out on the light palette and
                                // the buttons read as dark blobs.
                                Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to palette.bg2,
                                        1f to palette.bg3,
                                    ),
                                )
                            },
                        ).border(
                            if (pressed) 2.dp else 1.5.dp,
                            if (pressed) btn.accent else palette.glassBorder,
                            CircleShape,
                        ),
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
}

private data class FaceButton(
    val label: String,
    val accent: Color,
    val dx: Int,
    val dy: Int,
)
