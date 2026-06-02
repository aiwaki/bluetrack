package dev.xd.bluetrack.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bluetrack design tokens, ported one-to-one from
 * `docs/design/v1/tokens.css`. Pure constants, no Compose runtime
 * deps beyond `Color` / `Dp` / `Easing` — UI code reads them via
 * [BluetrackTheme] so the surface is `MaterialTheme.colorScheme.*`
 * plus a `LocalBluetrackTokens.current` for the values Material does
 * not model (glow, glass alpha, motion curve).
 *
 * Dark is canonical; light is shipped at parity but the design
 * canvas labels it "WIP" — keep glow intensities conservative.
 */
data object BluetrackTokens {
    // ─── Dark surfaces ───────────────────────────────────────────────
    val DarkBg0 = Color(0xFF08090A)
    val DarkBg1 = Color(0xFF101214)
    val DarkBg2 = Color(0xFF181B1E)
    val DarkBg3 = Color(0xFF20242A)
    val DarkHairline = Color(0x12FFFFFF) // 7% white
    val DarkHairline2 = Color(0x24FFFFFF) // 14% white
    val DarkFg0 = Color(0xFFF4F5F4)
    val DarkFg1 = Color(0xBDF4F5F4) // 74%
    val DarkFg2 = Color(0x80F4F5F4) // 50%
    val DarkFg3 = Color(0x4DF4F5F4) // 30%

    // ─── Light surfaces ──────────────────────────────────────────────
    val LightBg0 = Color(0xFFF3F4F1)
    val LightBg1 = Color(0xFFFFFFFF)
    val LightBg2 = Color(0xFFFAFBF8)
    val LightBg3 = Color(0xFFECEDE8)
    val LightHairline = Color(0x140F1210) // 8% on light
    val LightHairline2 = Color(0x280F1210) // 16% on light
    val LightFg0 = Color(0xFF0D0E0F)
    val LightFg1 = Color(0xB80D0E0F) // 72%
    val LightFg2 = Color(0x800D0E0F) // 50%
    val LightFg3 = Color(0x4D0D0E0F) // 30%

    // ─── Accent (neon red) ───────────────────────────────────────────
    val MintDark = Color(0xFFFF2A3A)
    val MintBrightDark = Color(0xFFFF5566)
    val MintDeepDark = Color(0xFFC4001A)
    val MintGlowDark = Color(0x8CFF2A3A) // ~55%
    val MintGlowSoftDark = Color(0x2EFF2A3A) // ~18%

    val MintLight = Color(0xFFE6122A)
    val MintBrightLight = Color(0xFFFF3850)
    val MintDeepLight = Color(0xFFA8001A)
    val MintGlowLight = Color(0x66E6122A) // ~40%
    val MintGlowSoftLight = Color(0x29E6122A) // ~16%

    // ─── Semantic spot colours (same in dark + light) ────────────────
    val Cool = Color(0xFF6DD6FF)

    // Soft cool wash behind the selected Mouse surface card — the cool
    // counterpart to MintGlowSoft. The light variant uses a deeper, more
    // saturated cool at higher alpha so the Mouse face actually reads on
    // a white background (the old flat `cool.copy(alpha = 0.14f)` washed
    // out in the light theme).
    val CoolGlowSoftDark = Color(0x2E6DD6FF) // ~18%
    val CoolGlowSoftLight = Color(0x402AA8E6) // ~25% deeper cool
    val Warn = Color(0xFFFFB86B)
    val Crit = Color(0xFFFF4060)

    /**
     * Calm "not supported" grey. Used wherever the runtime says the
     * device can't do something — NEVER red. Dark + light variants
     * mirror the `--calm` token in `tokens.css`.
     */
    val CalmDark = Color(0x6BF4F5F4) // 42% on dark
    val CalmLight = Color(0x6B0D0E0F) // 42% on light

