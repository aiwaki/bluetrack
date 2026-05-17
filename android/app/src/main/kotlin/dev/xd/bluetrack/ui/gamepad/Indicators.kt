package dev.xd.bluetrack.ui.gamepad

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Small animated chip showing that the gamepad wake-train is
 * running. Browsers need a button event before they recognise a
 * newly-attached gamepad, so Bluetrack quietly fires
 * placeholder press/release cycles every ~130 ms. Canvas
 * `WakeTrainChip` reflects that with three staggered pulse dots.
 */
@Composable
fun WakeTrainChip(modifier: Modifier = Modifier) {
    val palette = BluetrackTheme.palette
    val transition = rememberInfiniteTransition(label = "wake-train")
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .btGlass(strong = false, shape = RoundedCornerShape(999.dp))
            .border(1.dp, palette.glassBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (0..2).forEach { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = androidx.compose.animation.core
                            .StartOffset(offsetMillis = i * 150),
                    ),
                    label = "wake-train-dot-$i",
                )
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(palette.mint),
                )
            }
        }
        Text(
            text = "WAKE TRAIN",
            color = palette.fg0,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
        )
    }
}

/**
 * Tiny mono counter that ticks up with each emitted HID frame.
 * Canvas `FrameCounter` flashes briefly each tick — we render the
 * count in [palette.fg1] and rely on the upstream `pulse` flag to
 * trigger a single-frame mint flash via the alpha modifier.
 */
@Composable
fun FrameCounter(
    seq: Long,
    pulse: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "FRAME",
            color = palette.fg3,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        )
        Text(
            text = "#$seq",
            color = if (pulse) palette.mintBright else palette.fg1,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.4.sp,
        )
    }
}

/**
 * Connection lozenge in the gamepad top-rail: mint pulse dot +
 * host name + latency. Latency is purely cosmetic in this build
 * (we have no host RTT field on `GatewayStatus` yet); accept a
 * formatted string and let callers default to a placeholder until
 * the channel exposes it.
 */
@Composable
fun ConnectionLozenge(
    host: String,
    latency: String,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .btGlass(strong = false, shape = RoundedCornerShape(999.dp))
            .border(1.dp, palette.glassBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(palette.mintBright),
        )
        Text(text = host, color = palette.fg0, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(text = "·", color = palette.fg2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(text = latency, color = palette.mint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
