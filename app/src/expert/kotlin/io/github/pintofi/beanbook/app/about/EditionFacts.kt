package io.github.pintofi.beanbook.app.about

/**
 * What differs between editions on the screens that talk about the app itself.
 *
 * The screens are shared. Only these facts differ, which is the same principle the rest of the
 * flavor split follows: parameterise the one thing, do not fork the surface.
 */
internal object EditionFacts {
    const val NAME = "Expert"

    /**
     * The project's Ko-fi page (B81). **The account exists since 2026-10-12 (N21).** If it is ever null again
     * the week-one notice is never shown and no screen offers a link, so nothing points at a
     * page that is not there. Setting it is the whole of turning the notice on.
     *
     * Here, in src/expert, and nowhere else: `:verifyPlayFlavorBoundaries` fails if a donation
     * page is named in anything compiled into the Play edition (M3).
     */
    val SUPPORT_URL: String? = "https://ko-fi.com/pintofi"

    /** This is the edition with everything; there is nothing to point at. */
    val OTHER_EDITION: OtherEdition? = null
}
