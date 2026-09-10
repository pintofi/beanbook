package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.JournalEntry

/**
 * One entry, in full, and what can be done with it (B11).
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
    /** The document on disk, so it can be read here rather than handed to another app (B57). */
    fileForDocument: ((String) -> java.io.File)? = null,
    /** How the open ledger asks its figures to be written (B59). */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
    /** What Beancount said about this entry (B64). Empty for an entry it accepted. */
    errors: List<io.github.pintofi.beanbook.core.domain.LedgerError> = emptyList(),
) {
    var viewing by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember(entry) { mutableStateOf(false) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (errors.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (errors.size == 1) "Beancount reported an error here" else
                            "Beancount reported ${errors.size} errors here",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    errors.forEach { error ->
                        Text(
                            text = error.position?.let { "${error.message} ($it)" } ?: error.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.date, fontFamily = FontFamily.Monospace)
            Text(
                entry.description.ifBlank { "(no description)" },
                style = MaterialTheme.typography.titleMedium,
            )
            entry.flag?.let { flag ->
                Text(
                    if (flag == "!") "Pending (!) — flagged for another look" else "Cleared ($flag)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (entry.tags.isNotEmpty() || entry.links.isNotEmpty()) {
            Text(
                (entry.tags.map { "#$it" } + entry.links.map { "^$it" }).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        Text("Postings", style = MaterialTheme.typography.titleSmall)
        entry.postings.forEach { posting ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    posting.account,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                posting.amount?.let { AmountText(it, signed = true, format = format) }
                    ?: Text("(elided)", style = MaterialTheme.typography.bodySmall)
            }
            PostingLotText(posting)
        }

        // Documents this entry links to, by the convention link_documents uses. Read here
        // first (B57): checking what a transaction was for used to mean leaving the app
        // through a share sheet and coming back. Sharing is still one tap away, inside.
        if (entry.linkedDocuments.isNotEmpty()) {
            HorizontalDivider()
            Text("Documents", style = MaterialTheme.typography.titleSmall)
            entry.linkedDocuments.forEach { path ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        path.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
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

        // Where it lives. On a ledger split across included files this is the only way to
        // find the entry again in an editor, and the journal row cannot show it.
        entry.file?.let { file ->
            HorizontalDivider()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Where it is", style = MaterialTheme.typography.labelMedium)
                    Text(
                        listOfNotNull(file.substringAfterLast('/'), entry.line?.toString())
                            .joinToString(":"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        contentDescription = "Edit the source of this transaction"
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Confirm deleting this transaction" },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Delete this transaction?",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "\"${entry.description.ifBlank { entry.date }}\" will be removed from " +
                            "${entry.file?.substringAfterLast('/') ?: "the ledger"}. Any document " +
                            "it links to stays where it is, and this can be undone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { confirmingDelete = false; onDelete() },
                            modifier = Modifier.semantics {
                                contentDescription = "Delete this transaction"
                            },
                        ) { Text("Delete it") }
                        TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
                    }
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
