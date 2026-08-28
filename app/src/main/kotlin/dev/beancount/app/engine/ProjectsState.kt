package dev.beancount.app.engine

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import dev.beancount.app.storage.SafExportTarget
import dev.beancount.app.storage.SafImportSource
import dev.beancount.core.data.ImportResult
import dev.beancount.core.data.ProjectExporter
import dev.beancount.core.data.ProjectImporter
import dev.beancount.core.data.ProjectRegistry
import dev.beancount.core.data.ProjectSummary
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.data.ZipImportSource
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

    fun refresh() {
        _projects.value = registry.summaries()
        _activeId.value = registry.activeProjectId()
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
     * until the whole archive has been judged (docs/milestones/m5.md).
     */
    fun importArchive(resolver: ContentResolver, archive: Uri, cacheDir: File, onOpened: (String) -> Unit) =
        import(onOpened) {
            val local = File(cacheDir, "import-${System.currentTimeMillis()}.zip")
            resolver.openInputStream(archive)?.use { input ->
                local.outputStream().use { input.copyTo(it) }
            } ?: error("could not read the chosen file")
            ZipImportSource(local) to (archive.lastPathSegment ?: "imported")
        }

    /** Show the user a message that did not come from an operation this class ran. */
    fun report(text: String) {
        _message.value = text
    }

    /**
     * Writes a copy of a project into a folder the user chooses (SPEC.md §55, free tier).
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
    fun exportFolder(resolver: ContentResolver, tree: Uri, projectId: String) {
        scope.launch {
            _message.value = "Writing…"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ProjectExporter(workspace).exportFolder(projectId, SafExportTarget(resolver, tree))
                }
            }
            _message.value = outcome.fold(
                onSuccess = { count ->
                    "Exported $count file(s) to the folder you chose. This copy is not kept " +
                        "in step with the original — use Mirror for that."
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
        open: () -> Pair<dev.beancount.core.data.ImportSource, String>,
    ) {
        scope.launch {
            _message.value = "Reading…"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val (source, name) = open()
                    val id = ProjectRegistry.idFor(name, workspace.listProjects().toSet())
                    importer.import(source, id) to id
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

    private companion object {
        const val TAG = "ProjectsState"
    }
}
