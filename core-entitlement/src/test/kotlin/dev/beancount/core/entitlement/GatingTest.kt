package dev.beancount.core.entitlement

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What each tier unlocks, and — more importantly — what is never gated at all.
 *
 * These tests are the executable form of the promise in SPEC.md: *data-in, data-out and
 * recovery are never paywalled*. A future change that moves export or editing behind a tier
 * has to delete a test that says so out loud.
 */
class GatingTest {

    private fun keyed(tier: Tier) = Entitlement(tier, Grant.Key(tier, "b@e.com", "PP-1"))

    @Test
    fun `the gated list is exactly this, so nothing joins it by accident`() {
        // Pinned rather than described: adding a feature here is a product decision that has
        // to be made on purpose, and moving entry, export or restore into it would have to
        // start by editing this list (SPEC.md, "Free forever").
        assertEquals(
            listOf(
                "FAVA_REPORTS", "CHARTS", "BQL_QUERIES", "HOLDINGS_VALUATION",
                "DOCUMENTS", "CAMERA_CAPTURE", "PDF_ASSEMBLY", "OCR", "WIDGETS",
                "AUTO_MIRROR", "SCHEDULED_BACKUP",
                "REMOTE_MODE", "STATEMENT_IMPORTERS", "SCRIPTS", "PLUGINS", "FAVA_EXTENSIONS",
            ),
            Feature.entries.map { it.name },
        )
        // STATEMENT_IMPORTERS is automated statement parsing (beangulp), not the manual
        // import of a ledger, which is free and unlimited. SCHEDULED_BACKUP is the scheduler,
        // not backup itself. Both distinctions are the whole promise, so they are stated here.
        //
        // HOLDINGS_VALUATION gates the *choice* of showing balances at cost or at market
        // value. The plain units stay free and stay the default, so nothing a free user could
        // see before is behind it -- which is the line that keeps this consistent with
        // "data-out is never paywalled". Market value is applied price data, which is the
        // same kind of analysis as charts and BQL.
        assertFalse(Feature.entries.any { it.name == "IMPORT" || it.name == "EXPORT" })
        assertFalse(Feature.entries.any { it.name == "BACKUP" || it.name == "RESTORE" })
    }

    @Test
    fun `each tier unlocks its own features and everything below it`() {
        assertTrue(keyed(Tier.INSIGHT).allows(Feature.FAVA_REPORTS))
        assertFalse(keyed(Tier.INSIGHT).allows(Feature.DOCUMENTS))
        assertFalse(keyed(Tier.INSIGHT).allows(Feature.PLUGINS))

        assertTrue(keyed(Tier.WORKFLOW).allows(Feature.FAVA_REPORTS))
        assertTrue(keyed(Tier.WORKFLOW).allows(Feature.DOCUMENTS))
        assertFalse(keyed(Tier.WORKFLOW).allows(Feature.PLUGINS))

        Feature.entries.forEach { feature ->
            assertTrue(keyed(Tier.POWER).allows(feature), "Power must include ${feature.name}")
        }
    }

    @Test
    fun `the free tier allows no gated feature at all`() {
        Feature.entries.forEach { feature ->
            assertFalse(Entitlement.FREE.allows(feature), "${feature.name} leaked to free")
        }
    }

    @Test
    fun `a key keeps working after the trial ends`() {
        val storage = InMemoryStorage()
        val entitlements = Entitlements(storage)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        entitlements.onAppStart(start)
        entitlements.applyKey(StoredKey(Tier.INSIGHT, "b@e.com", "PP-1", "BCA1.x.y"))

        val afterTrial = entitlements.current(start.plus(Duration.ofDays(45)))

        assertEquals(Tier.INSIGHT, afterTrial.tier)
        assertTrue(afterTrial.allows(Feature.FAVA_REPORTS))
        assertFalse(afterTrial.allows(Feature.PLUGINS), "a Tier 1 key must not become Tier 3")
    }

    @Test
    fun `the trial never downgrades someone who already holds a higher key`() {
        val storage = InMemoryStorage()
        val entitlements = Entitlements(storage)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        entitlements.onAppStart(start)
        entitlements.applyKey(StoredKey(Tier.POWER, "b@e.com", "PP-1", "BCA1.x.y"))

        val during = entitlements.current(start.plus(Duration.ofDays(5)))
        val after = entitlements.current(start.plus(Duration.ofDays(500)))

        assertEquals(Tier.POWER, during.tier)
        assertEquals(Tier.POWER, after.tier)
        assertTrue(after.grant is Grant.Key, "the reason shown must be the key, not the trial")
    }

    @Test
    fun `removing a key falls back to what is left, and takes nothing else away`() {
        val storage = InMemoryStorage()
        val entitlements = Entitlements(storage)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        entitlements.onAppStart(start)
        entitlements.applyKey(StoredKey(Tier.POWER, "b@e.com", "PP-1", "BCA1.x.y"))

        entitlements.removeKey()
        val after = entitlements.current(start.plus(Duration.ofDays(500)))

        assertEquals(Tier.FREE, after.tier)
        assertTrue(after.grant is Grant.TrialEnded)
        assertTrue(storage.readTrial() != null, "removing a key must not disturb the trial record")
    }

    @Test
    fun `there is exactly one purchase route, and it takes no money in the app`() {
        // Exhaustive rather than reflective: a second implementation of PurchaseRoute stops
        // this `when` compiling, so a billing decision cannot arrive as a quiet addition.
        val route: PurchaseRoute = PurchaseRoute.EnterKeyOnly
        val name = when (route) {
            PurchaseRoute.EnterKeyOnly -> "EnterKeyOnly"
        }

        assertEquals("EnterKeyOnly", name)
        assertEquals("Enter key", route.label)
        assertFalse(route.transactsInApp, "no route may take money inside the app")
        assertFalse(route.linksOut, "no route may steer the user to a payment page")
    }

    private class InMemoryStorage : EntitlementStorage {
        private var trial: TrialRecord? = null
        private var key: StoredKey? = null
        override fun readTrial(): TrialRecord? = trial
        override fun writeTrial(record: TrialRecord) { trial = record }
        override fun readKey(): StoredKey? = key
        override fun writeKey(key: StoredKey?) { this.key = key }
    }
}
