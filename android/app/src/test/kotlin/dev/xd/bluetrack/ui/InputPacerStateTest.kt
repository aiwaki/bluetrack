package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InputPacerStateTest {
    @Test
    fun emitsQueuedMotionAsFrame() {
        val pacer = InputPacerState()

        pacer.queue(dx = 4f, dy = -2f, mode = HidMode.MOUSE, nowMs = 0L)

        val frame = pacer.nextFrame(nowMs = 0L) as InputPacerDecision.Frame
        assertEquals(4f, frame.dx, 0.0001f)
        assertEquals(-2f, frame.dy, 0.0001f)
        assertEquals(HidMode.MOUSE, frame.mode)
    }

    @Test
    fun stopsAfterIdleWindowWithoutMotion() {
        val pacer = InputPacerState(idleStopMs = 120L)

        assertSame(InputPacerDecision.Wait, pacer.nextFrame(nowMs = 20L))
        assertSame(InputPacerDecision.Stop, pacer.nextFrame(nowMs = 121L))
    }

    @Test
    fun splitsLargeMotionIntoMicroFrames() {
        val pacer = InputPacerState(maxFrameDelta = 10f)

        pacer.queue(dx = 35f, dy = 0f, mode = HidMode.MOUSE, nowMs = 0L)

        val frame = pacer.nextFrame(nowMs = 0L) as InputPacerDecision.Frame
        assertEquals(8.75f, frame.dx, 0.0001f)
        assertEquals(0f, frame.dy, 0.0001f)
    }

    @Test
    fun spreadsMotionAfterJankGap() {
        val pacer = InputPacerState(tickMs = 8L, jankGapMs = 24L, maxFrameDelta = 14f, maxRecoveryFrames = 6)

        pacer.queue(dx = 1f, dy = 0f, mode = HidMode.MOUSE, nowMs = 0L)
        pacer.nextFrame(nowMs = 8L)
        pacer.queue(dx = 42f, dy = 0f, mode = HidMode.MOUSE, nowMs = 16L)

        val frame = pacer.nextFrame(nowMs = 80L) as InputPacerDecision.Frame
        assertEquals(6f, frame.dx, 0.0001f)
        assertEquals(5, frame.recoveryFramesRemaining)
    }

    @Test
    fun resetDropsPendingMotionAndUpdatesMode() {
        val pacer = InputPacerState()

        pacer.queue(dx = 20f, dy = 0f, mode = HidMode.MOUSE, nowMs = 0L)
        pacer.reset(HidMode.GAMEPAD)

        assertSame(InputPacerDecision.Stop, pacer.nextFrame(nowMs = 121L))
    }
}
