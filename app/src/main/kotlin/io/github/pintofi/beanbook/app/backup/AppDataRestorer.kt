package io.github.pintofi.beanbook.app.backup

import io.github.pintofi.beanbook.app.remote.KeystoreSecret
import io.github.pintofi.beanbook.core.data.AppDataGroup
import io.github.pintofi.beanbook.core.data.CatalogueInstaller
import io.github.pintofi.beanbook.core.data.Json
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.TemplateStore
import io.github.pintofi.beanbook.engine.remote.RemoteAddress
import java.io.File

/** What restoring would do with one thing in a backup. */
enum class RestoreStatus {
    /** Nothing of that name is here yet. */
    NEW,

    /** Something of the same name is here already. */
    CLASH,

    /** This app cannot read it (a format from a newer version); it is listed but not restored. */
    UNREADABLE,

    /** Only a reminder: shown so it is not forgotten, and never applied by a restore. */
    REMINDER,

    /** Already installed here, so there is nothing to bring back. */
    INSTALLED,

    /** A single setting that is set here already; ticking it replaces what is set. */
    REPLACES,
}

/** What to do when a template or a script has the name of one that is here already. */
enum class Clash(val label: String) {
    KEEP_MINE("Keep mine"),
    REPLACE("Use the backup's"),
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
    val selectable: Boolean get() =
        status == RestoreStatus.NEW || status == RestoreStatus.CLASH || status == RestoreStatus.REPLACES
}

