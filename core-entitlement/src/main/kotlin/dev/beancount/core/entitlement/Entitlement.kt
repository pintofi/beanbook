package dev.beancount.core.entitlement

import java.time.Duration
import java.time.Instant

/**
 * What the user has unlocked (docs/distribution.md §4.4).
 *
 * Tiers are cumulative: Tier 3 includes Tier 2, which includes Tier 1.
 */
enum class Tier(val level: Int, val label: String) {
    FREE(0, "Free"),
    INSIGHT(1, "Insight"),
    WORKFLOW(2, "Workflow"),
    POWER(3, "Power"),
    ;

    fun includes(other: Tier): Boolean = level >= other.level

    companion object {
        fun ofLevel(level: Int): Tier = entries.firstOrNull { it.level == level } ?: FREE
    }
}

/**
 * Every gateable capability, with the tier that unlocks it.
 *
 * Anything absent from this list is free, permanently: adding and editing entries, the
 * journal, the account balances list, raw file editing, and **all manual import, export,
 * backup and restore**. Data-in, data-out and recovery are never paywalled.
 */
enum class Feature(val required: Tier) {
    // Tier 1 — Insight
    FAVA_REPORTS(Tier.INSIGHT),
    CHARTS(Tier.INSIGHT),
    BQL_QUERIES(Tier.INSIGHT),

    // Tier 2 — Workflow
    DOCUMENTS(Tier.WORKFLOW),
    CAMERA_CAPTURE(Tier.WORKFLOW),
    PDF_ASSEMBLY(Tier.WORKFLOW),
    OCR(Tier.WORKFLOW),
    WIDGETS(Tier.WORKFLOW),
    AUTO_MIRROR(Tier.WORKFLOW),
    SCHEDULED_BACKUP(Tier.WORKFLOW),

    // Tier 3 — Power
    REMOTE_MODE(Tier.POWER),
    STATEMENT_IMPORTERS(Tier.POWER),
    SCRIPTS(Tier.POWER),
    PLUGINS(Tier.POWER),
    FAVA_EXTENSIONS(Tier.POWER),
    ;
}

/** Why the current tier is what it is — the UI says this out loud rather than implying it. */
sealed interface Grant {
    /** No key, no trial: the permanent free tier. */
    data object Free : Grant

    /** The first-run trial. Everything is unlocked until [endsAt]. */
    data class Trial(val endsAt: Instant, val remaining: Duration) : Grant

    /** The trial has ended. Nothing was taken away except the paid features. */
    data class TrialEnded(val endedAt: Instant) : Grant

    /** A contribution key. [email] is shown in Settings so the user can identify it. */
    data class Key(val tier: Tier, val email: String, val orderId: String) : Grant
}

/**
 * The user's current entitlement: a tier, and the reason for it.
 */
data class Entitlement(val tier: Tier, val grant: Grant) {

    fun allows(feature: Feature): Boolean = tier.includes(feature.required)

    /** Days left in the trial, rounded down; null when no trial is running. */
    val trialDaysRemaining: Int?
        get() = (grant as? Grant.Trial)?.remaining?.toDays()?.toInt()

    companion object {
        val FREE = Entitlement(Tier.FREE, Grant.Free)
    }
}

/**
 * The first-run trial: **30 days of everything**, then the free tier — with all data
 * retained and every free feature still working (the model My Expenses uses).
 *
 * Nothing is deleted, disabled destructively, or held hostage when it ends: the user keeps
 * their ledger, keeps editing it, and keeps exporting it. Only the paid features stop.
 */
class TrialPolicy(
    val duration: Duration = Duration.ofDays(30),
    val tier: Tier = Tier.POWER,
) {

    fun evaluate(record: TrialRecord?, now: Instant): Grant {
        if (record == null) return Grant.Free

        val endsAt = record.startedAt.plus(duration)

        // Clock moved backwards (timezone change, manual adjustment, a restored backup):
        // use the furthest point in time we have ever seen instead, so winding the clock
        // back cannot extend a trial. Winding it *forward* ends the trial early, which is
        // the user's own doing and not worth fighting.
        val effectiveNow = maxOf(now, record.lastSeenAt)

        return if (effectiveNow.isBefore(endsAt)) {
            Grant.Trial(endsAt = endsAt, remaining = Duration.between(effectiveNow, endsAt))
        } else {
            Grant.TrialEnded(endsAt)
        }
    }
}

/** The durable trial state. Deliberately tiny: two timestamps. */
data class TrialRecord(val startedAt: Instant, val lastSeenAt: Instant)

/**
 * Resolves the effective entitlement from the trial and any key.
 *
 * The two never fight: the user always gets the **better** of the two, so an expiring trial
 * can never downgrade someone who has paid, and a Tier 1 key still gets everything while the
 * trial is running.
 */
class Entitlements(
    private val storage: EntitlementStorage,
    private val policy: TrialPolicy = TrialPolicy(),
) {

    /**
     * Called on every app start. Begins the trial the first time, and records that the app
     * was seen now (which is what makes clock rollback ineffective).
     */
    fun onAppStart(now: Instant): Entitlement {
        val existing = storage.readTrial()
        val record = if (existing == null) {
            TrialRecord(startedAt = now, lastSeenAt = now)
        } else {
            existing.copy(lastSeenAt = maxOf(existing.lastSeenAt, now))
        }
        storage.writeTrial(record)
        return current(now)
    }

    fun current(now: Instant): Entitlement {
        val key = storage.readKey()
        val trialGrant = policy.evaluate(storage.readTrial(), now)
        val trialTier = if (trialGrant is Grant.Trial) policy.tier else Tier.FREE

        return when {
            key != null && key.tier.level >= trialTier.level ->
                Entitlement(key.tier, Grant.Key(key.tier, key.email, key.orderId))

            trialGrant is Grant.Trial -> Entitlement(trialTier, trialGrant)

            key != null -> Entitlement(key.tier, Grant.Key(key.tier, key.email, key.orderId))

            else -> Entitlement(Tier.FREE, trialGrant)
        }
    }

    /** Stores a verified key. Verification itself is M7's job, not this class's. */
    fun applyKey(key: StoredKey) = storage.writeKey(key)

    fun removeKey() = storage.writeKey(null)
}

/** A contribution key that has already been verified. */
data class StoredKey(
    val tier: Tier,
    val email: String,
    val orderId: String,
    val raw: String,
)

/**
 * Persistence for entitlement state.
 *
 * Note what is *not* here: no network, no licence server, no device identifier. Keys are
 * verified offline (docs/distribution.md §4.5).
 */
interface EntitlementStorage {
    fun readTrial(): TrialRecord?
    fun writeTrial(record: TrialRecord)
    fun readKey(): StoredKey?
    fun writeKey(key: StoredKey?)
}
