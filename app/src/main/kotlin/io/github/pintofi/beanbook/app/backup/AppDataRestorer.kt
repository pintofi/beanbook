package io.github.pintofi.beanbook.app.backup

import io.github.pintofi.beanbook.core.data.AppDataGroup
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.Json
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.TemplateStore
import java.io.File

/** What restoring would do with one thing in a backup. */
enum class RestoreStatus {
    /** Nothing of that name is here yet. */
    NEW,

    /** Something of the same name is here already. */
    CLASH,

    /** This app cannot read it (a format from a newer version); it is listed but not restored. */
    UNREADABLE,
}

/** What to do when a template or a script has the name of one that is here already. */
enum class Clash(val label: String) {
    KEEP_MINE("Keep mine"),
    REPLACE("Replace with the backup's"),
    KEEP_BOTH("Keep both"),
}

/** One line of the restore preview: a choice the person can tick. */
data class RestoreItem(
    val key: String,
    val group: AppDataGroup,
    val title: String,
    val detail: String,
    val status: RestoreStatus,
) {
    val selectable: Boolean get() = status != RestoreStatus.UNREADABLE
}

/** What applying did, in numbers and in words for the person. */
data class AppDataApplied(val count: Int, val needsRestart: Boolean, val notes: List<String>) {
    fun describe(): String? {
        if (count == 0 && notes.isEmpty()) return null
        val head = if (count == 0) null else "Brought back $count item${if (count == 1) "" else "s"} of app data."
        return listOfNotNull(head, *notes.toTypedArray()).joinToString(" ")
    }
}

/**
 * Previews and applies the app data of a backup that has already been verified and staged
 * (`RestorePlan.stage`). Only what the person ticked is applied. Nothing in it is ever run: a script comes back
 * as a saved script, and the plugins that travel inside the project were turned off by `RestoredPlugins`.
 *
 * Writes go to the places the app already reads, by name, and nowhere else: [AppDataGroup] decides which paths
 * a backup may hold, and this class only reads those from the staging folder.
 */
class AppDataRestorer(private val workspace: ProjectWorkspace, private val stateDir: File) {

    private val templates = TemplateStore(workspace)

    private fun stateFile(name: String) = File(stateDir, name)

    /** The items a backup holds, as [projectId] would see them: which are new and which clash. */
    fun preview(staging: File, projectId: String): List<RestoreItem> = buildList {
        val templateFile = File(staging, "templates.templates")
        if (templateFile.isFile) {
            val found = templates.decode(templateFile.readText())
            if (found == null) {
                add(
                    RestoreItem(
                        "templates", AppDataGroup.TEMPLATES, "Entry templates",
                        "Saved by a newer version of the app. Not restored.", RestoreStatus.UNREADABLE,
                    ),
                )
            } else {
                val mine = templates.load(projectId).map { it.name.lowercase() }.toSet()
                found.forEach { template ->
                    add(
                        RestoreItem(
                            "template:${template.name}", AppDataGroup.TEMPLATES, template.name,
                            if (template.postings.size == 1) "1 posting" else "${template.postings.size} postings",
                            if (template.name.lowercase() in mine) RestoreStatus.CLASH else RestoreStatus.NEW,
                        ),
                    )
                }
            }
        }
        File(staging, "colour").takeIf { it.isFile }?.let {
            val name = it.readText().trim().lowercase()
            add(RestoreItem("colour", AppDataGroup.COLOUR, "This project's colour", name, RestoreStatus.NEW))
        }
        for ((name, title) in AppDataSource.PREFERENCES) {
            if (File(staging, "preferences/$name").isFile) {
                val status = if (stateFile(name).isFile) RestoreStatus.CLASH else RestoreStatus.NEW
                add(RestoreItem("pref:$name", AppDataGroup.PREFERENCES, title, "", status))
            }
        }
        val scripts = File(staging, "scripts").listFiles().orEmpty().filter { it.isFile && it.extension == "py" }
        scripts.sortedBy { it.name }.forEach { script ->
            val exists = File(stateDir, "scripts/${script.name}").isFile
            val hash = BackupArchive.sha256(script).take(8)
            add(
                RestoreItem(
                    "script:${script.name}", AppDataGroup.SCRIPTS, script.nameWithoutExtension,
                    "A program. Comes back saved and is never run. $hash",
                    if (exists) RestoreStatus.CLASH else RestoreStatus.NEW,
                ),
            )
        }
        remote(staging)?.let { (address, _) ->
            val status = if (stateFile(AppDataSource.REMOTE_ADDRESS).isFile) RestoreStatus.CLASH else RestoreStatus.NEW
            add(RestoreItem("remote", AppDataGroup.REMOTE, "Remote server address and user name", address, status))
        }
    }

    /** Brings back the items in [chosen] (keys from [preview]) into [projectId], settling name clashes by [clash]. */
    fun apply(staging: File, projectId: String, chosen: Set<String>, clash: Clash): AppDataApplied {
        val notes = mutableListOf<String>()
        var count = applyTemplates(staging, projectId, chosen, clash)
        count += applyScripts(staging, chosen, clash)
        val prefs = applyPreferences(staging, chosen)
        val colour = applyColour(staging, projectId, chosen, notes)
        val server = applyRemote(staging, chosen, notes)
        return AppDataApplied(count + prefs + colour + server, prefs + colour + server > 0, notes)
    }

