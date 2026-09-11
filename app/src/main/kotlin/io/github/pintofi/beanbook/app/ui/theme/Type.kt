package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The type scale: two voices, set deliberately (B74).
 *
 * A ledger has exactly two kinds of content and they want opposite things. **Prose** -- payee,
 * narration, account, the sentence explaining what a screen does -- wants a humanist sans and
 * generous line height. **Figures** -- amounts, dates, file positions, Beancount syntax --
 * want a face where every digit is the same width, because the whole point of a column of
 * money is that the decimal points line up. So the scale below is the prose voice, and
 * [LedgerType] is the figure voice, and nothing in the app is left to fall between them.
 *
 * This is not Material's default scale with a new palette. The differences are all in service
 * of density: the display sizes are smaller and tighter than Material's (a ledger never wants
 * 57sp of anything), the body sizes are a step larger than the defaults because they are read
 * for minutes at a time, and labels are sentence case at a readable size rather than the
 * tracked-out capitals that every dashboard uses for its eyebrows.
 *
 * No font files are bundled. The device's own sans and monospace are used, so there is no
 * licence to audit, nothing added to the APK, and no runtime fetch -- and the app inherits the
 * user's font settings, which is what an Android app should do.
 */
internal val BeancountTypography = Typography(
    // Reserved for the one figure that is the subject of a screen. Tight tracking, because at
    // this size the default spacing reads as a gap rather than a word.
    displayLarge = TextStyle(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.02).em,
    ),
    displayMedium = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.02).em,
    ),
    displaySmall = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.015).em,
    ),

    headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),

    // The workhorse: screen and section titles. Medium rather than Bold -- a ledger does not
    // shout, and the rules already separate one section from the next.
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.05.sp,
    ),

    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.05.sp),

    // Sentence case, not capitals. A tracked-out capital label is the commonest tell of a
    // generated interface, and it reads more slowly than the words it replaces.
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * The figure voice: everything that is read down a column rather than along a line (B74).
 *
 * One family, four sizes, one alignment rule. Amounts are right-aligned and monospaced for the
 * same reason: the eye follows the decimal point down the page, and a proportional face makes
 * "111.00" visibly narrower than "999.00" so the points stop lining up exactly where lining up
 * is the point.
 *
 * Deliberately **not** applied to account names or narrations, which are prose and read worse
 * in monospace -- and not applied to dates inside sentences, only to dates in a column.
 */
object LedgerType {

    /** The figure a screen is about: one per surface, never two. */
    val display: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.03).em,
        textAlign = TextAlign.Start,
    )

    /** A figure that heads a group: a section total, a card's own number. */
    val figure: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.02).em,
        textAlign = TextAlign.End,
    )

    /** A figure in a list, beside its account. The most common one in the app. */
    val row: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.01).em,
        textAlign = TextAlign.End,
    )

    /** Secondary figures: a date in a column, a lot, a file position. */
    val small: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        textAlign = TextAlign.Start,
    )

    /** Beancount source, where the file's own spacing is the meaning. */
    val source: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        textAlign = TextAlign.Start,
    )
}

/**
 * Tabular figures for anything the user compares vertically.
 *
 * Deliberately carries **no size**, so it can be merged over whichever scale style a surface
 * calls for: a headline figure on Home and a posting in the journal are then the same face at
 * two sizes rather than two hand-rolled treatments. [LedgerType] is the preferred entry point
 * now; this remains because it is what every existing amount composable merges.
 */
val AmountTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    letterSpacing = (-0.01).em,
    // Amounts are right-aligned for the same reason they are monospaced: the decimal point is
    // the thing the eye follows down the column.
    textAlign = TextAlign.End,
)
