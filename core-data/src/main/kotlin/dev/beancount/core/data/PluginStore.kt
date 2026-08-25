package dev.beancount.core.data

import dev.beancount.core.domain.PluginKind
import dev.beancount.core.domain.PluginPolicy
import dev.beancount.core.domain.PluginPreflight
import dev.beancount.core.domain.PluginScan
import dev.beancount.core.domain.PluginVerdict
import dev.beancount.core.domain.judge
import java.io.File

/**
 * A plugin the user has installed, and everything needed to answer "where did this come
 * from and has it changed?".
 *
 * [sourceLabel] is what the user picked it from — a display string, not a path to reopen.
 *
 * There are two checksums, and they are not interchangeable. [sourceSha256] is the file the
 * user chose, so it can be compared against what a project published. [installedSha256] is
 * the copy that will actually run, hashed the same way [PluginStore.verify] hashes it later.
 *
 * They were one field to begin with, which was a bug: install hashed the candidate and
 * verify hashed the installed tree, so every plugin reported itself as changed the moment it
 * was installed. A change alert that is always on is a change alert nobody reads.
 */
data class InstalledPlugin(
    val id: String,
    val name: String,
    val kind: PluginKind,
    val sourceSha256: String,
    val installedSha256: String,
    val sizeBytes: Long,
    val installedAtEpochMs: Long,
    val sourceLabel: String,
    val installedUnderPolicy: String,
    val enabled: Boolean,
    val modules: List<String>,
) {
    internal fun toJson(): Json.Obj = Json.Obj(
        mapOf(
            "id" to Json.Str(id),
            "name" to Json.Str(name),
            "kind" to Json.Str(kind.name),
            "source_sha256" to Json.Str(sourceSha256),
            "installed_sha256" to Json.Str(installedSha256),
            "size" to Json.Num(sizeBytes.toDouble()),
            "installed_at" to Json.Num(installedAtEpochMs.toDouble()),
            "source" to Json.Str(sourceLabel),
            "policy" to Json.Str(installedUnderPolicy),
            "enabled" to Json.Bool(enabled),
            "modules" to Json.Arr(modules.map { Json.Str(it) }),
        ),
    )

    internal companion object {
        fun fromJson(obj: Json.Obj): InstalledPlugin? {
            val kind = runCatching { PluginKind.valueOf(obj.string("kind") ?: return null) }
                .getOrNull() ?: return null
            return InstalledPlugin(
                id = obj.string("id") ?: return null,
                name = obj.string("name") ?: return null,
                kind = kind,
                sourceSha256 = obj.string("source_sha256") ?: return null,
                installedSha256 = obj.string("installed_sha256") ?: return null,
                sizeBytes = obj.long("size") ?: return null,
                installedAtEpochMs = obj.long("installed_at") ?: return null,
                sourceLabel = obj.string("source") ?: return null,
                installedUnderPolicy = obj.string("policy") ?: return null,
                enabled = (obj["enabled"] as? Json.Bool)?.value ?: false,
                modules = obj.array("modules").orEmpty().mapNotNull { (it as? Json.Str)?.value },
            )
        }
    }
}

/** The outcome of trying to install something. */
sealed interface InstallResult {
    data class Installed(val plugin: InstalledPlugin) : InstallResult
    data class Refused(val verdict: PluginVerdict.Rejected, val scan: PluginScan) : InstallResult
    data class Failed(val reason: String) : InstallResult
}

/** Whether what is on disk is still what the user approved. */
sealed interface PluginIntegrity {
    data object Unchanged : PluginIntegrity
    data class Changed(val nowSha256: String) : PluginIntegrity
    data object Missing : PluginIntegrity
}

/**
 * Installed plugins for one project, on disk.
 *
 * Nothing is written until the candidate has been read and judged: a refusal leaves the
 * store exactly as it was, so a rejected plugin cannot leave half a package behind for a
 * later load to trip over.
 *
 * Plugins are **disabled when installed**. Approving an install and approving execution are
 * different decisions, and running user code is the one that deserves its own yes
 * (docs/threat-model.md §3.2).
 */
class PluginStore(private val projectRoot: File) {

    private val pluginsDir = File(projectRoot, "plugins")
    private val indexFile = File(pluginsDir, "installed.json")

    fun list(): List<InstalledPlugin> {
        if (!indexFile.isFile) return emptyList()
        val root = Json.parse(indexFile.readText()) as? Json.Obj ?: return emptyList()
        return root.array("plugins").orEmpty()
            .mapNotNull { InstalledPlugin.fromJson(it as? Json.Obj ?: return@mapNotNull null) }
    }

