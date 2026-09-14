package io.github.pintofi.beanbook.app.packages

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
 * Two classes need to agree on it — [io.github.pintofi.beanbook.app.packages.PackagesState], which puts
 * things there, and `PluginsState.enabledDirectories`, which hands it to the engine. When
 * they were two string literals, a test that pointed one of them elsewhere would have passed
 * while the app quietly fetched into a directory nothing imported from.
 */
object OnDemandPackages {
    fun root(context: android.content.Context): java.io.File =
        java.io.File(context.filesDir, "ondemand")

    /**
     * Written while packages are being merged into [root], deleted once they are all there.
     *
     * Its presence means a merge started and did not finish, so **the install root cannot be
     * trusted** — see [isIncomplete] for what goes wrong without it.
     *
     * Beside the root rather than inside it, for the same reason the spool is: anything
     * inside the root is a candidate package, and a marker there would be reported as one.
     */
    fun marker(root: java.io.File): java.io.File =
        java.io.File(root.parentFile ?: root, "${root.name}-incomplete")

    /**
     * True when a previous merge into [root] was cut short.
     *
     * The failure this exists to stop (B37): unpacking is staged and digest-checked, but the
     * final step copies staging over the root file by file, and that is not atomic. Lose the
     * process partway — an out-of-memory kill is the realistic way, and is how this was
     * found — and the root keeps a half-copied package. Nothing recorded that, so the
     * directory looked installed, the engine was handed it, and Python failed on import with
     * `ImportError: cannot import name BaseTzInfo`: a message naming a transitive dependency
     * the user has never heard of, with nothing anywhere suggesting "clear the app's data".
     *
     * A marker is used rather than building a second copy of the root and renaming it into
     * place. The rename would be properly atomic, but beangrow's closure is ~143 MB unpacked
     * and doubling that on a phone is the wrong trade (docs/on-demand-packages.md), so this
     * follows the journal-intent half of the write protocol instead of the rename half.
     */
    fun isIncomplete(root: java.io.File): Boolean = marker(root).isFile

    /**
     * Throws away a root left half-merged, and returns what was being merged when it stopped.
     *
     * **All of it, not the packages named in the marker.** They were resolved as a closure,
     * and this project already refuses to remove one member of a closure and leave the rest
     * importable and broken (docs/on-demand-packages.md); a merge that overwrote a shared
     * file partway is the same situation. Re-fetching is what the user faced anyway, and it
     * is the only outcome that is certainly correct.
     *
     * Returns an empty list when there was nothing to recover, so the caller can tell "I
     * cleaned up after a crash" from "there was no crash".
     */
    fun discardIncomplete(root: java.io.File): List<String> {
        val marker = marker(root)
        if (!marker.isFile) return emptyList()
        val names = runCatching {
            marker.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        root.deleteRecursively()
        marker.delete()
        return names
    }
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
    /** The curated plugin catalogue (B82), which installs through the same fetcher. */
    val catalogue: io.github.pintofi.beanbook.app.plugins.CatalogueUi =
        io.github.pintofi.beanbook.app.plugins.CatalogueUi.EMPTY,
)
