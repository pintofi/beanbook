package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Edits one entry's own source text (B11).
 *
 * Text, not fields. Decomposing an entry into a form and rebuilding it would silently drop
 * whatever the form does not model — a comment inside the entry, metadata with no field, the
 * user's own alignment — and invariant 4 forbids exactly that. Replacing the entry's byte
 * range with what the user typed leaves every other byte in the file untouched.
 *
 * Nothing is validated here. The write protocol re-parses before it commits, so an entry that
 * no longer parses is rolled back with the reason rather than caught by a second, weaker
 * check written into the UI.
 */
@Composable
fun EntryEditDialog(
    original: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(original) { mutableStateOf(original) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit this entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    // Monospace, because Beancount's columns are how people read a posting,
                    // and a proportional font makes an aligned ledger look broken.
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    visualTransformation = beancountHighlighting(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .semantics { contentDescription = "The source text of this entry" },
                )
                Text(
                    "Saved only if Beancount still accepts the file.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
