package dev.beancount.app.mirror

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.beancount.core.data.BackupArchive
import dev.beancount.core.data.MirrorAction
import dev.beancount.core.data.MirrorPlan
import dev.beancount.core.data.MirrorPlanResult
import dev.beancount.core.data.MirrorState
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.data.WriteJournal
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Auto-mirror: the project, copied out to a folder the user chose
 * (docs/storage-and-backup.md §6).
 *
 * The private workspace stays canonical. This is a copy *out*, plus the ability to take
 * changes back in — not a second source of truth, and never a merge. Every case the app
 * cannot decide by itself becomes a conflict the user resolves, with both versions kept.
 *
 * Three rules hold this together:
 *
 *  * **Never while a write is in flight.** A pass is skipped if the journal has an open
 *    transaction: mirroring a file mid-rename would copy out something that is about to be
 *    rolled back.
 *  * **Content, not clocks.** `DocumentFile.lastModified()` is unreliable across providers,
 *    so every comparison is a SHA-256 of the bytes.
 *  * **One pass at a time.** Passes are serialised, because two overlapping ones would race
 *    on the same files and could resolve a conflict by accident.
 */
class MirrorController(
    private val context: Context,
    private val workspace: ProjectWorkspace,
    private val journal: WriteJournal,
    private val scope: CoroutineScope,
    private val settings: MirrorSettings,
) {
    private val state = MirrorState(workspace)
    private val pass = Mutex()

    private val _conflicts = MutableStateFlow<List<MirrorAction.Conflict>>(emptyList())
    val conflicts: StateFlow<List<MirrorAction.Conflict>> = _conflicts.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val folderUri: String? get() = settings.folderUri()

    val enabled: Boolean get() = settings.enabled() && folderUri != null

    fun setFolder(uri: String?) = settings.setFolder(uri)

    fun setEnabled(value: Boolean) = settings.setEnabled(value)

    /** Runs a pass if mirroring is on. Called after every successful commit, and by hand. */
    fun syncAfterCommit(projectId: String) {
        if (!enabled) return
        sync(projectId, announce = false)
    }

    fun sync(projectId: String, announce: Boolean = true) {
        val folder = folderUri ?: run {
            if (announce) _message.value = "Choose a folder to mirror into first."
            return
        }

        scope.launch {
            pass.withLock {
                if (journal.pending(projectId).isNotEmpty()) {
                    // A write is in flight; the next commit will trigger another pass.
                    Log.i(TAG, "mirror skipped: a write is in progress")
                    return@withLock
                }

                val outcome = withContext(Dispatchers.IO) {
                    runCatching { runPass(projectId, Uri.parse(folder)) }
                }
                outcome
                    .onSuccess { result ->
                        _conflicts.value = result.conflicts
                        _message.value = describe(result)
                    }
                    .onFailure { failure ->
                        Log.w(TAG, "mirror pass failed", failure)
                        _message.value =
                            "Mirroring failed: ${failure.message}. Your project is unchanged."
                    }
            }
        }
    }

    private fun describe(result: MirrorPlanResult): String = when {
        result.isClean -> "Everything is already in sync."
        result.conflicts.isNotEmpty() ->
            "${result.automatic.size} file(s) synced. " +
                "${result.conflicts.size} need(s) your decision — nothing was overwritten."
        else -> "${result.automatic.size} file(s) synced."
    }

    private fun runPass(projectId: String, folderUri: Uri): MirrorPlanResult {
        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("that folder is no longer reachable")
        val projectDir = workspace.projectDir(projectId)

        val local = hashLocal(projectDir)
        val externalFiles = enumerate(root)
        val external = externalFiles.mapValues { (_, document) -> hash(document) }
        val plan = MirrorPlan.plan(local, external, state.read(projectId))

        val synced = state.read(projectId).toMutableMap()
        // Files that already match on both sides are recorded, so a lost state file heals
        // itself on the next pass instead of re-copying everything for ever.
        local.forEach { (path, hash) -> if (external[path] == hash) synced[path] = hash }

        plan.actions.forEach { action ->
            when (action) {
                is MirrorAction.PushOut -> {
                    write(root, action.path, File(projectDir, action.path).readBytes())
                    synced[action.path] = local.getValue(action.path)
                }

                is MirrorAction.PullIn -> {
                    val bytes = read(externalFiles.getValue(action.path))
                    val target = File(projectDir, action.path)
                    target.parentFile?.mkdirs()
                    writeAtomically(target, bytes)
                    synced[action.path] = BackupArchive.sha256(bytes)
                }

                is MirrorAction.DeleteThere -> {
                    externalFiles[action.path]?.delete()
                    synced.remove(action.path)
                }

                is MirrorAction.Conflict -> preserveBothVersions(
                    action,
                    projectDir,
                    externalFiles[action.path],
                )
            }
        }

        state.write(projectId, synced)
        return plan
    }

    /**
     * Keeps the other side's version beside ours, so a conflict costs nothing while it waits.
     *
     * The copy is named `<file>.conflict-<timestamp>`, which is not a Beancount extension, so
     * it sits in the project visibly without being parsed or included.
     */
    private fun preserveBothVersions(
        conflict: MirrorAction.Conflict,
        projectDir: File,
        external: DocumentFile?,
    ) {
        if (external == null) return
        val copy = File(projectDir, "${conflict.path}.conflict-${Instant.now().epochSecond}")
        if (copy.exists()) return
        copy.parentFile?.mkdirs()
        writeAtomically(copy, read(external))
    }

    /** Resolves one conflict by keeping this device's version and copying it out. */
    fun keepMine(projectId: String, path: String) = resolve(projectId, path) { root, projectDir ->
        write(root, path, File(projectDir, path).readBytes())
    }

    /** Resolves one conflict by taking the folder's version. */
    fun keepTheirs(projectId: String, path: String) = resolve(projectId, path) { root, projectDir ->
        val document = enumerate(root)[path] ?: error("\"$path\" is no longer in your folder")
        val target = File(projectDir, path)
        target.parentFile?.mkdirs()
        writeAtomically(target, read(document))
    }

    private fun resolve(
        projectId: String,
        path: String,
        apply: (DocumentFile, File) -> Unit,
    ) {
        val folder = folderUri ?: return
        scope.launch {
            pass.withLock {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val root = DocumentFile.fromTreeUri(context, Uri.parse(folder))
                            ?: error("that folder is no longer reachable")
                        apply(root, workspace.projectDir(projectId))
                    }
                }
                outcome
                    .onSuccess {
                        _conflicts.value = _conflicts.value.filterNot { it.path == path }
                        _message.value = "\"$path\" resolved."
                    }
                    .onFailure { _message.value = "Could not resolve \"$path\": ${it.message}" }
            }
            // A fresh pass records the new agreement and picks up anything else.
            sync(projectId, announce = false)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun hashLocal(projectDir: File): Map<String, String> =
        if (!projectDir.isDirectory) {
            emptyMap()
        } else {
            projectDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(projectDir).path.replace(File.separatorChar, '/') }
                // Runtime state and the conflict copies themselves are ours, not the folder's.
                .filterNot { BackupArchive.isExcluded(it) || it.contains(".conflict-") }
                .associateWith { BackupArchive.sha256(File(projectDir, it)) }
        }

    private fun enumerate(root: DocumentFile): Map<String, DocumentFile> {
        val found = mutableMapOf<String, DocumentFile>()
        fun walk(directory: DocumentFile, prefix: String) {
            directory.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) walk(child, path) else found[path] = child
            }
        }
        walk(root, "")
        return found.filterNot { (path, _) ->
            BackupArchive.isExcluded(path) || path.contains(".conflict-")
        }
    }

    private fun hash(document: DocumentFile): String = BackupArchive.sha256(read(document))

    private fun read(document: DocumentFile): ByteArray =
        context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
            ?: error("could not read ${document.name}")

    private fun write(root: DocumentFile, path: String, bytes: ByteArray) {
        val segments = path.split('/')
        var directory = root
        segments.dropLast(1).forEach { name ->
            directory = directory.findFile(name)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(name)
                ?: error("could not create \"$name\" in your folder")
        }
        val name = segments.last()
        val existing = directory.findFile(name)
        val target = existing ?: directory.createFile("application/octet-stream", name)
            ?: error("could not create \"$path\" in your folder")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
            ?: error("the provider refused to write \"$path\"")
    }

    /** Local writes go through a temporary file, like every other durable write here. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.mirror-tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "MirrorController"
    }
}

/** Whether mirroring is on, and where it points. App state, outside any project. */
class MirrorSettings(private val stateDir: File) {

    private val folderFile get() = File(stateDir, "mirror-folder")
    private val enabledFile get() = File(stateDir, "mirror-enabled")

    fun folderUri(): String? = folderFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    fun setFolder(uri: String?) {
        stateDir.mkdirs()
        if (uri == null) folderFile.delete() else folderFile.writeText(uri)
    }

    fun enabled(): Boolean = enabledFile.takeIf { it.isFile }?.readText()?.trim() == "true"

    fun setEnabled(value: Boolean) {
        stateDir.mkdirs()
        enabledFile.writeText(value.toString())
    }
}
