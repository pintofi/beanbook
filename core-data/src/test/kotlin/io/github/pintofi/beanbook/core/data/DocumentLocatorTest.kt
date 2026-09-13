package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every way a ledger names a document leads to the file (B79). */
class DocumentLocatorTest {

    private val root: File = Files.createTempDirectory("locate").toFile()
    private val project = File(root, "common").also {
        File("../fixtures/common-ledger").copyRecursively(it)
    }
    private val receipt = File(project, "documents/Expenses/Food/Groceries/2026-09-01.receipt.abc123.pdf")
        .apply { parentFile.mkdirs(); writeText("%PDF-1.4") }

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    @Test
    fun `a bare file name, as the entry form writes it, is found among the known documents`() {
        val found = DocumentLocator.locate(
            project,
            "2026-09-01.receipt.abc123.pdf",
            entryFile = File(project, "files/transactions.beancount").path,
            known = listOf("documents/Expenses/Food/Groceries/2026-09-01.receipt.abc123.pdf"),
        )

        assertEquals(receipt.canonicalFile, found)
    }

    @Test
    fun `a path relative to the project root`() {
        assertEquals(
            receipt.canonicalFile,
            DocumentLocator.locate(project, "documents/Expenses/Food/Groceries/${receipt.name}"),
        )
    }

    @Test
    fun `a path relative to the file the entry is written in`() {
        assertEquals(
            receipt.canonicalFile,
            DocumentLocator.locate(
                project,
                "../documents/Expenses/Food/Groceries/${receipt.name}",
                entryFile = File(project, "files/transactions.beancount").path,
            ),
        )
    }

    @Test
    fun `an absolute directive path inside the project`() {
        assertEquals(receipt.canonicalFile, DocumentLocator.locate(project, receipt.absolutePath))
    }

    @Test
    fun `nothing outside the project, however it is named`() {
        val outside = File(root, "secret.pdf").apply { writeText("no") }

        assertNull(DocumentLocator.locate(project, outside.absolutePath))
        assertNull(DocumentLocator.locate(project, "../secret.pdf"))
        assertNull(DocumentLocator.locate(project, "secret.pdf", known = listOf(outside.absolutePath)))
    }

    @Test
    fun `a reference to a file that is not there is null, not a guess`() {
        assertNull(DocumentLocator.locate(project, "2026-01-01.missing.pdf"))
    }
}
