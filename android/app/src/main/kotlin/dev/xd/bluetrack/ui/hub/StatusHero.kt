package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Hero status card on the Hub. Mirrors canvas `status hero`
 * block in `docs/design/v1/hub.jsx` (`HubScreen` lines 521-561).
 *
 *   ┌────────────────────────────────────────┐
 *   │ 56dp avatar │ ACTIVE LINK              │
 *   │ Pulse +     │ studio-mbp               │  ← 26 sp display
 *   │ breath ring │ HID · 1000 Hz · 0 drop   │  ← mono metric
 *   └────────────────────────────────────────┘
 *
 * Two visual states:
 *  - `connected = true`  → mint-glow-soft avatar with [Pulse],
 *    breath-ring animation (2.4 s ease-in-out), "ACTIVE LINK"
 *    caption, host name in display weight, and a mono metric
 *    line (HID rate + drop count when known).
 *  - `connected = false` → calm grey avatar with "✕" glyph,
 *    "WAITING" caption, "no host" 26 sp.
 *
 * The breath ring is a scale/alpha animation on a transparent
 * 1 dp `border` slightly larger than the avatar so it reads as
 * a soft mint halo expanding outward without re-rasterising the
 * underlying drawable.
 */
@Composable
fun StatusHero(
    connected: Boolean,
    hostName: String?,
    metric: String?,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusLg)
    // Connect-burst: when `connected` flips false → true, scale
    // the hero from 1.04 back to 1.0 over a single spring beat.
    // Reads as a tactile "snap-in" matching the moment the host
    // name fills in. Disconnects do not get the burst — going
    // dark should feel calm, not punchy.
    var prevConnected by remember { mutableStateOf(connected) }
    val burst = remember { Animatable(1f) }
    LaunchedEffect(connected) {
        if (connected && !prevConnected) {
            burst.snapTo(1.04f)
            burst.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        prevConnected = connected
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(burst.value)
            .clip(shape)
            .btGlass(strong = true, shape = shape)
            .padding(BluetrackTokens.Sp5),
    ) {
        // Hero is now text-only. Earlier 64 dp avatar with a tinted
        // square + breathing border ring + centred dot read as
        // visually clunky and competed with the host name for
        // attention. The single calm Pulse dot in the caption row
        // tells the eye the route is alive without a decorative
        // square. Host name typography stays the visual anchor.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Caption row: small breathing dot + label. The dot
            // animates via the shared `Pulse` atom (mint glow halo)
            // recoloured to crit when connected, cool when not.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pulse(
                    size = 4.dp,
                    color = if (connected) palette.crit else palette.cool,
                )
                // Crossfade the caption between SEARCHING ↔ ACTIVE
                // LINK instead of an instant text swap. 220ms tween
                // pairs with the connect-burst on the parent card —
                // both finish around the same time so the card
                // settles in one beat.
                androidx.compose.animation.Crossfade(
                    targetState = connected,
                    animationSpec = tween(durationMillis = 220),
                    label = "hero-caption",
                ) { isConnected ->
                    Text(
                        text = if (isConnected) "ACTIVE LINK" else "SEARCHING",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isConnected) palette.fg2 else palette.cool,
                    )
                }
            }
            Text(
                // Host name is the visual anchor of the Hub —
                // matches the v2.4 design reference where the host
                // display sits at ~60 sp. Stays single-line via
                // ellipsis; long names still ride the same colour
                // treatment.
                text = if (connected) (hostName ?: "host") else "no host connected",
                color = if (connected) palette.fg0 else palette.fg1,
                fontSize = if (connected) 44.sp else 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                lineHeight = 44.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (connected && metric != null) {
                Text(
                    text = metric,
                    color = palette.fg2,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else if (!connected) {
                Text(
                    // Cool-tinted hint that the route is alive
                    // and looking. Pairs with the cool Pulse
                    // dot in the caption to signal "scanning",
                    // not "broken".
                    text = "tap a host below to connect",
                    color = palette.cool,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
