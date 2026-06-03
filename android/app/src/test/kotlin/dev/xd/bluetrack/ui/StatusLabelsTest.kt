package dev.xd.bluetrack.ui

import dev.xd.bluetrack.ble.GatewayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLabelsTest {
    private val now = 10_000L

    @Test
    fun hostConnectedReflectsHostField() {
        assertFalse(isHostConnected(GatewayStatus(host = null)))
        assertTrue(isHostConnected(GatewayStatus(host = "MacBook")))
    }

    @Test
    fun inputLiveWithinWindowOnly() {
        assertFalse(isInputLive(GatewayStatus(lastInputAtMs = null), now))
        // 1000 ms ago → within the 1400 ms live window.
        assertTrue(isInputLive(GatewayStatus(lastInputAtMs = now - 1000), now))
        // 2000 ms ago → stale.
        assertFalse(isInputLive(GatewayStatus(lastInputAtMs = now - 2000), now))
    }

    @Test
    fun primaryLabelErrorWins() {
        val s = GatewayStatus(error = "boom", host = "Mac", lastInputAtMs = now)
        assertEquals("Needs attention", primaryStatusLabel(s, now))
    }

    @Test
    fun primaryLabelReadyAndLive() {
        assertEquals(
            "Ready - input live",
            primaryStatusLabel(GatewayStatus(host = "Mac", lastInputAtMs = now - 200), now),
        )
        assertEquals(
            "Ready",
            primaryStatusLabel(GatewayStatus(host = "Mac", lastInputAtMs = now - 5000), now),
        )
    }

    @Test
    fun primaryLabelConnectingAndPairing() {
        assertEquals("Connecting", primaryStatusLabel(GatewayStatus(hid = "Connecting…"), now))
        assertEquals("Pairing", primaryStatusLabel(GatewayStatus(pairing = "Discoverable"), now))
    }

    @Test
    fun primaryLabelFallsBackToPreparing() {
        assertEquals("Preparing", primaryStatusLabel(GatewayStatus(), now))
    }

    @Test
    fun inputSourceLabelStates() {
        assertEquals(
            "Touchpad live",
            inputSourceLabel(GatewayStatus(lastInputSource = "Touchpad", lastInputAtMs = now - 100), now),
        )
        assertEquals(
            "Keyboard",
            inputSourceLabel(GatewayStatus(lastInputSource = "Keyboard", lastInputAtMs = now - 5000), now),
        )
        assertEquals("Idle", inputSourceLabel(GatewayStatus(), now))
    }

    @Test
    fun hostFallbackDefaultsToSearching() {
        assertEquals("Searching", hostFallbackLabel(GatewayStatus()))
        assertEquals("Pairing", hostFallbackLabel(GatewayStatus(pairing = "Discoverable")))
    }
}
