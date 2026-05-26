package dev.xd.bluetrack.ui.diag

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compose port of canvas `Sparkline` (`docs/design/v1/shared.jsx`
 * lines 142-152). Draws a thin polyline + optional filled
 * underlay over the data series, normalised to the widget bounds.
 *
 * The canvas version uses an SVG `viewBox="0 0 100 100"` then
 * scales via `preserveAspectRatio="none"`. We mirror that by
 * normalising x to `[0, width]` and y to `[h * 0.05, h * 0.95]`
 * so the stroke never touches the edges. `fill = true` adds a
 * 15 % alpha polygon under the line.
 */
@Composable
fun Sparkline(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    fill: Boolean = false,
) {
    // First-render fade-in. Sparkline lives inside the Diag
    // route; opening Diag should reveal the chart with a soft
    // 320ms ramp instead of a hard cut to a fully-drawn polyline.
    // Subsequent recompositions reuse the already-1f Animatable
    // so the live wave keeps updating without re-flashing.
    val fadeIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeIn.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        )
    }
    Canvas(modifier = modifier.fillMaxWidth().height(height).alpha(fadeIn.value)) {
        val w = size.width
        val h = size.height
        // Empty + all-zero both render as a faint dashed baseline
        // through the centre — same visual idiom as the canvas
        // `Heartbeat` strip. Earlier the canvas returned blank so
        // the user could not tell whether the widget was alive or
        // genuinely flat-lined; the dashed line removes that
        // ambiguity without implying activity that is not there.
        val empty = data.isEmpty() || (data.maxOrNull() ?: 0f) <= 0f
        if (empty) {
            drawLine(
                color = color.copy(alpha = 0.18f),
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
            return@Canvas
        }
        val max = (data.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val n = data.size
        val path = Path()
        val fillPath = Path()
        data.forEachIndexed { i, v ->
            val x = if (n > 1) (i.toFloat() / (n - 1)) * w else 0f
            val y = h - (v / max) * h * 0.9f - h * 0.05f
            if (i == 0) {
                path.moveTo(x, y)
                if (fill) fillPath.moveTo(0f, h)
            }
            path.lineTo(x, y)
            if (fill) fillPath.lineTo(x, y)
        }
        if (fill) {
            fillPath.lineTo(w, h)
            fillPath.close()
            drawPath(path = fillPath, color = color.copy(alpha = 0.15f))
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5f),
        )
        // Subtle end-cap dot to anchor the eye on the live edge.
        val last = data.last()
        val lastY = h - (last / max) * h * 0.9f - h * 0.05f
        drawCircle(
            color = color,
            radius = 1.8f,
            center = Offset(w - 1f, lastY),
        )
    }
}
