package io.github.pintofi.beanbook.app.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.github.pintofi.beanbook.app.storage.ContextHolder
import io.github.pintofi.beanbook.core.data.ArchiveEncryption
import io.github.pintofi.beanbook.core.data.ArchiveProblem
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.BackupManifest
import io.github.pintofi.beanbook.core.data.BackupRetention
import io.github.pintofi.beanbook.core.data.ProjectRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.RestorePlan
import io.github.pintofi.beanbook.core.data.RestoreStaged
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backing up and restoring, from the app's point of view.
 *
 * Everything that decides whether data survives lives in `:core-data` and is tested on the
 * JVM. What is here is the part that needs Android: writing through a `content://` URI the
 * user picked, and keeping the UI honest about what happened.
 */
class BackupController(
    private val workspace: ProjectWorkspace,
    private val registry: ProjectRegistry,
    private val scope: CoroutineScope,
    private val appVersion: String,
    /** Engine versions for the manifest; empty when the engine is not running. */
    private val engineVersions: suspend () -> Map<String, String> = { emptyMap() },
) {
    private val archives = BackupArchive(workspace)
    private val restores = RestorePlan(workspace)
    private val derivation = Argon2idKeyDerivation()

    /** Set when a restore stopped because the archive is encrypted and needs a passphrase. */
    private val _passphraseNeeded = MutableStateFlow(false)
    val passphraseNeeded: StateFlow<Boolean> = _passphraseNeeded.asStateFlow()

    /** The archive a passphrase is being asked for, kept so the retry has something to open. */
    private var pendingArchive: File? = null

    /** A restore waiting for the user to accept or discard it. */
    private val _pendingRestore = MutableStateFlow<RestoreStaged.Ready?>(null)
    val pendingRestore: StateFlow<RestoreStaged.Ready?> = _pendingRestore.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The default name for a new archive: sortable, and obvious a year later. */
    fun suggestedName(projectId: String, now: Instant = Instant.now()): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
            .withZone(ZoneId.systemDefault())
            .format(now)
        return "$projectId-$stamp.bcaz"
    }

    /**
     * Writes a backup of [projectId] to a document the user chose.
     *
     * Partial failure is reported as failure: if the write throws part-way, the message says
     * so rather than claiming a backup exists (docs/storage-and-backup.md §7).
     */
    fun backupTo(
        resolver: ContentResolver,
        uri: Uri,
        projectId: String,
        passphrase: CharArray? = null,
    ) {
        // Copied synchronously, before the coroutine starts. The caller is entitled to wipe
        // its own array the moment this returns — and does — so reading it later would derive
        // a key from a wiped buffer and produce an archive nobody can open. That is exactly
        // what happened the first time this ran on a device.
        val ownPassphrase = passphrase?.copyOf()

        scope.launch {
            _message.value =
                if (passphrase == null) "Backing up…" else "Encrypting and backing up…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val title = registry.summarise(projectId).title
                    val engines = runCatching { engineVersions() }.getOrDefault(emptyMap())
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        archives.write(
                            projectId = projectId,
                            out = out,
                            appVersion = appVersion,
                            engineVersions = engines,
                            projectTitle = title,
                            passphrase = ownPassphrase,
                            derivation = if (ownPassphrase == null) null else derivation,
                        )
                    } ?: error("the provider refused to write there")
                }
            }
            ownPassphrase?.fill('\u0000')
            _message.value = result.fold(
                onSuccess = { manifest -> describe(manifest) },
                onFailure = { failure ->
                    Log.w(TAG, "backup failed", failure)
                    // Opening the destination for writing empties it, so a failure part-way
                    // leaves a truncated file there. Removed when the provider allows it, and
                    // said plainly when it does not.
                    val removed = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                        .getOrDefault(false)
                    "Backup failed: ${failure.message}. " +
                        if (removed) {
                            "The unfinished file was removed."
                        } else {
                            "The file you chose may hold a partial copy; do not rely on it."
                        }
                },
            )
        }
    }

    private fun describe(manifest: BackupManifest): String {
        val size = when {
            manifest.totalBytes >= 1_000_000 -> "%.1f MB".format(manifest.totalBytes / 1e6)
            manifest.totalBytes >= 1_000 -> "${manifest.totalBytes / 1000} KB"
            else -> "${manifest.totalBytes} B"
        }
        val sealed = if (manifest.encryption is ArchiveEncryption.None) "" else ", encrypted"
        return "Backed up ${manifest.files.size} file(s), $size$sealed."
    }

    /**
     * Verifies an archive and stages it, then reports what restoring it would change.
     *
     * Nothing live is touched here: the user sees the diff first and decides
     * ([acceptRestore] / [discardRestore]).
     */
    fun stageRestore(
        resolver: ContentResolver,
        uri: Uri,
        projectId: String,
        passphrase: CharArray? = null,
    ) {
        val ownStagePassphrase = passphrase?.copyOf()
        scope.launch {
            _message.value = "Checking the backup…"
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    // Copied into the cache first: verification reads the archive several
                    // times, and a content:// stream is not reliably seekable.
                    val local = File(ContextHolder.require().cacheDir, "restore.bcaz")
                    resolver.openInputStream(uri)?.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("the provider refused to open that file")
                    pendingArchive = local
                    restores.stage(projectId, local, ownStagePassphrase, derivation)
                }
            }

            staged.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is RestoreStaged.Ready -> {
                            _pendingRestore.value = outcome
                            _passphraseNeeded.value = false
                            _message.value = outcome.diff.describe()
                            dropArchiveCopy()
                        }
                        is RestoreStaged.Refused -> {
                            _pendingRestore.value = null
                            // An encrypted archive is not a failure — it is a question.
                            _passphraseNeeded.value =
                                outcome.problems.any {
                                    it is ArchiveProblem.PassphraseNeeded ||
                                        it is ArchiveProblem.WrongPassphrase
                                }
                            _message.value = outcome.problems.first().describe()
                        }
                    }
                },
                onFailure = {
                    _pendingRestore.value = null
                    dropArchiveCopy()
                    _message.value = ArchiveProblem
                        .Unreadable(it.message ?: it.javaClass.simpleName)
                        .describe()
                },
            )
        }
    }

    /** Retries a staged restore with the passphrase the user has now typed. */
    fun retryRestoreWithPassphrase(projectId: String, passphrase: CharArray) {
        val archive = pendingArchive ?: return
        val ownPassphrase = passphrase.copyOf()
        scope.launch {
            _message.value = "Opening the backup…"
            val staged = withContext(Dispatchers.IO) {
                runCatching { restores.stage(projectId, archive, ownPassphrase, derivation) }
            }
            ownPassphrase.fill('\u0000')
            staged.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is RestoreStaged.Ready -> {
                            _pendingRestore.value = outcome
                            _passphraseNeeded.value = false
                            _message.value = outcome.diff.describe()
                            dropArchiveCopy()
                        }
                        is RestoreStaged.Refused -> {
                            _passphraseNeeded.value = true
                            _message.value = outcome.problems.first().describe()
                        }
                    }
                },
                onFailure = { _message.value = "Could not open that backup: ${it.message}" },
            )
        }
    }

    /** Puts the staged restore in place, keeping the previous project as a rollback point. */
    fun acceptRestore(projectId: String, onRestored: () -> Unit) {
        val staged = _pendingRestore.value ?: return
        // Cleared and announced at once, not when the copy ends: the Restore button stays on
        // screen for as long as the staged restore is pending, and a second tap during a
        // long copy would start a second restore over the first.
        _pendingRestore.value = null
        _message.value = "Restoring…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { restores.activate(projectId, staged.staging) }
            }
            _message.value = result.fold(
                onSuccess = { kept ->
                    "Restored. Your previous project is kept as ${kept.name} until you remove it."
                },
                onFailure = { "Restore failed: ${it.message}. Your project is unchanged." },
            )
            if (result.isSuccess) onRestored()
        }
    }

    /**
     * Deletes the cache copy of the archive being restored.
     *
     * It is a full copy of someone's ledger (readable, when the archive is not encrypted), and
     * once the restore is staged -- or has failed for good -- nothing needs it. It is kept
     * only while a passphrase is still being asked for, because the retry opens it again.
     */
    private fun dropArchiveCopy() {
        val copy = pendingArchive ?: return
        pendingArchive = null
        scope.launch(Dispatchers.IO) { copy.delete() }
    }

    fun discardRestore(projectId: String) {
        dropArchiveCopy()
        scope.launch {
            withContext(Dispatchers.IO) { restores.discardStaging(projectId) }
            _pendingRestore.value = null
            _message.value = "Restore cancelled. Nothing was changed."
        }
    }

    fun rollbackPoints(projectId: String): List<String> =
        restores.rollbackPoints(projectId).map { it.name }

    /**
     * Applies retention to the backups in a folder the user chose.
     *
     * Only files this app wrote are considered — a `.bcaz` with a manifest naming this
     * project. Nothing else in that folder is ever touched, because it is the user's folder
     * and may hold anything.
     */
    fun applyRetention(
        folder: DocumentFile,
        projectId: String,
        retention: BackupRetention,
        now: Instant = Instant.now(),
    ): List<String> {
        val candidates = folder.listFiles()
            .filter { it.isFile && it.name?.endsWith(".bcaz") == true }
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                val local = File(ContextHolder.require().cacheDir, "retention-check.bcaz")
                try {
                    runCatching {
                        ContextHolder.require().contentResolver.openInputStream(document.uri)
                            ?.use { input -> local.outputStream().use { input.copyTo(it) } }
                        val manifest = archives.peek(local) ?: return@mapNotNull null
                        if (manifest.projectId != projectId) return@mapNotNull null
                        document to BackupRetention.Archive(name, manifest.createdAt)
                    }.getOrNull()
                } finally {
                    local.delete()
                }
            }

        val expired = retention.expired(candidates.map { it.second }, now).map { it.name }.toSet()
        return candidates
            .filter { it.second.name in expired }
            .mapNotNull { (document, archive) -> archive.name.takeIf { document.delete() } }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "BackupController"
    }
}
