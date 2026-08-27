package dev.beancount.app.packages

import java.io.File

/**
 * Google Play edition: there is no fetcher here, and that is the point.
 *
 * The expert edition can fetch a Python package the user asks for — numpy and pandas for
 * beangrow's returns reports, say. This edition cannot, and the reason is policy rather than
 * capability: retrieving executable code from anywhere other than the store is what the
 * Device and Network Abuse policy prohibits, and a pure-Python wheel is still executable code
 * (docs/distribution.md 2.1).
 *
 * `:verifyPlayFlavorBoundaries` fails the build if the expert edition's version of this file
 * ever compiles into `play`, so this is not merely a promise in a comment.
 *
 * Every entry point returns a refusal rather than throwing: shared code may ask, and the
 * honest answer to "can you install this?" in this edition is no, not a crash.
 */
class WheelFetcher(
    @Suppress("UNUSED_PARAMETER") root: File,
    @Suppress("UNUSED_PARAMETER") allowed: Boolean,
    @Suppress("UNUSED_PARAMETER") allowNative: Boolean,
    @Suppress("UNUSED_PARAMETER") assets: android.content.res.AssetManager? = null,
) {
    fun resolve(names: List<String>): Result<Resolution> =
        Result.failure(UnsupportedOperationException(REASON))

    fun install(wheels: List<Wheel>): FetchResult = FetchResult.Refused(REASON)

    fun installed(): Set<String> = emptySet()

    fun preloadNativeLibraries(): List<String> = emptyList()

    private companion object {
        const val REASON =
            "This edition cannot download packages. The version from the project's own site can."
    }
}
