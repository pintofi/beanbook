package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.undo.HeldOffer

/**
 * The question asked when a change the person wrote would add errors to the ledger.
 *
 * Nothing has been saved when it is shown: the ledger is as it was. Don't save is the first and filled
 * button and is what Back and a tap outside do, so a stray tap never writes a broken ledger; Save anyway
 * makes the change with its errors kept, and Undo can take it back. The errors are listed as the ledger
 * reports them, the first few, so the person can judge them.
 */
@Composable
fun KeepWithErrorsDialog(offer: HeldOffer, onKeep: () -> Unit, onDiscard: () -> Unit, onClose: () -> Unit) {
    val count = offer.held.newErrors.size
    val title = when {
        offer.problem != null -> "Nothing was saved"
        count == 1 -> "This change adds 1 error to your ledger"
        else -> "This change adds $count errors to your ledger"
    }
    AlertDialog(
        onDismissRequest = if (offer.problem != null) onClose else onDiscard,
        modifier = Modifier.semantics { paneTitle = title },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (offer.problem != null) {
                    Text(offer.problem)
                    return@Column
                }
                Text("${offer.what}. Nothing has been saved yet: your ledger is as it was.")
                offer.held.newErrors.take(SHOWN).forEach { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall)
                }
                if (count > SHOWN) Text("and ${count - SHOWN} more", style = MaterialTheme.typography.bodySmall)
                Text(
                    "If you save anyway, Beancount will show the error until you fix it. Undo can take it back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            if (offer.problem != null) {
                Button(onClick = onClose) { Text("Close") }
            } else {
                Button(
                    onClick = onDiscard,
                    modifier = Modifier.semantics { contentDescription = "Don't save the change" },
                ) { Text("Don't save") }
            }
        },
        dismissButton = {
            if (offer.problem == null) {
                OutlinedButton(
                    onClick = onKeep,
                    enabled = !offer.working,
                    modifier = Modifier.semantics { contentDescription = "Save the change anyway, with the errors" },
                ) { Text(if (offer.working) "Saving…" else "Save anyway") }
            }
        },
    )
}

private const val SHOWN = 5
