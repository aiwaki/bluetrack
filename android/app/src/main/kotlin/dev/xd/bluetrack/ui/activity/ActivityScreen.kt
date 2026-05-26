package dev.xd.bluetrack.ui.activity

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ble.GatewayEvent
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.ui.hub.HubHeader
import dev.xd.bluetrack.ui.relativeAgeLabel
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Activity timeline route. Mirrors canvas `ActivityScreen`
 * (`docs/design/v1/activity.jsx`). Three blocks stacked
 * vertically:
 *
 * 1. **Session summary strip** — 4 mono stats (Hosts / Events /
 *    Warnings / Length) computed from the current snapshot.
 * 2. **Filter pills** — All / Pairing / Feedback / Trust /
 *    Errors. The selected pill carries the mint accent + glow.
 * 3. **Timeline** — vertical hairline rail at 28 dp with one
 *    `EventRow` per event. Up to 24 retained (matches the canvas
 *    cap; the gateway keeps a longer log but the UI window is
 *    capped).
 *
 * Event kinds are derived from the message text via [classify]
 * — the gateway has no kind field today; this mirrors the
 * heuristic used by `GatewayEvent.toActivityItem` for the Hub
 * strip.
 */
enum class ActivityKind(
    val accent: ActivityAccent,
    val label: String,
) {
    Connect(ActivityAccent.Mint, "Connected"),
    Disconnect(ActivityAccent.Calm, "Disconnected"),
    Pin(ActivityAccent.Cool, "PIN rotated"),
    Warn(ActivityAccent.Warn, "Warning"),
    Service(ActivityAccent.MintBright, "Service"),
    Reject(ActivityAccent.Crit, "Rejected"),
    Other(ActivityAccent.Calm, "Event"),
}

enum class ActivityAccent { Mint, MintBright, Cool, Warn, Crit, Calm }

@Composable
private fun ActivityAccent.color(): Color {
    val palette = BluetrackTheme.palette
    return when (this) {
        ActivityAccent.Mint -> palette.mint
        ActivityAccent.MintBright -> palette.mintBright
        ActivityAccent.Cool -> palette.cool
        ActivityAccent.Warn -> palette.warn
        ActivityAccent.Crit -> palette.crit
        ActivityAccent.Calm -> palette.fg3
    }
}

private enum class FilterKey(
    val label: String,
) {
    All("All"),
    Pairing("Pairing"),
    Feedback("Feedback"),
    Trust("Trust"),
    Errors("Errors"),
}

@Composable
fun ActivityScreen(
    status: GatewayStatus,
    now: Long,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    var filter by remember { mutableStateOf(FilterKey.All) }
    val limited = remember(status.events) { status.events.take(24) }
    val classified = remember(limited) { limited.map { it to classify(it) } }
    val visible = remember(classified, filter) { classified.filter { matches(filter, it.second) } }
    val warnings = classified.count {
        it.second == ActivityKind.Warn || it.second == ActivityKind.Reject
    }
    // Length anchors on now so the cell tracks wall-clock session
    // duration (oldest event → now) rather than the tight 24-event
    // window the old math gave.
    val sessionLength = remember(status.events, now) { sessionLengthLabel(status.events, now) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        HubHeader(title = "Activity", onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = BluetrackTokens.Sp6),
            verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        ) {
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 0),
            ) {
                SessionSummary(
                    events = classified.size,
                    warnings = warnings,
                    length = sessionLength,
                )
            }
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 1),
            ) {
                FilterRow(active = filter, onChange = { filter = it })
            }
            if (classified.isEmpty()) {
                Box(
                    modifier = dev.xd.bluetrack.ui
                        .rememberStaggerModifier(index = 2),
                ) {
                    EmptyState()
                }
            } else {
                Box(
                    modifier = dev.xd.bluetrack.ui
                        .rememberStaggerModifier(index = 2),
                ) {
                    Timeline(visible = visible, now = now)
                }
                Box(
                    modifier = dev.xd.bluetrack.ui
                        .rememberStaggerModifier(index = 3),
                ) {
                    RetainNote(visible = visible.size, total = classified.size, palette = palette)
                }
            }
        }
    }
}

@Composable
private fun SessionSummary(
    events: Int,
    warnings: Int,
    length: String,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    // Tween the headline counts so a fresh event nudges the
    // number visibly across 240ms instead of snapping. Same
    // FastOutSlowInEasing curve as the Diag route — keeps the
    // app's numeric pulse consistent.
    val animatedEvents by androidx.compose.animation.core.animateIntAsState(
        targetValue = events,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 240,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "activity-events",
    )
    val animatedWarnings by androidx.compose.animation.core.animateIntAsState(
        targetValue = warnings,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 240,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "activity-warnings",
    )
    // HOSTS cell removed — its value (count of unique host names
    // referenced in the event ring) was confusing on a single-host
    // workflow and the user flagged it as noise. The remaining
    // three cells carry actionable signal.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SummaryCell(label = "EVENTS", value = animatedEvents.toString())
        SummaryCell(
            label = "WARNINGS",
            value = animatedWarnings.toString(),
            accent = if (warnings > 0) palette.warn else palette.fg0,
        )
        SummaryCell(label = "LENGTH", value = length)
    }
}

@Composable
private fun SummaryCell(
    label: String,
    value: String,
    accent: Color? = null,
) {
    val palette = BluetrackTheme.palette
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            color = accent ?: palette.fg0,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = label,
            color = palette.fg2,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        )
    }
}

