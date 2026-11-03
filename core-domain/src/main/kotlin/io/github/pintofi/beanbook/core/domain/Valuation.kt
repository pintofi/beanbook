package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.math.MathContext

/**
 * Which of a holding's three figures to show, everywhere in the app.
 *
 * The same three choices Fava offers at the top right of its reports, with the same meaning:
 * - [UNITS]: the exact units of every commodity, nothing converted.
 * - [COST]: what each holding was bought for, in the currency it was bought in.
 * - [VALUE] ("Market value"): everything at the latest price, in the operating currency when
 *   the ledger declares one; where no price exists, shown at cost; where there is no cost
 *   either, in its own units.
 */
enum class Valuation(val label: String) {
    UNITS("Units"),
    COST("At cost"),
    VALUE("Market value");

    fun of(account: AccountBalance): List<Amount> = when (this) {
        UNITS -> account.positions
        COST -> account.atCost
        VALUE -> account.atValue
    }

    /**
     * One posting's amount on this basis, for figures the app adds up itself from the journal
     * (spending by category) rather than taking from the engine's balances.
     */
    fun ofPosting(posting: JournalPosting, book: PriceBook, operating: String?): Amount? {
        val units = posting.amount ?: return null
        val cost = posting.cost
        val atCost = cost?.let { Amount(units.number.multiply(it.amount.number), it.amount.currency) }
        return when (this) {
            UNITS -> units
            COST -> atCost ?: units
            VALUE -> {
                val target = operating
                when {
                    target == null -> atCost ?: units
                    units.currency == target -> units
                    else -> book.convert(units, target)
                        ?: atCost?.let { if (it.currency == target) it else book.convert(it, target) ?: it }
                        ?: units
                }
            }
        }
    }
}

/** The latest price of one commodity in another, as the engine read it from the ledger. */
data class PriceRate(val base: String, val quote: String, val rate: BigDecimal)

/**
 * Converts an amount with the ledger's latest prices -- directly, by the inverse of a quoted
 * pair, or through one middle currency. Nothing is invented: no route means no conversion.
 */
class PriceBook(rates: List<PriceRate>) {

    private val direct: Map<Pair<String, String>, BigDecimal> =
        rates.associate { (it.base to it.quote) to it.rate }

    private val quotes: Map<String, List<String>> =
        direct.keys.groupBy({ it.first }, { it.second })

    fun convert(amount: Amount, target: String): Amount? {
        val from = amount.currency
        if (from == target) return amount
        direct[from to target]?.let { return Amount(amount.number.multiply(it, BEANCOUNT_MATH), target) }
        // A price quoted the other way round is used as Beancount uses it: its price map stores the
        // inverse as `1 / rate` worked to 28 digits, and the amount is multiplied by that. Dividing the
        // amount by the rate instead is the better sum and the wrong answer, since the ledger's own
        // engine (and Fava, which shows the same ledger beside this app) would round a different cent in
        // the rare case the exact result is half a cent. `5,625 PKR` at 75 is 74.99999999999999999999999998
        // that way, which shows as 75.00 like everything else.
        direct[target to from]?.takeIf { it.signum() != 0 }?.let { quoted ->
            val inverse = BigDecimal.ONE.divide(quoted, BEANCOUNT_MATH)
            return Amount(amount.number.multiply(inverse, BEANCOUNT_MATH), target)
        }
        for (middle in quotes[from].orEmpty()) {
            if (middle == target) continue
            val first = direct[from to middle] ?: continue
            val second = direct[middle to target] ?: continue
            return Amount(amount.number.multiply(first, BEANCOUNT_MATH).multiply(second, BEANCOUNT_MATH), target)
        }
        return null
    }

    companion object {
        val EMPTY = PriceBook(emptyList())

        /**
         * Python's default decimal context, which is what Beancount computes in: 28 significant digits,
         * rounding half to even.
         */
        private val BEANCOUNT_MATH = MathContext(28, java.math.RoundingMode.HALF_EVEN)
    }
}
