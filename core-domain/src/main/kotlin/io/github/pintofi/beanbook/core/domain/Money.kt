package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Invariant 1 (SPEC.md 5): financial values use decimal arithmetic only. Binary floating
 * point never touches an amount.
 *
 * This type exists to make that invariant enforceable rather than merely intended: there
 * is no constructor, factory, or arithmetic operator here that accepts a [Double] or
 * [Float], so a floating-point value cannot enter the domain by accident.
 *
 * Beancount preserves the number exactly as written, including trailing zeros
 * ("10.00 USD" is not rewritten to "10 USD"), so [number] keeps the scale it was parsed
 * with and equality is scale-sensitive.
 */
data class Amount(val number: BigDecimal, val currency: String) {

    init {
        require(currency.isNotBlank()) { "currency must not be blank" }
        require(currency.all { it.isLetterOrDigit() || it in "._-'" }) {
            "invalid currency: $currency"
        }
    }

    operator fun plus(other: Amount): Amount {
        requireSameCurrency(other)
        return Amount(number.add(other.number), currency)
    }

    operator fun minus(other: Amount): Amount {
        requireSameCurrency(other)
        return Amount(number.subtract(other.number), currency)
    }

    operator fun unaryMinus(): Amount = Amount(number.negate(), currency)

    val isZero: Boolean get() = number.signum() == 0

    /** Renders the amount the way Beancount writes it, preserving the original scale. */
    fun format(): String = "${number.toPlainString()} $currency"

    private fun requireSameCurrency(other: Amount) {
        require(currency == other.currency) {
            "cannot combine $currency with ${other.currency}"
        }
    }

    companion object {
        /**
         * The only supported way to build an [Amount] from text. Parsing from a string
         * keeps every digit the user wrote; going via [Double] would not.
         */
        fun parse(number: String, currency: String): Amount =
            Amount(BigDecimal(number.trim()), currency.trim())

        fun zero(currency: String): Amount = Amount(BigDecimal.ZERO, currency)
    }
}

/**
 * The residual of a set of postings, per currency. A transaction balances when every
 * residual is zero within tolerance.
 *
 * The app never silently balances a transaction: it reports the residual and lets the
 * user decide (docs/ux.md 4).
 */
class Balance private constructor(private val byCurrency: Map<String, BigDecimal>) {

    val isBalanced: Boolean get() = byCurrency.values.all { it.signum() == 0 }

    fun residuals(): List<Amount> =
        byCurrency
            .filterValues { it.signum() != 0 }
            .map { (currency, number) -> Amount(number, currency) }
            .sortedBy { it.currency }

    /**
     * Beancount tolerates a residual smaller than half the last decimal place of the
     * amounts involved. This mirrors that idea without guessing at upstream's exact
     * inference rules, which stay upstream's job.
     */
    fun isBalancedWithin(tolerance: BigDecimal): Boolean =
        byCurrency.values.all { it.abs() <= tolerance }

    companion object {
        fun of(amounts: List<Amount>): Balance {
            val sums = LinkedHashMap<String, BigDecimal>()
            for (amount in amounts) {
                sums[amount.currency] =
                    (sums[amount.currency] ?: BigDecimal.ZERO).add(amount.number)
            }
            return Balance(sums)
        }

        /**
         * Beancount infers a tolerance per posting as half of that number's last decimal
         * place, then takes the **maximum** across postings — so the *least* precise
         * number sets the tolerance. `1.00` and `-0.999` therefore tolerate 0.005, not
         * 0.0005.
         *
         * This is a UI-side helper for showing whether an entry is plausibly balanced
         * while it is being typed. Authoritative tolerance handling stays upstream's job:
         * the ledger is validated by Beancount itself before any write is reported as
         * successful (invariant 2).
         */
        fun toleranceFor(amounts: List<Amount>): BigDecimal {
            val scale = amounts.minOfOrNull { it.number.scale() } ?: 0
            if (scale <= 0) return BigDecimal.ZERO
            return BigDecimal.ONE.movePointLeft(scale).divide(
                BigDecimal(2), scale + 1, RoundingMode.HALF_UP
            )
        }
    }
}
