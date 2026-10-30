package io.github.pintofi.beanbook.core.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting an entry or a `document` directive takes that text and at most one blank line with it, and
 * leaves every other line of the file exactly as it was.
 *
 * The example tests beside this one pin the layouts people have hit. This one tries a few thousand
 * layouts nobody wrote by hand: runs of blank lines, lines holding only spaces, comments, entries
 * with metadata, `document:` metadata naming the same file as a directive, and both line endings.
 * The seeds are fixed, so a failure names the seed and repeats.
 */
class TextEditPreservationTest {

    /** One generated ledger and where the pieces are, as 0-based line indexes into [lines]. */
    private class Ledger(
        val lines: List<String>,
        val entries: List<IntRange>,
        /** Each directive's line range (the directive and its metadata) and the path it names. */
        val directives: List<Pair<IntRange, String>>,
    )

    private fun generate(seed: Int): Ledger {
        val rnd = Random(seed)
        val eol = if (rnd.nextBoolean()) "\n" else "\r\n"
        val lines = mutableListOf<String>()
        val entries = mutableListOf<IntRange>()
        val directives = mutableListOf<Pair<IntRange, String>>()
        fun blanks() = repeat(rnd.nextInt(0, 3)) { lines += if (rnd.nextInt(4) == 0) "  \t" else "" }
        blanks()
        repeat(rnd.nextInt(2, 9)) { n ->
            val first = lines.size
            when (rnd.nextInt(4)) {
                0 -> lines += "; a comment $n"
                1 -> {
                    lines += "2026-01-0${n % 9 + 1} * \"Shop $n\" \"goods\""
                    lines += "  Expenses:Food  1.0$n USD"
                    lines += "  Assets:Cash"
                    // The same file a directive may name, as a transaction's own metadata.
                    if (rnd.nextBoolean()) lines += "  document: \"documents/r${rnd.nextInt(3)}.pdf\""
                    entries += first until lines.size
                }
                else -> {
                    val path = "documents/r${rnd.nextInt(3)}.pdf"
                    val comment = if (rnd.nextBoolean()) " ; filed" else ""
                    lines += "2026-01-0${n % 9 + 1} document Expenses:Food \"$path\"$comment"
                    if (rnd.nextBoolean()) lines += "  note: \"n$n\""
                    directives += (first until lines.size) to path
                }
            }
            blanks()
        }
        return Ledger(withEndings(lines.map { it + eol }, lines), entries, directives)
    }

    /** Every line ends with its terminator, except that the last may not: a file written by hand. */
    private fun withEndings(ended: List<String>, plain: List<String>): List<String> {
        if (plain.size % 2 == 0 || plain.isEmpty()) return ended
        return (ended.dropLast(1) + plain.last()).filter { it.isNotEmpty() }
    }

    private fun split(text: String): List<String> =
        text.split(Regex("(?<=\n)")).filter { it.isNotEmpty() }

    @Test
    fun `deleting an entry removes its lines and at most one blank line, and nothing else changes`() {
        var tried = 0
        for (seed in 0 until SEEDS) {
            val ledger = generate(seed)
            for (entry in ledger.entries) {
                tried++
                val work = ledger.lines.toMutableList()
                BeancountText.removeEntryLines(work, entry.first + 1, entry.last + 1)

                val original = ledger.lines
                val without = original.filterIndexed { i, _ -> i !in entry }
                val extraAfter = original.getOrNull(entry.last + 1)
                val extraBefore = original.getOrNull(entry.first - 1)
                val candidates = buildList {
                    add(without)
                    if (extraAfter != null && extraAfter.isBlank()) {
                        add(original.filterIndexed { i, _ -> i !in entry && i != entry.last + 1 })
                    }
                    if (extraBefore != null && extraBefore.isBlank()) {
                        add(original.filterIndexed { i, _ -> i !in entry && i != entry.first - 1 })
                    }
                }
                assertTrue(
                    work in candidates,
                    "seed $seed, entry lines ${entry.first + 1}-${entry.last + 1}: expected only those lines " +
                        "and at most one adjacent blank line to go.\nbefore: ${original.map { it.trimEnd() }}\n" +
                        "after:  ${work.map { it.trimEnd() }}",
                )
                // Whatever went with the entry, it was nothing but blank space beyond the entry itself.
                assertTrue(
                    original.size - work.size - entry.count() in 0..1,
                    "seed $seed: more than one extra line went",
                )
            }
        }
        assertTrue(tried > 500, "the generator should have produced plenty of entries, made $tried")
    }

