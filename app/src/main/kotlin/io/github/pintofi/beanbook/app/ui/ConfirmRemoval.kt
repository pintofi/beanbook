package io.github.pintofi.beanbook.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * A removal that names what goes before it goes.
 *
 * For the actions that delete something the app made on the person's behalf and cannot put back
 * by itself: a script's saved copy, or every package fetched for plugins. The words say what is
 * removed and what is not, so the button is a decision rather than a reflex.
 */
@Composable
internal fun ConfirmRemoval(
    title: String,
    body: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}
