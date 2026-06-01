package dev.xd.bluetrack.ui.shell

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Fill

/**
 * Animated "aura" background — a dark field with one dominant glowing
 * bloom (plus a faint same-family accent) that slowly breathes and
 * shifts hue. Cohesive on purpose: a single colour on screen at a
 * time, most of the field left near-black for contrast, the way the
 * Gemini reference reads — not a scatter of clashing hues.
 *
 * On API 33+ this is an AGSL [RuntimeShader] (smooth mesh gradient,
 * 60 fps, animated via a `time` uniform fed by
 * [withInfiniteAnimationFrameMillis]). On older devices it falls back
 * to a two-halo [Canvas] gradient.
 *
 * `motionReduced` freezes the animation (static frame) but keeps the
 * glow visible.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    motionReduced: Boolean = false,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AuroraShader(modifier = modifier, motionReduced = motionReduced)
    } else {
        AuroraFallback(modifier = modifier, motionReduced = motionReduced)
    }
}

// language=AGSL
private const val AURORA_AGSL = """
uniform float2 resolution;
uniform float time;

// HSV→RGB so the wash can be pinned to a curated cool arc
// (teal → cyan → blue → violet → magenta) and never wander into
// muddy warm / olive tones. Keeps it vivid and premium.
half3 hsv2rgb(float h, float s, float v) {
    float3 p = abs(fract(float3(h, h, h) + float3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return half3(v * mix(float3(1.0), clamp(p - 1.0, 0.0, 1.0), s));
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float asp = resolution.x / resolution.y;
    float2 q = float2(uv.x * asp, uv.y);

    // Slow ping-pong across a curated cool hue arc (teal ↔ magenta).
    float phase = 0.5 - 0.5 * cos(time * 0.05);
    float hue = mix(0.46, 0.84, phase);

    // Dominant bloom anchored just below the bottom edge; faint accent
    // just above the top edge. Both drift slightly so the glow breathes.
    float2 p1 = float2(0.50 + 0.16 * sin(time * 0.12), 1.04 + 0.04 * sin(time * 0.10));
    float2 p2 = float2(0.30 + 0.16 * cos(time * 0.09), -0.06 + 0.05 * cos(time * 0.15));

    float d1 = distance(q, float2(p1.x * asp, p1.y));
    float d2 = distance(q, float2(p2.x * asp, p2.y));

    float g1 = smoothstep(0.95, 0.0, d1);
    float g2 = smoothstep(0.70, 0.0, d2);

    half3 col = hsv2rgb(hue, 0.85, 1.0) * g1 + hsv2rgb(hue + 0.05, 0.9, 1.0) * g2 * 0.5;

    // Soft tonemap: lifts the glow but never blows out to white, so the
    // result stays matte/premium with deep blacks (high contrast).
    col = col * 1.25;
    col = col / (col + 0.65);

    return half4(col, 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AuroraShader(
    modifier: Modifier,
    motionReduced: Boolean,
) {
    val shader = remember { RuntimeShader(AURORA_AGSL) }
    val brush = remember { ShaderBrush(shader) }

    val time by if (motionReduced) {
        remember { androidx.compose.runtime.mutableStateOf(6f) }
    } else {
        produceState(0f) {
            val start = withInfiniteAnimationFrameMillis { it }
            while (true) {
                withInfiniteAnimationFrameMillis { millis ->
                    value = (millis - start) / 1000f
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    shader.setFloatUniform("resolution", size.width, size.height)
                    shader.setFloatUniform("time", time)
                    drawRect(brush)
                }
            },
    )
}

@Composable
private fun AuroraFallback(
    modifier: Modifier,
    motionReduced: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "aurora-fallback")
    val hue by transition.animateFloat(
        initialValue = 200f,
        targetValue = 320f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aurora-fallback-hue",
    )
    val h = if (motionReduced) 250f else hue
    val glow = Color.hsv(h, 0.75f, 1f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val hgt = size.height
        // One dominant bottom bloom + faint top accent, same hue.
        drawHalo(Offset(w * 0.5f, hgt * 1.02f), w * 1.05f, glow, 0.5f)
        drawHalo(Offset(w * 0.3f, -hgt * 0.04f), w * 0.6f, glow, 0.18f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHalo(
    center: Offset,
    radius: Float,
    color: Color,
    opacity: Float,
) {
    val scaled = color.copy(alpha = (color.alpha * opacity).coerceIn(0f, 1f))
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to scaled,
                1f to Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        size = Size(size.width, size.height),
        style = Fill,
    )
}
