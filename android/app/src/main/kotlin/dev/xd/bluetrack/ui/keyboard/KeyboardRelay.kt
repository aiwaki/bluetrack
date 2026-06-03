package dev.xd.bluetrack.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.engine.HidKeys
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * System-keyboard relay. Instead of a bespoke on-screen keyboard, we
 * focus an invisible text field so the user's own OS keyboard (Gboard,
 * etc.) opens — bringing swipe, autocorrect, voice, symbols and
 * languages for free — and forward whatever it produces to the host as
 * HID keyboard reports.
 *
 * Soft keyboards commit *text*, not keystrokes, so we diff the field's
 * value on every change and translate the delta:
 *  - characters appended → tap their keycodes ([charToHid]),
 *  - characters removed → tap Backspace that many times.
 * A zero-width anchor keeps the buffer non-empty so a Backspace at the
 * very start still reaches the host instead of being swallowed.
 *
 * Limitation: a boot HID keyboard sends US-layout keycodes, so only
 * ASCII maps. Non-ASCII (Cyrillic, emoji, accents) is dropped — that
 * needs a host-specific Unicode path, tracked separately.
 */
@Composable
fun KeyboardRelay(
    onType: (modifier: Int, keycode: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var value by remember { mutableStateOf(ANCHOR) }
    var active by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .btGlass(strong = false, shape = shape)
                .clickable {
                    if (active) {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    } else {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }.padding(BluetrackTokens.Sp4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                    .background(palette.cool.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "⌨", color = palette.cool, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (active) "Keyboard active" else "Keyboard",
                    color = palette.fg0,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (active) {
                        "Type on your phone keyboard — keys go to the host"
                    } else {
                        "Tap to type to the host with your system keyboard"
                    },
                    color = palette.fg2,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = if (active) "HIDE" else "OPEN ↑",
                color = if (active) palette.crit else palette.mintBright,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
            )
        }

        // Invisible capture field — holds focus so the OS keyboard
        // stays up; every edit is diffed into HID taps. 1 dp + alpha 0
        // keeps it off-screen without losing focusability.
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                if (!raw.startsWith(ANCHOR)) {
                    // The anchor itself was deleted → Backspace past the
                    // buffer start; forward it and re-seat the anchor.
                    onType(0, HidKeys.KC_BACKSPACE)
                    value = ANCHOR
                } else {
                    relayDiff(value, raw, onType)
                    value = raw
                }
            },
            singleLine = false,
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    active = state.isFocused
                    if (!state.isFocused) value = ANCHOR
                },
        )
    }
}

/** Zero-width space kept at index 0 so the buffer is never empty. */
private const val ANCHOR = "​"

/**
 * Forward the delta between [old] and [new] as HID taps: Backspace for
 * each trailing character removed past the common prefix, then a tap
 * per character added. Both strings start with [ANCHOR].
 */
private fun relayDiff(
    old: String,
    new: String,
    onType: (Int, Int) -> Unit,
) {
    var common = 0
    val max = minOf(old.length, new.length)
    while (common < max && old[common] == new[common]) common++
    repeat(old.length - common) { onType(0, HidKeys.KC_BACKSPACE) }
    for (i in common until new.length) {
        charToHid(new[i])?.let { onType(it[0], it[1]) }
    }
}

/**
 * Map an ASCII character to `[modifier, keycode]` (US layout), or
 * `null` when it has no boot-keyboard representation (anchor,
 * non-ASCII).
 */
private fun charToHid(c: Char): IntArray? {
    val s = HidKeys.MOD_LSHIFT
    return when (c) {
        in 'a'..'z' -> intArrayOf(0, HidKeys.KC_A + (c - 'a'))
        in 'A'..'Z' -> intArrayOf(s, HidKeys.KC_A + (c - 'A'))
        in '1'..'9' -> intArrayOf(0, HidKeys.KC_1 + (c - '1'))
        '0' -> intArrayOf(0, HidKeys.KC_0)
        ' ' -> intArrayOf(0, HidKeys.KC_SPACE)
        '\n' -> intArrayOf(0, HidKeys.KC_ENTER)
        '\t' -> intArrayOf(0, HidKeys.KC_TAB)
        '-' -> intArrayOf(0, HidKeys.KC_MINUS)
        '_' -> intArrayOf(s, HidKeys.KC_MINUS)
        '=' -> intArrayOf(0, HidKeys.KC_EQUAL)
        '+' -> intArrayOf(s, HidKeys.KC_EQUAL)
        '[' -> intArrayOf(0, HidKeys.KC_LBRACKET)
        '{' -> intArrayOf(s, HidKeys.KC_LBRACKET)
        ']' -> intArrayOf(0, HidKeys.KC_RBRACKET)
        '}' -> intArrayOf(s, HidKeys.KC_RBRACKET)
        '\\' -> intArrayOf(0, HidKeys.KC_BACKSLASH)
        '|' -> intArrayOf(s, HidKeys.KC_BACKSLASH)
        ';' -> intArrayOf(0, HidKeys.KC_SEMICOLON)
        ':' -> intArrayOf(s, HidKeys.KC_SEMICOLON)
        '\'' -> intArrayOf(0, HidKeys.KC_QUOTE)
        '"' -> intArrayOf(s, HidKeys.KC_QUOTE)
        '`' -> intArrayOf(0, HidKeys.KC_GRAVE)
        '~' -> intArrayOf(s, HidKeys.KC_GRAVE)
        ',' -> intArrayOf(0, HidKeys.KC_COMMA)
        '<' -> intArrayOf(s, HidKeys.KC_COMMA)
        '.' -> intArrayOf(0, HidKeys.KC_PERIOD)
        '>' -> intArrayOf(s, HidKeys.KC_PERIOD)
        '/' -> intArrayOf(0, HidKeys.KC_SLASH)
        '?' -> intArrayOf(s, HidKeys.KC_SLASH)
        '!' -> intArrayOf(s, HidKeys.KC_1)
        '@' -> intArrayOf(s, HidKeys.KC_2)
        '#' -> intArrayOf(s, HidKeys.KC_3)
        '$' -> intArrayOf(s, HidKeys.KC_4)
        '%' -> intArrayOf(s, HidKeys.KC_5)
        '^' -> intArrayOf(s, HidKeys.KC_6)
        '&' -> intArrayOf(s, HidKeys.KC_7)
        '*' -> intArrayOf(s, HidKeys.KC_8)
        '(' -> intArrayOf(s, HidKeys.KC_9)
        ')' -> intArrayOf(s, HidKeys.KC_0)
        else -> null
    }
}
