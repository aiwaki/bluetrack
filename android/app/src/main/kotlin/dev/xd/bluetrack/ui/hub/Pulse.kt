package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Mint pulsing dot — canvas `Pulse` atom.
 *
 * Canvas animation: 2 s ease-in-out `btPulse` that scales the halo
 * box-shadow up and down. Compose has no nested CSS box-shadow halo,
 * so we draw the core dot + an animated halo circle behind it via
 * [Canvas]. Opacity + radius animate together so the eye reads a
 * single breathing pulse instead of two layers.
 *
 * Picks the mint glow from [BluetrackTheme.palette] so dark/light
 * themes match the canvas.
 */
@Composable
fun Pulse(
    size: Dp = 10.dp,
    color: Color? = null,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val core = color ?: palette.mint
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulsePhase",
    )
    Canvas(modifier = modifier.size(size * 2f)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val coreR = this.size.width / 4f
        val haloR = coreR + coreR * (1f + phase)
        // Soft halo ring — fades and grows.
        drawCircle(
            color = palette.mintGlow.copy(alpha = palette.mintGlow.alpha * (1f - phase) * 0.55f),
            radius = haloR,
            center = center,
        )
        drawCircle(
            color = palette.mintGlowSoft.copy(alpha = palette.mintGlowSoft.alpha * (1f - phase * 0.5f)),
            radius = coreR * 1.55f,
            center = center,
        )
        drawCircle(color = core, radius = coreR, center = center)
    }
}
