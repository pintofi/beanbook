package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import io.github.pintofi.beanbook.engine.local.LedgerReader
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A holding is units and the lot they belong to, on the device that has to show it.
 *
 * The ledger below is the conversion a user sent: a sale out of a lot named by a label, at a
 * price, with the proceeds worked out by arithmetic in the file and one leg left for
 * Beancount to interpolate, and a purchase into a lot dated the same day. The app reported
 * four bare numbers of units for it, which cannot be reconciled with anything.
 */
@RunWith(AndroidJUnit4::class)
class LotAndPriceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = LedgerReader(context)

    private val equity =
        "Assets:Non-Current:Investments:Mutual-Funds:Equity:Example-Index-Fund"
    private val debt =
        "Assets:Non-Current:Investments:Mutual-Funds:Debt:Example-Cash-Fund"

    private fun ledger(): String {
        val dir = File(context.cacheDir, "lots").apply {
            deleteRecursively()
            mkdirs()
        }
        val main = File(dir, "main.beancount")
        main.writeText(
            """
            option "operating_currency" "PKR"

            2020-01-01 open $equity
            2020-01-01 open $debt
            2020-01-01 open Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Sale
            2020-01-01 open Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Cost
            2020-01-01 open Assets:Bank

            2025-01-01 * "UBL Funds" "Purchase"
              $equity  150.00 EIF {"lot-a", 400.00 PKR}
              Assets:Bank

            2026-09-03 * "UBL Funds" "Coversion from EIF to AICF"
              $equity  -150.00 EIF {"lot-a"} @ 120.0500 PKR
              Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Sale  -150.00*120.0500 PKR
              Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Cost
              $debt  710.47 AICF {103.2642 PKR, 2026-09-03}
            """.trimIndent(),
        )
        return main.absolutePath
    }

    @Test
    fun the_conversion_loads_without_an_error() {
        val snapshot = runBlocking { reader.read(ledger()) }.getOrThrow()

        assertEquals(snapshot.errors.joinToString("; ") { it.message }, 0, snapshot.errors.size)
        assertEquals(2, snapshot.journal.size)
    }

    @Test
    fun a_sale_carries_the_lot_it_came_from_and_the_price_it_went_out_at() {
        val snapshot = runBlocking { reader.read(ledger()) }.getOrThrow()
        // Newest first, as the journal is shown.
        val sale = snapshot.journal.first().postings.first()

        assertEquals(BigDecimal("-150.00"), sale.amount?.number)
        assertEquals("EIF", sale.amount?.currency)
        val cost = sale.cost
        assertNotNull("the sale carries no cost", cost)
        requireNotNull(cost)
        assertEquals(BigDecimal("400.00"), cost.amount.number)
        assertEquals("PKR", cost.amount.currency)
        assertEquals("2025-01-01", cost.date)
        assertEquals("lot-a", cost.label)
        assertEquals(BigDecimal("120.0500"), sale.price?.number)
    }

    @Test
    fun a_purchase_carries_the_lot_it_creates() {
        val snapshot = runBlocking { reader.read(ledger()) }.getOrThrow()
        val purchase = snapshot.journal.first().postings[3]

        assertEquals(BigDecimal("710.47"), purchase.amount?.number)
        assertEquals(BigDecimal("103.2642"), purchase.cost?.amount?.number)
        assertEquals("2026-09-03", purchase.cost?.date)
        assertNull(purchase.cost?.label)
        assertNull(purchase.price)
    }

    @Test
    fun an_interpolated_leg_arrives_with_the_figure_beancount_worked_out() {
        val snapshot = runBlocking { reader.read(ledger()) }.getOrThrow()
        val postings = snapshot.journal.first().postings

        // Written in the file as `-150.00*120.0500 PKR`, and as the empty line under it.
        assertEquals(BigDecimal("-18007.500000"), postings[1].amount?.number)
        assertEquals(BigDecimal("4641.383826"), postings[2].amount?.number)
        assertNull(postings[2].cost)
    }

    @Test
    fun the_holding_is_reported_at_units_and_at_cost() {
        val snapshot = runBlocking { reader.read(ledger()) }.getOrThrow()
        val held = snapshot.accounts.single { it.name == debt }

        assertEquals(BigDecimal("710.47"), held.positions.single().number)
        assertEquals("AICF", held.positions.single().currency)
        // 710.47 at 103.2642 PKR. What it cost is not the number of units, and the app has
        // both because the posting carried the lot.
        assertEquals("PKR", held.atCost.single().currency)
        assertTrue(held.atCost.single().number > BigDecimal("73000"))
    }

    @Test
    fun cloning_the_conversion_produces_the_same_entry_again() {
        val snapshot = runBlocking { reader.read(ledger()) }.getOrThrow()
        val clone = TransactionDraft.cloneOf(snapshot.journal.first(), "2026-09-10")

        // It balances: weighed by units this entry balances in nothing, and the form would
        // have refused to write it.
        assertEquals(clone.validate().joinToString { it.describe() }, 0, clone.validate().size)
        val text = clone.render()
        assertTrue(text, text.contains("{400.00 PKR, 2025-01-01, \"lot-a\"} @ 120.0500 PKR"))
        assertTrue(text, text.contains("{103.2642 PKR, 2026-09-03}"))
    }
}
