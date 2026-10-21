package io.github.pintofi.beanbook.app.ui

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
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import io.github.pintofi.beanbook.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.data.ProjectSummary
import io.github.pintofi.beanbook.core.data.TrashedProject
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Output

/** The trash as the project list needs it: what is in it, and what can be done to it. */
class ProjectTrash(
    val items: List<TrashedProject> = emptyList(),
    val onRestore: (String) -> Unit = {},
    val onDeleteForever: (String) -> Unit = {},
    val onEmpty: () -> Unit = {},
)

/** The trash wired to [ProjectsState]; [onRestored] is told the id of a project that came back. */
fun io.github.pintofi.beanbook.app.engine.ProjectsState.trashUi(
    items: List<TrashedProject>,
    onRestored: (String) -> Unit = {},
) = ProjectTrash(
    items = items,
    onRestore = { folder -> restore(folder, onRestored) },
    onDeleteForever = { folder -> deleteForever(folder) },
    onEmpty = { emptyTrash() },
)

/**
 * The project list. Shows enough to recognise a ledger without opening it, and never waits
 * on the engine to draw.
 *
 * Folios, in the order a person needs them: the projects there are, a new
 * one, one brought in from elsewhere, and what can be done with the open one. "New project" has
 * a heading of its own: the field used to sit under "The open project" and read as part of it
 * (device audit 2026-10-10).
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
    trash: ProjectTrash = ProjectTrash(),
) {
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    var newTitle by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FolioPage(modifier = Modifier.readableMeasureCentred()) {
            busyMessage?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.tight)) }

            FolioHeading("Your projects")
            FolioGroup {
                projects.forEach { project ->
                    item {
                        val open = project.id == activeId
                        FolioRow(
                            title = project.title,
                            detail = if (open) "Open now · ${project.describe()}" else project.describe(),
                            icon = if (open) Icons.Filled.FolderOpen else Icons.Outlined.Folder,
                            tone = if (open) FolioTone.ACCENT else FolioTone.PLAIN,
                            onClick = { onOpen(project.id) },
                            describe = "Open ${project.title}, ${project.describe()}" +
                                if (open) ". This is the open project" else "",
                        ) {
                            // An icon, not a second text button of the same weight as the row's
                            // own action (audit): deleting is the rare choice, and it asks first.
                            IconButton(
                                onClick = { pendingDelete = project },
                                modifier = Modifier.semantics {
                                    contentDescription = "Delete ${project.title}"
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            FolioHeading("New project")
            FolioGroup {
                item {
                    FolioBody {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text("Name") },
                            placeholder = { Text("Household") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onCreateNew(newTitle); newTitle = "" },
                            enabled = newTitle.isNotBlank(),
                            modifier = Modifier.semantics { contentDescription = "Create a new project" },
                        ) { Text("Create project") }
                    }
                }
            }
            FolioNote(
                "A project is the folder that holds one ledger. " +
                    "A new one starts with a main file you can add to.",
            )

            FolioHeading("Bring one in")
            FolioGroup {
                item {
                    FolioRow(
                        title = "Import a folder",
                        detail = "A folder of Beancount files, on this device or in cloud storage",
                        icon = Icons.Outlined.DriveFolderUpload,
                        onClick = onOpenFolder,
                        opens = true,
                    )
                }
                item {
                    FolioRow(
                        title = "Import a .zip",
                        detail = "A project exported from Beanbook, or any archive of a ledger",
                        icon = Icons.Outlined.FolderZip,
                        onClick = onOpenArchive,
                        opens = true,
                    )
                }
            }

            TrashSection(trash)

            if (activeId != null) {
                FolioHeading("The open project")
                FolioGroup {
                    item {
                        FolioRow(
                            title = "Export a copy",
                            detail = "The ledger, everything it includes, and its documents, written " +
                                "into a folder you choose. A copy taken now; Mirror is the one that " +
                                "stays in step.",
                            icon = Icons.Outlined.Output,
                            onClick = onExport,
                            describe = "Export a copy of the open project to a folder",
                        )
                    }
                }
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
                    "This removes ${project.fileCount} file(s), the whole project, " +
                        "including any documents, from the app. They are kept in the " +
                        "trash, listed on this screen, until you empty it or put them " +
                        "back. Any copy you exported elsewhere is untouched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(project.id)
                        pendingDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            },
        )
    }
}

/**
 * The mark, the name, and one sentence.
 *
 * The icon is rebuilt from its two layers rather than loaded, because `painterResource`
 * cannot take the `AdaptiveIconDrawable` that `@mipmap/ic_launcher` resolves to, and the
 * foreground is a 108dp drawable whose visible area is the central 72 — the same reason and
 * the same arithmetic as the About screen.
 */
