package dev.beancount.core.data

import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * What restoring an archive would change, file by file.
 *
 * Shown before anything is touched. A restore is the one operation where a mistake costs
 * work that cannot be typed again, so the user sees what will be added, what will be
 * replaced and — the one that matters most — **what exists now and is not in the backup**.
 */
data class RestoreDiff(
    val added: List<String>,
    val changed: List<String>,
    val removed: List<String>,
    val identical: List<String>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removed.isEmpty()

    /** A sentence for the confirmation, in plain terms and never rounded up to "no changes". */
    fun describe(): String = when {
        isEmpty -> "This backup matches your project exactly. Restoring would change nothing."
        else -> buildList {
            if (added.isNotEmpty()) add("${added.size} file(s) would be added")
            if (changed.isNotEmpty()) add("${changed.size} file(s) would be replaced")
            if (removed.isNotEmpty()) {
                add("${removed.size} file(s) in your project are not in this backup")
            }
        }.joinToString(", ") + "."
    }
}

/** The outcome of staging a restore. Nothing live has been touched in any of these cases. */
sealed interface RestoreStaged {
    data class Ready(
        val staging: File,
        val manifest: BackupManifest,
        val diff: RestoreDiff,
    ) : RestoreStaged

    data class Refused(val problems: List<ArchiveProblem>) : RestoreStaged
}

/**
 * Restores in two deliberate steps: **stage, then activate** (docs/storage-and-backup.md §7).
 *
 * Staging verifies the archive and extracts it beside the project. Activation is a pair of
 * renames, and the project that was there is *kept* as a rollback point rather than deleted.
 * Nothing overwrites the only good copy, ever.
 */
class RestorePlan(private val workspace: ProjectWorkspace) {

    private val archives = BackupArchive(workspace)

    /** The directory a restore is staged in. Beside the projects, never inside one. */
    fun stagingDir(projectId: String): File =
        workspace.projectsDir.resolve("$projectId$STAGING_SUFFIX")

    /**
     * Verifies [archive] and extracts it to a staging directory, then reports what applying
     * it would change. Live data is untouched whatever the outcome.
     */
    fun stage(
        projectId: String,
        archive: File,
        passphrase: CharArray? = null,
        derivation: KeyDerivation? = null,
    ): RestoreStaged {
        val staging = stagingDir(projectId)
        staging.deleteRecursively()

        val problems = archives.extract(archive, staging, passphrase, derivation)
        if (problems.isNotEmpty()) {
            staging.deleteRecursively()
            return RestoreStaged.Refused(problems)
        }

        val manifest = archives.peek(archive)
            ?: return RestoreStaged.Refused(listOf(ArchiveProblem.NotAnArchive))

        return RestoreStaged.Ready(staging, manifest, diff(projectId, staging))
    }

    /** Compares a staged restore against the project as it stands. */
    fun diff(projectId: String, staging: File): RestoreDiff {
        val current = workspace.projectDir(projectId)
        val currentFiles = relativeFiles(current)
        val stagedFiles = relativeFiles(staging)

        val added = stagedFiles.keys - currentFiles.keys
        val removed = currentFiles.keys - stagedFiles.keys
        val common = stagedFiles.keys intersect currentFiles.keys
        val (identical, changed) = common.partition { path ->
            currentFiles.getValue(path) == stagedFiles.getValue(path)
        }

        return RestoreDiff(
            added = added.sorted(),
            changed = changed.sorted(),
            removed = removed.sorted(),
            identical = identical.sorted(),
        )
    }

    /**
     * Puts a staged restore in place and keeps what was there.
     *
     * The previous project is renamed aside, not deleted — the user prunes it when they are
     * satisfied. If the second rename fails, the first is undone, so the project is never
     * left missing.
     *
     * @return the directory holding the previous version.
     */
    fun activate(projectId: String, staging: File, now: Instant = Instant.now()): File {
        require(staging.isDirectory) { "nothing staged to activate" }
        val live = workspace.projectDir(projectId)
        val keptAside = workspace.projectsDir.resolve(
            "$projectId$PREVIOUS_SUFFIX${now.toEpochMilli()}",
        )

        if (live.exists()) {
            check(live.renameTo(keptAside)) { "could not set the current project aside" }
        }
        if (!staging.renameTo(live)) {
            // Put it back exactly as it was rather than leaving no project at all.
            keptAside.renameTo(live)
            error("could not put the restored project in place")
        }
        return keptAside
    }

    /** Rollback points left by previous restores, newest first. */
    fun rollbackPoints(projectId: String): List<File> =
        workspace.projectsDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("$projectId$PREVIOUS_SUFFIX") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    fun discardStaging(projectId: String) {
        stagingDir(projectId).deleteRecursively()
    }

    private fun relativeFiles(root: File): Map<String, String> =
        if (!root.isDirectory) {
            emptyMap()
        } else {
            root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
                .filterNot { BackupArchive.isExcluded(it) }
                .associateWith { BackupArchive.sha256(File(root, it)) }
        }

    private companion object {
        const val STAGING_SUFFIX = ".restoring"
        const val PREVIOUS_SUFFIX = ".previous-"
    }
}

/**
 * Which backups to keep (docs/storage-and-backup.md §7).
 *
 * Retention deletes someone's backups, so the rules are conservative and explicit: the
 * newest [keepCount] are always kept whatever their age, and age only ever removes archives
 * beyond that. A configuration that would delete everything keeps the newest one anyway —
 * "no backups at all" is never the right answer to a retention setting.
 */
data class BackupRetention(
    val keepCount: Int = 10,
    val keepFor: Duration = Duration.ofDays(365),
) {
    data class Archive(val name: String, val createdAt: Instant)

    fun expired(archives: List<Archive>, now: Instant): List<Archive> {
        if (archives.isEmpty()) return emptyList()
        val newestFirst = archives.sortedByDescending { it.createdAt }
        val kept = newestFirst.take(maxOf(keepCount, 1)).toMutableList()
        val rest = newestFirst.drop(kept.size)

        val (stillFresh, tooOld) = rest.partition {
            Duration.between(it.createdAt, now) <= keepFor
        }
        kept += stillFresh
        return tooOld
    }

    fun keep(archives: List<Archive>, now: Instant): List<Archive> =
        archives - expired(archives, now).toSet()
}
