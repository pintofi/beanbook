package dev.beancount.app.billing

import android.content.Context
import dev.beancount.core.entitlement.Entitlements
import dev.beancount.core.entitlement.PurchaseRoute

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
