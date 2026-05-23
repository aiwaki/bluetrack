package dev.xd.bluetrack.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.hub.Wordmark
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * First-run Welcome / permissions explainer.
 *
 * The earlier shell jumped straight into the system Bluetooth
 * permission dialog on cold launch, which gave the user no
 * context for *why* the app needed those grants. Welcome is the
 * single screen that runs before the dock is rendered. It
 * mirrors the canvas onboarding sketch (`docs/design/v1/welcome.jsx`):
 * wordmark + a short pitch, three numbered cards explaining the
 * grants Bluetrack asks for, and a mint CTA that:
 *
 *   1. Persists `TweaksRepository.setOnboarded(true)`.
 *   2. Triggers the runtime BT-nearby permission flow.
 *
 * Skipped on subsequent launches (`onboarded` flow already true).
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BluetrackTokens.Sp6, vertical = BluetrackTokens.Sp5),
        verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp4),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
        ) {
            Wordmark(size = 14.sp)
            Text(
                text = "Turn your phone into a wireless\nMac · PC input device",
                color = palette.fg0,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.65).sp,
            )
            Text(
                text =
                    "Bluetrack speaks the Bluetooth HID profile, so your computer reads it as a " +
                        "standard mouse or gamepad — no driver, no extra app on the host.",
                color = palette.fg2,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif,
            )
        }

        // Three permission cards. Each is intentionally one-liner
        // body copy so the page never scrolls past a thumb on
        // typical phones.
        PermissionCard(
            index = 1,
            title = "Bluetooth nearby",
            body =
                "Required to advertise the HID profile so your Mac / PC can see " +
                    "Bluetrack in its Bluetooth settings and pair.",
            palette = palette,
        )
        PermissionCard(
            index = 2,
            title = "Notifications",
            body =
                "We surface a small foreground-service notification while the HID " +
                    "service is alive. Android requires this to keep the link " +
                    "running while the screen is off.",
            palette = palette,
        )
        PermissionCard(
            index = 3,
            title = "No background tracking",
            body =
                "Bluetrack never collects analytics, never uploads telemetry, and " +
                    "stores nothing off the device. Pairing keys live in app-private " +
                    "storage only.",
            palette = palette,
            tone = CardTone.Cool,
        )

        // CTA — mint pill at the bottom of the page.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BluetrackTokens.RadiusMd))
                .background(palette.mintBright)
                .clickable(onClick = onGetStarted)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "GET STARTED",
                color = Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
            )
        }
        Text(
            text = "You can revoke any permission later from Settings → Permissions.",
            color = palette.fg3,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private enum class CardTone { Mint, Cool }

@Composable
private fun PermissionCard(
    index: Int,
    title: String,
    body: String,
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
    tone: CardTone = CardTone.Mint,
) {
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    val accent =
        when (tone) {
            CardTone.Mint -> palette.mintBright
            CardTone.Cool -> palette.cool
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .border(1.dp, palette.hairline, shape)
            .padding(BluetrackTokens.Sp4),
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(BluetrackTokens.RadiusSm)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = palette.fg0,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = body,
                color = palette.fg2,
                fontSize = 12.sp,
            )
        }
    }
}
