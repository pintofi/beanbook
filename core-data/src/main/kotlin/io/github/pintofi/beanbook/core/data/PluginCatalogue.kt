package io.github.pintofi.beanbook.core.data

/**
 * The curated plugin catalogue shipped inside the app: `catalog/plugins.json`.
 *
 * Every Beancount plugin and Fava extension listed on awesome-beancount, plus any the project
 * added on purpose, each with the result of running it through the compatibility harness and,
 * where it can be installed, exactly what installing it downloads -- one archive, its SHA-256,
 * which paths inside it are the code, and which Python packages it needs.
 * `tools/plugin-compat/catalogue.py` writes those pins and checks the list against upstream.
 */
data class PluginCatalogue(val entries: List<Entry>) {

    data class Entry(
        val id: String,
        val name: String,
        /** `beancount-plugin`, `fava-extension`, `library` or `beancount-importer`. */
        val kind: String,
        val description: String,
        val repo: String,
        val licence: String,
        val licenceNote: String?,
        /** The harness's verdict: `supported`, `partial`, `needs-config`, `unsupported`, `untested`… */
        val status: String,
        val statusReason: String?,
        val install: Install?,
    ) {
        val isExtension: Boolean get() = kind == "fava-extension"
    }

    data class Install(
        val source: String,
        val archive: String,
        val sha256: String,
        val version: String,
        /** A directory prefix to remove from archive member names (a GitHub zip's top folder). */
        val strip: String,
        /** Directories or files, relative to [strip], that are the importable code. */
        val paths: List<String>,
        /** When a path is the archive root, the name it is installed under. */
        val importName: String?,
        val requires: List<String>,
        /** What a ledger names to use it: `plugin "…"` or `custom "fava-extension" "…"`. */
        val modules: List<String>,
    )

    companion object {
        /** Parses the catalogue file, skipping entries it cannot read rather than failing all. */
        fun parse(text: String): PluginCatalogue {
            val root = Json.parse(text) as? Json.Obj ?: return PluginCatalogue(emptyList())
            val entries = root.array("entries").orEmpty().mapNotNull { item ->
                val obj = item as? Json.Obj ?: return@mapNotNull null
                Entry(
                    id = obj.string("id") ?: return@mapNotNull null,
                    name = obj.string("name") ?: obj.string("id")!!,
                    kind = obj.string("kind").orEmpty(),
                    description = obj.string("description").orEmpty(),
                    repo = obj.string("repo").orEmpty(),
                    licence = obj.string("license") ?: "unstated",
                    licenceNote = obj.string("licence_note"),
                    status = obj.string("status") ?: "untested",
                    statusReason = obj.obj("tested")?.string("reason"),
                    install = obj.obj("install")?.let(::install),
                )
            }
            return PluginCatalogue(entries)
        }

        private fun install(obj: Json.Obj): Install? {
            fun strings(name: String) = obj.array(name).orEmpty().mapNotNull { (it as? Json.Str)?.value }
            val sha = obj.string("sha256")?.takeIf { SHA256.matches(it) } ?: return null
            val archive = obj.string("archive")?.takeIf { it.startsWith("https://") } ?: return null
            return Install(
                source = obj.string("source").orEmpty(),
                archive = archive,
                sha256 = sha,
                version = obj.string("version").orEmpty(),
                strip = obj.string("strip").orEmpty(),
                paths = strings("paths"),
                importName = obj.string("import_name"),
                requires = strings("requires"),
                modules = strings("modules"),
            )
        }

        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
