package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal

/**
 * Account balances over only the entries a filter kept.
 *
 * The balances screen could not narrow its figures the way Fava's filter bar does. This totals the
 * postings of the entries it is given, the way the engine totals every posting for the unfiltered
 * screen: units by commodity, and a posting held at a cost weighed at that cost for the cost basis.
 * Which entries are kept is Fava's decision, made in the engine (see [EntryFilter]).
 *
 * **There is no market value for a filtered set.** The engine values a holding against the
 * file's price directives, which are not part of the journal this reads; so the value basis is
 * reported at cost, and the screen says so rather than presenting a cost figure as a value.
 */
object TagBalances {

    fun of(journal: List<JournalEntry>): List<AccountBalance> {
        val units = linkedMapOf<String, MutableMap<String, BigDecimal>>()
        val cost = linkedMapOf<String, MutableMap<String, BigDecimal>>()
        journal.forEach { entry ->
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
