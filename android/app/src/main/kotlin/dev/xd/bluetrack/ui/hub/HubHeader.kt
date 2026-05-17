package dev.xd.bluetrack.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Unified Bluetrack app-bar header.
 *
 *   ┌─ wordmark micro-line ([Blue·track])
 *   │     "Hub" / "Hosts" / …    ← 26 sp display title
 *   │  ─────────────────────────────────────
 *   │                                          [● Live]  ← optional
 *
 * Mirrors the canvas `AppBar` atom from `shared.jsx`:
 *  - 18 dp horizontal gutter (`Sp6`)
 *  - Wordmark sits above the title at 11 sp with 85 % opacity
 *  - Title uses the display family at 26 sp, weight 700,
 *    letter-spacing −0.025 em, line-height 1
 *  - Right slot ([rightSlot]) is flushed to baseline-end of the
 *    title with a small bottom inset; pass `null` to omit it
 *
 * Used by the Hub route (with a [ServiceChip] right slot) but the
 * signature is generic so later routes (Hosts, Activity, etc.)
 * reuse it.
 */
@Composable
fun HubHeader(
    title: String,
    modifier: Modifier = Modifier,
    rightSlot: (@Composable () -> Unit)? = null,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = BluetrackTokens.Sp6,
                end = BluetrackTokens.Sp6,
                top = BluetrackTokens.Sp2,
                bottom = BluetrackTokens.Sp4,
            ),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp1),
        ) {
            Wordmark(
                size = 11.sp,
                modifier = Modifier.alpha(0.85f),
            )
            Text(
                text = title,
                color = palette.fg0,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-0.65).sp, // ≈ -0.025 em at 26 sp
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        rightSlot?.invoke()
    }
}
