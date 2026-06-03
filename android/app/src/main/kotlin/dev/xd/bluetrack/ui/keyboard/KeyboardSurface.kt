package dev.xd.bluetrack.ui.keyboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.engine.HidKeys
import dev.xd.bluetrack.ui.shell.AuroraBackground
import dev.xd.bluetrack.ui.shell.AuroraState
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Full on-screen keyboard surface (portrait). Forwards every keypress
 * to the host as a HID keyboard report (report ID 3) via [onKey].
 *
 * Model:
 *  - Normal keys fire [onKey] with the currently-armed modifier mask
 *    and the key's Usage-0x07 keycode, then clear the one-shot
 *    modifiers (Shift / Ctrl / Alt / ⌘). Tap a modifier to arm it for
 *    the next key; tap again to disarm. Multiple modifiers stack
 *    (⌘ + Shift + 4). This mirrors the way phone keyboards treat
 *    Shift, and lets the host see real chords like ⌘C / ⌘⇧4.
 *  - Shift also re-labels the keys (letters upper-case, digits → their
 *    shifted symbol) so what you see is what the host receives under a
 *    US layout.
 *
 * Host-layout note: we send raw keycodes + Shift, so the symbol that
 * lands depends on the host's keyboard layout (US assumed for the
 * shift labels). That matches how every other HID keyboard behaves.
 */
@Composable
fun KeyboardSurface(
    hostName: String,
    onExit: () -> Unit,
    onKey: (modifier: Int, keycode: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val darkTheme = isSystemInDarkTheme()
    var mods by remember { mutableIntStateOf(0) }
    val shiftOn = (mods and HidKeys.MOD_LSHIFT) != 0

    Box(modifier = modifier.fillMaxSize()) {
        AuroraBackground(
            modifier = Modifier.fillMaxSize(),
            state = AuroraState.Settings,
            darkTheme = darkTheme,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TopRail(hostName = hostName, onExit = onExit)
            Spacer(modifier = Modifier.weight(1f))
            Keys(
                shiftOn = shiftOn,
                armedMods = mods,
                onTap = { code ->
                    onKey(mods, code)
                    mods = 0
                },
                onToggleMod = { bit -> mods = mods xor bit },
            )
        }
    }
}

// ─── Top rail (exit + status) ───────────────────────────────

@Composable
private fun TopRail(
    hostName: String,
    onExit: () -> Unit,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeyButton(
            label = "‹ DONE",
            modifier = Modifier.height(34.dp),
            onTap = onExit,
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.crit),
        )
        Text(
            text = "KEYBOARD · $hostName",
            color = palette.fg2,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
        )
    }
}

// ─── Key rows ───────────────────────────────────────────────

private data class KeyDef(
    val base: String,
    val shifted: String,
    val keycode: Int,
    val weight: Float = 1f,
)

