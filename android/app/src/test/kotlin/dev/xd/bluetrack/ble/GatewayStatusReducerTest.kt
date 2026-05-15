package dev.xd.bluetrack.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStatusReducerTest {
    private val baseStatus =
        GatewayStatus(
            hid = "Idle",
            feedback = "Idle",
            pairing = "Not discoverable",
            host = "MacBook Pro",
            error = "Stale error",
            compatibility = CompatibilitySnapshot(hidProfile = "Proxy ready"),
            events = emptyList(),
            reportsSent = 7,
            feedbackPackets = 3,
            rejectedFeedbackPackets = 1,
            lastInputSource = "Touchpad",
            lastInputAtMs = 1_000L,
            lastReportAtMs = 1_100L,
            lastFeedbackAtMs = 1_200L,
        )

    @Test
    fun emptyUpdateReturnsCurrentStatusWithoutEvent() {
        val reduction = reduceGatewayStatus(current = baseStatus, nowMs = 5_000L)

        assertEquals(baseStatus, reduction.status)
        assertNull(reduction.event)
    }

    @Test
    fun hidUpdateChangesOnlyHidLabel() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                hid = "HID ready (MOUSE)",
            )

        assertEquals("HID ready (MOUSE)", reduction.status.hid)
        assertEquals(baseStatus.copy(hid = "HID ready (MOUSE)"), reduction.status)
    }

    @Test
    fun compatibilityReplacesPriorSnapshot() {
        val nextSnapshot = CompatibilitySnapshot(hidProfile = "Composite active GAMEPAD")

        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                compatibility = nextSnapshot,
            )

        assertSame(nextSnapshot, reduction.status.compatibility)
    }

    @Test
    fun hostExplicitNullClearsHost() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                host = null,
            )

        assertNull(reduction.status.host)
    }

    @Test
    fun hostOmittedPreservesCurrentHost() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                hid = "Connected",
            )

        assertEquals(baseStatus.host, reduction.status.host)
    }

    @Test
    fun errorExplicitNullClearsError() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                error = null,
            )

        assertNull(reduction.status.error)
    }

    @Test
    fun eventEmittedWithProvidedTimestampSourceAndMessage() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 9_999L,
                eventSource = "HID",
                eventMessage = "Composite registered.",
            )

        assertNotNull(reduction.event)
        val event = reduction.event!!
        assertEquals(9_999L, event.timestampMs)
        assertEquals("HID", event.source)
        assertEquals("Composite registered.", event.message)
        assertEquals(listOf(event), reduction.status.events)
    }

    @Test
    fun eventIsPrependedAndPreservesPriorEvents() {
        val older = GatewayEvent(timestampMs = 100L, source = "Bluetooth", message = "Adapter ready.")
        val current = baseStatus.copy(events = listOf(older))

        val reduction =
            reduceGatewayStatus(
                current = current,
                nowMs = 200L,
                eventSource = "HID",
                eventMessage = "Proxy ready.",
            )

        val events = reduction.status.events
        assertEquals(2, events.size)
        assertEquals("HID", events.first().source)
        assertEquals(older, events.last())
    }

    @Test
    fun eventListCappedAtConfiguredMax() {
        val seed =
            (1..5).map {
                GatewayEvent(timestampMs = it.toLong(), source = "Bluetooth", message = "msg $it")
            }
        val current = baseStatus.copy(events = seed)

        val reduction =
            reduceGatewayStatus(
                current = current,
                nowMs = 99L,
                maxEvents = 3,
                eventSource = "HID",
                eventMessage = "new",
            )

        val events = reduction.status.events
        assertEquals(3, events.size)
        assertEquals("new", events[0].message)
        assertEquals("msg 1", events[1].message)
        assertEquals("msg 2", events[2].message)
    }

    @Test
    fun blankEventSourceProducesNoEvent() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                eventSource = "   ",
                eventMessage = "Should not be emitted.",
            )

        assertNull(reduction.event)
        assertTrue(reduction.status.events.isEmpty())
    }

    @Test
    fun missingEventMessageProducesNoEvent() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                eventSource = "HID",
                eventMessage = null,
            )

        assertNull(reduction.event)
        assertTrue(reduction.status.events.isEmpty())
    }

    @Test
    fun countersOnlyChangeWhenProvided() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                reportsSent = 42,
            )

        assertEquals(42, reduction.status.reportsSent)
        assertEquals(baseStatus.feedbackPackets, reduction.status.feedbackPackets)
        assertEquals(baseStatus.rejectedFeedbackPackets, reduction.status.rejectedFeedbackPackets)
    }

    @Test
    fun lastInputAtMsExplicitNullClearsValue() {
        val reduction =
            reduceGatewayStatus(
                current = baseStatus,
                nowMs = 5_000L,
                lastInputAtMs = null,
            )

        assertNull(reduction.status.lastInputAtMs)
        assertEquals(baseStatus.lastInputSource, reduction.status.lastInputSource)
    }
}