    @Test
    fun `removing a document directive takes it, its metadata and at most one blank line, and nothing else`() {
        var tried = 0
        for (seed in 0 until SEEDS) {
            val ledger = generate(seed)
            val source = ledger.lines.joinToString("")
            for (path in ledger.directives.map { it.second }.distinct()) {
                tried++
                val result = BeancountText.removeDocumentDirective(source, path)
                assertNotNull(result, "seed $seed: a directive naming $path is there")

                val kept = split(result)
                val named = ledger.directives.filter { it.second == path }
                val gone = named.flatMap { it.first.toList() }.toSet()
                val expected = ledger.lines.filterIndexed { i, _ -> i !in gone }

                // Every line that remains is a line of the original, in order, untouched.
                var at = 0
                for (line in kept) {
                    while (at < expected.size && expected[at] != line) at++
                    assertTrue(at < expected.size, "seed $seed, $path: a line was changed or moved: ${line.trimEnd()}")
                    at++
                }
                // Only blank lines went beyond the directives themselves, and no more than one a directive.
                val extra = expected.size - kept.size
                assertTrue(
                    extra in 0..named.size,
                    "seed $seed, $path: $extra extra lines went for ${named.size}.\n" +
                        "before: ${ledger.lines.map { it.trimEnd() }}\nafter:  ${kept.map { it.trimEnd() }}",
                )
                val expectedNonBlank = expected.filter { it.isNotBlank() }
                assertEquals(
                    expectedNonBlank,
                    kept.filter { it.isNotBlank() },
                    "seed $seed, $path: a line that is not blank went or changed",
                )
                // Transactions naming the same file in their own metadata are not directives and stay.
                assertEquals(
                    ledger.lines.count { it.trimStart().startsWith("document:") },
                    kept.count { it.trimStart().startsWith("document:") },
                    "seed $seed, $path: a transaction's document metadata went",
                )
            }
        }
        assertTrue(tried > 500, "the generator should have produced plenty of directives, made $tried")
    }

    @Test
    fun `a path no directive names changes nothing`() {
        for (seed in 0 until SEEDS) {
            val ledger = generate(seed)
            assertNull(BeancountText.removeDocumentDirective(ledger.lines.joinToString(""), "documents/none.pdf"))
        }
    }

    @Test
    fun `refiling changes the account on the directives naming the path and not one other byte`() {
        for (seed in 0 until SEEDS) {
            val ledger = generate(seed)
            val source = ledger.lines.joinToString("")
            for (path in ledger.directives.map { it.second }.distinct()) {
                val result = BeancountText.refileDocument(source, path, "Assets:Bank:Docs")
                assertNotNull(result, "seed $seed: a directive naming $path is there")
                val directiveLines = ledger.directives.filter { it.second == path }.map { it.first.first }.toSet()
                val expected = ledger.lines.mapIndexed { i, line ->
                    if (i in directiveLines) line.replaceFirst(" Expenses:Food ", " Assets:Bank:Docs ") else line
                }.joinToString("")
                assertEquals(expected, result, "seed $seed, $path: something besides the account changed")
            }
        }
    }

    @Test
    fun `removing document metadata takes the lines naming the path and keeps every other byte`() {
        val tried = removeMetadataEverywhere(crlf = false)
        assertTrue(tried > 50, "the generator should have produced plenty of metadata lines, made $tried")
    }

    @Test
    fun `removing document metadata keeps CRLF line endings`() {
        val tried = removeMetadataEverywhere(crlf = true)
        assertTrue(tried > 50, "the generator should have produced plenty of metadata lines, made $tried")
    }

    /** Runs the metadata check over every generated ledger with the chosen line endings; returns how many removed. */
    private fun removeMetadataEverywhere(crlf: Boolean): Int {
        var tried = 0
        for (seed in 0 until SEEDS) {
            val ledger = generate(seed)
            if (ledger.lines.any { it.endsWith("\r\n") } != crlf) continue
            val source = ledger.lines.joinToString("")
            for (n in 0..2) {
                val path = "documents/r$n.pdf"
                val named = Regex("""^\s+document:\s*"${Regex.escape(path)}"\s*$""")
                val result = BeancountText.removeDocumentMetadata(source, path)
                val expected = ledger.lines.filterNot { named.matches(it.trimEnd('\r', '\n')) }
                if (expected.size == ledger.lines.size) {
                    assertNull(result, "seed $seed, $path: nothing names it")
                } else if (named.matches(ledger.lines.last())) {
                    // The last line has no terminator and goes: whether the line before keeps its
                    // own terminator is a choice either way, so only that layout is not compared.
                    continue
                } else {
                    tried++
                    assertEquals(
                        expected.joinToString(""),
                        result,
                        "seed $seed, $path: a line other than the metadata changed",
                    )
                }
            }
        }
        return tried
    }

    private companion object {
        const val SEEDS = 600
    }
}
