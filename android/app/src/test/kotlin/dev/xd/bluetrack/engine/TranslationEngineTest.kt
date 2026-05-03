package dev.xd.bluetrack.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
