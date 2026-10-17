package io.github.pintofi.beanbook.app.mirror

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.MirrorAction
import io.github.pintofi.beanbook.core.domain.PluginPreflight
import io.github.pintofi.beanbook.core.data.MirrorPlan
import io.github.pintofi.beanbook.core.data.MirrorPlanResult
import io.github.pintofi.beanbook.core.data.MirrorState
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.ProjectWriter
import io.github.pintofi.beanbook.core.data.WriteJournal
import io.github.pintofi.beanbook.core.data.WriteResult
import io.github.pintofi.beanbook.app.mirror.MirrorHistoryEntry.Kind
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
    private val writer: ProjectWriter,
    private val scope: CoroutineScope,
    private val settings: MirrorSettings,
) {
    private val state = MirrorState(workspace)
    private val pass = Mutex()

    private val _conflicts = MutableStateFlow<List<MirrorAction.Conflict>>(emptyList())
    val conflicts: StateFlow<List<MirrorAction.Conflict>> = _conflicts.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val historyStore = MirrorHistory(context.filesDir.resolve("state"))

    /** The last ten things the mirror did for the open project, newest first. */
    val history: StateFlow<List<MirrorHistoryEntry>> = historyStore.entries

    private val _status = MutableStateFlow<MirrorStatus>(MirrorStatus.Off)
    /** What mirroring is doing right now, for a screen that says so at all times. */
    val status: StateFlow<MirrorStatus> = _status.asStateFlow()

    init {
        _status.value = restingStatus()
    }

    val folderUri: String? get() = settings.folderUri()

    /**
     * Points the mirror at [projectId]'s own folder and forgets the previous project's
     * conflicts, which belong to files that are no longer open.
     */
    fun useProject(projectId: String) {
        settings.use(projectId)
        historyStore.use(projectId)
        _conflicts.value = emptyList()
        _message.value = null
        _status.value = restingStatus()
    }

    val enabled: Boolean get() = settings.enabled() && folderUri != null

    fun setFolder(uri: String?) {
        settings.setFolder(uri)
        _status.value = restingStatus()
    }

    fun setEnabled(value: Boolean) {
        settings.setEnabled(value)
        _status.value = restingStatus()
    }

    /**
     * What to show when no pass is running: off, waiting for a decision, or in step.
     *
     * A conflict outranks everything else. A folder chosen with the switch off is still
     * mirrored by hand, so that is "manual" rather than "off" -- the difference decides
     * whether the user should expect their folder to be current.
     */
    private fun restingStatus(): MirrorStatus = when {
        _conflicts.value.isNotEmpty() -> MirrorStatus.NeedsDecision(_conflicts.value.size)
        folderUri == null -> MirrorStatus.Off
        !settings.enabled() -> MirrorStatus.Manual(settings.lastPassAt())
        else -> MirrorStatus.Watching(settings.lastPassAt())
    }

    /** Runs a pass if mirroring is on. Called after every successful commit, and by hand. */
    fun syncAfterCommit(projectId: String) {
        if (!enabled) return
        sync(projectId, announce = false)
    }

    fun sync(projectId: String, announce: Boolean = true) {
        // A pass for a project the settings are not answering for would copy it into
        // another project's folder.
        if (settings.project != projectId) return
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

                _status.value = MirrorStatus.Comparing
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { runPass(projectId, Uri.parse(folder)) }
                }
                outcome
                    .onSuccess { result ->
                        recordHistory(result)
                        _conflicts.value = result.plan.conflicts
                        _message.value = describe(result)
                        settings.recordPass(System.currentTimeMillis())
                        _status.value = restingStatus()
                    }
                    .onFailure { failure ->
                        historyStore.record(
                            listOf(
                                MirrorHistoryEntry(
                                    System.currentTimeMillis(),
                                    MirrorHistoryEntry.Kind.FAILED,
                                    failure.message ?: "the folder could not be reached",
                                ),
                            ),
                        )
                        Log.w(TAG, "mirror pass failed", failure)
                        _message.value =
                            "Mirroring failed: ${failure.message}. Your project is unchanged."
                        _status.value = MirrorStatus.Failed(
                            failure.message ?: "the folder could not be reached",
                        )
                    }
            }
        }
    }

    /** File names and what happened to them, for the Mirror page's recent activity. */
    private fun recordHistory(result: MirrorPassOutcome) {
        val now = System.currentTimeMillis()
        val added = buildList {
            result.plan.pushes.forEach { add(MirrorHistoryEntry(now, Kind.SENT, it.path)) }
            result.committed.forEach { add(MirrorHistoryEntry(now, Kind.BROUGHT_IN, it)) }
            result.plan.actions.filterIsInstance<MirrorAction.DeleteThere>()
                .forEach { add(MirrorHistoryEntry(now, Kind.REMOVED, it.path)) }
            result.plan.conflicts.forEach { add(MirrorHistoryEntry(now, Kind.CONFLICT, it.path)) }
            result.refused.forEach { add(MirrorHistoryEntry(now, Kind.FAILED, it)) }
        }
        historyStore.record(added)
    }

    /**
     * What happened, counted from what committed rather than from what was planned.
     *
     * A pass used to report `plan.automatic.size` files synced whatever the writes did. A
     * file the write protocol refused or rolled back was still counted, so "3 file(s) synced"
     * could mean none of them were.
     */
    private fun describe(result: MirrorPassOutcome): String {
        val plan = result.plan
        val landed = plan.pushes.size + result.committed.size
        val refusedNote = if (result.refused.isEmpty()) {
            ""
        } else {
            " ${result.refused.size} could not be brought in and your project is unchanged " +
                "for those: ${result.refused.first()}"
        }
        return when {
            plan.isClean -> "Everything is already in sync."
            plan.conflicts.isNotEmpty() ->
                "$landed file(s) synced. ${plan.conflicts.size} need(s) your decision. " +
                    "Nothing was overwritten.$refusedNote"
            else -> "$landed file(s) synced.$refusedNote"
        }
    }

    private suspend fun runPass(projectId: String, folderUri: Uri): MirrorPassOutcome {
        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("that folder is no longer reachable")
        val projectDir = workspace.projectDir(projectId)

        val local = hashLocal(projectDir)
        val externalFiles = enumerate(root)
        val external = externalFiles.mapValues { (_, document) -> hash(document) }
        val plan = MirrorPlan.plan(local, external, state.read(projectId))

        val synced = state.read(projectId).toMutableMap()
        val committed = mutableListOf<String>()
        val refused = mutableListOf<String>()
        // Files that already match on both sides are recorded, so a lost state file heals
        // itself on the next pass instead of re-copying everything for ever.
        local.forEach { (path, hash) -> if (external[path] == hash) synced[path] = hash }

        var done = 0
        val total = plan.actions.size
        _status.value = if (total == 0) MirrorStatus.Comparing else MirrorStatus.Copying(0, total)
        plan.actions.forEach { action ->
            when (action) {
                is MirrorAction.PushOut -> {
                    write(root, action.path, File(projectDir, action.path).readBytes())
                    synced[action.path] = local.getValue(action.path)
                }

                is MirrorAction.PullIn -> {
                    // Through ProjectWriter, not a local helper: a file arriving from a folder
                    // this app does not control is a write to the user's project like any
                    // other, and needs the journal, the snapshot, the re-parse and the undo
                    // entry that go with one. Writing it directly and then reporting the pass
                    // as synced was invariants 2, 3 and 8 at once.
                    //
                    // Containment is ProjectWriter's own first check, so the explicit one that
                    // stood here is no longer needed.
                    val bytes = read(externalFiles.getValue(action.path))
                    val target = File(projectDir, action.path)
                    val result = writer.replaceBytes(
                        projectId = projectId,
                        file = target,
                        expectedSha = if (target.isFile) WriteJournal.sha256(target) else "",
                        newContent = bytes,
                        operation = "mirror-pull-in",
                    )
                    // Only what actually committed is recorded as agreed. Recording a refused
                    // or rolled-back file would tell the next pass the two sides match.
                    if (result is WriteResult.Committed) {
                        synced[action.path] = BackupArchive.sha256(bytes)
                        committed += action.path
                    } else {
                        refused += "${action.path}: ${describeFailure(result)}"
                    }
                }

                is MirrorAction.DeleteThere -> {
                    externalFiles[action.path]?.delete()
                    synced.remove(action.path)
                }

                is MirrorAction.Conflict -> preserveBothVersions(
                    projectId,
                    action,
                    projectDir,
                    externalFiles[action.path],
                )
            }
            done += 1
            _status.value = MirrorStatus.Copying(done, total)
        }

        state.write(projectId, synced)
        if (refused.isNotEmpty()) {
            Log.w(TAG, "mirror: ${refused.size} file(s) not pulled in: $refused")
        }
        return MirrorPassOutcome(plan, committed, refused)
    }

    /** What a pass planned, and what of it actually landed. */
    private data class MirrorPassOutcome(
        val plan: MirrorPlanResult,
        val committed: List<String>,
        val refused: List<String>,
    )

    private fun describeFailure(result: WriteResult): String = when (result) {
        is WriteResult.Refused -> result.reason
        is WriteResult.RolledBack -> result.reason
        is WriteResult.Committed -> "committed"
    }

    /**
     * Keeps the other side's version beside ours, so a conflict costs nothing while it waits.
     *
     * The copy is named `<file>.conflict-<timestamp>`, which is not a Beancount extension, so
     * it sits in the project visibly without being parsed or included.
     */
    private suspend fun preserveBothVersions(
        projectId: String,
        conflict: MirrorAction.Conflict,
        projectDir: File,
        external: DocumentFile?,
    ) {
        if (external == null) return
        val copy = File(projectDir, "${conflict.path}.conflict-${Instant.now().epochSecond}")
        if (copy.exists()) return
        // A conflict copy is a new file inside the user's project, so it goes through the
        // same protocol as any other. Its name is not a Beancount extension, so it is not
        // parsed or included, but it is still theirs and still has to be recoverable.
        val result = writer.replaceBytes(
            projectId = projectId,
            file = copy,
            expectedSha = "",
            newContent = read(external),
            operation = "mirror-conflict-copy",
        )
        // The history says "both versions kept", so it has to be true. Nothing is overwritten
        // while the conflict stands, but a copy that was refused is worth a line in the log.
        if (result !is WriteResult.Committed) {
            Log.w(TAG, "conflict copy of ${conflict.path} not kept: ${describeFailure(result)}")
        }
    }

    /** Resolves one conflict by keeping this device's version and copying it out. */
    fun keepMine(projectId: String, path: String) = resolve(projectId, path) { root, projectDir ->
        write(root, path, File(projectDir, path).readBytes())
    }

    /** Resolves one conflict by taking the folder's version. */
    fun keepTheirs(projectId: String, path: String) = resolve(projectId, path) { root, projectDir ->
        val document = enumerate(root)[path] ?: error("\"$path\" is no longer in your folder")
        val target = File(projectDir, path)
        val result = writer.replaceBytes(
            projectId = projectId,
            file = target,
            expectedSha = if (target.isFile) WriteJournal.sha256(target) else "",
            newContent = read(document),
            operation = "mirror-keep-theirs",
        )
        check(result is WriteResult.Committed) { describeFailure(result) }
    }

    private fun resolve(
        projectId: String,
        path: String,
        apply: suspend (DocumentFile, File) -> Unit,
    ) {
        val folder = folderUri ?: run {
            _message.value = "Choose the mirror folder again, then resolve \"$path\"."
            return
        }
        // Said at once: the choice waits for any sync pass already running, and a button
        // that answers only when that pass ends looked as if it had not been pressed.
        _message.value = "Resolving \"$path\"…"
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
                        _status.value = restingStatus()
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
            BackupArchive.isExcluded(path) || path.contains(".conflict-") ||
                // These names come from a DocumentsProvider's display-name column, which this
                // app does not control, and they are joined straight into a path under the
                // project directory. Every other place untrusted names reach the filesystem
                // checks this -- ProjectImporter, BackupArchive.extract, ProjectWriter.write
                // -- and the mirror did not. A name containing ".." would have written
                // outside the project on the way in.
                PluginPreflight.escapesRoot(path)
        }
    }

    /** Streamed, so a large PDF is hashed without being held in memory whole. */
    private fun hash(document: DocumentFile): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(document.uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: error("could not read ${document.name}")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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

    private companion object {
        const val TAG = "MirrorController"
    }
}

