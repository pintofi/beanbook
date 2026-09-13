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
            PostingDraft(
                account = posting.account,
                amount = posting.amount,
                cost = posting.cost,
                price = posting.price,
            )
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

    /**
     * An entry the form can edit, and whether saving it will change only its layout (B78).
     *
     * [reformats] is the honest part. A hand-written entry is usually aligned or indented
     * differently from how the app writes one, and its figures may carry thousands separators;
     * none of that is information, but invariant 4 says whitespace is never *silently*
     * discarded. So such an entry is editable as fields, and the form says, before saving,
     * that the entry's spacing will be rewritten -- the person chooses, rather than the app
     * choosing for them by sending every such entry to the text editor.
     */
    data class Editable(val draft: TransactionDraft, val reformats: Boolean)

    /**
     * The draft a form can edit [entry] with, or null when only the text editor can.
     *
     * Tried in order, strictest first: the draft exactly as the app would write it; the same
     * with the posting details (a lot, a price, `@@` totals) taken from the file's own text, so
     * what Beancount normalised is written back as it was typed; and then either of those
     * compared by content alone -- the same words, amounts and structure, in different spacing.
     * A comment, a line the draft does not model, or a different figure still fails all of
     * them, and those entries stay in the text editor, where nothing can be lost.
     */
    fun editableDraftOf(entry: JournalEntry, source: String): Editable? {
        val plain = draftOf(entry)
        val typed = plain.copy(postings = withTypedDetails(plain.postings, source))
        val candidates = listOf(plain, typed).distinct().flatMap(::elisionVariants)
        candidates.firstOrNull { normalise(it.render()) == normalise(source) }
            ?.let { return Editable(it, reformats = false) }
        val wanted = content(source)
        return candidates.firstOrNull { content(it.render()) == wanted }
            ?.let { Editable(it, reformats = true) }
    }

    /** The draft as it is, then with each single posting elided in turn. */
    private fun elisionVariants(draft: TransactionDraft): List<TransactionDraft> =
        listOf(draft) + draft.postings.indices.map { index ->
            draft.copy(
                postings = draft.postings.mapIndexed { at, posting ->
                    if (at == index) posting.copy(amount = null) else posting
                },
            )
        }

    /**
     * Each posting's details -- whatever follows the units on its line -- read from the file.
     *
     * Beancount divides an `@@` total into a per-unit price and books a lot with its date, so
     * the parsed posting renders a line the file does not contain. The file's own text is what
     * the person wrote and what the form should keep.
     */
    private fun withTypedDetails(postings: List<PostingDraft>, source: String): List<PostingDraft> {
        val lines = source.lines().drop(1).map { it.trim() }
        var from = 0
        return postings.map { posting ->
            val at = (from until lines.size).firstOrNull { lines[it].startsWith(posting.account) }
                ?: return@map posting
            from = at + 1
            val rest = lines[at].removePrefix(posting.account).substringBefore(';').trim()
            val match = POSTING_TAIL.matchEntire(rest) ?: return@map posting
            val details = match.groupValues[3].trim()
            if (details.startsWith("{") || details.startsWith("@")) posting.copy(details = details) else posting
        }
    }

    /**
     * The entry's content with its layout removed: indentation as a depth rather than a
     * width, runs of spaces as one, and thousands separators out of figures. Quoted strings
     * are left exactly as they are, spaces and all, because their spacing is the text.
     */
    private fun content(text: String): String {
        val lines = text.lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val widths = lines.map { line -> line.length - line.trimStart().length }.distinct().sorted()
        return lines.joinToString("\n") { line ->
            val depth = widths.indexOf(line.length - line.trimStart().length)
            "$depth|" + tokens(line.trim()).joinToString(" ") { token ->
                if (GROUPED_NUMBER.matches(token)) token.replace(",", "") else token
            }
        }
    }

    private fun tokens(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (ch in text) {
            when {
                ch == '"' -> { quoted = !quoted; current.append(ch) }
                ch.isWhitespace() && !quoted -> if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private val GROUPED_NUMBER = Regex("""-?\d{1,3}(,\d{3})+(\.\d+)?""")
    private val POSTING_TAIL = Regex("""(-?[\d,]*\.?\d+)\s+([A-Z][A-Z0-9'._\-]*)\s*(.*)""")

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
