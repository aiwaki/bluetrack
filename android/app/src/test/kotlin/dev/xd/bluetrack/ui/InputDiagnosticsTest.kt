package dev.xd.bluetrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDiagnosticsTest {
    @Test
    fun recordsTouchPacerQueueAndHidSendPeaks() {
        val logs = mutableListOf<String>()
        val diagnostics =
            InputDiagnostics(
                logger = { logs += it },
                touchGapWarningMs = 10L,
                pacerGapWarningMs = 10L,
                queueLatencyWarningMs = 10L,
                outputQueueLatencyWarningMs = 10L,
                hidSendWarningMs = 1L,
                warningCooldownMs = 0L,
            )

        diagnostics.recordTouch(100L)
        diagnostics.recordTouch(120L)
        diagnostics.recordPacerTick(120L)
        diagnostics.recordPacerTick(140L)
        diagnostics.recordFrame(nowMs = 150L, queuedAtMs = 120L)
        diagnostics.recordOutputFrame(nowMs = 155L, queuedAtMs = 120L)
        diagnostics.recordHidSend(durationNs = 2_000_000L, nowMs = 151L)

        val snapshot = diagnostics.snapshot()
        assertEquals(1, snapshot.frames)
        assertEquals(1, snapshot.touchGapWarnings)
        assertEquals(1, snapshot.pacerGapWarnings)
        assertEquals(1, snapshot.queueLatencyWarnings)
        assertEquals(1, snapshot.outputQueueLatencyWarnings)
        assertEquals(1, snapshot.hidSendWarnings)
        assertEquals(20L, snapshot.maxTouchGapMs)
        assertEquals(20L, snapshot.maxPacerGapMs)
        assertEquals(30L, snapshot.maxQueueLatencyMs)
        assertEquals(35L, snapshot.maxOutputQueueLatencyMs)
        assertEquals(2L, snapshot.maxHidSendMs)
        assertTrue(logs.any { it.contains("Input jank") })
    }

    @Test
    fun throttlesWarningLogs() {
        val logs = mutableListOf<String>()
        val diagnostics =
            InputDiagnostics(
                logger = { logs += it },
                touchGapWarningMs = 10L,
                warningCooldownMs = 100L,
            )

        diagnostics.recordTouch(0L)
        diagnostics.recordTouch(20L)
        diagnostics.recordTouch(40L)

        assertEquals(1, logs.size)
        assertEquals(2, diagnostics.snapshot().touchGapWarnings)
    }

    @Test
    fun resetTouchClockIgnoresIdleGapBetweenGestures() {
        val logs = mutableListOf<String>()
        val diagnostics =
            InputDiagnostics(
                logger = { logs += it },
                touchGapWarningMs = 10L,
                warningCooldownMs = 0L,
            )

        diagnostics.recordTouch(0L)
        diagnostics.resetTouchClock()
        diagnostics.recordTouch(1_000L)

        assertEquals(0, logs.size)
        assertEquals(0, diagnostics.snapshot().touchGapWarnings)
        assertEquals(0L, diagnostics.snapshot().maxTouchGapMs)
    }
}
