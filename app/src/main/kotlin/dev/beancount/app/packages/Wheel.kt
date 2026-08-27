package dev.beancount.app.packages

/**
 * One package to fetch, resolved to a file and a digest before anything is downloaded.
 *
 * Shared between the editions because the *types* are harmless — what differs is whether
 * anything can act on them. That is `WheelFetcher`, which exists in both source sets: the
 * expert one fetches, and the Play one refuses.
 */
data class Wheel(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
    val native: Boolean,
) {
    val filename: String get() = url.substringAfterLast('/')
}

/** What happened, in terms the screen can show without inventing wording. */
sealed interface FetchResult {
    data class Installed(val wheels: List<Wheel>, val bytes: Long) : FetchResult
    data class Refused(val reason: String) : FetchResult
    data class Failed(val reason: String) : FetchResult
}

/**
 * What resolving a request produced: what can be fetched, and what cannot.
 *
 * Partial on purpose. beangrow declares `beanprice`, which it uses only in its
 * price-downloading commands, and `beanprice` needs a native package with no Android build.
 * Refusing the whole install over a dependency the user's actual feature never touches would
 * be tidy and useless. So the closure is installed, [unavailable] is shown, and anything that
 * does reach for the missing piece fails at import with its name — which is how every other
 * missing dependency in this app behaves.
 */
data class Resolution(
    val wheels: List<Wheel>,
    val unavailable: List<String>,
) {
    val bytesUnknown: Boolean get() = wheels.isEmpty()
}