    fun directoryFor(plugin: InstalledPlugin): File = File(pluginsDir, plugin.id)

    /**
     * Reads [candidate], applies [policy], and installs it only if the policy allows.
     *
     * @param sourceLabel what to show the user as the origin.
     */
    fun install(
        candidate: File,
        policy: PluginPolicy,
        sourceLabel: String,
        now: Long = System.currentTimeMillis(),
    ): InstallResult {
        if (!candidate.exists()) return InstallResult.Failed("that file is no longer there")

        val scan = runCatching { PluginPreflight.scan(candidate) }
            .getOrElse { return InstallResult.Failed(it.message ?: "could not be read") }

        when (val verdict = policy.judge(scan)) {
            is PluginVerdict.Rejected -> return InstallResult.Refused(verdict, scan)
            PluginVerdict.Allowed -> Unit
        }

        val id = identifierFor(scan.name)
        val target = File(pluginsDir, id)
        // Staging then renaming: a copy interrupted halfway must not become a plugin.
        val staging = File(pluginsDir, "$id.installing")
        staging.deleteRecursively()
        staging.mkdirs()

        val copied = runCatching {
            when (scan.kind) {
                PluginKind.SOURCE_FILE -> candidate.copyTo(File(staging, candidate.name))
                PluginKind.DIRECTORY -> candidate.copyRecursively(File(staging, candidate.name))
                PluginKind.ARCHIVE -> extract(candidate, staging)
            }
        }
        if (copied.isFailure) {
            staging.deleteRecursively()
            return InstallResult.Failed(copied.exceptionOrNull()?.message ?: "could not be copied")
        }

        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            return InstallResult.Failed("could not be moved into place")
        }

        // Hashed from what is now on disk, the same way verify() will hash it. Reusing the
        // candidate's own checksum here is what made every plugin look modified.
        val installedSha = runCatching { PluginPreflight.scan(target).sha256 }
            .getOrElse { return InstallResult.Failed("installed copy could not be read back") }

        val plugin = InstalledPlugin(
            id = id,
            name = scan.name,
            kind = scan.kind,
            sourceSha256 = scan.sha256,
            installedSha256 = installedSha,
            sizeBytes = scan.sizeBytes,
            installedAtEpochMs = now,
            sourceLabel = sourceLabel,
            installedUnderPolicy = policy.name,
            enabled = false,
            modules = scan.pythonModules,
        )
        write(list().filterNot { it.id == id } + plugin)
        return InstallResult.Installed(plugin)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        write(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) {
        File(pluginsDir, id).deleteRecursively()
        write(list().filterNot { it.id == id })
    }

    /**
     * Re-reads what is on disk and compares it with what the user approved.
     *
     * This is not tamper-proofing — anything that can rewrite the plugin can rewrite the
     * index. It catches the ordinary case: a file edited or replaced since it was approved,
     * which the user should be told about before it runs again.
     */
    fun verify(plugin: InstalledPlugin): PluginIntegrity {
        val installed = File(pluginsDir, plugin.id)
        if (!installed.exists()) return PluginIntegrity.Missing
        val now = runCatching { PluginPreflight.scan(installed).sha256 }
            .getOrElse { return PluginIntegrity.Missing }
        return if (now == plugin.installedSha256) {
            PluginIntegrity.Unchanged
        } else {
            PluginIntegrity.Changed(now)
        }
    }

    private fun write(plugins: List<InstalledPlugin>) {
        pluginsDir.mkdirs()
        val json = Json.Obj(
            mapOf(
                "schema" to Json.Num(1.0),
                "plugins" to Json.Arr(plugins.sortedBy { it.id }.map { it.toJson() }),
            ),
        )
        val tmp = File(pluginsDir, "installed.json.tmp")
        tmp.writeText(json.write() + "\n")
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun extract(archive: File, into: File) {
        java.util.zip.ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name.replace('\\', '/')
                // Already refused by the scan; checked again because this is the step where
                // a path escape would actually write somewhere it should not.
                check(!PluginPreflight.escapesRoot(name)) { "refusing to extract $name" }
                val out = File(into, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** A folder name derived from the plugin, safe on every filesystem the app touches. */
    private fun identifierFor(name: String): String =
        name.removeSuffix(".py")
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
            .joinToString("")
            .trim('.', '-')
            .take(64)
            .ifEmpty { "plugin" }
}
