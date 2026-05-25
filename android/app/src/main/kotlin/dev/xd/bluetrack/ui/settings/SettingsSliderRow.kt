package dev.xd.bluetrack.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Continuous-value slider row backed by [BoxWithConstraints] so
 * we can map gesture x → fraction without hand-rolling a layout
 * sampler. Used today for the neon-strength tweak; the caller
 * persists the new value to `TweaksRepository.setNeonStrength`.
 *
 * Label + percentage value sit on top in the canvas's standard
 * row style; the 4 dp track + 16 dp thumb live below. Drag
 * commits on every pointer move; tap jumps the thumb. The whole
 * row is a tap target so users can flick the percentage without
 * hitting the 16 dp thumb directly.
 */
@Composable
fun SettingsSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueLabel: (Float) -> String = { v -> "${(v * 100f).toInt()}%" },
) {
    val palette = BluetrackTheme.palette
    val min = valueRange.start
    val max = valueRange.endInclusive
    val span = (max - min).coerceAtLeast(0.0001f)
    val clamped = value.coerceIn(min, max)
    val fraction = ((clamped - min) / span).coerceIn(0f, 1f)
    val display = valueLabel(clamped)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    color = palette.fg0,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                hint?.let { Text(text = it, color = palette.fg2, fontSize = 11.sp) }
            }
            Text(
                text = display,
                color = palette.mintBright,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthDp = maxWidth
            val density = LocalDensity.current
            val widthPx = with(density) { widthDp.toPx() }
            val thumbDp = 16.dp
            val thumbX = (widthDp * fraction) - thumbDp / 2
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbDp)
                    .pointerInput(widthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                onValueChange(min + (offset.x / widthPx).coerceIn(0f, 1f) * span)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                onValueChange(min + (change.position.x / widthPx).coerceIn(0f, 1f) * span)
                            },
                        )
                    }.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onValueChange(min + (offset.x / widthPx).coerceIn(0f, 1f) * span)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Track rail (full width).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                )
                // Filled portion — proportional via width sized to fraction.
                Box(
                    modifier = Modifier
                        .width(widthDp * fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.mint),
                )
                // Thumb — positioned via offset.
                Box(
                    modifier = Modifier
                        .offset(x = thumbX.coerceAtLeast(0.dp))
                        .size(thumbDp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}
