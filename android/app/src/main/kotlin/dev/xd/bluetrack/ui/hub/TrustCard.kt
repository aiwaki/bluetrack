package dev.xd.bluetrack.ui.hub

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
            when (state) {
                TrustState.Empty -> EmptyBody(palette)
                TrustState.Pinned -> PinnedBody(palette, fingerprint, onForget)
                TrustState.Rejection -> RejectionBody(palette)
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
                    .background(palette.mintGlowSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✓", color = palette.mintBright, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
