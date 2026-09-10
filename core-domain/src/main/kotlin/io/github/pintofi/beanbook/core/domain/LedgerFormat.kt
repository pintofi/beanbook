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
    /**
     * What separates thousands and what separates the decimal, as this ledger asks (B67).
     *
     * Fava's `locale` option decides both, and its rule is worth stating because it is not
     * the obvious one: `render_commas` alone means the locale `en` -- 1,234.56 -- while
     * `custom "fava-option" "locale" "de_DE"` means 1.234,56 whatever `render_commas` says.
     * A German ledger displayed with the English scheme is not a cosmetic difference: it
     * turns one thousand two hundred into one point two.
     */
    val groupSeparator: Char = ',',
    val decimalSeparator: Char = '.',
    /**
     * Fava's `invert-income-liabilities-equity` (B67).
     *
     * Income, liabilities and equity carry credit balances, which in double entry are
     * negative. Fava can show them the way a person reads them -- income as a positive
     * number -- and a ledger that asks for that and gets the raw sign has every income figure
     * on screen with the wrong sign in front of it.
     */
    val invertIncomeLiabilitiesEquity: Boolean = false,
    /** Fava's `currency-column`: where an amount is aligned in a file this app writes. */
    val currencyColumn: Int = DEFAULT_CURRENCY_COLUMN,
    /** Fava's `indent`: how far a posting is indented in a file this app writes. */
    val indent: Int = DEFAULT_INDENT,
    /**
     * Which trees the inversion applies to, under this file's own names for them (B70).
     *
     * Beancount's `name_income` and friends rename the roots, so the three to turn around are
     * whatever *this* ledger calls income, liabilities and equity -- not the English words.
     */
    val invertedRoots: List<String> = INVERTED_ROOTS,
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
        return if (groupThousands) grouped(plain) else pointed(plain)
    }

    /**
     * [amount] with the sign this ledger asks to see it in, for an account under [account].
     *
     * Display only, and never on the way into a file: inverting a figure that is then written
     * back would change the books rather than the way they read.
     */
    fun signed(amount: Amount, account: String): Amount =
        if (invertIncomeLiabilitiesEquity && isInverted(account)) -amount else amount

    /** Whether [account] is one of the three trees Fava's option turns around. */
    fun isInverted(account: String): Boolean =
        invertedRoots.any { account == it || account.startsWith("$it:") }

    /** What this ledger calls close enough to balance, for [currency]. */
    fun toleranceFor(currency: String): BigDecimal? = tolerances[currency]

    /** The decimal point moved to where this ledger puts it, when thousands are not grouped. */
    private fun pointed(plain: String): String =
        if (decimalSeparator == '.') plain else plain.replace('.', decimalSeparator)

    private fun grouped(plain: String): String {
        val negative = plain.startsWith('-')
        val body = if (negative) plain.substring(1) else plain
        val whole = body.substringBefore('.')
        val rest = body.substringAfter('.', "")
        val withSeparators = whole.reversed().chunked(3).joinToString(groupSeparator.toString()).reversed()
        return buildString {
            if (negative) append('-')
            append(withSeparators)
            if (rest.isNotEmpty()) append(decimalSeparator).append(rest)
        }
    }

    companion object {
        /** Fava's own defaults, so a file that says nothing is written the way Fava writes it. */
        const val DEFAULT_CURRENCY_COLUMN = 61
        const val DEFAULT_INDENT = 2

        private val INVERTED_ROOTS = listOf("Income", "Liabilities", "Equity")

        /** What a ledger that says nothing gets: Beancount's own plain rendering. */
        val PLAIN = LedgerFormat()
    }
}
