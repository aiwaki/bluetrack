package dev.xd.bluetrack.engine

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the keyboard HID report path (report ID 3, boot-protocol
 * layout) and the two-axis wheel report added for the Mac-trackpad
 * gesture release. Pure-Kotlin: the send callback is synchronous, so
 * no coroutine on the engine scope runs here.
 */
class TranslationEngineKeyboardTest {
    private fun engine() = TranslationEngine(TestScope())

    @Test
    fun tapKeyEmitsChordDownThenFullRelease() {
        val e = engine()
        val reports = mutableListOf<ByteArray>()
        e.tapKey(HidKeys.MOD_LGUI, HidKeys.KC_EQUAL) { reports.add(it.copyOf()) }

        assertEquals(2, reports.size)
        // Down: modifier byte set, reserved byte 0, first keycode slot filled.
        assertEquals(8, reports[0].size)
        assertEquals(HidKeys.MOD_LGUI.toByte(), reports[0][0])
        assertEquals(0.toByte(), reports[0][1])
        assertEquals(HidKeys.KC_EQUAL.toByte(), reports[0][2])
        // Up: every byte cleared.
        assertArrayEquals(ByteArray(8), reports[1])
    }

    @Test
    fun modifierOnlyChordSetsModifierWithNoKeycode() {
        val e = engine()
        val reports = mutableListOf<ByteArray>()
        e.tapKey(HidKeys.MOD_LCTRL, 0) { reports.add(it.copyOf()) }

        assertEquals(2, reports.size)
        assertEquals(HidKeys.MOD_LCTRL.toByte(), reports[0][0])
        assertEquals(0.toByte(), reports[0][2])
        assertArrayEquals(ByteArray(8), reports[1])
    }

    @Test
    fun simultaneousKeysRollOverCapsAtSix() {
        val e = engine()
        val reports = mutableListOf<ByteArray>()
        val codes = intArrayOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A) // 7 keys

        for (kc in codes) e.processKeyDown(0, kc) { reports.add(it.copyOf()) }

        // The 7th key is dropped (no emit) — boot-keyboard 6-key limit.
        assertEquals(6, reports.size)
        val last = reports.last()
        for (i in 0 until 6) assertEquals(codes[i].toByte(), last[2 + i])
    }

    @Test
    fun wheelEmitsVerticalThenHorizontalBytes() {
        val e = engine()
        val reports = mutableListOf<ByteArray>()

        e.processWheel(1f, 0f) { reports.add(it.copyOf()) }
        assertEquals(1, reports.size)
        assertEquals(5, reports[0].size)
        assertEquals(0.toByte(), reports[0][1]) // x delta
        assertEquals(0.toByte(), reports[0][2]) // y delta
        assertEquals(1.toByte(), reports[0][3]) // vertical wheel
        assertEquals(0.toByte(), reports[0][4]) // horizontal wheel (AC Pan)

        reports.clear()
        e.processWheel(0f, 1f) { reports.add(it.copyOf()) }
        assertEquals(1, reports.size)
        assertEquals(0.toByte(), reports[0][3])
        assertEquals(1.toByte(), reports[0][4])
    }

    @Test
    fun wheelWithBothAxesZeroEmitsNothing() {
        val e = engine()
        var count = 0
        e.processWheel(0f, 0f) { count++ }
        assertEquals(0, count)
    }

    @Test
    fun wheelCarriesFractionalResidueAcrossCalls() {
        val e = engine()
        val reports = mutableListOf<ByteArray>()

        // 0.4 rounds to 0 -> no emit, residue carried.
        e.processWheel(0.4f, 0f) { reports.add(it.copyOf()) }
        assertEquals(0, reports.size)

        // 0.4 + 0.4 carry = 0.8 -> rounds to one notch.
        e.processWheel(0.4f, 0f) { reports.add(it.copyOf()) }
        assertEquals(1, reports.size)
        assertEquals(1.toByte(), reports[0][3])
    }
}
