package io.github.pintofi.beanbook.app.packages

import io.github.pintofi.beanbook.core.data.InstallRecord
import io.github.pintofi.beanbook.core.data.UpstreamRelease

/**
 * Google Play edition: nothing is looked up and nothing is downloaded. The Play edition installs no
 * plugin from the catalogue, so there is nothing to check (docs/distribution.md 2.1, D1).
 */
class UpdateChecker {
    fun check(
        @Suppress("UNUSED_PARAMETER") record: InstallRecord,
        @Suppress("UNUSED_PARAMETER") version: String,
    ): Result<UpstreamRelease?> =
        Result.failure(UnsupportedOperationException("This edition does not check for plugin updates."))

    companion object {
        const val AVAILABLE = false
        val HOSTS: List<String> = emptyList()
    }
}
