package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How a ledger asks its own figures to be shown (B59).
 *
 * Beancount works this out from the file: the **display context** records how many decimal
 * places each commodity is actually written to, and `option "render_commas"` says whether
 * thousands are grouped. The app used neither — every figure came out at whatever scale the
 * arithmetic happened to produce, ungrouped — so a ledger kept in a currency written to three
 * places, with separators, was displayed to two places without them.
 *
 * `option "inferred_tolerance_default"` belongs here too: it is what the *file* considers
 * close enough to balance, and an app deciding that for itself is an app disagreeing with the
 * ledger it is showing.
 *
 * Empty is the honest default: a ledger that says nothing gets Beancount's own behaviour,
 * which is to print the number as written.
 */
data class LedgerFormat(
    /** `option "render_commas"`. */
    val groupThousands: Boolean = false,
    /** Decimal places per commodity, as the file writes them. */
    val precision: Map<String, Int> = emptyMap(),
    /** `option "inferred_tolerance_default"`, per commodity. */
    val tolerances: Map<String, BigDecimal> = emptyMap(),
) {

    /**
     * [amount] as this ledger would write it.
     *
     * The scale is only ever *applied for display*: the value itself is untouched, and
     * nothing here is ever written back to a file. A commodity the file says nothing about
     * keeps the digits it already has, because inventing a precision for it would be the app
     * deciding how somebody's shares are quoted.
     */
    fun format(amount: Amount): String = "${number(amount)} ${amount.currency}"

    /** Just the figure, for a column that puts the commodity somewhere else. */
    fun number(amount: Amount): String {
        val places = precision[amount.currency]
        val scaled = if (places == null) {
            amount.number
        } else {
            amount.number.setScale(places, RoundingMode.HALF_UP)
        }
        val plain = scaled.toPlainString()
        return if (groupThousands) grouped(plain) else plain
    }

    /** What this ledger calls close enough to balance, for [currency]. */
    fun toleranceFor(currency: String): BigDecimal? = tolerances[currency]

    private fun grouped(plain: String): String {
        val negative = plain.startsWith('-')
        val body = if (negative) plain.substring(1) else plain
        val whole = body.substringBefore('.')
        val rest = body.substringAfter('.', "")
        val withCommas = whole.reversed().chunked(3).joinToString(",").reversed()
        return buildString {
            if (negative) append('-')
            append(withCommas)
            if (rest.isNotEmpty()) append('.').append(rest)
        }
    }

    companion object {
        /** What a ledger that says nothing gets: Beancount's own plain rendering. */
        val PLAIN = LedgerFormat()
    }
}
