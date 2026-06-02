package dev.xd.bluetrack.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

    // Floating glass nav bar: a centred pill that hovers off the screen
    // edges over the aurora. Theme-aware translucent fill (light → soft
    // white, dark → smoked) via `palette.glassBg`, a soft drop shadow,
    // and a hairline edge. Deliberately matte — no Frutiger-Aero gloss.
    // The aurora glows through it.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val pill = RoundedCornerShape(percent = 50)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Soft "defining" drop shadow like the Gemini bar — a
                // large, diffuse elevation that lifts the pill off the
                // background.
                .shadow(
                    elevation = 22.dp,
                    shape = pill,
                    clip = false,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
                .clip(pill)
                // Near-opaque theme surface so the bar reads as a solid
                // floating control, not a translucent wash.
                .background(palette.bg1.copy(alpha = 0.94f))
                .border(1.dp, palette.hairline, pill)
                // Consume every tap that lands on the pill (including the
                // gaps between icons) so nothing falls through to the
                // content scrolling behind the floating bar.
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
}

@Composable
private fun DockSlot(
    route: Route,
    active: Boolean,
    activeColor: Color,
    inactiveTint: Color,
    onClick: () -> Unit,
) {
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
    // Press-scale spring independent of active state. The user's
    // finger gets a visual response on the very slot it touches,
    // not just the new slot that becomes active. 0.9 dip on press,
    // springy release. Multiplies with slotScale so an active slot
    // press still feels tactile (1.06 × 0.9 ≈ 0.95 dip from rest).
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessMedium)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            )
        },
        label = "dock-slot-press",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .scale(slotScale * pressScale)
            .clip(CircleShape)
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) onClick()
                    },
                )
            },
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
