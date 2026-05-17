package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
    Box(
        modifier = modifier
            .fillMaxWidth()
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
                Text(
                    text = if (connected) "ACTIVE LINK" else "WAITING",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.fg2,
                )
                Text(
                    text = if (connected) (hostName ?: "host") else "no host",
                    color = palette.fg0,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.65).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connected && metric != null) {
                    Text(
                        text = metric,
                        color = palette.fg2,
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
        if (connected) {
            // Breath ring: scale 1 → 1.18 with alpha 0.6 → 0
            // on a 2.4 s reversed ease curve. Sits *outside* the
            // 56 dp avatar so it reads as a halo expanding.
            val transition = rememberInfiniteTransition(label = "hero-breath")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.18f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2_400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "hero-breath-scale",
            )
            val ringAlpha by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2_400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "hero-breath-alpha",
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(scale)
                    .alpha(ringAlpha)
                    .border(1.dp, palette.mintGlowSoft, RoundedCornerShape(BluetrackTokens.RadiusLg)),
            )
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(shape)
                .background(
                    if (connected) palette.mintGlowSoft else Color.White.copy(alpha = 0.05f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (connected) {
                Pulse(size = 14.dp)
            } else {
                Text(
                    text = "✕",
                    color = palette.fg2,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
