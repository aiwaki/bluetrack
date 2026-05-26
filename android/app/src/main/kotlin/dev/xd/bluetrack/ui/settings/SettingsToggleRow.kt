package dev.xd.bluetrack.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * iOS-style switch row. The label / hint layout matches
 * [SettingsRow] so toggle rows sit cleanly inside the same
 * `SettingsGroup` card.
 *
 * `checked = true` paints the track in `mint` with a 1 dp white
 * thumb pulled to the right; `false` shows a neutral grey track
 * with the thumb left. Both transitions animate over 180 ms.
 *
 * Tap toggles the value via [onCheckedChange]. The whole row is
 * the hit target, not just the switch — matches the canvas's
 * full-row tap target for the settings rows.
 */
@Composable
fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                )
                onCheckedChange(!checked)
            }.padding(horizontal = 14.dp, vertical = 13.dp),
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = BluetrackTheme.palette
    val trackWidth = 42.dp
    val trackHeight = 24.dp
    val thumbSize = 20.dp
    val thumbInset = 2.dp
    val target = if (checked) trackWidth - thumbSize - thumbInset else thumbInset
    val thumbX by animateDpAsState(targetValue = target, animationSpec = tween(180), label = "switch-thumb")
    val trackAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0.18f,
        animationSpec = tween(180),
        label = "switch-track",
    )
    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (checked) palette.mint.copy(alpha = trackAlpha) else Color.White.copy(alpha = trackAlpha),
            ).border(
                1.dp,
                if (checked) Color.Transparent else palette.glassBorder,
                RoundedCornerShape(999.dp),
            ).clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Spacer(modifier = Modifier.size(thumbX))
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Color.Black.copy(alpha = 0.06f), CircleShape),
        )
    }
}
