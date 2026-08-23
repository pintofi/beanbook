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
    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Pads [left] so [right] ends at [column], with at least one space between. */
    fun alignRight(left: String, right: String, column: Int): String {
        val padding = (column - left.length - right.length).coerceAtLeast(1)
        return left + " ".repeat(padding) + right
    }
}
