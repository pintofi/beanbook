package dev.beancount.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recognising a receipt attached to a transaction (B17).
 *
 * `fava.plugins.link_documents` records the link as a `document` metadata key on the entry,
 * so that is what the app looks for. It is a convention rather than a Beancount language
 * feature, which is exactly why it belongs in one tested place instead of a string literal
 * in a composable.
 */
class LinkedDocumentsTest {

    private fun entry(vararg meta: Pair<String, String>) = JournalEntry(
        date = "2026-02-01", flag = "*", payee = "Shop", narration = "coffee",
        tags = emptyList(), links = emptyList(), postings = emptyList(),
        file = "main.beancount", line = 10, metadata = meta.toMap(),
    )

    @Test
    fun `a document key is a linked document`() {
        assertEquals(
            listOf("documents/receipt.pdf"),
            entry("document" to "documents/receipt.pdf").linkedDocuments,
        )
    }

    @Test
    fun `several documents are all found`() {
        val found = entry(
            "document" to "a.pdf",
            "document-2" to "b.pdf",
        ).linkedDocuments

        assertEquals(setOf("a.pdf", "b.pdf"), found.toSet())
    }

    @Test
    fun `unrelated metadata is not mistaken for a document`() {
        assertTrue(
            entry("invoice" to "A-1", "documented-by" to "someone").linkedDocuments.isEmpty(),
            "a key that merely starts with the same letters is not a document link",
        )
    }

    @Test
    fun `an entry with no metadata links nothing`() {
        assertTrue(entry().linkedDocuments.isEmpty())
    }

    @Test
    fun `a blank value is not offered as a document`() {
        assertTrue(
            entry("document" to "  ").linkedDocuments.isEmpty(),
            "an empty path would open nothing and say it could",
        )
    }
}
