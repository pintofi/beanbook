package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal

/**
 * What a posting says about its commodity after the amount: the lot and the price.
 *
 * In a Beancount file these are written on the same line, to the right of the units:
 *
 * ```
 * Assets:Broker   10 ACME {12.50 USD, 2025-01-01, "lot-a"} @ 13.00 USD
 * Assets:Broker   10 ACME {{125.00 USD}} @@ 130.00 USD
 * Assets:Broker  -10 ACME {}
 * ```
 *
 * The form used to take a cost and a price as four separate boxes, which could say
 * `{12.50 USD}` and `@ 13.00 USD` and nothing else: no total cost, no total price, no
 * acquisition date or lot label, and no empty `{}` that asks Beancount to pick the lot. The
 * entry form now takes this part of the line as text, the way the file has it, and this is the
 * one place that reads it back -- enough to weigh the posting for the balance shown while
 * typing, and to refuse text Beancount could not parse before it is written.
 *
 * **It does not rewrite what was typed.** The text goes into the file as entered. Reading it
 * is only for the arithmetic, and where the arithmetic cannot be done without Beancount's own
 * booking -- an empty `{}`, a lot chosen by label alone -- it says so rather than guessing.
 */
sealed interface CommodityDetails {

    /** Weighs [units] the way Beancount would, or null when only Beancount can. */
    fun weight(units: Amount): Amount?

    /** The lot and price were read, and the posting can be weighed. */
    data class Read(
        /** Cost per unit, when the text names one. */
        val costPerUnit: Amount? = null,
        /** Total cost of the lot, when written with double braces. */
        val totalCost: Amount? = null,
        /** Price per unit, when written with `@`. */
        val pricePerUnit: Amount? = null,
        /** Total price, when written with `@@`. */
        val totalPrice: Amount? = null,
        /** Whether a lot was named at all, even an empty one. */
        val hasLot: Boolean = false,
    ) : CommodityDetails {
        override fun weight(units: Amount): Amount? {
            // Cost wins over price, which is Beancount's rule: the books move at cost, and a
            // price on a reduction records only what it went out at.
            costPerUnit?.let { return Amount(units.number.multiply(it.number), it.currency) }
            totalCost?.let { return Amount(it.number.multiply(BigDecimal(units.number.signum())), it.currency) }
            // A lot with no figure is booked by Beancount against what it holds; the weight
            // is the cost of that lot, which nothing on this line says.
            if (hasLot) return null
            pricePerUnit?.let { return Amount(units.number.multiply(it.number), it.currency) }
            totalPrice?.let { return Amount(it.number.multiply(BigDecimal(units.number.signum())), it.currency) }
            return units
        }
    }

    /** Text that is not a lot or a price Beancount would accept. */
    data class Unreadable(val reason: String) : CommodityDetails {
        override fun weight(units: Amount): Amount? = null
    }

    companion object {

        /** A number as Beancount reads it: digits, optionally grouped by commas, optionally decimals. */
        private val NUMBER = Regex("""-?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?""")

        /** `{12.50 USD, 2025-01-01, "label"}` or `{{125 USD}}`, then `@ 13 USD` or `@@ 130 USD`. */
        fun parse(text: String): CommodityDetails {
            var rest = text.trim()
            if (rest.isEmpty()) return Read()

            var read = Read()
            if (rest.startsWith("{")) {
                val double = rest.startsWith("{{")
                val close = if (double) rest.indexOf("}}") else rest.indexOf('}')
                if (close < 0) return Unreadable("the lot is not closed with ${if (double) "}}" else "}"}")
                val inside = rest.substring(if (double) 2 else 1, close).trim()
                rest = rest.substring(close + if (double) 2 else 1).trim()
                read = read.copy(hasLot = true)
                if (inside.isNotEmpty()) {
                    val parts = splitOutsideQuotes(inside)
                    var amount: Amount? = null
                    for (part in parts) {
                        val token = part.trim()
                        when {
                            token.isEmpty() -> Unit
                            BeancountText.DATE.matches(token) -> Unit
                            token.startsWith('"') && token.endsWith('"') && token.length >= 2 -> Unit
                            else -> amount = readAmount(token) ?: return Unreadable(
                                "\"$token\" in the lot is not an amount, a date, or a quoted label",
                            )
                        }
                    }
                    read = if (double) read.copy(totalCost = amount) else read.copy(costPerUnit = amount)
                }
            }

            if (rest.isNotEmpty()) {
                val total = rest.startsWith("@@")
                if (!rest.startsWith("@")) return Unreadable("\"$rest\" is not a lot in braces or a price after @")
                val priceText = rest.removePrefix(if (total) "@@" else "@").trim()
                val price = readAmount(priceText)
                    ?: return Unreadable("\"$priceText\" is not a price such as 13.00 USD")
                read = if (total) read.copy(totalPrice = price) else read.copy(pricePerUnit = price)
            }
            return read
        }

        /** How the form shows a posting that arrived with a cost and a price already (a clone). */
        fun textOf(cost: Cost?, price: Amount?): String = buildString {
            cost?.let { append(it.format()) }
            price?.let {
                if (isNotEmpty()) append(' ')
                append("@ ").append(it.format())
            }
        }

        private fun readAmount(text: String): Amount? {
            val pieces = text.trim().split(Regex("""\s+"""))
            if (pieces.size != 2) return null
            val (number, commodity) = pieces
            if (!NUMBER.matches(number) || !BeancountText.CURRENCY.matches(commodity)) return null
            return Amount(BigDecimal(number.replace(",", "")), commodity)
        }

        /**
         * Whether the comma at [at] is inside a number such as `1,250.00`, which Beancount reads
         * as one figure, rather than the comma between the parts of a lot. A separator has a digit
         * directly before it and exactly three directly after, with no space either side.
         */
        private fun isThousandsSeparator(text: String, at: Int): Boolean =
            at > 0 && text[at - 1].isDigit() &&
                (at + 1..at + 3).all { it < text.length && text[it].isDigit() } &&
                (at + 4 >= text.length || !text[at + 4].isDigit())

        private fun splitOutsideQuotes(text: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            for ((at, ch) in text.withIndex()) {
                when {
                    ch == '"' -> { quoted = !quoted; current.append(ch) }
                    ch == ',' && !quoted && !isThousandsSeparator(text, at) -> {
                        parts += current.toString()
                        current.clear()
                    }
                    else -> current.append(ch)
                }
            }
            parts += current.toString()
            return parts
        }
    }
}
