package dev.xd.bluetrack.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.hub.Chip
import dev.xd.bluetrack.ui.hub.ChipKind
import dev.xd.bluetrack.ui.hub.Pulse
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Bonded-device record rendered as a row on the Hosts route.
 * Mirrors canvas `HostRow` (`docs/design/v1/hosts.jsx` lines
 * 40-101).
 *
 * Five visual states packed onto a single composable so the route
 * does not have to fan out by class:
 *
 *  - `Active`        → mint left-rail accent, `HID active` Live
 *                      chip + [Pulse], disconnect (✕) action.
 *  - `Available`     → `Bonded` Cool chip, `CONNECT` mint pill
 *                      action.
 *  - `Incompatible`  → calm `Not supported` chip + warn caveat
 *                      icon (iOS HID restriction etc.).
 *  - `Ignored`       → 66 % opacity, calm `Ignored` chip, helper
 *                      text under the row explaining why
 *                      (`AirPods Pro · audio · not a HID host`).
 *  - any class       → 38 dp class icon avatar + the class label
 *                      chip as the first slot.
 */
enum class HostState { Active, Available, Incompatible, Ignored }

enum class HostClass { Computer, Audio, Pointing, Keyboard, Unknown }

data class HostEntry(
    val id: String,
    val name: String,
    val os: String?,
    val klass: HostClass,
    val state: HostState,
    val fingerprint: String?,
    val caveat: String?,
    val reason: String?,
)

@Composable
fun HostRow(
    host: HostEntry,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onShowCaveat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val isIgnored = host.state == HostState.Ignored
    val isActive = host.state == HostState.Active
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .let { m ->
                if (isActive) {
                    m.border(
                        width = 3.dp,
                        color = palette.mint,
                        shape = RoundedCornerShape(
                            topStart = BluetrackTokens.RadiusMd,
                            bottomStart = BluetrackTokens.RadiusMd,
                            topEnd = 0.dp,
                            bottomEnd = 0.dp,
                        ),
                    )
                } else {
                    m
                }
            }.alpha(if (isIgnored) 0.66f else 1f)
            .padding(BluetrackTokens.Sp3),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        // Class avatar.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                .background(
                    if (isIgnored) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.05f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = host.klass.glyph(),
                color = host.klass.accent(palette),
                fontSize = 18.sp,
            )
        }
        // Body.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
            ) {
                Text(
                    text = host.name,
                    color = if (isIgnored) palette.fg2 else palette.fg0,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (host.caveat != null) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onShowCaveat(host.caveat) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "ⓘ",
                            color = palette.warn,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
            ) {
                Chip(text = host.klass.label(), kind = ChipKind.Calm)
                when (host.state) {
                    HostState.Active -> Chip(
                        text = "HID active",
                        kind = ChipKind.Live,
                        leading = { Pulse(size = 2.5.dp) },
                    )
                    HostState.Available -> Chip(text = "Bonded", kind = ChipKind.Cool)
                    HostState.Incompatible -> Chip(text = "Not supported", kind = ChipKind.Calm)
                    HostState.Ignored -> Chip(text = "Ignored", kind = ChipKind.Calm)
                }
            }
            host.reason?.takeIf { isIgnored }?.let { reason ->
                Text(
                    text = reason,
                    color = palette.fg3,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            host.fingerprint?.takeIf { it != "—" }?.let { fp ->
                Text(
                    text = fp,
                    color = palette.fg3,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Trailing action.
        when (host.state) {
            HostState.Available -> ConnectPill(onConnect)
            HostState.Active -> DisconnectButton(onDisconnect)
            else -> {}
        }
    }
}

@Composable
private fun ConnectPill(onConnect: () -> Unit) {
    val palette = BluetrackTheme.palette
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(palette.mint)
            .clickable(onClick = onConnect)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "CONNECT",
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun DisconnectButton(onDisconnect: () -> Unit) {
    val palette = BluetrackTheme.palette
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, palette.hairline, RoundedCornerShape(999.dp))
            .clickable(onClick = onDisconnect),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✕", color = palette.fg2, fontSize = 14.sp)
    }
}

private fun HostClass.label(): String = when (this) {
    HostClass.Computer -> "Computer"
    HostClass.Audio -> "Audio"
    HostClass.Pointing -> "Pointer"
    HostClass.Keyboard -> "Keyboard"
    HostClass.Unknown -> "Unknown"
}

private fun HostClass.glyph(): String = when (this) {
    HostClass.Computer -> "▭"
    HostClass.Audio -> "♪"
    HostClass.Pointing -> "↗"
    HostClass.Keyboard -> "⌨"
    HostClass.Unknown -> "?"
}

private fun HostClass.accent(palette: dev.xd.bluetrack.ui.theme.BluetrackPalette): Color = when (this) {
    HostClass.Computer -> palette.cool
    HostClass.Audio -> palette.fg2
    HostClass.Pointing -> palette.fg2
    HostClass.Keyboard -> palette.fg2
    HostClass.Unknown -> palette.fg3
}
