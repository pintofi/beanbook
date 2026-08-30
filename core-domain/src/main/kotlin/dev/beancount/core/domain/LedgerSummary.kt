package dev.beancount.core.domain

import java.math.BigDecimal

/**
 * The headline figures for a ledger, formatted once.
 *
 * Both the home surface and the home-screen widgets show these, and they must agree to the
 * character. `WidgetCache` computed and formatted them privately, with a comment warning that
 * doing it anywhere else is "how two places start disagreeing about what a number looks like";
 * adding a second screen is exactly that risk, so the computation moved here instead of being
 * copied (B10).
 *
 * Strings rather than numbers, deliberately: a widget runs in the launcher's process with no
 * access to the app's formatting, so the one formatter has to produce the final text.
 *
 * `null` means the ledger has nothing to say, not zero. Zero is a claim about somebody's
 * finances; the absence of accounts is not.
 */
data class LedgerSummary(
    val netWorth: String?,
    val income: String?,
    val expenses: String?,
) {
    companion object {

        fun of(ledger: LedgerSnapshot): LedgerSummary = LedgerSummary(
            // Assets *plus* liabilities, not minus: Beancount already stores what you owe as
            // a negative balance, so negating it made debt increase net worth.
            netWorth = format(sumByCurrency(ledger.totalFor("Assets") + ledger.totalFor("Liabilities"))),
            // Income is stored negative as well, and "-3000.00 earned" reads as a loss.
            income = format(sumByCurrency(negated(ledger.totalFor("Income")))),
            expenses = format(sumByCurrency(ledger.totalFor("Expenses"))),
        )

        fun negated(amounts: List<Amount>): List<Amount> =
            amounts.map { Amount(it.number.negate(), it.currency) }

        /**
         * Adds amounts of the same currency together.
         *
         * Two lists of totals concatenate rather than add, so net worth came out as
         * "1240.00 USD  300.00 USD" — two numbers side by side, which reads as a total and is
         * not one. Currencies are never mixed: adding them would need an exchange rate this
         * app does not have and must not invent.
         */
        fun sumByCurrency(amounts: List<Amount>): List<Amount> =
            amounts.groupBy { it.currency }
                .map { (currency, values) ->
                    Amount(values.fold(BigDecimal.ZERO) { total, a -> total + a.number }, currency)
                }
                .sortedBy { it.currency }

        /**
         * One currency per line.
         *
         * Joined with two spaces a multi-currency figure became a run-on inside a widget's
         * single `TextView`, unreadable at the glance a widget gets (B3).
         */
        fun format(amounts: List<Amount>): String? {
            val shown = amounts.filter { it.number.compareTo(BigDecimal.ZERO) != 0 }
                .ifEmpty { amounts.take(1) }
            if (shown.isEmpty()) return null
            return shown.joinToString("\n") { "${it.number.toPlainString()} ${it.currency}" }
        }
    }
}