/** What applying did, in numbers and in words for the person. */
data class AppDataApplied(
    val count: Int,
    val needsRestart: Boolean,
    val notes: List<String>,
    /** Catalogue plugins the backup listed, to be installed again by the person's tap. */
    val catalogue: List<String> = emptyList(),
) {
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
class AppDataRestorer(
    private val workspace: ProjectWorkspace,
    private val stateDir: File,
    catalogueRoot: File = File(stateDir.parentFile, "catalogue"),
) {
    private val catalogue = CatalogueInstaller(catalogueRoot)

    /** The catalogue plugins installed here now, by id. */
    private fun installedHere(): Set<String> = runCatching { catalogue.installed().keys }.getOrDefault(emptySet())


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
        val here = installedHere()
        catalogued(staging).forEach { (id, version) ->
            val installed = id in here
            add(
                RestoreItem(
                    "catalogue:$id", AppDataGroup.CATALOGUE, id,
                    if (installed) "Already installed here." else "Was installed, $version. Install it again.",
                    if (installed) RestoreStatus.INSTALLED else RestoreStatus.REMINDER,
                ),
            )
        }
        File(staging, "colour").takeIf { it.isFile }?.let {
            val name = it.readText().trim().lowercase()
            val set = appearanceLines().any { l -> l.startsWith("${AppDataSource.PROJECT_PREFIX}$projectId=") }
            add(
                RestoreItem(
                    "colour", AppDataGroup.COLOUR, "This project's colour", name,
                    if (set) RestoreStatus.REPLACES else RestoreStatus.NEW,
                ),
            )
        }
        for ((name, title) in AppDataSource.PREFERENCES) {
            if (File(staging, "preferences/$name").isFile) {
                val status = if (stateFile(name).isFile) RestoreStatus.REPLACES else RestoreStatus.NEW
                add(RestoreItem("pref:$name", AppDataGroup.PREFERENCES, title, "", status))
            }
        }
        val scripts = File(staging, "scripts").listFiles().orEmpty().filter { it.isFile && it.extension == "py" }
        scripts.sortedBy { it.name }.forEach { script ->
            val exists = File(stateDir, "scripts/${script.name}").isFile
            add(
                RestoreItem(
                    "script:${script.name}", AppDataGroup.SCRIPTS, script.nameWithoutExtension,
                    "Comes back saved",
                    if (exists) RestoreStatus.CLASH else RestoreStatus.NEW,
                ),
            )
        }
        remote(staging)?.let { (address, _) ->
            val set = stateFile(AppDataSource.REMOTE_ADDRESS).isFile
            val status = if (set) RestoreStatus.REPLACES else RestoreStatus.NEW
            add(RestoreItem("remote", AppDataGroup.REMOTE, "Remote server address and user name", address, status))
        }
    }

    /**
     * Brings back the items in [chosen] (keys from [preview]) into [projectId]. [clash] settles a template or a
     * script that has the name of one already here; a preference, the colour and the server address are replaced
     * when ticked, which is what ticking them says. Each kind is tried on its own, so one that cannot be written
     * is named and the others still come back.
     */
    fun apply(staging: File, projectId: String, chosen: Set<String>, clash: Clash): AppDataApplied {
        val notes = mutableListOf<String>()
        var count = 0
        var changedSettings = 0
        fun attempt(what: String, block: () -> Int): Int =
            runCatching(block).getOrElse {
                notes += "$what could not be brought back: ${it.message ?: it.javaClass.simpleName}."
                0
            }
        count += attempt("Templates") { applyTemplates(staging, projectId, chosen, clash) }
        count += attempt("Scripts") { applyScripts(staging, chosen, clash) }
        changedSettings += attempt("Preferences") { applyPreferences(staging, chosen) }
        changedSettings += attempt("The project colour") { applyColour(staging, projectId, chosen, notes) }
        changedSettings += attempt("The server address") { applyRemote(staging, chosen, notes) }
        val missing = catalogued(staging).map { it.first }.filterNot { it in installedHere() }
        if (missing.isNotEmpty()) {
            notes += "Catalogue plugins that were installed: ${missing.joinToString(", ")}. " +
                "Install them again from Plugins."
        }
        return AppDataApplied(count + changedSettings, changedSettings > 0, notes, missing)
    }

    private fun applyTemplates(staging: File, projectId: String, chosen: Set<String>, clash: Clash): Int {
        val wanted = chosen.filter { it.startsWith("template:") }.map { it.removePrefix("template:") }.toSet()
        val file = File(staging, "templates.templates")
        if (wanted.isEmpty() || !file.isFile) return 0
        val mine = templates.load(projectId).map { it.name.lowercase() }.toMutableSet()
        var count = 0
        templates.decode(file.readText()).orEmpty()
            .filter { it.name in wanted && it.name.isNotBlank() }
            .forEach { template ->
                val exists = template.name.lowercase() in mine
                when {
                    !exists || clash == Clash.REPLACE -> {
                        templates.save(projectId, template)
                        mine += template.name.lowercase()
                    }
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
                        target = File(folder, uniqueScriptName(name.removeSuffix(".py"), taken))
                    }
                }
            }
            source.copyTo(target, overwrite = true)
            count++
        }
        return count
    }

    /** The appearance file as key=value lines; the first version of it, two bare lines, is converted. */
    private fun appearanceLines(): List<String> {
        val lines = stateFile(AppDataSource.APPEARANCE).takeIf { it.isFile }?.readLines().orEmpty()
            .filter { it.isNotBlank() }
        if (lines.isEmpty() || lines.any { '=' in it }) return lines
        return listOfNotNull(lines.getOrNull(0)?.let { "theme=$it" }, lines.getOrNull(1)?.let { "dynamic=$it" })
    }

    private fun applyPreferences(staging: File, chosen: Set<String>): Int {
        var count = 0
        for ((name, _) in AppDataSource.PREFERENCES) {
            val source = File(staging, "preferences/$name")
            if ("pref:$name" !in chosen || !source.isFile) continue
            stateDir.mkdirs()
            if (name == AppDataSource.APPEARANCE) {
                // This device's per-project colours stay, and the backup's are not taken: a colour belongs to a
                // project and comes back as its own choice.
                val restored = source.readLines().filterNot { it.startsWith(AppDataSource.PROJECT_PREFIX) }
                val colours = appearanceLines().filter { it.startsWith(AppDataSource.PROJECT_PREFIX) }
                stateFile(name).writeText((restored.filter { it.isNotBlank() } + colours).joinToString("\n") + "\n")
            } else {
                stateFile(name).writeText(source.readText())
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
        val lines = appearanceLines().filterNot { it.startsWith(prefix) }
        stateDir.mkdirs()
        stateFile(AppDataSource.APPEARANCE).writeText((lines + "$prefix$value").joinToString("\n") + "\n")
        return 1
    }

    private fun applyRemote(staging: File, chosen: Set<String>, notes: MutableList<String>): Int {
        if ("remote" !in chosen) return 0
        val (given, user) = remote(staging) ?: return 0
        // The same reading of an address the Remote screen uses: a web address, no sign-in inside it.
        val address = RemoteAddress.parse(given).getOrNull()?.base
        if (address == null) {
            notes += "The remote server address in the backup was not usable, so it was left out."
            return 0
        }
        stateDir.mkdirs()
        val sameServer = stateFile(AppDataSource.REMOTE_ADDRESS).takeIf { it.isFile }?.readText()?.trim() == address &&
            stateFile(AppDataSource.REMOTE_USER).takeIf { it.isFile }?.readText()?.trim().orEmpty() == user.trim()
        // A saved password belongs to the server and user it was typed for; it must not follow another address.
        if (!sameServer) KeystoreSecret(stateFile("remote-password")).clear()
        stateFile(AppDataSource.REMOTE_ADDRESS).writeText(address)
        val userFile = stateFile(AppDataSource.REMOTE_USER)
        if (user.isBlank()) userFile.delete() else userFile.writeText(user.trim())
        if (!stateFile("remote-password").exists()) {
            notes += "The remote server's password is not in a backup: enter it again to connect."
        }
        return 1
    }

    /** The catalogue plugins the backup lists, as name and version. Read from a verified file, still checked. */
    private fun catalogued(staging: File): List<Pair<String, String>> {
        val file = File(staging, "catalogue.json").takeIf { it.isFile } ?: return emptyList()
        val root = runCatching { Json.parse(file.readText()) }.getOrNull() as? Json.Obj ?: return emptyList()
        return root.array("plugins").orEmpty().mapNotNull { item ->
            val entry = item as? Json.Obj ?: return@mapNotNull null
            val id = entry.string("id")?.takeIf { CATALOGUE_ID.matches(it) } ?: return@mapNotNull null
            id to entry.string("version").orEmpty().take(40)
        }
    }

    private fun remote(staging: File): Pair<String, String>? {
        val file = File(staging, "remote.json").takeIf { it.isFile } ?: return null
        val root = runCatching { Json.parse(file.readText()) }.getOrNull() as? Json.Obj ?: return null
        val address = root.string("address")?.trim().orEmpty()
        return if (address.isEmpty()) null else address to root.string("username").orEmpty()
    }

    /** [base] with [suffix] (and a number when that is taken too), not among [taken]. */
    private fun uniqueName(base: String, taken: Set<String>, suffix: String): String {
        var candidate = "$base$suffix"
        var n = 2
        while (candidate.lowercase() in taken) candidate = "$base${suffix.removeSuffix(")")} ${n++})"
        return candidate
    }

    /** A script file name for a copy: no spaces, and short enough that the copy can be backed up in turn. */
    private fun uniqueScriptName(base: String, taken: Set<String>): String {
        val stem = base.take(MAX_SCRIPT_STEM)
        var candidate = "$stem-restored.py"
        var n = 2
        while (candidate.lowercase() in taken) candidate = "$stem-restored-${n++}.py"
        return candidate
    }

    private companion object {
        /** `DYNAMIC` or the name of an accent palette: capitals only, so the value is never a path or a line. */
        val COLOUR_NAME = Regex("[A-Z_]{1,24}")
        val CATALOGUE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")

        /** A script's file name is at most 64 characters (see AppDataGroup); room is left for "-restored-99.py". */
        const val MAX_SCRIPT_STEM = 64 - "-restored-99.py".length
    }
}
