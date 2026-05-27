package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onBack: (() -> Unit)? = null,
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
        if (onBack != null) {
            // Leading back-affordance for routes that the dock no
            // longer surfaces (currently: Activity, reached via the
            // Hub `ActivityStrip`). Sized to match the title's
            // baseline so the glyph reads as a peer to the
            // wordmark + title block.
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            var pressed by remember { mutableStateOf(false) }
            val pressScale by animateFloatAsState(
                targetValue = if (pressed) 0.9f else 1f,
                animationSpec = if (pressed) {
                    spring(stiffness = Spring.StiffnessMedium)
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow,
                    )
                },
                label = "hub-header-back-press",
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .scale(pressScale)
                    .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                )
                                val released = tryAwaitRelease()
                                pressed = false
                                if (released) onBack()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = palette.fg0,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
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
