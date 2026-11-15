package io.github.pintofi.beanbook.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentIdsTest {

    @Test
    fun `a file below the folder is its id without the folder's`() {
        assertEquals(
            listOf("books", "hmw"),
            DocumentIds.below("primary:Documents/ledger", "primary:Documents/ledger/books/hmw"),
        )
        assertEquals(listOf("hmw"), DocumentIds.below("primary:Documents/ledger", "primary:Documents/ledger/hmw"))
    }

    @Test
    fun `a folder that is a whole volume works too`() {
        assertEquals(listOf("a", "b"), DocumentIds.below("primary:", "primary:a/b"))
    }

    @Test
    fun `a file that is not below the folder, or ids that are not paths, say nothing`() {
        assertNull(DocumentIds.below("primary:Documents/ledger", "primary:Documents/other/hmw"))
        assertNull(DocumentIds.below("primary:Documents/ledger", "primary:Documents/ledger2/hmw"))
        assertNull(DocumentIds.below("1234", "5678"))
        assertNull(DocumentIds.below("primary:Documents/ledger", "primary:Documents/ledger"))
    }
}
