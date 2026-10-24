package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal

/**
 * Invariant: financial values use decimal arithmetic only. Binary floating
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

    fun residuals(): List<Amount> =
        byCurrency
            .filterValues { it.signum() != 0 }
            .map { (currency, number) -> Amount(number, currency) }
            .sortedBy { it.currency }

    companion object {
        fun of(amounts: List<Amount>): Balance {
            val sums = LinkedHashMap<String, BigDecimal>()
            for (amount in amounts) {
                sums[amount.currency] =
                    (sums[amount.currency] ?: BigDecimal.ZERO).add(amount.number)
            }
            return Balance(sums)
        }
    }
}

/**
 * How far from zero a residual may be, per currency, before Beancount calls a transaction
 * unbalanced. This is Beancount's own rule (`infer_tolerances` in `beancount/core/interpolate.py`,
 * checked against 3.2.3), ported rather than approximated, because the app refuses to save an
 * entry the ledger would have accepted if it computes a tighter tolerance, and lets through an
 * entry the ledger will reject if it computes a looser one:
 *
 *  * each currency is separate;
 *  * a currency's tolerance is half of the last decimal place of the *units* of every posting
 *    written with decimals, and the largest of those wins ("1.00" and "-0.999" tolerate 0.005);
 *  * a whole number contributes nothing, and does not cancel what the other postings say;
 *  * `option "inferred_tolerance_default"` is a *floor* for the currency it names, never a
 *    replacement for what the postings imply, and its `*` entry is what a currency gets when
 *    nothing else says anything about it.
 *
 * `option "tolerance_multiplier"` replaces the 0.5. With `option "infer_tolerance_from_cost"` a
 * posting held at cost or converted at a price also widens the tolerance of the *cost's* currency
 * by the last decimal place of its units times the cost (or price) figure times the multiplier,
 * summed over the postings, and the larger of that and the ordinary figure wins -- the rule in
 * `infer_tolerances`, with each cost term capped at 0.5.
 * Authoritative checking stays Beancount's: the ledger is validated by Beancount itself before
 * any write is reported as successful (invariant 2). This only decides what the form shows and
 * refuses while a person is typing.
 */
class Tolerances private constructor(
    private val byCurrency: Map<String, BigDecimal>,
    private val fallback: BigDecimal,
) {

    fun forCurrency(currency: String): BigDecimal = byCurrency[currency] ?: fallback

    companion object {
        /** Beancount's `tolerance_multiplier` default. */
        val DEFAULT_MULTIPLIER: BigDecimal = BigDecimal("0.5")

        /**
         * @param units the amounts as written on the postings (not their weights)
         * @param defaults `option "inferred_tolerance_default"`, by currency, with `*` for any
         *   other
         */
        fun infer(
            units: List<Amount>,
            defaults: Map<String, BigDecimal> = emptyMap(),
            multiplier: BigDecimal = DEFAULT_MULTIPLIER,
        ): Tolerances {
            val byCurrency = HashMap<String, BigDecimal>()
            defaults.forEach { (currency, tolerance) -> if (currency != "*") byCurrency[currency] = tolerance }
            for (amount in units) {
                // A scale of zero or less is a whole number: no decimals, so nothing to infer.
                val scale = amount.number.scale()
                if (scale <= 0) continue
                val inferred = BigDecimal.ONE.movePointLeft(scale).multiply(multiplier)
                val known = byCurrency[amount.currency]
                byCurrency[amount.currency] = if (known == null || inferred > known) inferred else known
            }
            return Tolerances(byCurrency, defaults["*"] ?: BigDecimal.ZERO)
        }

        /** No single cost or price term may widen a tolerance past this, as in Beancount. */
        private val MAXIMUM_TERM: BigDecimal = BigDecimal("0.5")

        /**
         * The same rule over whole postings, so that [fromCost] (`infer_tolerance_from_cost`)
         * can read the cost and price each one carries.
         */
        fun infer(
            postings: List<PostingDraft>,
            defaults: Map<String, BigDecimal> = emptyMap(),
            multiplier: BigDecimal = DEFAULT_MULTIPLIER,
            fromCost: Boolean = false,
        ): Tolerances {
            val byCurrency = HashMap<String, BigDecimal>()
            defaults.forEach { (currency, tolerance) -> if (currency != "*") byCurrency[currency] = tolerance }
            val terms = HashMap<String, BigDecimal>()
            for (posting in postings) {
                val units = posting.amount ?: continue
                val scale = units.number.scale()
                if (scale <= 0) continue
                val tolerance = BigDecimal.ONE.movePointLeft(scale).multiply(multiplier)
                val known = byCurrency[units.currency]
                byCurrency[units.currency] = if (known == null || tolerance > known) tolerance else known
                if (!fromCost) continue

                val details = posting.readDetails as? CommodityDetails.Read
                val costs = if (details != null) {
                    listOfNotNull(details.totalCost, details.costPerUnit)
                } else {
                    listOfNotNull(posting.cost?.amount)
                }
                costs.firstOrNull()?.let { first ->
                    // The smallest of the figures written (total and per unit), each capped.
                    val term = costs.minOf { tolerance.multiply(it.number.abs()).min(MAXIMUM_TERM) }
                    terms.merge(first.currency, term, BigDecimal::add)
                }

                // A total price is a per-unit price once divided by the units, which is what
                // Beancount has by this point.
                val price = details?.pricePerUnit ?: posting.price
                    ?: details?.totalPrice?.let {
                        Amount(it.number.abs().divide(units.number.abs(), java.math.MathContext.DECIMAL64), it.currency)
                    }
                price?.let {
                    terms.merge(it.currency, tolerance.multiply(it.number.abs()).min(MAXIMUM_TERM), BigDecimal::add)
                }
            }
            for ((currency, term) in terms) {
                val known = byCurrency[currency]
                byCurrency[currency] = if (known == null || term > known) term else known
            }
            return Tolerances(byCurrency, defaults["*"] ?: BigDecimal.ZERO)
        }
    }
}
