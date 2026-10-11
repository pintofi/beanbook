package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.engine.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The header's health line and rule, now that report extensions count (B97, B98). */
class LedgerHealthTest {

    private fun ledger(errors: Int = 0, extensions: Map<String, String?> = emptyMap()) = LedgerSnapshot(
        title = null,
        operatingCurrencies = emptyList(),
        entryCount = 12,
        entryCounts = emptyMap(),
        accounts = emptyList(),
        journal = emptyList(),
        errors = List(errors) { LedgerError("Problem $it", "main.beancount", it + 1, "ValidationError") },
        includedFiles = emptyList(),
        loadMillis = 0,
        extensionHealth = extensions,
    )

    private val ready = EngineStatus.Serving(origin = "http://127.0.0.1:1", ledgerSlug = "main", startupMillis = 0)

    @Test fun `a clean ledger is healthy and says no errors`() {
        assertEquals("12 entries · no errors", ledgerHealth(ready, ledger()))
        assertTrue(ledgerHealthy(ledger()))
    }

    @Test fun `errors are counted and the ledger is not healthy`() {
        assertEquals("12 entries · 1 error", ledgerHealth(ready, ledger(errors = 1)))
        assertEquals("12 entries · 3 errors", ledgerHealth(ready, ledger(errors = 3)))
        assertFalse(ledgerHealthy(ledger(errors = 1)))
    }

    @Test fun `a failed extension is named and makes the rule say needs attention`() {
        val failing = ledger(extensions = mapOf("fava_portfolio_returns" to "No module named 'scipy'", "ok" to null))

        assertEquals("12 entries · no errors · 1 extension failed", ledgerHealth(ready, failing))
        assertFalse(ledgerHealthy(failing))
    }

    @Test fun `extensions that import change nothing`() {
        val fine = ledger(extensions = mapOf("fava_investor" to null))

        assertEquals("12 entries · no errors", ledgerHealth(ready, fine))
        assertTrue(ledgerHealthy(fine))
    }

    @Test fun `errors and failed extensions are both said`() {
        val both = ledger(errors = 2, extensions = mapOf("a" to "x", "b" to "y"))

        assertEquals("12 entries · 2 errors · 2 extensions failed", ledgerHealth(ready, both))
    }

    @Test fun `no ledger is never healthy`() {
        assertFalse(ledgerHealthy(null))
    }
}
