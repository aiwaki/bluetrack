package dev.xd.bluetrack.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackPalette
import dev.xd.bluetrack.ui.theme.BluetrackTheme

/**
 * Three-page first-run flow.
 *
 *   01 Welcome      → tagline + value prop
 *   02 Permissions  → three live grant rows + Grant access CTA
 *   03 Pair         → "open Bluetooth on your host" hint + Done
 *
 * Lean redesign 2026-05-24: drops the decorative PulseHero in
 * favour of a single brand-mark glyph and tighter typography so
 * the flow reads cleanly on both light and dark palettes. Every
 * surface pulls from `palette` — no hard-coded greys, no glass
 * tints that hide on the light surface.
 */
@Composable
fun WelcomeScreen(
    onFinish: () -> Unit,
    onRequestPermissions: () -> Unit,
    nearbyPermissionGranted: Boolean,
    notificationsPermissionGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var page by remember { mutableIntStateOf(0) }
    val totalPages = 3

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg0)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        TopRow(
            page = page,
            totalPages = totalPages,
            palette = palette,
            onBack = { if (page > 0) page -= 1 },
        )
        Spacer(modifier = Modifier.height(28.dp))

        // Page swap follows the dock idiom — slide horizontal in
        // the navigation direction, fade for the crossover. 240ms
        // FastOutSlowInEasing reads as a deliberate page turn.
        // AnimatedContent owns the weight(1f) slot directly so
        // every page composable gets the same height — wrapping it
        // in an extra Column flattened the pages to wrap_content
        // and broke the layout.
        androidx.compose.animation.AnimatedContent(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            targetState = page,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                val durMs = 240
                androidx.compose.animation.slideInHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(
                        durMs,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    initialOffsetX = { full -> direction * full / 5 },
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core
                        .tween(durMs),
                ) togetherWith androidx.compose.animation.slideOutHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(
                        durMs,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    targetOffsetX = { full -> -direction * full / 5 },
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core
                        .tween(durMs),
                )
            },
            label = "welcome-page-swap",
        ) { pageIndex ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (pageIndex) {
                    0 -> PageWelcome(palette = palette)
                    1 -> PagePermissions(
                        palette = palette,
                        nearbyPermissionGranted = nearbyPermissionGranted,
                        notificationsPermissionGranted = notificationsPermissionGranted,
                    )
                    else -> PagePair(palette = palette)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        PageDots(current = page, total = totalPages, palette = palette)
        Spacer(modifier = Modifier.height(20.dp))

        val ctaLabel = when (page) {
            0 -> "GET STARTED"
            1 -> "GRANT ACCESS"
            else -> "DONE"
        }
        CtaPill(
            label = ctaLabel,
            palette = palette,
            onClick = {
                when (page) {
                    0 -> page = 1
                    1 -> {
                        onRequestPermissions()
                        page = 2
                    }
                    else -> onFinish()
                }
            },
        )
        if (page == totalPages - 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        onFinish()
                    }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Skip — pair later",
                    color = palette.fg3,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun TopRow(
    page: Int,
    totalPages: Int,
    palette: BluetrackPalette,
    onBack: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page > 0) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, palette.hairline, CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = palette.fg1,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            BrandMark(palette = palette)
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "${(page + 1).toString().padStart(2, '0')} / ${totalPages.toString().padStart(2, '0')}",
            color = palette.fg3,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Small `[·]` glyph in the brand accent — replaces the noisy PulseHero. */
@Composable
private fun BrandMark(palette: BluetrackPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.crit.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(palette.crit),
            )
        }
        Text(
            text = "BLUETRACK",
            color = palette.fg1,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
        )
    }
}

// ─── Page 1: Welcome ───────────────────────────────────────

@Composable
private fun PageWelcome(palette: BluetrackPalette) {
    Text(
        text = "Your phone.",
        color = palette.fg0,
        fontSize = 40.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.4).sp,
        lineHeight = 42.sp,
    )
    Text(
        text = "Their mouse.",
        color = palette.crit,
        fontSize = 40.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.4).sp,
        lineHeight = 42.sp,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Turn this device into a wireless trackpad and gamepad over " +
            "Bluetooth HID. Low-latency. Works with macOS, Windows, Linux, " +
            "Android.",
        color = palette.fg2,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

