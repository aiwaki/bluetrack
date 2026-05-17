package dev.xd.bluetrack.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Foreground-service chip rendered at the right edge of the
 * Bluetrack [HubHeader].
 *
 * Two states match the canvas:
 *  - `running = true`  → soft mint pill with [Pulse] + "Live" label.
 *  - `running = false` → transparent pill with hairline border +
 *                        "Off" label (calm tone).
 *
 * The chip is intentionally compact (26 dp tall) so the wordmark
 * micro-line + display title in the header keep the visual weight.
 */
@Composable
fun ServiceChip(
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(999.dp)
    val bg = if (running) palette.mintGlowSoft else Color.White.copy(alpha = 0.05f)
    val fg = if (running) palette.mintBright else palette.fg2
    val borderColor = if (running) Color.Transparent else palette.hairline
    Row(
        modifier = modifier
            .height(26.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (running) Pulse(size = 3.dp)
        Text(
            text = if (running) "LIVE" else "OFF",
            color = fg,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.1.sp,
            style = MaterialTheme.typography.labelSmall.copy(color = fg),
        )
    }
}
