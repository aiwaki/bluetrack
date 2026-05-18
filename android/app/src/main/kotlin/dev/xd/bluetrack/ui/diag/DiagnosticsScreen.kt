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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import dev.xd.bluetrack.ui.hub.Chip
import dev.xd.bluetrack.ui.hub.ChipKind
import dev.xd.bluetrack.ui.hub.HubHeader
import dev.xd.bluetrack.ui.hub.Pulse
import dev.xd.bluetrack.ui.hub.SectionLabel
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
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val hidWave = remember { mutableStateListOf<Float>() }
    val fbWave = remember { mutableStateListOf<Float>() }
    var lastReports by remember { mutableLongStateOf(status.reportsSent.toLong()) }
    var lastFb by remember { mutableLongStateOf(status.feedbackPackets.toLong()) }
    // Read the *current* `GatewayStatus` snapshot inside the tick
    // loop instead of capturing the value seen at composition time.
    // Without `rememberUpdatedState`, the `LaunchedEffect(Unit)`
    // closes over the initial `status` and every later delta
    // collapses to zero, freezing the live counters and the
    // sparklines. Caught by Codex review on PR #51.
    val statusState = rememberUpdatedState(status)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            val nowReports = statusState.value.reportsSent.toLong()
            val nowFb = statusState.value.feedbackPackets.toLong()
            val dR = (nowReports - lastReports).coerceAtLeast(0L)
            val dF = (nowFb - lastFb).coerceAtLeast(0L)
            lastReports = nowReports
            lastFb = nowFb
            if (hidWave.size >= 60) hidWave.removeAt(0)
            if (fbWave.size >= 60) fbWave.removeAt(0)
            hidWave.add(dR.toFloat())
            fbWave.add(dF.toFloat())
        }
    }
    val hidRate by remember { derivedStateOf { hidWave.lastOrNull() ?: 0f } }
    val fbRate by remember { derivedStateOf { fbWave.lastOrNull() ?: 0f } }

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
            LiveRateHero(
                hidRate = hidRate.toLong(),
                fbRate = fbRate.toLong(),
                hidTotal = status.reportsSent.toLong(),
                fbTotal = status.feedbackPackets.toLong(),
                hidWave = hidWave,
                fbWave = fbWave,
            )
            SectionLabel(label = "Replay window")
            ReplayWindowCard(
                lastCounter = status.feedbackPackets.toLong(),
                drops = status.rejectedFeedbackPackets,
            )
            SectionLabel(label = "PIN lifecycle")
            PinLifecycleCard(
                pinPresent = status.feedbackPin != null,
                rolls = if (status.feedbackPin != null) 1 else 0,
            )
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
                modifier = Modifier.weight(1f),
            )
            RateColumn(
                label = "FEEDBACK",
                value = fbRate,
                color = palette.cool,
                wave = fbWave,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BluetrackTokens.Sp2),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "HID total · $hidTotal",
                color = palette.fg2,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "fb total · $fbTotal",
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
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.fg2,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value.toString(),
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
            StatCell(label = "LAST", value = "0x${lastCounter.toString(16).padStart(4, '0')}", color = palette.fg0)
            StatCell(label = "WINDOW", value = "64", color = palette.fg0)
            StatCell(label = "DROPS", value = drops.toString(), color = if (drops > 0) palette.warn else palette.fg0)
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
            StatCell(label = "ROLLS", value = rolls.toString(), color = palette.fg0)
            StatCell(label = "AGE", value = if (pinPresent) "live" else "—", color = palette.fg0)
            StatCell(
                label = "CURRENT",
                value = if (pinPresent) "••••••" else "—",
                color = if (pinPresent) palette.mintBright else palette.fg3,
                mono = true,
            )
        }
        Text(
            text = "PIN itself only shown on Hub.",
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
                Text(
                    text = row.count.toString(),
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