@Composable
internal fun WelcomeHeader() {
    // Left-aligned, like every other page in the app. Centred type is what a marketing
    // splash does; this is the first page of a ledger, and a ledger has a margin.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Measure.gap),
    ) {
        // In the theme's colours, so the first thing the app shows is in the palette of
        // everything after it.
        BeanbookMark(size = 72.dp)
        Text("Beanbook", style = MaterialTheme.typography.displaySmall)
        Text(
            "Plain-text double-entry accounting, on your device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the app does, before asking for anything.
 *
 * The screen used to open on two cards headed "Open an existing project" and "Start a new
 * project", which asks a decision of somebody who has not been told what they are deciding
 * about. These four lines are the answer, and they are deliberately **facts rather than
 * claims**: each one is either a property of the file format or something the app provably
 * does, so none of it is marketing that a user could later find untrue.
 *
 * No illustrations and no screenshots of itself. The audience keeps books in a text editor;
 * a carousel of stock imagery would be the wrong register, and a tour of screens they have
 * not reached yet teaches nothing they can act on.
 */
@Composable
internal fun WhatThisIs() {
    // Four facts hung off one rule, separated by hairlines: the same page the app uses for
    // everything it states, so the first screen already teaches the second.
    RuledColumn {
        Highlight(
            "Your ledger is plain text",
            "Ordinary Beancount files in a folder you can open on a computer whenever you like. " +
                "Nothing is locked in a database only this app can read.",
        )
        LedgerHairline()
        Highlight(
            "Beancount and Fava, running here",
            "The same Beancount and the same Fava reports you would run on a computer. " +
                "No account to make and no server to trust.",
        )
        LedgerHairline()
        Highlight(
            "Works with the network off",
            "Nothing is uploaded and nothing is tracked. Your ledger works with the network off; " +
                "only connecting to your own Fava server needs it.",
        )
        LedgerHairline()
        Highlight(
            "Receipts kept with the entry",
            "Photograph a receipt and file it against the transaction it belongs to, so the " +
                "paper and the books stay together.",
        )
    }
}

/** One capability: a claim, and the detail under it. */
@Composable
private fun Highlight(title: String, detail: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Measure.tight),
        // One description for the pair. Read separately a screen reader announces a heading
        // and then an orphaned sentence.
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Measure.gap)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun describeBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "${bytes / 1000} KB"
    else -> "$bytes B"
}

private fun trashedOn(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

/**
 * Deleted projects: put one back, delete one for good, or empty the lot. Shown on the project
 * list and, when there are no projects at all, on the Add a ledger page, so that deleting the
 * only project is never a one-way door. Draws nothing when the trash is empty.
 */
@Composable
internal fun TrashSection(trash: ProjectTrash) {
    var pendingForever by remember { mutableStateOf<TrashedProject?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    if (trash.items.isNotEmpty()) {
        val total = trash.items.sumOf { it.totalBytes }
        FolioHeading("Trash")
        FolioGroup {
            trash.items.forEach { item ->
                item {
                    FolioRow(
                        title = item.title,
                        // A no-break space before the unit: when the line has to wrap, "799 B" moves down whole.
                        detail = "Deleted ${trashedOn(item.trashedAt)} · ${item.describe()}"
                            .replace(Regex(" (B|KB|MB)$"), "\u00a0$1"),
                        icon = Icons.Outlined.Folder,
                        onClick = { trash.onRestore(item.folder) },
                        describe = "Restore ${item.title}, ${item.describe()}",
                    ) {
                        IconButton(
                            onClick = { pendingForever = item },
                            modifier = Modifier.semantics {
                                contentDescription = "Delete ${item.title} for good"
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item {
                FolioRow(
                    title = "Empty the trash",
                    detail = "${trash.items.size} project${if (trash.items.size == 1) "" else "s"}, " +
                        "${describeBytes(total)}, deleted for good",
                    icon = Icons.Outlined.Delete,
                    tone = FolioTone.PLAIN,
                    onClick = { confirmEmpty = true },
                    describe = "Empty the trash, ${trash.items.size} projects, ${describeBytes(total)}",
                )
            }
        }
        FolioNote(
            "Tap a deleted project to put it back. Anything you exported from it is " +
                "not in here and is not affected.",
        )
    }

    pendingForever?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingForever = null },
            title = { Text("Delete \"${item.title}\" for good?") },
            text = {
                Text(
                    "This deletes its ${item.fileCount} file(s), including any documents, " +
                        "${describeBytes(item.totalBytes)} in all. It cannot be undone. A copy " +
                        "you exported elsewhere is untouched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { trash.onDeleteForever(item.folder); pendingForever = null },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete for good") }
            },
            dismissButton = { TextButton(onClick = { pendingForever = null }) { Text("Keep it") } },
        )
    }

    if (confirmEmpty) {
        val names = trash.items.joinToString(", ") { "\"${it.title}\"" }
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the trash?") },
            text = {
                Text(
                    "This deletes $names for good: ${trash.items.sumOf { it.fileCount }} file(s), " +
                        "${describeBytes(trash.items.sumOf { it.totalBytes })} in all. It cannot be " +
                        "undone. Copies you exported elsewhere are untouched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { trash.onEmpty(); confirmEmpty = false },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Empty the trash") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Keep them") } },
        )
    }
}
