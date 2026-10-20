package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountNameTest {

    @Test fun `a well formed name has no problems, including Unicode letters`() {
        assertTrue(AccountName.problems("Expenses:Food:Groceries").isEmpty())
        assertTrue(AccountName.problems("Expenses:Café").isEmpty())
        assertTrue(AccountName.problems("Assets:2026-Fund").isEmpty())
    }

    @Test fun `the root has to be one of the ledger's own`() {
        assertEquals(listOf(AccountProblem.UnknownRoot), AccountName.problems("Food:Coffee"))
        val german = AccountNames(expenses = "Ausgaben")
        assertTrue(AccountName.problems("Ausgaben:Kaffee", german).isEmpty())
        assertEquals(listOf(AccountProblem.UnknownRoot), AccountName.problems("Expenses:Kaffee", german))
    }

    @Test fun `a name needs a part under the root`() {
        assertEquals(listOf(AccountProblem.NoName("Expenses")), AccountName.problems("Expenses"))
    }

    @Test fun `lowercase, empty and punctuated parts are refused`() {
        assertEquals(listOf(AccountProblem.BadComponent("coffee")), AccountName.problems("Expenses:coffee"))
        assertEquals(listOf(AccountProblem.EmptyComponent), AccountName.problems("Expenses::Coffee"))
        assertEquals(listOf(AccountProblem.EmptyComponent), AccountName.problems("Expenses:Coffee:"))
        assertEquals(listOf(AccountProblem.BadComponent("Co ffee")), AccountName.problems("Expenses:Co ffee"))
    }

    @Test fun `a name already on the list is a duplicate`() {
        assertEquals(
            listOf(AccountProblem.Duplicate("Expenses:Coffee")),
            AccountName.problems("Expenses:Coffee", existing = listOf("Expenses:Coffee")),
        )
    }

    @Test fun `typed words become parts Beancount accepts`() {
        assertEquals("Food-Coffee", AccountName.normalise("food coffee"))
        assertEquals("Food:Coffee", AccountName.normalise("food:coffee"))
        assertEquals("Café", AccountName.normalise(" café! "))
        assertEquals("", AccountName.normalise(" : "))
    }

    @Test fun `commodity names follow Beancount's rule`() {
        listOf("EUR", "PKR", "AIRSF-E", "VTI", "A", "BTC.X", "ABC'D").forEach { assertTrue(CommodityName.isValid(it), it) }
        listOf("eur", "E-", "1ABC", "", "A".repeat(25), "EU R").forEach { assertTrue(!CommodityName.isValid(it), it) }
    }

    @Test fun `open lines put currencies in a column and a booking method after them`() {
        val lines = AccountPlan.openLines(
            listOf(
                AccountPlan("Assets:Cash", listOf("EUR")),
                AccountPlan("Assets:Broker", listOf("VTI"), booking = "FIFO"),
                AccountPlan("Assets:Any"),
            ),
            "2026-09-30", nameWidth = 22,
        )
        assertEquals(
            "2026-09-30 open Assets:Cash" + " ".repeat(11) + "EUR\n" +
                "2026-09-30 open Assets:Broker" + " ".repeat(9) + "VTI \"FIFO\"\n" +
                "2026-09-30 open Assets:Any\n",
            lines,
        )
    }
}
