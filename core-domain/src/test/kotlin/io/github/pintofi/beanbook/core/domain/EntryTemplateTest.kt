package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryTemplateTest {

    private val rent = EntryTemplate(
        name = "Rent",
        currency = "EUR",
        payee = "Landlord",
        narration = "Monthly rent",
        postings = listOf(
            TemplatePosting("Expenses:Rent", BigDecimal("750.00")),
            TemplatePosting("Assets:Bank:Giro"),
        ),
    )

    @Test
    fun `applying a template writes the date it was applied to, not one of its own`() {
        val draft = rent.apply(LocalDate.of(2026, 9, 1))

        assertEquals("2026-09-01", draft.date)
        assertEquals(
            """
            2026-09-01 * "Landlord" "Monthly rent"
              Expenses:Rent                                    750.00 EUR
              Assets:Bank:Giro
            """.trimIndent() + "\n",
            draft.render(),
        )
    }

    @Test
    fun `a posting saved without an amount stays elided`() {
        val draft = rent.apply(LocalDate.of(2026, 9, 1))

        assertTrue(draft.postings.last().isElided, "the template must not invent a counter-amount")
        assertEquals(emptyList(), draft.validate())
    }

    @Test
    fun `a template with no name is refused`() {
        val problems = rent.copy(name = "  ").validate()

        assertTrue(problems.any { it is DraftProblem.MissingText }, "problems: $problems")
    }

    @Test
    fun `a template that could never produce a valid entry is refused when saved`() {
        // Two elided postings: Beancount can infer one missing amount, not two. Caught now
        // rather than on the day someone applies it.
        val broken = rent.copy(
            postings = listOf(
                TemplatePosting("Expenses:Rent"),
                TemplatePosting("Assets:Bank:Giro"),
            ),
        )

        assertTrue(
            broken.validate().any { it is DraftProblem.TooManyElidedPostings },
            "problems: ${broken.validate()}",
        )
    }

    @Test
    fun `an invalid account is refused at save time, naming the account`() {
        val problems = rent.copy(
            postings = listOf(TemplatePosting("expenses:rent"), TemplatePosting("Assets:Bank:Giro")),
        ).validate()

        assertEquals(
            "\"expenses:rent\" is not a valid account name",
            problems.filterIsInstance<DraftProblem.InvalidAccount>().single().describe(),
        )
    }

    @Test
    fun `an invalid currency is refused`() {
        assertTrue(rent.copy(currency = "eur").validate().any { it is DraftProblem.InvalidCurrency })
    }

    @Test
    fun `capturing the form keeps the currency of the amounts it captured`() {
        val draft = TransactionDraft(
            date = "2026-09-01",
            payee = "Landlord",
            postings = listOf(
                PostingDraft("Expenses:Rent", Amount(BigDecimal("750.00"), "EUR")),
                PostingDraft("Assets:Bank:Giro"),
            ),
        )

        val template = EntryTemplate.from("Rent", draft, fallbackCurrency = "USD")

        assertEquals("EUR", template.currency)
        assertEquals(BigDecimal("750.00"), template.postings.first().amount)
        assertEquals(null, template.postings.last().amount)
    }

    @Test
    fun `capturing a form with no amounts at all falls back to the form's currency`() {
        val draft = TransactionDraft(
            date = "2026-09-01",
            postings = listOf(
                PostingDraft("Expenses:Rent"),
                PostingDraft("Assets:Bank:Giro"),
            ),
        )

        assertEquals("GBP", EntryTemplate.from("Rent", draft, fallbackCurrency = "GBP").currency)
    }

    @Test
    fun `a captured template round-trips back to the same text on the same date`() {
        val draft = TransactionDraft(
            date = "2026-09-01",
            flag = "!",
            payee = "Land\"lord",
            narration = "Monthly rent",
            tags = listOf("home"),
            links = listOf("lease-2026"),
            postings = listOf(
                PostingDraft("Expenses:Rent", Amount(BigDecimal("750.00"), "EUR")),
                PostingDraft("Assets:Bank:Giro"),
            ),
        )

        val applied = EntryTemplate.from("Rent", draft, "EUR").apply(LocalDate.of(2026, 9, 1))

        assertEquals(draft.render(), applied.render())
    }
}
