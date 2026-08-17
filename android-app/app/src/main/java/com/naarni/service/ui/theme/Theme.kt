package com.naarni.service.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's colour system.
 *
 * A neutral scale carries the entire interface and the brand indigo appears only
 * on the one action that matters on a given screen. The previous scheme spent
 * indigo everywhere — gradient headers, gradient tiles, gradient avatars — which
 * meant that by the time a screen wanted to point at something, it had no colour
 * left to point with. Here, anything indigo is something to press.
 *
 * The neutrals are a true grey ramp rather than the old blue-tinted slate. On a
 * handset held outdoors in daylight a tinted grey reads as a colour cast, and
 * next to a genuinely coloured status chip it reads as a second, competing hue.
 */

// ── Neutral ramp ─────────────────────────────────────────────────────────────
// Evenly spaced in perceived lightness, so any two adjacent steps are a visible
// boundary without a border and any two steps apart survive a sunlit screen.
private val N50 = Color(0xFFFAFAFA)
private val N100 = Color(0xFFF4F4F5)
private val N200 = Color(0xFFE4E4E7)
private val N300 = Color(0xFFD4D4D8)
private val N400 = Color(0xFFA1A1AA)
private val N500 = Color(0xFF71717A)
private val N700 = Color(0xFF3F3F46)
private val N800 = Color(0xFF27272A)
private val N850 = Color(0xFF1C1C21)
private val N900 = Color(0xFF141418)
private val Ink = Color(0xFF0B0B0F)

// ── The single accent ────────────────────────────────────────────────────────
private val Accent = Color(0xFF4F46E5)
private val AccentLight = Color(0xFF8B85F5)
private val AccentSoft = Color(0xFFE8E7FD)
private val AccentDeep = Color(0xFF1E1B4B)
private val AccentDim = Color(0xFF2E2A6B)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentDeep,
    inversePrimary = AccentLight,

    // Secondary is deliberately neutral. In a single-accent system a second
    // brand hue is just a second thing competing for the eye; components that
    // reach for `secondary` should read as quiet, not as a rival to the accent.
    secondary = N500,
    onSecondary = Color.White,
    secondaryContainer = N100,
    onSecondaryContainer = N700,

    tertiary = N700,
    onTertiary = Color.White,
    tertiaryContainer = N100,
    onTertiaryContainer = N700,

    background = N50,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    // Sunken things: fields, wells, inset cards.
    surfaceVariant = N100,
    onSurfaceVariant = N500,
    surfaceTint = Accent,
    inverseSurface = N800,
    inverseOnSurface = N50,

    outline = N300,
    outlineVariant = N200,
    scrim = Color(0x99000000),

    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/**
 * Dark is authored role-for-role rather than left to Material's defaults.
 *
 * The previous dark scheme declared about half of these, and the rest fell back
 * to Material's baseline purple — which is why an unread divider and a vehicle
 * tag chip came out lilac in dark mode while everything around them was blue-
 * grey. Every role a component in this app can reach for is set here.
 */
private val Dark = darkColorScheme(
    primary = AccentLight,
    onPrimary = AccentDeep,
    primaryContainer = AccentDim,
    onPrimaryContainer = AccentSoft,
    inversePrimary = Accent,

    secondary = N400,
    onSecondary = Ink,
    secondaryContainer = N800,
    onSecondaryContainer = N200,

    tertiary = N300,
    onTertiary = Ink,
    tertiaryContainer = N800,
    onTertiaryContainer = N200,

    background = Ink,
    onBackground = N50,
    surface = N900,
    onSurface = N50,
    surfaceVariant = N850,
    onSurfaceVariant = N400,
    surfaceTint = AccentLight,
    inverseSurface = N100,
    inverseOnSurface = Ink,

    outline = N700,
    outlineVariant = N800,
    scrim = Color(0xB3000000),

    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF4C1414),
    onErrorContainer = Color(0xFFFECACA),
)

/**
 * Surfaces the Material scheme has no role for.
 *
 * Material gives one `surface` and one `surfaceVariant`, which is not enough for
 * a screen that stacks a card on a canvas and then insets a field inside the
 * card. Rather than sprinkling `.copy(alpha = …)` at each call site — which
 * produces a different grey depending on what happens to be behind it — the
 * three grounds are named once here.
 */
object AppSurface {

    /** A card or sheet lifted off the canvas. */
    val raised: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) N900 else Color.White

    /** A field, well or inset region pressed into a card. */
    val sunken: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) N850 else N100

    /** Hairline separators. Lighter than `outline`, which is for control borders. */
    val hairline: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) N800 else N200
}

/**
 * Semantic colour, in one place.
 *
 * Status, priority and severity were each mapping strings to raw hex in
 * `UiKit.kt`, with three different reds and two different ambers between them —
 * so a "Critical" alert and an "Urgent" ticket, which mean the same urgency to
 * the person reading them, arrived in different colours. One ramp now serves all
 * three, and each entry carries the tint used behind chip text so callers stop
 * inventing their own alpha.
 *
 * Each pair is contrast-checked against both canvases; the `on*` value is the
 * text colour, the `*Tint` the chip ground.
 */
object Semantic {

    /** Settled, done, healthy. */
    val positive: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF4ADE80) else Color(0xFF15803D)

    /** In flight, needs an eye on it but not now. */
    val caution: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFFB45309)

    /** Blocked, breached, failed. */
    val critical: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFF87171) else Color(0xFFB91C1C)

    /** Live work — the only semantic that borrows the accent. */
    val active: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) AccentLight else Accent

    /** Nothing has happened yet; not a state so much as an absence of one. */
    val idle: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) N400 else N500

    /** Somebody is reachable right now. Presence dots only. */
    val online: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF34D399) else Color(0xFF059669)

    /** The ground a semantic chip's text sits on. */
    @Composable
    @ReadOnlyComposable
    fun tint(base: Color): Color = base.copy(alpha = if (isSystemInDarkTheme()) 0.18f else 0.12f)
}

/**
 * The brand gradient, now used in exactly one place: the login hero.
 *
 * It was previously the ground for every header in the app, which is what made
 * the interface read as a consumer product rather than a working tool — and, more
 * practically, forced white text and white icons onto every top bar regardless of
 * theme. Sign-in is the one screen with no content to compete with, so it is the
 * one screen where the brand can have the floor.
 */
val BrandGradient = listOf(Color(0xFF6D5AE6), Accent, Color(0xFF312E81))

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * A compact scale with deliberate weight contrast.
 *
 * Titles are heavy and tight, body is regular and open. The gap between them is
 * what lets a list row be scanned without reading it — which is the whole job on
 * a screen someone is looking at with gloves on, one-handed, in a depot.
 */
private val AppType = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.3.sp),
)

@Composable
fun NaarniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppType,
        shapes = AppShapes,
        content = content,
    )
}
