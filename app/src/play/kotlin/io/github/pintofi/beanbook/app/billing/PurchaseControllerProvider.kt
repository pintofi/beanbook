package io.github.pintofi.beanbook.app.billing

import android.content.Context
import io.github.pintofi.beanbook.core.entitlement.Entitlements
import io.github.pintofi.beanbook.core.entitlement.PurchaseRoute

/** Play edition: contributions are taken through Google Play's billing system. */
internal fun currentPurchaseController(
    context: Context,
    entitlements: Entitlements,
    onEntitlementChanged: () -> Unit,
): PurchaseController = PlayPurchaseController(context, entitlements, onEntitlementChanged)

/** The route this edition offers. */
internal fun currentPurchaseRoute(): PurchaseRoute = PurchaseRoute.PlayBilling
