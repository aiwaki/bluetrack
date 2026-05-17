package dev.xd.bluetrack.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Drifting radial gradients that paint the canvas's `--bt-aurora`
 * behind the screen shell. Three soft halos slowly translate so the
 * dock + cards appear to float on a living surface.
 *
 * Behaviour matches `tokens.css`:
 * - Drift period 18 s (`AURORA_DURATION_MS`), alternates direction.
 * - `motionReduced = true` freezes the animation but keeps the
 *   static halos visible at 50% opacity.
 * - `glassEnabled = false` removes the aurora entirely so flat
 *   surfaces render exactly like the `data-glass="off"` canvas.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    motionReduced: Boolean = false,
    glassEnabled: Boolean = true,
) {
    val palette = BluetrackTheme.palette
    if (!glassEnabled) return

    val transition = rememberInfiniteTransition(label = "aurora")
    val driftX by transition.animateFloat(
        initialValue = -0.04f,
        targetValue = 0.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = BluetrackTokens.AURORA_DURATION_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDriftX",
    )
    val driftY by transition.animateFloat(
        initialValue = 0.03f,
        targetValue = -0.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = BluetrackTokens.AURORA_DURATION_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDriftY",
    )
    val tx = if (motionReduced) 0f else driftX
    val ty = if (motionReduced) 0f else driftY
    val opacity = if (motionReduced) 0.5f else 0.9f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Three radial halos roughly matching the canvas
        // `bt-aurora` recipe: mint top-left, violet top-right,
        // cool blue bottom-centre. Soft mint accent bottom-right
        // brings the dock area back to brand.
        drawHalo(
            center = Offset(w * (0.18f + tx), h * (0.14f + ty)),
            radius = w * 0.55f,
            color = palette.mintGlow,
            opacity = opacity,
        )
        drawHalo(
            center = Offset(w * (0.88f + tx), h * (0.30f + ty)),
            radius = w * 0.55f,
            color = Color(0x52_78_50_FF), // violet 32%
            opacity = opacity,
        )
        drawHalo(
            center = Offset(w * (0.50f + tx), h * (0.92f + ty)),
            radius = w * 0.75f,
            color = Color(0x3800_B4_FF), // cool 22%
            opacity = opacity,
        )
        drawHalo(
            center = Offset(w * (0.80f + tx), h * (0.80f + ty)),
            radius = w * 0.40f,
            color = palette.mintGlowSoft,
            opacity = opacity,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHalo(
    center: Offset,
    radius: Float,
    color: Color,
    opacity: Float,
) {
    val scaled = color.copy(alpha = (color.alpha * opacity).coerceIn(0f, 1f))
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to scaled,
                1f to Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        size = Size(size.width, size.height),
        style = Fill,
    )
}
