package dev.beancount.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Material's own scale, kept for every metric that affects layout.
 *
 * Sizes, line heights and letter spacing are the library's, and are read from here rather
 * than restated as literals, so a Compose upgrade that retunes the scale is inherited
 * instead of silently overridden. [BeancountTypography] changes weight and nothing else.
 */
private val Default = Typography()

/**
 * Material's scale, with Expressive's weight contrast and none of its size changes.
 *
 * Expressive's headline styles are heavier than baseline M3, which is most of what makes it
 * read as "expressive" — the corner radii in [BeancountShapes] are the other half. The
 * emphasised type styles that carry it are internal in Material3 1.4.0 along with the rest
 * of that API surface (see [BeancountShapes]), so the weight is applied here instead.
 *
 * **Only `fontWeight` is overridden, and deliberately so.** Every style keeps the library's
 * own `fontSize`, `lineHeight` and `letterSpacing` by copying from [Default] rather than
 * restating numbers, which has two consequences worth the constraint: a Compose upgrade
 * that retunes the scale is inherited rather than silently overridden, and no layout metric
 * moves — so `AccessibilityTest`'s 200% bounds checks still assert what they asserted
 * before, and the comment above about not wanting to re-test the scale still holds.
 *
 * Body and label styles are untouched. Weight belongs on the few things that head a screen;
 * putting it on running text makes a ledger look shouted rather than emphatic.
 */
internal val BeancountTypography = Default.copy(
    displayLarge = Default.displayLarge.copy(fontWeight = FontWeight.Bold),
    displayMedium = Default.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = Default.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * Tabular figures for anything the user compares vertically.
 *
 * This is the app's one genuine departure from Material's type, and it is functional rather
 * than decorative. Money is read in columns, and proportional digits make columns ragged —
 * "111.00" is visibly narrower than "999.00" in most sans faces, so figures stop lining up
 * exactly where lining up is the point. A monospaced face gives every digit the same
 * advance, so decimal points sit under each other down a journal.
 *
 * Deliberately **not** applied to account names or narrations, which are prose and read
 * worse in monospace. Deliberately untouched by the Expressive weight pass for the same
 * reason: a column of figures wants to be even, not emphatic.
 */
val AmountTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    // Amounts are right-aligned for the same reason they are monospaced: the decimal point
    // is the thing the eye follows down the column.
    textAlign = TextAlign.End,
)
