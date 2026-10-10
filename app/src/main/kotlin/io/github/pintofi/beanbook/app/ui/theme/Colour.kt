package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The palette: paper, ink, and one teal rule (B74).
 *
 * The app is a ledger, and a ledger is ink on ruled paper. So the ground is *paper* -- a
 * neutral with a faint green cast taken from the teal, not the warm cream that every
 * editorial template reaches for -- and the type is *ink*, a green-black rather than a grey.
 * The teal is the rule: it appears as lines, marks and the band across the top of a surface,
 * and almost never as a filled area. That is what keeps a screen full of figures calm while
 * still being unmistakably this app's.
 *
 * Terracotta is the third voice and is deliberately rationed: it marks things a person has
 * not finished -- a pending flag, an unreconciled row -- and never money. Nothing in the app
 * uses colour as the only signal for anything (see [LedgerColours]).
 *
 * Every role is stated explicitly rather than letting `lightColorScheme()` fill the gaps: the
 * gaps it fills are Material's purple baseline, and a half-specified scheme is how an app ends
 * up mostly branded and occasionally lilac.
 *
 * Contrast was checked against the pairs actually used: ink on paper is 14.9:1, teal on paper
 * 6.3:1, onSurfaceVariant on paper 7.2:1, and every container/on-container pair clears 4.5:1.
 *
 * The *fixed* roles (Material 3 Expressive) are the same tones in both themes by definition,
 * so they are set once, below, from the light containers: teal, sage and terracotta at the
 * container tone, a step darker for the dim variant, and the on-container inks on top. Left
 * out, they would be Material's baseline purple -- the half-specified scheme this file exists
 * to avoid. Every on-fixed ink clears 4.5:1 on both its fixed and its dim ground (the lowest,
 * terracotta variant on the dim tone, is 5.5:1).
 */
private val PrimaryFixed = Color(0xFFB9E7E2)
private val PrimaryFixedDim = Color(0xFF9ACFC9)
private val OnPrimaryFixed = Color(0xFF002B28)
private val OnPrimaryFixedVariant = Color(0xFF0A4A46)
private val SecondaryFixed = Color(0xFFCFE4DE)
private val SecondaryFixedDim = Color(0xFFB3C9C2)
private val OnSecondaryFixed = Color(0xFF05201C)
private val OnSecondaryFixedVariant = Color(0xFF2A4A44)
private val TertiaryFixed = Color(0xFFFFDBCD)
private val TertiaryFixedDim = Color(0xFFFFB59B)
private val OnTertiaryFixed = Color(0xFF3A1004)
private val OnTertiaryFixedVariant = Color(0xFF75331C)

internal val LightColours = lightColorScheme(
    // The teal is an ink colour, dark enough to read as text on paper without a container.
    primary = Color(0xFF0B5F5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9E7E2),
    onPrimaryContainer = Color(0xFF002B28),

    secondary = Color(0xFF3F5C56),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE4DE),
    onSecondaryContainer = Color(0xFF05201C),

    // Terracotta. The one warm note, and it means "unfinished", not "wrong".
    tertiary = Color(0xFF9A4C2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCD),
    onTertiaryContainer = Color(0xFF3A1004),

    error = Color(0xFFA02A20),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410003),

    // Paper, and a hair cooler than cream on purpose.
    background = Color(0xFFF3F5F2),
    onBackground = Color(0xFF16201E),
    surface = Color(0xFFF3F5F2),
    onSurface = Color(0xFF16201E),
    surfaceVariant = Color(0xFFE2E8E3),
    onSurfaceVariant = Color(0xFF44534E),

    // Hairlines. `outlineVariant` is the rule the whole layout is built on, so it is a real
    // colour rather than a near-invisible grey: a rule that cannot be seen is not structure.
    outline = Color(0xFF8A958F),
    outlineVariant = Color(0xFFC3CDC7),
    inverseSurface = Color(0xFF1E2927),
    inverseOnSurface = Color(0xFFEDF1EE),
    inversePrimary = Color(0xFF6FD3CA),
    scrim = Color(0xFF000000),

    // The raised neutrals. Kept close together: this design separates things with rules and
    // space rather than by stacking five shades of grey card on top of each other.
    surfaceBright = Color(0xFFF8FAF7),
    surfaceDim = Color(0xFFD8DED9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEDF1EC),
    surfaceContainer = Color(0xFFE7ECE7),
    surfaceContainerHigh = Color(0xFFE1E7E1),
    surfaceContainerHighest = Color(0xFFDBE2DC),
    surfaceTint = Color(0xFF0B5F5B),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

