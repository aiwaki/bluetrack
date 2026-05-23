package dev.xd.bluetrack.ble

import android.bluetooth.BluetoothClass
import java.util.Locale

internal data class HidHostCandidate(
    val name: String,
    val majorDeviceClass: Int?,
)

internal fun HidHostCandidate.hidHostRank(): Int? {
    val normalizedName = name.lowercase(Locale.US)
    if (normalizedName.looksLikeAudioOrAccessory()) return null

    return when (majorDeviceClass) {
        BluetoothClass.Device.Major.COMPUTER -> 100
        BluetoothClass.Device.Major.AUDIO_VIDEO,
        BluetoothClass.Device.Major.HEALTH,
        BluetoothClass.Device.Major.PERIPHERAL,
        BluetoothClass.Device.Major.TOY,
        BluetoothClass.Device.Major.WEARABLE,
        BluetoothClass.Device.Major.PHONE,
        -> null
        // Name match (any major class) ranks high enough to win
        // over uncategorised peers.
        else -> if (normalizedName.looksLikeComputer()) {
            75
        } else if (majorDeviceClass == null || majorDeviceClass == 0 || majorDeviceClass == -1) {
            // Uncategorised / null BluetoothClass is common for
            // older PCs paired before BTHID rebonding. Trust the
            // bonded record and let auto-connect try anyway — the
            // accessory blacklist above already filters out the
            // obvious "not a HID host" devices.
            50
        } else {
            null
        }
    }
}

private fun String.looksLikeAudioOrAccessory(): Boolean = AUDIO_OR_ACCESSORY_KEYWORDS.any { contains(it) }

private fun String.looksLikeComputer(): Boolean = COMPUTER_KEYWORDS.any { contains(it) }

private val AUDIO_OR_ACCESSORY_KEYWORDS =
    listOf(
        "airpods",
        "airpod",
        "beats",
        "headphone",
        "headphones",
        "headset",
        "earbud",
        "earbuds",
        "speaker",
        "buds",
        "wh-",
        "wf-",
        "keyboard",
        "mouse",
        "trackpad",
    )

private val COMPUTER_KEYWORDS =
    listOf(
        "macbook",
        "mac mini",
        "mac studio",
        "imac",
        "windows",
        "desktop",
        "laptop",
        "notebook",
        "thinkpad",
        "surface",
        "pc",
    )
