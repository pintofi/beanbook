package io.github.pintofi.beanbook.app.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pintofi.beanbook.app.BeancountApplication
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.BackupRetention
import java.time.Duration
import java.time.Instant

/**
 * Scheduled backups (Tier 2), through `WorkManager`.
 *
 * **Android decides when this runs, and the UI says so.** A periodic work request is a
 * request: the system batches it against doze, battery and network state, and the interval
 * is a floor rather than a promise. Telling the user "daily at 3am" would be a lie; telling
 * them "about once a day, when the device is charging and idle" is the truth.
 *
 * A run that cannot write reports failure. It never records a backup that did not happen —
 * a backup someone believes in but does not have is worse than none at all.
 */
class ScheduledBackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? BeancountApplication ?: return Result.failure()
        val settings = app.backupSettings
        val folderUri = settings.folderUri() ?: return Result.failure()
        val projectId = app.registry.activeProjectId() ?: return Result.success()

        return runCatching {
            val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folderUri))
                ?: error("the backup folder is no longer reachable")
            val name = app.backupController.suggestedName(projectId)
            val document = folder.createFile(MIME_TYPE, name)
                ?: error("could not create $name in the backup folder")

            val title = app.registry.summarise(projectId).title
            applicationContext.contentResolver.openOutputStream(document.uri, "wt")?.use { out ->
                BackupArchive(app.workspace).write(
                    projectId = projectId,
                    out = out,
                    appVersion = app.appVersion,
                    projectTitle = title,
                )
            } ?: error("the provider refused to write $name")

            settings.recordRun(Instant.now(), success = true, detail = name)

            // Retention runs after a successful write, never before: pruning old backups
            // before the new one exists could leave the user with fewer than they asked for.
            val pruned = app.backupController.applyRetention(folder, projectId, settings.retention())
            if (pruned.isNotEmpty()) Log.i(TAG, "retention removed ${pruned.size} old backup(s)")
            Result.success()
        }.getOrElse { failure ->
            Log.w(TAG, "scheduled backup failed", failure)
            settings.recordRun(
                Instant.now(),
                success = false,
                detail = failure.message ?: failure.javaClass.simpleName,
            )
            // Retried with backoff: a missing folder may come back when the provider does.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduledBackup"
        private const val MIME_TYPE = "application/octet-stream"
        const val WORK_NAME = "scheduled-backup"

        fun schedule(context: Context, every: Duration = Duration.ofDays(1)) {
            val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(every)
                .setConstraints(
                    Constraints.Builder()
                        // Backing up a ledger with documents is not something to do on a
                        // failing battery, and it needs no network at all.
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/**
 * Where scheduled backups go, how many are kept, and what happened last time.
 *
 * Stored as plain text in app-private state, outside any project: it describes this device's
 * arrangement, not the ledger.
 */
class BackupSettings(private val stateDir: java.io.File) {

    private val folderFile get() = java.io.File(stateDir, "backup-folder")
    private val lastRunFile get() = java.io.File(stateDir, "backup-last-run")
    private val retentionFile get() = java.io.File(stateDir, "backup-retention")

    fun folderUri(): String? = folderFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    fun setFolder(uri: String?) {
        stateDir.mkdirs()
        if (uri == null) folderFile.delete() else folderFile.writeText(uri)
    }

    fun retention(): BackupRetention {
        val text = retentionFile.takeIf { it.isFile }?.readText()?.trim() ?: return BackupRetention()
        val parts = text.split(' ')
        val count = parts.getOrNull(0)?.toIntOrNull() ?: return BackupRetention()
        val days = parts.getOrNull(1)?.toLongOrNull() ?: 365
        return BackupRetention(keepCount = count, keepFor = Duration.ofDays(days))
    }

    fun setRetention(retention: BackupRetention) {
        stateDir.mkdirs()
        retentionFile.writeText("${retention.keepCount} ${retention.keepFor.toDays()}")
    }

    /** The last attempt, successful or not. Failures are kept: a silent failure is the bug. */
    fun recordRun(at: Instant, success: Boolean, detail: String) {
        stateDir.mkdirs()
        lastRunFile.writeText("${at.epochSecond}\n$success\n$detail\n")
    }

    fun lastRun(): String? {
        val lines = lastRunFile.takeIf { it.isFile }?.readLines() ?: return null
        val at = lines.getOrNull(0)?.toLongOrNull() ?: return null
        val success = lines.getOrNull(1)?.toBooleanStrictOrNull() ?: return null
        val detail = lines.getOrNull(2).orEmpty()
        val moment = Instant.ofEpochSecond(at)
        return if (success) {
            "Last backup: $moment ($detail)."
        } else {
            "Last backup attempt failed at $moment: $detail."
        }
    }
}