/**
 * Whether mirroring is on, and where it points, **for one project**.
 *
 * It used to be one folder for the whole app, so opening or creating a second project made
 * the mirror reconcile that project against the first one's folder: a new project pulled in
 * hundreds of the other ledger's files and wrote `.conflict-*` copies of them every pass.
 * Each project now has its own folder, switch and last-pass time. App state, outside any
 * project.
 */
class MirrorSettings(private val stateDir: File) {

    /** The project the settings answer for; null until one is opened. */
    @Volatile
    var project: String? = null
        private set

    /**
     * Makes [id] the project the settings answer for.
     *
     * A folder chosen before settings were per project is handed to the first project opened
     * afterwards, once: it was set up for the project in use at the time, and nothing else
     * can say which that was. Every other project starts with no folder.
     */
    fun use(id: String) {
        project = id
        val legacy = File(stateDir, "mirror-folder")
        if (legacy.isFile && !File(stateDir, "mirror-migrated").exists()) {
            stateDir.mkdirs()
            File(stateDir, "mirror-folder-$id").writeText(legacy.readText())
            File(stateDir, "mirror-enabled").takeIf { it.isFile }
                ?.let { File(stateDir, "mirror-enabled-$id").writeText(it.readText()) }
            File(stateDir, "mirror-last-pass").takeIf { it.isFile }
                ?.let { File(stateDir, "mirror-last-pass-$id").writeText(it.readText()) }
            File(stateDir, "mirror-migrated").writeText(id)
        }
    }

