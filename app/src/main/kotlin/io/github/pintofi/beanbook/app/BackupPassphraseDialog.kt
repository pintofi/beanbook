package io.github.pintofi.beanbook.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.pintofi.beanbook.app.ui.PasswordField

/**
 * Asks for the passphrase of an encrypted backup that was chosen to be restored as a project.
 *
 * Over the app, like the other dialogs that do not belong to one screen: the backup can be chosen
 * on the first-run setup or on the Projects page, and the question is the same for both.
 */
@Composable
internal fun MainActivity.BackupPassphraseDialog() {
    val app = application as BeancountApplication
    val locked by app.projectsState.backupLocked.collectAsState()
    if (!locked) return
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { app.projectsState.cancelBackup() },
        title = { Text("This backup is encrypted") },
        text = {
            PasswordField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = "Passphrase",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val typed = passphrase.toCharArray()
                    passphrase = ""
                    app.projectsState.unlockBackup(typed) { id -> afterImport(app, id) }
                },
                enabled = passphrase.isNotEmpty(),
            ) { Text("Restore") }
        },
        dismissButton = {
            OutlinedButton(onClick = { app.projectsState.cancelBackup() }) { Text("Cancel") }
        },
    )
}
