package dev.xd.bluetrack.ui.diag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.ble.RejectionCause
import dev.xd.bluetrack.ui.automationLabel
import dev.xd.bluetrack.ui.hostFallbackLabel
import dev.xd.bluetrack.ui.hub.Chip
import dev.xd.bluetrack.ui.hub.ChipKind
import dev.xd.bluetrack.ui.hub.HubHeader
import dev.xd.bluetrack.ui.hub.Pulse
import dev.xd.bluetrack.ui.hub.SectionLabel
import dev.xd.bluetrack.ui.inputSourceLabel
import dev.xd.bluetrack.ui.primaryStatusLabel
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens
import kotlinx.coroutines.delay

/**
 * Diagnostics route. Mirrors canvas `DiagnosticsScreen`
 * (`docs/design/v1/diagnostics.jsx`).
 *
 * Four blocks rendered top-to-bottom under the unified
 * [HubHeader]:
 *
 * 1. **Live rate hero** — HID / Feedback per-second rates with
 *    sparklines and lifetime totals.
 * 2. **Replay window** — last counter / window size / drops + a
 *    64-bucket viz showing the head and any drop slots.
 * 3. **PIN lifecycle** — rolls / age / masked current.
 * 4. **Feedback rejections** — 7-category breakdown.
 *
 * Rate sparklines are driven by a 1 s tick that samples the
 * delta of `reportsSent` / `feedbackPackets` since the last
 * frame and pushes into a rolling 60-entry window. The hero
 * shows the latest delta as the headline number.
 */
