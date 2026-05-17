package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Mouse ⇄ Gamepad mode toggle with a 3-D card flip.
 *
 * Canvas equivalent: the 2-card row in `Hub` that selects the
 * HID mode. The flip is a `graphicsLayer { rotationY }`
 * animation across 380 ms — half the duration shows the front
 * face (Mouse), the other half shows the back (Gamepad) via a
 * camera-distance trick so the perspective reads as a real card
 * spin rather than a 2-D crossfade.
 *
 * Tapping anywhere on the card swaps the mode through the
 * provided [onToggle] callback (calls into
 * `MainViewModel.toggle(...)`).
 */
@Composable
fun ModeToggle(
    mode: HidMode,
    onToggle: (HidMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val target = if (mode == HidMode.GAMEPAD) 180f else 0f
    val rotation by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = BluetrackTokens.SETTLE_DURATION_MS),
        label = "mode-toggle-rotation",
    )
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(shape)
            .clickable {
                onToggle(if (mode == HidMode.GAMEPAD) HidMode.MOUSE else HidMode.GAMEPAD)
            }.graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            },
    ) {
        if (rotation <= 90f) {
            ModeFace(
                title = "Mouse",
                subtitle = "Pointer · clicks · scroll",
                accent = palette.mintBright,
                background = palette.mintGlowSoft,
                glyph = "↗",
                shape = shape,
            )
        } else {
            // Pre-flip the back face by 180° so when rotationY
            // passes 90° it reads upright instead of mirrored.
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                ModeFace(
                    title = "Gamepad",
                    subtitle = "Sticks · D-pad · 16 buttons",
                    accent = palette.cool,
                    background = palette.cool.copy(alpha = 0.14f),
                    glyph = "▦",
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
