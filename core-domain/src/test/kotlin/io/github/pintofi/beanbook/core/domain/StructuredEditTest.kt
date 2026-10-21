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

    // ---- Entries written by hand ------------------------------------------------------

    /** Aligned to the author's own column and indented with four spaces, as hand-written files are. */
    private val handWritten = """
        2026-01-05 * "Grocer" "Weekly shop"
            Expenses:Food        12.50 USD
            Assets:Cash
    """.trimIndent()

    @Test fun `a hand-aligned entry is editable as fields, and says its spacing will change`() {
        val editable = assertNotNull(StructuredEdit.editableDraftOf(entry, handWritten))

        assertTrue(editable.reformats, "the form must say the layout will be rewritten")
        assertEquals(null, editable.draft.postings.last().amount, "the elided leg stays elided")
    }

    @Test fun `an entry the app wrote itself is not reported as reformatted`() {
        val editable = assertNotNull(
            StructuredEdit.editableDraftOf(entry, StructuredEdit.draftOf(entry).render()),
        )
        assertFalse(editable.reformats)
    }

    @Test fun `thousands separators in the file are layout, not a different figure`() {
        val big = entry.copy(
            postings = listOf(
                JournalPosting("Expenses:Rent", amount("1200.00")),
                JournalPosting("Assets:Cash", amount("-1200.00")),
            ),
        )
        val source = "2026-01-05 * \"Grocer\" \"Weekly shop\"\n  Expenses:Rent  1,200.00 USD\n  Assets:Cash\n"

        assertNotNull(StructuredEdit.editableDraftOf(big, source))
    }

    /** Beancount turns `@@` into a per-unit price; the form keeps what the file says. */
    @Test fun `a total price is kept as the file writes it`() {
        val exchange = entry.copy(
            postings = listOf(
                JournalPosting("Assets:Eur", amount("100", "EUR"), price = amount("1.085")),
                JournalPosting("Assets:Cash", amount("-108.50")),
            ),
        )
        val source = "2026-01-05 * \"Grocer\" \"Weekly shop\"\n  Assets:Eur   100 EUR @@ 108.50 USD\n  Assets:Cash\n"

        val editable = assertNotNull(StructuredEdit.editableDraftOf(exchange, source))
        assertEquals("@@ 108.50 USD", editable.draft.postings.first().details)
        assertTrue(editable.draft.render().contains("100 EUR @@ 108.50 USD"))
    }

    @Test fun `a comment inside a hand-written entry is kept, so the entry opens as fields`() {
        val source = handWritten.replace("    Assets:Cash", "    ; paid in coins\n    Assets:Cash")
        val editable = assertNotNull(StructuredEdit.editableDraftOf(entry, source))
        assertEquals(listOf(" paid in coins"), editable.draft.postings.last().notesBefore)
        // Editing a figure must not cost the entry its comment.
        val changed = editable.draft.copy(
            postings = editable.draft.postings.map { it.copy(amount = Amount(BigDecimal("13.00"), "USD")) },
        )
        assertTrue(changed.render().contains("; paid in coins"))
    }

    private fun sourceEditable(source: String, parsed: JournalEntry = entry) =
        assertNotNull(StructuredEdit.editableDraftOf(parsed, source), "expected the form to open for:\n$source")

    private fun parsedAs(
        payee: String?,
        narration: String?,
        vararg postings: Pair<String, String?>,
        date: String = "2026-01-05",
    ) = JournalEntry(
        date = date, flag = "*", payee = payee, narration = narration,
        tags = emptyList(), links = emptyList(),
        postings = postings.map { (account, figure) ->
            JournalPosting(account, figure?.let { Amount(BigDecimal(it.substringBefore(' ')), it.substringAfter(' ')) })
        },
        file = "main.beancount", line = 1,
    )

    @Test fun `a comment on a posting line and one on the first line are kept`() {
        val source = """
            2026-01-05 * "Grocer" "Weekly shop"  ; the good shop
              Expenses:Food    12.50 USD  ; coffee
              Assets:Cash
        """.trimIndent() + "\n"
        val editable = sourceEditable(source)
        assertEquals(" the good shop", editable.draft.headerComment)
        assertEquals("coffee", editable.draft.postings.first().comment)
        assertTrue(editable.draft.render().contains("; coffee"))
        assertTrue(editable.draft.render().contains("; the good shop"))
    }

    @Test fun `comments before the first posting and after the last are kept in place`() {
        val source = """
            2026-01-05 * "Grocer" "Weekly shop"
              ; about the whole entry
              Expenses:Food    12.50 USD
              Assets:Cash
              ; closing remark
        """.trimIndent() + "\n"
        val rendered = sourceEditable(source).draft.render()
        assertTrue(rendered.indexOf("about the whole entry") < rendered.indexOf("Expenses:Food"))
        assertTrue(rendered.indexOf("closing remark") > rendered.indexOf("Assets:Cash"))
    }

    @Test fun `metadata on a posting is read from the text`() {
        val source = """
            2026-01-05 * "Landlord" "Rent"
              Expenses:Home    50000.00 USD
                period: "2026-08"
              Assets:Cash
        """.trimIndent() + "\n"
        val rent = parsedAs("Landlord", "Rent", "Expenses:Home" to "50000.00 USD", "Assets:Cash" to "-50000.00 USD")
        val editable = sourceEditable(source, rent)
        assertEquals(mapOf("period" to "2026-08"), editable.draft.postings.first().metadata)
        assertTrue(editable.draft.postings.last().metadata.isEmpty())
    }

    @Test fun `a number written bare as metadata stays a number`() {
        val source = """
            2026-01-05 * "Trip" "Days"
              trip-days: 5
              Expenses:Food    12.50 USD
              Assets:Cash
        """.trimIndent() + "\n"
        val trip = parsedAs("Trip", "Days", "Expenses:Food" to "12.50 USD", "Assets:Cash" to "-12.50 USD")
        val editable = sourceEditable(source, trip)
        assertTrue("trip-days" in editable.draft.bareMetadata)
        assertTrue(editable.draft.render().contains("trip-days: 5\n"))
        // A string it could be mistaken for keeps its quotes.
        val quoted = source.replace("trip-days: 5", "trip-days: \"5\"")
        assertTrue(sourceEditable(quoted, trip).draft.render().contains("trip-days: \"5\""))
    }

    @Test fun `a posting Beancount split across currencies opens as one elided posting`() {
        // The elided leg of an entry with two currencies is booked into two postings, which is why
        // an entry-derived draft cannot reproduce the text; the text itself is unambiguous.
        val source = """
            2026-01-01 * "Opening" "Balances"
              Assets:Cash        5,000.00 PKR
              Assets:Cash          200.00 SAR
              Equity:Opening
        """.trimIndent() + "\n"
        val booked = parsedAs(
            "Opening", "Balances",
            "Assets:Cash" to "5000.00 PKR", "Assets:Cash" to "200.00 SAR",
            "Equity:Opening" to "-5000.00 PKR", "Equity:Opening" to "-200.00 SAR",
            date = "2026-01-01",
        )
        val editable = sourceEditable(source, booked)
        assertEquals(3, editable.draft.postings.size)
        assertTrue(editable.draft.postings.last().isElided)
    }

    @Test fun `a text that is not the entry Beancount parsed is not edited as fields`() {
        val source = "2026-01-05 * \"Grocer\" \"Weekly shop\"\n  Expenses:Food    99.00 USD\n  Assets:Cash\n"
        assertEquals(null, StructuredEdit.editableDraftOf(entry, source))
    }

    @Test fun `shapes the draft cannot hold still go to the text editor`() {
        listOf(
            // a flag on a posting
            "2026-01-05 * \"G\" \"W\"\n  ! Expenses:Food    12.50 USD\n  Assets:Cash\n",
            // an amount that is an expression
            "2026-01-05 * \"G\" \"W\"\n  Expenses:Food    (10 + 2.50) USD\n  Assets:Cash\n",
            // the old `txn` keyword
            "2026-01-05 txn \"G\" \"W\"\n  Expenses:Food    12.50 USD\n  Assets:Cash\n",
            // a line that is neither a posting, metadata nor a comment
            "2026-01-05 * \"G\" \"W\"\n  Expenses:Food    12.50 USD\n  ^stray\n  Assets:Cash\n",
            // a comment between a posting and its own metadata has no place in a draft
            "2026-01-05 * \"G\" \"W\"\n  Expenses:Food    12.50 USD\n  ; odd\n    note: \"x\"\n  Assets:Cash\n",
        ).forEach { source ->
            assertEquals(null, StructuredEdit.editableDraftOf(entry, source), "opened as fields: $source")
        }
    }

    @Test fun `a different figure is not mistaken for different spacing`() {
        val source = handWritten.replace("12.50", "12.60")
        assertEquals(null, StructuredEdit.editableDraftOf(entry, source))
    }

    /** `fava.plugins.link_documents` links every entry with a document; the file never says so. */
    @Test fun `a link a plugin added does not send the entry to the text editor`() {
        val linked = entry.copy(
            links = listOf("dok-2026-09-13"),
            metadata = mapOf("document" to "2026-09-13.f64293477180.pdf"),
        )
        val source = "2026-01-05 * \"Grocer\" \"Weekly shop\"\n" +
            "  document: \"2026-09-13.f64293477180.pdf\"\n" +
            "  Expenses:Food        12.50 USD\n  Assets:Cash\n"

        val editable = assertNotNull(StructuredEdit.editableDraftOf(linked, source))
        assertEquals(emptyList(), editable.draft.links, "the plugin's link must not be written into the file")
    }

    @Test fun `a link the file does write is kept`() {
        val linked = entry.copy(links = listOf("invoice-4411"))
        val source = StructuredEdit.draftOf(linked).render()

        val editable = assertNotNull(StructuredEdit.editableDraftOf(linked, source))
        assertEquals(listOf("invoice-4411"), editable.draft.links)
        assertFalse(editable.reformats)
    }

    @Test fun `a narration with an escaped quote is still compared by its content`() {
        val quoted = entry.copy(narration = "the \"big\"  shop")
        // Same words, different spacing between the postings: only layout differs.
        val source = StructuredEdit.draftOf(quoted).render().lines()
            .joinToString("\n") { if (it.startsWith("  ")) "  $it" else it }

        val editable = assertNotNull(StructuredEdit.editableDraftOf(quoted, source))
        assertTrue(editable.reformats)
        assertEquals("the \"big\"  shop", editable.draft.narration)
    }
}

