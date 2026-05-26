package dev.xd.bluetrack.ui.gamepad

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.rememberStaggerModifier
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import kotlinx.coroutines.delay

/**
 * Full landscape gamepad surface — redesigned 2026-05-20.
 *
 * Layout (landscape):
 *
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │  ← EXIT       ● GAMEPAD LIVE · MacBook · XINPUT · 16-BTN     │  ← top status
 *   ├──────────────────────────────────────────────────────────────┤
 *   │       L2 ▓          L1 ━                R1 ━          ▓ R2  │  ← shoulders / triggers
 *   │                                                              │
 *   │  ◉ L-stick   D-pad   SELECT  ● HOME ●  START   ABXY  R-stick ◉│
 *   │                                                              │
 *   ├──────────────────────────────────────────────────────────────┤
 *   │  POLL 1000   LAT 6.2 ms   REPORTS 42 k   UPTIME 3:41         │  ← stats
 *   └──────────────────────────────────────────────────────────────┘
 *
 * Pure presentation + local press / drag state. Stick drags fire
 * [onStickMotion] (left = source `"L"`, right = `"R"`), button /
 * trigger / D-pad / face presses fire [onButton]; the host
 * activity wires those into `MainViewModel` / the HID transport.
 */
@Composable
fun GamepadSurface(
    hostName: String,
    seq: Long,
    pulse: Boolean,
    onExit: () -> Unit,
    onStickMotion: (source: String, x: Float, y: Float) -> Unit,
    onButton: (label: String, pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pollHz: Int = 0,
    latencyMs: Float = 0f,
    reportsTotal: Long = 0L,
    uptimeMs: Long = 0L,
) {
    val palette = BluetrackTheme.palette
    // Slow red breath pulse drawn behind everything else — the
    // user-flagged "приятная тусклая красная пульсация по центру".
    // Sits at low alpha (0.06 → 0.16) so it reads as ambient
    // background warmth without competing with the controls.
    val pulseTransition = rememberInfiniteTransition(label = "gamepad-bg-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gamepad-bg-pulse-alpha",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg0)
            .drawBehind {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = (size.width.coerceAtLeast(size.height)) * 0.65f
                // Deep red — more saturated / darker than the
                // bright `palette.crit` so the pulse reads as
                // "warm low-light glow" rather than "warning".
                val deep = Color(0xFF8B0000)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            deep.copy(alpha = pulseAlpha),
                            deep.copy(alpha = pulseAlpha * 0.5f),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Respect system bars + display cutout so the top
                // rail clears the clock band and the L2 / R2
                // vertical triggers do not overlap the camera
                // notch in landscape.
                .windowInsetsPadding(WindowInsets.systemBars)
                .displayCutoutPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Cascading entry on flip — same shared stagger helper
            // the Hub / Diagnostics / Settings / Hosts / Activity
            // routes use. Five zones fade-and-lift in sequence (top
            // rail → L stick → center → R stick → bottom stats) so
            // the gamepad feels like it boots up rather than pops.
            Box(modifier = rememberStaggerModifier(index = 0)) {
                TopStatusRail(
                    hostName = hostName,
                    onExit = onExit,
                )
            }

            // Body row: 5 zones from left to right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LeftThumbStack(
                    onStickMotion = onStickMotion,
                    onButton = onButton,
                    modifier = Modifier
                        .weight(1f)
                        .then(rememberStaggerModifier(index = 1)),
                )
                CenterStack(
                    seq = seq,
                    pulse = pulse,
                    onButton = onButton,
                    modifier = Modifier
                        .weight(1.2f)
                        .then(rememberStaggerModifier(index = 2)),
                )
                RightThumbStack(
                    onStickMotion = onStickMotion,
                    onButton = onButton,
                    modifier = Modifier
                        .weight(1f)
                        .then(rememberStaggerModifier(index = 3)),
                )
            }

            Box(modifier = rememberStaggerModifier(index = 4)) {
                BottomStatsRail(
                    pollHz = pollHz,
                    latencyMs = latencyMs,
                    reportsTotal = reportsTotal,
                    uptimeMs = uptimeMs,
                )
            }
        }
    }
}

// ─── Top status rail ────────────────────────────────────────

