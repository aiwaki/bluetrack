package dev.xd.bluetrack.ui.hub

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Three-part Bluetrack wordmark: `[Blue·track]`.
 *
 *  - Mint brackets with a soft glow (canvas `text-shadow: 0 0 8px
 *    var(--mint-glow)`), approximated via a [Shadow] span.
 *  - Mid-dot rendered with the brighter `mintBright` to make the
 *    glyph carry a hint of inner light (canvas `wm-cut` 10 px glow).
 *
 * Compose has no per-glyph CSS text-shadow, but `SpanStyle.shadow`
 * compiles to a soft drop shadow on each affected span which reads
 * close enough on dark backgrounds. The wordmark sits at a small
 * cap-height (11–12 sp) inside `HubHeader`, so the eye reads the
 * brand silhouette before any shadow detail.
 */
@Composable
fun Wordmark(
    size: TextUnit = 12.sp,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val annotated: AnnotatedString = buildAnnotatedString {
        val bracketStyle = SpanStyle(
            color = palette.mint,
            fontWeight = FontWeight.Medium,
            shadow = Shadow(color = palette.mintGlow, blurRadius = 8f),
        )
        val nameStyle = SpanStyle(
            color = palette.mint,
            fontWeight = FontWeight.Medium,
        )
        val dotStyle = SpanStyle(
            color = palette.mintBright,
            fontWeight = FontWeight.Bold,
            shadow = Shadow(color = palette.mintGlow, blurRadius = 10f),
        )
        withStyle(bracketStyle) { append("[") }
        withStyle(nameStyle) { append("Blue") }
        withStyle(dotStyle) { append("·") }
        withStyle(nameStyle) { append("track") }
        withStyle(bracketStyle) { append("]") }
    }
    Row(modifier = modifier) {
        Text(
            text = annotated,
            fontSize = size,
            fontFamily = FontFamily.SansSerif,
        )
    }
}
