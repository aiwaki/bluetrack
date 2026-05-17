package dev.xd.bluetrack.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * 4-way D-pad. Canvas value 0..7 hat encoding maps to the
 * standard composite gamepad report's hat-switch byte (Up = 0,
 * Right = 2, Down = 4, Left = 6, neutral = 8).
 *
 * Each direction is a 32 dp pill positioned 22 dp from centre.
 * Press = mint gradient + 18 dp mint glow + filled arrow glyph.
 * Release = dark vertical gradient + outlined arrow.
 *
 * The composable reports the active direction via [onHat] using
 * the hat-switch convention so the host-side report builder
 * (`TranslationEngine`) can drop the value straight into the
 * report byte without remapping.
 */
@Composable
fun DPad(
    onHat: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    var active by remember { mutableStateOf<Int?>(null) }
    val dirs = listOf(
        Triple(0, 0 to -1, "↑"),
        Triple(2, 1 to 0, "→"),
        Triple(4, 0 to 1, "↓"),
        Triple(6, -1 to 0, "←"),
    )
    Box(
        modifier = modifier.size(88.dp),
    ) {
        dirs.forEach { (hatValue, xy, glyph) ->
            val (dx, dy) = xy
            val isActive = active == hatValue
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
                    .offset(x = (dx * 22).dp, y = (dy * 22).dp)
                    .clip(RoundedCornerShape(BluetrackTokens.RadiusXs))
                    .background(
                        if (isActive) {
                            Brush.verticalGradient(
                                colors = listOf(palette.mintBright, palette.mintDeep),
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF2A2C2E), Color(0xFF15171A)),
                            )
                        },
                    ).border(
                        1.dp,
                        if (isActive) Color.Transparent else palette.glassBorder,
                        RoundedCornerShape(BluetrackTokens.RadiusXs),
                    ).pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                active = hatValue
                                onHat(hatValue)
                                val released = tryAwaitRelease()
                                active = null
                                onHat(8)
                                @Suppress("UNUSED_EXPRESSION")
                                released
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    color = if (isActive) Color.White else palette.fg1,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
