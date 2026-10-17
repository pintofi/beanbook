package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.app.packages.isPackageName
import io.github.pintofi.beanbook.app.storage.safeDisplayName
import io.github.pintofi.beanbook.core.domain.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixes from the baseline review of the app's engine, services and glue. */
class ReviewFixesTest {

    private fun entry(date: String = "2024-03-05", payee: String? = "Cafe", narration: String? = "Lunch") =
        JournalEntry(
            date = date, flag = "*", payee = payee, narration = narration,
            tags = emptyList(), links = emptyList(), postings = emptyList(),
            file = "main.beancount", line = 10,
        )

    @Test
    fun `a link is opened outside the app only if it is a plain web address`() {
        assertTrue(isOpenableExternally("https", "fava.example.com", null))
        assertTrue(isOpenableExternally("HTTP", "192.168.1.5", null))
        assertFalse(isOpenableExternally("intent", "x", null))
        assertFalse(isOpenableExternally("file", "", null))
        assertFalse(isOpenableExternally("content", "x", null))
        assertFalse(isOpenableExternally("tel", null, null))
        assertFalse(isOpenableExternally(null, "x", null))
        // https://good.example@evil.example/ shows one host and goes to the other.
        assertFalse(isOpenableExternally("https", "evil.example", "good.example"))
        assertFalse(isOpenableExternally("https", null, null))
    }

    @Test
    fun `a display name from a provider cannot leave the folder it is joined onto`() {
        // The last segment is what is kept, never the path.
        assertEquals("plugin.py", safeDisplayName("../x/plugin.py", "fallback"))
        assertEquals("plugin.py", safeDisplayName("a/b\\plugin.py", "fallback"))
        assertEquals("fallback", safeDisplayName("..", "fallback"))
        assertEquals("fallback", safeDisplayName("x/..", "fallback"))
        assertEquals("fallback", safeDisplayName("   ", "fallback"))
        assertEquals("fallback", safeDisplayName("a\u0000b.py", "fallback"))
        assertEquals(120, safeDisplayName("n".repeat(500), "fallback").length)
    }

    @Test
    fun `only real package names reach an index address`() {
        assertTrue(isPackageName("numpy"))
        assertTrue(isPackageName("python-dateutil"))
        assertTrue(isPackageName("zope.interface"))
        assertFalse(isPackageName(""))
        assertFalse(isPackageName("../../etc"))
        assertFalse(isPackageName("a/b"))
        assertFalse(isPackageName("a b"))
        assertFalse(isPackageName("-leading"))
        assertFalse(isPackageName("trailing."))
    }

    @Test
    fun `the entry found at a line is the one that was listed`() {
        val text = "2024-03-05 * \"Cafe\" \"Lunch\"\n  Expenses:Food  4.50 USD\n  Assets:Cash\n"
        assertTrue(sliceMatches(entry(), text))
    }

    @Test
    fun `a different entry at the same line is refused`() {
        val other = "2024-03-06 * \"Cafe\" \"Lunch\"\n  Expenses:Food  4.50 USD\n  Assets:Cash\n"
        assertFalse(sliceMatches(entry(), other))
        val another = "2024-03-05 * \"Bakery\" \"Bread\"\n  Expenses:Food  2.00 USD\n  Assets:Cash\n"
        assertFalse(sliceMatches(entry(), another))
    }

    @Test
    fun `an escaped quote in a narration still matches`() {
        val text = "2024-03-05 * \"Cafe\" \"The \\\"best\\\" lunch\"\n  Expenses:Food  4.50 USD\n  Assets:Cash\n"
        assertTrue(sliceMatches(entry(narration = "The \"best\" lunch"), text))
    }

    @Test
    fun `an entry with neither payee nor narration is matched on its date`() {
        val text = "2024-03-05 note Assets:Cash \"counted\"\n"
        assertTrue(sliceMatches(entry(payee = null, narration = null), text))
        assertFalse(sliceMatches(entry(date = "2024-03-04", payee = null, narration = null), text))
    }
}