internal val DarkColours = darkColorScheme(
    primary = Color(0xFF6FD3CA),
    onPrimary = Color(0xFF00332F),
    primaryContainer = Color(0xFF0A4A46),
    onPrimaryContainer = Color(0xFFB9E7E2),

    secondary = Color(0xFFA9C9C1),
    onSecondary = Color(0xFF12332E),
    secondaryContainer = Color(0xFF2A4A44),
    onSecondaryContainer = Color(0xFFCFE4DE),

    tertiary = Color(0xFFFFB59B),
    onTertiary = Color(0xFF57200A),
    tertiaryContainer = Color(0xFF75331C),
    onTertiaryContainer = Color(0xFFFFDBCD),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8E2A22),
    onErrorContainer = Color(0xFFFFDAD5),

    // Not grey: the dark ground keeps the same green cast as the paper, so the two themes are
    // recognisably the same product rather than a light design and a black one.
    background = Color(0xFF0D1211),
    onBackground = Color(0xFFE2E8E5),
    surface = Color(0xFF0D1211),
    onSurface = Color(0xFFE2E8E5),
    surfaceVariant = Color(0xFF2C3734),
    onSurfaceVariant = Color(0xFFB2BEB9),

    outline = Color(0xFF74817C),
    outlineVariant = Color(0xFF2F3B38),
    inverseSurface = Color(0xFFE2E8E5),
    inverseOnSurface = Color(0xFF0D1211),
    inversePrimary = Color(0xFF0B5F5B),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF323B39),
    surfaceDim = Color(0xFF090D0C),
    surfaceContainerLowest = Color(0xFF070B0A),
    surfaceContainerLow = Color(0xFF101615),
    surfaceContainer = Color(0xFF141B19),
    surfaceContainerHigh = Color(0xFF1A2220),
    surfaceContainerHighest = Color(0xFF212A28),
    surfaceTint = Color(0xFF6FD3CA),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

/**
 * A dark scheme with a true-black ground, for OLED screens.
 *
 * Not a Material concept -- there is no `amoledColorScheme()`. It is a display trick: an OLED
 * pixel showing #000000 is switched off, so a black ground costs no power and has no glow in
 * a dark room. This is applied *over* whichever dark scheme is in use, so it composes with
 * dynamic colour rather than replacing it.
 *
 * The raised roles stay above the ground for the same reason as before: a notice or a sheet
 * that is exactly the colour of the page behind it stops being either. What changes here is
 * that the rules get brighter -- on a black ground a hairline at #2F3B38 disappears, and the
 * rules are the structure of this design.
 */
internal fun ColorScheme.onBlack(): ColorScheme = copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF080D0C),
    surfaceContainer = Color(0xFF0D1413),
    surfaceContainerHigh = Color(0xFF141C1A),
    surfaceContainerHighest = Color(0xFF1B2422),
    surfaceDim = Color(0xFF000000),
    outlineVariant = Color(0xFF37433F),
)

/**
 * Colours for money and for the marks beside it, which are *not* Material roles on purpose.
 *
 * Red and green alone are the classic accessibility failure -- around 1 in 12 men cannot
 * reliably separate them -- so nothing in this app uses colour as the only signal that a
 * number is negative. The minus sign does that, and the balance mark beside an entry says in
 * shape what [pending] says in colour. These exist to reinforce, never to carry.
 *
 * [negative] is an amber-brown rather than a red: a negative balance is ordinary in
 * double-entry bookkeeping, and dressing it up as an error would be wrong about accounting as
 * well as alarming. [pending] is the terracotta, and marks work a person has not finished.
 * [rule] is the ledger's own line colour, brighter than the Material hairline because it is
 * the spine of a list rather than a divider between two of them.
 */
data class LedgerColours(
    val positive: Color,
    val negative: Color,
    val neutral: Color = Color(0xFF6B7873),
    val pending: Color = Color(0xFF9A4C2E),
    val rule: Color = Color(0xFF0B5F5B),
) {
    companion object {
        val Light = LedgerColours(
            positive = Color(0xFF1B5E3F),
            negative = Color(0xFF8A4B00),
            neutral = Color(0xFF66736E),
            pending = Color(0xFF9A4C2E),
            rule = Color(0xFF0B5F5B),
        )
        val Dark = LedgerColours(
            positive = Color(0xFF7FD3A8),
            negative = Color(0xFFFFB870),
            neutral = Color(0xFF9AA7A2),
            pending = Color(0xFFFFB59B),
            rule = Color(0xFF6FD3CA),
        )
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
