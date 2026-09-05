package io.github.pintofi.beanbook.core.entitlement

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

    /**
     * Balances at cost and at market value, beside the plain units (B14).
     *
     * Tier 1 rather than an edition difference. It was asked for as "Expert edition only",
     * but the editions have feature parity by design and the only differences permitted are
     * the ones Play policy forces (CLAUDE.md). Showing what a holding cost is not a policy
     * matter, so gating it on the flavor would break that rule for no reason. It is the same
     * kind of analytical feature as charts and BQL, so it sits with them.
     */
    HOLDINGS_VALUATION(Tier.INSIGHT),

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

    /**
     * A purchase made through the store the app was installed from.
     *
     * Distinct from [Key] rather than folded into it, because the two differ in ways the
     * user can see: a store purchase is restored by the store on a new device and is
     * refunded through the store, while a key is a file they hold and can paste anywhere.
     * The Contribute screen says which one is in force, and telling them apart is the only
     * way it can.
     *
     * There is no email here. A store purchase does not give the app one, and inventing a
     * placeholder would put a fake address on a screen that shows it.
     */
    data class Purchase(val tier: Tier, val orderId: String) : Grant
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
        val purchase = storage.readPurchase()
        val trialGrant = policy.evaluate(storage.readTrial(), now)
        val trialTier = if (trialGrant is Grant.Trial) policy.tier else Tier.FREE

        // The best of the three, never a fight between them. Someone who bought Tier 1 in
        // the store and later pasted a Tier 3 key gets Tier 3, and an expiring trial can
        // still not take away anything paid for.
        //
        // A key wins ties with a purchase only because it has to be decided somehow; at
        // equal tiers the entitlement is identical and only the wording differs.
        val paid: Grant? = when {
            key != null && purchase != null ->
                if (purchase.tier.level > key.tier.level) {
                    Grant.Purchase(purchase.tier, purchase.orderId)
                } else {
                    Grant.Key(key.tier, key.email, key.orderId)
                }
            key != null -> Grant.Key(key.tier, key.email, key.orderId)
            purchase != null -> Grant.Purchase(purchase.tier, purchase.orderId)
            else -> null
        }
        val paidTier = when (paid) {
            is Grant.Key -> paid.tier
            is Grant.Purchase -> paid.tier
            else -> Tier.FREE
        }

        return when {
            paid != null && paidTier.level >= trialTier.level -> Entitlement(paidTier, paid)
            trialGrant is Grant.Trial -> Entitlement(trialTier, trialGrant)
            paid != null -> Entitlement(paidTier, paid)
            else -> Entitlement(Tier.FREE, trialGrant)
        }
    }

    /** Stores a verified key. Verification itself is M7's job, not this class's. */
    fun applyKey(key: StoredKey) = storage.writeKey(key)

    fun removeKey() = storage.writeKey(null)

    /**
     * Records what the store says is owned, or `null` when it says nothing is.
     *
     * Called with the store's answer, not with a guess: passing `null` because the store
     * could not be reached would revoke a paid tier over a bad connection.
     */
    fun applyPurchase(purchase: StoredPurchase?) = storage.writePurchase(purchase)
}

/**
 * A store purchase the app has seen, cached so entitlement survives being offline.
 *
 * The store remains the authority: this is refreshed whenever the store can be reached, and
 * a purchase that has been refunded or is no longer owned clears it. Caching only decides
 * what happens between those checks, and it errs towards the user keeping what they paid
 * for rather than losing it in a tunnel.
 */
data class StoredPurchase(val tier: Tier, val orderId: String)

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
    fun readPurchase(): StoredPurchase?
    fun writePurchase(purchase: StoredPurchase?)
}
