package io.github.pintofi.beanbook.core.data

import java.io.File
import java.util.Locale

/**
 * What the project picker shows. Deliberately cheap to produce: no engine, no parse.
 *
 * Opening the picker must never wait on Python — a cold engine start costs seconds
 *, and a list of projects is a filesystem question.
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
 * A deleted project waiting in the trash. [folder] is its name there, which is all the
 * trash operations take: it is `<project id>-<time deleted>`.
 */
data class TrashedProject(
    val folder: String,
    val projectId: String,
    val title: String,
    val fileCount: Int,
    val totalBytes: Long,
    val trashedAt: Long,
) {
    fun describe(): String = ProjectSummary(
        id = projectId, title = title, mainFile = File(folder), fileCount = fileCount,
        totalBytes = totalBytes, lastModified = trashedAt,
    ).describe()
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
                .mapNotNull { TITLE_OPTION.find(it)?.groupValues?.get(1) }
                .firstOrNull()
                // The two escapes Beancount's strings have (a quote and a backslash), in one pass.
                ?.let { ESCAPE.replace(it) { match -> match.groupValues[1] } }
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

    /**
     * Puts a trashed project back and returns the id it now has. The id it had is reused when
     * it is free. When a newer project has taken it, the restored one gets the next free id
     * (`my-ledger-2`) rather than being refused: the trash is the only copy, and a name clash
     * is no reason to leave it there.
     */
    fun restoreFromTrash(trashed: File): String {
        val wanted = trashed.name.substringBeforeLast('-')
        val projectId = if (workspace.exists(wanted)) idFor(wanted, workspace.listProjects().toSet()) else wanted
        val target = workspace.projectDir(projectId)
        require(!target.exists()) { "a project with that id already exists: $projectId" }
        check(trashed.renameTo(target)) { "could not restore ${trashed.name}" }
        return projectId
    }

    /** What is in the trash: names, sizes and when each went in, newest first. */
    fun trashSummaries(): List<TrashedProject> = trashed().map { folder ->
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        TrashedProject(
            folder = folder.name,
            projectId = folder.name.substringBeforeLast('-'),
            title = readTitle(File(folder, ProjectWorkspace.MAIN_FILE)) ?: folder.name.substringBeforeLast('-'),
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            trashedAt = folder.name.substringAfterLast('-').toLongOrNull() ?: folder.lastModified(),
        )
    }

    /** Puts a trashed project back; returns its id, which differs from the old one if that was taken. */
    fun restoreFromTrash(folderName: String): String = restoreFromTrash(trashFolder(folderName))

    /** Deletes one trashed project for good. Returns whether it was there. */
    fun deleteFromTrash(folderName: String): Boolean = trashFolder(folderName).deleteRecursively()

    /** Deletes everything in the trash for good. Returns how many projects went. */
    fun emptyTrash(): Int = trashed().count { it.deleteRecursively() }

    /** [name] as a folder directly inside the trash, and nothing else: a name cannot reach out of it. */
    private fun trashFolder(name: String): File {
        val trash = File(stateDir, "trash").canonicalFile
        val folder = File(trash, name).canonicalFile
        require(folder.parentFile == trash && folder.isDirectory) { "not in the trash: $name" }
        return folder
    }

    fun trashed(): List<File> =
        File(stateDir, "trash").listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            .orEmpty()

    companion object {
        private const val TITLE_SCAN_LINES = 50

        /** `option "title" "..."`, with whatever follows it (a comment, trailing spaces) ignored. */
        private val ESCAPE = Regex("""\\(.)""")
        private val TITLE_OPTION = Regex("""^\s*option\s+"title"\s+"((?:[^"\\]|\\.)*)"""")

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
