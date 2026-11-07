package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** `*`, `!` and any other character are all flags: Cleared, Pending, and Other flag. */
class FlagWordingTest {

    @Test fun `a star is cleared and an exclamation mark is pending`() {
        assertEquals("Cleared", describeFlag("*"))
        assertEquals("Pending", describeFlag("!"))
    }

    @Test fun `no flag, or txn, is cleared`() {
        assertEquals("Cleared", describeFlag(null))
        assertEquals("Cleared", describeFlag("txn"))
    }

    @Test fun `any other character is an other flag and says which`() {
        assertEquals("Other flag P", describeFlag("P"))
        assertEquals(LedgerMark.FLAGGED, markForFlag("T"))
    }
}
