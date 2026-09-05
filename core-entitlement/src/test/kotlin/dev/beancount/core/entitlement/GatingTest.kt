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
    fun `every purchase route is accounted for, and none of them links out`() {
        // Exhaustive rather than reflective: a third implementation of PurchaseRoute stops
        // this `when` compiling, so a billing decision still cannot arrive as a quiet
        // addition. It arrived as a deliberate one when Play Billing was adopted, and this
        // test changed with it rather than being deleted.
        val routes = listOf<PurchaseRoute>(PurchaseRoute.EnterKeyOnly, PurchaseRoute.PlayBilling)

        val names = routes.map { route ->
            when (route) {
                PurchaseRoute.EnterKeyOnly -> "EnterKeyOnly"
                PurchaseRoute.PlayBilling -> "PlayBilling"
            }
        }
        assertEquals(listOf("EnterKeyOnly", "PlayBilling"), names)

        // The rule that survived the change: anti-steering. Play forbids directing users to
        // another payment method from inside the app, and the Expert build matches it so the
        // two editions differ only where policy forces them to.
        routes.forEach { route ->
            assertFalse(route.linksOut, "${route.label} may not steer the user to a payment page")
        }

        assertFalse(PurchaseRoute.EnterKeyOnly.transactsInApp, "pasting a key takes no money")
        assertTrue(PurchaseRoute.PlayBilling.transactsInApp, "Play Billing is the route that does")
    }

    @Test
    fun `a store purchase grants its tier, and says it was a purchase`() {
        val storage = InMemoryStorage()
        val entitlements = Entitlements(storage)
        entitlements.applyPurchase(StoredPurchase(Tier.WORKFLOW, "GPA.1234-5678"))

        val now = Instant.parse("2026-01-01T00:00:00Z")
        val current = entitlements.current(now)

        assertEquals(Tier.WORKFLOW, current.tier)
        assertEquals(Grant.Purchase(Tier.WORKFLOW, "GPA.1234-5678"), current.grant)
    }

    @Test
    fun `the better of a key and a purchase wins, whichever it is`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")

        val purchaseHigher = Entitlements(InMemoryStorage()).apply {
            applyKey(StoredKey(Tier.INSIGHT, "a@example.com", "PP-1", "BCA1.x.y"))
            applyPurchase(StoredPurchase(Tier.POWER, "GPA.9"))
        }.current(now)
        assertEquals(Tier.POWER, purchaseHigher.tier)
        assertTrue(purchaseHigher.grant is Grant.Purchase)

        val keyHigher = Entitlements(InMemoryStorage()).apply {
            applyKey(StoredKey(Tier.POWER, "a@example.com", "PP-1", "BCA1.x.y"))
            applyPurchase(StoredPurchase(Tier.INSIGHT, "GPA.9"))
        }.current(now)
        assertEquals(Tier.POWER, keyHigher.tier)
        assertTrue(keyHigher.grant is Grant.Key)
    }

    /**
     * A refund, or a purchase the store no longer reports, drops the tier back.
     *
     * The store is the authority. What must not happen is the opposite: `applyPurchase(null)`
     * is only ever called with the store's actual answer, never because it could not be
     * reached, which is why being offline leaves the cached purchase alone.
     */
    @Test
    fun `a purchase the store stops reporting is given up`() {
        val storage = InMemoryStorage()
        val entitlements = Entitlements(storage)
        val now = Instant.parse("2026-01-01T00:00:00Z")

        entitlements.applyPurchase(StoredPurchase(Tier.POWER, "GPA.9"))
        assertEquals(Tier.POWER, entitlements.current(now).tier)

        entitlements.applyPurchase(null)

        assertEquals(Tier.FREE, entitlements.current(now).tier)
    }

    /** A running trial still beats a smaller purchase, and neither is destroyed by the other. */
    @Test
    fun `a trial outranks a lower purchase without replacing it`() {
        val storage = InMemoryStorage()
        val entitlements = Entitlements(storage)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        entitlements.onAppStart(start)
        entitlements.applyPurchase(StoredPurchase(Tier.INSIGHT, "GPA.9"))

        val during = entitlements.current(start.plus(Duration.ofDays(1)))
        assertEquals(Tier.POWER, during.tier, "the trial unlocks everything while it runs")
        assertTrue(during.grant is Grant.Trial)

        val after = entitlements.current(start.plus(Duration.ofDays(400)))
        assertEquals(Tier.INSIGHT, after.tier, "the purchase is still there once the trial ends")
        assertTrue(after.grant is Grant.Purchase)
    }

    private class InMemoryStorage : EntitlementStorage {
        private var trial: TrialRecord? = null
        private var key: StoredKey? = null
        private var purchase: StoredPurchase? = null
        override fun readTrial(): TrialRecord? = trial
        override fun writeTrial(record: TrialRecord) { trial = record }
        override fun readKey(): StoredKey? = key
        override fun writeKey(key: StoredKey?) { this.key = key }
        override fun readPurchase(): StoredPurchase? = purchase
        override fun writePurchase(purchase: StoredPurchase?) { this.purchase = purchase }
    }
}
