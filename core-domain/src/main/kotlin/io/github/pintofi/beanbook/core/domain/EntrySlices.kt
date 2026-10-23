package io.github.pintofi.beanbook.core.domain

import java.security.MessageDigest

/**
 * Where an entry's text is in its file, found by reading the file.
 *
 * An entry starts on the line Beancount reported for it and runs until the first blank line or
 * the first line that starts in column zero -- the rule Fava uses to cut an entry out of its
 * source, so the extent agrees with the one the engine used to answer. It used to be asked of
 * the engine, which loads the whole ledger to answer; for a large ledger, or an engine busy with
 * something else, Edit and Delete waited on that, and a failure left them waiting on nothing.
 * The lines are the answer, and they are in the file.
 */
object EntrySlices {

    /** An entry's extent: 1-based, inclusive lines, the text with `\n` between them, and its hash. */
    data class Slice(val startLine: Int, val endLine: Int, val text: String, val sha256: String)

    private val STARTS_WITH_DATE = Regex("""^\d{4}-\d{2}-\d{2}(\s|$)""")

    /**
     * The entry that starts at [line] (1-based) of [fileLines], or null when no entry can start
     * there: the line does not exist, or does not begin with a date in column zero.
     */
    fun at(fileLines: List<String>, line: Int): Slice? {
        val first = fileLines.getOrNull(line - 1)?.removeSuffix("\r") ?: return null
        if (!STARTS_WITH_DATE.containsMatchIn(first)) return null
        val taken = mutableListOf(first)
        var next = line
        while (true) {
            val candidate = fileLines.getOrNull(next)?.removeSuffix("\r") ?: break
            if (candidate.isBlank() || !candidate.first().isWhitespace()) break
            taken += candidate
            next++
        }
        val text = taken.joinToString("\n")
        return Slice(line, line + taken.size - 1, text, sha256(text))
    }

    /** Splits [content] into lines the way a file is read: `\n` or `\r\n`, no final empty line. */
    fun linesOf(content: String): List<String> = content.split("\n").let { parts ->
        if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
