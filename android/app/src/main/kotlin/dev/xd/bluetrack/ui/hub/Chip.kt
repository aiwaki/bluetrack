package dev.xd.bluetrack.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Canvas `Chip` atom — 5 colour variants matching the design
 * system tokens.
 *
 *  | kind     | bg                | fg               | border         |
 *  |----------|-------------------|------------------|----------------|
 *  | default  | white α 6 %       | fg-1             | hairline       |
 *  | live     | mint-glow-soft    | mintBright       | transparent    |
 *  | cool     | cool α 14 %       | cool             | transparent    |
 *  | warn     | warn α 14 %       | warn             | transparent    |
 *  | calm     | white α 5 %       | calm             | hairline       |
 *
 * 24 dp tall pill, 10 px horizontal padding, mono caps label —
 * matches `Chip` in `docs/design/v1/shared.jsx`.
 */
enum class ChipKind { Default, Live, Cool, Warn, Calm }

@Composable
fun Chip(
    text: String,
    kind: ChipKind = ChipKind.Default,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(999.dp)
    val bg: Color
    val fg: Color
    val border: Color
    when (kind) {
        ChipKind.Live -> {
            bg = palette.mintGlowSoft
            fg = palette.mintBright
            border = Color.Transparent
        }
        ChipKind.Cool -> {
            bg = palette.cool.copy(alpha = 0.14f)
            fg = palette.cool
            border = Color.Transparent
        }
        ChipKind.Warn -> {
            bg = palette.warn.copy(alpha = 0.14f)
            fg = palette.warn
            border = Color.Transparent
        }
        ChipKind.Calm -> {
            bg = Color.White.copy(alpha = 0.05f)
            fg = palette.calm
            border = palette.hairline
        }
        ChipKind.Default -> {
            bg = Color.White.copy(alpha = 0.06f)
            fg = palette.fg1
            border = palette.hairline
        }
    }
    Row(
        modifier = modifier
            .height(24.dp)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(
            text = text.uppercase(),
            color = fg,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
        )
    }
}
