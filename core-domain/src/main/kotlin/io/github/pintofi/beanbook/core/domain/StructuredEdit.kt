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
     * An entry the form can edit, and whether saving it will change only its layout.
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
        val plain = draftOf(entry).let { draft ->
            // Tags and links the file does not write were added on the way in: by
            // `fava.plugins.link_documents`, which links every entry that has a document, or
            // by a `pushtag` above it. They are not the entry's text and never were, so the
            // form leaves them out and the next load adds them again. Without this, every
            // entry with a receipt had a link the source did not contain and could only be
            // edited as text.
            val header = source.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            draft.copy(
                tags = draft.tags.filter { writtenIn(header, "#", it) },
                links = draft.links.filter { writtenIn(header, "^", it) },
            )
        }
        val typed = plain.copy(postings = withTypedDetails(plain.postings, source))
        val candidates = listOf(plain, typed).distinct().flatMap(::elisionVariants)
        candidates.firstOrNull { normalise(it.render()) == normalise(source) }
            ?.let { return Editable(it, reformats = false) }
        val wanted = content(source)
        candidates.firstOrNull { content(it.render()) == wanted }
            ?.let { return Editable(it, reformats = true) }
        // What the parsed entry cannot say -- a comment, a posting's own metadata, a value
        // written as a number rather than a string -- is in the entry's text. Read from there,
        // and held to the same proof: it is offered only if it renders back to the same words.
        val fromText = draftFromSource(source)?.takeIf { describes(entry, it) } ?: return null
        if (normalise(fromText.render()) == normalise(source)) return Editable(fromText, reformats = false)
        return fromText.takeIf { content(it.render()) == wanted }?.let { Editable(it, reformats = true) }
    }

    /**
     * The draft an entry's own text describes, or null when the text has a shape the draft cannot
     * hold. Nothing is guessed: whatever this reads is rendered back and compared with the text by
     * the caller, so a misreading is refused rather than saved.
     *
     * Held beyond what the form shows, so that editing the fields never costs the entry what the
     * form has no box for: full-line comments (above a posting, before the first line of
     * content, after the last posting), a comment at the end of the first line or of a posting,
     * metadata on a posting, and metadata written bare, as in `trip-days: 5`.
     */
    fun draftFromSource(source: String): TransactionDraft? {
        val lines = source.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val head = lines.firstOrNull() ?: return null
        val header = readHeader(head) ?: return null

        val notes = mutableListOf<String>()
        val metadata = linkedMapOf<String, String>()
        val bare = mutableSetOf<String>()
        val postings = mutableListOf<PostingDraft>()
        var pending = mutableListOf<String>()
        var base = -1

        for (raw in lines.drop(1)) {
            if (raw.isBlank()) return null
            val indent = raw.length - raw.trimStart().length
            val text = raw.trim()
            if (indent == 0) return null
            if (text.startsWith(";")) {
                pending += text.removePrefix(";")
                continue
            }
            if (base < 0) base = indent
            if (indent < base) return null
            val meta = METADATA_LINE.matchEntire(text)
            if (indent > base || (meta != null && postings.isEmpty())) {
                // A key and its value: on the entry (no posting yet, at the posting depth) or on
                // the posting above (deeper). Comments among the metadata have nowhere to go.
                meta ?: return null
                if (pending.isNotEmpty() && (postings.isNotEmpty() || metadata.isNotEmpty())) return null
                val (key, value, isBare) = readMetadata(meta) ?: return null
                if (indent > base) {
                    val last = postings.lastOrNull() ?: return null
                    if (key in last.metadata) return null
                    postings[postings.lastIndex] = last.copy(
                        metadata = last.metadata + (key to value),
                        bareMetadata = if (isBare) last.bareMetadata + key else last.bareMetadata,
                    )
                } else {
                    if (key in metadata) return null
                    metadata[key] = value
                    if (isBare) bare += key
                    notes += pending
                    pending = mutableListOf()
                }
                continue
            }
            val posting = readPosting(text) ?: return null
            if (postings.isEmpty() && metadata.isEmpty()) {
                notes += pending
            } else {
                // Comments between the entry's metadata and its first posting have no slot in a
                // draft; one above a later posting belongs to it.
                if (postings.isEmpty() && pending.isNotEmpty()) return null
            }
            postings += if (postings.isEmpty() && metadata.isEmpty()) posting
            else posting.copy(notesBefore = pending.takeIf { postings.isNotEmpty() }?.toList() ?: emptyList())
            pending = mutableListOf()
        }

        return TransactionDraft(
            date = header.date,
            flag = header.flag,
            payee = header.payee,
            narration = header.narration,
            tags = header.tags,
            links = header.links,
            metadata = metadata,
            postings = postings,
            headerComment = header.comment,
            notes = notes,
            trailingNotes = pending,
            bareMetadata = bare,
        )
    }

    /**
     * Whether [draft], read from the entry's text, is the entry Beancount parsed: the same date,
     * flag, strings, accounts and figures. A text that disagrees with the parsed entry is a file
     * that changed under the list, or something read wrongly, and neither is edited as fields.
     *
     * Tags and links a plugin added are in the entry and not in the text, so the draft's own
     * must be among the entry's rather than equal to them. One elided posting may stand for
     * several booked ones, which is what Beancount does with a leg in two currencies.
     */
    private fun describes(entry: JournalEntry, draft: TransactionDraft): Boolean {
        if (draft.date != entry.date || draft.flag != (entry.flag ?: "*")) return false
        if (draft.payee != entry.payee?.takeIf { it.isNotBlank() }) return false
        if (draft.narration != entry.narration?.takeIf { it.isNotBlank() }) return false
        if (!entry.tags.containsAll(draft.tags) || !entry.links.containsAll(draft.links)) return false
        var next = 0
        draft.postings.forEachIndexed { index, posting ->
            val booked = entry.postings.getOrNull(next) ?: return false
            if (booked.account != posting.account) return false
            next++
            val amount = posting.amount
            if (amount != null) {
                val figure = booked.amount ?: return false
                if (figure.currency != amount.currency || figure.number.compareTo(amount.number) != 0) return false
            } else {
                val left = draft.postings.size - index - 1
                while (entry.postings.size - next > left && entry.postings.getOrNull(next)?.account == posting.account) next++
            }
        }
        return next == entry.postings.size
    }

    private class Header(
        val date: String,
        val flag: String,
        val payee: String?,
        val narration: String?,
        val tags: List<String>,
        val links: List<String>,
        val comment: String?,
    )

    /** `2026-08-01 * "Payee" "Narration" #tag ^link ; comment`. Anything else is not read. */
    private fun readHeader(line: String): Header? {
        if (line.first().isWhitespace()) return null
        var i = 0
        fun skipSpaces() { while (i < line.length && line[i] == ' ') i++ }
        fun word(): String {
            val start = i
            while (i < line.length && !line[i].isWhitespace()) i++
            return line.substring(start, i)
        }
        val date = word()
        if (!BeancountText.DATE.matches(date)) return null
        skipSpaces()
        val flag = word()
        if (flag != "*" && flag != "!") return null
        val strings = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val links = mutableListOf<String>()
        var comment: String? = null
        while (true) {
            skipSpaces()
            if (i >= line.length) break
            when {
                line[i] == '"' -> {
                    val (value, end) = BeancountText.unquoteAt(line, i) ?: return null
                    if (tags.isNotEmpty() || links.isNotEmpty() || strings.size == 2) return null
                    strings += value
                    i = end
                }
                line[i] == '#' -> { val w = word().removePrefix("#"); if (!BeancountText.TAG.matches(w)) return null; tags += w }
                line[i] == '^' -> { val w = word().removePrefix("^"); if (!BeancountText.TAG.matches(w)) return null; links += w }
                line[i] == ';' -> { comment = line.substring(i + 1); i = line.length }
                else -> return null
            }
        }
        return Header(
            date = date,
            flag = flag,
            payee = if (strings.size == 2) strings[0] else null,
            narration = strings.lastOrNull(),
            tags = tags,
            links = links,
            comment = comment,
        )
    }

    private fun readMetadata(match: MatchResult): Triple<String, String, Boolean>? {
        val key = match.groupValues[1]
        val rest = match.groupValues[2].trim()
        if (rest.isEmpty()) return null
        if (rest.startsWith("\"")) {
            val (value, end) = BeancountText.unquoteAt(rest, 0) ?: return null
            if (rest.substring(end).isNotBlank()) return null
            return Triple(key, value, false)
        }
        if (!BeancountText.isBareMetadataValue(rest)) return null
        return Triple(key, rest, true)
    }

    /** `Account  12.50 USD {lot} @ price  ; comment`, with the account alone for an elided amount. */
    private fun readPosting(text: String): PostingDraft? {
        val comment = text.indexOf(';').takeIf { it >= 0 }
        val body = (if (comment != null) text.substring(0, comment) else text).trim()
        val remark = comment?.let { text.substring(it + 1).trim() }?.takeIf { it.isNotEmpty() }
        val account = body.substringBefore(' ').substringBefore('\t')
        if (!BeancountText.ACCOUNT.matches(account)) return null
        val rest = body.removePrefix(account).trim()
        if (rest.isEmpty()) return PostingDraft(account = account, comment = remark)
        val match = POSTING_TAIL.matchEntire(rest) ?: return null
        val number = runCatching { java.math.BigDecimal(match.groupValues[1].replace(",", "")) }.getOrNull()
            ?: return null
        val details = match.groupValues[3].trim()
        if (details.isNotEmpty() && !details.startsWith("{") && !details.startsWith("@")) return null
        return PostingDraft(
            account = account,
            amount = Amount(number, match.groupValues[2]),
            details = details.ifEmpty { null },
            comment = remark,
        )
    }

    private val METADATA_LINE = Regex("""([a-z][A-Za-z0-9\-_]*):(?:\s+(.*))?""")

    private fun writtenIn(header: String, sigil: String, word: String): Boolean =
        Regex("""(^|\s)${Regex.escape(sigil + word)}(\s|$)""").containsMatchIn(header)

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
        var escaped = false
        for (ch in text) {
            when {
                // Inside a string a backslash takes the next character with it, so an escaped
                // quote does not end the string and the spaces after it stay as typed.
                escaped -> { escaped = false; current.append(ch) }
                quoted && ch == '\\' -> { escaped = true; current.append(ch) }
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
