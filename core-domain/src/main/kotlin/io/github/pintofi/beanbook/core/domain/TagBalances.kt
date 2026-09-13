package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal

/**
 * Account balances over only the entries that carry some tags or links (B78).
 *
 * Fava's filter bar narrows its reports with `#tag` and `^link`, and the journal here already
 * finds entries that way; the balances screen could not. This totals the postings of the
 * matching transactions, the way the engine totals every posting for the unfiltered screen:
 * units by commodity, and a posting held at a cost weighed at that cost for the cost basis.
 *
 * **There is no market value for a filtered set.** The engine values a holding against the
 * file's price directives, which are not part of the journal this reads; so the value basis is
 * reported at cost, and the screen says so rather than presenting a cost figure as a value.
 */
object TagBalances {

    /** What to filter by: tags without their `#`, links without their `^`. Empty means none. */
    data class Filter(val tags: Set<String> = emptySet(), val links: Set<String> = emptySet()) {
        val isEmpty: Boolean get() = tags.isEmpty() && links.isEmpty()

        /** Every tag and every link must be on the entry, as Fava combines its filter terms. */
        fun matches(entry: JournalEntry): Boolean =
            tags.all { it in entry.tags } && links.all { it in entry.links }

        companion object {
            /** `#trip ^invoice-4411`, or `trip`, typed in one box. A bare word is a tag. */
            fun parse(text: String): Filter {
                val words = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
                val (links, tags) = words.partition { it.startsWith('^') }
                return Filter(
                    tags = tags.map { it.removePrefix("#") }.filter { it.isNotEmpty() }.toSet(),
                    links = links.map { it.removePrefix("^") }.filter { it.isNotEmpty() }.toSet(),
                )
            }
        }
    }

    fun of(journal: List<JournalEntry>, filter: Filter): List<AccountBalance> {
        val units = linkedMapOf<String, MutableMap<String, BigDecimal>>()
        val cost = linkedMapOf<String, MutableMap<String, BigDecimal>>()
        journal.filter(filter::matches).forEach { entry ->
            entry.postings.forEach { posting ->
                val amount = posting.amount ?: return@forEach
                units.getOrPut(posting.account) { linkedMapOf() }
                    .merge(amount.currency, amount.number, BigDecimal::add)
                val basis = posting.cost?.amount
                val (number, commodity) = if (basis != null) {
                    amount.number.multiply(basis.number) to basis.currency
                } else {
                    amount.number to amount.currency
                }
                cost.getOrPut(posting.account) { linkedMapOf() }.merge(commodity, number, BigDecimal::add)
            }
        }
        return units.keys.sorted().map { account ->
            val held = units.getValue(account).toAmounts()
            val atCost = cost.getValue(account).toAmounts()
            AccountBalance(
                name = account,
                positions = held,
                atCost = atCost,
                atValue = atCost,
                atOperating = held,
            )
        }
    }

    private fun Map<String, BigDecimal>.toAmounts(): List<Amount> =
        entries.filter { it.value.signum() != 0 }
            .sortedBy { it.key }
            .map { Amount(it.value, it.key) }
}
