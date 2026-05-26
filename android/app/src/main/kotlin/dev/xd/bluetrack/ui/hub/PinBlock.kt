package dev.xd.bluetrack.ui.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens
import kotlinx.coroutines.delay

/**
 * BLE feedback-channel pairing PIN card.
 *
 * State matrix (matches canvas `PinBlock` in `docs/design/v1/hub.jsx`):
 *
 *  | gattOpen | display                                              |
 *  |----------|------------------------------------------------------|
 *  | false    | grey em-dashes + "PIN appears once a host opens …"   |
 *  | true     | 6 mono digits (tap to copy) + countdown + helper     |
 *
 * Copy-to-clipboard semantics mirror the canvas: tap the digits,
 * the value lands on the clipboard, and a 30 s countdown starts.
 * When the countdown hits zero we overwrite the clipboard with an
 * empty string so a forgotten PIN never lives there forever. Each
 * fresh `pin` value resets the copied state — opening a new GATT
 * session never silently keeps a stale PIN on the clipboard.
 *
 * The card uses the [btGlass] strong tint and gains a mint border
 * glow when `gattOpen = true` to echo the canvas `box-shadow`
 * inset / outer glow combo.
 */
@Composable
fun PinBlock(
    pin: String?,
    session: Int,
    gattOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = RoundedCornerShape(BluetrackTokens.RadiusLg)
    var copied by remember(pin) { mutableStateOf(false) }
    var secs by remember { mutableIntStateOf(0) }

    // 30 s clipboard auto-clear.
    LaunchedEffect(copied, pin) {
        if (!copied || pin == null) return@LaunchedEffect
        var remaining = 30
        secs = remaining
        while (remaining > 0 && copied) {
            delay(1_000L)
            remaining -= 1
            secs = remaining
        }
        if (copied) {
            clearClipboard(context)
            copied = false
        }
    }

    // New-PIN burst. When a fresh `pin` arrives (null → value
    // or session rolls), snap the card to scale 1.03 and
    // spring back. Reads as a tactile "PIN issued" beat —
    // pairs with the existing `NeonRibbon` flash on the route
    // above. Disappearing PIN (value → null) does not burst.
    var prevPin by remember { mutableStateOf<String?>(pin) }
    val burst = remember {
        androidx.compose.animation.core
            .Animatable(1f)
    }
    LaunchedEffect(pin) {
        if (pin != null && pin != prevPin) {
            burst.snapTo(1.03f)
            burst.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                ),
            )
        }
        prevPin = pin
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(burst.value)
            .clip(shape)
            .btGlass(strong = true, shape = shape)
            .padding(horizontal = BluetrackTokens.Sp5, vertical = BluetrackTokens.Sp4),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp2),
        ) {
            // Title row: caption + session number on the left, GATT
            // chip on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Renamed "PAIRING PIN" → "FEEDBACK PIN" because
                    // this is NOT the system BR/EDR pairing PIN.
                    // It is the per-session secret the encrypted BLE
                    // correction-packet channel uses to authenticate
                    // the host. Calling it "pairing" misled users
                    // into looking for it during Bluetooth pair on
                    // the host side, where it has no role.
                    Text(
                        text = "FEEDBACK PIN",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.fg2,
                    )
                    Text(
                        text = "session #$session",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = palette.fg3,
                    )
                }
                if (gattOpen) {
                    Chip(text = "GATT open", kind = ChipKind.Live, leading = { Pulse(size = 2.5.dp) })
                } else {
                    Chip(text = "GATT closed", kind = ChipKind.Calm)
                }
            }

            if (gattOpen && pin != null) {
                // 6 mono digits, tap to copy.
                val digits = pin.padEnd(6, '·').take(6).toCharArray()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                        .clickable {
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            copyToClipboard(context, pin)
                            copied = true
                        }.padding(vertical = BluetrackTokens.Sp2),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    digits.forEach { c ->
                        Text(
                            text = c.toString(),
                            modifier = Modifier.weight(1f),
                            color = palette.mintBright,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "--pin $pin",
                        modifier = Modifier
                            .background(
                                Color.White.copy(alpha = 0.04f),
                                RoundedCornerShape(8.dp),
                            ).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = palette.fg2,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = if (copied) "copied · clears in ${secs}s" else "tap to copy",
                        color = if (copied) palette.mintBright else palette.fg3,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    // Tell the user what to actually do with the
                    // digits. Earlier copy implied a process (PIN
                    // rotation, clipboard clearing) but never said
                    // *where* the PIN is used.
                    text = "Enter this on your Mac / PC feedback receiver to authorize encrypted " +
                        "correction packets. A new PIN is generated every session. " +
                        "Clipboard auto-clears after 30 s.",
                    color = palette.fg3,
                    fontSize = 11.sp,
                )
            } else {
                // GATT closed placeholder.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = BluetrackTokens.Sp3),
                    verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp1),
                ) {
                    Text(
                        text = "— — — — — —",
                        color = palette.fg3,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 26.sp,
                        letterSpacing = 6.sp,
                    )
                    Text(
                        text = "A 6-digit feedback PIN appears here when a host opens the encrypted " +
                            "BLE channel. Enter it on the host to authorize correction packets — " +
                            "this is separate from the system Bluetooth pairing prompt.",
                        color = palette.fg2,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, pin: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Bluetrack pairing PIN", pin))
}

private fun clearClipboard(context: Context) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Bluetrack pairing PIN", ""))
}
