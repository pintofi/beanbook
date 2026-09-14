package io.github.pintofi.beanbook.app.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.pintofi.beanbook.app.ui.Notice
import io.github.pintofi.beanbook.app.ui.NoticeTone
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * The other edition, described in the one that lacks what it adds (B81).
 *
 * Shown in the Play edition only, at the top of More, where people look for what the app can
 * do. It says what differs and where the editions are explained; it neither downloads nor
 * installs anything, which Play's Device and Network Abuse policy would forbid, and it is not
 * a request for money -- both editions are free.
 */
@Composable
internal fun EditionNotice(
    modifier: Modifier = Modifier,
    other: OtherEdition? = EditionFacts.OTHER_EDITION,
) {
    val edition = other ?: return
    val context = LocalContext.current
    Notice(
        NoticeTone.QUIET,
        modifier = modifier.padding(horizontal = Measure.gutter, vertical = Measure.gap),
    ) {
        Text("There is also an ${edition.name} edition", style = MaterialTheme.typography.titleMedium)
        Text(edition.summary, style = MaterialTheme.typography.bodyMedium)
        edition.differences.forEach { difference ->
            Text("•  $difference", style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(edition.url))) } },
            modifier = Modifier.semantics {
                contentDescription = "Read how the ${edition.name} edition differs, opens in your browser"
            },
        ) { Text("How the editions differ") }
    }
}

/**
 * The one mention that the project accepts donations, after a week of use (B81).
 *
 * Nothing is locked and nothing changes whichever button is pressed; both answer it for good.
 * Absent when the edition has no support page -- the Play edition by policy, and every edition
 * until the Ko-fi page exists.
 */
@Composable
internal fun SupportNotice(
    onAnswered: () -> Unit,
    modifier: Modifier = Modifier,
    url: String? = EditionFacts.SUPPORT_URL,
) {
    val link = url ?: return
    val context = LocalContext.current
    Notice(NoticeTone.QUIET, modifier = modifier) {
        Text("Beanbook is free", style = MaterialTheme.typography.titleMedium)
        Text(
            "It has no trial, no paid features and no ads, and it never will. If it has been " +
                "useful this week and you would like to help it along, you can support the " +
                "project on Ko-fi.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Measure.snug)) {
            TextButton(
                onClick = {
                    onAnswered()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                },
                modifier = Modifier.semantics {
                    contentDescription = "Support Beanbook on Ko-fi, opens in your browser"
                },
            ) { Text("Support on Ko-fi") }
            TextButton(
                onClick = onAnswered,
                modifier = Modifier.semantics { contentDescription = "Close this and do not show it again" },
            ) { Text("No thanks") }
        }
    }
}

/**
 * The support notice, when the week has passed and nobody has answered it (B81).
 *
 * Reads the app's own record directly: it is one fact about this install, and threading it
 * through the activity is what makes detekt's LargeClass bite there.
 */
@Composable
internal fun SupportNoticeHost(url: String? = EditionFacts.SUPPORT_URL) {
    if (url == null) return
    val app = LocalContext.current.applicationContext as? io.github.pintofi.beanbook.app.BeancountApplication
        ?: return
    var due by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(app.supportPrompt.isDue(java.time.Instant.now()))
    }
    if (!due) return
    SupportNotice(
        url = url,
        onAnswered = {
            app.supportPrompt.answer()
            due = false
        },
    )
}
