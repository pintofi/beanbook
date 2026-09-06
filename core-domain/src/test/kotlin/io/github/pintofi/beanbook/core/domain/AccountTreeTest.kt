package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folding a branch away must not fold the money away with it.
 *
 * The subtotal on a collapsed row is the only thing standing between "this branch is hidden"
 * and "this branch is empty", so it is what these tests are mostly about.
 */
class AccountTreeTest {

    private fun balance(name: String, value: String, currency: String = "USD") =
        AccountBalance(name, listOf(Amount(BigDecimal(value), currency)))

    private val accounts = listOf(
        balance("Assets:Bank:Checking", "100.00"),
        balance("Assets:Bank:Savings", "250.00"),
        balance("Assets:Cash", "20.00"),
        balance("Expenses:Food", "5.00"),
    )

    @Test fun `an ancestor nothing posted to is still a node`() {
        val tree = AccountTree.of(accounts, "Assets")
        val bank = tree.single { it.name == "Assets:Bank" }
        assertNull(bank.own, "nothing posts to Assets:Bank, so it has no balance of its own")
        assertEquals(listOf("Assets:Bank:Checking", "Assets:Bank:Savings"), bank.children.map { it.name })
    }

    @Test fun `a folded branch still shows what is inside it`() {
        val bank = AccountTree.of(accounts, "Assets").single { it.name == "Assets:Bank" }
        assertEquals(
            listOf(Amount(BigDecimal("350.00"), "USD")),
            bank.subtotal(AccountBalance::positions),
        )
    }

    @Test fun `an account with a balance of its own and children counts both`() {
        val withOwn = accounts + balance("Assets:Bank", "7.00")
        val bank = AccountTree.of(withOwn, "Assets").single { it.name == "Assets:Bank" }
        assertEquals(
            listOf(Amount(BigDecimal("357.00"), "USD")),
            bank.subtotal(AccountBalance::positions),
        )
    }

    @Test fun `currencies are not mixed in a subtotal`() {
        val mixed = accounts + balance("Assets:Bank:Euros", "10.00", "EUR")
        val bank = AccountTree.of(mixed, "Assets").single { it.name == "Assets:Bank" }
        assertEquals(
            setOf(Amount(BigDecimal("350.00"), "USD"), Amount(BigDecimal("10.00"), "EUR")),
            bank.subtotal(AccountBalance::positions).toSet(),
        )
    }

    @Test fun `a root with nothing under it produces no nodes`() {
        assertTrue(AccountTree.of(accounts, "Liabilities").isEmpty())
    }

    @Test fun `every account reaches the tree exactly once`() {
        val names = listOf("Assets", "Expenses").flatMap { root ->
            AccountTree.of(accounts, root).flatMap { it.names() }
        }
        assertEquals(names.size, names.toSet().size, "an account appeared twice in the tree")
        assertTrue(accounts.map { it.name }.all { it in names.toSet() })
    }
}
