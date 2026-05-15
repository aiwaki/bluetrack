package dev.xd.bluetrack.ui

import dev.xd.bluetrack.ble.GatewayStatus

internal fun GatewayStatus.shouldAutoRequestDiscoverability(): Boolean = compatibility.bluetoothAvailable &&
    compatibility.bluetoothEnabled &&
    host == null &&
    compatibility.bondedDevices.isEmpty()

internal fun GatewayStatus.automationLabel(): String = when {
    !compatibility.bluetoothAvailable -> "Bluetooth unavailable"
    !compatibility.bluetoothEnabled -> "Waiting for Bluetooth"
    host != null -> "Connected to $host"
    compatibility.bondedDevices.isNotEmpty() -> "Auto-connecting bonded host"
    pairing.startsWith("Discoverable") -> "Waiting for host pairing"
    pairing == "Opening pairing window" -> "Opening pairing window"
    pairing == "Discoverability cancelled" -> "Pairing prompt cancelled"
    hid.startsWith("Waiting") -> "Preparing HID"
    hid.contains("ready", ignoreCase = true) -> "Ready for pairing"
    else -> "Autopilot active"
}
