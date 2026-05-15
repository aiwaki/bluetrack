package dev.xd.bluetrack.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val now: Long = 1_000_000L

    @Test
    fun nullTimestampReturnsIdleLabel() {
        assertEquals("Idle", relativeAgeLabel(now = now, timestampMs = null))
    }

    @Test
    fun nullTimestampHonorsCustomIdleLabel() {
        assertEquals("—", relativeAgeLabel(now = now, timestampMs = null, idleLabel = "—"))
    }

    @Test
    fun zeroAndSubSecondDeltaReportsNow() {
        assertEquals("now", relativeAgeLabel(now = now, timestampMs = now))
        assertEquals("now", relativeAgeLabel(now = now, timestampMs = now - 1))
        assertEquals("now", relativeAgeLabel(now = now, timestampMs = now - 999))
    }

    @Test
    fun negativeDeltaFromClockSkewReportsNow() {
        assertEquals("now", relativeAgeLabel(now = now, timestampMs = now + 5_000))
    }

    @Test
    fun secondBoundaryUsesSeconds() {
        assertEquals("1s ago", relativeAgeLabel(now = now, timestampMs = now - 1_000))
        assertEquals("59s ago", relativeAgeLabel(now = now, timestampMs = now - 59_000))
    }

    @Test
    fun minuteBoundaryUsesMinutes() {
        assertEquals("1m ago", relativeAgeLabel(now = now, timestampMs = now - 60_000))
        assertEquals("59m ago", relativeAgeLabel(now = now, timestampMs = now - 59 * 60_000L))
    }

    @Test
    fun hourBoundaryUsesHours() {
        assertEquals("1h ago", relativeAgeLabel(now = now, timestampMs = now - 3_600_000))
        assertEquals("23h ago", relativeAgeLabel(now = now, timestampMs = now - 23 * 3_600_000L))
    }

    @Test
    fun dayBoundaryUsesDays() {
        assertEquals("1d ago", relativeAgeLabel(now = now, timestampMs = now - 86_400_000L))
        assertEquals("7d ago", relativeAgeLabel(now = now, timestampMs = now - 7 * 86_400_000L))
    }
}
