package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal

/**
 * Reads one entry's own text into a [TransactionDraft].
 *
 * Nothing is guessed: a line this does not recognise ends the read with null, and whatever it
 * does read is rendered back and compared with the text by [StructuredEdit], so a misreading is
 * refused rather than saved.
 *
 * It keeps what the form has no box for, so editing the fields never costs the entry any of it:
 * full-line comments (above a posting, before the first line of content, after the last
 * posting), a comment at the end of the first line or of a posting, metadata on a posting, and
 * metadata written bare, as in `trip-days: 5`.
 */
internal object EntrySource {

    fun parse(source: String): TransactionDraft? {
        val lines = source.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val header = lines.firstOrNull()?.let(::readHeader) ?: return null
        val collector = Collector()
        for (raw in lines.drop(1)) {
            if (!collector.accept(raw)) return null
        }
        return collector.finish(header)
    }

    /** What has been read of the body so far, and the rules for where each kind of line may sit. */
    private class Collector {
        val notes = mutableListOf<String>()
        val metadata = linkedMapOf<String, String>()
        val bare = mutableSetOf<String>()
        val postings = mutableListOf<PostingDraft>()
        var pending = mutableListOf<String>()
        var base = -1

        fun accept(raw: String): Boolean {
            if (raw.isBlank()) return false
            val indent = raw.length - raw.trimStart().length
            val text = raw.trim()
            if (indent == 0) return false
            if (text.startsWith(";")) {
                pending += text.removePrefix(";")
                return true
            }
            if (base < 0) base = indent
            if (indent < base) return false
            val meta = METADATA_LINE.matchEntire(text)
            return when {
                indent > base -> postingMetadata(meta)
                meta != null && postings.isEmpty() -> entryMetadata(meta)
                else -> posting(text)
            }
        }

        /** Metadata deeper than the posting line above it. */
        private fun postingMetadata(meta: MatchResult?): Boolean {
            val (key, value, isBare) = readMetadata(meta ?: return false) ?: return false
            val last = postings.lastOrNull() ?: return false
            if (pending.isNotEmpty() || key in last.metadata) return false
            postings[postings.lastIndex] = last.copy(
                metadata = last.metadata + (key to value),
                bareMetadata = if (isBare) last.bareMetadata + key else last.bareMetadata,
            )
            return true
        }

        /** Metadata on the entry itself: at the posting depth, before any posting. */
        private fun entryMetadata(meta: MatchResult): Boolean {
            val (key, value, isBare) = readMetadata(meta) ?: return false
            // A comment between metadata lines has nowhere to go in a draft.
            if (key in metadata || (metadata.isNotEmpty() && pending.isNotEmpty())) return false
            metadata[key] = value
            if (isBare) bare += key
            notes += pending
            pending = mutableListOf()
            return true
        }

        private fun posting(text: String): Boolean {
            val read = readPosting(text) ?: return false
            val first = postings.isEmpty()
            // Comments between the entry's metadata and its first posting have no slot either.
            if (first && metadata.isNotEmpty() && pending.isNotEmpty()) return false
            if (first && metadata.isEmpty()) {
                notes += pending
                postings += read
            } else {
                postings += read.copy(notesBefore = if (first) emptyList() else pending.toList())
            }
            pending = mutableListOf()
            return true
        }

        fun finish(header: Header) = TransactionDraft(
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

    private class Header(
        val date: String,
        val flag: String,
        val payee: String?,
        val narration: String?,
        val tags: List<String>,
        val links: List<String>,
        val comment: String?,
    )

    /** Walks the first line word by word. */
    private class Scanner(private val line: String) {
        var at = 0
        val atEnd get() = at >= line.length

        fun skipSpaces() {
            while (at < line.length && line[at] == ' ') at++
        }

        fun word(): String {
            val start = at
            while (at < line.length && !line[at].isWhitespace()) at++
            return line.substring(start, at)
        }

        fun peek(): Char = line[at]

        fun string(): String? {
            val (value, end) = BeancountText.unquoteAt(line, at) ?: return null
            at = end
            return value
        }

        fun rest(): String {
            val text = line.substring(at + 1)
            at = line.length
            return text
        }
    }

    /** `2026-08-01 * "Payee" "Narration" #tag ^link ; comment`. Anything else is not read. */
    private fun readHeader(line: String): Header? {
        if (line.first().isWhitespace()) return null
        val scan = Scanner(line)
        val date = scan.word().takeIf { BeancountText.DATE.matches(it) } ?: return null
        scan.skipSpaces()
        val flag = scan.word().takeIf { it == "*" || it == "!" } ?: return null
        val strings = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val links = mutableListOf<String>()
        var comment: String? = null
        while (true) {
            scan.skipSpaces()
            if (scan.atEnd) break
            when (scan.peek()) {
                '"' -> {
                    if (tags.isNotEmpty() || links.isNotEmpty() || strings.size == 2) return null
                    strings += scan.string() ?: return null
                }
                '#' -> tags += tagWord(scan.word().removePrefix("#")) ?: return null
                '^' -> links += tagWord(scan.word().removePrefix("^")) ?: return null
                ';' -> comment = scan.rest()
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

    private fun tagWord(word: String): String? = word.takeIf { BeancountText.TAG.matches(it) }

    private fun readMetadata(match: MatchResult): Triple<String, String, Boolean>? {
        val key = match.groupValues[1]
        val rest = match.groupValues[2].trim()
        if (rest.isEmpty()) return null
        if (rest.startsWith("\"")) {
            val (value, end) = BeancountText.unquoteAt(rest, 0) ?: return null
            return if (rest.substring(end).isBlank()) Triple(key, value, false) else null
        }
        return if (BeancountText.isBareMetadataValue(rest)) Triple(key, rest, true) else null
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
        val number = runCatching { BigDecimal(match.groupValues[1].replace(",", "")) }.getOrNull() ?: return null
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
    private val POSTING_TAIL = Regex("""(-?[\d,]*\.?\d+)\s+([A-Z][A-Z0-9'._\-]*)\s*(.*)""")
}
