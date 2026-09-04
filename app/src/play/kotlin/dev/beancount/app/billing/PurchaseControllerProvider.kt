package dev.beancount.app.billing

import android.content.Context
import dev.beancount.core.entitlement.Entitlements
import dev.beancount.core.entitlement.PurchaseRoute

/** Play edition: contributions are taken through Google Play's billing system. */
internal fun currentPurchaseController(
    context: Context,
    entitlements: Entitlements,
    onEntitlementChanged: () -> Unit,
): PurchaseController = PlayPurchaseController(context, entitlements, onEntitlementChanged)

/** The route this edition offers. */
internal fun currentPurchaseRoute(): PurchaseRoute = PurchaseRoute.PlayBilling
