package io.github.pintofi.beanbook.core.domain

/**
 * What the Home, Journal and Balances screens are narrowed to, one filter for the three.
 *
 * A search typed on one screen used to stay on that screen: look something up in the journal,
 * switch to Balances for what it came to, and the search was gone, so the two screens were
 * answering different questions. Now there is one filter, and each screen shows the same slice of
 * the ledger until it is cleared.
 *
 * @property query words that must each appear in an entry (date, payee, narration, tags, links,
 *   accounts, figures), as the journal's search has always read them.
 * @property tags `#tag` and `^link` terms, as Fava's filter bar takes them.
 */
data class EntryFilter(val query: String = "", val tags: String = "") {

    val isActive: Boolean get() = query.isNotBlank() || tags.isNotBlank()

    private val tagFilter: TagBalances.Filter get() = TagBalances.Filter.parse(tags)

    fun matches(entry: JournalEntry): Boolean {
        if (query.isNotBlank() && !entry.matches(query)) return false
        val terms = tagFilter
        return terms.isEmpty || terms.matches(entry)
    }
}

/**
 * This ledger as an active [filter] sees it: only the matching entries, and every account's
 * balance worked out from those entries alone.
 *
 * The balances come from the entries rather than from the engine, so market value is not
 * available while a filter is on: a filtered balance is reported at cost, the same rule the
 * tag filter on the Balances screen has always followed. An inactive filter returns the ledger
 * itself.
 */
fun LedgerSnapshot.filteredBy(filter: EntryFilter): LedgerSnapshot {
    if (!filter.isActive) return this
    val kept = journal.filter(filter::matches)
    return copy(
        journal = kept,
        accounts = TagBalances.of(kept, TagBalances.Filter()),
    )
}
