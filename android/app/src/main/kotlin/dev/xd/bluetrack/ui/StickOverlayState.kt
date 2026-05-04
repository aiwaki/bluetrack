package dev.xd.bluetrack.ui

import kotlin.math.sqrt

internal const val STICK_AXIS_MAX = 127

internal enum class StickDeflection { IDLE, LIGHT, STRONG }

internal data class StickOverlayState(
    val deflection: StickDeflection,
    val xLabel: String,
    val yLabel: String,
    val normalizedX: Float,
    val normalizedY: Float,
)

internal fun stickOverlayState(stickX: Int, stickY: Int): StickOverlayState {
    val clampedX = stickX.coerceIn(-STICK_AXIS_MAX, STICK_AXIS_MAX)
    val clampedY = stickY.coerceIn(-STICK_AXIS_MAX, STICK_AXIS_MAX)
    val magnitude = sqrt((clampedX * clampedX + clampedY * clampedY).toDouble())
    val deflection = when {
        magnitude < 16.0 -> StickDeflection.IDLE
        magnitude < 96.0 -> StickDeflection.LIGHT
        else -> StickDeflection.STRONG
    }
    return StickOverlayState(
        deflection = deflection,
        xLabel = formatSigned(clampedX),
        yLabel = formatSigned(clampedY),
        normalizedX = clampedX / STICK_AXIS_MAX.toFloat(),
        normalizedY = clampedY / STICK_AXIS_MAX.toFloat(),
    )
}

internal fun stickDeflectionLabel(deflection: StickDeflection): String = when (deflection) {
    StickDeflection.IDLE -> "Stick idle"
    StickDeflection.LIGHT -> "Stick nudge"
    StickDeflection.STRONG -> "Stick strong"
}

private fun formatSigned(value: Int): String = if (value >= 0) "+$value" else value.toString()