    private fun file(name: String): File? = project?.let { File(stateDir, "$name-$it") }

    fun folderUri(): String? =
        file("mirror-folder")?.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    fun setFolder(uri: String?) {
        val target = file("mirror-folder") ?: return
        stateDir.mkdirs()
        if (uri == null) target.delete() else target.writeText(uri)
    }

    fun enabled(): Boolean =
        file("mirror-enabled")?.takeIf { it.isFile }?.readText()?.trim() == "true"

    fun setEnabled(value: Boolean) {
        val target = file("mirror-enabled") ?: return
        stateDir.mkdirs()
        target.writeText(value.toString())
    }

    /** When the last pass finished, so a resting screen can say how current the folder is. */
    fun lastPassAt(): Long? =
        file("mirror-last-pass")?.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    fun recordPass(atEpochMs: Long) {
        val target = file("mirror-last-pass") ?: return
        stateDir.mkdirs()
        target.writeText(atEpochMs.toString())
    }
}

/**
 * What mirroring is doing, at every moment.
 *
 * The screen used to say nothing at all between passes: a message appeared after one and
 * stayed until something replaced it, so "3 file(s) synced" was on screen for a folder that
 * had since gone unreachable, and a pass in flight looked identical to one that had never
 * run.
 */
sealed interface MirrorStatus {

    /** No folder chosen. Nothing is being copied anywhere. */
    data object Off : MirrorStatus

    /** A folder is chosen but passes are not automatic: the user mirrors by hand. */
    data class Manual(val lastPassAtEpochMs: Long?) : MirrorStatus

    /** On, and passing after every change. */
    data class Watching(val lastPassAtEpochMs: Long?) : MirrorStatus

    /** Reading both sides. There is no count to show yet. */
    data object Comparing : MirrorStatus

    /** Applying the plan: [done] of [total] files. */
    data class Copying(val done: Int, val total: Int) : MirrorStatus

    /** Files changed in both places. Nothing is overwritten until the user chooses. */
    data class NeedsDecision(val count: Int) : MirrorStatus

    /** The last pass could not finish. The project is unchanged. */
    data class Failed(val reason: String) : MirrorStatus

    val isRunning: Boolean get() = this is Comparing || this is Copying
}
