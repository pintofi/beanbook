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
 *   accounts, figures), as the journal's search has always read them. This is Beanbook's own live
 *   search, not Fava's.
 * @property expression a filter in Fava's own grammar (`#tag`, `^link`, `-`, `,`, parentheses,
 *   `any()`, plain words and so on). It is never read here: Fava's filter runs in the engine and
 *   answers with an [ExpressionAnswer].
 */
data class EntryFilter(val query: String = "", val expression: String = "") {

    val isActive: Boolean get() = query.isNotBlank() || expression.isNotBlank()

    fun matches(entry: JournalEntry): Boolean = query.isBlank() || entry.matches(query)
}

/** Fava's answer to a filter expression, for the expression it was asked about. */
sealed interface ExpressionAnswer {
    val expression: String

    /**
     * The transactions Fava kept, as positions in the journal a read returns: oldest first, counting
     * transactions only. [transactionCount] and [inputHash] say which ledger the positions are for.
     */
    data class Kept(
        override val expression: String,
        val positions: Set<Int>,
        val transactionCount: Int,
        val inputHash: String?,
    ) : ExpressionAnswer

    /** Fava could not read the expression; [message] is its own words. */
    data class Rejected(override val expression: String, val message: String) : ExpressionAnswer
}

/**
 * This ledger as an active [filter] sees it: only the matching entries, and every account's
 * balance worked out from those entries alone.
 *
 * With an expression, the entries are the ones Fava's [answer] kept, and only an answer for this
 * expression and this very ledger counts. Anything else (no answer yet, Fava refusing the
 * expression, an answer for files that have since changed) gives an empty ledger, never the whole
 * one: a filter that did not apply must not look like one that did.
 *
 * The balances come from the entries rather than from the engine, so market value is not
 * available while a filter is on: a filtered balance is reported at cost. An inactive filter
 * returns the ledger itself.
 */
fun LedgerSnapshot.filteredBy(filter: EntryFilter, answer: ExpressionAnswer? = null): LedgerSnapshot {
    if (!filter.isActive) return this
    val narrowed = when {
        filter.expression.isBlank() -> journal
        answer is ExpressionAnswer.Kept && answer.expression == filter.expression && answer.describes(this) ->
            // The journal is newest first and the engine counts oldest first.
            journal.filterIndexed { index, _ -> (journal.lastIndex - index) in answer.positions }
        else -> emptyList()
    }
    val kept = narrowed.filter(filter::matches)
    return copy(journal = kept, accounts = TagBalances.of(kept))
}

private fun ExpressionAnswer.Kept.describes(ledger: LedgerSnapshot): Boolean =
    inputHash != null && inputHash == ledger.inputHash && transactionCount == ledger.journal.size
