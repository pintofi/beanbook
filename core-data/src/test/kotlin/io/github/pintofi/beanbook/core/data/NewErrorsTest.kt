package io.github.pintofi.beanbook.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** A refused save names what it added, not what the ledger already had. */
class NewErrorsTest {

    @Test
    fun `existing errors are not blamed on the new entry`() {
        val before = listOf("a.beancount:3 Disagreeing price entries")
        val after = before + listOf("t.beancount:9 Invalid reference to unknown account 'Assets:Bank:Ca'")

        assertEquals(
            listOf("t.beancount:9 Invalid reference to unknown account 'Assets:Bank:Ca'"),
            newErrors(after, before, count = 1),
        )
    }

    @Test
    fun `when the texts cannot tell, the last ones are taken`() {
        val before = listOf("x:1 problem")
        val after = listOf("x:2 problem", "y:5 new one")

        assertEquals(listOf("y:5 new one"), newErrors(after, before, count = 1))
    }
}
