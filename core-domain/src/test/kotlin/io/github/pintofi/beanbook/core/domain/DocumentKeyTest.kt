package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Naming the metadata key that links a document to a transaction.
 *
 * `fava.plugins.link_documents` reads `document` and any `document-*` key, which
 * [JournalEntry.linkedDocuments] mirrors. Attaching a second receipt to one transaction
 * therefore cannot reuse `document`: a map has one value per key, so the first link would be
 * silently replaced by the second.
 */
class DocumentKeyTest {

    @Test
    fun `the first document uses the plain key`() {
        assertEquals("document", BeancountText.nextDocumentKey(emptySet()))
    }

    @Test
    fun `a second document is numbered rather than overwriting the first`() {
        assertEquals("document-2", BeancountText.nextDocumentKey(setOf("document")))
    }

    @Test
    fun `numbering continues past the keys already there`() {
        assertEquals(
            "document-4",
            BeancountText.nextDocumentKey(setOf("document", "document-2", "document-3")),
        )
    }

    @Test
    fun `a gap in the numbering is filled rather than skipped`() {
        assertEquals("document-3", BeancountText.nextDocumentKey(setOf("document", "document-2", "document-4")))
    }

    @Test
    fun `unrelated keys are ignored`() {
        assertEquals("document", BeancountText.nextDocumentKey(setOf("invoice", "documented-by")))
    }

    @Test
    fun `every key it produces is one Beancount accepts`() {
        var keys = emptySet<String>()
        repeat(5) {
            val next = BeancountText.nextDocumentKey(keys)
            assertEquals(true, BeancountText.META_KEY.matches(next), "not a valid key: $next")
            keys = keys + next
        }
    }
}