@Composable
private fun TopStatusRail(
    hostName: String,
    onExit: () -> Unit,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Exit pill — leading.
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, palette.glassBorder, RoundedCornerShape(999.dp))
                .clickable(onClick = onExit)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "‹",
                color = palette.fg1,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "EXIT",
                color = palette.fg1,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Status badges.
        StatusDotLabel(label = "GAMEPAD LIVE", color = palette.crit)
        StatusDivider()
        Text(
            text = hostName,
            color = palette.fg2,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        StatusDivider()
        Text(
            text = "XINPUT · 16-BTN",
            color = palette.fg2,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
private fun StatusDotLabel(label: String, color: Color) {
    val palette = BluetrackTheme.palette
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            color = palette.fg1,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
private fun StatusDivider() {
    val palette = BluetrackTheme.palette
    Text(
        text = "·",
        color = palette.fg3,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

// ─── Left thumb stack: L-stick + D-pad + L1/L2 ─────────────

@Composable
private fun LeftThumbStack(
    onStickMotion: (String, Float, Float) -> Unit,
    onButton: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Outer rail: L2 vertical bar.
        VerticalTriggerBar(
            label = "L2",
            digital = true,
            onChange = { p -> onButton("LT", p) },
            modifier = Modifier.fillMaxHeight(0.85f),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalShoulderPill(
                label = "L1",
                onChange = { p -> onButton("LB", p) },
            )
            Stick(
                label = "L",
                onChange = { x, y -> onStickMotion("L", x, y) },
                modifier = Modifier.size(124.dp),
                onPress = { pressed -> onButton("L3", pressed) },
            )
            DPad(onHat = { hat -> onButton("HAT_$hat", hat != 8) })
        }
    }
}

// ─── Right thumb stack: R-stick + ABXY + R1/R2 ────────────

@Composable
private fun RightThumbStack(
    onStickMotion: (String, Float, Float) -> Unit,
    onButton: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalShoulderPill(
                label = "R1",
                onChange = { p -> onButton("RB", p) },
            )
            FaceButtons(onChange = onButton)
            Stick(
                label = "R",
                onChange = { x, y -> onStickMotion("R", x, y) },
                modifier = Modifier.size(124.dp),
                onPress = { pressed -> onButton("R3", pressed) },
            )
        }
        VerticalTriggerBar(
            label = "R2",
            digital = true,
            onChange = { p -> onButton("RT", p) },
            modifier = Modifier.fillMaxHeight(0.85f),
        )
    }
}

// ─── Center: SELECT · HOME · START · frame counter ──────

@Composable
private fun CenterStack(
    seq: Long,
    pulse: Boolean,
    onButton: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CenterPillButton(
                label = "SELECT",
                onChange = { p -> onButton("BACK", p) },
            )
            HomeButton(onChange = { p -> onButton("GUIDE", p) })
            CenterPillButton(
                label = "START",
                onChange = { p -> onButton("START", p) },
            )
        }
        FrameBadge(seq = seq, pulse = pulse, palette = palette)
    }
}

@Composable
private fun CenterPillButton(
    label: String,
    onChange: (Boolean) -> Unit,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessMedium)
        } else {
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "center-pill-press",
    )
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .height(34.dp)
            .scale(pressScale)
            .clip(shape)
            .background(
                if (pressed) palette.crit.copy(alpha = 0.18f) else palette.bg2,
            ).border(
                1.dp,
                if (pressed) palette.crit else palette.glassBorder,
                shape,
            ).padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        onChange(true)
                        tryAwaitRelease()
                        pressed = false
                        onChange(false)
                    },
                )
            }.wrapContentSize(Alignment.Center),
    ) {
        Text(
            text = label,
            color = if (pressed) palette.crit else palette.fg1,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

/**
 * Large central HOME button (Guide / Xbox button equivalent).
 * Renders a red disc with a glow ring on press. Primary visual
 * anchor of the surface.
 */
@Composable
private fun HomeButton(onChange: (Boolean) -> Unit) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    // Spring the 58dp ↔ 64dp size shift instead of snapping
    // so the home button breathes when pressed. Press uses a
    // medium-stiff spring (snappy hit), release uses a
    // low-stiff bouncy spring for the rebound cap feel.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 1f else 58f / 64f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessMedium)
        } else {
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "home-press",
    )
    Box(
        modifier = Modifier
            .size(64.dp)
            .scale(pressScale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        palette.crit,
                        palette.crit.copy(alpha = 0.85f),
                        palette.crit.copy(alpha = 0.65f),
                    ),
                ),
            ).border(
                width = if (pressed) 2.dp else 1.dp,
                color = if (pressed) Color.White.copy(alpha = 0.5f) else palette.crit.copy(alpha = 0.4f),
                shape = CircleShape,
            ).pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        onChange(true)
                        tryAwaitRelease()
                        pressed = false
                        onChange(false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "HOME",
            color = Color.White,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
private fun FrameBadge(
    seq: Long,
    pulse: Boolean,
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.bg2)
            .border(1.dp, palette.hairline, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (pulse) palette.crit else palette.fg3),
        )
        Text(
            text = "FRAME · #$seq",
            color = palette.fg2,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
        )
    }
}

