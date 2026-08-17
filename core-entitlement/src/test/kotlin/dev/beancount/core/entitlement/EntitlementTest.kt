package dev.beancount.core.entitlement

import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 30-day trial and how it interacts with contribution keys.
 *
 * The rule the tests exist to protect: when the trial ends, **nothing is taken away except
 * the paid features**. The ledger stays, editing stays, export stays.
 */
class EntitlementTest {

    private val dir: File = Files.createTempDirectory("entitlement").toFile()
    private val storage = FileEntitlementStorage(dir)
    private val entitlements = Entitlements(storage)

    private val install = Instant.parse("2026-09-01T10:00:00Z")
    private fun daysLater(days: Long, hours: Long = 0) =
        install.plus(Duration.ofDays(days)).plus(Duration.ofHours(hours))

    @AfterTest
    fun cleanUp() = dir.deleteRecursively().let { }

    // --- the trial --------------------------------------------------------------------

    @Test
    fun `a fresh install starts a 30-day trial with everything unlocked`() {
        val entitlement = entitlements.onAppStart(install)

        assertEquals(Tier.POWER, entitlement.tier)
        assertIs<Grant.Trial>(entitlement.grant)
        assertEquals(30, entitlement.trialDaysRemaining)
        Feature.entries.forEach { assertTrue(entitlement.allows(it), "trial must unlock $it") }
    }

    @Test
    fun `the trial is still running on day 29 and over on day 31`() {
        entitlements.onAppStart(install)

        val day29 = entitlements.current(daysLater(29))
        assertEquals(Tier.POWER, day29.tier)
        // Exactly one full day left, which is what the UI should say.
        assertEquals(1, day29.trialDaysRemaining)

        val day31 = entitlements.current(daysLater(31))
        assertEquals(Tier.FREE, day31.tier)
        assertIs<Grant.TrialEnded>(day31.grant)
    }

    @Test
    fun `the boundary is the moment 30 days after install, not a day either side`() {
        entitlements.onAppStart(install)

        assertEquals(Tier.POWER, entitlements.current(daysLater(29, hours = 23)).tier)
        assertEquals(Tier.FREE, entitlements.current(daysLater(30, hours = 1)).tier)
    }

    @Test
    fun `when the trial ends the free features still work and nothing is destroyed`() {
        entitlements.onAppStart(install)
        val after = entitlements.current(daysLater(45))

        // Paid features stop.
        assertFalse(after.allows(Feature.FAVA_REPORTS))
        assertFalse(after.allows(Feature.AUTO_MIRROR))
        assertFalse(after.allows(Feature.PLUGINS))

        // Everything the free tier promises keeps working. These are absent from the
        // Feature enum by design — entries, the journal, balances, raw editing, and all
        // manual import/export/backup/restore are never gateable at all.
        assertEquals(Tier.FREE, after.tier)
        assertTrue(
            Feature.entries.none { it.name.contains("EXPORT") || it.name.contains("IMPORT_PROJECT") },
            "data-in and data-out must not be gateable features",
        )
    }

    @Test
    fun `the trial survives a restart because it is on disk`() {
        entitlements.onAppStart(install)

        val restarted = Entitlements(FileEntitlementStorage(dir))
        val entitlement = restarted.onAppStart(daysLater(5))

        assertEquals(Tier.POWER, entitlement.tier)
        assertEquals(25, entitlement.trialDaysRemaining)
    }

    @Test
    fun `winding the clock back does not extend the trial`() {
        entitlements.onAppStart(install)
        entitlements.onAppStart(daysLater(29))      // the app has seen day 29

        // User sets the clock back to install day.
        val rolledBack = entitlements.current(install)
        assertEquals(1, rolledBack.trialDaysRemaining, "rollback must not buy extra days")

        entitlements.onAppStart(daysLater(31))
        assertEquals(Tier.FREE, entitlements.current(install).tier, "the trial stays over")
    }

    @Test
    fun `no trial record means the free tier, not an accidental unlock`() {
        assertEquals(Tier.FREE, entitlements.current(install).tier)
        assertIs<Grant.Free>(entitlements.current(install).grant)
    }

    // --- keys -------------------------------------------------------------------------

    @Test
    fun `a key keeps working after the trial ends`() {
        entitlements.onAppStart(install)
        entitlements.applyKey(StoredKey(Tier.WORKFLOW, "buyer@example.com", "PP-1", "BCA1.x.y"))

        val after = entitlements.current(daysLater(60))

        assertEquals(Tier.WORKFLOW, after.tier)
        assertIs<Grant.Key>(after.grant)
        assertTrue(after.allows(Feature.DOCUMENTS))
        assertTrue(after.allows(Feature.FAVA_REPORTS), "tiers are cumulative")
        assertFalse(after.allows(Feature.REMOTE_MODE), "Tier 3 stays locked for a Tier 2 key")
    }

    @Test
    fun `an expiring trial never downgrades someone who has paid`() {
        entitlements.onAppStart(install)
        entitlements.applyKey(StoredKey(Tier.INSIGHT, "buyer@example.com", "PP-2", "BCA1.x.y"))

        // During the trial the user has everything…
        assertEquals(Tier.POWER, entitlements.current(daysLater(3)).tier)
        // …and afterwards exactly what they paid for, never less.
        assertEquals(Tier.INSIGHT, entitlements.current(daysLater(31)).tier)
    }

    @Test
    fun `the key's email is available so Settings can show whose key it is`() {
        entitlements.applyKey(StoredKey(Tier.POWER, "buyer@example.com", "PP-3", "BCA1.x.y"))
        val grant = entitlements.current(install).grant

        assertIs<Grant.Key>(grant)
        assertEquals("buyer@example.com", grant.email)
    }

    @Test
    fun `a key survives a restart and can be removed`() {
        entitlements.applyKey(StoredKey(Tier.POWER, "buyer@example.com", "PP-4", "BCA1.x.y"))

        assertEquals(Tier.POWER, Entitlements(FileEntitlementStorage(dir)).current(install).tier)

        entitlements.removeKey()
        assertNull(FileEntitlementStorage(dir).readKey())
        assertEquals(Tier.FREE, entitlements.current(daysLater(60)).tier)
    }

    @Test
    fun `entitlement state lives outside the project tree`() {
        entitlements.onAppStart(install)
        entitlements.applyKey(StoredKey(Tier.POWER, "buyer@example.com", "PP-5", "BCA1.x.y"))

        // Both files are in the directory we were given, which the workspace keeps apart
        // from projects/ — so they can never be exported with a ledger.
        assertTrue(File(dir, "trial").isFile)
        assertTrue(File(dir, "key").isFile)
        assertFalse(dir.path.contains("projects"))
    }
}
