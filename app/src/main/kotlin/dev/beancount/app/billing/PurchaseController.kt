package dev.beancount.app.billing

import android.app.Activity
import dev.beancount.core.entitlement.Tier

/**
 * What the Contribute screen can do about money, without knowing how it is taken.
 *
 * The shared UI is compiled into both editions, so it cannot mention a billing library. It
 * asks this instead, and each flavor supplies an implementation: Play talks to Google Play's
 * billing system, Expert reports that there is nothing to buy here and leaves the screen on
 * its key field.
 *
 * The boundary gate enforces the half of this that matters — `BillingClient` may appear in
 * `src/play` and nowhere else (M1 in gradle/play-boundaries.gradle.kts).
 */
interface PurchaseController {

    /**
     * Whether buying is possible right now.
     *
     * False in the Expert edition always, and false in the Play edition when the billing
     * service is unavailable — an old Play Store, a device without it, a user profile that
     * cannot transact. The screen shows what is owned either way rather than an error.
     */
    val available: Boolean

    /** What can be bought, cheapest first, with prices as the store formats them. */
    val offers: List<TierOffer>

    /**
     * Starts the store's purchase flow for [tier].
     *
     * Everything after this is the store's: the price, the payment method, the receipt. The
     * result arrives asynchronously and is applied by refreshing entitlement, not by
     * trusting the call that started it.
     */
    fun buy(activity: Activity, tier: Tier)

    /**
     * Asks the store what is owned and writes the answer through to entitlement.
     *
     * Called on start and whenever the Contribute screen is opened. It is also how a refund
     * takes effect and how a purchase follows the user to a new device.
     */
    fun refresh()

    /** A tier that can be bought, with the store's own localised price. */
    data class TierOffer(val tier: Tier, val formattedPrice: String)

    /** The Expert edition's answer, and the fallback when billing cannot start. */
    object Unavailable : PurchaseController {
        override val available: Boolean = false
        override val offers: List<TierOffer> = emptyList()
        override fun buy(activity: Activity, tier: Tier) = Unit
        override fun refresh() = Unit
    }
}
