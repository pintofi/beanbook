package io.github.pintofi.beanbook.core.domain

/**
 * What Fava's code is asked to work out: the period, the text, the tags and links, and the amounts.
 * Each is typed in its own box. They are kept as typed here and read by the engine, never by this app.
 *
 * @property time Fava's time filter: `2020`, `2020-03`, `2020-Q1`, `FY2020`, `month-1`, `2020-01-15 to 2020-03-10`.
 * @property text Fava's filter grammar for words: a plain word searches payees, descriptions and notes.
 * @property tags Fava's filter grammar for `#tag` and `^link`, with `-`, a comma for or, parentheses and `any()`.
 * @property amount Fava's filter grammar for amounts: `>=100`, `<50`, `=12.5`, `-=5`, `any(account:Food >20)`.
 */
data class ViewRequest(
    val time: String = "",
    val text: String = "",
    val tags: String = "",
    val amount: String = "",
) {
    val isActive: Boolean
        get() = time.isNotBlank() || text.isNotBlank() || tags.isNotBlank() ||
            amount.isNotBlank()
}

/**
 * What the Home, Journal and Balances screens are narrowed to, one filter for the three.
 *
 * A search typed on one screen used to stay on that screen: look something up in the journal,
 * switch to Balances for what it came to, and the search was gone, so the two screens were
 * answering different questions. Now there is one filter, and each screen shows the same slice of
 * the ledger until it is cleared.
 *
 * @property view the part Fava answers (period, text, tags and links, amounts); see [ViewRequest].
 * @property query words that must each appear in an entry (date, payee, narration, tags, links,
 *   accounts, figures), as the journal's search has always read them. This is Beanbook's own live
 *   search, not Fava's.
 * @property account part of an account name: only entries with a posting to a matching account are listed,
 *   and Balances lists only the matching accounts. Beanbook's own, not Fava's account filter.
 */
data class EntryFilter(
    val query: String = "",
    val account: String = "",
    val view: ViewRequest = ViewRequest(),
) {

    val isActive: Boolean get() = query.isNotBlank() || account.isNotBlank() || view.isActive

    fun matches(entry: JournalEntry): Boolean =
        (query.isBlank() || entry.matches(query)) &&
            (account.isBlank() || entry.postings.any { it.account.contains(account.trim(), ignoreCase = true) })
}

/** Fava's answer to a [ViewRequest]. */
sealed interface ViewAnswer {
    val request: ViewRequest

    /**
     * The ledger as Fava shows it for the request. [positions] are the kept transactions, as positions in the
     * journal a whole-ledger read returns, oldest first. [summaryRows] are the opening-balance rows Fava makes
     * up for a period; they have no file or line. [transactionCount] and [inputHash] say which ledger this is.
     */
    data class Ready(
        override val request: ViewRequest,
        val positions: Set<Int>,
        val summaryRows: List<JournalEntry>,
        val accounts: List<AccountBalance>,
        val rates: List<PriceRate>,
        val transactionCount: Int,
        val inputHash: String?,
        val begin: String?,
        val end: String?,
    ) : ViewAnswer

    /** An input Fava could not use. [field] is `time`, `text`, `tags` or `amount`; [message] is Fava's own words. */
    data class Refused(override val request: ViewRequest, val field: String, val message: String) : ViewAnswer
}

/**
 * This ledger as an active [filter] sees it.
 *
 * What Fava answers (the period, text, tags and links, amounts) comes from the engine's [answer], and only an
 * answer for this request and this very ledger counts: no answer yet, Fava refusing an input, or an answer for
 * files that have since changed gives an empty ledger, never the whole one, because a filter that did not apply
 * must not look like one that did. Balances then come from Fava, valued at the end of the period.
 *
 * Beanbook's own search and account name narrow the entries that are listed on top of that. The search also
 * works the figures out again from the entries it kept, at cost, as it always has; the account name only
 * narrows what is listed and leaves every figure as it was.
 */
fun LedgerSnapshot.filteredBy(filter: EntryFilter, answer: ViewAnswer? = null): LedgerSnapshot {
    if (!filter.isActive) return this
    val base = when {
        !filter.view.isActive -> this
        answer is ViewAnswer.Ready && answer.request == filter.view && answer.describes(this) -> viewed(answer)
        else -> return copy(journal = emptyList(), accounts = emptyList())
    }
    if (filter.query.isBlank() && filter.account.isBlank()) return base
    val kept = base.journal.filter(filter::matches)
    return base.copy(
        journal = kept,
        accounts = if (filter.query.isBlank()) base.accounts else TagBalances.of(kept),
    )
}

private fun LedgerSnapshot.viewed(answer: ViewAnswer.Ready): LedgerSnapshot {
    // The journal is newest first and the engine counts oldest first. The rows Fava makes up for a period are
    // dated the day before it starts, so they come last.
    val kept = journal.filterIndexed { index, _ -> (journal.lastIndex - index) in answer.positions }
    return copy(
        journal = kept + answer.summaryRows.asReversed(),
        accounts = answer.accounts,
        rates = answer.rates,
    )
}

private fun ViewAnswer.Ready.describes(ledger: LedgerSnapshot): Boolean =
    inputHash != null && inputHash == ledger.inputHash && transactionCount == ledger.journal.size
