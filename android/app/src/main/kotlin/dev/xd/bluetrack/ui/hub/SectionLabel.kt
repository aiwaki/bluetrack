package dev.xd.bluetrack.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Canvas `SectionLabel` atom (`docs/design/v1/shared.jsx`).
 *
 * Caption-style header with an optional trailing action — used
 * over the activity strip, settings rows, and any list section
 * that needs a "see all →" or similar inline link.
 *
 * Renders the caption with `labelMedium` (mono caps, 1.92 sp
 * tracking) so it matches `bt-cap`.
 */
@Composable
fun SectionLabel(
    label: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = BluetrackTokens.Sp2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = palette.fg2,
        )
        action?.invoke()
    }
}
