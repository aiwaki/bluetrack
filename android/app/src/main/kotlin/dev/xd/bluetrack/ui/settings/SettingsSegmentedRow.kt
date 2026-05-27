package dev.xd.bluetrack.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Settings row with a segmented multi-choice control. Used for
 * Appearance → Theme (`SYSTEM / LIGHT / DARK`) but the API is
 * generic — caller passes the options list, current selection,
 * and the `onSelect` callback.
 */
@Composable
fun SettingsSegmentedRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    hint: String? = null,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = palette.fg0,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        // Segmented pill bar — each option a tappable slot, the
        // selected one filled with the crit accent.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, palette.hairline, RoundedCornerShape(999.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isActive = option == selected
                var pressed by remember { mutableStateOf(false) }
                val pressScale by animateFloatAsState(
                    targetValue = if (pressed) 0.92f else 1f,
                    animationSpec = if (pressed) {
                        spring(stiffness = Spring.StiffnessMedium)
                    } else {
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow,
                        )
                    },
                    label = "segmented-press-$option",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(36.dp)
                        .scale(pressScale)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (isActive) palette.crit else Color.Transparent,
                        ).pointerInput(option) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    haptic.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                    )
                                    val released = tryAwaitRelease()
                                    pressed = false
                                    if (released) onSelect(option)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        color = if (isActive) Color.White else palette.fg2,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    )
                }
            }
        }
        hint?.let {
            Text(
                text = it,
                color = palette.fg3,
                fontSize = 11.sp,
            )
        }
        @Suppress("UNUSED_EXPRESSION")
        BluetrackTokens
    }
}
