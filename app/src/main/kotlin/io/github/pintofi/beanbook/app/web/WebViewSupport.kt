package io.github.pintofi.beanbook.app.web

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * What Reports needs from the phone's System WebView.
 *
 * Reports, the editor and the other Fava pages are Fava's own, drawn by the WebView. Fava's script
 * uses syntax a very old WebView cannot parse (Chrome 74 stops with "Unexpected token ?"), so the page
 * never runs and stays blank. Nothing in the app can fix that, but it can say what is wrong.
 */
internal object WebViewSupport {

    /**
     * Chrome 74 is confirmed too old. 80 is where optional chaining and `??` arrived, the syntax the
     * error points at, so it is where the line is drawn: an estimate, not a measured limit.
     */
    const val MIN_MAJOR = 80

    /** The System WebView's major version, or null when the phone does not say. */
    fun major(): Int? = runCatching {
        WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull()
    }.getOrNull()

    /** Whether a WebView of this version is too old for Fava's pages. Unknown is not old. */
    fun tooOld(major: Int?): Boolean = major != null && major < MIN_MAJOR
}

/**
 * Says the System WebView is too old for Reports. Draws nothing for a current one.
 *
 * Shown over the top of the page rather than in place of it: the page may still work, and where it does
 * not it is blank, so there is nothing underneath worth protecting.
 */
@Composable
internal fun OldWebViewNotice(
    modifier: Modifier = Modifier,
    major: Int? = remember { WebViewSupport.major() },
) {
    if (!WebViewSupport.tooOld(major)) return
    Surface(
        modifier = modifier.padding(Measure.gutter),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = BeanbookShape.control,
    ) {
        Column(Modifier.padding(Measure.indent)) {
            Text(
                "This phone's WebView is out of date",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Reports are Fava's own pages, and they need a newer Android System WebView than this " +
                    "phone has (Chrome $major). They may stay blank until you update it, in your app " +
                    "store or in the system settings. Your ledger and the rest of the app are not affected.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Measure.tight),
            )
        }
    }
}
