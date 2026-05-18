package dev.xd.bluetrack.engine

internal object GamepadReportFormat {
    const val LENGTH = 7
    const val BUTTON_LOW_INDEX = 0
    const val BUTTON_HIGH_INDEX = 1
    const val HAT_INDEX = 2
    const val LEFT_X_INDEX = 3
    const val LEFT_Y_INDEX = 4
    const val RIGHT_X_INDEX = 5
    const val RIGHT_Y_INDEX = 6
    const val HAT_NEUTRAL: Byte = 0x08

    /**
     * Button → (byteIndex, bitMask) map used by
     * [TranslationEngine.setGamepadButton] to flip the correct
     * bit in the composite gamepad report. Labels match the
     * canvas `GamepadSurface` (`docs/design/v1/gamepad.jsx`)
     * +/- a few HID-host conventions:
     *
     *  - Low byte (BUTTON_LOW_INDEX = 0):
     *      bit0=A  bit1=B  bit2=X  bit3=Y
     *      bit4=LB bit5=RB bit6=LT bit7=RT
     *  - High byte (BUTTON_HIGH_INDEX = 1):
     *      bit0=BACK bit1=START bit2=L3 bit3=R3 bit4=GUIDE
     *      bit5-6 reserved
     *      bit7=DISCOVERY (existing browser wake-train ping;
     *      avoided by real game buttons).
     */
    val BUTTON_MASKS: Map<String, Pair<Int, Int>> = mapOf(
        "A" to (BUTTON_LOW_INDEX to 0x01),
        "B" to (BUTTON_LOW_INDEX to 0x02),
        "X" to (BUTTON_LOW_INDEX to 0x04),
        "Y" to (BUTTON_LOW_INDEX to 0x08),
        "LB" to (BUTTON_LOW_INDEX to 0x10),
        "RB" to (BUTTON_LOW_INDEX to 0x20),
        "LT" to (BUTTON_LOW_INDEX to 0x40),
        "RT" to (BUTTON_LOW_INDEX to 0x80),
        "BACK" to (BUTTON_HIGH_INDEX to 0x01),
        "START" to (BUTTON_HIGH_INDEX to 0x02),
        "L3" to (BUTTON_HIGH_INDEX to 0x04),
        "R3" to (BUTTON_HIGH_INDEX to 0x08),
        "GUIDE" to (BUTTON_HIGH_INDEX to 0x10),
    )

    /**
     * Mask reserved for the browser wake-train ping. Keep
     * separate from [BUTTON_MASKS] so real game buttons can
     * never clobber it accidentally.
     */
    const val DISCOVERY_BUTTON_MASK_HIGH: Byte = -128

    fun neutralReport(): ByteArray = byteArrayOf(
        0,
        0,
        HAT_NEUTRAL,
        0,
        0,
        0,
        0,
    )

    fun discoveryWakeReport(): ByteArray = byteArrayOf(
        0,
        DISCOVERY_BUTTON_MASK_HIGH,
        HAT_NEUTRAL,
        0,
        0,
        0,
        0,
    )
}
