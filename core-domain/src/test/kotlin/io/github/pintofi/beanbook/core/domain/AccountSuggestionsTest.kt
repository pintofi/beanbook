package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The account suggestions agree with Fava's own.
 *
 * The expected orders and scores below were not worked out by hand: the same transactions were put
 * through Fava 1.30.15's `ExponentialDecayRanker` (`fava/util/ranking.py`) and its account input's
 * `fuzzytest` / `fuzzyfilter` (`fava/static/app.js`), and what they returned is written here.
 */
class AccountSuggestionsTest {

    private fun entry(date: String, payee: String, vararg accounts: String) = JournalEntry(
        date = date, flag = "*", payee = payee, narration = null, tags = emptyList(), links = emptyList(),
        postings = accounts.map { JournalPosting(it, null) }, file = null, line = null,
    )

    private val journal = listOf(
        entry("2024-01-10", "Grocer", "Expenses:Food:Groceries", "Assets:Bank:Checking"),
        entry("2024-01-12", "Grocer", "Expenses:Food:Groceries", "Assets:Bank:Checking"),
        entry("2024-01-15", "Grocer", "Expenses:Food:Groceries", "Liabilities:Card"),
        entry("2025-11-02", "Cafe", "Expenses:Food:Dining", "Liabilities:Card"),
        entry("2026-02-20", "Cafe", "Expenses:Food:Dining", "Liabilities:Card"),
        entry("2026-08-01", "Landlord", "Expenses:Rent", "Assets:Bank:Checking"),
        entry("2026-09-01", "Landlord", "Expenses:Rent", "Assets:Bank:Checking"),
        entry("2026-10-01", "Employer", "Assets:Bank:Checking", "Income:Salary"),
        entry("2026-10-20", "Cafe", "Expenses:Food:Dining", "Assets:Cash"),
    )

    private val accounts = listOf(
        "Assets:Bank:Checking",
        "Assets:Cash",
        "Assets:Savings",
        "Expenses:Food:Dining",
        "Expenses:Food:Groceries",
        "Expenses:Rent",
        "Expenses:Travel",
        "Expenses:Zoo",
        "Income:Salary",
        "Liabilities:Card",
    )

    @Test fun `accounts are ranked by use and recency, then alphabetically, as Fava ranks them`() {
        assertEquals(listOf(
            "Assets:Bank:Checking",
            "Expenses:Food:Dining",
            "Expenses:Rent",
            "Liabilities:Card",
            "Assets:Cash",
            "Income:Salary",
            "Expenses:Food:Groceries",
            "Assets:Savings",
            "Expenses:Travel",
            "Expenses:Zoo",
        ), AccountSuggestions.rank(accounts, journal))
    }


    @Test fun `with the payee Grocer the accounts they used come first, then the overall order`() {
        assertEquals(
            listOf(
                "Expenses:Food:Groceries",
                "Assets:Bank:Checking",
                "Liabilities:Card",
                "Expenses:Food:Dining",
                "Expenses:Rent",
                "Assets:Cash",
                "Income:Salary",
                "Assets:Savings",
                "Expenses:Travel",
                "Expenses:Zoo",
            ),
            AccountSuggestions.forEntry(accounts, journal, "Grocer"),
        )
    }


    @Test fun `with the payee Cafe the accounts they used come first, then the overall order`() {
        assertEquals(
            listOf(
                "Expenses:Food:Dining",
                "Liabilities:Card",
                "Assets:Cash",
                "Assets:Bank:Checking",
                "Expenses:Rent",
                "Income:Salary",
                "Expenses:Food:Groceries",
                "Assets:Savings",
                "Expenses:Travel",
                "Expenses:Zoo",
            ),
            AccountSuggestions.forEntry(accounts, journal, "Cafe"),
        )
    }


    @Test fun `with the payee Landlord the accounts they used come first, then the overall order`() {
        assertEquals(
            listOf(
                "Assets:Bank:Checking",
                "Expenses:Rent",
                "Expenses:Food:Dining",
                "Liabilities:Card",
                "Assets:Cash",
                "Income:Salary",
                "Expenses:Food:Groceries",
                "Assets:Savings",
                "Expenses:Travel",
                "Expenses:Zoo",
            ),
            AccountSuggestions.forEntry(accounts, journal, "Landlord"),
        )
    }


