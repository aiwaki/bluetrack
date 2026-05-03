package dev.xd.bluetrack.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadReportFormatTest {
    @Test
    fun neutralReportKeepsHatInNullPosition() {
        val report = GamepadReportFormat.neutralReport()

        assertEquals(GamepadReportFormat.LENGTH, report.size)
        assertEquals(GamepadReportFormat.HAT_NEUTRAL, report[GamepadReportFormat.HAT_INDEX])
        assertArrayEquals(byteArrayOf(0, 0, 8, 0, 0, 0, 0), report)
    }

    @Test
    fun wakeReportPressesOnlyPrimaryButton() {
        val report = GamepadReportFormat.buttonAWakeReport()

        assertEquals(1, report[GamepadReportFormat.BUTTON_LOW_INDEX].toInt())
        assertEquals(0, report[GamepadReportFormat.BUTTON_HIGH_INDEX].toInt())
        assertEquals(GamepadReportFormat.HAT_NEUTRAL, report[GamepadReportFormat.HAT_INDEX])
        assertArrayEquals(byteArrayOf(1, 0, 8, 0, 0, 0, 0), report)
    }
}
