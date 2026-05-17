package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * One-shot neon ribbon flash that paints across the top of the
 * screen when a fresh handshake lands (canvas `bt-ribbon`).
 *
 * The canvas version is a CSS sweep animation triggered by a key
 * change. In Compose we mirror that with an [Animatable] alpha
 * that fades from 1 → 0 over ~700 ms, keyed off [trigger]. Each new
 * value of `trigger` (e.g. a fresh `session.id`) restarts the
 * animation. Pass `0` (or a stable value) to leave the ribbon dim.
 *
 * Kept intentionally tiny (3 dp) so it reads as a brand flash, not
 * a UI bar.
 */
@Composable
fun NeonRibbon(
    trigger: Any?,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 720),
        )
    }
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.mintGlow.copy(alpha = palette.mintGlow.alpha * alpha.value),
                        palette.mintBright.copy(alpha = alpha.value),
                        palette.mintGlow.copy(alpha = palette.mintGlow.alpha * alpha.value),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}
