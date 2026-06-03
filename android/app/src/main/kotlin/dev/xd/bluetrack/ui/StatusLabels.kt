package dev.xd.bluetrack.ui

import dev.xd.bluetrack.ble.GatewayStatus

/**
 * Pure derived labels for the gateway status flow. Lived inside
 * `MainActivity` while only the Hub consumed them, lifted into
 * the `ui` package once Diagnostics started rendering the same
 * State / Host / Input / Flow rows that used to live on the Hub.
 */

fun isHostConnected(status: GatewayStatus): Boolean = status.host != null

fun isInputLive(
    status: GatewayStatus,
    now: Long,
): Boolean = status.lastInputAtMs?.let { now - it < 1400L } == true

fun primaryStatusLabel(
    status: GatewayStatus,
    now: Long,
): String = when {
    status.error != null -> "Needs attention"
    isHostConnected(status) && isInputLive(status, now) -> "Ready - input live"
    isHostConnected(status) -> "Ready"
    status.hid.contains("connecting", ignoreCase = true) ||
        status.pairing.contains("connecting", ignoreCase = true) -> "Connecting"
    isDiscoverable(status.pairing) ||
        status.pairing.contains("pairing", ignoreCase = true) -> "Pairing"
    else -> "Preparing"
}

/**
 * True only for the active discoverable state. Guards against the idle
 * default label "Not discoverable", whose "discoverable" substring used
 * to trip the pairing branch and mislabel an idle gateway as "Pairing".
 */
private fun isDiscoverable(pairing: String): Boolean =
    pairing.contains("discoverable", ignoreCase = true) &&
        !pairing.contains("not discoverable", ignoreCase = true)

fun hostFallbackLabel(status: GatewayStatus): String = when {
    status.compatibility.bondedDevices.isNotEmpty() -> "Bonded"
    isDiscoverable(status.pairing) -> "Pairing"
    else -> "Searching"
}

fun inputSourceLabel(
    status: GatewayStatus,
    now: Long,
): String = when {
    isInputLive(status, now) -> "${status.lastInputSource ?: "Input"} live"
    status.lastInputSource != null -> status.lastInputSource ?: "Idle"
    else -> "Idle"
}
