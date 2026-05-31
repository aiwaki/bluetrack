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
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Iridescent aurora — vivid radial colour halos that BOTH drift in
 * position and cycle through the hue wheel, so the canvas behind the
 * shell slowly "переливается" like the Gemini app's living gradient
 * instead of sitting on one muted brand tint.
 *
 * - Hue cycle: a single phase sweeps 0 → 1 (= 0 → 360°) every
 *   [AURORA_HUE_CYCLE_MS]; each halo reads the phase plus a fixed
 *   offset, so several spectrum bands are on screen at once and shift
 *   together — the shimmer.
 * - Position drift: halos also translate over [BluetrackTokens
 *   .AURORA_DURATION_MS] (alternating) so the bands move, not just
 *   recolour.
 * - Halos are radial → transparent, so the screen centre stays dark
 *   enough for white text while the corners glow vivid.
 * - `motionReduced` freezes both animations at a static phase;
 *   `glassEnabled = false` removes the aurora entirely so flat
 *   surfaces render like the `data-glass="off"` canvas.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    motionReduced: Boolean = false,
    glassEnabled: Boolean = true,
) {
    if (!glassEnabled) return

    val transition = rememberInfiniteTransition(label = "aurora")
    val driftX by transition.animateFloat(
        initialValue = -0.04f,
        targetValue = 0.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BluetrackTokens.AURORA_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDriftX",
    )
    val driftY by transition.animateFloat(
        initialValue = 0.03f,
        targetValue = -0.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BluetrackTokens.AURORA_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDriftY",
    )
    // Continuous hue sweep 0 → 1 (Restart, not Reverse) so the wash
    // rolls forward through the full spectrum and loops seamlessly.
    val huePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = AURORA_HUE_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraHue",
    )

    val tx = if (motionReduced) 0f else driftX
    val ty = if (motionReduced) 0f else driftY
    val phase = if (motionReduced) STATIC_PHASE else huePhase
    val alpha = if (motionReduced) 0.55f else 0.85f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        HALOS.forEach { halo ->
            val hue = ((phase + halo.hueOffset) % 1f) * 360f
            drawHalo(
                center = Offset(w * (halo.cx + tx), h * (halo.cy + ty)),
                radius = w * halo.r,
                color = Color.hsv(hue, HALO_SATURATION, 1f).copy(alpha = alpha),
            )
        }
    }
}

/**
 * Four halos at fixed anchors, each offset around the hue wheel so the
 * screen always shows a multi-colour spread that drifts and recolours
 * together. Anchors hug the corners + bottom centre, leaving the
 * content column comparatively dark.
 */
private val HALOS = listOf(
    Halo(cx = 0.16f, cy = 0.12f, r = 0.62f, hueOffset = 0.00f),
    Halo(cx = 0.90f, cy = 0.26f, r = 0.60f, hueOffset = 0.30f),
    Halo(cx = 0.50f, cy = 0.94f, r = 0.80f, hueOffset = 0.58f),
    Halo(cx = 0.82f, cy = 0.74f, r = 0.46f, hueOffset = 0.80f),
)

private data class Halo(
    val cx: Float,
    val cy: Float,
    val r: Float,
    val hueOffset: Float,
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHalo(
    center: Offset,
    radius: Float,
    color: Color,
) {
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to color,
                1f to Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        size = Size(size.width, size.height),
        style = Fill,
    )
}

// Full hue sweep period. 12 s reads as a slow, calm shimmer rather
// than a distracting rainbow strobe.
private const val AURORA_HUE_CYCLE_MS = 12_000

// High saturation = vivid, Gemini-style colour; value pinned at 1 so
// the halos glow before the radial fade drops them to transparent.
private const val HALO_SATURATION = 0.85f

// Frozen hue when motion is reduced — a pleasant mid-spectrum spread.
private const val STATIC_PHASE = 0.55f
