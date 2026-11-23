package io.github.pintofi.beanbook.app.backup

import io.github.pintofi.beanbook.app.plugins.SavedScripts
import io.github.pintofi.beanbook.core.data.AppDataGroup
import io.github.pintofi.beanbook.core.data.AppDataPayload
import io.github.pintofi.beanbook.core.data.CatalogueInstaller
import io.github.pintofi.beanbook.core.data.PluginStore
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.TemplateStore
import java.io.File

/** One thing a person can choose to put in a backup, or to bring back from one. */
data class AppDataItem(
    /** Stable name of the choice: `template:Rent`, `script:check.py`, `pref:appearance`, `colour`, `remote`. */
    val key: String,
    val group: AppDataGroup,
    val title: String,
    val detail: String,
    val bytes: Long,
    /** Ticked when the list is first shown. Code and device-specific choices start off. */
    val onByDefault: Boolean,
)

/**
 * What Beanbook keeps beside the ledger that a backup can carry, and the files that carry it.
 *
 * Everything here is chosen one item at a time. Nothing is read from a place a secret lives: the remote
 * server's password, the scheduled backup's passphrase, the Share on Wi-Fi sign-in and every other
 * device-specific setting are not reachable from this class at all, and `AppDataGroup` only accepts paths
 * for the groups it lists.
 *
 * [stateDir] is the app's `state` folder, where its small settings files live.
 */
