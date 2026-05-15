package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeCardStateTest {
    @Test
    fun cardsAreOrderedMouseThenGamepad() {
        val states = modeCardStates(currentMode = HidMode.GAMEPAD, hostConnected = true)

        assertEquals(2, states.size)
        assertEquals(HidMode.MOUSE, states[0].mode)
        assertEquals(HidMode.GAMEPAD, states[1].mode)
    }

    @Test
    fun selectedCardReportsActiveWhenHostConnected() {
        val gamepad =
            modeCardStates(currentMode = HidMode.GAMEPAD, hostConnected = true)
                .first { it.mode == HidMode.GAMEPAD }

        assertTrue(gamepad.isSelected)
        assertEquals("Active", gamepad.statusLabel)
    }

    @Test
    fun selectedCardReportsSelectedWhenHostMissing() {
        val mouse =
            modeCardStates(currentMode = HidMode.MOUSE, hostConnected = false)
                .first { it.mode == HidMode.MOUSE }

        assertTrue(mouse.isSelected)
        assertEquals("Selected", mouse.statusLabel)
    }

    @Test
    fun unselectedCardAlwaysShowsTapToSwitch() {
        val whenConnected =
            modeCardStates(currentMode = HidMode.MOUSE, hostConnected = true)
                .first { it.mode == HidMode.GAMEPAD }
        val whenIdle =
            modeCardStates(currentMode = HidMode.GAMEPAD, hostConnected = false)
                .first { it.mode == HidMode.MOUSE }

        assertFalse(whenConnected.isSelected)
        assertEquals("Tap to switch", whenConnected.statusLabel)
        assertFalse(whenIdle.isSelected)
        assertEquals("Tap to switch", whenIdle.statusLabel)
    }

    @Test
    fun titlesAndTaglinesAreStable() {
        val states = modeCardStates(currentMode = HidMode.MOUSE, hostConnected = false)
        val mouse = states.first { it.mode == HidMode.MOUSE }
        val gamepad = states.first { it.mode == HidMode.GAMEPAD }

        assertEquals("Mouse", mouse.title)
        assertEquals("Cursor and click", mouse.tagline)
        assertEquals("Gamepad", gamepad.title)
        assertEquals("Buttons and sticks", gamepad.tagline)
    }

    @Test
    fun exactlyOneCardIsSelectedAcrossAllCombinations() {
        listOf(true, false).forEach { connected ->
            HidMode.values().forEach { mode ->
                val states = modeCardStates(currentMode = mode, hostConnected = connected)
                assertEquals(
                    "currentMode=$mode hostConnected=$connected",
                    1,
                    states.count { it.isSelected },
                )
            }
        }
    }
}
