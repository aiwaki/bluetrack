package dev.xd.bluetrack.ui.diag

// Forwarding alias kept for binary stability while existing
// Diag composables transition to the shared
// `dev.xd.bluetrack.ui.rememberStaggerModifier`. New callers
// should depend on the shared helper directly.

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberStaggerModifier(
    index: Int,
    offsetDp: Dp = 12.dp,
    perItemDelayMs: Long = 60L,
    durationMs: Int = 320,
): Modifier =
    dev.xd.bluetrack.ui.rememberStaggerModifier(
        index = index,
        offsetDp = offsetDp,
        perItemDelayMs = perItemDelayMs,
        durationMs = durationMs,
    )
