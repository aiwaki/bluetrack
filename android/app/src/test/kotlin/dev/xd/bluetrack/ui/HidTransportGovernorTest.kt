package dev.xd.bluetrack.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HidTransportGovernorTest {
    @Test
    fun normalTransportAllowsImmediateFirstSendAndShortCatchUpPacing() {
        val governor = HidTransportGovernor(baseIntervalMs = 6L)

        assertEquals(0L, governor.delayBeforeSend(nowMs = 100L))

        governor.recordSend(durationMs = 1L, finishedAtMs = 101L)

        assertEquals(6L, governor.delayBeforeSend(nowMs = 101L))
        assertEquals(1L, governor.delayBeforeSend(nowMs = 106L))
        assertEquals(0L, governor.delayBeforeSend(nowMs = 107L))
    }

    @Test
    fun slowSendAppliesTemporaryBackoff() {
        val governor = HidTransportGovernor(
            baseIntervalMs = 6L,
            moderateBackoffIntervalMs = 10L,
            moderateSendMs = 12L,
            moderateBackoffWindowMs = 500L,
        )

        governor.recordSend(durationMs = 20L, finishedAtMs = 100L)

        assertEquals(10L, governor.delayBeforeSend(nowMs = 100L))
        assertEquals(1L, governor.delayBeforeSend(nowMs = 109L))
        assertEquals(0L, governor.delayBeforeSend(nowMs = 110L))
    }

    @Test
    fun severeSendUsesLongerBackoffAndRecoversAfterFastReports() {
        val governor = HidTransportGovernor(
            baseIntervalMs = 6L,
            severeBackoffIntervalMs = 16L,
            severeSendMs = 48L,
            severeBackoffWindowMs = 100L,
            fastRecoveryReports = 2,
        )

        governor.recordSend(durationMs = 90L, finishedAtMs = 100L)

        assertEquals(16L, governor.delayBeforeSend(nowMs = 100L))

        governor.recordSend(durationMs = 1L, finishedAtMs = 220L)
        assertEquals(16L, governor.delayBeforeSend(nowMs = 220L))

        governor.recordSend(durationMs = 1L, finishedAtMs = 240L)
        assertEquals(6L, governor.delayBeforeSend(nowMs = 240L))
    }

    @Test
    fun resetClearsBackoff() {
        val governor = HidTransportGovernor(severeSendMs = 48L, severeBackoffIntervalMs = 16L)

        governor.recordSend(durationMs = 90L, finishedAtMs = 100L)
        governor.reset()

        assertEquals(0L, governor.delayBeforeSend(nowMs = 100L))
    }
}
