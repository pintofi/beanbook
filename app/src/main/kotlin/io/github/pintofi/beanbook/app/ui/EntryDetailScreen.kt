package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.JournalEntry

/**
 * One entry, in full, and what can be done with it.
 *
 * The journal row already shows the postings, so this screen earns its place by saying the
 * things a list cannot: which file and line the entry came from — the answer somebody needs
 * before editing a multi-file ledger by hand — and whether its amounts balance to zero.
 *
 * Actions live here rather than on the row. A destructive action reachable from a list is one
 * people trigger while scrolling.
 */
@Composable
fun EntryDetailScreen(
    entry: JournalEntry,
    onClone: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onOpenDocument: ((String) -> Unit)? = null,
    /** The document on disk, so it can be read here rather than handed to another app. */
    fileForDocument: ((String) -> java.io.File)? = null,
    /** How the open ledger asks its figures to be written. */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
    /** What Beancount said about this entry. Empty for an entry it accepted. */
    errors: List<io.github.pintofi.beanbook.core.domain.LedgerError> = emptyList(),
) {
    var viewing by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember(entry) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(Measure.section),
    ) {
        if (errors.isNotEmpty()) {
            Notice(NoticeTone.PROBLEM) {
                Text(
                    if (errors.size == 1) "Beancount reported an error here" else
                        "Beancount reported ${errors.size} errors here",
                    style = MaterialTheme.typography.titleSmall,
                )
                errors.forEach { error ->
                    Text(
                        text = error.position?.let { "${error.message} ($it)" } ?: error.message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        // The subject of the screen, hung off the rule like every other heading in the app:
        // the mark says what the file's flag means, the words say what the entry is, and the
        // date is underneath in the figure face where the eye expects a column of them.
        RuledColumn {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StampMark(
                    if (errors.isNotEmpty()) LedgerMark.ATTENTION else markForFlag(entry.flag),
                    flag = entry.flag,
                    modifier = Modifier.padding(end = Measure.gap),
                )
                // The file's own flag, said as what it is (device audit 2026-10-10): a `P` entry
                // was described here as "Cleared".
                Text(
                    entry.flag?.let { flag ->
                        when (markForFlag(flag)) {
                            LedgerMark.PENDING -> "Pending, flagged for another look"
                            LedgerMark.FLAGGED -> "Flagged $flag"
                            else -> "Cleared"
                        }
                    } ?: "No flag",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.description.ifBlank { NO_DESCRIPTION },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = Measure.snug),
            )
            Text(
                entry.date,
                style = LedgerType.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Measure.tight),
            )
        }

        if (entry.tags.isNotEmpty() || entry.links.isNotEmpty()) {
            Text(
                (entry.tags.map { "#$it" } + entry.links.map { "^$it" }).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        RuledColumn {
            LedgerHeading("Postings")
            entry.postings.forEachIndexed { index, posting ->
                if (index > 0) LedgerHairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Measure.snug),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        breakableAccount(posting.account),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(end = Measure.snug),
                    )
                    // In ink: the sign carries direction.
                    posting.amount?.let { AmountText(it, format = format) }
                        ?: Text(
                            "(worked out by Beancount)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                PostingLotText(posting, format = format)
            }
        }

        // Documents this entry links to, by the convention link_documents uses. Read here
        // first: checking what a transaction was for used to mean leaving the app
        // through a share sheet and coming back. Sharing is still one tap away, inside.
        if (entry.linkedDocuments.isNotEmpty()) {
            RuledColumn {
                LedgerHeading("Documents")
                entry.linkedDocuments.forEach { path ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            path.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (fileForDocument != null) {
                            TextButton(
                                onClick = { viewing = path },
                                modifier = Modifier.semantics {
                                    contentDescription = "View ${path.substringAfterLast('/')}"
                                },
                            ) { Text("View") }
                        }
                        if (onOpenDocument != null) {
                            TextButton(
                                onClick = { onOpenDocument(path) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Share ${path.substringAfterLast('/')}"
                                },
                            ) { Text("Share") }
                        }
                    }
                }
            }
        }

        // Where it lives. On a ledger split across included files this is the only way to
        // find the entry again in an editor, and the journal row cannot show it.
        entry.file?.let { file ->
            RuledColumn {
                LedgerHeading("Where it is")
                Text(
                    listOfNotNull(file.substringAfterLast('/'), entry.line?.toString())
                        .joinToString(":"),
                    style = LedgerType.source,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Wraps onto a second line at large text rather than pushing Close off the screen.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalArrangement = Arrangement.spacedBy(Measure.tight),
        ) {
            Button(
                onClick = onClone,
                modifier = Modifier.semantics {
                    contentDescription = "Clone this transaction into a new entry"
                },
            ) { Text("Clone") }
            if (onEdit != null && !confirmingDelete) {
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics {
                        contentDescription = "Edit this transaction"
                    },
                ) { Text("Edit") }
            }
            if (onDelete != null && !confirmingDelete) {
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Delete this transaction"
                    },
                ) { Text("Delete") }
            }
            TextButton(onClick = onClose) { Text("Close") }
        }

        // Invariant 7: a destructive action names its scope and is confirmable. It says
        // which entry, says the file it will be taken out of, and says that undo exists --
        // because the honest reassurance is that this is recoverable, not that it is small.
        if (onDelete != null && confirmingDelete) {
            Notice(
                NoticeTone.PROBLEM,
                modifier = Modifier.semantics {
                    contentDescription = "Confirm deleting this transaction"
                },
            ) {
                Text("Delete this transaction?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "\"${entrySummary(entry, format)}\" will be removed from " +
                        "${entry.file?.substringAfterLast('/') ?: "the ledger"}. Any document " +
                        "it links to stays where it is, and this can be undone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Measure.snug)) {
                    Button(
                        onClick = { confirmingDelete = false; onDelete() },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Delete this transaction"
                        },
                    ) { Text("Delete it") }
                    TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
                }
            }
        }
    }

    viewing?.let { path ->
        fileForDocument?.let { resolve ->
            DocumentViewer(
                file = resolve(path),
                onShare = {
                    onOpenDocument?.invoke(path)
                    viewing = null
                },
                onDismiss = { viewing = null },
            )
        }
    }

}
