package io.github.pintofi.beanbook.app.about

/**
 * What differs between editions on the screens that talk about the app itself.
 *
 * **No donation link in the Play edition (B81).** Google Play's Payments policy says apps "may
 * not lead users to a payment method other than Google Play's billing system", and the only
 * donations it exempts are "tax exempt donations". A tip to an individual developer through
 * Ko-fi is not one, so this edition names no support page at all. Whether to offer support
 * through Play's own billing instead is recorded as a decision to make (N21).
 */
internal object EditionFacts {
    const val NAME = "Play"

    /** Where voluntary support goes. None here, by policy. */
    val SUPPORT_URL: String? = null

    /** No support pages here, by policy. */
    val SUPPORT_LINKS: List<SupportLink> = emptyList()

    /** The other edition, described for people who might want it (B81). */
    val OTHER_EDITION: OtherEdition? = OtherEdition(
        name = "Expert",
        summary = "A separate edition of Beanbook, installed from outside Google Play, for " +
            "people who want more control. Your ledger is a folder of plain files, so it " +
            "opens unchanged in either edition.",
        differences = listOf(
            "Plugins, Fava extensions and scripts may use Python packages with native code",
            "Missing Python packages can be fetched when you ask for them",
            "Plugins and scripts can use Android's own features",
        ),
        url = EDITIONS_URL,
    )
}
