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

    // DualSense-style cross: each arm is a tall pill oriented
    // along its own axis so adjacent arrows share parallel
    // edges (not corners). Up / Down are 28 dp wide x 40 dp
    // tall, Left / Right swap dimensions. With centres offset
    // 30 dp from origin, edges just touch — no overlap, all
    // four reading as one continuous cross.
    data class Arm(
        val hat: Int,
        val dx: Int,
        val dy: Int,
        val glyph: String,
        val vertical: Boolean,
    )
    val dirs = listOf(
        Arm(hat = 0, dx = 0, dy = -1, glyph = "↑", vertical = true),
        Arm(hat = 2, dx = 1, dy = 0, glyph = "→", vertical = false),
        Arm(hat = 4, dx = 0, dy = 1, glyph = "↓", vertical = true),
        Arm(hat = 6, dx = -1, dy = 0, glyph = "←", vertical = false),
    )
    Box(
        modifier = modifier.size(112.dp),
    ) {
        dirs.forEach { arm ->
            val isActive = active == arm.hat
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        width = if (arm.vertical) 28.dp else 40.dp,
                        height = if (arm.vertical) 40.dp else 28.dp,
                    ).offset(x = (arm.dx * 30).dp, y = (arm.dy * 30).dp)
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
                                active = arm.hat
                                onHat(arm.hat)
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
                    text = arm.glyph,
                    color = if (isActive) Color.White else palette.fg1,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
