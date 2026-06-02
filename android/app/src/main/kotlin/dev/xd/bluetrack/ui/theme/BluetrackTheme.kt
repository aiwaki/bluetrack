package dev.xd.bluetrack.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Bluetrack theme wrapper. Provides:
 *  - [MaterialTheme.colorScheme] mapped from [BluetrackTokens] so
 *    Material widgets pick up the canvas palette automatically.
 *  - [MaterialTheme.shapes] mapped from the 8/12/16/22/pill radii.
 *  - [MaterialTheme.typography] using Geist if/when the font is
 *    bundled (TODO below); falls back to the platform sans family
 *    for now so the PR ships without a new asset blob.
 *  - [LocalBluetrackPalette] for tokens Material does not model —
 *    the multi-stop glow stack, calm grey, glass alphas, and the
 *    Bluetrack settle curve. Components that need these read
 *    `BluetrackTheme.palette` instead of the Material colour
 *    surface.
 *
 * Step 1 of `docs/UI_DESIGN.md`: tokens only, no screen changes.
 */
@Composable
fun BluetrackTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = if (darkTheme) BluetrackDarkColorScheme else BluetrackLightColorScheme
    // Override LocalIndication directly so EVERY
    // `Modifier.clickable {}` picks up a ripple coloured by
    // `palette.fg0` (foreground = opposite of background).
    // `LocalRippleConfiguration` alone wasn't pulling through
    // reliably on the light palette — dark fg0 on light bg now
    // renders as the soft frosted-glass wave the user expects.
    val themedRipple = ripple(color = palette.fg0)
    CompositionLocalProvider(
        LocalBluetrackPalette provides palette,
        LocalIndication provides themedRipple,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = BluetrackShapes,
            typography = BluetrackTypography,
            content = content,
        )
    }
}

/** Convenience accessor: `BluetrackTheme.palette.glow` etc. */
object BluetrackTheme {
    val palette: BluetrackPalette
        @Composable get() = LocalBluetrackPalette.current
}

/**
 * Bluetrack-specific design surface that Material's `ColorScheme`
 * cannot express: multi-stop glow stack, calm grey for "not
 * supported" states, glass alphas, motion curves. Components that
 * need these pull them from the composition local; Material widgets
 * keep working off `colorScheme.*` as usual.
 */
@Immutable
data class BluetrackPalette(
    val mint: Color,
    val mintBright: Color,
    val mintDeep: Color,
    val mintGlow: Color,
    val mintGlowSoft: Color,
    val cool: Color,
    val coolGlowSoft: Color,
    val warn: Color,
    val crit: Color,
    val calm: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val bg3: Color,
    val fg0: Color,
    val fg1: Color,
    val fg2: Color,
    val fg3: Color,
    val glassBg: Color,
    val glassBgStrong: Color,
    val glassBorder: Color,
    val glassSheen: Color,
    val glassRimTop: Color,
    val glassRimBottom: Color,
)

internal val DarkPalette = BluetrackPalette(
    mint = BluetrackTokens.MintDark,
    mintBright = BluetrackTokens.MintBrightDark,
    mintDeep = BluetrackTokens.MintDeepDark,
    mintGlow = BluetrackTokens.MintGlowDark,
    mintGlowSoft = BluetrackTokens.MintGlowSoftDark,
    cool = BluetrackTokens.Cool,
    coolGlowSoft = BluetrackTokens.CoolGlowSoftDark,
    warn = BluetrackTokens.Warn,
    crit = BluetrackTokens.Crit,
    calm = BluetrackTokens.CalmDark,
    hairline = BluetrackTokens.DarkHairline,
    hairlineStrong = BluetrackTokens.DarkHairline2,
    bg0 = BluetrackTokens.DarkBg0,
    bg1 = BluetrackTokens.DarkBg1,
    bg2 = BluetrackTokens.DarkBg2,
    bg3 = BluetrackTokens.DarkBg3,
    fg0 = BluetrackTokens.DarkFg0,
    fg1 = BluetrackTokens.DarkFg1,
    fg2 = BluetrackTokens.DarkFg2,
    fg3 = BluetrackTokens.DarkFg3,
    glassBg = BluetrackTokens.GlassBgDark,
    glassBgStrong = BluetrackTokens.GlassBgStrongDark,
    glassBorder = BluetrackTokens.GlassBorderDark,
    glassSheen = BluetrackTokens.GlassSheenTopDark,
    glassRimTop = BluetrackTokens.GlassRimTopDark,
    glassRimBottom = BluetrackTokens.GlassRimBottomDark,
)

