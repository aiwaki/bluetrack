package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
 * with a spike every ~36 frames so the surface looks "alive"
 * while the HID link is up.
 *
 * The earlier revision drove the spike with a fixed-rate
 * `infiniteRepeatable` regardless of actual HID activity, so the
 * trace looked the same whether the user was moving the cursor
 * at 200 reports/s or sitting idle on a paired-but-quiet link.
 * That read as fake. We now scale spike rate and amplitude by
 * [intensity] (0f..1f) — 0 collapses to the calm baseline, 1
 * gives the canvas spike cadence. The host activity intensity
 * is fed from the Hub composable (`isInputLive` window).
 *
 * `active = false` collapses the trace to a flat dashed hairline
 * (canvas "host disconnected" state) so the strip never
 * animates when there is nothing to communicate.
 */
@Composable
fun Heartbeat(
    active: Boolean,
    modifier: Modifier = Modifier,
    intensity: Float = 0f,
) {
    val palette = BluetrackTheme.palette
    val rawIntensity = intensity.coerceIn(0f, 1f)
    // Smooth intensity transitions so a sudden burst of HID
    // activity ramps amplitude up over 320ms instead of
    // snapping. Same on the way back down — the spike calms
    // gently rather than collapsing the instant activity
    // stops. Reads as live rather than mechanically clamped.
    val clampedIntensity by animateFloatAsState(
        targetValue = rawIntensity,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "heartbeat-intensity",
    )
    // Active state fade. `active=false` collapses the trace
    // to a flat dashed hairline; tween the trace alpha 0..1
    // so the spike polyline melts away on disconnect instead
    // of vanishing in a single frame.
    val traceAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "heartbeat-trace-alpha",
    )
    val transition = rememberInfiniteTransition(label = "heartbeat")
    // Period shortens with intensity. Calm idle ~3.6 s, full
    // activity ~1.4 s — the eye reads that as "fast pulse".
    val periodMs = (3_600 - 2_200 * clampedIntensity).toInt().coerceAtLeast(900)
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
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
        // Hairline base. Solid when the trace is fully alive,
        // dashed when collapsed — interpolation between is
        // unnecessary, the eye reads the dash style instantly.
        drawLine(
            color = palette.hairline,
            start = Offset(0f, h - 0.5f),
            end = Offset(w, h - 0.5f),
            strokeWidth = 1f,
            pathEffect = if (traceAlpha > 0.05f) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
        )
        // Skip the polyline build once the fade-out finishes.
        // While active, draw on every frame so the running
        // spike continues to advance.
        if (traceAlpha <= 0.001f) return@Canvas

        // Build the heartbeat polyline. Mirrors the canvas
        // sampling loop: noise sine baseline + a spike every
        // ~72 step units, where one "step" is 4 px in this
        // coordinate space.
        val stepPx = 4f
        val drift = phase * 72f
        val path = Path()
        var x = 0f
        var first = true
        // Spike height + baseline jitter both scale with intensity
        // so a quiet link reads as a calm hairline and a busy one
        // reads as a tall, rapid pulse.
        val spikeUp = 7f * clampedIntensity
        val spikeDown = 3f * clampedIntensity
        val sineAmp = 0.6f * (0.4f + 0.6f * clampedIntensity)
        while (x <= w) {
            val v = (x + drift) / 12f
            var y = baseY + (sin(v.toDouble()) * sineAmp).toFloat()
            val spikeAt = ((x + drift) % 72f)
            if (spikeAt < 8f) {
                val s = sin((spikeAt / 8f) * PI).toFloat()
                y -= s * spikeUp
            } else if (spikeAt < 14f) {
                val s = sin(((spikeAt - 8f) / 6f) * PI).toFloat()
                y += s * spikeDown
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
            color = palette.crit.copy(alpha = 0.30f * traceAlpha),
            style = Stroke(width = 4f),
        )
        drawPath(
            path = path,
            color = palette.crit.copy(alpha = traceAlpha),
            style = Stroke(width = 1.4f),
        )
    }
}
