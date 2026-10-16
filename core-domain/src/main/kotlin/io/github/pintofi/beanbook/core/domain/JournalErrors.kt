package io.github.pintofi.beanbook.core.domain

/**
 * Which entry an error is about.
 *
 * A ledger's errors were a list at the top of the screen and nothing more: "Transaction does
 * not balance: (-1.00 USD)" named a file and a line, and the entry it was about sat somewhere
 * in a journal of thousands, looking exactly like every entry that was fine. The reader had
 * to carry a line number to a text editor to find out which one.
 *
 * Beancount reports a source position for almost every error, and every transaction carries
 * the position it was parsed from, so the two can be put together. An error is attributed to
 * the entry whose text contains its line: the same line when Beancount points at the
 * directive, and a line inside the entry when it points at a posting.
 *
 * Entries do not record where they end, so the end is taken as the start of the next entry in
 * the same file. That is an over-estimate when a blank line or another directive follows --
 * an error on the `balance` directive after a transaction would attach to the transaction --
 * so the span only ever reaches to the next entry, and an error at a line no entry starts at
 * or before is left where it was, in the ledger's own list.
 */
object JournalErrors {

    /** `main.beancount:12`, the position an entry can be recognised by. */
    fun keyOf(entry: JournalEntry): String? =
        entry.file?.let { file -> entry.line?.let { line -> "$file:$line" } }

    /**
     * The errors belonging to each entry, keyed by [keyOf].
     *
     * Only entries that own an error appear, so an empty map means the journal itself is
     * clean even when the ledger is not.
     */
    fun attach(
        journal: List<JournalEntry>,
        errors: List<LedgerError>,
    ): Map<String, List<LedgerError>> {
        if (journal.isEmpty() || errors.isEmpty()) return emptyMap()

        // Per file, the entries in the order they are written, which is what makes "the next
        // entry" meaningful. The journal itself is ordered by date and comes newest first.
        val byFile: Map<String, List<JournalEntry>> = journal
            .filter { it.file != null && it.line != null }
            .groupBy { it.file!! }
            .mapValues { (_, entries) -> entries.sortedBy { it.line!! } }

        val attached = LinkedHashMap<String, MutableList<LedgerError>>()
        for (error in errors) {
            val file = error.file ?: continue
            val line = error.line ?: continue
            val entries = byFile[file] ?: continue
            val at = entries.indexOfLast { it.line!! <= line }
            if (at < 0) continue
            val owner = entries[at]
            val ends = entries.getOrNull(at + 1)?.line
            if (ends != null && line >= ends) continue
            attached.getOrPut(keyOf(owner)!!) { mutableListOf() } += error
        }
        return attached
    }

    /**
     * The errors no entry owns: a failing `balance` assertion, a bad `option`, a plugin's
     * complaint about the file as a whole.
     *
     * These have nowhere to be marked, so they stay in the ledger's own list rather than
     * being dropped -- an error the app stopped showing is an error the user cannot fix.
     */
    fun unattached(
        journal: List<JournalEntry>,
        errors: List<LedgerError>,
    ): List<LedgerError> {
        val owned = attach(journal, errors).values.flatten().toSet()
        return errors.filterNot { it in owned }
    }
}
