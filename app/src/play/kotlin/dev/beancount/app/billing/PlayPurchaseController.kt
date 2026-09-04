package dev.beancount.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dev.beancount.core.entitlement.Entitlements
import dev.beancount.core.entitlement.StoredPurchase
import dev.beancount.core.entitlement.Tier

/**
 * Google Play's billing system, for the play edition only.
 *
 * Three one-time products, one per tier. Not subscriptions: the tiers are a one-off
 * contribution, and billing someone monthly for an accounting app that runs entirely on
 * their own device would be a different product than the one described everywhere else.
 *
 * **The store is the authority, and this class never decides on its own.** It asks what is
 * owned, writes that answer through to [Entitlements], and lets the ordinary resolution
 * decide the tier. A purchase that is refunded stops being reported and the tier drops; a
 * purchase made on another device is reported here and the tier appears.
 *
 * What it deliberately does not do: verify receipts against a server. There is no server,
 * which is the whole design. Someone determined can already build the app from source
 * without paying, and the honest framing on the Contribute screen says as much
 * (docs/distribution.md 4.7), so a licence check that pretends otherwise would buy nothing.
 */
internal class PlayPurchaseController(
    context: Context,
    private val entitlements: Entitlements,
    private val onEntitlementChanged: () -> Unit,
) : PurchaseController {

    private val productForTier = mapOf(
        Tier.INSIGHT to PRODUCT_INSIGHT,
        Tier.WORKFLOW to PRODUCT_WORKFLOW,
        Tier.POWER to PRODUCT_POWER,
    )
    private val tierForProduct = productForTier.entries.associate { (k, v) -> v to k }

    @Volatile private var details: Map<String, ProductDetails> = emptyMap()
    @Volatile private var connected = false

    override val available: Boolean get() = connected

    override val offers: List<PurchaseController.TierOffer>
        get() = productForTier.entries
            .sortedBy { it.key.level }
            .mapNotNull { (tier, productId) ->
                val price = details[productId]
                    ?.oneTimePurchaseOfferDetails
                    ?.formattedPrice
                    ?: return@mapNotNull null
                PurchaseController.TierOffer(tier, price)
            }

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        // Every outcome, not only success: a cancelled flow has to leave the screen in a
        // sane state rather than waiting forever for something that will not arrive.
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach(::acknowledgeIfNeeded)
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.w(TAG, "purchase flow ended: ${result.responseCode} ${result.debugMessage}")
        }
        // Reconcile from the store either way rather than trusting this callback's payload.
        refresh()
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdated)
        // One-time products can be pending (cash at a kiosk, a parent's approval), and
        // Billing 9 requires saying so explicitly. `bestOf` still refuses to grant on a
        // pending purchase; this only allows them to exist rather than erroring.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    init {
        connect()
    }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connected = result.responseCode == BillingClient.BillingResponseCode.OK
                if (connected) {
                    loadProductDetails()
                    refresh()
                } else {
                    Log.w(TAG, "billing unavailable: ${result.responseCode} ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // Not retried in a loop. The next refresh reconnects, and a device without a
                // working Play Store should show "nothing to buy here" rather than spin.
                connected = false
            }
        })
    }

    private fun loadProductDetails() {
        val products = productForTier.values.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
        ) { result, queried ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                details = queried.productDetailsList.associateBy { it.productId }
            } else {
                Log.w(TAG, "product details unavailable: ${result.debugMessage}")
            }
        }
    }

    override fun buy(activity: Activity, tier: Tier) {
        val product = details[productForTier[tier]] ?: run {
            Log.w(TAG, "no product details for $tier yet")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun refresh() {
        if (!connected) {
            connect()
            return
        }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Do **not** clear the entitlement here. A failed query means the store could
                // not be asked, not that nothing is owned, and revoking a paid tier because
                // of a bad connection is the worst thing this class could do.
                Log.w(TAG, "could not read purchases: ${result.debugMessage}")
                return@queryPurchasesAsync
            }
            purchases.forEach(::acknowledgeIfNeeded)
            entitlements.applyPurchase(bestOf(purchases))
            // The tier may have just changed, so tell the UI rather than waiting for a
            // relaunch: someone who has paid should see it immediately.
            onEntitlementChanged()
        }
    }

    /**
     * The highest tier actually owned, or `null` when the store reports nothing.
     *
     * Only `PURCHASED` counts. A pending purchase — cash at a kiosk, a parent's approval —
     * is not yet paid for, and granting on it would hand out tiers that may never complete.
     */
    private fun bestOf(purchases: List<Purchase>): StoredPurchase? =
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .flatMap { purchase -> purchase.products.mapNotNull(tierForProduct::get).map { it to purchase } }
            .maxByOrNull { it.first.level }
            ?.let { (tier, purchase) -> StoredPurchase(tier, purchase.orderId.orEmpty()) }

    /**
     * Acknowledges a completed purchase.
     *
     * Google refunds any purchase that is not acknowledged within three days, so this is not
     * housekeeping: skipping it takes the money and then gives it back, leaving a user who
     * paid without the tier they bought.
     */
    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build(),
        ) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: ${result.debugMessage}")
            }
        }
    }

    private companion object {
        const val TAG = "PlayBilling"

        // Product ids as created in the Play Console. Changing one after release strands
        // everyone who bought it, so they are written once here and nowhere else.
        const val PRODUCT_INSIGHT = "tier_insight"
        const val PRODUCT_WORKFLOW = "tier_workflow"
        const val PRODUCT_POWER = "tier_power"
    }
}
