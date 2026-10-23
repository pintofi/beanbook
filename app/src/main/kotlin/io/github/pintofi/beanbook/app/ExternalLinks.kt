package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.app.ui.SecondaryButton
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.pintofi.beanbook.app.ui.theme.LedgerType

/**
 * Whether a link out of a report may be handed to the browser at all.
 *
 * Only web addresses, and none with a user name in them: `https://example.com@evil.example/`
 * shows one host and goes to another. Anything else a page can name (`intent:`, `file:`,
 * `content:`, `tel:`) is not something a report has a reason to open.
 */
internal fun isOpenableExternally(uri: Uri): Boolean = isOpenableExternally(
    scheme = uri.scheme,
    host = uri.host,
    userInfo = uri.userInfo,
)

internal fun isOpenableExternally(scheme: String?, host: String?, userInfo: String?): Boolean {
    val lower = scheme?.lowercase()
    return (lower == "http" || lower == "https") && !host.isNullOrBlank() && userInfo == null
}

/**
 * A link a report asked to open, held until the person says yes.
 *
 * The threat model promises that external links open in the browser only after a
 * confirmation; this is where that is kept. The dialog shows the whole address, because the
 * host alone is what a look-alike address would get right.
 */
internal fun MainActivity.askToOpenExternally(uri: Uri) {
    if (isOpenableExternally(uri)) pendingExternalLink = uri
}

@Composable
internal fun MainActivity.ExternalLinkDialog() {
    val link = pendingExternalLink ?: return
    AlertDialog(
        onDismissRequest = { pendingExternalLink = null },
        title = { Text("Open this link in your browser?") },
        text = { Text(link.toString(), style = LedgerType.source) },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    pendingExternalLink = null
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, link)) }
                },
            ) { Text("Open") }
        },
        dismissButton = {
            SecondaryButton(onClick = { pendingExternalLink = null }) { Text("Cancel") }
        },
    )
}
