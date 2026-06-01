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
 * Iridescent aurora — vivid radial halos that both drift and slowly
 * shift hue, so the canvas behind the shell reads as a living,
 * colour-shimmering surface (reference: the Gemini app's bright
 * shifting wash) rather than the earlier muted single-accent glow.
 *
 * Each halo is a saturated [Color.hsv] blob whose hue = a shared
 * rotating phase + a fixed per-halo offset. Because the offsets span
 * the wheel, several distinct vivid hues are on screen at once and
 * all rotate together — blue → green → violet → pink → back — giving
 * the "переливается" shimmer. Saturated `value = 1` over the dark
 * `bg0` reads as a bright glow; the centre stays comparatively dark
 * (halos sit at the edges / corners + one big bottom bloom) so card
 * text keeps its contrast.
 *
 * - Hue rotates over [HUE_PERIOD_MS] (RepeatMode.Restart — 360°≡0° so
 *   the loop is seamless). Position drifts over `AURORA_DURATION_MS`.
 * - `motionReduced = true` freezes both animations on a calm static
 *   frame at reduced intensity.
 * - `glassEnabled = false` removes the aurora entirely (flat surface).
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
            animation = tween(BluetrackTokens.AURORA_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDriftX",
    )
    val driftY by transition.animateFloat(
        initialValue = 0.03f,
        targetValue = -0.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(BluetrackTokens.AURORA_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDriftY",
    )
    // Shared hue phase, full 360° rotation. Restart (not Reverse) so
    // the colour keeps travelling the same way around the wheel.
    val huePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(HUE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraHue",
    )

    val tx = if (motionReduced) 0f else driftX
    val ty = if (motionReduced) 0f else driftY
    // Frozen calm frame keeps a pleasant cyan/violet mix when motion
    // is reduced; live mode rotates from the moving phase.
    val hue = if (motionReduced) 210f else huePhase
    val intensity = if (motionReduced) 0.6f else 1f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Five vivid blobs. Hue offsets spread across the wheel so the
        // field is multi-colour at any instant; all share the rotating
        // phase so the whole wash shimmers in sync. The big bottom
        // bloom (radius 0.9w) mirrors the reference's strong lower glow.
        drawHalo(Offset(w * (0.16f + tx), h * (0.12f + ty)), w * 0.62f, hue + 0f, 0.40f * intensity)
        drawHalo(Offset(w * (0.88f + tx), h * (0.16f + ty)), w * 0.58f, hue + 80f, 0.38f * intensity)
        drawHalo(Offset(w * (0.50f + tx), h * (0.96f + ty)), w * 0.90f, hue + 165f, 0.44f * intensity)
        drawHalo(Offset(w * (0.08f + tx), h * (0.60f + ty)), w * 0.50f, hue + 250f, 0.34f * intensity)
        drawHalo(Offset(w * (0.90f + tx), h * (0.82f + ty)), w * 0.52f, hue + 315f, 0.36f * intensity)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHalo(
    center: Offset,
    radius: Float,
    hueDeg: Float,
    alpha: Float,
) {
    // Wrap hue into 0..360 and build a vivid, fully-saturated colour.
    val hue = ((hueDeg % 360f) + 360f) % 360f
    val color = Color.hsv(hue, saturation = 0.85f, value = 1f, alpha = alpha.coerceIn(0f, 1f))
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

/** Full hue-wheel rotation period for the iridescent shimmer. */
private const val HUE_PERIOD_MS = 14_000
