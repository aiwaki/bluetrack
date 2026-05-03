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

    fun neutralReport(): ByteArray = byteArrayOf(
        0,
        0,
        HAT_NEUTRAL,
        0,
        0,
        0,
        0,
    )

    fun buttonAWakeReport(): ByteArray = byteArrayOf(
        1,
        0,
        HAT_NEUTRAL,
        0,
        0,
        0,
        0,
    )
}
