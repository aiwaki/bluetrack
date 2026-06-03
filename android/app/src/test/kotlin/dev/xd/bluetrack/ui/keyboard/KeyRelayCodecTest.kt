package dev.xd.bluetrack.ui.keyboard

import dev.xd.bluetrack.engine.HidKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRelayCodecTest {
    @Test
    fun deltaAppendsNewSuffix() {
        val d = KeyRelayCodec.delta("", "abc")
        assertEquals(0, d.backspaces)
        assertEquals("abc", d.text)
    }

    @Test
    fun deltaGrowingComposingTypesOnlyTheNewChar() {
        val d = KeyRelayCodec.delta("cir", "circ")
        assertEquals(0, d.backspaces)
        assertEquals("c", d.text)
    }

    @Test
    fun deltaAutocorrectReplacesTailExactly() {
        // The circumtances → circumstances autocorrect: backspace the
        // wrong tail, type the corrected tail. No duplication.
        val d = KeyRelayCodec.delta("circumtances", "circumstances")
        assertEquals("circumtances".length - "circum".length, d.backspaces)
        assertEquals("stances", d.text)
    }

    @Test
    fun deltaPureDeletionTypesNothing() {
        val d = KeyRelayCodec.delta("abcd", "ab")
        assertEquals(2, d.backspaces)
        assertEquals("", d.text)
    }

    @Test
    fun deltaIdenticalIsNoOp() {
        val d = KeyRelayCodec.delta("hello", "hello")
        assertEquals(0, d.backspaces)
        assertEquals("", d.text)
    }

    @Test
    fun lettersMapWithShiftForUppercase() {
        assertArrayEquals(intArrayOf(0, HidKeys.KC_A), KeyRelayCodec.charToHid('a'))
        assertArrayEquals(intArrayOf(0, HidKeys.KC_Z), KeyRelayCodec.charToHid('z'))
        assertArrayEquals(intArrayOf(HidKeys.MOD_LSHIFT, HidKeys.KC_A), KeyRelayCodec.charToHid('A'))
        assertArrayEquals(intArrayOf(HidKeys.MOD_LSHIFT, HidKeys.KC_Z), KeyRelayCodec.charToHid('Z'))
    }

    @Test
    fun digitsAndShiftedSymbolsMap() {
        assertArrayEquals(intArrayOf(0, HidKeys.KC_1), KeyRelayCodec.charToHid('1'))
        assertArrayEquals(intArrayOf(0, HidKeys.KC_0), KeyRelayCodec.charToHid('0'))
        // Shifted number row.
        assertArrayEquals(intArrayOf(HidKeys.MOD_LSHIFT, HidKeys.KC_1), KeyRelayCodec.charToHid('!'))
        assertArrayEquals(intArrayOf(HidKeys.MOD_LSHIFT, HidKeys.KC_0), KeyRelayCodec.charToHid(')'))
    }

    @Test
    fun whitespaceAndPunctuationMap() {
        assertArrayEquals(intArrayOf(0, HidKeys.KC_SPACE), KeyRelayCodec.charToHid(' '))
        assertArrayEquals(intArrayOf(0, HidKeys.KC_ENTER), KeyRelayCodec.charToHid('\n'))
        assertArrayEquals(intArrayOf(0, HidKeys.KC_SLASH), KeyRelayCodec.charToHid('/'))
        assertArrayEquals(intArrayOf(HidKeys.MOD_LSHIFT, HidKeys.KC_SLASH), KeyRelayCodec.charToHid('?'))
    }

    @Test
    fun nonAsciiHasNoMapping() {
        assertNull(KeyRelayCodec.charToHid('ж'))
        assertNull(KeyRelayCodec.charToHid('é'))
        assertNull(KeyRelayCodec.charToHid('€'))
    }
}
