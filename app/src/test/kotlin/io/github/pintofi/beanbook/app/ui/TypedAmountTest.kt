package io.github.pintofi.beanbook.app.ui

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedAmountTest {

    @Test fun `plain numbers are read as they are written`() {
        assertEquals(BigDecimal("4.50"), parseTypedAmount("4.50"))
        assertEquals(BigDecimal("-300"), parseTypedAmount(" -300 "))
        assertEquals(BigDecimal("+7.1"), parseTypedAmount("+7.1"))
        assertEquals(BigDecimal("12"), parseTypedAmount("12."))
    }

    @Test fun `thousands grouped with commas are accepted, as Beancount accepts them`() {
        assertEquals(BigDecimal("1234.50"), parseTypedAmount("1,234.50"))
        assertEquals(BigDecimal("-2624167.69"), parseTypedAmount("-2,624,167.69"))
    }

    @Test fun `anything else is refused rather than read as empty`() {
        // A decimal comma is not guessed at: 4,50 could be 450 or 4.50.
        listOf("4,50", "1,23", "4.5O", "1e5", "abc", "1 000", ".5", "--3", "1,2345.00", "").forEach {
            assertNull("accepted \"$it\"", parseTypedAmount(it))
        }
    }
}
