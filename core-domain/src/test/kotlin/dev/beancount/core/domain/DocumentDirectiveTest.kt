package dev.beancount.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Removing one `document` directive without touching anything else (R2).
 *
 * The first version of this filtered lines with `line.contains(path) && line.contains("document")`
 * and rebuilt the file with `readLines().joinToString("\n")`. Two defects came out of that: a
 * ledger with CRLF endings — routine for a file synced from a Windows desktop — was silently
 * rewritten to LF across every untouched line, which invariant 4 forbids; and the predicate
 * matched a transaction's own `document:` metadata, a comment mentioning the file, and every
 * occurrence rather than one.
 */
class DocumentDirectiveTest {

    @Test
    fun `removes the directive for that path`() {
        val source = """
            2026-01-01 open Assets:Bank
            2026-02-01 document Assets:Bank "documents/receipt.pdf"
            2026-03-01 open Expenses:Food
        """.trimIndent() + "\n"

        val result = BeancountText.removeDocumentDirective(source, "documents/receipt.pdf")

        assertTrue(result != null && !result.contains("receipt.pdf"), "the directive survived")
        assertTrue(result!!.contains("open Assets:Bank"), "an unrelated line was removed")
        assertTrue(result.contains("open Expenses:Food"), "an unrelated line was removed")
    }

    @Test
    fun `leaves CRLF endings on every untouched line`() {
        val source = "2026-01-01 open Assets:Bank\r\n" +
            "2026-02-01 document Assets:Bank \"documents/receipt.pdf\"\r\n" +
            "2026-03-01 open Expenses:Food\r\n"

        val result = BeancountText.removeDocumentDirective(source, "documents/receipt.pdf")

        assertEquals(
            "2026-01-01 open Assets:Bank\r\n2026-03-01 open Expenses:Food\r\n",
            result,
            "line endings outside the removed directive were rewritten",
        )
    }

    @Test
    fun `does not touch a transaction's document metadata`() {
        val source = "2026-02-01 * \"Shop\"\n" +
            "  document: \"documents/receipt.pdf\"\n" +
            "  Expenses:Food   12.00 USD\n" +
            "  Assets:Bank\n"

        val result = BeancountText.removeDocumentDirective(source, "documents/receipt.pdf")

        assertNull(result, "a metadata line was mistaken for a document directive")
    }

    @Test
    fun `does not touch a comment mentioning the file`() {
        val source = "; the document documents/receipt.pdf is filed elsewhere\n" +
            "2026-01-01 open Assets:Bank\n"

        assertNull(
            BeancountText.removeDocumentDirective(source, "documents/receipt.pdf"),
            "a comment was mistaken for a document directive",
        )
    }

    @Test
    fun `takes the directive's own metadata with it`() {
        val source = "2026-02-01 document Assets:Bank \"documents/receipt.pdf\"\n" +
            "  note: \"scanned\"\n" +
            "2026-03-01 open Expenses:Food\n"

        val result = BeancountText.removeDocumentDirective(source, "documents/receipt.pdf")

        assertEquals(
            "2026-03-01 open Expenses:Food\n",
            result,
            "the directive's indented metadata was orphaned, which would not parse",
        )
    }

    @Test
    fun `returns null when nothing links to the path`() {
        val source = "2026-01-01 open Assets:Bank\n"
        assertNull(BeancountText.removeDocumentDirective(source, "documents/other.pdf"))
    }

    @Test
    fun `does not match a different file whose name contains the target`() {
        val source = "2026-02-01 document Assets:Bank \"documents/receipt.pdf.bak\"\n"
        assertNull(
            BeancountText.removeDocumentDirective(source, "documents/receipt.pdf"),
            "a longer path was matched by substring",
        )
    }
}
