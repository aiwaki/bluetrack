package dev.xd.bluetrack.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Bottom dock — redesigned 2026-05-23 to match the v2.4 design
 * reference. Tighter pill, icons only, active slot is a filled
 * red circle behind the icon instead of an underline bar. Drops
 * the labels entirely — the active route's name lives in the
 * route header (`HubHeader`) so the dock can stay quiet.
 *
 * Activity is intentionally absent from the dock — the route is
 * still reachable via the Hub `ActivityStrip` "Open" affordance
 * and `ActivityScreen` renders a leading `‹` back arrow.
 */
@Composable
fun BluetrackDock(
    current: Route,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") neonStrength: Float = 1f,
) {
    val palette = BluetrackTheme.palette
    val routes = Route.entries.filter { it != Route.Activity }

    // Dock floats on the aurora background — no glass surface,
    // no border, just the slot row. User read the previous
    // `btGlass(strong=true)` panel as "black bar at the bottom"
    // rather than the intended translucent shelf. Going fully
    // transparent matches the design reference where the icon
    // row reads as anchored UI without a chrome rail.
    // Outer wrapper is now a Row directly. Earlier we wrapped
    // it in a Box with horizontal padding, which combined with
    // `SpaceEvenly` placed extra slack at the edges and visibly
    // shifted the icon cluster off centre. Row fills the dock's
    // assigned width and `SpaceAround` equalises the gaps so the
    // four icons sit symmetric on both axes.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        routes.forEach { route ->
            DockSlot(
                route = route,
                active = route == current,
                activeColor = palette.crit,
                inactiveTint = palette.fg2,
                onClick = { onSelect(route) },
            )
        }
    }
}

@Composable
private fun DockSlot(
    route: Route,
    active: Boolean,
    activeColor: Color,
    inactiveTint: Color,
    onClick: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Animated active indicator. Background colour crossfades
    // between transparent ↔ crit red; icon tint crossfades fg2 ↔
    // white; circle scale springs slightly above 1.0 when the
    // route becomes active and back to 1.0 — gives the dock a
    // tactile "settle" instead of an instant swap. Springs use
    // medium-low stiffness so the motion reads as deliberate, not
    // bouncy.
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )
    val bgColor by animateColorAsState(
        targetValue = if (active) activeColor else Color.Transparent,
        animationSpec = tweenColor(220),
        label = "dock-slot-bg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (active) Color.White else inactiveTint,
        animationSpec = tweenColor(220),
        label = "dock-slot-tint",
    )
    val slotScale by animateFloatAsState(
        targetValue = if (active) 1.06f else 1f,
        animationSpec = springSpec,
        label = "dock-slot-scale",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .scale(slotScale)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                )
                onClick()
            }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = route.label,
            modifier = Modifier.size(18.dp),
            tint = iconTint,
        )
    }
}

private fun tweenColor(durMs: Int) =
    androidx.compose.animation.core
        .tween<Color>(durationMillis = durMs)
