package dev.xd.bluetrack.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Five top-level destinations in the redesigned Bluetrack shell.
 * Order matches the canvas dock left-to-right.
 *
 * Welcome / Permissions / Pair are first-run flows that the shell
 * surfaces above the dock layer when their preconditions fail; they
 * do not get their own dock entries.
 */
enum class Route(
    val label: String,
    val icon: ImageVector,
) {
    Hub("Hub", Icons.Outlined.Sensors),
    Hosts("Hosts", Icons.Outlined.Devices),
    Activity("Activity", Icons.Outlined.Timeline),
    Diagnostics("Diag", Icons.Outlined.Insights),
    Settings("Settings", Icons.Outlined.Settings),
}

/**
 * Tiny in-memory router. The shell drives the active destination via
 * [current]; the dock listens through [onSelect]. State only — no
 * back stack, no nav graph; Compose Navigation is deliberately not
 * pulled in for this stage of the port to keep the diff small.
 */
class RouterState(
    initial: Route = Route.Hub,
) {
    var current: Route by mutableStateOf(initial)
        private set

    fun navigate(to: Route) {
        current = to
    }
}

@Composable
fun rememberRouter(initial: Route = Route.Hub): RouterState =
    remember { RouterState(initial) }

/** Icon size pinned for the dock + any route headers that reuse the glyph. */
@Composable
internal fun RouteIcon(route: Route, contentDescription: String? = route.label) {
    Icon(imageVector = route.icon, contentDescription = contentDescription)
}
