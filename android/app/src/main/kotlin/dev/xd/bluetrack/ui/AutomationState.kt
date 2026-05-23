package dev.xd.bluetrack.ui

import dev.xd.bluetrack.ble.BluetoothHostKind
import dev.xd.bluetrack.ble.GatewayStatus

/**
 * Trigger the system `ACTION_REQUEST_DISCOVERABLE` prompt only
 * when there is no plausible computer host already bonded.
 *
 * Earlier this guard checked `bondedDevices.isEmpty()`, which
 * meant a phone bonded with AirPods, a speaker, or a controller
 * (which the user definitely will have) would never get the
 * pairing-window prompt — so Mac/PC could not discover Bluetrack
 * in the scan list. We now key off the classified host kinds:
 * if at least one bonded device looks like a computer (either
 * by `BluetoothClass.Device.Major.COMPUTER` or by name keyword
 * via `classifyByName`), assume the user is reconnecting to a
 * known host and skip the prompt; otherwise open the system
 * discoverability dialog so a fresh host can find the phone.
 */
internal fun GatewayStatus.shouldAutoRequestDiscoverability(): Boolean = compatibility.bluetoothAvailable &&
    compatibility.bluetoothEnabled &&
    host == null &&
    compatibility.hostKinds.values.none { it == BluetoothHostKind.Computer }

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
