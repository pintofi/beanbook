package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.data.RestoreStaged
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsBackupRestore

/**
 * Back up, restore, and schedule (docs/storage-and-backup.md §7).
 *
 * The screen's job is to make one thing impossible: restoring without seeing what it would
 * change. A staged restore always shows its diff first, and the confirmation names what is
 * about to be lost rather than asking "are you sure?".
 */
@Composable
fun BackupScreen(
    projectTitle: String,
    pendingRestore: RestoreStaged.Ready?,
    passphraseNeeded: Boolean,
    scheduleEnabled: Boolean,
    scheduleFolder: String?,
    lastRun: String?,
    rollbackPoints: List<String>,
    onBackUpNow: (CharArray?) -> Unit,
    onUnlockRestore: (CharArray) -> Unit,
    onChooseRestoreFile: () -> Unit,
    onAcceptRestore: () -> Unit,
    onDiscardRestore: () -> Unit,
    onChooseScheduleFolder: () -> Unit,
    onScheduleChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    var encrypt by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var unlockPassphrase by remember { mutableStateOf("") }

    val passphrasesMatch = passphrase.isNotEmpty() && passphrase == confirmation

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
    FolioPage(modifier = Modifier.readableMeasureCentred()) {
        FolioHeading("Back up now")
        FolioGroup {
            item {
                FolioSwitchRow(
                    title = "Encrypt with a passphrase",
                    checked = encrypt,
                    onCheckedChange = { encrypt = it },
                    icon = Icons.Outlined.Lock,
                    describe = "Encrypt with a passphrase",
                )
            }
            if (encrypt) {
                item {
                    FolioBody {
                        // The warning is deliberately blunt and sits *above* the fields, where it
                        // is read before a passphrase is chosen rather than after
                        // (docs/storage-and-backup.md 7).
                        Notice(NoticeTone.PROBLEM) {
                            Text(
                                "If you forget this passphrase, the backup cannot be opened. There is " +
                                    "no reset and no recovery code. Write it down before you continue.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Passphrase" },
                        )
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text("Passphrase again") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            isError = confirmation.isNotEmpty() && !passphrasesMatch,
                            supportingText = if (confirmation.isNotEmpty() && !passphrasesMatch) {
                                { Text("The two passphrases are different.") }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Passphrase again" },
                        )
                    }
                }
            }
        }
        FolioNote(
            "One .bcaz file holding the whole project. Without a passphrase it is an ordinary zip, " +
                "openable without this app.",
        )
        Button(
            onClick = {
                onBackUpNow(if (encrypt) passphrase.toCharArray() else null)
            },
            enabled = !encrypt || passphrasesMatch,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { contentDescription = "Back up $projectTitle now" },
        ) { Text(if (encrypt) "Back up, encrypted" else "Back up now") }

        message?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.tight)) }

        FolioHeading("Restore")
        FolioGroup {
            if (pendingRestore == null) {
                item {
                    FolioRow(
                        title = "Choose a backup file",
                        detail = "Nothing is overwritten until you confirm. You see what would change first.",
                        icon = Icons.Outlined.SettingsBackupRestore,
                        onClick = onChooseRestoreFile,
                        opens = true,
                    )
                }
                if (passphraseNeeded) {
                    item {
                        FolioBody {
                            OutlinedTextField(
                                value = unlockPassphrase,
                                onValueChange = { unlockPassphrase = it },
                                label = { Text("Passphrase for this backup") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "Passphrase for this backup" },
                            )
                            Button(
                                onClick = { onUnlockRestore(unlockPassphrase.toCharArray()) },
                                enabled = unlockPassphrase.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open this backup") }
                        }
                    }
                }
            } else {
                item {
                    FolioBody {
                        RestorePreview(
                            staged = pendingRestore,
                            onAccept = onAcceptRestore,
                            onDiscard = onDiscardRestore,
                        )
                    }
                }
            }
        }

        if (rollbackPoints.isNotEmpty()) {
            FolioHeading("Kept from earlier restores")
            FolioGroup {
                rollbackPoints.forEach { name ->
                    item { FolioRow(title = name, icon = Icons.Outlined.History) }
                }
            }
        }

        // The folder first, then the switch that needs it (device audit 2026-10-10): the switch
        // sat above "Choose a folder", disabled, with nothing beside it saying why.
        FolioHeading("Scheduled backups")
        FolioGroup {
            item {
                // The whole row chooses or changes the folder: a button beside the words
                // squeezed them into a narrow column on a phone.
                FolioRow(
                    title = scheduleFolder?.let { humanFolder(it) } ?: "Choose a folder",
                    detail = if (scheduleFolder == null) {
                        "Where scheduled backups are written"
                    } else {
                        "Backups go to this folder. Tap to change it."
                    },
                    icon = Icons.Outlined.Folder,
                    tone = if (scheduleFolder == null) FolioTone.PLAIN else FolioTone.ACCENT,
                    onClick = onChooseScheduleFolder,
                    opens = true,
                    describe = if (scheduleFolder == null) {
                        "Choose a folder for scheduled backups"
                    } else {
                        "Backups go to ${humanFolder(scheduleFolder)}. Change the folder"
                    },
                )
            }
            item {
                FolioSwitchRow(
                    title = "Back up automatically",
                    checked = scheduleEnabled,
                    onCheckedChange = onScheduleChanged,
                    enabled = scheduleFolder != null,
                    // Said plainly rather than implied: Android decides when this actually runs.
                    detail = if (scheduleFolder == null) {
                        "Choose a folder first."
                    } else {
                        "About once a day. Android decides the exact moment."
                    },
                    icon = Icons.Outlined.Schedule,
                    describe = "Back up automatically",
                )
            }
        }
        lastRun?.let { FolioNote(it) }
    }
    }
}

/** What a restore would do, before it does it. */
@Composable
private fun RestorePreview(
    staged: RestoreStaged.Ready,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
        run {
            Text(
                "Backup of \"${staged.manifest.projectTitle}\"",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = Measure.snug),
            )
            Text(
                "Taken ${staged.manifest.createdAt}, ${staged.manifest.files.size} file(s).",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(staged.diff.describe(), style = MaterialTheme.typography.bodyMedium)

            // The list that matters most: files that exist now and are not in the backup.
            if (staged.diff.removed.isNotEmpty()) {
                Text(
                    "These files are in your project but not in this backup, and restoring " +
                        "would leave them behind:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                staged.diff.removed.take(REMOVED_SHOWN).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
                if (staged.diff.removed.size > REMOVED_SHOWN) {
                    Text(
                        "· and ${staged.diff.removed.size - REMOVED_SHOWN} more",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (staged.diff.changed.isNotEmpty()) {
                Text("Replaced:", style = MaterialTheme.typography.bodySmall)
                staged.diff.changed.take(REMOVED_SHOWN).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "Your current project is kept first, so you can go back to it. Nothing is deleted.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.semantics {
                        contentDescription = "Restore this backup over the current project"
                    },
                ) { Text("Restore") }
                SecondaryButton(onClick = onDiscard) { Text("Cancel") }
            }
        }
    }
}

private const val REMOVED_SHOWN = 5
