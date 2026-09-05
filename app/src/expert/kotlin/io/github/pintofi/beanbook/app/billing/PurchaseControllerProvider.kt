package io.github.pintofi.beanbook.app.billing

import android.content.Context
import io.github.pintofi.beanbook.core.entitlement.Entitlements
import io.github.pintofi.beanbook.core.entitlement.PurchaseRoute

/**
 * Expert edition: nothing to buy here.
 *
 * The Expert build is not distributed through Google Play, so Play Billing would have no
 * store to talk to. Contributions arrive as a signed key the user pastes in, which is what
 * the Contribute screen offers when this returns [PurchaseController.Unavailable].
 */
internal fun currentPurchaseController(
    context: Context,
    entitlements: Entitlements,
    onEntitlementChanged: () -> Unit,
): PurchaseController = PurchaseController.Unavailable

/** The route this edition offers. */
internal fun currentPurchaseRoute(): PurchaseRoute = PurchaseRoute.EnterKeyOnly
