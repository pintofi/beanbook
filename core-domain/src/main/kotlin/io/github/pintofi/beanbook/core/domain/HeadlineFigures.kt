package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal

/**
 * A figure of several commodities, cut down to what a headline can hold (design-direction §5).
 *
 * In Units, a ledger holding twenty commodities has a net worth of twenty lines, and Home set
 * all of them in its largest type: one headline taller than the screen, with the rest of Home
 * below the fold (device audit 2026-10-10). A headline is for the glance; the full list belongs
 * in Balances, one tap away. So a headline shows [max] lines and says how many it left out.
 *
 * **Which lines.** The operating currencies first, in the order the file declares them --
 * they are what the person thinks in -- then the rest by size, largest first, because a
 * position of 2,000 SAR says more about where somebody stands than 6 shares of APL does when
 * neither can be converted. Size is the absolute number: a large debt is as much the headline
 * as a large holding. Ties fall back to the commodity name so the order never shuffles between
 * two loads of the same ledger.
 *
 * Nothing here converts, rounds or adds: the amounts come out exactly as they went in, and the
 * caller formats them with the ledger's own format. Zero amounts are dropped first, the same
 * rule [LedgerSummary.format] applies, so a headline never spends one of its lines on nothing.
 */
data class HeadlineFigures(
    /** The lines to show, in the order to show them. */
    val shown: List<Amount>,
    /** How many non-zero amounts were left out. */
    val more: Int,
) {
    companion object {
        /** Three lines: enough for a home currency and the two largest others. */
        const val DEFAULT_LINES = 3

        fun of(
            amounts: List<Amount>,
            operating: List<String> = emptyList(),
            max: Int = DEFAULT_LINES,
        ): HeadlineFigures {
            require(max >= 1) { "a headline shows at least one line" }
            val nonZero = amounts.filter { it.number.compareTo(BigDecimal.ZERO) != 0 }
            val ordered = nonZero.sortedWith(
                compareBy<Amount> { amount ->
                    operating.indexOf(amount.currency).let { if (it < 0) Int.MAX_VALUE else it }
                }
                    .thenByDescending { it.number.abs() }
                    .thenBy { it.currency },
            )
            val shown = ordered.take(max)
            return HeadlineFigures(shown = shown, more = ordered.size - shown.size)
        }
    }
}
