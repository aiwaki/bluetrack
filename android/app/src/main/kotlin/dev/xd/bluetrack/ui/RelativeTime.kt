package dev.xd.bluetrack.ui

internal fun relativeAgeLabel(
    now: Long,
    timestampMs: Long?,
    idleLabel: String = "Idle",
): String {
    if (timestampMs == null) return idleLabel
    val deltaMs = (now - timestampMs).coerceAtLeast(0L)
    val seconds = deltaMs / 1000L
    return when {
        deltaMs < 1000L -> "now"
        seconds < 60L -> "${seconds}s ago"
        seconds < 3600L -> "${seconds / 60L}m ago"
        seconds < 86_400L -> "${seconds / 3600L}h ago"
        else -> "${seconds / 86_400L}d ago"
    }
}
