package io.github.pintofi.beanbook.core.domain

/**
 * Whether an entry can be edited as fields without losing anything.
 *
 * Editing an entry as text is safe and always available: replacing its byte range with what
 * the user wrote touches nothing else. Editing it as *fields* is friendlier and much more
 * dangerous, because a draft models a fixed set of things — date, flag, payee, narration,
 * tags, links, metadata, postings — and anything in the source it does not model would be
 * silently dropped on the way back out. A comment inside the entry, a directive shape the app
 * has no field for, the user's own alignment. Invariant 4 forbids exactly that, and it is
 * release-blocking.
 *
 * So the question is settled per entry, by proof rather than by assumption: rebuild the draft
 * from what the ledger parsed, render it, and compare with the bytes actually in the file.
 * Identical means the draft is a complete description of that entry and a form can edit it
 * without losing anything. Different means something in the file is not represented, and the
 * text editor is the only honest option.
 *
 * The comparison ignores trailing whitespace on each line and blank lines at either end,
 * because neither survives a rewrite meaningfully and neither is information a user would
 * miss. Everything else — including alignment *within* a line — must match.
 */
object StructuredEdit {

    /** The draft describing [entry], for rendering and comparison. */
    fun draftOf(entry: JournalEntry): TransactionDraft = TransactionDraft(
        date = entry.date,
        flag = entry.flag ?: "*",
        payee = entry.payee,
        narration = entry.narration,
        tags = entry.tags,
        links = entry.links,
        metadata = entry.metadata,
        postings = entry.postings.map { posting ->
            PostingDraft(account = posting.account, amount = posting.amount)
        },
    )

    /**
     * The draft that renders back to [source] exactly, or null when none does.
     *
     * [source] is the entry's own text as it appears in the file.
     *
     * One posting may have been written without an amount -- that is how a user asks
     * Beancount to work the remainder out, and it is what the Add form does by default --
     * but by the time the entry reaches here Beancount has filled the figure in, so the
     * straight draft renders a line the file does not contain. Rather than guess which leg
     * was elided, every single-elision candidate is rendered and compared: at most one can
     * match, and a match is proof rather than inference. Two elided postings cannot occur,
     * because Beancount refuses to load them.
     */
    fun losslessDraftOf(entry: JournalEntry, source: String): TransactionDraft? {
        val full = draftOf(entry)
        val wanted = normalise(source)
        if (normalise(full.render()) == wanted) return full
        return full.postings.indices.asSequence()
            .map { index ->
                full.copy(
                    postings = full.postings.mapIndexed { at, posting ->
                        if (at == index) posting.copy(amount = null) else posting
                    },
                )
            }
            .firstOrNull { normalise(it.render()) == wanted }
    }

    /** True when a field editor can round-trip [source] without losing anything. */
    fun isLossless(entry: JournalEntry, source: String): Boolean =
        losslessDraftOf(entry, source) != null

    private fun normalise(text: String): String =
        text.lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
}