// ─── Shoulder triggers ──────────────────────────────────────

/**
 * Horizontal pill for L1 / R1 (digital bumpers). Mint glow on
 * press matches the `Trigger` composable but stays compact for
 * the corner placement called out in the canvas reference.
 */
@Composable
private fun HorizontalShoulderPill(
    label: String,
    onChange: (Boolean) -> Unit,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessMedium)
        } else {
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "shoulder-pill-press-$label",
    )
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .width(110.dp)
            .height(28.dp)
            .scale(pressScale)
            .clip(shape)
            .background(
                if (pressed) {
                    Brush.verticalGradient(listOf(palette.crit, palette.crit.copy(alpha = 0.7f)))
                } else {
                    Brush.verticalGradient(listOf(palette.bg2, palette.bg3))
                },
            ).border(
                1.dp,
                if (pressed) Color.Transparent else palette.glassBorder,
                shape,
            ).pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        onChange(true)
                        tryAwaitRelease()
                        pressed = false
                        onChange(false)
                    },
                )
            }.wrapContentSize(Alignment.Center),
    ) {
        Text(
            text = label,
            color = if (pressed) Color.White else palette.fg1,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

/**
 * Vertical trigger bar for L2 / R2 — full-height analog feel
 * even though we only emit the digital press bit.
 */
@Composable
private fun VerticalTriggerBar(
    label: String,
    digital: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessMedium)
        } else {
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "trigger-bar-press-$label",
    )
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(28.dp)
            .scale(pressScale)
            .clip(shape)
            .background(
                if (pressed) {
                    Brush.verticalGradient(listOf(palette.crit.copy(alpha = 0.7f), palette.crit))
                } else {
                    Brush.verticalGradient(listOf(palette.bg3, palette.bg2))
                },
            ).border(
                1.dp,
                if (pressed) Color.Transparent else palette.glassBorder,
                shape,
            ).pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        onChange(true)
                        tryAwaitRelease()
                        pressed = false
                        onChange(false)
                    },
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = label,
            color = if (pressed) Color.White else palette.fg1,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        @Suppress("UNUSED_EXPRESSION")
        digital
    }
}

// ─── Bottom stats rail ─────────────────────────────────────

@Composable
private fun BottomStatsRail(
    pollHz: Int,
    latencyMs: Float,
    reportsTotal: Long,
    uptimeMs: Long,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatPair("POLL", "$pollHz Hz")
        StatPair("LAT", "${"%.1f".format(latencyMs)} ms")
        StatPair("REPORTS", compactCountForStat(reportsTotal))
        StatPair("UPTIME", uptimeLabel(uptimeMs))
        @Suppress("UNUSED_EXPRESSION")
        palette
    }
}

@Composable
private fun StatPair(label: String, value: String) {
    val palette = BluetrackTheme.palette
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = palette.fg3,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            color = palette.fg1,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun compactCountForStat(value: Long): String =
    when {
        value < 1_000L -> value.toString()
        value < 1_000_000L -> "${value / 1000L}.${(value % 1000L) / 100L} k"
        else -> "${value / 1_000_000L}.${(value % 1_000_000L) / 100_000L} M"
    }

private fun uptimeLabel(ms: Long): String {
    if (ms <= 0L) return "—"
    val secs = ms / 1000L
    val h = secs / 3600L
    val m = (secs % 3600L) / 60L
    val s = secs % 60L
    return if (h > 0L) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

// ─── Frame counter loop (kept) ──────────────────────────────

/**
 * Convenience holder for the 130 ms frame-counter pulse. Drives
 * [FrameCounterState.seq] forward and toggles [FrameCounterState.pulse]
 * for ~80 ms each step.
 */
@Composable
fun rememberFrameCounterState(): FrameCounterState {
    val seq = remember { mutableLongStateOf(48_217L) }
    val pulse = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(130L)
            seq.longValue += 1
            pulse.value = true
            delay(80L)
            pulse.value = false
        }
    }
    return FrameCounterState(seq.longValue, pulse.value)
}

data class FrameCounterState(
    val seq: Long,
    val pulse: Boolean,
)
