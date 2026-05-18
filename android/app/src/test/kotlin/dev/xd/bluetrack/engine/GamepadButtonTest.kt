package dev.xd.bluetrack.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the gamepad button + hat plumbing added in step
 * 9c. Verifies the per-label bit layout, latch semantics across
 * press / release / hold, and the unknown-label fallback that
 * `MainViewModel` relies on.
 */
class GamepadButtonTest {
    private fun engine(): TranslationEngine =
        TranslationEngine(scope = CoroutineScope(Dispatchers.Unconfined))

    private fun captured(): MutableList<ByteArray> = mutableListOf()

    @Test
    fun maskMapCoversCanonicalXboxButtons() {
        val masks = GamepadReportFormat.BUTTON_MASKS
        val expectLow = setOf("A", "B", "X", "Y", "LB", "RB", "LT", "RT")
        val expectHigh = setOf("BACK", "START", "L3", "R3", "GUIDE")
        expectLow.forEach { assertEquals("$it in low byte", 0, masks.getValue(it).first) }
        expectHigh.forEach { assertEquals("$it in high byte", 1, masks.getValue(it).first) }
        // No bit collisions across the low byte.
        val lowBits = expectLow.map { masks.getValue(it).second }
        assertEquals(lowBits.size, lowBits.toSet().size)
        // Reserved discovery bit (0x80 high) must not appear in
        // any game-button mask.
        masks.values.forEach { (index, mask) ->
            assertFalse(
                "discovery bit overlap",
                index == GamepadReportFormat.BUTTON_HIGH_INDEX && (mask and 0x80) != 0,
            )
        }
    }

    @Test
    fun pressSetsBitReleaseClearsBit() {
        val sent = captured()
        val engine = engine()
        engine.setGamepadButton("A", pressed = true) { sent.add(it.copyOf()) }
        engine.setGamepadButton("A", pressed = false) { sent.add(it.copyOf()) }
        assertEquals(2, sent.size)
        assertEquals(0x01, sent[0][0].toInt() and 0xFF)
        assertEquals(0x00, sent[1][0].toInt() and 0xFF)
    }

    @Test
    fun multipleButtonsLatchSimultaneously() {
        val sent = captured()
        val engine = engine()
        engine.setGamepadButton("A", pressed = true) { sent.add(it.copyOf()) }
        engine.setGamepadButton("B", pressed = true) { sent.add(it.copyOf()) }
        engine.setGamepadButton("LB", pressed = true) { sent.add(it.copyOf()) }
        // After 3 presses, low byte should carry A | B | LB.
        val last = sent.last()
        assertEquals(0x01 or 0x02 or 0x10, last[0].toInt() and 0xFF)
        // High byte still clear.
        assertEquals(0x00, last[1].toInt() and 0xFF)
        // Releasing A clears only A, B + LB remain.
        engine.setGamepadButton("A", pressed = false) { sent.add(it.copyOf()) }
        val afterReleaseA = sent.last()
        assertEquals(0x02 or 0x10, afterReleaseA[0].toInt() and 0xFF)
    }

    @Test
    fun unknownLabelReturnsFalseAndDoesNotEmit() {
        val sent = captured()
        val engine = engine()
        val ok = engine.setGamepadButton("XYZZY", pressed = true) { sent.add(it.copyOf()) }
        assertFalse(ok)
        assertTrue("no report emitted for unknown label", sent.isEmpty())
    }

    @Test
    fun hatByteSettableEightIsNeutral() {
        val sent = captured()
        val engine = engine()
        engine.setGamepadHat(2) { sent.add(it.copyOf()) }
        assertEquals(2, sent.last()[GamepadReportFormat.HAT_INDEX].toInt())
        engine.setGamepadHat(8) { sent.add(it.copyOf()) }
        assertEquals(8, sent.last()[GamepadReportFormat.HAT_INDEX].toInt())
    }

    @Test
    fun buttonPersistsAcrossHatUpdate() {
        val sent = captured()
        val engine = engine()
        engine.setGamepadButton("Y", pressed = true) { sent.add(it.copyOf()) }
        engine.setGamepadHat(0) { sent.add(it.copyOf()) }
        // After hat update, Y bit (0x08 low) must still be set.
        val last = sent.last()
        assertEquals(0x08, last[0].toInt() and 0xFF)
        assertEquals(0, last[GamepadReportFormat.HAT_INDEX].toInt())
    }

    @Test
    fun highByteButtonsDoNotCollideWithDiscoveryBit() {
        val sent = captured()
        val engine = engine()
        listOf("BACK", "START", "L3", "R3", "GUIDE").forEach { label ->
            engine.setGamepadButton(label, pressed = true) { sent.add(it.copyOf()) }
        }
        val last = sent.last()
        val highByte = last[1].toInt() and 0xFF
        // All five high-byte buttons fold into bits 0..4.
        assertEquals(0x01 or 0x02 or 0x04 or 0x08 or 0x10, highByte)
        // Discovery bit (0x80) remains untouched.
        assertFalse("discovery bit must stay clear", (highByte and 0x80) != 0)
    }

    @Test
    fun neutralReportBaselineMatchesExpectedShape() {
        // Sanity: a fresh engine emits the canonical neutral
        // report on first button release (idempotent).
        val sent = captured()
        val engine = engine()
        engine.setGamepadButton("A", pressed = false) { sent.add(it.copyOf()) }
        assertArrayEquals(GamepadReportFormat.neutralReport(), sent.last())
    }
}
