package dev.xd.bluetrack.ui.keyboard

import dev.xd.bluetrack.engine.HidKeys

/**
 * Pure, unit-testable core of the keyboard relay: the composing-region
 * diff and the ASCII→HID keycode map. Kept free of Android types so the
 * fiddly bits (autocorrect deltas, shifted symbols) carry JVM tests —
 * `KeyboardRelay` / `HidRelayEditText` only wire these into the IME and
 * the HID transport.
 */
object KeyRelayCodec {
    /** Backspaces to send, then text to type, turning [old] into [new]. */
    data class Delta(
        val backspaces: Int,
        val text: String,
    )

    /** Minimal common-prefix delta between two composing strings. */
    fun delta(old: String, new: String): Delta {
        var common = 0
        val max = minOf(old.length, new.length)
        while (common < max && old[common] == new[common]) common++
        val text = if (new.length > common) new.substring(common) else ""
        return Delta(backspaces = old.length - common, text = text)
    }

    /**
     * Map an ASCII character to `[modifier, keycode]` (US layout), or
     * `null` when it has no boot-keyboard representation (non-ASCII).
     */
    fun charToHid(c: Char): IntArray? {
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
}