    private fun applyTemplates(staging: File, projectId: String, chosen: Set<String>, clash: Clash): Int {
        val wanted = chosen.filter { it.startsWith("template:") }.map { it.removePrefix("template:") }.toSet()
        val file = File(staging, "templates.templates")
        if (wanted.isEmpty() || !file.isFile) return 0
        val mine = templates.load(projectId).map { it.name.lowercase() }.toMutableSet()
        var count = 0
        templates.decode(file.readText()).orEmpty().filter { it.name in wanted }.forEach { template ->
            val exists = template.name.lowercase() in mine
            when {
                !exists || clash == Clash.REPLACE -> templates.save(projectId, template)
                clash == Clash.KEEP_BOTH -> {
                    val name = uniqueName(template.name, mine, " (restored)")
                    templates.save(projectId, template.copy(name = name))
                    mine += name.lowercase()
                }
                else -> return@forEach
            }
            count++
        }
        return count
    }

    private fun applyScripts(staging: File, chosen: Set<String>, clash: Clash): Int {
        var count = 0
        chosen.filter { it.startsWith("script:") }.map { it.removePrefix("script:") }.forEach { name ->
            val source = File(staging, "scripts/$name")
            if (!AppDataGroup.SCRIPTS.accepts("scripts/$name") || !source.isFile) return@forEach
            val folder = File(stateDir, "scripts").apply { mkdirs() }
            var target = File(folder, name)
            if (target.exists()) {
                when (clash) {
                    Clash.KEEP_MINE -> return@forEach
                    Clash.REPLACE -> Unit
                    Clash.KEEP_BOTH -> {
                        val taken = folder.list().orEmpty().map { it.lowercase() }.toSet()
                        target = File(folder, uniqueName(name.removeSuffix(".py"), taken, "-restored", ".py"))
                    }
                }
            }
            source.copyTo(target, overwrite = true)
            count++
        }
        return count
    }

    private fun applyPreferences(staging: File, chosen: Set<String>): Int {
        var count = 0
        for ((name, _) in AppDataSource.PREFERENCES) {
            val source = File(staging, "preferences/$name")
            if ("pref:$name" !in chosen || !source.isFile) continue
            val text = source.readText()
            stateDir.mkdirs()
            if (name == AppDataSource.APPEARANCE) {
                // This device's per-project colours stay: the restored file only holds the global lines.
                val colours = stateFile(name).takeIf { it.isFile }?.readLines().orEmpty()
                    .filter { it.startsWith(AppDataSource.PROJECT_PREFIX) }
                stateFile(name).writeText((text.trimEnd().lines() + colours).joinToString("\n") + "\n")
            } else {
                stateFile(name).writeText(text)
            }
            count++
        }
        return count
    }

    private fun applyColour(staging: File, projectId: String, chosen: Set<String>, notes: MutableList<String>): Int {
        val file = File(staging, "colour")
        if ("colour" !in chosen || !file.isFile) return 0
        val value = file.readText().trim()
        if (!COLOUR_NAME.matches(value)) {
            notes += "The project colour in the backup was not recognised, so it was left out."
            return 0
        }
        val prefix = "${AppDataSource.PROJECT_PREFIX}$projectId="
        val lines = stateFile(AppDataSource.APPEARANCE).takeIf { it.isFile }?.readLines().orEmpty()
            .filterNot { it.startsWith(prefix) }
        stateDir.mkdirs()
        stateFile(AppDataSource.APPEARANCE).writeText((lines + "$prefix$value").joinToString("\n") + "\n")
        return 1
    }

    private fun applyRemote(staging: File, chosen: Set<String>, notes: MutableList<String>): Int {
        if ("remote" !in chosen) return 0
        val (address, user) = remote(staging) ?: return 0
        if (!address.startsWith("https://") && !address.startsWith("http://")) {
            notes += "The remote server address in the backup was not usable, so it was left out."
            return 0
        }
        stateDir.mkdirs()
        stateFile(AppDataSource.REMOTE_ADDRESS).writeText(address)
        val userFile = stateFile(AppDataSource.REMOTE_USER)
        if (user.isBlank()) userFile.delete() else userFile.writeText(user)
        notes += "The remote server's password is not in a backup: enter it again to connect."
        return 1
    }

    private fun remote(staging: File): Pair<String, String>? {
        val file = File(staging, "remote.json").takeIf { it.isFile } ?: return null
        val root = runCatching { Json.parse(file.readText()) }.getOrNull() as? Json.Obj ?: return null
        val address = root.string("address")?.trim().orEmpty()
        return if (address.isEmpty()) null else address to root.string("username").orEmpty()
    }

    /** [base] with [suffix] (and a number when that is taken too), not among [taken]. */
    private fun uniqueName(base: String, taken: Set<String>, suffix: String, extension: String = ""): String {
        var candidate = "$base$suffix$extension"
        var n = 2
        while (candidate.lowercase() in taken) candidate = "$base$suffix $n$extension".also { n++ }
        return candidate
    }

    private companion object {
        /** `DYNAMIC` or the name of an accent palette: capitals only, so the value is never a path or a line. */
        val COLOUR_NAME = Regex("[A-Z_]{1,24}")
    }
}
