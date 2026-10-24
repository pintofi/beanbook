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
    fun rewrite(block: String, original: Map<String, String>, edited: Map<String, String>): Result {
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
