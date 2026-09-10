package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Postings held at a cost and exchanged at a price (B63).
 *
 * The app modelled a posting as an account and a number of units, which is not what a
 * transaction says. A fund conversion sells 151.88 units out of a named lot at 483.05 and
 * buys 710.47 units of another at 103.2642: weighed by units it balances in nothing, and a
 * form that judged it by units called a correct entry broken and refused to write it.
 */
class PostingLotTest {

    private fun amount(value: String, currency: String) = Amount(BigDecimal(value), currency)

    private val sale = PostingDraft(
        account = "Assets:Investments:ASSF",
        amount = amount("-151.88", "ASSF"),
        cost = Cost(amount("400.00", "PKR"), date = "2025-01-01", label = "avcoassf"),
        price = amount("483.0500", "PKR"),
    )

    @Test fun `a posting held at cost weighs its cost, not its units`() {
        assertEquals(amount("-60752.0000", "PKR"), sale.weight())
    }

    @Test fun `cost wins over price when a posting carries both`() {
        // Beancount's own rule: the books move at cost, and the price records what the units
        // went out at. Weighing this leg at 483.05 would misstate the gain by the difference.
        assertEquals("PKR", sale.weight()?.currency)
        assertEquals(BigDecimal("-60752.0000"), sale.weight()?.number)
    }

    @Test fun `a posting with only a price weighs that price`() {
        val exchanged = PostingDraft(
            account = "Assets:A",
            amount = amount("-100.00", "EUR"),
            price = amount("1.10", "USD"),
        )
        assertEquals(amount("-110.0000", "USD"), exchanged.weight())
    }

    @Test fun `a plain posting weighs its units`() {
        val plain = PostingDraft("Assets:Cash", amount("-12.50", "USD"))
        assertEquals(amount("-12.50", "USD"), plain.weight())
    }

    @Test fun `a conversion between two commodities balances`() {
        val draft = TransactionDraft(
            date = "2026-09-03",
            payee = "UBL Funds",
            narration = "Coversion from ASSF to AICF",
            postings = listOf(
                sale,
                PostingDraft("Income:Gains:Sale", amount("-73365.634000", "PKR")),
                PostingDraft("Income:Gains:Cost", amount("60751.517826", "PKR")),
                PostingDraft(
                    account = "Assets:Investments:AICF",
                    amount = amount("710.47", "AICF"),
                    cost = Cost(amount("103.2642", "PKR"), date = "2026-09-03"),
                ),
            ),
        )

        // 0.482174 PKR of residual, which the entry's own precision tolerates: Beancount
        // loads this file without an error, and the app agreeing with it is the point.
        assertEquals(emptyList(), draft.validate().filterIsInstance<DraftProblem.DoesNotBalance>())
        assertTrue(draft.isValid)
    }

    @Test fun `an unbalanced conversion is still reported`() {
        val wrong = TransactionDraft(
            date = "2026-09-03",
            postings = listOf(
                sale.copy(cost = Cost(amount("400.00", "PKR"))),
                PostingDraft("Assets:Bank", amount("1.00", "PKR")),
            ),
        )
        assertTrue(wrong.validate().any { it is DraftProblem.DoesNotBalance })
    }

    @Test fun `a cost and a price are written the way the file writes them`() {
        val draft = TransactionDraft(
            date = "2026-09-03",
            narration = "Conversion",
            postings = listOf(sale, PostingDraft("Assets:Bank", amount("60752.0000", "PKR"))),
        )
        val line = draft.render().lines()[1]
        assertTrue(
            line.endsWith("-151.88 ASSF {400.00 PKR, 2025-01-01, \"avcoassf\"} @ 483.0500 PKR"),
            "rendered: $line",
        )
    }

    @Test fun `a cost with no date or label writes only its figure`() {
        assertEquals("{103.2642 PKR}", Cost(amount("103.2642", "PKR")).format())
    }

    @Test fun `cloning an entry keeps the lot it was held at`() {
        val entry = JournalEntry(
            date = "2026-09-03",
            flag = "*",
            payee = "UBL Funds",
            narration = "Coversion from ASSF to AICF",
            tags = emptyList(),
            links = emptyList(),
            postings = listOf(
                JournalPosting(
                    account = "Assets:Investments:ASSF",
                    amount = amount("-151.88", "ASSF"),
                    cost = Cost(amount("400.00", "PKR"), "2025-01-01", "avcoassf"),
                    price = amount("483.0500", "PKR"),
                ),
                JournalPosting("Assets:Bank", amount("60752.0000", "PKR")),
            ),
            file = "main.beancount",
            line = 12,
        )

        val clone = TransactionDraft.cloneOf(entry, "2026-09-10")

        assertEquals(entry.postings[0].cost, clone.postings[0].cost)
        assertEquals(entry.postings[0].price, clone.postings[0].price)
    }

    @Test fun `a booked reduction is not editable as fields`() {
        // The file says `{"avcoassf"}`; Beancount reports the whole lot. Rendering the draft
        // produces text the file does not contain, so the form must refuse and the text
        // editor is the honest option (invariant 4).
        val entry = JournalEntry(
            date = "2026-09-03",
            flag = "*",
            payee = null,
            narration = "Sale",
            tags = emptyList(),
            links = emptyList(),
            postings = listOf(
                JournalPosting(
                    account = "Assets:Investments:ASSF",
                    amount = amount("-151.88", "ASSF"),
                    cost = Cost(amount("400.00", "PKR"), "2025-01-01", "avcoassf"),
                ),
                JournalPosting("Assets:Bank", amount("60752.0000", "PKR")),
            ),
            file = "main.beancount",
            line = 12,
        )
        val source = """
            2026-09-03 * "Sale"
              Assets:Investments:ASSF  -151.88 ASSF {"avcoassf"}
              Assets:Bank  60752.0000 PKR
        """.trimIndent()

        assertFalse(StructuredEdit.isLossless(entry, source))
    }

    @Test fun `a search finds an entry by its lot`() {
        val entry = JournalEntry(
            date = "2026-09-03",
            flag = "*",
            payee = null,
            narration = "Sale",
            tags = emptyList(),
            links = emptyList(),
            postings = listOf(
                JournalPosting(
                    account = "Assets:Investments:ASSF",
                    amount = amount("-151.88", "ASSF"),
                    cost = Cost(amount("400.00", "PKR"), "2025-01-01", "avcoassf"),
                    price = amount("483.0500", "PKR"),
                ),
            ),
            file = "main.beancount",
            line = 12,
        )

        assertTrue(entry.matches("avcoassf"))
        assertTrue(entry.matches("483.0500"))
    }
}
