package dev.xd.bluetrack.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Trio of small pill buttons living under the D-pad in the canvas
 * layout: BACK / guide (◉) / START. Press the guide button to
 * surface an OS overlay on hosts that support the Xbox guide HID
 * usage; in this build we just forward the press to [onPress]
 * which the report builder maps to the report's button bitfield.
 *
 * The guide button has a faint mint glow so it stands out as the
 * privileged middle pill.
 */
@Composable
fun CenterRail(
    onPress: (label: String, pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CenterBtn(label = "BACK", glyph = null, guide = false, onPress = onPress)
        CenterBtn(label = "GUIDE", glyph = "◉", guide = true, onPress = onPress)
        CenterBtn(label = "START", glyph = null, guide = false, onPress = onPress)
    }
}

@Composable
private fun CenterBtn(
    label: String,
    glyph: String?,
    guide: Boolean,
    onPress: (String, Boolean) -> Unit,
) {
    val palette = BluetrackTheme.palette
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    val bg = when {
        pressed -> palette.mint
        guide -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val fg = when {
        pressed -> Color.White
        guide -> palette.mintBright
        else -> palette.fg1
    }
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(shape)
            .background(bg, shape)
            .border(
                1.dp,
                if (pressed) Color.Transparent else palette.glassBorder,
                shape,
            ).padding(horizontal = if (glyph != null) 8.dp else 12.dp)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPress(label, true)
                        val released = tryAwaitRelease()
                        pressed = false
                        onPress(label, false)
                        @Suppress("UNUSED_EXPRESSION")
                        released
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph ?: label,
            color = fg,
            fontSize = if (glyph != null) 14.sp else 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
        )
    }
}
