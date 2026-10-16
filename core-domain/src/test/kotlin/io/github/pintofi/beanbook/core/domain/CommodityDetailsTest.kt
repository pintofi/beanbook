package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The part of a posting line after the units, typed as the file writes it (B77).
 *
 * The form used to take a cost and a price as four boxes, which could not say a total cost, a
 * total price, an acquisition date, a lot label, or an empty `{}`. It takes the text now, and
 * these hold the two things that matter: the text lands in the file untouched, and the balance
 * shown while typing is the balance Beancount will compute.
 */
class CommodityDetailsTest {

    private fun amount(value: String, commodity: String) = Amount(BigDecimal(value), commodity)

    @Test fun `a per-unit cost weighs the units at that cost`() {
        val details = CommodityDetails.parse("{12.50 USD}")

        assertEquals(amount("125.00", "USD"), details.weight(amount("10", "ACME")))
    }

    @Test fun `a total cost is the weight, whatever the number of units`() {
        val details = CommodityDetails.parse("{{125.00 USD}}")

        assertEquals(amount("125.00", "USD"), details.weight(amount("10", "ACME")))
        assertEquals(amount("-125.00", "USD"), details.weight(amount("-10", "ACME")))
    }

    @Test fun `a date and a label in the lot are read past`() {
        val details = CommodityDetails.parse("{400.00 PKR, 2025-01-01, \"avco, assf\"}")

        assertEquals(amount("4000.00", "PKR"), details.weight(amount("10", "ASSF")))
    }

    @Test fun `cost wins over price, as it does in Beancount`() {
        val details = CommodityDetails.parse("{12.50 USD} @ 13.00 USD")

        assertEquals(amount("125.00", "USD"), details.weight(amount("10", "ACME")).let {
            Amount(it!!.number.setScale(2), it.currency)
        })
    }

    @Test fun `a price alone weighs by price, per unit or total`() {
        assertEquals(amount("1300", "USD"), CommodityDetails.parse("@ 130 USD").weight(amount("10", "EUR")))
        assertEquals(amount("130", "USD"), CommodityDetails.parse("@@ 130 USD").weight(amount("10", "EUR")))
    }

    /** An empty lot is booked by Beancount against what it holds, and only Beancount knows. */
    @Test fun `an empty lot cannot be weighed here`() {
        assertNull(CommodityDetails.parse("{}").weight(amount("-10", "ACME")))
    }

    @Test fun `text that is not a lot or a price is refused with the reason`() {
        assertIs<CommodityDetails.Unreadable>(CommodityDetails.parse("{12.50 USD"))
        assertIs<CommodityDetails.Unreadable>(CommodityDetails.parse("at 13 USD"))
        assertIs<CommodityDetails.Unreadable>(CommodityDetails.parse("@ thirteen USD"))
    }

    @Test fun `the details go into the file exactly as typed`() {
        val draft = TransactionDraft(
            date = "2026-09-13",
            postings = listOf(
                PostingDraft("Assets:Broker", amount("10", "ACME"), details = "{{125.00 USD}} @@ 130.00 USD"),
                PostingDraft("Assets:Cash"),
            ),
        )

        assertTrue(
            draft.render().contains("10 ACME {{125.00 USD}} @@ 130.00 USD"),
            draft.render(),
        )
    }

    @Test fun `a transaction with an unbookable lot is left to Beancount to balance`() {
        val draft = TransactionDraft(
            date = "2026-09-13",
            postings = listOf(
                PostingDraft("Assets:Broker", amount("-10", "ACME"), details = "{}"),
                PostingDraft("Assets:Cash", amount("130", "USD")),
            ),
        )

        assertTrue(draft.validate().none { it is DraftProblem.DoesNotBalance }, "${draft.validate()}")
    }

    @Test fun `unreadable details stop the save and say which posting`() {
        val draft = TransactionDraft(
            date = "2026-09-13",
            postings = listOf(
                PostingDraft("Assets:Broker", amount("10", "ACME"), details = "{12.50 USD"),
                PostingDraft("Assets:Cash"),
            ),
        )

        val problem = draft.validate().filterIsInstance<DraftProblem.UnreadableDetails>().single()
        assertEquals("Assets:Broker", problem.account)
    }

    @Test fun `a cost written with thousands separators is read, as Beancount reads it`() {
        val per = CommodityDetails.parse("{1,250.00 USD}")
        assertEquals(amount("12500.00", "USD"), per.weight(amount("10", "ACME")))

        val total = CommodityDetails.parse("{{1,250.00 USD}}")
        assertEquals(amount("1250.00", "USD"), total.weight(amount("10", "ACME")))
    }

    @Test fun `misplaced separators are not a number`() {
        assertIs<CommodityDetails.Unreadable>(CommodityDetails.parse("{12,50 USD}"))
        assertIs<CommodityDetails.Unreadable>(CommodityDetails.parse("{1,25,000 USD}"))
    }
}
