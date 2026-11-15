package io.github.pintofi.beanbook.app.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.pintofi.beanbook.app.engine.ProjectsState
import io.github.pintofi.beanbook.app.storage.DocumentIds
import io.github.pintofi.beanbook.app.storage.SingleDocumentSource
import io.github.pintofi.beanbook.app.storage.TreeLinkedSource
import io.github.pintofi.beanbook.core.data.ImportPreview
import io.github.pintofi.beanbook.core.data.PreviewLine
import io.github.pintofi.beanbook.core.domain.BeancountText
import io.github.pintofi.beanbook.core.domain.LedgerLinks
import io.github.pintofi.beanbook.core.domain.LinkProblem
import io.github.pintofi.beanbook.core.domain.LinkedLedger
import io.github.pintofi.beanbook.core.domain.LinkedSource
import io.github.pintofi.beanbook.core.domain.UnresolvedLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the import dialogs show, from choosing the file to the answer. */
sealed interface ImportState {
    data object Idle : ImportState

    /** Asks the host to open the system file picker. */
    data object PickFile : ImportState

    /** The picker is open; nothing to show. */
    data object Waiting : ImportState

    data class Reading(val name: String) : ImportState

    /** The file links to others, and Android has to be told which folder they are in. */
    data class NeedsFolder(
        val name: String,
        val links: Int,
        /** How many folders above the file a link wants to reach; 0 when none does. */
        val up: Int,
        val problem: String? = null,
    ) : ImportState

    /** Asks the host to open the folder picker, starting where the file is. */
    data class PickFolder(val initial: Uri, val name: String, val links: Int, val up: Int) : ImportState

    data class Preview(
        val name: String,
        val lines: List<PreviewLine>,
        val fileCount: Int,
        val totalBytes: Long,
        val unresolved: List<UnresolvedLink>,
    ) : ImportState

    data class Failed(val message: String) : ImportState
}

/**
 * Importing a ledger: the person selects one file; the app follows what it links to, shows exactly which
 * files will come in, and imports them only when they tap Import.
 *
 * A single-file grant reaches only that file, so when the file links to others the person is asked to allow
 * the folder that holds it. The folder is used for this import only (no persistable grant is taken), a
 * folder's entries are read only where a link points into it, and nothing in it is written, listed or
 * kept. If they decline, the file can come in alone and every link is reported as not reachable.
 */
class ImportController(
    private val context: Context,
    private val projects: ProjectsState,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** What is held between the steps: the chosen file, and what has been read of it. */
    private var uri: Uri? = null
    private var name = ""
    private var links = 0
    private var up = 0
    private var ready: Ready? = null

    private class Ready(val source: LinkedSource, val ledger: LinkedLedger, val mainFolders: List<String>)

    /** Start: open the file picker. */
    fun chooseFile() {
        _state.value = ImportState.PickFile
    }

    /** The host has opened the picker. */
    fun pickerOpened() {
        if (_state.value is ImportState.PickFile || _state.value is ImportState.PickFolder) {
            _state.value = ImportState.Waiting
        }
    }

    fun fileChosen(chosen: Uri?) {
        if (chosen == null) {
            _state.value = ImportState.Idle
            return
        }
        val resolver = context.contentResolver
        val display = displayName(chosen)
        uri = chosen
        name = display
        _state.value = ImportState.Reading(display)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val single = SingleDocumentSource(resolver, chosen, display)
                    val ledger = LedgerLinks(single).walk(display)
                    val text = single.read(display)?.decodeToString().orEmpty()
                    Triple(single, ledger, BeancountText.documentRoots(text).isNotEmpty())
                }
            }
            outcome.fold(
                onSuccess = { (single, ledger, hasDocumentsRoot) -> afterFirstRead(single, ledger, hasDocumentsRoot) },
                onFailure = { _state.value = ImportState.Failed("Could not read that file: ${it.message}") },
            )
        }
    }

    private fun afterFirstRead(single: LinkedSource, ledger: LinkedLedger, hasDocumentsRoot: Boolean) {
        if (ledger.files.isEmpty()) {
            val notText = ledger.unresolved.any { it.problem == LinkProblem.NOT_TEXT }
            _state.value = ImportState.Failed(
                if (notText) "That file is not text, so it is not a Beancount file." else "Could not read that file.",
            )
            return
        }
        val reachable = ledger.unresolved.filter { it.problem != LinkProblem.ABSOLUTE }
        links = reachable.size + if (hasDocumentsRoot) 1 else 0
        up = reachable.filter { it.problem == LinkProblem.OUTSIDE }.maxOfOrNull { it.levelsUp } ?: 0
        if (links == 0) {
            showPreview(single, ledger, emptyList())
        } else {
            ready = Ready(single, ledger, emptyList())
            _state.value = ImportState.NeedsFolder(name, links, up)
        }
    }

    /** Allow the folder: open the folder picker where the file is. */
    fun chooseFolder() {
        val chosen = uri ?: return
        _state.value = ImportState.PickFolder(chosen, name, links, up)
    }

    /** Go on with the file alone, every link reported as out of reach. */
    fun importAlone() {
        val held = ready ?: return
        showPreview(held.source, held.ledger, emptyList())
    }

    fun folderChosen(tree: Uri?) {
        val file = uri
        if (tree == null || file == null) {
            _state.value = ImportState.NeedsFolder(name, links, up)
            return
        }
        _state.value = ImportState.Reading(name)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { readWithin(tree, file) } }
            outcome.fold(
                onSuccess = { found ->
                    if (found == null) {
                        _state.value = ImportState.NeedsFolder(
                            name, links, up,
                            problem = "Beanbook could not find $name inside that folder. Choose the folder " +
                                "that holds it.",
                        )
                    } else {
                        showPreview(found.source, found.ledger, found.mainFolders)
                    }
                },
                onFailure = { _state.value = ImportState.Failed("Could not read that folder: ${it.message}") },
            )
        }
    }

    private fun readWithin(tree: Uri, file: Uri): Ready? {
        val resolver = context.contentResolver
        val root = DocumentFile.fromTreeUri(context, tree) ?: return null
        val below = DocumentIds.below(
            DocumentsContract.getTreeDocumentId(tree), DocumentsContract.getDocumentId(file),
        ) ?: return null
        val mainDir = below.dropLast(1)
        val source = TreeLinkedSource(resolver, root, mainDir)
        val mainName = below.last()
        val ledger = LedgerLinks(source, maxUp = mainDir.size).walk(mainName)
        if (ledger.files.isEmpty()) return null
        return Ready(source, ledger, mainDir.takeLast(ledger.levelsUp))
    }

    private fun showPreview(source: LinkedSource, ledger: LinkedLedger, mainFolders: List<String>) {
        ready = Ready(source, ledger, mainFolders)
        _state.value = ImportState.Preview(
            name = name,
            lines = ImportPreview.lines(ledger, mainFolders),
            fileCount = ledger.files.size,
            totalBytes = ledger.totalBytes,
            unresolved = ledger.unresolved,
        )
    }

    /** Import: nothing is copied until now. */
    fun confirm(onOpened: (String) -> Unit) {
        val held = ready ?: return
        _state.value = ImportState.Idle
        projects.importLinked(held.source, held.ledger, held.mainFolders, name.substringBeforeLast('.'), onOpened)
        ready = null
    }

    fun cancel() {
        ready = null
        uri = null
        _state.value = ImportState.Idle
    }

    private fun displayName(chosen: Uri): String =
        context.contentResolver.query(chosen, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: chosen.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "ledger" }
}
