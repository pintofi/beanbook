package io.github.pintofi.beanbook.core.domain

/**
 * The syntax rules every draft shares.
 *
 * One place, because quoting is where a payee containing a `"` turns into a corrupted
 * ledger, and having each directive type implement its own escaping is how that happens in
 * exactly one of them.
 */
object BeancountText {

    val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** `Assets:Bank:Checking` — a root, then at least one colon-separated component. */
    val ACCOUNT = Regex("""[A-Z][A-Za-z0-9\-]*(:[A-Z0-9][A-Za-z0-9\-]*)+""")

    /** Currencies are upper-case and may contain digits, `.`, `_`, `-`, `'`. */
    val CURRENCY = Regex("""[A-Z][A-Z0-9'._\-]*""")

    val TAG = Regex("""[A-Za-z0-9\-_/.]+""")

    /** Metadata keys are lower-case and may not start with a digit. */
    val META_KEY = Regex("""[a-z][A-Za-z0-9\-_]*""")

    /**
     * The `document` directive naming [path], and its own metadata, removed from [source].
     *
     * Returns `null` when no such directive is there, so the caller can say so rather than
     * write the file back unchanged.
     *
     * Line-oriented, and deliberately narrow about what a directive is. A previous version
     * kept every line that did not contain both the path and the word "document", which
     * matched a transaction's own `document:` metadata and any comment mentioning the file,
     * and removed every occurrence rather than one.
     *
     * Terminators are preserved exactly. Splitting on lines and rejoining with "\n" rewrote
     * a CRLF ledger -- routine for a file synced from a Windows desktop -- across every
     * untouched line, which invariant 4 forbids: whitespace is never silently discarded.
     *
     * Indented lines following the directive go with it. They are its metadata, and leaving
     * them behind would orphan them into a syntax error.
     *
     * [from] restricts it to the directive filing the document under that account. Without
     * it every directive naming [path] goes, which is right for the ordinary single-filing
     * case and wrong once a document is filed under two accounts -- and unlike refiling,
     * this destroys the reference rather than moving it, so the other account's filing would
     * be gone with nothing on screen saying so.
     */
    fun removeDocumentDirective(source: String, path: String, from: String? = null): String? {
        // date, "document", an account, then the path as a quoted string of its own. The
        // account is captured so [from] can restrict the removal to one of several.
        val directive = Regex(
            """^\s*${DATE.pattern}\s+document\s+(?<account>${ACCOUNT.pattern})""" +
                """\s+"${Regex.escape(path)}"\s*(;.*)?$"""
        )
        val lines = splitKeepingTerminators(source)
        val kept = StringBuilder()
        var removedAny = false
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val match = directive.find(line.trimEnd('\r', '\n'))
            if (match == null || (from != null && match.groups["account"]!!.value != from)) {
                kept.append(line)
                index++
                continue
            }
            removedAny = true
            index++
            // Its metadata: indented, non-blank continuation lines.
            while (index < lines.size) {
                val next = lines[index]
                val body = next.trimEnd('\r', '\n')
                if (body.isNotBlank() && body.first().isWhitespace()) index++ else break
            }
        }
        return if (removedAny) kept.toString() else null
    }

    /**
     * [source] with the `document` directive naming [path] filed under [newAccount] instead.
     *
     * Returns `null` when no such directive is there, so the caller can say so rather than
     * report a move that did not happen.
     *
     * Only the account token is rewritten. The date, the spacing around it, the quoted path,
     * a trailing comment and the directive's own indented metadata are left exactly as they
     * were, as is every other line in the file: invariant 4 draws no distinction between
     * discarding the user's formatting and rewriting it.
     *
     * [from] narrows it to the directive currently filing the document under that account.
     * Without it every directive naming [path] is moved, which is right for the ordinary
     * single-filing case and wrong the moment a document is filed under two accounts.
     *
     * The same narrowness as [removeDocumentDirective], and for the same reason -- a
     * transaction's own `document:` metadata names the very same file, and is not a directive.
     */
    fun refileDocument(
        source: String,
        path: String,
        newAccount: String,
        from: String? = null,
    ): String? {
        require(ACCOUNT.matches(newAccount)) { "not an account: $newAccount" }
        // The account is captured on its own so that it, and nothing around it, can be swapped.
        val directive = Regex(
            // Named groups: ACCOUNT has capturing groups of its own, so the numbering here
            // would depend on that pattern's internals.
            """^(?<head>\s*${DATE.pattern}\s+document\s+)(?<account>${ACCOUNT.pattern})""" +
                """(?<tail>\s+"${Regex.escape(path)}"\s*(;.*)?)$"""
        )
        val lines = splitKeepingTerminators(source)
        val rebuilt = StringBuilder()
        var movedAny = false
        for (line in lines) {
            val terminator = line.takeLastWhile { it == '\r' || it == '\n' }
            val body = line.substring(0, line.length - terminator.length)
            val match = directive.matchEntire(body)
            // Beancount allows one document under two accounts, and the Organize list shows
            // a row per directive. Without [from], refiling one row moved both -- silently
            // changing a filing the user was not looking at.
            if (match == null || (from != null && match.groups["account"]!!.value != from)) {
                rebuilt.append(line)
                continue
            }
            movedAny = true
            // Rebuilt from the match's own ranges rather than with a replacement template,
            // because a trailing comment may contain $ or \ and a template would read those.
            rebuilt.append(match.groups["head"]!!.value).append(newAccount)
            rebuilt.append(match.groups["tail"]!!.value)
            rebuilt.append(terminator)
        }
        return if (movedAny) rebuilt.toString() else null
    }

    /**
     * The paths an `include` directive names, in the order they appear.
     *
     * Beancount accepts a directive only at column 0, so an indented `include` is not one --
     * matching it would treat a file as included when Beancount does not, and that file would
     * wrongly stop being a root candidate.
     *
     * The paths are as written. Resolving them against the including file is
     * [resolveInclude]'s job, because only the caller knows which file this text came from.
     */
    fun includedPaths(source: String): List<String> {
        val include = Regex("""^include\s+"([^"]*)"\s*(;.*)?$""")
        return source.lineSequence()
            .mapNotNull { include.matchEntire(it.trimEnd('\r'))?.groupValues?.get(1) }
            .filter { it.isNotBlank() }
            .toList()
    }

    /**
     * [include] resolved against the directory of [from], as a project-relative path.
     *
     * `.` and `..` are collapsed rather than carried. Left in, "books/../shared.beancount"
     * would match no entry when compared against the project's own file list, and a file that
     * really is included would look un-included -- which is exactly the wrong answer for
     * choosing a root.
     */
    fun resolveInclude(from: String, include: String): String {
        val parts = from.substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filter { it.isNotEmpty() }
            .toMutableList()
        for (segment in include.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    /**
     * The folders `option "documents"` names in [source], as written.
     *
     * Only the top-level file is worth reading: Beancount takes options from the file it was
     * asked to load and ignores them in included files. An option commented out with `;` does
     * not match, because the line must start with `option`.
     */
    fun documentRoots(source: String): List<String> =
        DOCUMENTS_OPTION.findAll(source)
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .filter { it.isNotBlank() }
            .toList()

    private val DOCUMENTS_OPTION =
        Regex("""^[ \t]*option[ \t]+"documents"[ \t]+"((?:[^"\\]|\\.)*)"""", RegexOption.MULTILINE)

    /**
     * [source] with any `document:` metadata naming [path] removed.
     *
     * A receipt attached while entering a transaction is a **metadata line on that
     * transaction**, not a `document` directive — `fava.plugins.link_documents` reads
     * `document` and `document-*` keys. Unlinking only ever removed directives, so a receipt
     * attached from the Add form stayed attached however often it was unlinked, and the
     * screen went on saying so. This removes the other half.
     *
     * Whole lines only, and only lines whose value is exactly the path: a comment mentioning
     * the filename, or a key whose value merely contains it, is left alone. Returns null when
     * nothing matched, so a caller can tell "removed" from "there was nothing there".
     */
    fun removeDocumentMetadata(source: String, path: String): String? {
        val leaf = path.substringAfterLast('/')
        val line = Regex(
            """^\s*document(-\d+)?\s*:\s*"(?<value>[^"]*)"\s*$""",
        )
        var removed = false
        val kept = source.lines().filter { text ->
            val match = line.matchEntire(text) ?: return@filter true
            val value = match.groups["value"]?.value.orEmpty()
            val names = value == path || value.substringAfterLast('/') == leaf
            if (names) removed = true
            !names
        }
        return if (removed) kept.joinToString("\n") else null
    }

    /**
     * The metadata key to link one more document to a transaction, given the keys it has.
     *
     * `fava.plugins.link_documents` reads `document` and any `document-*` key, which
     * [JournalEntry.linkedDocuments] mirrors. A second receipt cannot reuse `document`:
     * metadata is a map, so the first link would be silently replaced by the second.
     *
     * Gaps are filled rather than skipped, so removing the second of three attachments and
     * adding another does not leave the numbering climbing forever.
     */
    fun nextDocumentKey(existing: Set<String>): String {
        if ("document" !in existing) return "document"
        var index = 2
        while ("document-$index" in existing) index++
        return "document-$index"
    }

    /** Lines with their own terminators attached, so rejoining cannot change them. */
    private fun splitKeepingTerminators(source: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < source.length) {
            if (source[i] == '\n') {
                lines += source.substring(start, i + 1)
                start = i + 1
            }
            i++
        }
        if (start < source.length) lines += source.substring(start)
        return lines
    }

    /** What a highlighted run of source is. */
    enum class TokenKind { COMMENT, STRING, DATE, KEYWORD, ACCOUNT, NUMBER, CURRENCY, TAG }

    /** A run of [kind] covering `[start, end)` of the source it came from. */
    data class Token(val start: Int, val end: Int, val kind: TokenKind)

    private val DIRECTIVES = setOf(
        "open", "close", "commodity", "txn", "balance", "pad", "note", "document",
        "price", "event", "query", "custom", "option", "plugin", "include", "poptag",
        "pushtag", "popmeta", "pushmeta",
    )

    /**
     * Colours for one file of Beancount source, as non-overlapping runs.
     *
     * Line by line, and in a deliberate order: a comment claims the rest of its line, then
     * quoted strings claim theirs, and only what is left is looked at for dates, keywords,
     * accounts, numbers and currencies. That order is the whole correctness argument — a
     * comment mentioning `Assets:Bank` is prose, and an account name inside a narration is
     * part of the string, not an account. Colouring either one as code would be a lie about
     * what Beancount will do with the file.
     *
     * Ranges are returned rather than styled text so this stays in `:core-domain`, where it
     * can be tested without a Compose runtime, and so the caller owns the palette.
     */
    fun highlight(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var lineStart = 0

        source.split("\n").forEach { line ->
            val claimed = BooleanArray(line.length)

            fun claim(from: Int, to: Int, kind: TokenKind) {
                if (from >= to) return
                for (i in from until to) {
                    if (claimed[i]) return
                }
                for (i in from until to) claimed[i] = true
                tokens += Token(lineStart + from, lineStart + to, kind)
            }

            // 1. A comment owns the rest of the line, including anything that looks like code.
            val comment = line.indexOf(';')
            if (comment >= 0) claim(comment, line.length, TokenKind.COMMENT)

            // 2. Quoted strings, before anything inside them can be mistaken for code.
            var quote = line.indexOf('"')
            while (quote >= 0 && (comment < 0 || quote < comment)) {
                var end = quote + 1
                while (end < line.length && line[end] != '"') {
                    if (line[end] == '\\') end++
                    end++
                }
                val close = if (end < line.length) end + 1 else line.length
                claim(quote, close, TokenKind.STRING)
                quote = line.indexOf('"', close)
            }

            fun scan(pattern: Regex, kind: TokenKind, accept: (MatchResult) -> Boolean = { true }) {
                pattern.findAll(line).forEach { match ->
                    if (accept(match)) claim(match.range.first, match.range.last + 1, kind)
                }
            }

            scan(DATE, TokenKind.DATE)
            scan(Regex("""\b[a-z]+\b"""), TokenKind.KEYWORD) { it.value in DIRECTIVES }
            scan(ACCOUNT, TokenKind.ACCOUNT)
            scan(Regex("""-?\d[\d,]*\.?\d*"""), TokenKind.NUMBER)
            scan(Regex("""#[A-Za-z0-9\-_/.]+|\^[A-Za-z0-9\-_/.]+"""), TokenKind.TAG)
            // Currencies last: the pattern also matches a bare account root, so anything that
            // was an account has already claimed its characters by now.
            scan(Regex("""\b[A-Z][A-Z0-9'._\-]*\b"""), TokenKind.CURRENCY)

            lineStart += line.length + 1
        }

        return tokens.sortedBy { it.start }
    }

    /** Beancount strings are double-quoted; quotes and backslashes must be escaped. */
    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Pads [left] so [right] ends at [column], with at least one space between. */
    fun alignRight(left: String, right: String, column: Int): String {
        val padding = (column - left.length - right.length).coerceAtLeast(1)
        return left + " ".repeat(padding) + right
    }

    /**
     * Removes the 1-based lines [startLine]..[endLine] and the one blank line that set the
     * entry apart from the next, as Fava does.
     *
     * Every entry is written followed by one blank line, so that line goes with it: the blank
     * line after the entry, or, for an entry that has none (the last in a file written by hand),
     * the blank line before it. Nothing else is touched: other blank lines, comments and
     * whitespace stay.
     */
    fun removeEntryLines(lines: MutableList<String>, startLine: Int, endLine: Int) {
        val first = startLine - 1
        val last = endLine - 1
        val before = lines.getOrNull(first - 1)?.isBlank() == true
        val after = lines.getOrNull(last + 1)?.isBlank() == true
        repeat(last - first + 1) { lines.removeAt(first) }
        when {
            after -> lines.removeAt(first)
            before -> lines.removeAt(first - 1)
        }
    }
}