@Composable
private fun Keys(
    shiftOn: Boolean,
    armedMods: Int,
    onTap: (Int) -> Unit,
    onToggleMod: (Int) -> Unit,
) {
    val rowGap = Arrangement.spacedBy(6.dp)
    val numbers = listOf(
        KeyDef("1", "!", HidKeys.KC_1),
        KeyDef("2", "@", HidKeys.KC_2),
        KeyDef("3", "#", HidKeys.KC_3),
        KeyDef("4", "$", HidKeys.KC_4),
        KeyDef("5", "%", HidKeys.KC_5),
        KeyDef("6", "^", HidKeys.KC_6),
        KeyDef("7", "&", HidKeys.KC_7),
        KeyDef("8", "*", HidKeys.KC_8),
        KeyDef("9", "(", HidKeys.KC_9),
        KeyDef("0", ")", HidKeys.KC_0),
    )
    val rowQ = lettersRow("QWERTYUIOP", intArrayOf(0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13))
    val rowA = lettersRow("ASDFGHJKL", intArrayOf(0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F))
    val rowZ = lettersRow("ZXCVBNM", intArrayOf(0x1D, 0x1B, 0x06, 0x19, 0x05, 0x11, 0x10))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Control strip: Esc · Tab · arrows.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = rowGap) {
            KeyButton("Esc", Modifier.weight(1.4f)) { onTap(HidKeys.KC_ESC) }
            KeyButton("Tab", Modifier.weight(1.4f)) { onTap(HidKeys.KC_TAB) }
            Spacer(Modifier.weight(2f))
            KeyButton("←", Modifier.weight(1f)) { onTap(HidKeys.KC_LEFT) }
            KeyButton("↑", Modifier.weight(1f)) { onTap(HidKeys.KC_UP) }
            KeyButton("↓", Modifier.weight(1f)) { onTap(HidKeys.KC_DOWN) }
            KeyButton("→", Modifier.weight(1f)) { onTap(HidKeys.KC_RIGHT) }
        }
        // Numbers.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = rowGap) {
            numbers.forEach { k ->
                KeyButton(if (shiftOn) k.shifted else k.base, Modifier.weight(1f)) { onTap(k.keycode) }
            }
        }
        // Q row.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = rowGap) {
            rowQ.forEach { k ->
                KeyButton(if (shiftOn) k.base else k.shifted, Modifier.weight(1f)) { onTap(k.keycode) }
            }
        }
        // A row (inset).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = rowGap) {
            Spacer(Modifier.weight(0.5f))
            rowA.forEach { k ->
                KeyButton(if (shiftOn) k.base else k.shifted, Modifier.weight(1f)) { onTap(k.keycode) }
            }
            Spacer(Modifier.weight(0.5f))
        }
        // Shift + Z row + Backspace.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = rowGap) {
            KeyButton(
                label = "⇧",
                modifier = Modifier.weight(1.6f),
                active = shiftOn,
                onTap = { onToggleMod(HidKeys.MOD_LSHIFT) },
            )
            rowZ.forEach { k ->
                KeyButton(if (shiftOn) k.base else k.shifted, Modifier.weight(1f)) { onTap(k.keycode) }
            }
            KeyButton("⌫", Modifier.weight(1.6f)) { onTap(HidKeys.KC_BACKSPACE) }
        }
        // Modifiers + punctuation + space + enter.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = rowGap) {
            KeyButton(
                label = "ctrl",
                modifier = Modifier.weight(1.3f),
                active = (armedMods and HidKeys.MOD_LCTRL) != 0,
                onTap = { onToggleMod(HidKeys.MOD_LCTRL) },
            )
            KeyButton(
                label = "⌥",
                modifier = Modifier.weight(1f),
                active = (armedMods and HidKeys.MOD_LALT) != 0,
                onTap = { onToggleMod(HidKeys.MOD_LALT) },
            )
            KeyButton(
                label = "⌘",
                modifier = Modifier.weight(1.2f),
                active = (armedMods and HidKeys.MOD_LGUI) != 0,
                onTap = { onToggleMod(HidKeys.MOD_LGUI) },
            )
            KeyButton(",", Modifier.weight(1f)) { onTap(HidKeys.KC_COMMA) }
            KeyButton("space", Modifier.weight(3.5f)) { onTap(HidKeys.KC_SPACE) }
            KeyButton(".", Modifier.weight(1f)) { onTap(HidKeys.KC_PERIOD) }
            KeyButton("↵", Modifier.weight(1.8f)) { onTap(HidKeys.KC_ENTER) }
        }
    }
}

private fun lettersRow(labels: String, codes: IntArray): List<KeyDef> =
    labels.mapIndexed { i, c -> KeyDef(c.toString(), c.lowercaseChar().toString(), codes[i]) }

// ─── Single key ─────────────────────────────────────────────

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onTap: () -> Unit,
) {
    val palette = BluetrackTheme.palette
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "key-press",
    )
    val shape = RoundedCornerShape(9.dp)
    val fill = when {
        pressed -> palette.crit.copy(alpha = 0.85f)
        active -> palette.crit.copy(alpha = 0.30f)
        else -> palette.glassBgStrong
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .scale(pressScale)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, if (active) palette.crit else palette.glassBorder, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) onTap()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (pressed) Color.White else palette.fg0,
            fontSize = if (label.length > 1) 12.sp else 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
