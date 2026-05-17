package dev.xd.bluetrack.ui.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens
import dev.xd.bluetrack.ui.theme.bluetrackGlow

/**
 * Five-destination bottom dock — icons only, glass surface, spring-
 * eased active indicator. Matches the canvas `bt-dock` shape:
 * Hub / Hosts / Activity / Diag / Settings.
 *
 * The dock itself is opaque tint + hairline (see [btGlass]); the
 * aurora layer in [ScreenShell] sits behind it. Each slot is a
 * 44 dp tap target (a11y baseline) with the icon at 24 dp and a
 * 2 dp accent bar that springs to the active slot's centre.
 */
@Composable
fun BluetrackDock(
    current: Route,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
    neonStrength: Float = 1f,
) {
    val palette = BluetrackTheme.palette
    val routes = Route.entries
    val activeIndex = routes.indexOf(current).coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(BluetrackTokens.RadiusLg))
            .btGlass(strong = true, shape = RoundedCornerShape(BluetrackTokens.RadiusLg))
            .padding(horizontal = BluetrackTokens.Sp3, vertical = BluetrackTokens.Sp2),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                routes.forEachIndexed { _, route ->
                    DockSlot(
                        route = route,
                        active = route == current,
                        onClick = { onSelect(route) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Active indicator: thin neon bar under the current slot.
            // Springs between slot centres with the Bluetrack settle.
            ActiveIndicator(
                slotCount = routes.size,
                activeIndex = activeIndex,
                neonStrength = neonStrength,
            )
        }
    }
}

@Composable
private fun DockSlot(
    route: Route,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val tint = if (active) palette.mintBright else palette.fg2
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
            .clickable(onClick = onClick)
            .padding(vertical = BluetrackTokens.Sp2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = route.label,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
        Text(
            text = route.label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = if (active) 1f else 0.7f),
            modifier = Modifier
                .padding(top = 2.dp)
                .alpha(if (active) 1f else 0.85f),
        )
    }
}

/**
 * Thin accent bar that springs between dock slot centres on
 * selection change. The bar width is fixed; the offset animates.
 * `Modifier.bluetrackGlow` adds the neon halo so the indicator
 * carries the brand presence without dominating.
 */
@Composable
private fun ActiveIndicator(
    slotCount: Int,
    activeIndex: Int,
    neonStrength: Float,
) {
    val palette = BluetrackTheme.palette
    // Indicator width: ~40% of slot width, centred under the icon.
    val barWidth: Dp = 26.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .padding(top = 2.dp),
    ) {
        // BoxWithConstraints would be heavier; instead, use offset
        // expressed as a fraction-based padding via the
        // `weight(1f)` parents above and a manual
        // `animateDpAsState` for the slot centre.
        SlotIndicator(
            slotCount = slotCount,
            activeIndex = activeIndex,
            barWidth = barWidth,
            neonStrength = neonStrength,
            barColor = palette.mintBright,
        )
    }
}

@Composable
private fun SlotIndicator(
    slotCount: Int,
    activeIndex: Int,
    barWidth: Dp,
    neonStrength: Float,
    barColor: androidx.compose.ui.graphics.Color,
) {
    // Each slot occupies 1/N of the dock width. Anchor the bar at
    // the start of the slot then nudge it to centre via a half-
    // slot offset minus half-bar; the parent uses a weight-equal
    // Row so we can express the centring with `fillMaxWidth() *
    // fraction` via a simple Box layout.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val slotWidth = maxWidth / slotCount
        val target = slotWidth * activeIndex + (slotWidth - barWidth) / 2
        val offsetX by animateDpAsState(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = 320f,
            ),
            label = "dockIndicator",
        )
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(barWidth)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .bluetrackGlow(strength = neonStrength)
                .background(barColor),
        )
    }
}
