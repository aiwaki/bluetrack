package dev.xd.bluetrack.ui.hosts

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    val isIncompatible = host.state == HostState.Incompatible
    // Each non-default state gets its own affordance. We can't
    // use a square-cornered `Modifier.border` here because the
    // row is clipped to a fully rounded shape and the
    // accent rectangle's right-side sharp corners would get
    // truncated by the clip — visible as a notch on Active /
    // Incompatible rows. Instead the left rail is painted via
    // `drawBehind` inside the clipped area; Ignored still uses
    // a full-perimeter hairline border whose shape matches the
    // clip exactly.
    val leftRail: Pair<Color, Float>? =
        when {
            isActive -> palette.crit to 3.dp.value
            isIncompatible -> palette.warn.copy(alpha = 0.55f) to 2.dp.value
            else -> null
        }
    val outline: Modifier =
        if (isIgnored) {
            Modifier.border(width = 1.dp, color = palette.hairline, shape = shape)
        } else {
            Modifier
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .btGlass(strong = false, shape = shape)
            .then(outline)
            .drawBehind {
                leftRail?.let { (color, widthDp) ->
                    val px = widthDp * density
                    drawRect(
                        color = color,
                        topLeft = Offset.Zero,
                        size = Size(px, size.height),
                    )
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
        // Trailing action: ✕ on rows where unpair is meaningful
        // (Active = currently connected; Available = bonded
        // computer waiting for auto-connect). Skipped on
        // Ignored / Incompatible — those rows are visible for
        // transparency only; nothing the user does there
        // affects the HID path.
        if (host.state == HostState.Active || host.state == HostState.Available) {
            DisconnectButton(onDisconnect)
        }
    }
}

@Composable
private fun DisconnectButton(onDisconnect: () -> Unit) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Press-scale spring matches the rest of the polished surfaces.
    // 0.9 dip on a 32 dp pill reads as a clear "I pressed it" beat
    // and gives the user a moment to abort by sliding off before
    // the disconnect actually fires (tryAwaitRelease semantics).
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
        label = "host-row-disconnect-press",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .scale(pressScale)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, palette.hairline, RoundedCornerShape(999.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) onDisconnect()
                    },
                )
            },
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
