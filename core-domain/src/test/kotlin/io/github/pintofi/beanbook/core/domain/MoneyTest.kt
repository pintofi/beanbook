package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Invariant 1 (SPEC.md 5): decimal only, never binary floating point. */
class AmountTest {

    @Test
    fun `addition is exact where binary floating point is not`() {
        val sum = Amount.parse("0.1", "USD") + Amount.parse("0.2", "USD")
        assertEquals(Amount.parse("0.3", "USD"), sum)
        // The same sum in binary floating point is 0.30000000000000004.
        assertTrue(0.1 + 0.2 != 0.3, "sanity: Double really is inexact here")
    }

    @Test
    fun `trailing zeros survive, because Beancount does not rewrite them`() {
        val amount = Amount.parse("10.00", "USD")
        assertEquals("10.00 USD", amount.format())
        assertEquals(2, amount.number.scale())
    }

    @Test
    fun `large values keep full precision`() {
        val amount = Amount.parse("123456789012345678.99", "USD")
        assertEquals("123456789012345678.99 USD", amount.format())
    }

    @Test
    fun `cents do not drift over many additions`() {
        var total = Amount.zero("USD")
        repeat(1000) { total += Amount.parse("0.01", "USD") }
        assertEquals(BigDecimal("10.00"), total.number)
    }

    @Test
    fun `mixing currencies is rejected rather than silently coerced`() {
        val error = runCatching { Amount.parse("1", "USD") + Amount.parse("1", "EUR") }
        assertTrue(error.isFailure)
    }

    @Test
    fun `blank currency is rejected`() {
        assertTrue(runCatching { Amount.parse("1", " ") }.isFailure)
    }
}

class BalanceTest {

    @Test
    fun `a balanced transaction has no residual`() {
        val balance = Balance.of(
            listOf(Amount.parse("42.30", "USD"), Amount.parse("-42.30", "USD"))
        )
        assertTrue(balance.isBalanced)
        assertTrue(balance.residuals().isEmpty())
    }

    @Test
    fun `an unbalanced transaction reports its residual instead of hiding it`() {
        val balance = Balance.of(
            listOf(Amount.parse("42.30", "USD"), Amount.parse("-42.00", "USD"))
        )
        assertFalse(balance.isBalanced)
        assertEquals(listOf(Amount.parse("0.30", "USD")), balance.residuals())
    }

    @Test
    fun `residuals are reported per currency`() {
        val balance = Balance.of(
            listOf(
                Amount.parse("10.00", "USD"),
                Amount.parse("-4.00", "USD"),
                Amount.parse("5.00", "EUR"),
            )
        )
        assertEquals(
            listOf(Amount.parse("5.00", "EUR"), Amount.parse("6.00", "USD")),
            balance.residuals(),
        )
    }
}
