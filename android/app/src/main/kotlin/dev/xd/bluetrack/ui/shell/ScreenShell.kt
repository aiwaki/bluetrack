package dev.xd.bluetrack.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.RouterState
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Root shell every Bluetrack screen lives inside.
 *
 *   ┌────────────────────────────────────────┐
 *   │ aurora layer (Canvas)                  │
 *   │ ┌────────────────────────────────────┐ │
 *   │ │ scrollable content (Hub, Hosts, …) │ │
 *   │ └────────────────────────────────────┘ │
 *   │ ┌────────────────────────────────────┐ │
 *   │ │ bottom dock (glass)                │ │
 *   │ └────────────────────────────────────┘ │
 *   └────────────────────────────────────────┘
 *
 * Step 2 of the UI port — wires the persistent chrome around the
 * existing Hub content so future port PRs (Hub move, hero
 * components, route-specific screens) drop straight into a slot.
 *
 * The shell honours [motionReduced] / [glassEnabled] which will
 * eventually be driven by the Tweaks panel. For now both default
 * to the canvas defaults (`full` / `on`).
 */
@Composable
fun ScreenShell(
    router: RouterState,
    modifier: Modifier = Modifier,
    motionReduced: Boolean = false,
    glassEnabled: Boolean = true,
    neonStrength: Float = 1f,
    auroraState: AuroraState = AuroraState.Calm,
    darkTheme: Boolean = true,
    content: @Composable (Route) -> Unit,
) {
    val palette = BluetrackTheme.palette
    // Slow deep-red radial pulse drawn behind everything — same
    // idiom as the gamepad surface so the main shell shares the
    // ambient warmth. Lower max alpha keeps it from competing
    // with content. Period 8 s reads as ambient, not active.
    val pulse = rememberInfiniteTransition(label = "shell-bg-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shell-bg-pulse-alpha",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg0)
            .drawBehind {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = (size.width.coerceAtLeast(size.height)) * 0.7f
                val deep = Color(0xFF8B0000)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            deep.copy(alpha = pulseAlpha),
                            deep.copy(alpha = pulseAlpha * 0.4f),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                )
            },
    ) {
        AuroraBackground(
            modifier = Modifier.fillMaxSize(),
            state = auroraState,
            darkTheme = darkTheme,
            motionReduced = motionReduced,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = DOCK_RESERVED_HEIGHT_DP.dp),
        ) {
            // Route transitions. Direction follows the dock's
            // left-to-right enum order so navigating from Hub →
            // Settings slides in from the right, and Settings →
            // Hub slides in from the left. Reads as native
            // tab-bar motion without pulling in
            // Compose Navigation. Disabled when `motionReduced`
            // is on — accessibility users get an instant swap.
            AnimatedContent(
                targetState = router.current,
                transitionSpec = {
                    if (motionReduced) {
                        fadeIn(animationSpec = tween(0)) togetherWith
                            fadeOut(animationSpec = tween(0))
                    } else {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        val durMs = 260
                        slideInHorizontally(
                            animationSpec = tween(durMs, easing = FastOutSlowInEasing),
                            initialOffsetX = { full -> direction * full / 6 },
                        ) + fadeIn(animationSpec = tween(durMs)) togetherWith
                            slideOutHorizontally(
                                animationSpec = tween(durMs, easing = FastOutSlowInEasing),
                                targetOffsetX = { full -> -direction * full / 6 },
                            ) + fadeOut(animationSpec = tween(durMs))
                    }
                },
                label = "route-transition",
            ) { route ->
                content(route)
            }
        }
        BluetrackDock(
            current = router.current,
            onSelect = router::navigate,
            neonStrength = neonStrength,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight()
                .windowInsetsPadding(WindowInsets.navigationBars)
                // Tiny lift off the gesture bar — earlier 18 dp
                // floated the icons too high; 6 dp matches the
                // breathing room above other route content.
                .padding(bottom = 6.dp),
        )
    }
}

/**
 * Placeholder body for routes the port has not landed yet. Keeps
 * the dock + aurora intact so dock interaction can be tested before
 * the matching screen ports start.
 */
@Composable
fun ComingSoonScreen(label: String) {
    val palette = BluetrackTheme.palette
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                color = palette.fg0,
            )
            Text(
                text = "Coming soon",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = palette.fg2,
            )
        }
    }
}

/**
 * Reserve enough room below the scrollable content so the dock does
 * not overlap the last row. Chosen to match the canvas dock height
 * (~ 60 dp) plus a 12 dp safety margin so spring overshoot doesn't
 * clip into content.
 */
// Tall enough that scrolled content always stops a clear gap ABOVE the
// floating nav pill (capsule ≈ 60 dp + lift) — the bar hangs in the air
// over the background, content never slides behind it.
private const val DOCK_RESERVED_HEIGHT_DP: Int = 84
