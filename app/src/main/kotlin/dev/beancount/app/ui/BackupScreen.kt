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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.beancount.core.data.RestoreStaged

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
    scheduleEnabled: Boolean,
    scheduleFolder: String?,
    lastRun: String?,
    rollbackPoints: List<String>,
    onBackUpNow: () -> Unit,
    onChooseRestoreFile: () -> Unit,
    onAcceptRestore: () -> Unit,
    onDiscardRestore: () -> Unit,
    onChooseScheduleFolder: () -> Unit,
    onScheduleChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Text(
            "A backup is a single .bcaz file: a zip with a readable manifest and a checksum " +
                "for every file. It is not encrypted, so it can be opened anywhere, by any " +
                "tool, without this app.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = onBackUpNow,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Back up $projectTitle now" },
        ) { Text("Back up now") }

        HorizontalDivider()

        Text("Restore", style = MaterialTheme.typography.titleSmall)
        if (pendingRestore == null) {
            Text(
                "Restoring never overwrites straight away: the backup is checked, unpacked " +
                    "beside your project, and you see exactly what would change before " +
                    "anything happens.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onChooseRestoreFile) { Text("Choose a backup file") }
        } else {
            RestorePreview(
                staged = pendingRestore,
                onAccept = onAcceptRestore,
                onDiscard = onDiscardRestore,
            )
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (rollbackPoints.isNotEmpty()) {
            Text("Kept from earlier restores", style = MaterialTheme.typography.titleSmall)
            rollbackPoints.forEach { name ->
                Text(name, style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()

        Text("Scheduled backups", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Back up automatically",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = scheduleEnabled,
                onCheckedChange = onScheduleChanged,
                enabled = scheduleFolder != null,
                modifier = Modifier.semantics {
                    contentDescription = "Back up automatically"
                },
            )
        }
        // Said plainly rather than implied: Android decides when this actually runs.
        Text(
            "About once a day, when the device has battery and storage to spare. Android " +
                "decides the exact moment, so this is not a guaranteed time.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onChooseScheduleFolder) {
            Text(if (scheduleFolder == null) "Choose a folder" else "Change folder")
        }
        scheduleFolder?.let {
            Text("Backups go to $it", style = MaterialTheme.typography.bodySmall)
        }
        lastRun?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** What a restore would do, before it does it. */
@Composable
private fun RestorePreview(
    staged: RestoreStaged.Ready,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Backup of \"${staged.manifest.projectTitle}\"",
                style = MaterialTheme.typography.titleSmall,
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
                "Your current project is kept as a rollback point; nothing is deleted.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.semantics {
                        contentDescription = "Restore this backup over the current project"
                    },
                ) { Text("Restore") }
                TextButton(onClick = onDiscard) { Text("Cancel") }
            }
        }
    }
}

private const val REMOVED_SHOWN = 5