@Composable
private fun FilterRow(active: FilterKey, onChange: (FilterKey) -> Unit) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterKey.entries.forEach { key ->
            val selected = key == active
            // Crossfade chip bg + border + label color over
            // 180ms so the selection state slides between
            // chips instead of snapping. Subtle but reads as
            // alive — same idiom as the dock active indicator.
            val bgColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) palette.mint else Color.Transparent,
                animationSpec = androidx.compose.animation.core
                    .tween(180),
                label = "filter-chip-bg-${key.name}",
            )
            val borderColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) Color.Transparent else palette.hairline,
                animationSpec = androidx.compose.animation.core
                    .tween(180),
                label = "filter-chip-border-${key.name}",
            )
            val labelColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) Color.White else palette.fg1,
                animationSpec = androidx.compose.animation.core
                    .tween(180),
                label = "filter-chip-label-${key.name}",
            )
            // Press-scale spring so each chip dips on touch.
            // 0.9 on press, springy LowBouncy release. Pairs with
            // the existing 180ms color crossfade so the tap reads
            // as "press + slide" instead of an instant colour swap.
            var pressed by remember { mutableStateOf(false) }
            val pressScale by animateFloatAsState(
                targetValue = if (pressed) 0.9f else 1f,
                animationSpec = if (pressed) {
                    spring(stiffness = Spring.StiffnessMedium)
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow,
                    )
                },
                label = "filter-chip-press-${key.name}",
            )
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .scale(pressScale)
                    .clip(RoundedCornerShape(999.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(999.dp))
                    .pointerInput(key) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                )
                                val released = tryAwaitRelease()
                                pressed = false
                                if (released) onChange(key)
                            },
                        )
                    }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = key.label.uppercase(),
                    color = labelColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}

@Composable
private fun Timeline(
    visible: List<Pair<GatewayEvent, ActivityKind>>,
    now: Long,
) {
    val palette = BluetrackTheme.palette
    Box(modifier = Modifier.fillMaxWidth()) {
        // Vertical rail at 11 dp left (centre of the 22 dp dot).
        Box(
            modifier = Modifier
                .padding(start = 11.dp, top = 12.dp, bottom = 12.dp)
                .width(1.dp)
                .height((visible.size * 60).dp.coerceAtLeast(0.dp))
                .background(palette.hairline),
        )
        Column(verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp1)) {
            visible.forEach { (event, kind) ->
                EventRow(event = event, kind = kind, now = now)
            }
        }
    }
}

@Composable
private fun EventRow(
    event: GatewayEvent,
    kind: ActivityKind,
    now: Long,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BluetrackTokens.Sp2),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        DotBadge(accent = kind.accent.color(), bg = palette.bg0, border = palette.hairlineStrong)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.message,
                    color = palette.fg0,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = relativeAgeLabel(now, event.timestampMs),
                    color = palette.fg3,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = event.source,
                color = palette.fg2,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DotBadge(accent: Color, bg: Color, border: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
    }
}

@Composable
private fun EmptyState() {
    val palette = BluetrackTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Quiet.",
            color = palette.fg0,
            fontSize = 18.sp,
        )
        Text(
            text = "Connect a host to start logging events. The last 24 will appear here.",
            color = palette.fg2,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RetainNote(
    visible: Int,
    total: Int,
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BluetrackTokens.Sp3),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "SHOWING $visible OF $total · MAX 24 RETAINED",
            color = palette.fg3,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
        )
    }
}

private fun classify(event: GatewayEvent): ActivityKind {
    val msg = event.message.lowercase()
    val src = event.source.lowercase()
    return when {
        msg.contains("reject") || msg.contains("untrusted") -> ActivityKind.Reject
        msg.contains("warn") || msg.contains("drop") || msg.contains("dropped") -> ActivityKind.Warn
        msg.contains("pin") || msg.contains("gatt") || msg.contains("handshake") -> ActivityKind.Pin
        msg.contains("disconnect") || msg.contains("closed") -> ActivityKind.Disconnect
        msg.contains("connect") || msg.contains("ready") || msg.contains("active") -> ActivityKind.Connect
        msg.contains("service") || msg.contains("foreground") || src.contains("service") -> ActivityKind.Service
        else -> ActivityKind.Other
    }
}

private fun matches(filter: FilterKey, kind: ActivityKind): Boolean = when (filter) {
    FilterKey.All -> true
    FilterKey.Pairing -> kind == ActivityKind.Connect || kind == ActivityKind.Disconnect || kind == ActivityKind.Pin
    FilterKey.Feedback -> kind == ActivityKind.Pin || kind == ActivityKind.Service
    FilterKey.Trust -> kind == ActivityKind.Reject
    FilterKey.Errors -> kind == ActivityKind.Warn || kind == ActivityKind.Reject
}

/**
 * "Session length" reads as wall-clock from the oldest event in
 * the ring up to *now*. Earlier this computed `newest - oldest`
 * over a 24-event window, which produced numbers like "11m"
 * even after the app had been running for hours — the 24 most
 * recent events spanned a tight burst at the end of the day,
 * but the user reads the cell as "how long has this session
 * been live". Anchoring on `now` matches that mental model.
 */
private fun sessionLengthLabel(
    events: List<GatewayEvent>,
    now: Long,
): String {
    if (events.isEmpty()) return "—"
    val oldest = events.minByOrNull { it.timestampMs }?.timestampMs ?: return "—"
    val ms = (now - oldest).coerceAtLeast(0L)
    val mins = ms / 60_000L
    val hours = mins / 60L
    val rem = mins % 60L
    return when {
        hours > 0L -> "${hours}h ${rem}m"
        mins > 0L -> "${mins}m"
        else -> "<1m"
    }
}
