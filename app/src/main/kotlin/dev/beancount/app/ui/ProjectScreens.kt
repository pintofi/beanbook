package dev.beancount.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.beancount.core.data.ProjectSummary

/**
 * First run. Three ways in, no account, no network, and nothing asked for
 * (docs/ux.md §9).
 */
@Composable
fun OnboardingScreen(
    onCreateNew: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onOpenArchive: () -> Unit,
    modifier: Modifier = Modifier,
    busyMessage: String? = null,
) {
    var title by remember { mutableStateOf("My Ledger") }

    // Scrollable, not just tall: at 200% font the "Start a new project" card falls below
    // the fold, and without this it cannot be reached at all.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Beancount", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Plain-text double-entry accounting. Your files stay on this device and stay " +
                "yours — ordinary text you can open on a computer at any time.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open an existing project", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Import a folder or a .zip you already keep on this device or in cloud " +
                        "storage. Free, and as often as you like.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Wraps rather than squeezing: side by side these two buttons stop fitting
                // at large font sizes, and a button squashed until its label breaks
                // mid-word is not a usable control.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onOpenFolder) { Text("Choose folder") }
                    OutlinedButton(onClick = onOpenArchive) { Text("Choose .zip") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start a new project", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { onCreateNew(title) },
                    enabled = title.isNotBlank(),
                ) { Text("Create") }
            }
        }

        busyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * The project list. Shows enough to recognise a ledger without opening it, and never waits
 * on the engine to draw.
 */
@Composable
fun ProjectPickerScreen(
    projects: List<ProjectSummary>,
    activeId: String?,
    onOpen: (String) -> Unit,
    onCreateNew: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onOpenArchive: () -> Unit,
    onExport: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    busyMessage: String? = null,
) {
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    var newTitle by remember { mutableStateOf("") }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                "Projects",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
        }

        items(projects, key = { it.id }) { project ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(project.id) }
                    .padding(16.dp)
                    .semantics {
                        contentDescription = "Open ${project.title}, ${project.describe()}"
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = project.title + if (project.id == activeId) "  ·  open" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(project.describe(), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { pendingDelete = project }) { Text("Delete") }
            }
            HorizontalDivider()
        }

        item {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add a project", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenFolder) { Text("Import folder") }
                    OutlinedButton(onClick = onOpenArchive) { Text("Import .zip") }
                }

                if (activeId != null) {
                    HorizontalDivider()
                    Text("The open project", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Write a copy of it into a folder you choose — the ledger, everything " +
                            "it includes, and its documents. It is a copy taken now, not kept " +
                            "in step afterwards; Mirror is the one that stays in step.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.semantics {
                            contentDescription = "Export a copy of the open project to a folder"
                        },
                    ) { Text("Export a copy") }
                }
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Or create a new one") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { onCreateNew(newTitle); newTitle = "" },
                    enabled = newTitle.isNotBlank(),
                ) { Text("Create") }
                busyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    // Invariant 7: a destructive action names its exact scope, and this one is reversible —
    // the files move to the trash rather than being deleted.
    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${project.title}\"?") },
            text = {
                Text(
                    "This removes ${project.fileCount} file(s) — the whole project, " +
                        "including any documents — from the app. They are kept in the " +
                        "trash so you can put them back, and any copy you exported " +
                        "elsewhere is untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(project.id)
                    pendingDelete = null
                }) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            },
        )
    }
}
