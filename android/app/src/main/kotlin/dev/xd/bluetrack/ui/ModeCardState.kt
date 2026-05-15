package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidMode

internal data class ModeCardState(
    val mode: HidMode,
    val title: String,
    val tagline: String,
    val isSelected: Boolean,
    val statusLabel: String,
)

internal fun modeCardStates(
    currentMode: HidMode,
    hostConnected: Boolean,
): List<ModeCardState> = listOf(
    cardState(HidMode.MOUSE, "Mouse", "Cursor and click", currentMode, hostConnected),
    cardState(HidMode.GAMEPAD, "Gamepad", "Buttons and sticks", currentMode, hostConnected),
)

private fun cardState(
    cardMode: HidMode,
    title: String,
    tagline: String,
    currentMode: HidMode,
    hostConnected: Boolean,
): ModeCardState {
    val selected = cardMode == currentMode
    return ModeCardState(
        mode = cardMode,
        title = title,
        tagline = tagline,
        isSelected = selected,
        statusLabel =
            when {
                !selected -> "Tap to switch"
                hostConnected -> "Active"
                else -> "Selected"
            },
    )
}