    // ─── Radii ───────────────────────────────────────────────────────
    val RadiusXs: Dp = 8.dp // code chips, value pills
    val RadiusSm: Dp = 12.dp // inner avatars, rows
    val RadiusMd: Dp = 16.dp // default cards
    val RadiusLg: Dp = 22.dp // hero / dialog cards
    val RadiusPill: Dp = 999.dp

    // ─── Spacing ─────────────────────────────────────────────────────
    val Sp1: Dp = 4.dp
    val Sp2: Dp = 8.dp
    val Sp3: Dp = 12.dp
    val Sp4: Dp = 14.dp // small card padding
    val Sp5: Dp = 16.dp // default card padding
    val Sp6: Dp = 18.dp // screen gutter
    val Sp7: Dp = 22.dp
    val Sp8: Dp = 28.dp

    // ─── Touch / control heights ─────────────────────────────────────
    val HeightChip: Dp = 24.dp
    val HeightIcon: Dp = 36.dp
    val HeightButton: Dp = 44.dp // matches `--bt-tap` minimum (a11y)
    val HeightCta: Dp = 50.dp

    // ─── Glass ───────────────────────────────────────────────────────
    val GlassBgDark = Color(0xE00B0C0F) // near-black frosted, ~88% — interior almost merges with bg, edge-defined
    val GlassBgStrongDark = Color(0xF00D0E12) // near-black frosted, ~94%
    val GlassBorderDark = Color(0x1AFFFFFF) // 10% white

    val GlassBgLight = Color(0xB3FFFFFF) // ~70%, a touch milkier
    val GlassBgStrongLight = Color(0xDCFFFFFF) // ~86%
    val GlassBorderLight = Color(0x1A0F1210)

    // ─── Premium "liquid glass" specular cues ────────────────────────
    // A faint top-edge sheen overlaid on the tint + a brighter top rim
    // (fading to a dim bottom rim) for the border. In dark this gives the
    // glass a lit top edge so cards/the dock read as premium frosted
    // panels even where a black drop shadow is invisible. Light keeps the
    // existing dark hairline on both rim stops so its look is unchanged.
    val GlassSheenTopDark = Color(0x12FFFFFF) // ~7% — a touch of body so the card isn't a pure-black void
    val GlassRimTopDark = Color(0x30FFFFFF) // ~19% — soft top highlight, not a stark white outline
    val GlassRimBottomDark = Color(0x0AFFFFFF) // ~4% dim bottom edge

    // Light mirrors the dark "edge-defined" language in its own idiom:
    // a faint light top hairline + a slightly stronger dark bottom edge
    // ground the card, instead of one flat border. Fill is unchanged so
    // the (liked) light surface stays put.
    val GlassSheenTopLight = Color(0x40FFFFFF) // subtle top sheen on white
    val GlassRimTopLight = Color(0x120F1210) // ~7% light top hairline
    val GlassRimBottomLight = Color(0x240F1210) // ~14% grounded bottom edge

    /** Backdrop blur radius for default + strong glass layers. */
    val GlassBlur: Dp = 22.dp
    val GlassBlurStrong: Dp = 28.dp

    // ─── Motion ──────────────────────────────────────────────────────

    /**
     * Bluetrack settle curve — the single spec used everywhere a
     * "settle" feel applies (dock indicator, toggles, scale-in
     * popups, screen enters). Mirrors `--bt-curve` in `tokens.css`.
     */
    val SettleEasing: Easing = CubicBezierEasing(0.16f, 1.18f, 0.32f, 1f)

    /** Faster sibling for short transient transitions (mono pulse, hover). */
    val SettleEasingFast: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** Default settle duration. 380 ms = `--bt-dur`. */
    const val SETTLE_DURATION_MS: Int = 380

    /** Short transient duration for the mono pulse / tick animations. */
    const val FAST_DURATION_MS: Int = 280

    /** Aurora long-drift duration (alternates direction in CSS). */
    const val AURORA_DURATION_MS: Int = 18_000
}
