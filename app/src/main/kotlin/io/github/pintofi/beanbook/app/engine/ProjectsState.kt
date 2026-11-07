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
    /** Reads a staged new ledger with Beancount before it is put in place; null where there is no engine. */
    private val validator: io.github.pintofi.beanbook.core.data.LedgerValidator? = null,
) {
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _onboarding = MutableStateFlow(false)

    /** True while the setup is on screen with a project open, because a new one is being added. */
    val onboarding: StateFlow<Boolean> = _onboarding.asStateFlow()

    /** Only one import at a time: a second one started on top of the first picked the same id. */
    private val importing = java.util.concurrent.atomic.AtomicBoolean(false)

    private val restores = io.github.pintofi.beanbook.core.data.RestorePlan(workspace)
    private val derivation = io.github.pintofi.beanbook.app.backup.Argon2idKeyDerivation()

    /** A backup chosen to be restored as a new project, kept while its passphrase is asked for. */
    private var heldBackup: File? = null
    private val _backupLocked = MutableStateFlow(false)

    /** True while a chosen backup is encrypted and waiting for its passphrase. */
    val backupLocked: StateFlow<Boolean> = _backupLocked.asStateFlow()

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
        _onboarding.value = false
        _message.value = null
    }

    /** Shows the setup for a new ledger even though a project is open. */
    fun startOnboarding() {
        _onboarding.value = true
    }

    /** Leaves the setup for the project that was open. */
    fun endOnboarding() {
        _onboarding.value = false
    }

    /**
     * Creates a ledger from the answers the first-run setup collected, and has Beancount read it before
     * it is put in place. The result is the new project's id, or the reason it was not
     * made: a ledger the app generated that Beancount will not read is the app's mistake, and
     * nothing is left behind.
     */
    suspend fun createFromBlueprint(
        blueprint: io.github.pintofi.beanbook.core.domain.LedgerBlueprint,
    ): Result<String> = withContext(Dispatchers.IO) {
        val id = ProjectRegistry.idFor(blueprint.title, workspace.takenIds())
        runCatching {
            workspace.createFromBlueprint(id, blueprint) { staged ->
                validator?.validate(staged.absolutePath)
                    ?: Result.failure(io.github.pintofi.beanbook.core.data.LedgerUnavailable("no engine"))
            }
            id
        }.onSuccess {
            // Listed, not opened: opening takes the person off the setup, and the Finish page is
            // where they are told it worked.
            refresh()
        }.onFailure { Log.w(TAG, "creating a ledger failed", it) }
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
        if (!copyChosen(resolver, archive, local)) {
            local.delete()
            _message.value = "Could not read that: could not read the chosen file"
            return
        }
        // A backup is restored, not imported: its files sit under project/ beside a manifest.
        if (io.github.pintofi.beanbook.core.data.looksLikeBackup(local)) {
            heldBackup = local
            restoreHeldBackup(null, onOpened)
            return
        }
        import(onOpened, cleanup = { local.delete() }) {
            ZipImportSource(local) to (archive.lastPathSegment ?: "imported")
        }
    }

    private fun copyChosen(resolver: ContentResolver, from: Uri, to: File): Boolean = runCatching {
        resolver.openInputStream(from)?.use { input -> to.outputStream().use { input.copyTo(it) } } != null
    }.getOrDefault(false)

    /** Retries the held backup with the passphrase just typed. */
    fun unlockBackup(passphrase: CharArray, onOpened: (String) -> Unit) = restoreHeldBackup(passphrase, onOpened)

    /** Gives up on the held backup and removes the copy of it. */
    fun cancelBackup() {
        heldBackup?.delete()
        heldBackup = null
        _backupLocked.value = false
        _message.value = "Nothing was restored."
    }

    /** Restores the held backup as a project of its own, asking for a passphrase when it needs one. */
    private fun restoreHeldBackup(passphrase: CharArray?, onOpened: (String) -> Unit) {
        val archive = heldBackup ?: return
        val firstRun = activeId.value == null || onboarding.value
        if (!importing.compareAndSet(false, true)) {
            _message.value = "Still reading the last one. Wait for it to finish."
            return
        }
        val own = passphrase?.copyOf()
        scope.launch {
            try {
                _message.value = "Restoring the backup…"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { restores.restoreAsNew(archive, own, if (own == null) null else derivation) }
                }
                own?.fill('\u0000')
                outcome.fold(
                    onSuccess = { result ->
                        when (result) {
                            is io.github.pintofi.beanbook.core.data.RestoredNew.Restored -> {
                                heldBackup = null
                                _backupLocked.value = false
                                withContext(Dispatchers.IO) { archive.delete() }
                                refresh()
                                if (!firstRun) open(result.projectId)
                                onOpened(result.projectId)
                                _message.value = "Restored ${result.manifest.files.size} file(s) as " +
                                    "${result.manifest.projectTitle}."
                            }
                            is io.github.pintofi.beanbook.core.data.RestoredNew.Refused -> {
                                val asks = result.problems.any {
                                    it is io.github.pintofi.beanbook.core.data.ArchiveProblem.PassphraseNeeded ||
                                        it is io.github.pintofi.beanbook.core.data.ArchiveProblem.WrongPassphrase
                                }
                                _backupLocked.value = asks
                                if (!asks) {
                                    heldBackup = null
                                    withContext(Dispatchers.IO) { archive.delete() }
                                }
                                _message.value = result.problems.first().describe()
                            }
                        }
                    },
                    onFailure = { error ->
                        Log.w(TAG, "restore as a new project failed", error)
                        heldBackup = null
                        _backupLocked.value = false
                        withContext(Dispatchers.IO) { archive.delete() }
                        _message.value = "Could not restore that: ${error.message ?: "unknown error"}"
                    },
                )
            } finally {
                importing.set(false)
            }
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
        // From the first-run screen (nothing open yet) the import is not opened for the caller: the
        // setup's last page says what came in and asks what to do next.
        val firstRun = activeId.value == null || onboarding.value
        if (!importing.compareAndSet(false, true)) {
            _message.value = "Still reading the last one. Wait for it to finish."
            return
        }
        scope.launch {
            try {
                _message.value = "Reading…"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        try {
                            val (source, name) = open()
                            val id = ProjectRegistry.idFor(name, workspace.takenIds())
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
                                if (!firstRun) open(id)
                                onOpened(id)
                                "Imported ${result.fileCount} file(s)." + when {
                                    result.rootName == null -> ""
                                    result.rootMoved ->
                                        " Your main ledger, ${result.rootName}, is now called main.beancount."
                                    else ->
                                        " main.beancount includes your main ledger, ${result.rootName}, " +
                                            "which stays where it was."
                                }
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
            } finally {
                importing.set(false)
            }
        }
    }

    /**
     * Moves a project to the trash. Deleting one that is not open changes nothing about the open
     * one. Deleting the open one opens the next, and [onNowOpen] gets its id so the caller can load it;
     * only when it was the last project is nothing left open, and the first-run screen shows.
     */
    fun delete(projectId: String, onNowOpen: (String) -> Unit = {}) {
        scope.launch {
            val wasOpen = activeId.value == projectId
            val trashed = withContext(Dispatchers.IO) { registry.moveToTrash(projectId) }
            refresh()
            if (wasOpen) activeId.value?.let(onNowOpen)
            _message.value = "Moved to the trash. Nothing was deleted from any export you made."
            Log.i(TAG, "project moved to trash: ${trashed.name}")
        }
    }

    /**
     * Puts a deleted project back. If a newer project has taken its id it comes back under the
     * next free one, and the message says so. [onRestored] gets the id, so the first-run screen
     * can open the project it just brought back.
     */
    fun restore(folder: String, onRestored: (String) -> Unit = {}) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { registry.restoreFromTrash(folder) } }
            refresh()
            _message.value = outcome.fold(
                onSuccess = { id ->
                    val wanted = folder.substringBeforeLast('-')
                    if (id == wanted) "Restored \"$id\"." else "Restored as \"$id\", because \"$wanted\" is in use."
                },
                onFailure = { "Not restored: ${it.message}" },
            )
            outcome.getOrNull()?.let(onRestored)
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
