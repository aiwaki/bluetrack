package dev.xd.bluetrack.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ble.GatewayEvent
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Compact activity preview shown on the Hub. Canvas
 * `ActivityStrip` (`docs/design/v1/hub.jsx`) — 4 most recent
 * gateway events, each with a coloured status dot, a one-line
 * caption, the relative timestamp, and a chevron.
 *
 * The "see all →" inline action and the per-row tap both invoke
 * `onOpen` — the destination Activity route does the full
 * timeline in a later step. Keeping both callbacks on the same
 * handler avoids a router dependency for now.
 *
 * Event kinds are derived heuristically from the event message —
 * the `GatewayEvent` type does not carry an explicit kind enum,
 * so we keyword-classify until the gateway plumbs one through.
 */
enum class ActivityKind { Good, Warn, Default }

data class ActivityItem(
    val kind: ActivityKind,
    val text: String,
    val timeLabel: String,
)

@Composable
fun ActivityStrip(
    items: List<ActivityItem>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    maxRows: Int = 4,
) {
    val palette = BluetrackTheme.palette
    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(
            label = "Activity",
            action = {
                TextButton(onClick = onOpen) {
                    Text(
                        text = "SEE ALL →",
                        color = palette.fg2,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp,
                    )
                }
            },
        )
        val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .btGlass(strong = false, shape = shape),
        ) {
            val visible = items.take(maxRows)
            if (visible.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BluetrackTokens.Sp4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
                ) {
                    StatusDot(kind = ActivityKind.Default, palette = palette)
                    Text(
                        text = "Quiet — nothing has happened yet.",
                        color = palette.fg2,
                        fontSize = 12.sp,
                    )
                }
            } else {
                visible.forEachIndexed { i, it ->
                    if (i > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 0.dp,
                                    color = palette.hairline,
                                    shape = RoundedCornerShape(0.dp),
                                ).background(palette.hairline)
                                .padding(0.dp),
                        )
                    }
                    ActivityRow(item = it, onClick = onOpen, palette = palette)
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    onClick: () -> Unit,
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                )
                onClick()
            }.padding(horizontal = BluetrackTokens.Sp3, vertical = BluetrackTokens.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        StatusDot(kind = item.kind, palette = palette)
        Text(
            text = item.text,
            modifier = Modifier.weight(1f),
            color = palette.fg1,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = item.timeLabel,
            color = palette.fg3,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "›",
            color = palette.fg3,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StatusDot(kind: ActivityKind, palette: dev.xd.bluetrack.ui.theme.BluetrackPalette) {
    val color: Color = when (kind) {
        ActivityKind.Good -> palette.mint
        ActivityKind.Warn -> palette.warn
        ActivityKind.Default -> palette.fg3
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Heuristic mapper from raw [GatewayEvent.message] to an
 * [ActivityKind]. Until the gateway exposes an explicit kind
 * enum, classify by keywords so the strip can still colour-code
 * meaningful changes.
 */
fun GatewayEvent.toActivityItem(timeLabel: String): ActivityItem {
    val lower = message.lowercase()
    val kind = when {
        lower.contains("reject") ||
            lower.contains("drop") ||
            lower.contains("fail") ||
            lower.contains("error") ||
            lower.contains("unsupported") -> ActivityKind.Warn
        lower.contains("connected") ||
            lower.contains("opened") ||
            lower.contains("started") ||
            lower.contains("ready") ||
            lower.contains("pinned") -> ActivityKind.Good
        else -> ActivityKind.Default
    }
    return ActivityItem(kind = kind, text = message, timeLabel = timeLabel)
}
