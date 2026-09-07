package io.github.pintofi.beanbook.core.domain

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

        fun of(ledger: LedgerSnapshot): LedgerSummary {
            // In the operating currency where the ledger declares one. These are single
            // headline figures, and a headline reading "1200.00 USD 340.00 EUR 0.5 BTC"
            // answers nothing -- it is a list of holdings, not a net worth. Where no
            // operating currency is declared, `atOperating` is the units and this behaves
            // exactly as it used to.
            val select = AccountBalance::atOperating
            // Rendered the way the file asks (B59): its own decimal places and its own
            // thousands separators, rather than whatever scale the arithmetic produced.
            val format = ledger.format
            return LedgerSummary(
                // Assets *plus* liabilities, not minus: Beancount already stores what you
                // owe as a negative balance, so negating it made debt increase net worth.
                netWorth = format(
                    sumByCurrency(
                        ledger.totalFor("Assets", select) + ledger.totalFor("Liabilities", select),
                    ),
                    format,
                ),
                // Income is stored negative as well, and "-3000.00 earned" reads as a loss.
                income = format(sumByCurrency(negated(ledger.totalFor("Income", select))), format),
                expenses = format(sumByCurrency(ledger.totalFor("Expenses", select)), format),
            )
        }

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
        fun format(
            amounts: List<Amount>,
            ledgerFormat: LedgerFormat = LedgerFormat.PLAIN,
        ): String? {
            val shown = amounts.filter { it.number.compareTo(BigDecimal.ZERO) != 0 }
                .ifEmpty { amounts.take(1) }
            if (shown.isEmpty()) return null
            return shown.joinToString("\n") { ledgerFormat.format(it) }
        }
    }
}

/**
 * The [count] most recent entries, newest first.
 *
 * The journal arrives newest first — the engine reports the last window of entries in file
 * order and the reader reverses it — so this is a `take`, not a `takeLast`. The home screen
 * used `takeLast(count).asReversed()`, which on an already-reversed list returns the oldest
 * entries in the ledger under a heading that says "Recent" (D9).
 */
fun LedgerSnapshot.recentEntries(count: Int): List<JournalEntry> = journal.take(count)
