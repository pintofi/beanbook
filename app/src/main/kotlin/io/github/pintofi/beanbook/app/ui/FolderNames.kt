package io.github.pintofi.beanbook.app.ui

import java.net.URLDecoder

/**
 * A folder the person chose, in the words they would use for it (design-direction §7).
 *
 * The Mirror page printed the grant itself --
 * `content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FHamza%2FBeanbook`
 * -- which is Android's handle for the folder, not its name (device audit 2026-10-10). This
 * turns it back into "Documents/Hamza/Beanbook". Display only: the grant is what the app keeps
 * and uses, and nothing here is ever written back.
 *
 * Pure string work, so it is tested on the JVM: the tree id after `/tree/` is decoded, the
 * volume before its colon is named (the phone's own storage needs no name; anything else is
 * "SD card" or "USB storage"), and providers other than local storage are named by app. Anything
 * not recognised comes back decoded rather than raw, and an unparseable value comes back as it
 * was, so a folder is never shown as nothing.
 */
fun humanFolder(uri: String): String {
    val authority = uri.substringAfter("content://", "").substringBefore('/')
    val treeId = uri.substringAfter("/tree/", "").substringBefore('/')
    if (authority.isEmpty() || treeId.isEmpty()) return uri
    val decoded = decode(treeId) ?: return uri
    return when (authority) {
        EXTERNAL_STORAGE -> {
            val volume = decoded.substringBefore(':', missingDelimiterValue = "")
            val path = decoded.substringAfter(':').trim('/')
            val place = when {
                volume == "primary" -> null
                volume.equals("home", ignoreCase = true) -> "Documents"
                volume.isEmpty() -> null
                else -> "SD card or USB storage"
            }
            when {
                place == null && path.isEmpty() -> "This phone's storage"
                place == null -> path
                path.isEmpty() -> place
                else -> "$place · $path"
            }
        }
        DOWNLOADS -> "Downloads"
        else -> {
            val app = KNOWN_PROVIDERS[authority]
            val tail = decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { null }
            listOfNotNull(app, tail?.takeIf { app == null || !it.looksLikeAnId() }).joinToString(" · ")
                .ifBlank { decoded }
        }
    }
}

/** A provider's own opaque id -- long, no spaces, mostly letters and digits -- is not a name. */
private fun String.looksLikeAnId(): Boolean = length > ID_LENGTH && none { it == ' ' }

private fun decode(value: String): String? =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()

private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
private const val DOWNLOADS = "com.android.providers.downloads.documents"
private const val ID_LENGTH = 24

private val KNOWN_PROVIDERS = mapOf(
    "com.google.android.apps.docs.storage" to "Google Drive",
    "com.nextcloud.android.providers.documents" to "Nextcloud",
    "com.owncloud.android.providers" to "ownCloud",
    "com.dropbox.product.android.dbapp.document_provider.documents" to "Dropbox",
    "com.microsoft.skydrive.content.StorageAccessProvider" to "OneDrive",
    "com.github.catfriend1.syncthingandroid.documents" to "Syncthing",
)
