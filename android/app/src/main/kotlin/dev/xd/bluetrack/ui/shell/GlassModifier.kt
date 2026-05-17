package dev.xd.bluetrack.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Apple-glass surface tint + hairline border + clip.
 *
 * Compose has no first-class backdrop blur (the canvas
 * `backdrop-filter: blur(22px) saturate(180%)` cannot be expressed
 * without a screenshot-and-blur trick or a third-party library like
 * Haze). The pragmatic approximation here:
 *
 * 1. Tinted translucent background (canvas `--glass-bg`).
 * 2. 1 dp hairline border (canvas `--glass-border`).
 * 3. Clipped to [shape] so child content inherits the rounded
 *    silhouette.
 *
 * Because the aurora layer underneath uses soft pastel halos, the
 * tint already reads as glass to the eye — the missing blur is more
 * noticeable on photographic backgrounds than on Bluetrack's
 * synthetic surfaces. A `Modifier.graphicsLayer { renderEffect = … }`
 * upgrade is a follow-up once we want a backdrop blur pass on
 * API 31+.
 */
fun Modifier.btGlass(
    strong: Boolean = false,
    shape: Shape = androidx.compose.foundation.shape
        .RoundedCornerShape(BluetrackTokens.RadiusLg),
): Modifier = composed {
    val palette = BluetrackTheme.palette
    val bg = if (strong) palette.glassBgStrong else palette.glassBg
    this
        .clip(shape)
        .background(bg, shape)
        .border(1.dp, palette.glassBorder, shape)
}
