package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccountCurrenciesTest {

    private fun changed(block: String, currencies: List<String>): String =
        assertIs<AccountDetails.Result.Changed>(AccountDetails.rewriteCurrencies(block, currencies)).text

    private val meta = "  institution: \"Corner Bank\"\n    ; a comment of the person's\n  rank: 2"

    @Test fun `a currency is added to an account that allowed none`() {
        assertEquals(
            "2026-01-01 open Assets:Cash EUR\n$meta",
            changed("2026-01-01 open Assets:Cash\n$meta", listOf("EUR")),
        )
    }

    @Test fun `the list is replaced and the lines under the directive stay byte for byte`() {
        assertEquals(
            "2026-01-01 open Assets:Cash EUR,USD\n$meta",
            changed("2026-01-01 open Assets:Cash EUR\n$meta", listOf("EUR", "USD")),
        )
    }

    @Test fun `an empty list takes the restriction away`() {
        assertEquals(
            "2026-01-01 open Assets:Cash\n$meta",
            changed("2026-01-01 open Assets:Cash EUR,USD\n$meta", emptyList()),
        )
    }

    @Test fun `the booking method and a trailing comment stay`() {
        assertEquals(
            "2026-01-01  open  Assets:Bank:Cash USD \"FIFO\"  ; mine",
            changed("2026-01-01  open  Assets:Bank:Cash EUR \"FIFO\"  ; mine", listOf("USD")),
        )
        assertEquals(
            "2026-01-01 open Assets:Cash EUR \"STRICT\"",
            changed("2026-01-01 open Assets:Cash \"STRICT\"", listOf("EUR")),
        )
    }

    @Test fun `a list written with spaces after the commas is read`() {
        assertEquals(
            "2026-01-01 open Assets:Cash USD",
            changed("2026-01-01 open Assets:Cash EUR, USD, GBP", listOf("USD")),
        )
    }

    @Test fun `the same list in the same order is not a change`() {
        assertEquals(
            AccountDetails.Result.Unchanged,
            AccountDetails.rewriteCurrencies("2026-01-01 open Assets:Cash EUR,USD\n$meta", listOf("EUR", "USD")),
        )
    }

    @Test fun `a repeated currency is written once`() {
        assertEquals(
            "2026-01-01 open Assets:Cash EUR,USD",
            changed("2026-01-01 open Assets:Cash", listOf("EUR", "USD", "EUR")),
        )
    }

    @Test fun `a line ending of CRLF is kept`() {
        assertEquals(
            "2026-01-01 open Assets:Cash EUR\r\n  rank: 2",
            changed("2026-01-01 open Assets:Cash\r\n  rank: 2", listOf("EUR")),
        )
        assertEquals(
            "2026-01-01 open Assets:Cash USD \"FIFO\" ; c\r",
            changed("2026-01-01 open Assets:Cash EUR \"FIFO\" ; c\r", listOf("USD")),
        )
    }

    @Test fun `a name that is not a currency is refused and nothing is written`() {
        val result = AccountDetails.rewriteCurrencies("2026-01-01 open Assets:Cash", listOf("eur"))
        assertIs<AccountDetails.Result.Refused>(result)
        assertIs<AccountDetails.Result.Refused>(
            AccountDetails.rewriteCurrencies("2026-01-01 open Assets:Cash", listOf("US D")),
        )
    }

    @Test fun `something that is not an open line is refused`() {
        assertIs<AccountDetails.Result.Refused>(
            AccountDetails.rewriteCurrencies("2026-01-01 close Assets:Cash", listOf("EUR")),
        )
    }

    @Test fun `details and currencies are edited together in one pass`() {
        val block = "2026-01-01 open Assets:Cash EUR\n  rank: 2"
        val result = AccountDetails.rewrite(block, mapOf("rank" to "2"), mapOf("rank" to "3"), listOf("EUR", "USD"))
        val text = assertIs<AccountDetails.Result.Changed>(result).text
        assertEquals("2026-01-01 open Assets:Cash EUR,USD\n  rank: 3", text)
    }

    @Test fun `only the currencies changing is a change, and nothing changing is not`() {
        val block = "2026-01-01 open Assets:Cash EUR\n  rank: 2"
        val same = mapOf("rank" to "2")
        assertEquals(AccountDetails.Result.Unchanged, AccountDetails.rewrite(block, same, same, listOf("EUR")))
        assertEquals(AccountDetails.Result.Unchanged, AccountDetails.rewrite(block, same, same, null))
        assertEquals(
            "2026-01-01 open Assets:Cash USD\n  rank: 2",
            assertIs<AccountDetails.Result.Changed>(AccountDetails.rewrite(block, same, same, listOf("USD"))).text,
        )
    }
}
