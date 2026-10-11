package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The measurements the layout is built from, so rhythm is a decision rather than an accident
 * (B74).
 *
 * Before this, every screen chose its own padding -- 16 here, 12 there, 24 in the one that was
 * written last -- and the result was a set of screens that were individually tidy and visibly
 * unrelated. These are the numbers the whole app uses.
 *
 * The gutter is **20dp, not 16**. It is the widest value that still leaves a phone row room for
 * an account name and an amount, and it is what gives the page the margin a ledger has. The
 * rule sits *inside* that margin, which is what makes a list read as ruled paper rather than as
 * a list of cards.
 */
object Measure {

    /** The base step. Everything below is a multiple of it. */
    val step: Dp = 4.dp

    /** The page margin. */
    val gutter: Dp = 20.dp

    /** The margin a nested block gets: enough to read as indented, not enough to waste a phone. */
    val indent: Dp = 16.dp

    /** Between a label and the thing it labels. */
    val tight: Dp = 4.dp

    /** Between two lines of the same block. */
    val snug: Dp = 8.dp

    /** Between two blocks. */
    val gap: Dp = 12.dp

    /** Between two sections of a screen. */
    val section: Dp = 24.dp

    /** Above a new part of a screen that has its own heading. */
    val chapter: Dp = 32.dp

    /** The vertical padding of a data row: tight enough to scan, tall enough to tap. */
    val rowPadding: Dp = 12.dp

    /** The smallest a row may be, so a whole row is always a 48dp target. */
    val rowMinHeight: Dp = 56.dp

    /** The width of the column rule that runs down a financial list. */
    val rule: Dp = 2.dp

    /** A hairline: the divider between two rows. */
    val hairline: Dp = 1.dp

    /** How far the content sits from the column rule. */
    val ruleGap: Dp = 14.dp

    /** The height of the tick a rule draws beside a group heading. */
    val tick: Dp = 10.dp

    /** The width of the stamp beside an entry. */
    val stamp: Dp = 18.dp

    /** Space kept under a scrolling surface so the action button never covers the last row. */
    val actionClearance: Dp = 96.dp

    /** The gap between two rows of a folio: enough to see the seam, not enough to split it. */
    val seam: Dp = 2.dp

    /** The icon badge at the start of a folio row. */
    val badge: Dp = 40.dp

    /** Content columns on a wide window, side by side (Home in landscape and on a tablet). */
    val columnGap: Dp = 28.dp
}
