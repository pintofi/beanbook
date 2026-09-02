package dev.beancount.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The fallback palette, for devices without dynamic colour (below Android 12) and for
 * anyone who turns dynamic colour off.
 *
 * A deep teal rather than Material's default purple: this is an app people look at columns
 * of numbers in, and the palette's job is to stay out of the way of them. Every role is
 * stated explicitly instead of letting `lightColorScheme()` fill the gaps, because the
 * defaults it fills with are the purple baseline — a half-specified scheme is how an app
 * ends up mostly branded and occasionally lilac.
 *
 * Contrast: the on/container pairs are the standard Material 3 tonal steps (40/100/90/10
 * light, 80/20/30/90 dark), which meet WCAG AA for body text at every pairing used here.
 */
internal val LightColours = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F0),
    onPrimaryContainer = Color(0xFF002020),

    secondary = Color(0xFF4A6363),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),

    tertiary = Color(0xFF4B607C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFAFDFC),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4949),

    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC9C8),
    inverseSurface = Color(0xFF2D3131),
    inverseOnSurface = Color(0xFFEFF1F1),
    inversePrimary = Color(0xFF80D5D4),
    scrim = Color(0xFF000000),
)

internal val DarkColours = darkColorScheme(
    primary = Color(0xFF80D5D4),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    onPrimaryContainer = Color(0xFF9CF1F0),

    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3535),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),

    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF191C1C),
    onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF191C1C),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC9C8),

    outline = Color(0xFF899393),
    outlineVariant = Color(0xFF3F4949),
    inverseSurface = Color(0xFFE0E3E2),
    inverseOnSurface = Color(0xFF191C1C),
    inversePrimary = Color(0xFF006A6A),
    scrim = Color(0xFF000000),
)

/**
 * A dark scheme with a true-black ground, for OLED screens.
 *
 * Not a Material concept — there is no `amoledColorScheme()`. It is a display trick: an OLED
 * pixel showing #000000 is switched off, so a black ground costs no power and has no glow in
 * a dark room. This is applied *over* whichever dark scheme is in use, so it composes with
 * dynamic colour rather than replacing it.
 *
 * The containers are deliberately *not* black. Making every surface #000000 is the usual
 * mistake: this app puts figures on cards — the home totals, the trial notice, a document
 * row — and a card that is the same colour as the page behind it stops being a card. So the
 * page goes black and the raised roles keep a step above it, which is the elevation Material
 * expresses with colour in dark themes.
 */
internal fun ColorScheme.onBlack(): ColorScheme = copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0E0E),
    surfaceContainer = Color(0xFF121616),
    surfaceContainerHigh = Color(0xFF1B1F1F),
    surfaceContainerHighest = Color(0xFF242828),
    surfaceDim = Color(0xFF000000),
)

/**
 * Colours for money, which are *not* part of the Material palette on purpose.
 *
 * Red and green alone are the classic accessibility failure — around 1 in 12 men cannot
 * reliably separate them — so nothing in this app uses colour as the only signal that a
 * number is negative. The minus sign does that. These exist to reinforce it, and the amber
 * is deliberately not "warning": a negative balance is ordinary in double-entry
 * bookkeeping, and dressing it up as an error would be wrong about accounting as well as
 * alarming.
 */
data class LedgerColours(val positive: Color, val negative: Color) {
    companion object {
        val Light = LedgerColours(positive = Color(0xFF1B5E3F), negative = Color(0xFF8A4B00))
        val Dark = LedgerColours(positive = Color(0xFF7FD3A8), negative = Color(0xFFFFB870))
    }
}

/**
 * The fallback palettes, exposed for instrumentation tests.
 *
 * The tests need a scheme whose values are known and fixed. Reading `MaterialTheme` inside a
 * test would give whatever dynamic colour the device happened to generate, and an assertion
 * against that could only be "some colour changed", which is not a test.
 */
val LightColoursForTest = LightColours
val DarkColoursForTest = DarkColours
