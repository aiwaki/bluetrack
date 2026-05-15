package dev.xd.bluetrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickOverlayStateTest {
    @Test
    fun centeredInputsReportIdle() {
        val state = stickOverlayState(stickX = 0, stickY = 0)

        assertEquals(StickDeflection.IDLE, state.deflection)
        assertEquals("+0", state.xLabel)
        assertEquals("+0", state.yLabel)
        assertEquals(0f, state.normalizedX, 1e-6f)
        assertEquals(0f, state.normalizedY, 1e-6f)
    }

    @Test
    fun smallDiagonalStaysInIdleZone() {
        val state = stickOverlayState(stickX = 10, stickY = 10)

        assertEquals(StickDeflection.IDLE, state.deflection)
    }

    @Test
    fun moderateDeflectionIsLight() {
        val state = stickOverlayState(stickX = 50, stickY = 0)

        assertEquals(StickDeflection.LIGHT, state.deflection)
    }

    @Test
    fun fullAxisDeflectionIsStrong() {
        assertEquals(StickDeflection.STRONG, stickOverlayState(stickX = 100, stickY = 0).deflection)
        assertEquals(StickDeflection.STRONG, stickOverlayState(stickX = -127, stickY = 0).deflection)
    }

    @Test
    fun diagonalAddsIntoStrongZone() {
        // 90 on each axis sums to magnitude ~127 (sqrt(2) * 90), past the strong threshold.
        assertEquals(StickDeflection.STRONG, stickOverlayState(stickX = 90, stickY = 90).deflection)
    }

    @Test
    fun outOfRangeInputClampsAndStaysStrong() {
        val state = stickOverlayState(stickX = 1_000, stickY = -1_000)

        assertEquals(StickDeflection.STRONG, state.deflection)
        assertEquals("+127", state.xLabel)
        assertEquals("-127", state.yLabel)
        assertEquals(1f, state.normalizedX, 1e-6f)
        assertEquals(-1f, state.normalizedY, 1e-6f)
    }

    @Test
    fun signedLabelsCoverPositiveZeroAndNegative() {
        val negative = stickOverlayState(stickX = -50, stickY = 25)
        assertEquals("-50", negative.xLabel)
        assertEquals("+25", negative.yLabel)
    }

    @Test
    fun deflectionLabelsMatchEachLevel() {
        assertEquals("Stick idle", stickDeflectionLabel(StickDeflection.IDLE))
        assertEquals("Stick nudge", stickDeflectionLabel(StickDeflection.LIGHT))
        assertEquals("Stick strong", stickDeflectionLabel(StickDeflection.STRONG))
    }

    @Test
    fun normalizedAxesStayWithinUnitRange() {
        listOf(-127, -64, 0, 64, 127).forEach { x ->
            listOf(-127, -64, 0, 64, 127).forEach { y ->
                val state = stickOverlayState(x, y)
                assertTrue("normalizedX out of range for ($x,$y)", state.normalizedX in -1f..1f)
                assertTrue("normalizedY out of range for ($x,$y)", state.normalizedY in -1f..1f)
            }
        }
    }
}