    @Test fun `with the payee Nobody the accounts they used come first, then the overall order`() {
        assertEquals(
            listOf(
                "Assets:Bank:Checking",
                "Expenses:Food:Dining",
                "Expenses:Rent",
                "Liabilities:Card",
                "Assets:Cash",
                "Income:Salary",
                "Expenses:Food:Groceries",
                "Assets:Savings",
                "Expenses:Travel",
                "Expenses:Zoo",
            ),
            AccountSuggestions.forEntry(accounts, journal, "Nobody"),
        )
    }


    @Test fun `no payee, or a blank one, gives the overall ranking`() {
        val overall = AccountSuggestions.rank(accounts, journal)
        assertEquals(overall, AccountSuggestions.forEntry(accounts, journal, null))
        assertEquals(overall, AccountSuggestions.forEntry(accounts, journal, "  "))
    }

    private class Case(val typed: String, val scores: List<Int>, val matches: List<String>)

    private val cases = listOf(
        Case("food", listOf(0, 0, 0, 16, 16, 0, 0, 0, 0, 0), listOf(
                "Expenses:Food:Dining",
                "Expenses:Food:Groceries",
            )),
        Case("Food", listOf(0, 0, 0, 16, 16, 0, 0, 0, 0, 0), listOf(
                "Expenses:Food:Dining",
                "Expenses:Food:Groceries",
            )),
        Case("fgr", listOf(0, 0, 0, 0, 4, 0, 0, 0, 0, 0), listOf(
                "Expenses:Food:Groceries",
            )),
        Case("ag", listOf(2, 0, 2, 0, 0, 0, 0, 0, 0, 0), listOf(
                "Assets:Bank:Checking",
                "Assets:Savings",
            )),
        Case("bank", listOf(16, 0, 0, 0, 0, 0, 0, 0, 0, 0), listOf(
                "Assets:Bank:Checking",
            )),
        Case("ba", listOf(4, 0, 0, 0, 0, 0, 0, 0, 0, 2), listOf(
                "Assets:Bank:Checking",
                "Liabilities:Card",
            )),
        Case("che", listOf(9, 0, 0, 0, 0, 0, 0, 0, 0, 0), listOf(
                "Assets:Bank:Checking",
            )),
        Case("xyz", listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), listOf(
            )),
        Case("rent", listOf(0, 0, 0, 0, 0, 16, 0, 0, 0, 0), listOf(
                "Expenses:Rent",
            )),
        Case("ZOO", listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), listOf(
            )),
        Case("cash", listOf(0, 16, 0, 0, 0, 0, 0, 0, 0, 0), listOf(
                "Assets:Cash",
            )),
        Case("as:ba", listOf(9, 0, 0, 0, 0, 0, 0, 0, 0, 0), listOf(
                "Assets:Bank:Checking",
            )),
        Case("e:f", listOf(0, 0, 0, 4, 4, 0, 0, 0, 0, 0), listOf(
                "Expenses:Food:Dining",
                "Expenses:Food:Groceries",
            )),
        Case("ing", listOf(9, 0, 9, 9, 0, 0, 0, 0, 0, 0), listOf(
                "Assets:Bank:Checking",
                "Assets:Savings",
                "Expenses:Food:Dining",
            )),
        Case("AB", listOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0), listOf(
                "Assets:Bank:Checking",
            )),
    )

    @Test fun `the match score is Fava's for every typed text and account`() {
        cases.forEach { case ->
            val scored = accounts.map { AccountSuggestions.score(case.typed, it) }
            assertEquals(case.scores, scored, "scores for ${case.typed}")
        }
    }

    @Test fun `what matches, and in what order, is Fava's`() {
        cases.forEach { case ->
            assertEquals(case.matches, AccountSuggestions.matching(case.typed, accounts), "matches for ${case.typed}")
        }
    }

    @Test fun `nothing typed keeps every suggestion in order`() {
        assertEquals(accounts, AccountSuggestions.matching("", accounts))
    }

    @Test fun `an account is offered up to and including the day it closes, and not after`() {
        assertTrue(AccountSuggestions.offeredOn("2026-03-01", "2026-02-28"))
        assertTrue(AccountSuggestions.offeredOn("2026-03-01", "2026-03-01"))
        assertFalse(AccountSuggestions.offeredOn("2026-03-01", "2026-03-02"))
    }

    @Test fun `an account that is not closed is always offered`() {
        assertTrue(AccountSuggestions.offeredOn(null, "2030-01-01"))
    }

    @Test fun `a date that cannot be read filters nothing`() {
        assertTrue(AccountSuggestions.offeredOn("2026-03-01", ""))
        assertTrue(AccountSuggestions.offeredOn("2026-03-01", "2026-3"))
    }
}