@Composable
fun DiagnosticsScreen(
    status: GatewayStatus,
    hidWave: List<Float>,
    fbWave: List<Float>,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    // Surface peak rate over the 60 s rolling window owned by
    // the ViewModel. Diagnostics has no touchpad of its own, so
    // a "latest second" delta is almost always 0 while the user
    // reads the screen — the 60 s peak still reflects whether
    // the gateway was busy recently, and the wave survives
    // navigating away from Hub and back because the sampler
    // lives at the VM, not the composable.
    val hidRate by remember(hidWave) { derivedStateOf { hidWave.maxOrNull() ?: 0f } }
    val fbRate by remember(fbWave) { derivedStateOf { fbWave.maxOrNull() ?: 0f } }

    // 1 s ticking clock drives the "last seen Xs ago" labels on
    // each rate card. Same cadence as the sparkline pump so the
    // ageing text updates in lock-step with the chart.
    var nowMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            HubHeader(
                title = "Diagnostics",
                modifier = Modifier.weight(1f),
                rightSlot = {
                    Chip(text = "RECORDING", kind = ChipKind.Live, leading = { Pulse(size = 2.5.dp) })
                },
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = BluetrackTokens.Sp6),
            verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        ) {
            // Connection + System cards moved from Hub to
            // Diagnostics (2026-05-20). Hub keeps the at-a-glance
            // StatusHero and TrustCard; raw transport state is a
            // Diag concern.
            Column(
                modifier = rememberStaggerModifier(index = 0),
                verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
            ) {
                SectionLabel(label = "Connection")
                ConnectionCard(status = status, now = nowMs)
            }
            Column(
                modifier = rememberStaggerModifier(index = 1),
                verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
            ) {
                SectionLabel(label = "System")
                SystemCard(status = status)
            }
            val empty =
                status.lifetimeCounters.reports == 0L &&
                    status.lifetimeCounters.feedback == 0L &&
                    status.lifetimeCounters.rejections == 0L
            if (empty) {
                Text(
                    text = "Counters appear once a host connects.",
                    color = palette.fg0,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Bluetrack only meters traffic during an active HID session. " +
                        "Moving the touchpad in the app while no host is paired emits " +
                        "no HID reports, so the counters stay at zero.",
                    color = palette.fg2,
                    fontSize = 12.sp,
                )
                return@Column
            }
            Box(modifier = rememberStaggerModifier(index = 2)) {
                LiveRateHero(
                    hidRate = hidRate.toLong(),
                    fbRate = fbRate.toLong(),
                    // Show lifetime totals from the persisted counters
                    // rather than the per-session `reportsSent` /
                    // `feedbackPackets` fields. Lifetime values survive
                    // a process kill and reflect the cumulative work the
                    // engine has done — what a user opening Diagnostics
                    // actually wants to see.
                    hidTotal = status.lifetimeCounters.reports,
                    fbTotal = status.lifetimeCounters.feedback,
                    hidWave = hidWave,
                    fbWave = fbWave,
                    hidLastAtMs = status.lastReportAtMs,
                    fbLastAtMs = status.lastFeedbackAtMs,
                    now = nowMs,
                )
            }
            // The three blocks below (Replay window / PIN lifecycle
            // / Feedback rejections) only carry signal once the
            // encrypted BLE feedback channel is actually in use. A
            // plain HID-only session (cursor + scroll on the host)
            // never opens that channel, so the cards stay empty —
            // explain that explicitly rather than leaving the user
            // staring at "0 / 0 / 0" wondering whether something
            // is broken.
            val feedbackChannelEverUsed =
                status.lifetimeCounters.feedback > 0L ||
                    status.lifetimeCounters.rejections > 0L ||
                    status.feedbackPin != null
            if (!feedbackChannelEverUsed) {
                FeedbackChannelDormantHint(palette)
            }
            Column(
                modifier = rememberStaggerModifier(index = 3),
                verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
            ) {
                SectionLabel(label = "Replay window")
                ReplayWindowCard(
                    // Replay window's "last counter" is the per-frame
                    // counter the gateway acked, not the count of
                    // accepted packets. Display the accepted-feedback
                    // count anyway until the gateway exposes the real
                    // counter value; clearer label below makes that
                    // explicit.
                    lastCounter = status.feedbackPackets.toLong(),
                    drops = (status.lifetimeCounters.rejectionsByCause[RejectionCause.Replay] ?: 0L).toInt(),
                )
            }
            Column(
                modifier = rememberStaggerModifier(index = 4),
                verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
            ) {
                SectionLabel(label = "PIN lifecycle")
                PinLifecycleCard(
                    pinPresent = status.feedbackPin != null,
                    rolls = if (status.feedbackPin != null) 1 else 0,
                )
            }
            Column(
                modifier = rememberStaggerModifier(index = 5),
                verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
            ) {
                SectionLabel(
                    label = "Feedback rejections",
                    action = {
                        Text(
                            text = "SESSION · ${status.rejectedFeedbackPackets} REJ",
                            color = palette.fg3,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp,
                        )
                    },
                )
                RejectionsCard(byCause = status.lifetimeCounters.rejectionsByCause)
            }
            // Bottom breathing room so the last card never sits
            // flush against the dock. Matches the trailing
            // `Modifier.padding(bottom = 24.dp)` on the Settings,
            // Hosts, and Activity routes.
            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun LiveRateHero(
    hidRate: Long,
    fbRate: Long,
    hidTotal: Long,
    fbTotal: Long,
    hidWave: List<Float>,
    fbWave: List<Float>,
    hidLastAtMs: Long?,
    fbLastAtMs: Long?,
    now: Long,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = true, shape = shape)
            .padding(BluetrackTokens.Sp5),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp5),
        ) {
            RateColumn(
                label = "HID SEND",
                value = hidRate,
                color = palette.mintBright,
                wave = hidWave,
                lastSeenAtMs = hidLastAtMs,
                now = now,
                modifier = Modifier.weight(1f),
            )
            RateColumn(
                label = "FEEDBACK",
                value = fbRate,
                color = palette.cool,
                wave = fbWave,
                lastSeenAtMs = fbLastAtMs,
                now = now,
                modifier = Modifier.weight(1f),
            )
        }
        // Tween the lifetime totals so a fresh report bumps the
        // number visibly instead of jumping. Each total caps at
        // Int.MAX_VALUE worth of reports (~2.1 billion) which the
        // gateway will never reach in any realistic session, so
        // the `toInt()` truncation is safe.
        val animatedHidTotal by androidx.compose.animation.core.animateIntAsState(
            targetValue = hidTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 240,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            label = "rate-total-hid",
        )
        val animatedFbTotal by androidx.compose.animation.core.animateIntAsState(
            targetValue = fbTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 240,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            label = "rate-total-fb",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BluetrackTokens.Sp2),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "HID total · $animatedHidTotal",
                color = palette.fg2,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "fb total · $animatedFbTotal",
                color = palette.fg2,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun RateColumn(
    label: String,
    value: Long,
    color: Color,
    wave: List<Float>,
    lastSeenAtMs: Long?,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = palette.fg2,
            )
            Text(
                text = "PEAK 60s",
                color = palette.fg3,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
            )
        }
        // Animate the peak-rate readout between samples so the
        // headline number tweens up / down over 240ms instead of
        // snapping to the new 60s rolling peak. Reads as a live
        // gauge, not a flickering counter — same trick as the
        // host name / hero stat triplet on the Hub.
        val animatedRate by androidx.compose.animation.core.animateIntAsState(
            targetValue = value.toInt(),
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 240,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            label = "rate-value-$label",
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = animatedRate.toString(),
                color = color,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "/s",
                color = palette.fg2,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
            )
        }
        Sparkline(data = wave, color = color, height = 28.dp, fill = true)
        Text(
            // Diagnostics has no touchpad of its own, so the live
            // "/s" delta is almost always zero while the user reads
            // the screen. "Last seen Xs ago" tells the user the
            // pipeline is alive — and goes "—" when the channel
            // has never fired (typical for FEEDBACK without the
            // macOS-hid-inspector running on the host).
            text = lastSeenAgoLabel(lastSeenAtMs, now),
            color = palette.fg3,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * "last X seconds ago" / "last X minutes ago" formatter for the
 * per-channel rate cards. `null` = no event has ever fired in
 * this session.
 */
private fun lastSeenAgoLabel(lastSeenAtMs: Long?, now: Long): String {
    if (lastSeenAtMs == null) return "last seen —"
    val ageMs = (now - lastSeenAtMs).coerceAtLeast(0L)
    val secs = ageMs / 1_000L
    val mins = secs / 60L
    val hours = mins / 60L
    return when {
        secs < 2L -> "active now"
        secs < 60L -> "last seen ${secs}s ago"
        mins < 60L -> "last seen ${mins}m ago"
        else -> "last seen ${hours}h ago"
    }
}

@Composable
private fun FeedbackChannelDormantHint(
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
) {
    val shape = RoundedCornerShape(BluetrackTokens.RadiusSm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.cool.copy(alpha = 0.3f), shape)
            .background(palette.cool.copy(alpha = 0.05f))
            .padding(BluetrackTokens.Sp3),
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "ⓘ",
            color = palette.cool,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Feedback channel dormant",
                color = palette.fg0,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "Replay window, PIN lifecycle and rejection counters only " +
                        "tick while an encrypted feedback channel is open. Run the " +
                        "macOS-hid-inspector `feedback` subcommand on the host to " +
                        "exercise this path.",
                color = palette.fg2,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ReplayWindowCard(
    lastCounter: Long,
    drops: Int,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(BluetrackTokens.Sp4),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val animatedDrops by androidx.compose.animation.core.animateIntAsState(
                targetValue = drops,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 240,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
                label = "replay-drops",
            )
            StatCell(label = "LAST", value = "0x${lastCounter.toString(16).padStart(4, '0')}", color = palette.fg0)
            StatCell(label = "WINDOW", value = "64", color = palette.fg0)
            StatCell(
                label = "DROPS",
                value = animatedDrops.toString(),
                color = if (drops > 0) palette.warn else palette.fg0,
            )
        }
        // 64-bucket viz: head at last index, drop markers placed
        // arithmetically. With no per-counter drop log in the
        // status snapshot we render drops at slot 47 (canvas
        // mock) only when `drops > 0`, otherwise the row is
        // empty except for the bright head.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, palette.hairline, RoundedCornerShape(8.dp)),
        ) {
            val w = size.width
            val h = size.height
            val slots = 64
            for (i in 0 until slots) {
                val x = (i.toFloat() / (slots - 1)) * w
                val isHead = i == slots - 1
                val isDrop = drops > 0 && i == 47
                val color = when {
                    isHead -> palette.mintBright
                    isDrop -> palette.warn
                    i > 56 -> palette.cool
                    else -> Color.White.copy(alpha = 0.16f)
                }
                drawRect(
                    color = color,
                    topLeft = Offset(x - 1f, h * 0.18f),
                    size = androidx.compose.ui.geometry
                        .Size(2f, h * 0.64f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "n − 63", color = palette.fg3, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(text = "head ↑", color = palette.fg3, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(text = "n", color = palette.fg3, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PinLifecycleCard(
    pinPresent: Boolean,
    rolls: Int,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(BluetrackTokens.Sp4),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val animatedRolls by androidx.compose.animation.core.animateIntAsState(
                targetValue = rolls,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 240,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
                label = "pin-rolls",
            )
            StatCell(label = "ROLLS", value = animatedRolls.toString(), color = palette.fg0)
            StatCell(label = "AGE", value = if (pinPresent) "live" else "—", color = palette.fg0)
            StatCell(
                label = "CURRENT",
                value = if (pinPresent) "••••••" else "—",
                color = if (pinPresent) palette.mintBright else palette.fg3,
                mono = true,
            )
        }
        Text(
            text =
                if (pinPresent) {
                    "PIN digits themselves are only shown on the Hub."
                } else {
                    "No PIN issued yet — appears when the host opens the encrypted channel."
                },
            color = palette.fg3,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    color: Color,
    mono: Boolean = false,
) {
    val palette = BluetrackTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = palette.fg2,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        )
        Text(
            text = value,
            color = color,
            fontSize = if (mono) 18.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            letterSpacing = if (mono) 2.sp else 0.sp,
        )
    }
}

@Composable
private fun RejectionsCard(byCause: Map<RejectionCause, Long>) {
    val palette = BluetrackTheme.palette

    // Real per-category counters from `GatewayStatus.lifetimeCounters`
    // (step 9b). Missing bucket = 0; rendered greyed-out.
    fun count(cause: RejectionCause): Int = (byCause[cause] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val rows = listOf(
        Rejection("Wrong frame size", "not 28 bytes", palette.fg2, count(RejectionCause.Size)),
        Rejection("GCM tag failure", "wrong PIN, key or tampered", palette.crit, count(RejectionCause.Gcm)),
        Rejection("Replay window drop", "counter outside window", palette.cool, count(RejectionCause.Replay)),
        Rejection(
            "Wrong-length handshake",
            "malformed handshake frame",
            palette.fg2,
            count(RejectionCause.HandshakeLength),
        ),
        Rejection("Bad Ed25519 signature", "host identity mismatch", palette.crit, count(RejectionCause.Signature)),
        Rejection("Untrusted host", "TOFU pin mismatch", palette.warn, count(RejectionCause.Untrusted)),
        Rejection("X25519 derivation", "malformed peer pubkey", palette.crit, count(RejectionCause.X25519)),
        Rejection("Rate-limited", "handshake flood throttled", palette.fg2, count(RejectionCause.RateLimit)),
        Rejection(
            "Session not ready",
            "frame before handshake completed",
            palette.fg2,
            count(RejectionCause.SessionNotReady),
        ),
    )
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(BluetrackTokens.Sp4),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.hairline),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BluetrackTokens.Sp2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(row.color.copy(alpha = if (row.count > 0) 1f else 0.35f)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = row.label,
                        color = if (row.count > 0) palette.fg0 else palette.fg2,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = row.note,
                        color = palette.fg3,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                val animatedCount by androidx.compose.animation.core.animateIntAsState(
                    targetValue = row.count,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 240,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    label = "rejection-count-${row.label}",
                )
                Text(
                    text = animatedCount.toString(),
                    color = if (row.count > 0) row.color else palette.fg3,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private data class Rejection(
    val label: String,
    val note: String,
    val color: Color,
    val count: Int,
)

@Composable
private fun ConnectionCard(
    status: GatewayStatus,
    now: Long,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(BluetrackTokens.Sp4),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
    ) {
        DiagStatusLine(label = "STATE", value = primaryStatusLabel(status, now))
        DiagStatusLine(label = "HOST", value = status.host ?: hostFallbackLabel(status))
        DiagStatusLine(label = "INPUT", value = inputSourceLabel(status, now))
        DiagStatusLine(label = "FLOW", value = status.automationLabel())
        status.error?.let { error ->
            Text(
                text = error,
                color = palette.warn,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SystemCard(status: GatewayStatus) {
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(BluetrackTokens.Sp4),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
    ) {
        DiagStatusLine(
            label = "BT",
            value = if (status.compatibility.bluetoothEnabled) "Ready" else "Off",
        )
        DiagStatusLine(label = "HID", value = status.hid)
        DiagStatusLine(label = "PAIR", value = status.pairing)
        DiagStatusLine(label = "BLE", value = status.feedback)
    }
}

@Composable
private fun DiagStatusLine(label: String, value: String) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = palette.fg3,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(58.dp),
        )
        Text(
            text = value,
            color = palette.fg0,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
