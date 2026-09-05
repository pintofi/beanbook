package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Refiling a document under a different account (B18).
 *
 * The account is the only token that may move. Everything else on the line — the date, the
 * spacing, the quoted path, a trailing comment — and every other line in the file is the
 * user's, and invariant 4 does not distinguish between discarding it and rewriting it.
 *
 * Modelled on [DocumentDirectiveTest], which records why this family of edits is done by
 * matching a directive rather than by searching for the path.
 */
class RefileDocumentTest {

    @Test
    fun `moves the directive to the new account`() {
        val source = """
            2026-01-01 open Assets:Bank
            2026-02-01 document Assets:Bank "documents/receipt.pdf"
            2026-03-01 open Expenses:Food
        """.trimIndent() + "\n"

        val result = BeancountText.refileDocument(source, "documents/receipt.pdf", "Expenses:Food")

        assertEquals(
            """
            2026-01-01 open Assets:Bank
            2026-02-01 document Expenses:Food "documents/receipt.pdf"
            2026-03-01 open Expenses:Food
            """.trimIndent() + "\n",
            result,
        )
    }

    @Test
    fun `keeps the directive's own metadata and trailing comment`() {
        val source = "2026-02-01 document Assets:Bank \"r.pdf\"  ; scanned\n" +
            "  statement: \"February\"\n"

        val result = BeancountText.refileDocument(source, "r.pdf", "Assets:Card")

        assertEquals(
            "2026-02-01 document Assets:Card \"r.pdf\"  ; scanned\n" +
                "  statement: \"February\"\n",
            result,
        )
    }

    @Test
    fun `preserves CRLF terminators on untouched lines`() {
        val source = "2026-01-01 open Assets:Bank\r\n" +
            "2026-02-01 document Assets:Bank \"r.pdf\"\r\n"

        val result = BeancountText.refileDocument(source, "r.pdf", "Assets:Card")

        assertEquals(
            "2026-01-01 open Assets:Bank\r\n" +
                "2026-02-01 document Assets:Card \"r.pdf\"\r\n",
            result,
        )
    }

    @Test
    fun `does not touch a transaction's document metadata naming the same file`() {
        val source = """
            2026-02-01 * "Shop"
              document: "r.pdf"
              Assets:Bank  -5.00 USD
        """.trimIndent() + "\n"

        assertNull(BeancountText.refileDocument(source, "r.pdf", "Assets:Card"))
    }

    @Test
    fun `returns null when no directive names that path`() {
        val source = "2026-02-01 document Assets:Bank \"other.pdf\"\n"

        assertNull(BeancountText.refileDocument(source, "r.pdf", "Assets:Card"))
    }

    @Test
    fun `refuses an account that is not one`() {
        val source = "2026-02-01 document Assets:Bank \"r.pdf\"\n"

        // Writing this would produce a file that no longer parses. The write protocol would
        // catch it at the re-parse and roll back, but failing here says which input was wrong.
        assertFailsWith<IllegalArgumentException> {
            BeancountText.refileDocument(source, "r.pdf", "not an account")
        }
    }

    @Test
    fun `refiles only the directive under the named account`() {
        // Beancount allows one document under two accounts, and DocumentIndex lists a row
        // per directive. Without a target, refiling one row moved both -- silently changing
        // a filing the user was not looking at.
        val source = """
            2026-02-01 document Assets:Bank "r.pdf"
            2026-02-01 document Expenses:Food "r.pdf"
        """.trimIndent() + "\n"

        val result = BeancountText.refileDocument(source, "r.pdf", "Assets:Card", from = "Assets:Bank")

        assertEquals(
            """
            2026-02-01 document Assets:Card "r.pdf"
            2026-02-01 document Expenses:Food "r.pdf"
            """.trimIndent() + "\n",
            result,
        )
    }

    @Test
    fun `returns null when no directive for that path sits under the named account`() {
        val source = "2026-02-01 document Assets:Bank \"r.pdf\"\n"

        assertNull(BeancountText.refileDocument(source, "r.pdf", "Assets:Card", from = "Equity:Opening"))
    }
}
