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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Trusted-host TOFU card. Three states mirror canvas `TrustCard`
 * in `docs/design/v1/hub.jsx`:
 *
 *  - `Empty`     → No host pinned yet. Shows the dashed avatar
 *                  ("+"), "No host trusted yet" headline, and a
 *                  one-line explainer that the first handshake
 *                  pins the host.
 *  - `Pinned`    → Solid mint avatar with a check glyph, host
 *                  fingerprint (short hex from
 *                  `GatewayStatus.trustedHostFingerprint`), and a
 *                  `Forget…` action wired to
 *                  `MainViewModel.forgetTrustedHost`.
 *  - `Rejection` → Warn-tinted rejection notice (rendered when the
 *                  rejection counter ticks above zero).
 *
 * The `Show QR` action is a placeholder in this PR — wiring it up
 * to a real identity QR is a follow-up after the
 * `--export-identity` CLI lands; the canvas already covers the
 * sheet design.
 */
enum class TrustState { Empty, Pinned, Rejection }

@Composable
fun TrustCard(
    state: TrustState,
    fingerprint: String?,
    onForget: () -> Unit,
    onShowQR: () -> Unit,
    modifier: Modifier = Modifier,
    recommendedHosts: List<String> = emptyList(),
    activeHost: String? = null,
    onConnect: (String) -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusLg)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .padding(BluetrackTokens.Sp5),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TRUSTED HOST",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.fg2,
                )
                TextButton(onClick = onShowQR) {
                    Text(
                        text = "SHOW QR",
                        color = palette.fg1,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp,
                    )
                }
            }
            // Crossfade between trust states so Empty → Pinned
            // (first successful handshake) and Pinned → Rejection
            // (replay / forgery attempt) read as a transition,
            // not an instant swap. 280ms matches the host name
            // settle in `StatusHero`.
            androidx.compose.animation.Crossfade(
                targetState = state,
                animationSpec = androidx.compose.animation.core
                    .tween(durationMillis = 280),
                label = "trust-state-crossfade",
            ) { current ->
                when (current) {
                    TrustState.Empty -> EmptyBody(palette)
                    TrustState.Pinned -> PinnedBody(palette, fingerprint, onForget)
                    TrustState.Rejection -> RejectionBody(palette)
                }
            }
            if (recommendedHosts.isNotEmpty()) {
                RecommendedHostsSection(
                    palette = palette,
                    hosts = recommendedHosts,
                    activeHost = activeHost,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )
            } else if (state == TrustState.Empty) {
                // No computer-class host bonded yet. Surface the
                // empty rail with a hint so the user knows where
                // pairing must start (the Mac/PC side, not the
                // phone — Android cannot force its way into the
                // host's Bluetooth menu).
                NoRecommendedHostsHint(palette)
            }
        }
    }
}

/**
 * List of bonded computer-class devices a tap can route a fresh
 * HID connect to. Used so the user does not have to wait for
 * the auto-connect tick (or rely on the host initiating from
 * its side, which Mac/PC do unreliably after sleep).
 */
@Composable
private fun NoRecommendedHostsHint(
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2)) {
        Text(
            text = "RECOMMENDED",
            style = MaterialTheme.typography.labelMedium,
            color = palette.fg2,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                .border(
                    1.dp,
                    palette.hairline,
                    RoundedCornerShape(BluetrackTokens.RadiusSm),
                ).padding(BluetrackTokens.Sp3),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "No computer host bonded",
                    color = palette.fg1,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "On your Mac / PC open Bluetooth settings, " +
                        "find \"Bluetrack\" and pair. It will appear here.",
                    color = palette.fg2,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun RecommendedHostsSection(
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
    hosts: List<String>,
    activeHost: String?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2)) {
        Text(
            text = "RECOMMENDED",
            style = MaterialTheme.typography.labelMedium,
            color = palette.fg2,
        )
        hosts.forEach { name ->
            val isActive = name == activeHost
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                    .border(
                        1.dp,
                        if (isActive) palette.crit.copy(alpha = 0.4f) else palette.hairline,
                        RoundedCornerShape(BluetrackTokens.RadiusSm),
                    ).clickable {
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        if (isActive) onDisconnect() else onConnect(name)
                    }.padding(horizontal = BluetrackTokens.Sp3, vertical = BluetrackTokens.Sp3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                            .background(
                                if (isActive) palette.crit.copy(alpha = 0.18f) else palette.hairline.copy(alpha = 0.5f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "▭",
                            color = if (isActive) palette.crit else palette.fg2,
                            fontSize = 14.sp,
                        )
                    }
                    Text(
                        text = name,
                        color = palette.fg0,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Tap an active row to force-disconnect; tap an
                // inactive one to wake the host. Stuck-link rescue
                // path — silent disconnects (Mac sleep / lid close)
                // can leave the cached host pinned until the LMP
                // supervision timer fires 30+ s later, and toggling
                // Bluetooth radio is overkill. The DISCONNECT pill
                // calls `BluetoothHidDevice.disconnect` which does
                // synchronously update the profile state.
                Text(
                    text = if (isActive) "DISCONNECT" else "CONNECT",
                    color = if (isActive) palette.warn else palette.fg1,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EmptyBody(palette: dev.xd.bluetrack.ui.theme.BluetrackPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                .border(
                    1.dp,
                    palette.hairlineStrong,
                    RoundedCornerShape(BluetrackTokens.RadiusSm),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "+", color = palette.fg3, fontSize = 22.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "No host trusted yet",
                color = palette.fg0,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "The first host that opens the feedback channel will be pinned. " +
                    "After that, others are rejected.",
                color = palette.fg2,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PinnedBody(
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
    fingerprint: String?,
    onForget: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                    .background(palette.crit.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✓", color = palette.crit, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Pinned host",
                    color = palette.fg0,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = fingerprint ?: "—",
                    color = palette.fg2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BluetrackTokens.Sp2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "pinned · TOFU",
                color = palette.fg3,
                fontSize = 11.sp,
            )
            TextButton(onClick = onForget) {
                Text(
                    text = "FORGET…",
                    color = palette.fg1,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

@Composable
private fun RejectionBody(palette: dev.xd.bluetrack.ui.theme.BluetrackPalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
            .background(palette.warn.copy(alpha = 0.08f))
            .border(
                1.dp,
                palette.warn.copy(alpha = 0.2f),
                RoundedCornerShape(BluetrackTokens.RadiusSm),
            ).padding(BluetrackTokens.Sp3),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(text = "Rejected", kind = ChipKind.Warn)
            }
            Text(
                text = "A different host tried to connect.",
                color = palette.fg1,
                fontSize = 13.sp,
            )
            Text(
                text = "Tap Forget to re-pair, or ignore to keep your trusted host.",
                color = palette.fg2,
                fontSize = 11.sp,
            )
        }
    }
}
