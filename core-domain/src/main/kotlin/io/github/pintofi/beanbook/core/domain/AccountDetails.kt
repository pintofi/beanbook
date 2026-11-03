package io.github.pintofi.beanbook.core.domain

/**
 * Changing the metadata under an account's `open` directive, in the file, without disturbing
 * anything else in it.
 *
 * The directive is kept as written: its first line, its comments, its spacing, and every
 * metadata line whose value was not changed stay byte for byte. A changed value is rewritten in
 * place, a removed key loses its line, and a new key is added after the last line of the block.
 */
object AccountDetails {

    private val METADATA_LINE = Regex("""^(\s+)([a-z][a-zA-Z0-9\-_]*):(\s*)(.*)$""")

    sealed interface Result {
        data class Changed(val text: String) : Result
        data object Unchanged : Result
        data class Refused(val reason: String) : Result
    }

    /**
     * @param block the directive as it is in the file: the `open` line and the indented lines
     *   under it, joined by newlines.
     * @param original the metadata the ledger reported for the account, as text.
     * @param edited what the person wants it to be. A key with a blank value is removed.
     */
    fun rewrite(
        block: String,
        original: Map<String, String>,
        edited: Map<String, String>,
        /** The currencies the account is to allow, in the order to write them; null leaves them as they are. */
        currencies: List<String>? = null,
    ): Result {
        val details = rewriteMetadata(block, original, edited)
        if (currencies == null) return details
        val base = when (details) {
            is Result.Changed -> details.text
            is Result.Unchanged -> block
            is Result.Refused -> return details
        }
        return when (val allowed = rewriteCurrencies(base, currencies)) {
            is Result.Changed -> Result.Changed(allowed.text)
            is Result.Unchanged -> if (base == block) Result.Unchanged else Result.Changed(base)
            is Result.Refused -> allowed
        }
    }

    private val OPEN_LINE = Regex(
        """^(?<head>\s*${BeancountText.DATE.pattern}\s+open\s+${BeancountText.ACCOUNT.pattern})""" +
            """(?<list>\s+${BeancountText.CURRENCY.pattern}(?:\s*,\s*${BeancountText.CURRENCY.pattern})*)?""" +
            """(?<rest>(?:\s+"[^"]*")?\s*(?:;[^\n]*)?)$""",
    )

    /**
     * The currencies an `open` directive allows, rewritten on its first line.
     *
     * Only the list is touched: the date and its spacing, the account, the booking method, a
     * trailing comment, the line ending and every line under it stay as they are. An empty list
     * removes the restriction, so the account takes any currency.
     */
    fun rewriteCurrencies(block: String, currencies: List<String>): Result {
        val bad = currencies.firstOrNull { !BeancountText.CURRENCY.matches(it) }
        if (bad != null) return Result.Refused("\"$bad\" is not a valid currency name.")
        val lines = block.split("\n")
        val match = lines.firstOrNull()?.let { OPEN_LINE.matchEntire(it) }
            ?: return Result.Refused("that is not an open directive this app can read")
        val list = currencies.distinct()
        val first = match.groups["head"]!!.value +
            (if (list.isEmpty()) "" else " " + list.joinToString(",")) +
            match.groups["rest"]!!.value
        val text = (listOf(first) + lines.drop(1)).joinToString("\n")
        return if (text == block) Result.Unchanged else Result.Changed(text)
    }

    private fun rewriteMetadata(block: String, original: Map<String, String>, edited: Map<String, String>): Result {
        val keep = edited.filterValues { it.isNotBlank() }
        val bad = keep.keys.firstOrNull { !DirectiveDraft.isMetadataKey(it) }
        if (bad != null) return Result.Refused("\"$bad\" is not a valid name for a detail.")

        val lines = block.split("\n")
        if (lines.isEmpty() || !lines.first().contains(" open ")) {
            return Result.Refused("that is not an open directive")
        }

        val seen = mutableSetOf<String>()
        val out = mutableListOf(lines.first())
        for (line in lines.drop(1)) {
            val match = METADATA_LINE.matchEntire(line)
            val key = match?.groupValues?.get(2)
            if (match == null || key == null || key !in original) {
                out += line
                continue
            }
            seen += key
            val wanted = keep[key] ?: continue // removed
            if (wanted == original[key]) {
                out += line
            } else {
                out += match.groupValues[1] + key + ": " + DirectiveDraft.renderMetadataValue(wanted)
            }
        }
        // New keys go at the end of the directive.
        val added = keep.filterKeys { it !in original && it !in seen }
        added.forEach { (key, value) ->
            out += "  $key: ${DirectiveDraft.renderMetadataValue(value)}"
        }
        val text = out.joinToString("\n")
        return if (text == block) Result.Unchanged else Result.Changed(text)
    }
}
