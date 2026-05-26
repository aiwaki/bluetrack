package dev.xd.bluetrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Settings row. Two layouts driven by the canvas heuristic in
 * `docs/design/v1/settings.jsx`:
 *
 *  - If [kind] is [SettingsRowKind.Text] and the value is mono or
 *    longer than 14 characters, the row stacks (label on top,
 *    value beneath in `fg-2`). This stops mono fingerprints and
 *    OS strings from ellipsing.
 *  - Otherwise the row lays out horizontally: label on the left,
 *    value clipped on the right, with an optional chevron / ext
 *    glyph.
 *
 * [accent] = true paints the value in `mintBright` for "live"
 * state hints (e.g. `Foreground service · Running`).
 * Tap handler is optional; passing one makes the whole row
 * clickable. The [hint] line surfaces an explanatory line under
 * the value in `fg-3`.
 */
enum class SettingsRowKind { Text, Chev, Ext }

@Composable
fun SettingsRow(
    label: String,
    value: String? = null,
    mono: Boolean = false,
    accent: Boolean = false,
    kind: SettingsRowKind = SettingsRowKind.Text,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val stacked = kind == SettingsRowKind.Text && value != null && (mono || value.length > 14)
    val base = modifier
        .fillMaxWidth()
        .let { m ->
            if (onClick != null) {
                m.clickable {
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                    )
                    onClick()
                }
            } else {
                m
            }
        }.padding(horizontal = 14.dp, vertical = 13.dp)
    if (stacked) {
        Column(
            modifier = base,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = palette.fg0,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (kind == SettingsRowKind.Chev) ChevGlyph(palette.fg3)
                if (kind == SettingsRowKind.Ext) ExtGlyph(palette.fg3)
            }
            Text(
                text = value!!,
                color = if (accent) palette.mintBright else palette.fg2,
                fontSize = 12.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hint?.let {
                Text(text = it, color = palette.fg3, fontSize = 11.sp)
            }
        }
    } else {
        Row(
            modifier = base,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    color = palette.fg0,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                hint?.let {
                    Text(text = it, color = palette.fg2, fontSize = 11.sp)
                }
            }
            if (value != null && kind == SettingsRowKind.Text) {
                Text(
                    text = value,
                    color = if (accent) palette.mintBright else palette.fg2,
                    fontSize = 11.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (kind == SettingsRowKind.Chev) ChevGlyph(palette.fg3)
            if (kind == SettingsRowKind.Ext) ExtGlyph(palette.fg3)
        }
    }
}

@Composable
private fun ChevGlyph(color: androidx.compose.ui.graphics.Color) {
    Text(text = "›", color = color, fontSize = 14.sp)
}

@Composable
private fun ExtGlyph(color: androidx.compose.ui.graphics.Color) {
    Text(text = "↗", color = color, fontSize = 12.sp)
}
