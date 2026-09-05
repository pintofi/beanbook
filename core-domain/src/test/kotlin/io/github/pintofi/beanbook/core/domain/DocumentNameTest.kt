package io.github.pintofi.beanbook.core.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentNameTest {

    private val date = LocalDate.parse("2026-08-24")
    private val receipt = "a receipt".toByteArray()

    @Test
    fun `a document is filed in Beancount's own documents layout`() {
        val name = DocumentName.forContent("Expenses:Food:Groceries", date, receipt)

        assertTrue(
            name.relativePath.startsWith("documents/Expenses/Food/Groceries/2026-08-24."),
            name.relativePath,
        )
        assertTrue(name.relativePath.endsWith(".pdf"), name.relativePath)
    }

    @Test
    fun `the same capture saved twice gets the same name, so a retry cannot duplicate it`() {
        val first = DocumentName.forContent("Assets:Bank:Checking", date, receipt)
        val second = DocumentName.forContent("Assets:Bank:Checking", date, receipt.copyOf())

        assertEquals(first.relativePath, second.relativePath)
    }

    @Test
    fun `different content on the same day for the same account gets a different name`() {
        val first = DocumentName.forContent("Assets:Bank:Checking", date, "one".toByteArray())
        val second = DocumentName.forContent("Assets:Bank:Checking", date, "two".toByteArray())

        assertNotEquals(first.relativePath, second.relativePath)
    }

    @Test
    fun `a hash collision is resolved by an ordinal rather than by overwriting`() {
        val name = DocumentName.forContent("Assets:Cash", date, receipt)

        val next = name.next()

        assertNotEquals(name.relativePath, next.relativePath)
        assertTrue(next.relativePath.contains("-1."), next.relativePath)
        assertEquals(next.relativePath, name.next().relativePath, "the ordinal is deterministic")
    }

    @Test
    fun `an account that is not an account is refused, because it would become a path`() {
        listOf(
            "../../etc",
            "Expenses:../secrets",
            "/absolute",
            "expenses:food",
            "",
        ).forEach { hostile ->
            assertFailsWith<IllegalArgumentException>("accepted \"$hostile\"") {
                DocumentName.forContent(hostile, date, receipt)
            }
        }
    }

    @Test
    fun `an extension that could escape or hide is refused`() {
        listOf("../pdf", "pd/f", "PDF", "", "pdf.exe").forEach { hostile ->
            assertFailsWith<IllegalArgumentException>("accepted \"$hostile\"") {
                DocumentName.forContent("Assets:Cash", date, receipt, hostile)
            }
        }
    }

    @Test
    fun `every path stays inside the documents directory`() {
        val name = DocumentName.forContent("Liabilities:Card:Visa", date, receipt)

        val segments = name.relativePath.split('/')
        assertEquals("documents", segments.first())
        assertTrue(segments.none { it == ".." || it == "." || it.isEmpty() }, name.relativePath)
    }

    @Test
    fun `the hash is stable, and short enough to read out over the phone`() {
        assertEquals(DocumentName.hash(receipt), DocumentName.hash(receipt))
        assertEquals(DocumentName.HASH_LENGTH, DocumentName.hash(receipt).length)
        assertTrue(DocumentName.hash(receipt).all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `unlink and delete say different things, and both name the file`() {
        val path = "documents/Assets/Cash/2026-08-24.abc.pdf"

        val unlink = DocumentRemoval.UNLINK.describe(path)
        val delete = DocumentRemoval.DELETE_FILE.describe(path)

        assertTrue(unlink.contains("stays"), unlink)
        assertTrue(delete.contains("Delete"), delete)
        assertNotEquals(unlink, delete)
        listOf(unlink, delete).forEach { assertTrue(it.contains(path), it) }
    }
}
