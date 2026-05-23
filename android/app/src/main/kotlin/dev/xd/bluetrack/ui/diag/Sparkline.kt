package dev.xd.bluetrack.ui.diag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
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
