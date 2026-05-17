package dev.xd.bluetrack.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Bluetrack neon glow stack. Mirrors `--mint-glow` /
 * `--mint-glow-soft` text-shadow chain in `tokens.css`: a tight
 * inner halo, a wider soft glow, and a very wide soft glow. Drawn
 * BEHIND the composable, so use it on text/icons that already paint
 * themselves in `palette.mintBright`.
 *
 * Strength scales linearly with `--neonStrength` from the Tweaks
 * panel; `1.0` matches the canvas default.
 *
 * Step 1 of the UI port keeps this minimal: callers paint their own
 * colour; the modifier only contributes the halo. Later steps add a
 * dedicated `Modifier.neonText` that wraps text + glow + inset
 * stroke as a single thing.
 */
@Composable
fun Modifier.glow(
    color: Color,
    strength: Float = 1f,
    innerRadius: Dp = 6.dp,
    midRadius: Dp = 16.dp,
    outerRadius: Dp = 28.dp,
): Modifier = composed {
    val clamped = remember(strength) { strength.coerceIn(0f, 2.5f) }
    val inner = innerRadius * clamped
    val mid = midRadius * clamped
    val outer = outerRadius * clamped
    drawBehind {
        drawHalo(color = color, radiusPx = outer.toPx(), alphaScale = 0.18f)
        drawHalo(color = color, radiusPx = mid.toPx(), alphaScale = 0.32f)
        drawHalo(color = color, radiusPx = inner.toPx(), alphaScale = 0.55f)
    }
}

/**
 * Convenience overload: glow with the current palette's `mintGlow`
 * stack. Reads through the theme so accent changes from the Tweaks
 * panel update without recomposition gymnastics.
 */
@Composable
fun Modifier.bluetrackGlow(strength: Float = 1f): Modifier =
    glow(color = BluetrackTheme.palette.mint, strength = strength)

/**
 * Reserve symmetric padding around a composable that participates in
 * the glow stack. Without this the halo gets clipped by the parent
 * layout because the halo lives in the draw layer but the layout
 * box does not extend to accommodate it. Use on glow targets that
 * sit inside `Row` / `Column` with strict bounds.
 */
fun Modifier.glowPadding(extra: Dp = 8.dp): Modifier = padding(extra)

private fun DrawScope.drawHalo(color: Color, radiusPx: Float, alphaScale: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseExtent = max(size.width, size.height) / 2f
    val rx = baseExtent + radiusPx
    val ry = baseExtent + radiusPx
    drawOval(
        color = color.copy(alpha = (color.alpha * alphaScale).coerceIn(0f, 1f)),
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f),
    )
}
