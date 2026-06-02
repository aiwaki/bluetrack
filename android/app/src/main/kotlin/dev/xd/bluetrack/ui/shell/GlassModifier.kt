package dev.xd.bluetrack.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
        // Specular top sheen — a faint light wash on the top edge that
        // fades out, giving the flat tint a lit "liquid glass" feel.
        .background(
            Brush.verticalGradient(
                listOf(palette.glassSheen, Color.Transparent),
            ),
            shape,
        )
        // Two-tone rim: brighter on top, dimmer at the bottom, so the
        // glass reads as premium frosted even where a drop shadow can't
        // (e.g. dark glass over the dark aura).
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(palette.glassRimTop, palette.glassRimBottom),
            ),
            shape,
        )
}
