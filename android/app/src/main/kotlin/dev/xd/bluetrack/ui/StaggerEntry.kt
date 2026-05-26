package dev.xd.bluetrack.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cascading fade + lift entry animation used by Diagnostics
 * and Activity route cards. Each card calls this with a
 * unique `index`; the effect launches once per composition
 * lifetime with a delay of `index * 60ms`, then tweens
 * `alpha 0 → 1` and `translationY (offsetDp) → 0` over
 * 320ms with `FastOutSlowInEasing`.
 *
 * Reads as a single "settling in" sweep down the route on
 * first open — looks deliberate, not animation-fatigued.
 * Subsequent updates (live numeric tweens, sparkline refresh)
 * hit the steady-state alpha=1 / translationY=0 surface and
 * stay unaffected.
 */
@Composable
fun rememberStaggerModifier(
    index: Int,
    offsetDp: Dp = 12.dp,
    perItemDelayMs: Long = 60L,
    durationMs: Int = 320,
): Modifier {
    val alphaAnim = remember { Animatable(0f) }
    val translateAnim = remember { Animatable(offsetDp.value) }
    LaunchedEffect(Unit) {
        delay(index * perItemDelayMs)
        coroutineScope {
            launch {
                alphaAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                )
            }
            launch {
                translateAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                )
            }
        }
    }
    return Modifier
        .alpha(alphaAnim.value)
        .graphicsLayer { translationY = translateAnim.value }
}
