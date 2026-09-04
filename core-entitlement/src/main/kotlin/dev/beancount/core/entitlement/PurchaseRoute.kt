package dev.beancount.core.entitlement

/**
 * How a contribution can be made — the seam that keeps a billing decision out of the app's
 * architecture (docs/distribution.md §4.2).
 *
 * Two routes, one per edition, and the edition decides which is in force:
 *
 * * **Play** uses [PlayBilling]. Google Play's Payments policy requires its billing system
 *   for unlocking "app functionality or content", and a key bought elsewhere but consumed
 *   inside a Play-distributed app is not covered by the exemption for goods consumed outside
 *   it. Taking the money through Play is the compliant answer, not a workaround.
 * * **Expert** keeps [EnterKeyOnly]. It is not distributed through Play, Play Billing would
 *   have nothing to talk to, and a signed key the user holds is the right shape there.
 *
 * What both share: neither may **link out** to a payment page from inside the app. For Play
 * that is the anti-steering rule; for Expert it is consistency, since two builds that differ
 * in more than policy requires stop being the same product.
 *
 * `:core-entitlement` still knows nothing about any billing library. It names the route and
 * stops there; the Play flavor supplies the implementation.
 */
sealed interface PurchaseRoute {

    /** What the Contribute screen offers. */
    val label: String

    /** True only for a route that takes money inside the app. Nothing does today. */
    val transactsInApp: Boolean

    /**
     * Whether the UI may show a way out to a payment page. False in both editions: Play's
     * anti-steering rules restrict directing users to alternative payment from inside the
     * app, and shipping one edition that links out and one that does not would make the two
     * builds differ in more than policy compliance.
     */
    val linksOut: Boolean

    /** Paste a key bought outside the app. The Expert edition's route. */
    data object EnterKeyOnly : PurchaseRoute {
        override val label: String = "Enter key"
        override val transactsInApp: Boolean = false
        override val linksOut: Boolean = false
    }

    /**
     * Buy a tier through Google Play. The Play edition's route.
     *
     * Three one-time products, one per tier, rather than subscriptions: the tiers are a
     * one-off contribution and billing someone monthly for a local accounting app would be
     * a different product than the one described everywhere else.
     */
    data object PlayBilling : PurchaseRoute {
        override val label: String = "Contribute"
        override val transactsInApp: Boolean = true
        override val linksOut: Boolean = false
    }
}