class AppDataSource(
    private val workspace: ProjectWorkspace,
    private val stateDir: File,
    private val catalogueRoot: File = File(stateDir.parentFile, "catalogue"),
) {

    private val templates = TemplateStore(workspace)
    private val scripts = SavedScripts(stateDir)
    private val catalogue = CatalogueInstaller(catalogueRoot)

    private fun stateFile(name: String) = File(stateDir, name)

    /** The lines of the appearance file that are about one project's colour. */
    private fun colourLine(projectId: String): String? =
        stateFile(APPEARANCE).takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("$PROJECT_PREFIX$projectId=") }

    /** What can be backed up for [projectId], in the order the checklist shows it. */
    fun available(projectId: String): List<AppDataItem> = buildList {
        // Plugins already travel with the project, so they start ticked; unticking one leaves it out.
        runCatching { PluginStore(workspace.projectDir(projectId)).list() }.getOrDefault(emptyList()).forEach {
            add(
                AppDataItem(
                    "plugin:${it.id}", AppDataGroup.PLUGINS, it.name,
                    "A plugin. Comes back turned off. ${it.installedSha256.take(8)}", it.sizeBytes, true,
                ),
            )
        }
        runCatching { catalogue.installed() }.getOrDefault(emptyMap()).toSortedMap().forEach { (id, version) ->
            add(
                AppDataItem(
                    "catalogue:$id", AppDataGroup.CATALOGUE, id,
                    "Catalogue plugin $version. Only its name is kept; install it again from Plugins.", 0L, true,
                ),
            )
        }
        templates.load(projectId).forEach { template ->
            add(
                AppDataItem(
                    key = "template:${template.name}",
                    group = AppDataGroup.TEMPLATES,
                    title = template.name,
                    detail = if (template.postings.size == 1) "1 posting" else "${template.postings.size} postings",
                    bytes = templates.encode(listOf(template)).toByteArray().size.toLong(),
                    onByDefault = true,
                ),
            )
        }
        colourLine(projectId)?.let {
            add(
                AppDataItem(
                    "colour", AppDataGroup.COLOUR, "This project's colour", it.substringAfter('=').lowercase(),
                    it.length.toLong(), onByDefault = true,
                ),
            )
        }
        for ((name, title) in PREFERENCES) {
            val text = preferenceText(name) ?: continue
            add(AppDataItem("pref:$name", AppDataGroup.PREFERENCES, title, "", text.toByteArray().size.toLong(), false))
        }
        scripts.list().forEach { script ->
            add(
                AppDataItem(
                    key = "script:${script.id}",
                    group = AppDataGroup.SCRIPTS,
                    title = script.name,
                    detail = "A program. Comes back saved, never run. ${script.sha256.take(8)}",
                    bytes = script.file.length(),
                    onByDefault = false,
                ),
            )
        }
        val address = stateFile(REMOTE_ADDRESS).takeIf { it.isFile }?.readText()?.trim()
        if (!address.isNullOrEmpty()) {
            add(
                AppDataItem(
                    "remote", AppDataGroup.REMOTE, "Remote server address and user name",
                    "$address. The password is never included.", address.length.toLong(), false,
                ),
            )
        }
    }

    /** The plugins in the project that are not ticked in [chosen], to be left out of the backup. */
    fun pluginsLeftOut(projectId: String, chosen: Set<String>): Set<String> =
        runCatching { PluginStore(workspace.projectDir(projectId)).list() }.getOrDefault(emptyList())
            .map { it.id }.filter { "plugin:$it" !in chosen }.toSet()

    /** The files that carry the items in [chosen] (keys from [available]). A key that is not offered is ignored. */
    fun payloads(projectId: String, chosen: Set<String>): List<AppDataPayload> = buildList {
        val names = chosen.filter { it.startsWith("template:") }.map { it.removePrefix("template:") }.toSet()
        if (names.isNotEmpty()) {
            val picked = templates.load(projectId).filter { it.name in names }
            if (picked.isNotEmpty()) {
                val text = templates.encode(picked)
                add(AppDataPayload(AppDataGroup.TEMPLATES, "templates.templates", text.toByteArray()))
            }
        }
        val kept = runCatching { catalogue.installed() }.getOrDefault(emptyMap())
            .filterKeys { "catalogue:$it" in chosen }.toSortedMap()
        if (kept.isNotEmpty()) {
            val list = kept.entries.joinToString(",") { "{\"id\":${quote(it.key)},\"version\":${quote(it.value)}}" }
            add(AppDataPayload(AppDataGroup.CATALOGUE, "catalogue.json", "{\"plugins\":[$list]}\n".toByteArray()))
        }
        if ("colour" in chosen) {
            colourLine(projectId)?.let {
                add(AppDataPayload(AppDataGroup.COLOUR, "colour", it.substringAfter('=').toByteArray()))
            }
        }
        for ((name, _) in PREFERENCES) {
            if ("pref:$name" in chosen) {
                preferenceText(name)?.let {
                    add(AppDataPayload(AppDataGroup.PREFERENCES, "preferences/$name", it.toByteArray()))
                }
            }
        }
        scripts.list().filter { "script:${it.id}" in chosen }.forEach { script ->
            val path = "scripts/${script.id}"
            if (AppDataGroup.SCRIPTS.accepts(path) && script.file.length() <= MAX_BYTES) {
                add(AppDataPayload(AppDataGroup.SCRIPTS, path, script.file.readBytes()))
            }
        }
        if ("remote" in chosen) {
            val address = stateFile(REMOTE_ADDRESS).takeIf { it.isFile }?.readText()?.trim().orEmpty()
            val user = stateFile(REMOTE_USER).takeIf { it.isFile }?.readText()?.trim().orEmpty()
            if (address.isNotEmpty()) {
                add(AppDataPayload(AppDataGroup.REMOTE, "remote.json", remoteJson(address, user).toByteArray()))
            }
        }
    }

    /**
     * The text of a preference file as it travels. The appearance file also holds every project's colour; those
     * lines stay behind, because a colour belongs to a project id and travels as its own choice.
     */
    private fun preferenceText(name: String): String? {
        val file = stateFile(name).takeIf { it.isFile } ?: return null
        val lines = file.readLines()
        val kept = if (name == APPEARANCE) lines.filterNot { it.startsWith(PROJECT_PREFIX) } else lines
        return kept.joinToString("\n").takeIf { it.isNotBlank() }?.plus("\n")
    }

    companion object {
        const val APPEARANCE = "appearance"
        const val PROJECT_PREFIX = "project."
        const val REMOTE_ADDRESS = "remote-address"
        const val REMOTE_USER = "remote-username"
        private const val MAX_BYTES = 1024 * 1024

        /** The preference files that can travel, with what the checklist calls each. */
        val PREFERENCES = listOf(
            "appearance" to "Appearance: theme, contrast, text size, motion",
            "home-cards" to "Home layout",
            "balances-basis" to "Balances: units, cost or market value",
        )

        fun remoteJson(address: String, user: String) =
            "{\"address\":${quote(address)},\"username\":${quote(user)}}\n"

        private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
