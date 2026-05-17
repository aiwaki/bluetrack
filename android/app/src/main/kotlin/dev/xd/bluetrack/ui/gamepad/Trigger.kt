package dev.xd.bluetrack.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * Shoulder / trigger button. Used for LB / LT / RB / RT — all four
 * share the same shape and only differ by label.
 *
 * Press = `mintBright → mintDeep` gradient, 18 dp mint glow, white
 * label. Release = subtle glass tint + hairline border + fg-1
 * label. The optional "DIGITAL" suffix in the tail lights up only
 * on triggers (`digital = true`) so the user can see at a glance
 * which controls offer pressure (none, in this build — composite
 * report carries digital triggers only).
 */
@Composable
fun Trigger(
    label: String,
    digital: Boolean,
    onChange: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(BluetrackTokens.RadiusSm)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(
                if (pressed) {
                    Brush.verticalGradient(
                        colors = listOf(palette.mintBright, palette.mintDeep),
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.04f),
                            Color.Black.copy(alpha = 0.3f),
                        ),
                    )
                },
            ).border(
                1.dp,
                if (pressed) Color.Transparent else palette.glassBorder,
                shape,
            ).padding(horizontal = BluetrackTokens.Sp3)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onChange(true)
                        val released = tryAwaitRelease()
                        pressed = false
                        onChange(false)
                        @Suppress("UNUSED_EXPRESSION")
                        released
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = if (pressed) Color.White else palette.fg1,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
        )
        if (digital) {
            Text(
                text = "DIGITAL",
                color = (if (pressed) Color.White else palette.fg2).copy(alpha = 0.6f),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.4.sp,
            )
        }
    }
}
