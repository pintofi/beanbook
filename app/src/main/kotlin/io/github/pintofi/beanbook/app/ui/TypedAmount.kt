package io.github.pintofi.beanbook.app.ui

import java.math.BigDecimal

private val NUMBER = Regex("[+-]?\\d+(\\.\\d*)?")
private val GROUPED = Regex("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d*)?")

/**
 * A number typed into an amount box, or null when it is not one Beancount would read.
 *
 * Thousands may be grouped with commas, as Beancount allows (`1,234.50`). Nothing else is
 * guessed at: `4,50` is not 4.50 in a ledger that uses a point, and `1e5` is not a number to
 * Beancount at all. A box that did not parse used to be treated as an empty one, which made
 * the posting "elided" and let Beancount fill in whatever balanced -- the figure the person
 * typed was dropped without a word. The form now refuses to save until the box is right.
 */
internal fun parseTypedAmount(text: String): BigDecimal? {
    val typed = text.trim()
    val plain = if (GROUPED.matches(typed)) typed.replace(",", "") else typed
    if (!NUMBER.matches(plain)) return null
    return runCatching { BigDecimal(plain) }.getOrNull()
}

/** What to tell the person about a box [parseTypedAmount] refused. */
internal fun unreadableAmountMessage(text: String): String =
    "\"${text.trim()}\" is not an amount; use digits and a point, for example 4.50"
