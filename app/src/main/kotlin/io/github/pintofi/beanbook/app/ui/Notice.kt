package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme

/** What a message is, which decides its colour and icon. */
enum class NoticeKind { ERROR, SUCCESS, INFO }

/**
 * Which kind a status message is, from how the app words it. The messages are written as
 * sentences ("Not saved: ...", "Could not ...", "Imported 3 files."), and every one of them
 * starts the same way for the same kind, so the words are the one place all of them can be
 * classified without each call site naming a kind.
 */
fun noticeKindOf(text: String): NoticeKind {
    val start = text.trimStart()
    return when {
        ERROR_STARTS.any { start.startsWith(it) } || ERROR_WORDS.any { it in text } -> NoticeKind.ERROR
        SUCCESS_STARTS.any { start.startsWith(it) } -> NoticeKind.SUCCESS
        else -> NoticeKind.INFO
    }
}

private val ERROR_STARTS = listOf(
    "Not ", "Could not", "Couldn't", "Can't", "Cannot", "Failed", "Unable", "Refused", "Wrong ",
    "That is not", "That did not", "That was not", "Something went wrong",
)
private val ERROR_WORDS = listOf(" failed", " was refused", " went wrong")
private val SUCCESS_STARTS = listOf(
    "Imported", "Saved", "Deleted", "Moved", "Restored", "Renamed", "Done", "Exported", "Copied", "Added",
)

/**
 * A message in a box of its own: an error in the error colours with a warning mark, a result that
 * went well in green with a tick, anything else in a neutral tone with an information mark. The
 * words are never the only sign of which it is, and an error is announced at once where the rest
 * wait for the screen reader to finish.
 */
@Composable
fun MessageBox(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = noticeKindOf(text),
) {
    val scheme = MaterialTheme.colorScheme
    val tone = when (kind) {
        NoticeKind.ERROR -> NoticeTone.PROBLEM
        NoticeKind.SUCCESS -> NoticeTone.SUCCESS
        NoticeKind.INFO -> NoticeTone.QUIET
    }
    val accent = when (kind) {
        NoticeKind.ERROR -> scheme.error
        NoticeKind.SUCCESS -> BeancountTheme.ledger.positive
        NoticeKind.INFO -> scheme.primary
    }
    val icon = when (kind) {
        NoticeKind.ERROR -> Icons.Filled.Warning
        NoticeKind.SUCCESS -> Icons.Filled.CheckCircle
        NoticeKind.INFO -> Icons.Filled.Info
    }
    Notice(
        tone,
        modifier = modifier.semantics {
            liveRegion = if (kind == NoticeKind.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
