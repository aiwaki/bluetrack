package dev.xd.bluetrack.ui

import dev.xd.bluetrack.ble.CompatibilitySnapshot
import dev.xd.bluetrack.ble.GatewayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStateTest {
    @Test
    fun autoRequestsDiscoverabilityOnlyWhenReadyAndUnbonded() {
        assertTrue(
            status(enabled = true, bondedDevices = emptyList(), host = null)
                .shouldAutoRequestDiscoverability(),
        )
        assertFalse(
            status(enabled = true, bondedDevices = listOf("MacBook Pro"), host = null)
                .shouldAutoRequestDiscoverability(),
        )
        assertFalse(
            status(enabled = true, bondedDevices = emptyList(), host = "MacBook Pro")
                .shouldAutoRequestDiscoverability(),
        )
        assertFalse(
            status(enabled = false, bondedDevices = emptyList(), host = null)
                .shouldAutoRequestDiscoverability(),
        )
    }

    @Test
    fun automationLabelPrioritizesConnectedAndBondedStates() {
        assertEquals(
            "Connected to MacBook Pro",
            status(enabled = true, bondedDevices = listOf("MacBook Pro"), host = "MacBook Pro")
                .automationLabel(),
        )
        assertEquals(
            "Auto-connecting bonded host",
            status(enabled = true, bondedDevices = listOf("MacBook Pro"), host = null)
                .automationLabel(),
        )
        assertEquals(
            "Waiting for host pairing",
            status(enabled = true, bondedDevices = emptyList(), host = null, pairing = "Discoverable for 300s")
                .automationLabel(),
        )
    }

    private fun status(
        enabled: Boolean,
        bondedDevices: List<String>,
        host: String?,
        pairing: String = "Not discoverable",
    ) = GatewayStatus(
        hid = "HID ready (MOUSE)",
        pairing = pairing,
        host = host,
        compatibility =
            CompatibilitySnapshot(
                bluetoothAvailable = true,
                bluetoothEnabled = enabled,
                bondedDevices = bondedDevices,
            ),
    )
}
