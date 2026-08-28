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

/**
 * Where fetched packages live, named in one place.
 *
 * Two classes need to agree on it — [dev.beancount.app.packages.PackagesState], which puts
 * things there, and `PluginsState.enabledDirectories`, which hands it to the engine. When
 * they were two string literals, a test that pointed one of them elsewhere would have passed
 * while the app quietly fetched into a directory nothing imported from.
 */
object OnDemandPackages {
    fun root(context: android.content.Context): java.io.File =
        java.io.File(context.filesDir, "ondemand")
}

/** What the screen is showing while a fetch is in progress or has finished. */
sealed interface PackagesStatus {
    data object Idle : PackagesStatus
    data class Resolving(val request: String) : PackagesStatus
    data class Confirming(val request: String, val resolution: Resolution) : PackagesStatus
    data class Fetching(val request: String) : PackagesStatus
}

/**
 * Everything the packages section of the plugins screen needs, in one parameter.
 *
 * Seven separate parameters threaded through three composables is how a screen's signature
 * grows until nobody can see what it takes. Grouped because they are one feature, and because
 * the alternative made the activity that passes them large enough for detekt to say so.
 */
data class PackagesUi(
    val installed: List<String>,
    val status: PackagesStatus,
    val message: String?,
    val onRequest: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onRemoveAll: () -> Unit,
)