internal val LightPalette = BluetrackPalette(
    mint = BluetrackTokens.MintLight,
    mintBright = BluetrackTokens.MintBrightLight,
    mintDeep = BluetrackTokens.MintDeepLight,
    mintGlow = BluetrackTokens.MintGlowLight,
    mintGlowSoft = BluetrackTokens.MintGlowSoftLight,
    cool = BluetrackTokens.Cool,
    coolGlowSoft = BluetrackTokens.CoolGlowSoftLight,
    warn = BluetrackTokens.Warn,
    crit = BluetrackTokens.Crit,
    calm = BluetrackTokens.CalmLight,
    hairline = BluetrackTokens.LightHairline,
    hairlineStrong = BluetrackTokens.LightHairline2,
    bg0 = BluetrackTokens.LightBg0,
    bg1 = BluetrackTokens.LightBg1,
    bg2 = BluetrackTokens.LightBg2,
    bg3 = BluetrackTokens.LightBg3,
    fg0 = BluetrackTokens.LightFg0,
    fg1 = BluetrackTokens.LightFg1,
    fg2 = BluetrackTokens.LightFg2,
    fg3 = BluetrackTokens.LightFg3,
    glassBg = BluetrackTokens.GlassBgLight,
    glassBgStrong = BluetrackTokens.GlassBgStrongLight,
    glassBorder = BluetrackTokens.GlassBorderLight,
    glassSheen = BluetrackTokens.GlassSheenTopLight,
    glassRimTop = BluetrackTokens.GlassRimTopLight,
    glassRimBottom = BluetrackTokens.GlassRimBottomLight,
)

internal val LocalBluetrackPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Map of canvas tokens → Material 3 `ColorScheme`. We use
 * `darkColorScheme` / `lightColorScheme` as a baseline so any
 * Material widget renders sensibly without bespoke wiring; the
 * Bluetrack-specific accents (glow, calm) live in
 * [BluetrackPalette].
 */
internal val BluetrackDarkColorScheme = darkColorScheme(
    primary = BluetrackTokens.MintDark,
    onPrimary = BluetrackTokens.DarkFg0,
    primaryContainer = BluetrackTokens.MintDeepDark,
    onPrimaryContainer = BluetrackTokens.DarkFg0,
    secondary = BluetrackTokens.Cool,
    onSecondary = BluetrackTokens.DarkBg0,
    tertiary = BluetrackTokens.Warn,
    onTertiary = BluetrackTokens.DarkBg0,
    background = BluetrackTokens.DarkBg0,
    onBackground = BluetrackTokens.DarkFg0,
    surface = BluetrackTokens.DarkBg1,
    onSurface = BluetrackTokens.DarkFg0,
    surfaceVariant = BluetrackTokens.DarkBg2,
    onSurfaceVariant = BluetrackTokens.DarkFg1,
    outline = BluetrackTokens.DarkHairline2,
    outlineVariant = BluetrackTokens.DarkHairline,
    error = BluetrackTokens.Crit,
    onError = BluetrackTokens.DarkFg0,
)

internal val BluetrackLightColorScheme = lightColorScheme(
    primary = BluetrackTokens.MintLight,
    onPrimary = BluetrackTokens.LightBg1,
    primaryContainer = BluetrackTokens.MintDeepLight,
    onPrimaryContainer = BluetrackTokens.LightBg1,
    secondary = BluetrackTokens.Cool,
    onSecondary = BluetrackTokens.LightFg0,
    tertiary = BluetrackTokens.Warn,
    onTertiary = BluetrackTokens.LightFg0,
    background = BluetrackTokens.LightBg0,
    onBackground = BluetrackTokens.LightFg0,
    surface = BluetrackTokens.LightBg1,
    onSurface = BluetrackTokens.LightFg0,
    surfaceVariant = BluetrackTokens.LightBg2,
    onSurfaceVariant = BluetrackTokens.LightFg1,
    outline = BluetrackTokens.LightHairline2,
    outlineVariant = BluetrackTokens.LightHairline,
    error = BluetrackTokens.Crit,
    onError = BluetrackTokens.LightBg1,
)

internal val BluetrackShapes = Shapes(
    extraSmall = RoundedCornerShape(BluetrackTokens.RadiusXs),
    small = RoundedCornerShape(BluetrackTokens.RadiusSm),
    medium = RoundedCornerShape(BluetrackTokens.RadiusMd),
    large = RoundedCornerShape(BluetrackTokens.RadiusLg),
    extraLarge = RoundedCornerShape(BluetrackTokens.RadiusPill),
)

// Geist is the canvas sans family; bundling the OFL font files is a
// follow-up (~150 KB to the APK). Until then fall back to the
// platform sans family so the rest of the type system still applies
// the right weights and sizes.
internal val BluetrackSansFamily: FontFamily = FontFamily.SansSerif
internal val BluetrackMonoFamily: FontFamily = FontFamily.Monospace

/**
 * Typography aligned with the canvas: tighter letter spacing on
 * display text, mono family for counters / pins / fingerprints,
 * everything else at sensible Material defaults.
 */
internal val BluetrackTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 38.sp,
        letterSpacing = (-1).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BluetrackSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Caption / mono labels: canvas uses Geist Mono with letter
    // spacing 0.16em uppercase for `bt-cap`. Use `labelLarge` /
    // `labelMedium` to surface that without a custom slot.
    labelLarge = TextStyle(
        fontFamily = BluetrackMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BluetrackMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.92.sp, // ≈ 0.16em on 12sp uppercase caps
    ),
    labelSmall = TextStyle(
        fontFamily = BluetrackMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.76.sp,
    ),
)
