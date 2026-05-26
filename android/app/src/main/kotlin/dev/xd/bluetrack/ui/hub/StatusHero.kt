package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp4),
        ) {
            AvatarBlock(connected = connected)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                Text(
                    // Host name is now the dominant typography on
                    // the Hub — matches the v2.4 design reference
                    // where the host display sits at ~60 sp as the
                    // primary visual anchor. Stays single-line via
                    // ellipsis; long names still ride the same
                    // pulse / colour treatment.
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
                        // and looking. Pairs with the cool breath
                        // ring on the avatar to signal "scanning",
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
}

@Composable
private fun AvatarBlock(connected: Boolean) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Box(
        modifier = Modifier.size(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Breath ring is rendered in both states, but with
        // different tempo + colour. Connected = fast mint pulse
        // ("active link"). Disconnected = slow cool pulse
        // ("searching, alive"). Keeping the animation in the
        // empty state was the user-flagged polish — the earlier
        // `✕` glyph read as a broken connection rather than a
        // calm idle.
        val transition = rememberInfiniteTransition(label = "hero-breath")
        val periodMs = if (connected) 2_400 else 3_600
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "hero-breath-scale",
        )
        val ringAlpha by transition.animateFloat(
            initialValue = if (connected) 0.55f else 0.32f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "hero-breath-alpha",
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .scale(scale)
                .alpha(ringAlpha)
                .border(
                    1.dp,
                    if (connected) palette.crit.copy(alpha = 0.65f) else palette.cool.copy(alpha = 0.55f),
                    RoundedCornerShape(BluetrackTokens.RadiusLg),
                ),
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(shape)
                .background(
                    if (connected) palette.crit.copy(alpha = 0.18f) else palette.cool.copy(alpha = 0.08f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Crossfade the inner glyph so the dot ↔ "···" swap
            // dissolves over 280ms instead of popping in. Matches
            // the caption crossfade above so both halves of the
            // hero transition together.
            androidx.compose.animation.Crossfade(
                targetState = connected,
                animationSpec = tween(durationMillis = 280),
                label = "hero-avatar-glyph",
            ) { isConnected ->
                if (isConnected) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.crit),
                    )
                } else {
                    // Three-dot searching glyph — reads as "looking"
                    // rather than "error" / "rejected" the old `✕`
                    // conveyed.
                    Text(
                        text = "···",
                        color = palette.cool,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
