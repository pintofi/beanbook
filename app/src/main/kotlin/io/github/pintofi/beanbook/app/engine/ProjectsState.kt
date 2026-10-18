package io.github.pintofi.beanbook.app.engine

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import io.github.pintofi.beanbook.app.storage.SafExportTarget
import io.github.pintofi.beanbook.app.storage.SafImportSource
import io.github.pintofi.beanbook.core.data.ImportResult
import io.github.pintofi.beanbook.core.data.ProjectExporter
import io.github.pintofi.beanbook.core.data.ProjectImporter
import io.github.pintofi.beanbook.core.data.ProjectRegistry
import io.github.pintofi.beanbook.core.data.ProjectSummary
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.TrashedProject
import io.github.pintofi.beanbook.core.data.ZipImportSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The project list, the active project, and importing new ones.
 *
 * Import runs off the main thread and reports its outcome in the user's words: a refused
 * archive explains *which* rule it broke (docs/storage-and-backup.md §4), because "import
 * failed" tells someone nothing about a file they may have spent years building.
 */
class ProjectsState(
    private val workspace: ProjectWorkspace,
    private val registry: ProjectRegistry,
    private val importer: ProjectImporter,
    private val scope: CoroutineScope,
) {
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _trash = MutableStateFlow<List<TrashedProject>>(emptyList())
    val trash: StateFlow<List<TrashedProject>> = _trash.asStateFlow()

    fun refresh() {
        _projects.value = registry.summaries()
        _activeId.value = registry.activeProjectId()
        _trash.value = registry.trashSummaries()
    }

    fun open(projectId: String) {
        registry.setActive(projectId)
        _activeId.value = projectId
        _message.value = null
    }

    fun createNew(title: String, onOpened: (String) -> Unit) {
        scope.launch {
            val id = ProjectRegistry.idFor(title, workspace.listProjects().toSet())
            withContext(Dispatchers.IO) { workspace.createProject(id, title = title.trim()) }
            refresh()
            open(id)
            onOpened(id)
        }
    }

    /** Imports a SAF folder tree. */
    fun importFolder(resolver: ContentResolver, tree: Uri, onOpened: (String) -> Unit) =
        import(onOpened) { SafImportSource(resolver, tree) to tree.lastPathSegment.orEmpty() }

    /**
     * Imports a `.zip`. The archive is copied into app storage first, because validation
     * uses `ZipFile`, which needs random access — and because nothing should be extracted
     * until the whole archive has been judged.
     */
    fun importArchive(resolver: ContentResolver, archive: Uri, cacheDir: File, onOpened: (String) -> Unit) {
        val local = File(cacheDir, "import-${System.currentTimeMillis()}.zip")
        // Deleted when the import ends, whatever happens: it is a whole copy of the ledger.
        import(onOpened, cleanup = { local.delete() }) {
            resolver.openInputStream(archive)?.use { input ->
                local.outputStream().use { input.copyTo(it) }
            } ?: error("could not read the chosen file")
            ZipImportSource(local) to (archive.lastPathSegment ?: "imported")
        }
    }

    /**
     * Writes a copy of a project into a folder the user chooses.
     *
     * Distinct from mirroring, which sets up an ongoing relationship with one folder. This is
     * a snapshot: it happens once, to wherever the user points it, and the app forgets about
     * it afterwards. Both exist because they answer different questions — "keep this in sync"
     * and "give me a copy".
     *
     * The count comes back from the exporter rather than being assumed, because a partial
     * export reported as a success is the failure that matters here (invariant 5: `include`
     * directives and document links must stay valid across an export).
     */
    fun exportFolder(resolver: ContentResolver, tree: Uri, projectId: String? = activeId.value) {
        if (projectId == null) {
            _message.value = "Open a project before exporting a copy of it."
            return
        }
        scope.launch {
            _message.value = "Writing…"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ProjectExporter(workspace).exportFolder(projectId, SafExportTarget(resolver, tree))
                }
            }
            _message.value = outcome.fold(
                onSuccess = { count ->
                    "Exported $count file(s). This copy is not kept in step with the " +
                        "original; use Mirror for that."
                },
                onFailure = {
                    // Named rather than generic: SAF providers fail for reasons the user can
                    // usually act on, and "export failed" tells them nothing.
                    "Nothing was exported: ${it.message ?: it::class.simpleName}"
                },
            )
        }
    }

    private fun import(
        onOpened: (String) -> Unit,
        cleanup: () -> Unit = {},
        open: () -> Pair<io.github.pintofi.beanbook.core.data.ImportSource, String>,
    ) {
        scope.launch {
            _message.value = "Reading…"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    try {
                        val (source, name) = open()
                        val id = ProjectRegistry.idFor(name, workspace.listProjects().toSet())
                        importer.import(source, id) to id
                    } finally {
                        cleanup()
                    }
                }
            }

            _message.value = outcome.fold(
                onSuccess = { (result, id) ->
                    when (result) {
                        is ImportResult.Imported -> {
                            refresh()
                            open(id)
                            onOpened(id)
                            "Imported ${result.fileCount} file(s)."
                        }
                        is ImportResult.Rejected ->
                            "Not imported. " + result.problems.take(3)
                                .joinToString("; ") { it.describe() } +
                                if (result.problems.size > 3) " (and ${result.problems.size - 3} more)" else ""
                        is ImportResult.NoLedgerFound ->
                            "No .beancount file found in that ${result.checked} file(s)."
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "import failed", error)
                    "Could not read that: ${error.message ?: "unknown error"}"
                },
            )
        }
    }

    fun delete(projectId: String) {
        scope.launch {
            val trashed = withContext(Dispatchers.IO) { registry.moveToTrash(projectId) }
            refresh()
            _message.value = "Moved to the trash. Nothing was deleted from any export you made."
            Log.i(TAG, "project moved to trash: ${trashed.name}")
        }
    }

    /** Puts a deleted project back. Refused, with the reason, if its id has been reused since. */
    fun restore(folder: String) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { registry.restoreFromTrash(folder) } }
            refresh()
            _message.value = outcome.fold(
                onSuccess = { "Restored \"$it\"." },
                onFailure = { "Not restored: ${it.message}" },
            )
        }
    }

    /** Deletes one trashed project for good. The caller has already asked. */
    fun deleteForever(folder: String) {
        scope.launch {
            withContext(Dispatchers.IO) { registry.deleteFromTrash(folder) }
            refresh()
            _message.value = "Deleted for good."
        }
    }

    /** Deletes everything in the trash for good. The caller has already asked. */
    fun emptyTrash() {
        scope.launch {
            val count = withContext(Dispatchers.IO) { registry.emptyTrash() }
            refresh()
            _message.value = "Emptied the trash: $count project${if (count == 1) "" else "s"} deleted for good."
        }
    }

    private companion object {
        const val TAG = "ProjectsState"
    }
}
