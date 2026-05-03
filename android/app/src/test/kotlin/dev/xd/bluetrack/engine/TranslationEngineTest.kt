package dev.xd.bluetrack.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationEngineTest {
    @Test
    fun mouseModeAppliesCorrectionAndClampsToHidRange() {
        val engine = TranslationEngine(TestScope())
        val reports = mutableListOf<ByteArray>()

        engine.updateCorrection(10f, -4f)
        engine.processMouseToStick(200f, -200f, HidMode.MOUSE) { report ->
            reports += report.copyOf()
        }

        assertArrayEquals(byteArrayOf(0, 127.toByte(), (-127).toByte(), 0), reports.single())
    }

    @Test
    fun mouseModeAccumulatesFractionalDeltas() {
        val engine = TranslationEngine(TestScope())
        val reports = mutableListOf<ByteArray>()

        engine.processMouseToStick(0.4f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }
        engine.processMouseToStick(0.4f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }
        engine.processMouseToStick(0.4f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), reports[0])
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), reports[1])
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), reports[2])
    }

    @Test
    fun mouseModeCarriesOverflowAfterClamp() {
        val engine = TranslationEngine(TestScope())
        val reports = mutableListOf<ByteArray>()

        engine.processMouseToStick(200f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }
        engine.processMouseToStick(0f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }

        assertArrayEquals(byteArrayOf(0, 127.toByte(), 0, 0), reports[0])
        assertArrayEquals(byteArrayOf(0, 73, 0, 0), reports[1])
    }

    @Test
    fun telemetryIsThrottledWithoutDroppingReports() {
        var now = 0L
        val engine = TranslationEngine(TestScope(), nowMs = { now })
        val reports = mutableListOf<ByteArray>()

        engine.processMouseToStick(1f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }
        now = 40L
        engine.processMouseToStick(2f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }
        now = 100L
        engine.processMouseToStick(3f, 0f, HidMode.MOUSE) { report -> reports += report.copyOf() }

        assertEquals(3, reports.size)
        assertEquals(Telemetry(rawX = 3, rawY = 0, stickX = 6, stickY = 0), engine.telemetry.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun gamepadModeWritesAxesAfterButtonBytesAndResetsDeadman() = runTest {
        val engine = TranslationEngine(this)
        val reports = mutableListOf<ByteArray>()

        engine.sensitivity = 2f
        engine.processMouseToStick(3f, -4f, HidMode.GAMEPAD) { report ->
            reports += report.copyOf()
        }

        assertArrayEquals(byteArrayOf(0, 0, 6, (-8).toByte(), 0, 0), reports.single())

        advanceTimeBy(20)
        runCurrent()

        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0), reports.last())
    }
}
