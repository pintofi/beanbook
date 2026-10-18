package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A document is linked in one of two ways, and the app knew about one of them.
 *
 * A `document` directive files a file under an account. A `document:` metadata key attaches
 * it to a transaction — which is what the Add form writes, and what
 * `fava.plugins.link_documents` reads. Unlinking removed only the directive, so a receipt
 * attached while entering a transaction stayed attached however often it was unlinked, and
 * Organize went on saying "not linked to any account yet" about a file an entry pointed at.
 */
class DocumentAttachmentTest {

    private val entry = """
        2026-09-07 * "Grocer" "weekly shop"
          document: "documents/Expenses/Food/2026-09-07.abc123.pdf"
          Expenses:Food   12.00 USD
          Assets:Cash
    """.trimIndent()

    @Test fun `a metadata link is removed by path`() {
        val out = BeancountText.removeDocumentMetadata(
            entry,
            "documents/Expenses/Food/2026-09-07.abc123.pdf",
        )
        assertTrue(out != null, "nothing was removed")
        assertTrue(!out!!.contains("document:"), "the metadata line survived: $out")
        assertTrue(out.contains("Expenses:Food"), "the entry itself was damaged: $out")
    }

    @Test fun `numbered keys are removed too`() {
        val two = entry.replace("document:", "document-2:")
        val out = BeancountText.removeDocumentMetadata(
            two,
            "documents/Expenses/Food/2026-09-07.abc123.pdf",
        )
        assertTrue(out != null && !out.contains("document-2:"))
    }

    @Test fun `a line that merely mentions the file is left alone`() {
        val commented = entry.replace(
            "  document: ",
            "  ; keep the receipt documents/Expenses/Food/2026-09-07.abc123.pdf\n  document: ",
        )
        val out = BeancountText.removeDocumentMetadata(
            commented,
            "documents/Expenses/Food/2026-09-07.abc123.pdf",
        )
        assertTrue(out!!.contains("; keep the receipt"), "a comment was deleted: $out")
    }

    @Test fun `nothing to remove is reported rather than silently rewritten`() {
        assertNull(BeancountText.removeDocumentMetadata(entry, "documents/other.pdf"))
    }

    @Test fun `an attached document is not called unlinked`() {
        val rows = DocumentIndex.reconcile(
            root = "/project",
            onDisk = listOf("documents/receipt.pdf"),
            directives = emptyList(),
            attachments = mapOf("receipt.pdf" to listOf("2026-09-07 Grocer · weekly shop")),
        )
        assertEquals(1, rows.size)
        assertEquals(DocumentStatus.UNFILED, rows.single().status)
        assertTrue(
            rows.single().summary.startsWith("Attached to"),
            "the row still claims nothing links to it: ${rows.single().summary}",
        )
    }

    @Test fun `a file nothing refers to still says so`() {
        val rows = DocumentIndex.reconcile(
            root = "/project",
            onDisk = listOf("documents/stray.pdf"),
            directives = emptyList(),
        )
        assertEquals("Not linked to any account yet", rows.single().summary)
    }

    private val bare = """
        2026-09-07 * "Grocer" "weekly shop"
          document: "invoice.pdf"
          Expenses:Food   12.00 USD
          Assets:Cash
    """.trimIndent()

    @Test fun `a bare name is removed when it is the only document with that name`() {
        val out = BeancountText.removeDocumentMetadata(
            bare, "documents/a/invoice.pdf", bareNameIsUnique = true,
        )
        assertTrue(out != null && !out.contains("document:"), "the link survived: $out")
    }

    @Test fun `a bare name is left alone when another document has the same name`() {
        assertNull(
            BeancountText.removeDocumentMetadata(
                bare, "documents/a/invoice.pdf", bareNameIsUnique = false,
            ),
        )
        assertTrue(BeancountText.namesDocumentByBareName(bare, "invoice.pdf"))
    }

    @Test fun `a link to the same file name in another folder is not removed`() {
        val other = bare.replace("\"invoice.pdf\"", "\"documents/b/invoice.pdf\"")
        assertNull(
            BeancountText.removeDocumentMetadata(
                other, "documents/a/invoice.pdf", bareNameIsUnique = true,
            ),
        )
        assertTrue(!BeancountText.namesDocumentByBareName(other, "invoice.pdf"))
    }
}
