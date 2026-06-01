package dev.xd.bluetrack.ui.shell

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
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
 * Situational ("state-driven") aura background.
 *
 * A dark field with one diffuse glow that hugs the bottom edge (so it
 * wraps the floating nav bar) while the top + centre stay deep black
 * for depth. The glow's colour, reach, brightness and breathing speed
 * are chosen by [AuroraState] and cross-fade smoothly (≈1.2 s,
 * [FastOutSlowInEasing]) whenever the state changes, so switching tabs
 * makes the light flow rather than snap.
 *
 * API 33+: an AGSL [RuntimeShader] (GPU, 60 fps). Older devices fall
 * back to a single tinted [Canvas] halo. `motionReduced` freezes the
 * breathing.
 */
enum class AuroraState {
    /** Idle / Hub / Hosts / Settings — calm cool teal↔violet breathing. */
    Calm,

    /** A HID host is connected — brighter, taller cyan↔azure glow. */
    Live,

    /** Diagnostics route — restrained steel blue-grey / cool white. */
    Diagnostics,
}

private data class AuroraParams(
    val colorA: Color,
    val colorB: Color,
    val glowHeight: Float,
    val intensity: Float,
    val speed: Float,
)

private fun AuroraState.params(): AuroraParams = when (this) {
    AuroraState.Calm -> AuroraParams(
        colorA = Color(0xFF18B5AE),
        colorB = Color(0xFF6E5CFF),
        glowHeight = 0.50f,
        intensity = 0.85f,
        speed = 1.0f,
    )
    AuroraState.Live -> AuroraParams(
        colorA = Color(0xFF2BD4FF),
        colorB = Color(0xFF3E76FF),
        glowHeight = 0.62f,
        intensity = 1.0f,
        speed = 1.15f,
    )
    AuroraState.Diagnostics -> AuroraParams(
        colorA = Color(0xFF8AA2C6),
        colorB = Color(0xFFE3ECF6),
        glowHeight = 0.42f,
        intensity = 0.7f,
        speed = 0.7f,
    )
}

@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    state: AuroraState = AuroraState.Calm,
    motionReduced: Boolean = false,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AuroraShader(modifier = modifier, state = state, motionReduced = motionReduced)
    } else {
        AuroraFallback(modifier = modifier, state = state, motionReduced = motionReduced)
    }
}

// language=AGSL
private const val AURORA_AGSL = """
uniform float2 resolution;
uniform float time;
uniform half3 colorA;
uniform half3 colorB;
uniform float glowHeight;
uniform float intensity;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float asp = resolution.x / resolution.y;

    // Bloom centre sits just below the bottom edge and drifts a little
    // horizontally so the glow breathes. glowHeight is the radial reach
    // (aspect-corrected) — small reach keeps the upper screen deep black.
    float2 c = float2(0.5 + 0.10 * sin(time * 0.22), 1.06);
    float d = distance(float2(uv.x * asp, uv.y), float2(c.x * asp, c.y));
    float g = smoothstep(glowHeight, 0.0, d);

    // Slow colour breathing between the state's two stops.
    float tt = 0.5 - 0.5 * cos(time * 0.20);
    half3 col = mix(colorA, colorB, tt) * g * intensity;

    // Soft tonemap: diffuse aura, never an acid-neon blowout; keeps deep
    // blacks for contrast/volume.
    col = col / (col + 0.9);
    return half4(col, 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AuroraShader(
    modifier: Modifier,
    state: AuroraState,
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
    val intensity by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-intensity",
    ) { it.params().intensity }
    val speed by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-speed",
    ) { it.params().speed }

    // Phase accumulator so a speed change never jumps the breathing
    // phase — we integrate dt * (live, animated) speed.
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
                    shader.setFloatUniform("colorA", colorA.red, colorA.green, colorA.blue)
                    shader.setFloatUniform("colorB", colorB.red, colorB.green, colorB.blue)
                    shader.setFloatUniform("glowHeight", glowHeight)
                    shader.setFloatUniform("intensity", intensity)
                    drawRect(brush)
                }
            },
    )
}

@Composable
private fun AuroraFallback(
    modifier: Modifier,
    state: AuroraState,
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
    val intensity by transition.animateFloat(
        transitionSpec = { tween(1200, easing = FastOutSlowInEasing) },
        label = "aurora-fb-intensity",
    ) { it.params().intensity }

    Canvas(modifier = modifier.fillMaxSize()) {
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
