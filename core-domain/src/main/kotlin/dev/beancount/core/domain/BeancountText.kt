package dev.beancount.core.domain

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

    /** Beancount strings are double-quoted; quotes and backslashes must be escaped. */
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
     */
    fun removeDocumentDirective(source: String, path: String): String? {
        // date, "document", an account, then the path as a quoted string of its own.
        val directive = Regex(
            """^\s*${DATE.pattern}\s+document\s+${ACCOUNT.pattern}\s+"${Regex.escape(path)}"\s*(;.*)?$"""
        )
        val lines = splitKeepingTerminators(source)
        val kept = StringBuilder()
        var removedAny = false
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!directive.containsMatchIn(line.trimEnd('\r', '\n'))) {
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

    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Pads [left] so [right] ends at [column], with at least one space between. */
    fun alignRight(left: String, right: String, column: Int): String {
        val padding = (column - left.length - right.length).coerceAtLeast(1)
        return left + " ".repeat(padding) + right
    }
}
