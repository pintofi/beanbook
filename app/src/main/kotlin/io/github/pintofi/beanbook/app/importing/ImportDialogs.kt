package io.github.pintofi.beanbook.app.importing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pintofi.beanbook.core.domain.LinkProblem
import io.github.pintofi.beanbook.core.domain.UnresolvedLink

/**
 * The steps of an import over the app: the system pickers, and the dialogs between them. Over the app, like
 * the other dialogs that do not belong to one screen, so the first-run screen and More start it the same way.
 */
@Composable
fun ImportHost(controller: ImportController, onImported: (String) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        controller.fileChosen(it)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        controller.folderChosen(it)
    }
    LaunchedEffect(state) {
        when (val current = state) {
            ImportState.PickFile -> {
                controller.pickerOpened()
                filePicker.launch(arrayOf("*/*"))
            }
            is ImportState.PickFolder -> {
                controller.pickerOpened()
                folderPicker.launch(current.initial)
            }
            else -> Unit
        }
    }
    ImportDialogs(
        state = state,
        onChooseFolder = controller::chooseFolder,
        onImportAlone = controller::importAlone,
        onImport = { controller.confirm(onImported) },
        onCancel = controller::cancel,
    )
}

/** The dialogs for [state]; nothing for the steps that are a picker. */
@Composable
fun ImportDialogs(
    state: ImportState,
    onChooseFolder: () -> Unit,
    onImportAlone: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        is ImportState.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Reading ${state.name}…") },
            text = { Text("Looking at the files it links to. Nothing is imported yet.") },
            confirmButton = {},
        )
        is ImportState.NeedsFolder -> NeedsFolderDialog(state, onChooseFolder, onImportAlone, onCancel)
        is ImportState.Preview -> PreviewDialog(state, onImport, onCancel)
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Not imported") },
            text = { Text(state.message) },
            confirmButton = { Button(onClick = onCancel) { Text("Close") } },
        )
        else -> Unit
    }
}

@Composable
private fun NeedsFolderDialog(
    state: ImportState.NeedsFolder,
    onChooseFolder: () -> Unit,
    onImportAlone: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = "Allow the folder that holds ${state.name}"
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.semantics { paneTitle = title },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "${state.name} links to ${state.links} other ${if (state.links == 1) "file" else "files"}. " +
                        "To read them, Android needs you to allow the folder that holds ${state.name}. Only " +
                        "the files it links to are read; nothing else in the folder is listed, copied or changed.",
                )
                if (state.up > 0) {
                    Text(
                        "It links to files ${state.up} ${if (state.up == 1) "folder" else "folders"} above it, " +
                            "so choose the folder that many levels up.",
                    )
                }
                state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 4.dp)) {
                OutlinedButton(
                    onClick = onImportAlone,
                    modifier = Modifier.semantics { contentDescription = "Import ${state.name} on its own" },
                ) { Text("On its own") }
                Button(
                    onClick = onChooseFolder,
                    modifier = Modifier.semantics { contentDescription = "Choose the folder to allow" },
                ) { Text("Choose folder") }
            }
        },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PreviewDialog(state: ImportState.Preview, onImport: () -> Unit, onCancel: () -> Unit) {
    val title = "Import ${state.name}?"
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.semantics { paneTitle = title },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "${state.fileCount} ${if (state.fileCount == 1) "file" else "files"}, ${size(state.totalBytes)}. " +
                        "These are the only files that will be imported, in these folders.",
                )
                state.lines.forEach { line ->
                    Text(
                        (if (line.isFolder) "${line.name}/" else line.name) +
                            (if (line.isMain) "  (your Beancount file)" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = (line.depth * 16).dp),
                    )
                }
                if (state.unresolved.isNotEmpty()) {
                    Text("Not reachable", style = MaterialTheme.typography.titleSmall)
                    state.unresolved.take(UNRESOLVED_SHOWN).forEach {
                        Text(describe(it), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.unresolved.size > UNRESOLVED_SHOWN) {
                        val more = state.unresolved.size - UNRESOLVED_SHOWN
                        Text("and $more more", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onImport, modifier = Modifier.semantics { contentDescription = "Import these files" }) {
                Text("Import")
            }
        },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private const val UNRESOLVED_SHOWN = 5

/** Where a link is written and what became of it, in the words of the file: nothing else is named. */
internal fun describe(link: UnresolvedLink): String {
    val where = if (link.line > 0) "${link.file}, line ${link.line}" else link.file
    val why = when (link.problem) {
        LinkProblem.NOT_FOUND -> "could not be found"
        LinkProblem.NO_MATCH -> "matched no file"
        LinkProblem.ABSOLUTE -> "is an absolute path, which is not followed"
        LinkProblem.OUTSIDE -> "is outside the folder you allowed"
        LinkProblem.NOT_TEXT -> "is not text"
        LinkProblem.CASE_COLLISION -> "has the same name as another linked file in a different case"
    }
    return "$where: ${link.text} $why"
}

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "${bytes / 1000} KB"
    else -> "$bytes B"
}
