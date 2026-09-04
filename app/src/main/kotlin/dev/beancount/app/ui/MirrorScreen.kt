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
import androidx.compose.material3.CardDefaults
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
import dev.beancount.core.data.MirrorAction

/**
 * Auto-mirror (docs/storage-and-backup.md §6).
 *
 * The screen exists mostly to make conflicts unmissable and their resolution deliberate.
 * Everything else — a file changed here, a file changed there — happens without asking,
 * because there is only one sensible answer. When there is more than one, the app stops.
 */
@Composable
fun MirrorScreen(
    enabled: Boolean,
    folder: String?,
    conflicts: List<MirrorAction.Conflict>,
    onChooseFolder: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mirror to a folder", style = MaterialTheme.typography.titleMedium)
        Text(
            "A copy of your project is kept in a folder you choose. The version on this " +
                "device stays the original.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Mirror after every change",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                enabled = folder != null,
                modifier = Modifier.semantics { contentDescription = "Mirror after every change" },
            )
        }

        TextButton(onClick = onChooseFolder) {
            Text(if (folder == null) "Choose a folder" else "Change folder")
        }
        folder?.let { Text("Mirroring to $it", style = MaterialTheme.typography.bodySmall) }

        Button(
            onClick = onSyncNow,
            enabled = folder != null,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Mirror now" },
        ) { Text("Mirror now") }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (conflicts.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Needs your decision",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Nothing has been overwritten. The folder's copy is saved beside yours with " +
                    "a \".conflict-\" suffix until you choose.",
                style = MaterialTheme.typography.bodySmall,
            )

            conflicts.forEach { conflict ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(conflict.path, style = MaterialTheme.typography.bodyMedium)
                        Text(conflict.describe(), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onKeepMine(conflict.path) },
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        "Keep this device's version of ${conflict.path}"
                                },
                            ) { Text("Keep this device's") }
                            TextButton(
                                onClick = { onKeepTheirs(conflict.path) },
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        "Keep the folder's version of ${conflict.path}"
                                },
                            ) { Text("Keep the folder's") }
                        }
                    }
                }
            }
        }
    }
}
