package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A field editor is offered only for entries it can describe completely.
 *
 * The cases that matter are the negative ones. A check that says "yes" for everything is the
 * same as not checking, and the cost of a wrong "yes" is a user's comment or an unmodelled
 * line deleted by an edit they thought was safe — invariant 4, release-blocking.
 */
class StructuredEditTest {

    private fun amount(value: String, currency: String = "USD") =
        Amount(BigDecimal(value), currency)

    private val entry = JournalEntry(
        date = "2026-01-05",
        flag = "*",
        payee = "Grocer",
        narration = "Weekly shop",
        tags = emptyList(),
        links = emptyList(),
        postings = listOf(
            JournalPosting("Expenses:Food", amount("12.50")),
            JournalPosting("Assets:Cash", amount("-12.50")),
        ),
        file = "main.beancount",
        line = 10,
    )

    @Test fun `an entry the draft describes exactly is editable as fields`() {
        val source = StructuredEdit.draftOf(entry).render()
        assertTrue(StructuredEdit.isLossless(entry, source))
    }

    @Test fun `trailing whitespace and surrounding blank lines do not disqualify it`() {
        val source = "\n" + StructuredEdit.draftOf(entry).render()
            .lines().joinToString("\n") { "$it   " } + "\n\n"
        assertTrue(StructuredEdit.isLossless(entry, source))
    }

    @Test fun `a comment inside the entry disqualifies it`() {
        val source = StructuredEdit.draftOf(entry).render()
            .replace("  Assets:Cash", "  ; paid in coins\n  Assets:Cash")
        assertFalse(
            StructuredEdit.isLossless(entry, source),
            "a comment the draft cannot hold was treated as losslessly editable",
        )
    }

    @Test fun `a line the draft does not model disqualifies it`() {
        val source = StructuredEdit.draftOf(entry).render() + "  ^some-link-the-app-missed\n"
        assertFalse(StructuredEdit.isLossless(entry, source))
    }

    @Test fun `alignment the user chose disqualifies it`() {
        val source = StructuredEdit.draftOf(entry).render().replace("Expenses:Food", "Expenses:Food     ")
        assertFalse(
            StructuredEdit.isLossless(entry, source),
            "the user's own alignment inside a line was treated as noise",
        )
    }

    @Test fun `an elided posting is recognised and stays elided`() {
        // What the Add form writes: the second leg has no amount, and Beancount computes it.
        val source = "2026-01-05 * \"Grocer\" \"Weekly shop\"\n" +
            StructuredEdit.draftOf(entry).render().lines()[1] + "\n" +
            "  Assets:Cash\n"
        val draft = StructuredEdit.losslessDraftOf(entry, source)
        assertNotNull(draft, "an entry written with an elided posting was refused a field editor")
        assertTrue(draft.postings[1].isElided, "the elided leg came back with an amount in it")
        assertEquals(normalised(source), normalised(draft.render()))
    }

    @Test fun `an elided posting does not excuse anything else in the entry`() {
        val source = "2026-01-05 * \"Grocer\" \"Weekly shop\"\n" +
            StructuredEdit.draftOf(entry).render().lines()[1] + "\n" +
            "  ; paid in coins\n" +
            "  Assets:Cash\n"
        assertFalse(
            StructuredEdit.isLossless(entry, source),
            "eliding a posting was allowed to hide a comment the draft cannot hold",
        )
    }

    private fun normalised(text: String) =
        text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")
}
