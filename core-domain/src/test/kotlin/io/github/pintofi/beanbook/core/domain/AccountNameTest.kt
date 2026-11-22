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
        listOf("EUR", "PKR", "AIRSF-E", "VTI", "A", "BTC.X", "ABC'D")
            .forEach { assertTrue(CommodityName.isValid(it), it) }
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

    // What Beancount 3.2.3 accepts, checked against its loader: only the first character after the
    // root has to be an upper-case letter or a digit.
    @Test fun `names follow what Beancount accepts, not just what its lexer reads`() {
        val accepted = listOf(
            "Expenses:Ärzte", "Expenses:Käse", "Expenses:Straße", "Assets:Girokonto-Süd", "Expenses:1ä",
        )
        for (ok in accepted) {
            assertTrue(AccountName.problems(ok).isEmpty(), ok)
        }
        val refused = listOf(
            "Expenses:ärzte", "Expenses:ä", "Expenses:日本", "Expenses:😀", "Expenses:Foo Bar", "Expenses:Foo\u3000Bar",
        )
        for (bad in refused) {
            assertEquals(listOf(AccountProblem.BadComponent(bad.substringAfter(':'))), AccountName.problems(bad), bad)
        }
    }

    @Test fun `a decomposed name is accepted and typed text is composed`() {
        val decomposed = "A\u0308rzte"
        assertTrue(AccountName.problems("Expenses:$decomposed").isEmpty())
        assertEquals("\u00C4rzte", AccountName.normalise(decomposed))
        assertEquals("\u00C4rzte", AccountName.normalise("\u00E4rzte"))
    }
}
