package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Cost
import io.github.pintofi.beanbook.core.domain.PostingDraft
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PendingSubmissionStoreTest {

    private val dir: File = Files.createTempDirectory("pending").toFile()
    private val file = File(dir, "state/remote-pending")
    private val store = PendingSubmissionStore(file)

    @AfterTest
    fun cleanUp() = dir.deleteRecursively().let { }

    private val simple = TransactionDraft(
        date = "2026-08-25",
        payee = "Grocer",
        narration = "Weekly shop",
        postings = listOf(
            PostingDraft("Expenses:Food", Amount(BigDecimal("42.30"), "USD")),
            PostingDraft("Assets:Bank"),
        ),
    )

    @Test
    fun `nothing saved means nothing pending`() {
        assertNull(store.load())
    }

    @Test
    fun `an entry and its key come back as they were, after the process is gone`() {
        store.save(PendingSubmission(simple, "key-1"))

        // A fresh store on the same file stands in for the next start of the app.
        assertEquals(PendingSubmission(simple, "key-1"), PendingSubmissionStore(file).load())
    }

    @Test
    fun `everything an entry can carry survives, including text that needs escaping`() {
        val full = TransactionDraft(
            date = "2026-09-01",
            flag = "!",
            payee = "O'Neil \"The\" Baker\\Co",
            narration = "line one\nline two\ttabbed — é 日本",
            tags = listOf("trip", "2026"),
            links = listOf("invoice-9"),
            metadata = mapOf("receipt" to "a b", "note" to "x"),
            postings = listOf(
                PostingDraft(
                    account = "Assets:Broker",
                    amount = Amount(BigDecimal("10.000000001"), "VTI"),
                    cost = Cost(Amount(BigDecimal("212.5"), "USD"), date = "2026-09-01", label = "lot 1"),
                    price = Amount(BigDecimal("213"), "USD"),
                    comment = "bought",
                    metadata = mapOf("broker" to "x"),
                    details = "{212.5 USD}",
                ),
                PostingDraft("Assets:Cash", Amount(BigDecimal("-2125.00000001"), "USD")),
                PostingDraft("Expenses:Fees", null, comment = "elided"),
            ),
        )
        store.save(PendingSubmission(full, "key-2"))

        assertEquals(PendingSubmission(full, "key-2"), store.load())
    }

    @Test
    fun `saving again replaces the file, and clearing removes it`() {
        store.save(PendingSubmission(simple, "key-1"))
        store.save(PendingSubmission(simple.copy(narration = "Other"), "key-2"))
        assertEquals("key-2", store.load()?.key)

        store.clear()

        assertNull(store.load())
        assertFalse(file.exists())
        assertEquals(emptyList(), file.parentFile.listFiles()?.toList().orEmpty(), "no temporary file left")
    }

    @Test
    fun `an empty, torn or foreign file means nothing is pending, never a crash`() {
        file.parentFile.mkdirs()
        val unusable = listOf(
            "", "{", "[]", "not json",
            """{"version":99,"key":"k","draft":{}}""",
            """{"version":1,"key":"","draft":{}}""",
        )
        for (text in unusable) {
            file.writeText(text)
            assertNull(store.load(), "for: $text")
        }
    }
}
