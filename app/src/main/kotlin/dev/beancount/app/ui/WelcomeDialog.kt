package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Says what the app is and where the trial stands, once, on a first launch (B27).
 *
 * This exists so the trial does not have to be announced on every screen forever. Told once
 * at the start and then kept somewhere it can be looked up is how a person learns a fact;
 * a line above every tab is how they learn to stop seeing it.
 *
 * The wording states what the trial does *not* take away, because that is the question
 * somebody trusting an accounting app with their records actually has. It is true of this
 * app by design: the ledger is the user's own plain text and stays editable whatever the
 * entitlement says (docs/distribution.md 4.4).
 */
@Composable
fun WelcomeDialog(trialDays: Int?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welcome to Beanbook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Your books are plain text files on this device. You can open them on a " +
                        "computer at any time, and nothing here sends them anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when {
                        trialDays == null -> "Everything is unlocked while you try the app."
                        trialDays <= 1 -> "Everything is unlocked — this is the last day of your trial."
                        else -> "Everything is unlocked for the next $trialDays days."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "When the trial ends, writing, editing and exporting keep working. " +
                        "Some features become paid. Details are under More.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = "Close the welcome and start using the app"
                },
            ) { Text("Get started") }
        },
    )
}
