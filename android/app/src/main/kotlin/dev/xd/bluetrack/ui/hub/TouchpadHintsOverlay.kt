package dev.xd.bluetrack.ui.hub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * First-run gesture hint overlay for the Hub touchpad.
 *
 * Renders on top of the touchpad surface until the user taps
 * "GOT IT". The whole overlay is a pointerInput sink — taps go
 * to the dismiss button, not through to the touchpad below, so
 * the user can't accidentally fire a click while reading. Once
 * dismissed (persisted via `TweaksRepository.setTouchpadHintsDismissed`)
 * the overlay never appears again unless the user reinstalls or
 * clears app data.
 *
 * Hints map to the actual gesture handler:
 *  - 1-finger drag → cursor motion.
 *  - 1-finger tap → left click.
 *  - 2-finger tap → right click (Mac trackpad convention).
 *  - 2-finger drag → scroll wheel.
 */
@Composable
fun TouchpadHintsOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val haptic = LocalHapticFeedback.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(260)),
        exit = fadeOut(animationSpec = tween(220)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.bg0.copy(alpha = 0.86f))
                .border(1.dp, palette.crit.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                // Sink all pointer events so a finger landing on the
                // overlay doesn't fall through to the touchpad
                // surface below (which would otherwise emit a
                // phantom click while the user is just reading).
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }.padding(14.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "WELCOME",
                    color = palette.fg2,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Use it like a MacBook trackpad.",
                    color = palette.fg0,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Text(
                    text = "Yes, it's that simple.",
                    color = palette.crit,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                var dismissPressed by remember { mutableStateOf(false) }
                val dismissPressScale by animateFloatAsState(
                    targetValue = if (dismissPressed) 0.96f else 1f,
                    animationSpec = if (dismissPressed) {
                        spring(stiffness = Spring.StiffnessMedium)
                    } else {
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow,
                        )
                    },
                    label = "hints-dismiss-press",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .scale(dismissPressScale)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.crit)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    dismissPressed = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val released = tryAwaitRelease()
                                    dismissPressed = false
                                    if (released) onDismiss()
                                },
                            )
                        }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "GOT IT",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.4.sp,
                    )
                }
            }
        }
    }
}
