package dev.xd.bluetrack.ble

import android.bluetooth.BluetoothClass

/**
 * Real Bluetooth Class of Device classification used by the
 * Hosts route to replace its previous name-keyword heuristic.
 *
 * Mapping to the Android `BluetoothClass.Device.Major` codes:
 *
 *  - [Computer]  → MAJOR.COMPUTER (`0x0100`)
 *  - [Audio]     → MAJOR.AUDIO_VIDEO (`0x0400`)
 *  - [Pointing]  → MAJOR.PERIPHERAL (`0x0500`) with the pointing
 *    minor bit set (`0x0080`).
 *  - [Keyboard]  → MAJOR.PERIPHERAL with the keyboard minor bit
 *    (`0x0040`).
 *  - [Phone]     → MAJOR.PHONE (`0x0200`). Surfaced separately so
 *    the Hosts route can attach the `ios-hid` caveat (Apple
 *    restricts BLE HID Device to MFi accessories on iOS).
 *  - [Unknown]   → no useful major code (uncategorised, IMAGING,
 *    HEALTH, WEARABLE, …). The route renders these as
 *    `Not supported` and lets the user keep the row for
 *    transparency.
 */
enum class BluetoothHostKind { Computer, Audio, Pointing, Keyboard, Phone, Unknown }

/**
 * Classify a [BluetoothClass] into one of [BluetoothHostKind].
 * Pure function so it is trivial to unit-test on the JVM.
 *
 * `null` (the field is sometimes null on emulators / mock
 * devices) → [BluetoothHostKind.Unknown].
 */
fun classifyBluetoothHost(klass: BluetoothClass?): BluetoothHostKind {
    klass ?: return BluetoothHostKind.Unknown
    val major = klass.majorDeviceClass
    val device = klass.deviceClass
    return when (major) {
        BluetoothClass.Device.Major.COMPUTER -> BluetoothHostKind.Computer
        BluetoothClass.Device.Major.AUDIO_VIDEO -> BluetoothHostKind.Audio
        BluetoothClass.Device.Major.PHONE -> BluetoothHostKind.Phone
        BluetoothClass.Device.Major.PERIPHERAL -> when {
            // Peripheral minor codes from the Bluetooth Assigned
            // Numbers spec. The `device` integer carries both
            // major and minor bits; mask out the major to read
            // the minor.
            (device and PERIPHERAL_KEYBOARD_BIT) != 0 -> BluetoothHostKind.Keyboard
            (device and PERIPHERAL_POINTING_BIT) != 0 -> BluetoothHostKind.Pointing
            else -> BluetoothHostKind.Unknown
        }
        else -> BluetoothHostKind.Unknown
    }
}

private const val PERIPHERAL_KEYBOARD_BIT: Int = 0x0040
private const val PERIPHERAL_POINTING_BIT: Int = 0x0080

/**
 * Name-keyword fallback for devices whose `BluetoothClass` is
 * absent or uninformative (some older bonded records on Android,
 * uncategorised Bluetooth chips, emulator stubs). Conservative —
 * only returns a kind when the name carries a strong hint;
 * everything else stays [BluetoothHostKind.Unknown].
 */
fun classifyByName(name: String): BluetoothHostKind {
    val n = name.lowercase()
    val audio = listOf("airpod", "headphone", "headset", "buds", "speaker", "soundbar", "earbud", "audio")
    val pointing = listOf("magic mouse", "trackpad", "mouse")
    val keyboard = listOf("magic keyboard", "keyboard", "k380", "k480")
    val phone = listOf("iphone", "ipad", "android phone", "pixel")
    val computer = listOf(
        "mbp",
        "macbook",
        "imac",
        "mac mini",
        "mac studio",
        "windows",
        "thinkpad",
        "surface",
        "tower",
        "desktop",
        "pc",
        "linux",
        "tablet",
    )
    return when {
        audio.any { n.contains(it) } -> BluetoothHostKind.Audio
        pointing.any { n.contains(it) } -> BluetoothHostKind.Pointing
        keyboard.any { n.contains(it) } -> BluetoothHostKind.Keyboard
        phone.any { n.contains(it) } -> BluetoothHostKind.Phone
        computer.any { n.contains(it) } -> BluetoothHostKind.Computer
        else -> BluetoothHostKind.Unknown
    }
}
