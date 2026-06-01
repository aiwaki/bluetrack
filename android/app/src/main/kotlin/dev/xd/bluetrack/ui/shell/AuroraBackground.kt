package dev.xd.bluetrack.ui.shell

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Fill
import kotlinx.coroutines.isActive

/**
 * Situational ("state-driven") ambient aura.
 *
 * A deep base (near-black in dark theme, near-white in light) with one
 * diffuse glow hugging the bottom edge so it wraps the floating nav
 * bar; the top + centre stay clean for depth. Over the glow sits an
 * animated stipple (dot-screen) texture that breathes — subtle dimples
 * whose size pulses and whose grid micro-drifts, giving the surface a
 * living digital texture without any Frutiger-Aero gloss.
 *
 * Each [AuroraState] (one per route, plus a connected "Live" variant)
 * carries its own cool-family palette / reach / speed, and they
 * cross-fade over ≈1.2 s ([FastOutSlowInEasing]) on change, so moving
 * between tabs makes the light flow.
 *
 * API 33+: an AGSL [RuntimeShader] (GPU, 60 fps). Older devices fall
 * back to a single tinted [Canvas] halo (no stipple). `motionReduced`
 * freezes the animation.
 */
enum class AuroraState { Calm, Live, Diagnostics, Hosts, Activity, Settings }

private data class AuroraParams(
    val colorA: Color,
    val colorB: Color,
    val glowHeight: Float,
    val intensity: Float,
    val speed: Float,
)

private fun AuroraState.params(): AuroraParams = when (this) {
    AuroraState.Calm -> AuroraParams(Color(0xFF18B5AE), Color(0xFF6E5CFF), 0.50f, 0.85f, 1.00f)
    AuroraState.Live -> AuroraParams(Color(0xFF2BD4FF), Color(0xFF3E76FF), 0.62f, 1.00f, 1.15f)
    AuroraState.Diagnostics -> AuroraParams(Color(0xFF8AA2C6), Color(0xFFE3ECF6), 0.42f, 0.70f, 0.70f)
    AuroraState.Hosts -> AuroraParams(Color(0xFF1FCF9A), Color(0xFF2BD4FF), 0.50f, 0.85f, 0.95f)
    AuroraState.Activity -> AuroraParams(Color(0xFF7A5CFF), Color(0xFFC95CFF), 0.50f, 0.85f, 0.90f)
    AuroraState.Settings -> AuroraParams(Color(0xFF3E76FF), Color(0xFF5C6CFF), 0.46f, 0.80f, 0.85f)
}

@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    state: AuroraState = AuroraState.Calm,
    darkTheme: Boolean = true,
    motionReduced: Boolean = false,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AuroraShader(modifier = modifier, state = state, darkTheme = darkTheme, motionReduced = motionReduced)
    } else {
        AuroraFallback(modifier = modifier, state = state, darkTheme = darkTheme, motionReduced = motionReduced)
    }
}

// language=AGSL
private const val AURORA_AGSL = """
uniform float2 resolution;
uniform float time;
uniform half3 baseColor;
uniform half3 colorA;
uniform half3 colorB;
uniform float glowHeight;
uniform float intensity;
uniform float dotStrength;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float asp = resolution.x / resolution.y;

    // Bloom centred just below the bottom edge, drifting a little so it
    // breathes. glowHeight is the radial reach — small reach keeps the
    // upper screen at the clean base colour.
    float2 c = float2(0.5 + 0.10 * sin(time * 0.22), 1.06);
    float d = distance(float2(uv.x * asp, uv.y), float2(c.x * asp, c.y));
    float g = smoothstep(glowHeight, 0.0, d);

    float tt = 0.5 - 0.5 * cos(time * 0.20);
    half3 glow = mix(colorA, colorB, tt);
    half3 col = mix(baseColor, glow, g * intensity);

    // Animated stipple (dot screen): a fine grid of dimples gated to the
    // glow region. The grid micro-drifts and each dot's radius pulses on
    // a per-cell phase, so the texture "breathes".
    float spacing = 9.0;
    float2 gr = fragCoord / spacing + float2(sin(time * 0.15), cos(time * 0.12)) * 0.6;
    float2 cell = fract(gr) - 0.5;
    float dd = length(cell);
    float pulse = 0.32 + 0.08 * sin(time * 0.9 + (floor(gr).x + floor(gr).y));
    float dot = smoothstep(pulse, pulse - 0.10, dd);
    col = col * (1.0 - dot * dotStrength * g);

    return half4(col, 1.0);
}
"""

private fun baseColorFor(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF07080C) else Color(0xFFF6F8FB)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AuroraShader(
    modifier: Modifier,
    state: AuroraState,
    darkTheme: Boolean,
    motionReduced: Boolean,
) {
    val shader = remember { RuntimeShader(AURORA_AGSL) }
    val brush = remember { ShaderBrush(shader) }

    val transition = updateTransition(targetState = state, label = "aurora-state")
    val colorA by transition.animateColor(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-colorA",
    ) { it.params().colorA }
    val colorB by transition.animateColor(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-colorB",
    ) { it.params().colorB }
    val glowHeight by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-glow",
    ) { it.params().glowHeight }
    val intensityBase by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-intensity",
    ) { it.params().intensity }
    val speed by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-speed",
    ) { it.params().speed }

    // Light theme: pull the glow back so it reads as a soft pastel wash
    // on white instead of a saturated bloom.
    val intensity = intensityBase * if (darkTheme) 1.0f else 0.5f
    val baseColor = baseColorFor(darkTheme)
    val dotStrength = if (darkTheme) 0.12f else 0.08f

    var time by remember { mutableStateOf(0f) }
    LaunchedEffect(motionReduced) {
        if (motionReduced) {
            time = 6f
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            withInfiniteAnimationFrameMillis { ms ->
                if (last != 0L) time += (ms - last) / 1000f * speed
                last = ms
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
                    shader.setFloatUniform("baseColor", baseColor.red, baseColor.green, baseColor.blue)
                    shader.setFloatUniform("colorA", colorA.red, colorA.green, colorA.blue)
                    shader.setFloatUniform("colorB", colorB.red, colorB.green, colorB.blue)
                    shader.setFloatUniform("glowHeight", glowHeight)
                    shader.setFloatUniform("intensity", intensity)
                    shader.setFloatUniform("dotStrength", dotStrength)
                    drawRect(brush)
                }
            },
    )
}

@Composable
private fun AuroraFallback(
    modifier: Modifier,
    state: AuroraState,
    darkTheme: Boolean,
    motionReduced: Boolean,
) {
    val transition = updateTransition(targetState = state, label = "aurora-state-fb")
    val color by transition.animateColor(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-fb-color",
    ) { it.params().colorA }
    val glowHeight by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-fb-glow",
    ) { it.params().glowHeight }
    val intensityBase by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-fb-intensity",
    ) { it.params().intensity }

    val intensity = intensityBase * if (darkTheme) 1.0f else 0.5f
    val base = baseColorFor(darkTheme)

    Box(modifier = modifier.fillMaxSize().background(base)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to color.copy(alpha = (0.5f * intensity).coerceIn(0f, 1f)),
                        1f to Color.Transparent,
                    ),
                    center = Offset(w * 0.5f, h * 1.06f),
                    radius = w * (0.55f + glowHeight),
                ),
                size = Size(w, h),
                style = Fill,
            )
        }
    }
}
