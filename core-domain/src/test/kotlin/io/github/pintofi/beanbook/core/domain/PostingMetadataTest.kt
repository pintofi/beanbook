package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Metadata on a single posting, not only on the transaction.
 *
 * Beancount allows both, and they mean different things: a key on the transaction describes
 * the whole event, a key on a posting describes that leg of it. The draft could express the
 * first and not the second, so anything per-leg — an invoice number against one account, a
 * document linked to one side of a transfer — could not be entered through the app at all.
 *
 * Indentation is the part that has to be right. Posting metadata is indented deeper than the
 * posting it belongs to; at the same depth Beancount reads it as belonging to the transaction,
 * which is a different statement about somebody's books.
 */
class PostingMetadataTest {

    private fun amount(value: String) = Amount(BigDecimal(value), "USD")

    private fun draftWith(vararg meta: Pair<String, String>) = TransactionDraft(
        date = "2026-02-01",
        payee = "Shop",
        narration = "coffee",
        postings = listOf(
            PostingDraft("Expenses:Food", amount("12.00"), metadata = meta.toMap()),
            PostingDraft("Assets:Bank:Checking"),
        ),
    )

    @Test
    fun `posting metadata is indented deeper than the posting`() {
        val rendered = draftWith("invoice" to "A-1").render()

        val lines = rendered.lines()
        val posting = lines.indexOfFirst { it.contains("Expenses:Food") }
        val meta = lines.indexOfFirst { it.contains("invoice:") }

        assertTrue(meta > posting, "metadata must follow its posting:\n$rendered")
        val postingIndent = lines[posting].takeWhile { it == ' ' }.length
        val metaIndent = lines[meta].takeWhile { it == ' ' }.length
        assertTrue(
            metaIndent > postingIndent,
            "metadata at depth $metaIndent under a posting at $postingIndent reads as the " +
                "transaction's:\n$rendered",
        )
    }

    @Test
    fun `a metadata value is quoted like any other string`() {
        val rendered = draftWith("note" to """he said "hi"""").render()

        assertTrue(
            rendered.contains("""note: "he said \"hi\""""),
            "an unescaped quote would not parse:\n$rendered",
        )
    }

    @Test
    fun `a posting with no metadata renders exactly as before`() {
        val plain = TransactionDraft(
            date = "2026-02-01",
            payee = "Shop",
            postings = listOf(
                PostingDraft("Expenses:Food", amount("12.00")),
                PostingDraft("Assets:Bank:Checking"),
            ),
        )

        assertTrue(
            plain.render().lines().none { it.trim().startsWith(":") },
            "an empty metadata map emitted something:\n${plain.render()}",
        )
    }

    @Test
    fun `an invalid metadata key is refused rather than written`() {
        // Keys are lower-case and may not start with a digit; "Invoice" would not parse.
        val problems = draftWith("Invoice" to "A-1").validate()

        assertTrue(
            problems.any { it is DraftProblem.InvalidMetadataKey },
            "an unparseable key was accepted: $problems",
        )
    }

    @Test
    fun `a valid key passes validation`() {
        assertEquals(emptyList(), draftWith("invoice-no" to "A-1").validate())
    }
}
