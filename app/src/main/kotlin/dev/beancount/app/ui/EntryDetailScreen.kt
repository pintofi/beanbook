package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.beancount.core.domain.JournalEntry

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
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                posting.amount?.let { AmountText(it) }
                    ?: Text("(elided)", style = MaterialTheme.typography.bodySmall)
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
                    contentDescription = "Enter this transaction again"
                },
            ) { Text("Enter again") }
            TextButton(onClick = onClose) { Text("Close") }
        }

        Text(
            "\"Enter again\" opens the Add tab with these details and today's date. Nothing is " +
                "written until you save it there.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