// ─── Page 2: Permissions ──────────────────────────────────

@Composable
private fun PagePermissions(
    palette: BluetrackPalette,
    nearbyPermissionGranted: Boolean,
    notificationsPermissionGranted: Boolean,
) {
    Text(
        text = "Permissions.",
        color = palette.fg0,
        fontSize = 40.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.4).sp,
        lineHeight = 42.sp,
    )
    Text(
        text = "Bluetrack needs Bluetooth access to advertise itself to your " +
            "computer. Nothing leaves the device.",
        color = palette.fg2,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    PermissionRow(
        palette = palette,
        title = "Bluetooth nearby",
        body = "Find and talk to your paired host.",
        granted = nearbyPermissionGranted,
    )
    PermissionRow(
        palette = palette,
        title = "Notifications",
        body = "Show the foreground HID service banner (Android 13+).",
        granted = notificationsPermissionGranted,
    )
}

@Composable
private fun PermissionRow(
    palette: BluetrackPalette,
    title: String,
    body: String,
    granted: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    // Scale burst when the permission flips false → true. Snap to
    // 1.18 then springs back; reads as a small "granted!" pop that
    // confirms the user's action without sound or an extra label.
    val iconBurst = remember { Animatable(1f) }
    var prevGranted by remember { mutableStateOf(granted) }
    LaunchedEffect(granted) {
        if (granted && !prevGranted) {
            iconBurst.snapTo(1.18f)
            iconBurst.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        prevGranted = granted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.bg1)
            .border(1.dp, palette.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .scale(iconBurst.value)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (granted) palette.crit.copy(alpha = 0.85f) else palette.bg2,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (granted) "✓" else "→",
                color = if (granted) Color.White else palette.fg2,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = palette.fg0,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = palette.fg2,
                fontSize = 12.sp,
            )
        }
        Text(
            text = if (granted) "GRANTED" else "NEEDED",
            color = if (granted) palette.crit else palette.fg3,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

// ─── Page 3: Pair ──────────────────────────────────────────

@Composable
private fun PagePair(palette: BluetrackPalette) {
    Text(
        text = "Pair a host.",
        color = palette.fg0,
        fontSize = 40.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.4).sp,
        lineHeight = 42.sp,
    )
    Text(
        text = "Open Bluetooth settings on your Mac or PC. Bluetrack will " +
            "appear under your device's adapter name.",
        color = palette.fg2,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepCard(palette = palette, n = "1", label = "Open Bluetooth", modifier = Modifier.weight(1f))
        StepCard(palette = palette, n = "2", label = "Tap Bluetrack", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StepCard(
    palette: BluetrackPalette,
    n: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(palette.bg1)
            .border(1.dp, palette.hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = n,
            color = palette.crit,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = label,
            color = palette.fg1,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─── Page dots ────────────────────────────────────────────

@Composable
private fun PageDots(current: Int, total: Int, palette: BluetrackPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val active = i == current
            // Tween the dot's width on page change so the active
            // indicator slides between dots instead of jumping. 280ms
            // FastOutSlowInEasing mirrors the AnimatedContent page
            // swap so both finish around the same time.
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 280,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
                label = "welcome-dot-$i-width",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = width, height = 8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) palette.crit else palette.fg3.copy(alpha = 0.35f)),
            )
        }
    }
}

// ─── CTA ──────────────────────────────────────────────────

@Composable
private fun CtaPill(
    label: String,
    palette: BluetrackPalette,
    onClick: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Press-scale spring matches the gamepad / face-button polish —
    // 0.96 on press, springy LowBouncy / StiffnessLow release so the
    // pill feels like a tactile cap instead of a flat click target.
    // Haptic dropped from LongPress to TextHandleMove because the
    // earlier strong feedback read as "alert" in onboarding flow.
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessMedium)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            )
        },
        label = "welcome-cta-press",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .clip(RoundedCornerShape(999.dp))
            .background(palette.crit)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) onClick()
                    },
                )
            }.padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
        )
    }
}
