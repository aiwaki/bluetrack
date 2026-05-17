package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens
import kotlin.math.PI
import kotlin.math.sin

/**
 * Mint heartbeat trace beneath the Hub. Mirrors canvas
 * `Heartbeat` (`docs/design/v1/hub.jsx`): a faint sine baseline
 * with a spike every ~36 frames so the surface always looks
 * "alive" while the HID link is up.
 *
 * Implemented as a [Canvas] that samples the same polyline shape
 * the canvas builds in JS, driven by an [infiniteRepeatable]
 * `phase` (0..1 over [BluetrackTokens.AURORA_DURATION_MS] / 8) so
 * the trace drifts left at a steady rate without per-frame
 * `requestAnimationFrame` churn.
 *
 * `active = false` collapses the trace to a flat hairline (canvas
 * "host disconnected" state) so the strip never animates when
 * there is nothing to communicate.
 */
@Composable
fun Heartbeat(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val transition = rememberInfiniteTransition(label = "heartbeat")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heartbeatPhase",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = BluetrackTokens.Sp6, vertical = 4.dp),
    ) {
        val w = size.width
        val h = size.height
        val baseY = h * 0.5f
        // Hairline base.
        drawLine(
            color = palette.hairline,
            start = Offset(0f, h - 0.5f),
            end = Offset(w, h - 0.5f),
            strokeWidth = 1f,
            pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
        )
        if (!active) return@Canvas

        // Build the heartbeat polyline. Mirrors the canvas
        // sampling loop: noise sine baseline + a spike every
        // ~72 step units, where one "step" is 4 px in this
        // coordinate space.
        val stepPx = 4f
        val drift = phase * 72f
        val path = Path()
        var x = 0f
        var first = true
        while (x <= w) {
            val v = (x + drift) / 12f
            var y = baseY + (sin(v.toDouble()) * 0.6).toFloat()
            val spikeAt = ((x + drift) % 72f)
            if (spikeAt < 8f) {
                val s = sin((spikeAt / 8f) * PI).toFloat()
                y -= s * 7f
            } else if (spikeAt < 14f) {
                val s = sin(((spikeAt - 8f) / 6f) * PI).toFloat()
                y += s * 3f
            }
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += stepPx
        }
        // Mint stroke with a softer underglow drawn underneath so
        // the line still reads on the dark surface.
        drawPath(
            path = path,
            color = palette.mintGlow.copy(alpha = palette.mintGlow.alpha * 0.55f),
            style = Stroke(width = 4f),
        )
        drawPath(
            path = path,
            color = Color(palette.mintBright.value),
            style = Stroke(width = 1.4f),
        )
    }
}
