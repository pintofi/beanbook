package dev.beancount.core.data

import java.io.File
import java.util.Locale

/**
 * What the project picker shows. Deliberately cheap to produce: no engine, no parse.
 *
 * Opening the picker must never wait on Python — a cold engine start costs seconds
 * (docs/milestones/m3.md), and a list of projects is a filesystem question.
 */
data class ProjectSummary(
    val id: String,
    val title: String,
    val mainFile: File,
    val fileCount: Int,
    val totalBytes: Long,
    val lastModified: Long,
) {
    /** "3 files · 12 KB" — enough to recognise a project without opening it. */
    fun describe(): String {
        val size = when {
            totalBytes >= 1_000_000 -> "%.1f MB".format(totalBytes / 1e6)
            totalBytes >= 1_000 -> "${totalBytes / 1000} KB"
            else -> "$totalBytes B"
        }
        return "$fileCount file${if (fileCount == 1) "" else "s"} · $size"
    }
}

/**
 * Tracks which project is open and lists the others.
 *
 * The active project id is stored outside the project tree, like every other piece of
 * runtime state, so it is never exported or backed up with a ledger.
 */
class ProjectRegistry(
    private val workspace: ProjectWorkspace,
    private val stateDir: File,
) {

    private val activeFile: File get() = File(stateDir, "active-project")

    fun activeProjectId(): String? =
        activeFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { workspace.exists(it) }

    fun setActive(projectId: String) {
        require(workspace.exists(projectId)) { "no such project: $projectId" }
        stateDir.mkdirs()
        val tmp = File(stateDir, "active-project.tmp")
        tmp.writeText(projectId)
        tmp.renameTo(activeFile)
    }

    fun summaries(): List<ProjectSummary> =
        workspace.listProjects().map { summarise(it) }.sortedByDescending { it.lastModified }

    fun summarise(projectId: String): ProjectSummary {
        val dir = workspace.projectDir(projectId)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return ProjectSummary(
            id = projectId,
            title = readTitle(workspace.mainFile(projectId)) ?: projectId,
            mainFile = workspace.mainFile(projectId),
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            lastModified = files.maxOfOrNull { it.lastModified() } ?: 0L,
        )
    }

    /**
     * Reads `option "title"` without parsing the ledger — the picker needs a name, not a
     * balance sheet. Only the head of the file is read, so a 7.7 MB ledger costs nothing.
     */
    private fun readTitle(main: File): String? {
        if (!main.isFile) return null
        return main.useLines { lines ->
            lines.take(TITLE_SCAN_LINES)
                .firstOrNull { it.trimStart().startsWith("option \"title\"") }
                ?.substringAfter("option \"title\"")
                ?.trim()
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Moves a project to the trash rather than deleting it.
     *
     * Invariant 7: a destructive action must be confirmable *or* undoable. Deleting a
     * ledger is the most destructive thing in the app, so it is both — the UI confirms by
     * name, and this keeps the files so the decision can be reversed.
     */
    fun moveToTrash(projectId: String): File {
        require(workspace.exists(projectId)) { "no such project: $projectId" }
        val trash = File(stateDir, "trash").apply { mkdirs() }
        val target = File(trash, "$projectId-${System.currentTimeMillis()}")
        val source = workspace.projectDir(projectId)
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        }
        if (activeProjectId() == null) activeFile.delete()
        return target
    }

    fun restoreFromTrash(trashed: File): String {
        val projectId = trashed.name.substringBeforeLast('-')
        val target = workspace.projectDir(projectId)
        require(!target.exists()) { "a project with that id already exists: $projectId" }
        check(trashed.renameTo(target)) { "could not restore ${trashed.name}" }
        return projectId
    }

    fun trashed(): List<File> =
        File(stateDir, "trash").listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            .orEmpty()

    companion object {
        private const val TITLE_SCAN_LINES = 50

        /**
         * Turns a user's title into a directory name. Project ids are validated strictly by
         * [ProjectWorkspace], so this must produce something that passes: letters, digits,
         * hyphen and underscore only.
         */
        fun idFor(title: String, taken: Set<String>): String {
            val base = title.lowercase(Locale.ROOT)
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                .joinToString("")
                .trim('-')
                .replace(Regex("-+"), "-")
                .take(48)
                .ifBlank { "ledger" }

            if (base !in taken) return base
            var suffix = 2
            while ("$base-$suffix" in taken) suffix++
            return "$base-$suffix"
        }
    }
}
