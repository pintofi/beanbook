package io.github.pintofi.beanbook.app.packages

/**
 * Google Play edition: catalogue plugins are listed, never downloaded.
 *
 * Retrieving executable code from anywhere other than the store is what the Device and Network
 * Abuse policy prohibits (docs/distribution.md 2.1, D1). The Play edition shows the catalogue
 * so a person can see what exists and how it tested, and installs a plugin only from a file
 * they supply.
 */
class ArchiveFetcher {
    fun fetch(@Suppress("UNUSED_PARAMETER") url: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("This edition does not download plugins."))

    companion object {
        const val AVAILABLE = false
    }
}
