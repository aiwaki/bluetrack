package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.TouchpadSurfaceMode
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Touchpad ⇄ Mouse Mirror surface toggle with a 3-D card flip.
 *
 * Replaces the earlier MOUSE/GAMEPAD toggle. Gamepad now lives on
 * its own fullscreen flip route (`GamepadShortcut` + `gamepadActive`)
 * so this card is dedicated to the two pointer-class surfaces:
 *
 *  - `TOUCHPAD` — on-screen virtual trackpad (finger drives cursor).
 *  - `MOUSE`    — passthrough surface that waits for a real
 *                  USB-OTG or Bluetooth mouse connected to the
 *                  phone, captures its pointer, and forwards every
 *                  motion / scroll / button event as HID reports.
 *
 * Animation matches the original: `graphicsLayer { rotationY }`
 * sweep, 380 ms, camera-distance trick for a real card spin.
 */
@Composable
fun ModeToggle(
    surfaceMode: TouchpadSurfaceMode,
    onToggle: (TouchpadSurfaceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val target = if (surfaceMode == TouchpadSurfaceMode.MOUSE) 180f else 0f
    val rotation by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = BluetrackTokens.SETTLE_DURATION_MS),
        label = "surface-toggle-rotation",
    )
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    // Press-scale separate from the flip rotation. Card dips 0.97
    // on touch then springs back even when the press releases mid
    // flip animation. Lets the user feel the tap before the flip
    // has actually finished swapping faces.
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessMedium)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            )
        },
        label = "mode-toggle-press",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .scale(pressScale)
            .clip(shape)
            .pointerInput(surfaceMode) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) {
                            onToggle(
                                if (surfaceMode == TouchpadSurfaceMode.MOUSE) {
                                    TouchpadSurfaceMode.TOUCHPAD
                                } else {
                                    TouchpadSurfaceMode.MOUSE
                                },
                            )
                        }
                    },
                )
            }.graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            },
    ) {
        if (rotation <= 90f) {
            ModeFace(
                title = "Touchpad",
                subtitle = "Finger pointer · 2-finger scroll",
                accent = palette.mintBright,
                background = palette.mintGlowSoft,
                glyph = "⊟",
                shape = shape,
            )
        } else {
            // Pre-flip the back face by 180° so when rotationY
            // passes 90° it reads upright instead of mirrored.
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                ModeFace(
                    title = "Mouse",
                    subtitle = "Mirror a real USB / BT mouse",
                    accent = palette.cool,
                    background = palette.cool.copy(alpha = 0.14f),
                    glyph = "◯",
                    shape = shape,
                )
            }
        }
    }
}

@Composable
private fun ModeFace(
    title: String,
    subtitle: String,
    accent: Color,
    background: Color,
    glyph: String,
    shape: RoundedCornerShape,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .background(background)
            .border(1.dp, palette.hairline, shape)
            .padding(horizontal = BluetrackTokens.Sp5, vertical = BluetrackTokens.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp4),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = glyph, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = palette.fg0,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = palette.fg2,
                fontSize = 11.sp,
            )
        }
        Text(
            text = "TAP TO FLIP",
            color = accent,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
        )
    }
}
