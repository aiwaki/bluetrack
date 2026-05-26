package dev.xd.bluetrack.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * 4-way D-pad. Canvas value 0..7 hat encoding maps to the
 * standard composite gamepad report's hat-switch byte (Up = 0,
 * Right = 2, Down = 4, Left = 6, neutral = 8).
 *
 * Each direction is a 32 dp pill positioned 22 dp from centre.
 * Press = mint gradient + 18 dp mint glow + filled arrow glyph.
 * Release = dark vertical gradient + outlined arrow.
 *
 * The composable reports the active direction via [onHat] using
 * the hat-switch convention so the host-side report builder
 * (`TranslationEngine`) can drop the value straight into the
 * report byte without remapping.
 */
@Composable
fun DPad(
    onHat: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var active by remember { mutableStateOf<Int?>(null) }

    // DualSense-style cross: each arm is a tall pill oriented
    // along its own axis so adjacent arrows share parallel
    // edges (not corners). Up / Down are 28 dp wide x 40 dp
    // tall, Left / Right swap dimensions. With centres offset
    // 30 dp from origin, edges just touch — no overlap, all
    // four reading as one continuous cross.
    data class Arm(
        val hat: Int,
        val dx: Int,
        val dy: Int,
        val glyph: String,
        val vertical: Boolean,
    )
    val dirs = listOf(
        Arm(hat = 0, dx = 0, dy = -1, glyph = "↑", vertical = true),
        Arm(hat = 2, dx = 1, dy = 0, glyph = "→", vertical = false),
        Arm(hat = 4, dx = 0, dy = 1, glyph = "↓", vertical = true),
        Arm(hat = 6, dx = -1, dy = 0, glyph = "←", vertical = false),
    )
    Box(
        modifier = modifier.size(112.dp),
    ) {
        dirs.forEach { arm ->
            val isActive = active == arm.hat
            // Press feedback spring — same idiom as FaceButtons.
            // Inner visual snaps to 0.9 on press, springs back
            // to 1.0 with a LowBouncy / StiffnessLow profile so
            // the rebound reads as a tactile cap rather than a
            // hard snap.
            val pressScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isActive) 0.9f else 1f,
                animationSpec = if (isActive) {
                    androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    )
                } else {
                    androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                    )
                },
                label = "dpad-press-${arm.hat}",
            )
            // Outer hit zone is 4 dp wider/taller on each side than
            // the visual pill so taps just outside the arm still
            // register. Stops short of the perpendicular arm's
            // hit zone because adjacent arms have centres 30 dp
            // apart and visual half-thickness only 14 dp.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        width = if (arm.vertical) 40.dp else 54.dp,
                        height = if (arm.vertical) 54.dp else 40.dp,
                    ).offset(x = (arm.dx * 30).dp, y = (arm.dy * 30).dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                active = arm.hat
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                )
                                onHat(arm.hat)
                                val released = tryAwaitRelease()
                                active = null
                                onHat(8)
                                @Suppress("UNUSED_EXPRESSION")
                                released
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            width = if (arm.vertical) 28.dp else 40.dp,
                            height = if (arm.vertical) 40.dp else 28.dp,
                        ).scale(pressScale)
                        .clip(RoundedCornerShape(BluetrackTokens.RadiusXs))
                        .background(
                            if (isActive) {
                                Brush.verticalGradient(
                                    colors = listOf(palette.crit, palette.crit.copy(alpha = 0.7f)),
                                )
                            } else {
                                // Theme-aware idle fill — earlier hard
                                // grey 0xFF2A2C2E / 0xFF15171A made the
                                // arms invisible on the light palette.
                                Brush.verticalGradient(
                                    colors = listOf(palette.bg2, palette.bg3),
                                )
                            },
                        ).border(
                            1.dp,
                            if (isActive) Color.Transparent else palette.glassBorder,
                            RoundedCornerShape(BluetrackTokens.RadiusXs),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = arm.glyph,
                        color = if (isActive) Color.White else palette.fg1,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
