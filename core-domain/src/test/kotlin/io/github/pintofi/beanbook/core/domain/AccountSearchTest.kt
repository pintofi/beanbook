package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountSearchTest {

    private fun account(name: String) = AccountBalance(name, emptyList())

    private val accounts = listOf(
        "Assets:Bank:Checking", "Assets:Cash", "Expenses:Food:Groceries", "Expenses:Food:Dining", "Income:Salary",
    ).map(::account)

    private fun found(query: String) = accounts.filter { it.matchesQuery(query) }.map { it.name }

    @Test fun `a blank query matches every account`() {
        assertEquals(accounts.map { it.name }, found(""))
        assertEquals(accounts.map { it.name }, found("   "))
    }

    @Test fun `any part of the name matches, whatever the case`() {
        assertEquals(listOf("Expenses:Food:Groceries", "Expenses:Food:Dining"), found("FOOD"))
        assertEquals(listOf("Assets:Cash"), found("cash"))
    }

    @Test fun `every word has to be in the name, in any order`() {
        assertEquals(listOf("Expenses:Food:Groceries"), found("groc food"))
        assertEquals(listOf("Expenses:Food:Groceries"), found("food  groceries"))
    }

    @Test fun `a word that is in no name finds nothing`() {
        assertTrue(found("zebra").isEmpty())
        assertFalse(account("Assets:Cash").matchesQuery("cash savings"))
    }
}