class StructuredEditOpeningTest {
    @Test fun `the opening entry of a two currency ledger opens as fields`() {
        val source = """
            2026-08-01 * "Opening" "Opening balances"
              Assets:Current:Cash                 5,000.00 PKR
              Assets:Current:Cash                   200.00 SAR
              Assets:Current:Bank:Meezan        250,000.00 PKR
              Equity:Opening-Balances
        """.trimIndent() + "\n"
        fun p(a: String, n: String, c: String) = JournalPosting(a, Amount(BigDecimal(n), c))
        val entry = JournalEntry(
            date = "2026-08-01", flag = "*", payee = "Opening", narration = "Opening balances",
            tags = emptyList(), links = emptyList(),
            postings = listOf(
                // In the order Beancount reports them: grouped by currency, not as written.
                p("Assets:Current:Cash", "5000.00", "PKR"), p("Assets:Current:Bank:Meezan", "250000.00", "PKR"),
                p("Equity:Opening-Balances", "-255000.00", "PKR"),
                p("Assets:Current:Cash", "200.00", "SAR"), p("Equity:Opening-Balances", "-200.00", "SAR"),
            ),
            file = "f", line = 1,
        )
        assertNotNull(StructuredEdit.draftFromSource(source), "parse")
        assertNotNull(StructuredEdit.editableDraftOf(entry, source))
    }
}
