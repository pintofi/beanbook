package dev.beancount.core.entitlement

/**
 * How a contribution can be made — the seam that keeps a billing decision out of the app's
 * architecture (docs/distribution.md §4.2).
 *
 * There is exactly one implementation today, [EnterKeyOnly], and it is the same in both
 * editions: keys are bought on the website, in a browser, and pasted in. The app contains no
 * billing SDK, no purchase flow, and — in the `play` build — no link to any payment method.
 *
 * The interface exists because the residual risk is a *review* risk, not a technical one. If
 * Google ever objects to externally-purchased keys, adding a Play Billing route is a new
 * implementation of this interface in the play flavor; `:core-entitlement` does not change,
 * and neither does anything that gates a feature.
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

    /** Paste a key bought outside the app. The only route that exists. */
    data object EnterKeyOnly : PurchaseRoute {
        override val label: String = "Enter key"
        override val transactsInApp: Boolean = false
        override val linksOut: Boolean = false
    }
}
