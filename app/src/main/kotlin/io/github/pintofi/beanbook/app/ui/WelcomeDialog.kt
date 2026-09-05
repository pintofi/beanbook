package io.github.pintofi.beanbook.app.ui

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
 * Says where the trial stands, once, on a first launch (B27).
 *
 * This exists so the trial does not have to be announced on every screen forever. Told once
 * at the start and then kept somewhere it can be looked up is how a person learns a fact;
 * a line above every tab is how they learn to stop seeing it.
 *
 * It used to open by saying the books are plain text files that go nowhere. The welcome
 * screen behind it now says that, at more length and with the rest of what the app does, so
 * repeating it here made the dialog a summary of the page it was covering. What is left is
 * the one thing the page cannot state as a fact about the app, because it is a fact about
 * this install: how long is left.
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
        title = { Text("Your trial") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    contentDescription = "Close this and start using the app"
                },
            ) { Text("Get started") }
        },
    )
}
