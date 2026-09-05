package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reading `include` directives, so an import can pick a root deterministically (B9, B22).
 *
 * `findLedgerCandidates` claimed in its own docstring that a root is "a file that no other
 * file includes". It was never implemented: the code sorted by filename and path depth and
 * read no file at all. A project whose root is `personal.beancount` next to an included
 * `accounts.beancount` got the wrong root, alphabetically.
 */
class IncludeGraphTest {

    @Test
    fun `reads the paths a ledger includes`() {
        val source = """
            option "title" "Personal"
            include "accounts.beancount"
            include "2026/january.beancount"
        """.trimIndent()

        assertEquals(
            listOf("accounts.beancount", "2026/january.beancount"),
            BeancountText.includedPaths(source),
        )
    }

    @Test
    fun `ignores an include inside a comment`() {
        val source = "; include \"old.beancount\"\ninclude \"real.beancount\"\n"

        assertEquals(listOf("real.beancount"), BeancountText.includedPaths(source))
    }

    @Test
    fun `ignores an indented include, which Beancount does not accept as a directive`() {
        val source = "  include \"metadata-ish.beancount\"\ninclude \"real.beancount\"\n"

        assertEquals(listOf("real.beancount"), BeancountText.includedPaths(source))
    }

    @Test
    fun `reads includes from a file with CRLF endings`() {
        val source = "include \"a.beancount\"\r\ninclude \"b.beancount\"\r\n"

        assertEquals(listOf("a.beancount", "b.beancount"), BeancountText.includedPaths(source))
    }

    @Test
    fun `a ledger with no includes yields nothing`() {
        assertEquals(emptyList(), BeancountText.includedPaths("2026-01-01 open Assets:Bank\n"))
    }

    @Test
    fun `an include resolves against the directory of the file declaring it`() {
        assertEquals(
            "2026/january.beancount",
            BeancountText.resolveInclude(from = "main.beancount", include = "2026/january.beancount"),
        )
        assertEquals(
            "books/2026/january.beancount",
            BeancountText.resolveInclude(from = "books/main.beancount", include = "2026/january.beancount"),
        )
    }

    @Test
    fun `a parent reference in an include is resolved, not left in the path`() {
        // Left as-is, "books/../shared.beancount" would match no entry in the archive and the
        // file would look un-included -- making an included file a root candidate.
        assertEquals(
            "shared.beancount",
            BeancountText.resolveInclude(from = "books/main.beancount", include = "../shared.beancount"),
        )
        assertEquals(
            "books/shared.beancount",
            BeancountText.resolveInclude(from = "books/main.beancount", include = "./shared.beancount"),
        )
    }
}
