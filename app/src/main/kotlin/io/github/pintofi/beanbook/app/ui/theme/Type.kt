package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The type scale, and the one place this app deviates from Material's defaults.
 *
 * Material 3's scale is kept as-is: it is well tested at every font scale, and rewriting it
 * would mean re-testing the 200% cases in `AccessibilityTest` for no gain.
 *
 * [amount] is the deviation, and it is functional rather than decorative. Money is read in
 * columns, and proportional digits make columns ragged — "111.00" is visibly narrower than
 * "999.00" in most sans faces, so figures stop lining up exactly where lining up is the
 * point. A monospaced face gives every digit the same advance, so decimal points sit under
 * each other down a journal.
 *
 * Deliberately **not** applied to account names or narrations, which are prose and read
 * worse in monospace.
 */
internal val BeancountTypography = Typography()

/** Tabular figures for anything the user compares vertically. */
val AmountTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    // Amounts are right-aligned for the same reason they are monospaced: the decimal point
    // is the thing the eye follows down the column.
    textAlign = TextAlign.End,
)
