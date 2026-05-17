package dev.xd.bluetrack.ui.shell

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.RouterState
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

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
    content: @Composable (Route) -> Unit,
) {
    val palette = BluetrackTheme.palette
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg0),
    ) {
        AuroraBackground(
            modifier = Modifier.fillMaxSize(),
            motionReduced = motionReduced,
            glassEnabled = glassEnabled,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = DOCK_RESERVED_HEIGHT_DP.dp),
        ) {
            content(router.current)
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
                .padding(
                    horizontal = BluetrackTokens.Sp5,
                    vertical = BluetrackTokens.Sp3,
                ),
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
private const val DOCK_RESERVED_HEIGHT_DP: Int = 84
